package com.example.audio

import com.example.data.model.AudioSettings
import com.example.data.model.NoiseCancellationLevel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Low-latency, CPU-optimized on-device PCM 16-bit microphone DSP processor for mobile gaming speech.
 *
 * Strictly operates ONLY on the user's microphone channel — game audio and teammate audio are never altered here.
 * Features:
 * - Zero heap allocations inside processInPlace()
 * - Adaptive soft-Wiener noise cancellation (OFF / LOW / MEDIUM / HIGH / VOICE_ONLY / AI_DSP)
 * - Adjustable Wind Noise Reduction high-pass filter (variable cutoff 75Hz..220Hz via windReductionStrength)
 * - Close Voice Focus preset (~0.5 ft preference): tuned direct-voice emphasis + smooth attack/hold/release
 *   voice gate with hysteresis so word starts and endings stay natural without harsh clipping
 * - Adjustable Microphone Sensitivity, Voice Isolation Strength, Ambient Noise Reduction & Voice Gate threshold
 */
class VoiceDspProcessor(private val sampleRate: Int = 44100) {

    // 1st-order high-pass filter state (variable wind/rumble cut)
    private var hpfPrevInput = 0f
    private var hpfPrevOutput = 0f

    // Vocal presence emphasis state (1st-order pre-emphasis)
    private var clarityPrev = 0f

    // Adaptive noise floor (RMS in 0..1 linear scale)
    private var noiseFloorRms = 0.008f
    private var gateGain = 1f
    private var gateHoldFrames = 0
    private var gateIsOpen = false
    private var smoothedLevel = 0f
    private var peakHold = 0f
    private var peakHoldFrames = 0

    data class FrameMetrics(
        val normalizedLevel: Float,
        val peakLevel: Float,
        val dbFs: Float,
        val speakingDetected: Boolean,
        val gateOpen: Boolean = speakingDetected
    )

    fun processInPlace(
        samples: ShortArray,
        count: Int,
        settings: AudioSettings,
        lightweightMode: Boolean = false
    ): FrameMetrics {
        if (count <= 0) return FrameMetrics(0f, 0f, -60f, speakingDetected = false, gateOpen = false)

        // Fast path when muted: zero buffer immediately with minimal CPU
        if (!settings.micEnabled) {
            samples.fill(0, 0, count)
            gateGain = 0f
            gateIsOpen = false
            gateHoldFrames = 0
            smoothedLevel *= 0.6f
            peakHold = max(0f, peakHold - 0.06f)
            return FrameMetrics(smoothedLevel, peakHold, -60f, speakingDetected = false, gateOpen = false)
        }

        val sensitivity = settings.micSensitivity.coerceIn(0.4f, 2.0f)

        // 1. Compute raw frame RMS (stride 2 in lightweightMode halves loop ops on 4GB devices)
        val stride = if (lightweightMode) 2 else 1
        var sumSq = 0f
        var sampledCount = 0
        var idx = 0
        while (idx < count) {
            val s = samples[idx] * INV_32768 * sensitivity
            sumSq += s * s
            sampledCount++
            idx += stride
        }
        val frameRms = sqrt(sumSq / max(1, sampledCount))

        // 2. Adaptive noise floor tracker (tracks quiet background slowly, freezes during speech)
        noiseFloorRms = if (frameRms < noiseFloorRms * 1.55f) {
            noiseFloorRms * 0.96f + frameRms * 0.04f
        } else {
            noiseFloorRms * 0.998f + frameRms * 0.002f
        }.coerceIn(0.0015f, 0.08f)

        val snrRatio = if (noiseFloorRms > 1e-5f) frameRms / noiseFloorRms else 10f
        val ambientStrength = settings.ambientNoiseReduction.coerceIn(0f, 1f)
        val isolationStrength = settings.voiceIsolationStrength.coerceIn(0f, 1f)

        // 3. Soft Wiener-like attenuation based on selected Noise Cancellation level + ambient/isolation sliders
        val ncAttenuation = when (settings.noiseCancellationLevel) {
            NoiseCancellationLevel.OFF -> 1.0f
            NoiseCancellationLevel.LOW -> computeSoftWienerGain(
                snr = snrRatio,
                floorGain = (0.55f - ambientStrength * 0.18f).coerceIn(0.25f, 0.70f),
                kneeSnr = 1.5f + isolationStrength * 0.35f
            )
            NoiseCancellationLevel.MEDIUM -> computeSoftWienerGain(
                snr = snrRatio,
                floorGain = (0.35f - ambientStrength * 0.18f).coerceIn(0.12f, 0.45f),
                kneeSnr = 1.9f + isolationStrength * 0.45f
            )
            NoiseCancellationLevel.HIGH -> computeSoftWienerGain(
                snr = snrRatio,
                floorGain = (0.18f - ambientStrength * 0.10f).coerceIn(0.05f, 0.25f),
                kneeSnr = 2.4f + isolationStrength * 0.55f
            )
            NoiseCancellationLevel.VOICE_ONLY -> computeSoftWienerGain(
                snr = snrRatio,
                floorGain = (0.07f - ambientStrength * 0.04f).coerceIn(0.02f, 0.10f),
                kneeSnr = 2.9f + isolationStrength * 0.85f
            )
            NoiseCancellationLevel.AI_DSP -> computeSoftWienerGain(
                snr = snrRatio,
                floorGain = (0.12f - ambientStrength * 0.06f).coerceIn(0.04f, 0.18f),
                kneeSnr = 2.1f + isolationStrength * 0.50f
            )
        }

        // 4. Smooth Attack / Hold / Release Voice Gate (with Close Voice Focus tuning)
        val frameDb = linearToDb(frameRms)
        val closeFocus = settings.closeVoiceFocusEnabled
        // Close Voice Focus raises the gate threshold gently (~ +5 dB) & favors stronger direct voice energy
        // while keeping a ~180 ms hold tail (8 frames @ 23ms) so word endings are never chopped off.
        val openThresholdDb = if (closeFocus) {
            (settings.noiseGateThresholdDb + 5f).coerceIn(-44f, -18f)
        } else {
            settings.noiseGateThresholdDb
        }
        val closeThresholdDb = openThresholdDb - 4.5f // Hysteresis prevents rapid chatter on syllables

        val gateActive = settings.noiseGateEnabled || closeFocus || settings.noiseCancellationLevel == NoiseCancellationLevel.VOICE_ONLY
        val gateTarget = if (!gateActive) {
            gateIsOpen = true
            1.0f
        } else {
            if (frameDb >= openThresholdDb) {
                gateIsOpen = true
                gateHoldFrames = if (closeFocus) 8 else 6
            } else if (gateIsOpen && frameDb >= closeThresholdDb) {
                gateHoldFrames = max(gateHoldFrames, 4)
            } else if (gateHoldFrames > 0) {
                gateHoldFrames--
            } else {
                gateIsOpen = false
            }
            when {
                gateIsOpen -> 1.0f
                closeFocus || settings.noiseCancellationLevel == NoiseCancellationLevel.VOICE_ONLY -> 0.03f
                else -> 0.06f
            }
        }

        val targetCombinedGain = (ncAttenuation * gateTarget).coerceIn(0f, 1.0f)
        val micDrive = settings.micVolume.coerceIn(0f, 1.5f) * sensitivity * 1.15f

        // Variable Wind Noise Reduction HPF: cutoff scales smoothly from 75 Hz to 220 Hz with windReductionStrength
        val windStrength = settings.windReductionStrength.coerceIn(0f, 1f)
        val applyHpf = settings.windNoiseReduction && windStrength > 0.02f
        val hpfAlpha = if (applyHpf) {
            val cutoffHz = 75f + windStrength * 145f
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            val dt = 1f / sampleRate
            rc / (rc + dt)
        } else {
            1f
        }

        // Gentle vocal presence emphasis (boosted slightly in Close Voice Focus; skipped in lightweightMode)
        val applyClarity = (settings.voiceClarityEnhancement || closeFocus) && !lightweightMode
        val clarityMix = if (closeFocus) 0.22f else 0.15f

        var outSumSq = 0f
        var localHpfIn = hpfPrevInput
        var localHpfOut = hpfPrevOutput
        var localClarity = clarityPrev
        var localGate = gateGain

        // Fast attack (32 samples) so word starts are never clipped; smooth release (96 samples) to avoid pumping
        val rampSamples = min(
            if (targetCombinedGain > localGate) 32 else 96,
            count
        )
        val gainStep = (targetCombinedGain - localGate) / max(1, rampSamples)

        for (i in 0 until count) {
            var x = samples[i] * INV_32768

            if (applyHpf) {
                val y = hpfAlpha * (localHpfOut + x - localHpfIn)
                localHpfIn = x
                localHpfOut = y
                x = y
            }

            if (applyClarity) {
                val high = x - 0.72f * localClarity
                localClarity = x
                x += clarityMix * high
            }

            if (i < rampSamples) {
                localGate += gainStep
            }

            val driven = x * localGate * micDrive
            val limited = fastSoftClip(driven)
            samples[i] = (limited * 32767f).toInt().toShort()

            if (!lightweightMode || (i and 1) == 0) {
                outSumSq += limited * limited
            }
        }

        hpfPrevInput = localHpfIn
        hpfPrevOutput = localHpfOut
        clarityPrev = localClarity
        gateGain = targetCombinedGain

        val rmsDenom = if (lightweightMode) max(1, (count + 1) / 2) else count
        val outRms = sqrt(outSumSq / rmsDenom)
        val outDb = linearToDb(outRms)
        val normLevel = ((outDb + 60f) / 60f).coerceIn(0f, 1f)

        smoothedLevel = smoothedLevel * 0.65f + normLevel * 0.35f
        if (normLevel >= peakHold) {
            peakHold = normLevel
            peakHoldFrames = 14
        } else if (peakHoldFrames > 0) {
            peakHoldFrames--
        } else {
            peakHold = max(0f, peakHold - 0.04f)
        }

        val speaking = gateIsOpen && outDb > (openThresholdDb - 2f).coerceAtLeast(-48f)
        return FrameMetrics(
            normalizedLevel = smoothedLevel,
            peakLevel = peakHold,
            dbFs = outDb,
            speakingDetected = speaking,
            gateOpen = gateIsOpen
        )
    }

    private fun computeSoftWienerGain(snr: Float, floorGain: Float, kneeSnr: Float): Float {
        if (snr >= kneeSnr) return 1.0f
        if (snr <= 1.05f) return floorGain
        val t = ((snr - 1.05f) / (kneeSnr - 1.05f)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return floorGain + (1f - floorGain) * smooth
    }

    companion object {
        private const val INV_32768 = 1f / 32768f

        fun linearToDb(rms: Float): Float {
            if (rms <= 1e-4f) return -60f
            return (20f * log10(rms.toDouble()).toFloat()).coerceIn(-60f, 0f)
        }

        /**
         * Fast rational soft-clipper: linear up to +/-0.85, smoothly saturates toward +/-1.0
         * with zero transcendental tanh() overhead on mobile CPUs.
         */
        fun fastSoftClip(x: Float): Float {
            val ax = abs(x)
            if (ax <= 0.85f) return x
            val excess = ax - 0.85f
            val compressed = 0.85f + 0.15f * (excess / (excess + 0.15f))
            return if (x >= 0f) compressed.coerceAtMost(1f) else (-compressed).coerceAtLeast(-1f)
        }

        fun softClipMix(sum: Float): Short {
            return (fastSoftClip(sum) * 32767f).toInt().toShort()
        }

        fun computeRmsNormalized(samples: ShortArray, count: Int, stride: Int = 1): Pair<Float, Float> {
            if (count <= 0) return 0f to -60f
            var sumSq = 0f
            var n = 0
            var i = 0
            while (i < count) {
                val v = samples[i] * INV_32768
                sumSq += v * v
                n++
                i += stride
            }
            val rms = sqrt(sumSq / max(1, n))
            val db = linearToDb(rms)
            val norm = min(1f, max(0f, (db + 60f) / 60f))
            return norm to db
        }
    }
}
