package com.example.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import com.example.R
import com.example.data.model.AudioMeterState
import com.example.data.model.YouTubeChatMessage
import com.example.studio.ChromaKeyProcessor
import com.example.studio.FacecamShape
import com.example.studio.LayerScaleMode
import com.example.studio.LayerSourceType
import com.example.studio.SceneLayer
import com.example.studio.StudioScene
import com.example.studio.StudioWorkspaceState
import kotlin.math.roundToInt

/**
 * Real-time OBS Canvas-to-H.264 Broadcast Compositor.
 *
 * Renders the exact active StudioScene (Screen 1, Screen 2, Screen 3, Screen 4 — in 16:9 or 9:16)
 * directly onto the H.264 MediaCodec input Surface Canvas so every layer addition, move, resize,
 * crop, opacity edit, chroma-key setting, text update, or scene switch appears instantaneously
 * in the live RTMP video output.
 */
class StudioLiveCompositor(private val context: Context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(10, 14, 22)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val srcRect = Rect()
    private val dstRectF = RectF()
    private val clipPath = Path()

    // Bounded decoded bitmap cache (URIs + bundled scene artwork) to prevent per-frame allocations
    private val bitmapCache = object : LruCache<String, Bitmap>(16) {}
    private val failedKeys = HashSet<String>()

    /**
     * Renders the current [workspace] active scene onto [canvas] at full broadcast resolution.
     * Supports both 16:9 landscape and 9:16 vertical Shorts canvases seamlessly.
     */
    fun renderFrame(
        canvas: Canvas,
        workspace: StudioWorkspaceState,
        gameplayBitmap: Bitmap?,
        audioMeters: AudioMeterState = AudioMeterState(),
        chatMessages: List<YouTubeChatMessage> = emptyList(),
        privacyShieldActive: Boolean = false,
        forceVerticalSceneOverride: Boolean = false
    ) {
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        if (cw <= 0f || ch <= 0f) return

        canvas.drawRect(0f, 0f, cw, ch, bgPaint)

        // Global Privacy Shield / BRB quick override
        if (privacyShieldActive) {
            drawFullScreenStatusCard(
                canvas = canvas,
                cw = cw,
                ch = ch,
                drawableResId = R.drawable.img_scene_brb_gaming_1795732558862,
                title = "PRIVACY SHIELD ACTIVE",
                subtitle = "Selected game is hidden — gameplay paused automatically",
                accentColor = Color.rgb(16, 185, 129)
            )
            return
        }

        val scene: StudioScene = workspace.activeScene
        if (workspace.brbActive && scene.slot != com.example.studio.SceneSlot.BRB_BREAK) {
            drawFullScreenStatusCard(
                canvas = canvas,
                cw = cw,
                ch = ch,
                drawableResId = R.drawable.img_scene_brb_gaming_1795732558862,
                title = "BE RIGHT BACK!",
                subtitle = "Stream paused briefly — Gameplay & camera hidden",
                accentColor = Color.rgb(6, 182, 212)
            )
            return
        }

        val sortedLayers = scene.layers.sortedBy { it.zIndex }
        for (layer in sortedLayers) {
            if (!layer.visible) continue
            val isOverlayLayer = layer.type != LayerSourceType.GAMEPLAY_SCREEN &&
                layer.type != LayerSourceType.BACKGROUND &&
                layer.type != LayerSourceType.STARTING_SOON &&
                layer.type != LayerSourceType.BRB_BANNER &&
                layer.type != LayerSourceType.ENDING_BANNER
            if (!workspace.overlaysGlobalVisible && isOverlayLayer) continue

            // If Dual Live secondary 9:16 stream renders a 16:9 Gameplay scene, keep gameplay centered-cropped cleanly
            if (forceVerticalSceneOverride && layer.type == LayerSourceType.GAMEPLAY_SCREEN) {
                drawGameplayLayer(
                    canvas = canvas,
                    bounds = RectF(0f, 0f, cw, ch),
                    layer = layer,
                    gameplayBitmap = gameplayBitmap,
                    preferCenterCrop = true
                )
            } else {
                drawLayer(
                    canvas = canvas,
                    cw = cw,
                    ch = ch,
                    layer = layer,
                    workspace = workspace,
                    gameplayBitmap = gameplayBitmap,
                    audioMeters = audioMeters,
                    chatMessages = chatMessages
                )
            }
        }
    }

    private fun drawLayer(
        canvas: Canvas,
        cw: Float,
        ch: Float,
        layer: SceneLayer,
        workspace: StudioWorkspaceState,
        gameplayBitmap: Bitmap?,
        audioMeters: AudioMeterState,
        chatMessages: List<YouTubeChatMessage>
    ) {
        val left = (layer.x * cw)
        val top = (layer.y * ch)
        val width = (layer.width * cw).coerceAtLeast(8f)
        val height = (layer.height * ch).coerceAtLeast(8f)
        val bounds = RectF(left, top, left + width, top + height)

        val alphaInt = (layer.opacity.coerceIn(0.05f, 1f) * 255f).roundToInt()

        val saveCount = canvas.save()
        try {
            if (layer.rotationDeg != 0f) {
                canvas.rotate(layer.rotationDeg, bounds.centerX(), bounds.centerY())
            }

            // Layer bounds ARE the exact visible cropped box. Clip strictly to bounds, and if cropped,
            // project the virtual uncropped frame inside bounds so zero invisible margin appears in live output!
            canvas.clipRect(bounds)
            val hasCrop = layer.cropLeft > 0.001f || layer.cropTop > 0.001f ||
                layer.cropRight > 0.001f || layer.cropBottom > 0.001f
            val contentBounds = if (hasCrop) {
                val visW = (1f - layer.cropLeft - layer.cropRight).coerceIn(0.15f, 1f)
                val visH = (1f - layer.cropTop - layer.cropBottom).coerceIn(0.15f, 1f)
                val fullW = bounds.width() / visW
                val fullH = bounds.height() / visH
                val fullLeft = bounds.left - fullW * layer.cropLeft
                val fullTop = bounds.top - fullH * layer.cropTop
                RectF(fullLeft, fullTop, fullLeft + fullW, fullTop + fullH)
            } else {
                bounds
            }

            if (alphaInt < 255) {
                canvas.saveLayerAlpha(bounds, alphaInt)
            }

            when (layer.type) {
                LayerSourceType.GAMEPLAY_SCREEN -> drawGameplayLayer(
                    canvas = canvas,
                    bounds = contentBounds,
                    layer = layer,
                    gameplayBitmap = gameplayBitmap,
                    preferCenterCrop = false
                )
                LayerSourceType.STARTING_SOON -> drawSceneCardLayer(
                    canvas = canvas,
                    bounds = contentBounds,
                    layer = layer,
                    defaultDrawableRes = R.drawable.img_scene_starting_soon,
                    defaultTitle = "STREAMING START SOON",
                    defaultSubtitle = "Live broadcast starting shortly — Stay tuned!",
                    accentColor = Color.rgb(59, 130, 246)
                )
                LayerSourceType.BRB_BANNER -> drawSceneCardLayer(
                    canvas = canvas,
                    bounds = contentBounds,
                    layer = layer,
                    defaultDrawableRes = R.drawable.img_scene_brb_gaming_1795732558862,
                    defaultTitle = "BE RIGHT BACK!",
                    defaultSubtitle = "Stream paused briefly — Gameplay & camera hidden",
                    accentColor = Color.rgb(6, 182, 212)
                )
                LayerSourceType.ENDING_BANNER -> drawSceneCardLayer(
                    canvas = canvas,
                    bounds = contentBounds,
                    layer = layer,
                    defaultDrawableRes = R.drawable.img_scene_ending_gaming_1795732560448,
                    defaultTitle = "THANKS FOR WATCHING!",
                    defaultSubtitle = "Subscribe & turn on notifications for the next live stream",
                    accentColor = Color.rgb(245, 158, 11)
                )
                LayerSourceType.BACKGROUND -> drawBackgroundLayer(canvas, contentBounds, layer)
                LayerSourceType.IMAGE,
                LayerSourceType.LOGO,
                LayerSourceType.GIF,
                LayerSourceType.VIDEO_OVERLAY -> drawMediaLayer(canvas, contentBounds, layer)
                LayerSourceType.TEXT,
                LayerSourceType.ANIMATED_OVERLAY -> drawTextBannerLayer(canvas, contentBounds, layer)
                LayerSourceType.SUBSCRIBER_COUNTER -> drawSubscriberCounterLayer(canvas, contentBounds, layer)
                LayerSourceType.CHAT_OVERLAY -> drawChatOverlayLayer(canvas, contentBounds, layer, workspace, chatMessages)
                LayerSourceType.WEBCAM -> drawWebcamLayer(canvas, contentBounds, layer, workspace)
                LayerSourceType.AUDIO_VISUALIZER -> drawAudioVisualizerLayer(canvas, contentBounds, layer, audioMeters)
                LayerSourceType.DECORATIVE_FRAME -> drawDecorativeFrameLayer(canvas, bounds, layer)
                LayerSourceType.BROWSER_WEB -> drawWebWidgetPlaceholderLayer(canvas, contentBounds, layer)
                LayerSourceType.MERGED_GROUP -> {
                    canvas.clipRect(bounds)
                    for (child in layer.mergedChildren.sortedBy { it.zIndex }) {
                        if (!child.visible) continue
                        val mapped = child.copy(
                            x = layer.x + child.x * layer.width,
                            y = layer.y + child.y * layer.height,
                            width = child.width * layer.width,
                            height = child.height * layer.height,
                            opacity = (child.opacity * layer.opacity).coerceIn(0.05f, 1f)
                        )
                        drawLayer(canvas, cw, ch, mapped, workspace, gameplayBitmap, audioMeters, chatMessages)
                    }
                }
            }

            // Optional crisp border around layer
            if (layer.borderWidthDp > 0f && layer.type != LayerSourceType.DECORATIVE_FRAME && layer.type != LayerSourceType.WEBCAM) {
                strokePaint.color = layer.borderColorHex.toInt()
                strokePaint.strokeWidth = (layer.borderWidthDp * (ch / 360f)).coerceIn(1.5f, 10f)
                val radius = (ch * 0.014f).coerceIn(4f, 16f)
                canvas.drawRoundRect(bounds, radius, radius, strokePaint)
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawGameplayLayer(
        canvas: Canvas,
        bounds: RectF,
        layer: SceneLayer,
        gameplayBitmap: Bitmap?,
        preferCenterCrop: Boolean
    ) {
        fillPaint.color = Color.rgb(9, 13, 21)
        canvas.drawRect(bounds, fillPaint)

        val bmp = gameplayBitmap
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            drawBitmapMapped(
                canvas = canvas,
                bitmap = bmp,
                bounds = bounds,
                scaleMode = if (preferCenterCrop) null else layer.scaleMode,
                centerCrop = preferCenterCrop
            )
            return
        }

        // Clean technical standby frame if capture frame hasn't arrived yet (never claims fake gameplay)
        textPaint.color = Color.rgb(226, 232, 240)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (bounds.height() * 0.055f).coerceIn(18f, 44f)
        canvas.drawText("WAITING FOR CAPTURE FRAMES", bounds.centerX(), bounds.centerY() - 8f, textPaint)

        subTextPaint.color = Color.rgb(148, 163, 184)
        subTextPaint.typeface = Typeface.MONOSPACE
        subTextPaint.textAlign = Paint.Align.CENTER
        subTextPaint.textSize = (bounds.height() * 0.032f).coerceIn(12f, 24f)
        canvas.drawText("Zro Live • Active Capture Pipeline", bounds.centerX(), bounds.centerY() + textPaint.textSize, subTextPaint)
    }

    private fun drawSceneCardLayer(
        canvas: Canvas,
        bounds: RectF,
        layer: SceneLayer,
        defaultDrawableRes: Int,
        defaultTitle: String,
        defaultSubtitle: String,
        accentColor: Int
    ) {
        val customBmp = resolveLayerBitmap(layer)
        val backdropBmp = customBmp ?: if (!layer.transparentBackground) loadDrawableBitmap(defaultDrawableRes) else null
        if (backdropBmp != null && !backdropBmp.isRecycled) {
            drawBitmapMapped(
                canvas = canvas,
                bitmap = backdropBmp,
                bounds = bounds,
                scaleMode = null,
                centerCrop = true
            )
            // Subtle readability scrim so user text always pops crisply over artwork
            scrimPaint.color = Color.argb(110, 6, 10, 18)
            canvas.drawRect(bounds, scrimPaint)
        } else if (!layer.transparentBackground) {
            val shader = LinearGradient(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                Color.rgb(10, 14, 24), Color.rgb(18, 25, 40),
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = shader
            canvas.drawRect(bounds, fillPaint)
            fillPaint.shader = null
        }

        // Responsive typography for both 16:9 landscape and 9:16 vertical Shorts canvases
        val minDim = minOf(bounds.width(), bounds.height())
        val barH = (minDim * 0.009f).coerceIn(3f, 8f)
        fillPaint.color = accentColor
        val barW = bounds.width() * 0.18f
        canvas.drawRoundRect(
            RectF(bounds.centerX() - barW / 2f, bounds.centerY() - minDim * 0.15f, bounds.centerX() + barW / 2f, bounds.centerY() - minDim * 0.15f + barH),
            barH, barH, fillPaint
        )

        val title = layer.textContent.ifBlank { defaultTitle }
        val subtitle = layer.subtitleText.ifBlank { defaultSubtitle }

        textPaint.color = layer.textColorHex.toInt()
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (minDim * 0.085f).coerceIn(20f, 70f)
        textPaint.setShadowLayer(8f, 0f, 3f, Color.argb(180, 0, 0, 0))
        canvas.drawText(title, bounds.centerX(), bounds.centerY() + textPaint.textSize * 0.15f, textPaint)
        textPaint.clearShadowLayer()

        if (subtitle.isNotBlank()) {
            subTextPaint.color = Color.rgb(214, 222, 236)
            subTextPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            subTextPaint.textAlign = Paint.Align.CENTER
            subTextPaint.textSize = (minDim * 0.036f).coerceIn(12f, 28f)
            canvas.drawText(subtitle, bounds.centerX(), bounds.centerY() + textPaint.textSize * 0.95f, subTextPaint)
        }
    }

    private fun drawFullScreenStatusCard(
        canvas: Canvas,
        cw: Float,
        ch: Float,
        drawableResId: Int,
        title: String,
        subtitle: String,
        accentColor: Int
    ) {
        val bounds = RectF(0f, 0f, cw, ch)
        val bmp = loadDrawableBitmap(drawableResId)
        if (bmp != null && !bmp.isRecycled) {
            drawBitmapMapped(canvas, bmp, bounds, scaleMode = null, centerCrop = true)
            scrimPaint.color = Color.argb(135, 6, 10, 18)
            canvas.drawRect(bounds, scrimPaint)
        } else {
            fillPaint.color = Color.rgb(12, 16, 26)
            canvas.drawRect(bounds, fillPaint)
        }
        fillPaint.color = accentColor
        val barW = cw * 0.16f
        canvas.drawRect(cw / 2f - barW / 2f, ch * 0.39f, cw / 2f + barW / 2f, ch * 0.39f + 5f, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (ch * 0.075f).coerceIn(22f, 64f)
        canvas.drawText(title, cw / 2f, ch * 0.50f, textPaint)

        subTextPaint.color = Color.rgb(203, 213, 225)
        subTextPaint.typeface = Typeface.SANS_SERIF
        subTextPaint.textAlign = Paint.Align.CENTER
        subTextPaint.textSize = (ch * 0.034f).coerceIn(13f, 28f)
        canvas.drawText(subtitle, cw / 2f, ch * 0.58f, subTextPaint)
    }

    private fun drawBackgroundLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        val bmp = resolveLayerBitmap(layer)
        if (bmp != null && !bmp.isRecycled) {
            drawBitmapMapped(canvas, bmp, bounds, scaleMode = layer.scaleMode, centerCrop = false)
        } else {
            fillPaint.color = layer.backgroundColorHex.toInt()
            canvas.drawRect(bounds, fillPaint)
        }
    }

    private fun drawMediaLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        val bmp = resolveLayerBitmap(layer)
        if (bmp != null && !bmp.isRecycled) {
            drawBitmapMapped(canvas, bmp, bounds, scaleMode = layer.scaleMode, centerCrop = false)
            return
        }
        if (!layer.transparentBackground) {
            fillPaint.color = layer.backgroundColorHex.toInt()
            val radius = (bounds.height() * 0.12f).coerceIn(4f, 16f)
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
        }
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (bounds.height() * 0.28f).coerceIn(12f, 28f)
        canvas.drawText(layer.name.uppercase(), bounds.centerX(), bounds.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawTextBannerLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        val radius = (bounds.height() * 0.16f).coerceIn(4f, 14f)
        if (!layer.transparentBackground) {
            fillPaint.color = layer.backgroundColorHex.toInt()
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
        }
        val text = layer.textContent.ifBlank { layer.name }
        textPaint.color = layer.textColorHex.toInt()
        textPaint.typeface = when (layer.fontStyle) {
            "MONO" -> Typeface.MONOSPACE
            "DISPLAY" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        textPaint.textAlign = Paint.Align.CENTER
        val scaledSize = (bounds.height() * 0.44f).coerceIn(14f, 52f)
        textPaint.textSize = scaledSize
        val baseline = bounds.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, bounds.centerX(), baseline, textPaint)
    }

    private fun drawSubscriberCounterLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        val radius = (bounds.height() * 0.14f).coerceIn(6f, 16f)
        fillPaint.color = Color.argb(220, 13, 18, 30)
        canvas.drawRoundRect(bounds, radius, radius, fillPaint)

        val padX = bounds.width() * 0.08f
        val topY = bounds.top + bounds.height() * 0.34f

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = (bounds.height() * 0.20f).coerceIn(10f, 20f)
        canvas.drawText(layer.subTitle.ifBlank { "SUBSCRIBERS" }, bounds.left + padX, topY, textPaint)

        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = (bounds.height() * 0.25f).coerceIn(12f, 24f)
        canvas.drawText("${layer.subCount}/${layer.subGoal}", bounds.right - padX, topY, textPaint)

        // Progress bar
        val barTop = bounds.top + bounds.height() * 0.58f
        val barBottom = bounds.top + bounds.height() * 0.78f
        val barRect = RectF(bounds.left + padX, barTop, bounds.right - padX, barBottom)
        fillPaint.color = Color.rgb(30, 41, 59)
        canvas.drawRoundRect(barRect, 6f, 6f, fillPaint)

        val ratio = if (layer.subGoal > 0) (layer.subCount.toFloat() / layer.subGoal.toFloat()).coerceIn(0f, 1f) else 0f
        if (ratio > 0f) {
            fillPaint.color = Color.rgb(34, 197, 94)
            val fillRect = RectF(barRect.left, barRect.top, barRect.left + barRect.width() * ratio, barRect.bottom)
            canvas.drawRoundRect(fillRect, 6f, 6f, fillPaint)
        }
    }

    private fun drawChatOverlayLayer(
        canvas: Canvas,
        bounds: RectF,
        layer: SceneLayer,
        workspace: StudioWorkspaceState,
        chatMessages: List<YouTubeChatMessage>
    ) {
        if (!workspace.chatOverlayEnabled) return
        val radius = 10f
        if (!layer.transparentBackground) {
            fillPaint.color = layer.backgroundColorHex.toInt()
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
        }
        val pad = (bounds.width() * 0.05f).coerceIn(8f, 18f)
        var cursorY = bounds.top + pad + 16f

        textPaint.color = Color.rgb(96, 165, 250)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = (bounds.height() * 0.068f).coerceIn(11f, 20f)
        canvas.drawText("LIVE CHAT", bounds.left + pad, cursorY, textPaint)
        cursorY += textPaint.textSize * 1.3f

        val lines = chatMessages.takeLast(5)
        subTextPaint.typeface = Typeface.SANS_SERIF
        subTextPaint.textAlign = Paint.Align.LEFT
        subTextPaint.textSize = (bounds.height() * 0.064f).coerceIn(11f, 20f)

        if (lines.isEmpty()) {
            subTextPaint.color = Color.rgb(148, 163, 184)
            canvas.drawText("Waiting for chat messages…", bounds.left + pad, cursorY, subTextPaint)
        } else {
            for (msg in lines) {
                if (cursorY > bounds.bottom - pad) break
                subTextPaint.color = Color.WHITE
                val line = "${msg.authorName}: ${msg.messageText}".take(42)
                canvas.drawText(line, bounds.left + pad, cursorY, subTextPaint)
                cursorY += subTextPaint.textSize * 1.35f
            }
        }
    }

    private fun drawWebcamLayer(
        canvas: Canvas,
        bounds: RectF,
        layer: SceneLayer,
        workspace: StudioWorkspaceState
    ) {
        if (!workspace.cameraEnabled) return
        val save = canvas.save()
        try {
            clipPath.reset()
            when (layer.facecamShape) {
                FacecamShape.CIRCLE -> {
                    val r = minOf(bounds.width(), bounds.height()) / 2f
                    clipPath.addCircle(bounds.centerX(), bounds.centerY(), r, Path.Direction.CW)
                }
                FacecamShape.SQUARE -> clipPath.addRect(bounds, Path.Direction.CW)
                FacecamShape.ROUNDED_RECT -> clipPath.addRoundRect(bounds, 16f, 16f, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val customBmp = resolveLayerBitmap(layer)
            if (customBmp != null && !customBmp.isRecycled) {
                drawBitmapMapped(canvas, customBmp, bounds, scaleMode = null, centerCrop = true)
            } else {
                fillPaint.color = Color.rgb(18, 24, 38)
                canvas.drawRect(bounds, fillPaint)
                textPaint.color = Color.rgb(148, 163, 184)
                textPaint.typeface = Typeface.MONOSPACE
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = (bounds.height() * 0.14f).coerceIn(12f, 24f)
                canvas.drawText("FACECAM ON", bounds.centerX(), bounds.centerY(), textPaint)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        strokePaint.color = layer.borderColorHex.toInt()
        strokePaint.strokeWidth = (layer.borderWidthDp * 2f).coerceIn(2f, 8f)
        when (layer.facecamShape) {
            FacecamShape.CIRCLE -> {
                val r = minOf(bounds.width(), bounds.height()) / 2f
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), r, strokePaint)
            }
            FacecamShape.SQUARE -> canvas.drawRect(bounds, strokePaint)
            FacecamShape.ROUNDED_RECT -> canvas.drawRoundRect(bounds, 16f, 16f, strokePaint)
        }
    }

    private fun drawAudioVisualizerLayer(
        canvas: Canvas,
        bounds: RectF,
        layer: SceneLayer,
        meters: AudioMeterState
    ) {
        if (!layer.transparentBackground) {
            fillPaint.color = Color.argb(180, 12, 16, 26)
            canvas.drawRoundRect(bounds, 8f, 8f, fillPaint)
        }
        val bars = 12
        val gap = 4f
        val barW = ((bounds.width() - gap * (bars + 1)) / bars).coerceAtLeast(3f)
        val level = maxOf(meters.masterLevel, meters.micLevel).coerceIn(0.08f, 1f)
        for (i in 0 until bars) {
            val factor = (0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((i + 1) * 0.8f + level * 4f))).toFloat()
            val h = (bounds.height() * 0.8f * level * factor).coerceAtLeast(4f)
            val x = bounds.left + gap + i * (barW + gap)
            fillPaint.color = when {
                i >= 10 -> Color.rgb(239, 68, 68)
                i >= 7 -> Color.rgb(245, 158, 11)
                else -> Color.rgb(34, 197, 94)
            }
            canvas.drawRoundRect(RectF(x, bounds.bottom - h - 4f, x + barW, bounds.bottom - 4f), 3f, 3f, fillPaint)
        }
    }

    private fun drawDecorativeFrameLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        strokePaint.color = layer.borderColorHex.toInt()
        strokePaint.strokeWidth = (layer.borderWidthDp * 2.5f).coerceIn(3f, 14f)
        canvas.drawRoundRect(bounds, 12f, 12f, strokePaint)
    }

    private fun drawWebWidgetPlaceholderLayer(canvas: Canvas, bounds: RectF, layer: SceneLayer) {
        if (layer.webUrl.isBlank()) return
        fillPaint.color = Color.argb(170, 12, 18, 30)
        canvas.drawRoundRect(bounds, 8f, 8f, fillPaint)
        textPaint.color = Color.rgb(147, 197, 253)
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (bounds.height() * 0.22f).coerceIn(11f, 22f)
        canvas.drawText(layer.name, bounds.centerX(), bounds.centerY(), textPaint)
    }

    private fun drawBitmapMapped(
        canvas: Canvas,
        bitmap: Bitmap,
        bounds: RectF,
        scaleMode: LayerScaleMode?,
        centerCrop: Boolean
    ) {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return

        if (centerCrop) {
            val canvasAspect = bounds.width() / bounds.height()
            val bmpAspect = bw.toFloat() / bh.toFloat()
            if (bmpAspect > canvasAspect) {
                val cropW = (bh * canvasAspect).toInt().coerceIn(1, bw)
                val xOff = (bw - cropW) / 2
                srcRect.set(xOff, 0, xOff + cropW, bh)
            } else {
                val cropH = (bw / canvasAspect).toInt().coerceIn(1, bh)
                val yOff = (bh - cropH) / 2
                srcRect.set(0, yOff, bw, yOff + cropH)
            }
            canvas.drawBitmap(bitmap, srcRect, bounds, bitmapPaint)
            return
        }

        when (scaleMode) {
            LayerScaleMode.FIT -> {
                val scale = minOf(bounds.width() / bw.toFloat(), bounds.height() / bh.toFloat())
                val drawW = bw * scale
                val drawH = bh * scale
                val left = bounds.left + (bounds.width() - drawW) / 2f
                val top = bounds.top + (bounds.height() - drawH) / 2f
                dstRectF.set(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(bitmap, null, dstRectF, bitmapPaint)
            }
            else -> {
                // Default STRETCH (Fill Box)
                canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
            }
        }
    }

    private fun resolveLayerBitmap(layer: SceneLayer): Bitmap? {
        val uriStr = layer.mediaUri?.takeIf { it.isNotBlank() } ?: return null
        val chroma = layer.graphicAdjustment.chromaKey
        val cacheKey = if (chroma.enabled) {
            "uri:$uriStr:ck:${chroma.hashCode()}"
        } else {
            "uri:$uriStr"
        }
        bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        if (failedKeys.contains(cacheKey)) return null

        val rawKey = "uri:$uriStr"
        val rawBmp = bitmapCache.get(rawKey)?.takeIf { !it.isRecycled } ?: decodeUriBitmap(uriStr)?.also {
            bitmapCache.put(rawKey, it)
        }
        if (rawBmp == null) {
            failedKeys.add(cacheKey)
            return null
        }

        return if (chroma.enabled) {
            val keyed = runCatching { ChromaKeyProcessor.applyChromaKey(rawBmp, chroma) }.getOrNull() ?: rawBmp
            bitmapCache.put(cacheKey, keyed)
            keyed
        } else {
            rawBmp
        }
    }

    private fun decodeUriBitmap(uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadDrawableBitmap(resId: Int): Bitmap? {
        val key = "res:$resId"
        bitmapCache.get(key)?.let { if (!it.isRecycled) return it }
        if (failedKeys.contains(key)) return null
        val bmp = runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeResource(context.resources, resId, opts)
        }.getOrNull()
        if (bmp != null) {
            bitmapCache.put(key, bmp)
        } else {
            failedKeys.add(key)
        }
        return bmp
    }

    fun release() {
        bitmapCache.evictAll()
        failedKeys.clear()
    }
}
