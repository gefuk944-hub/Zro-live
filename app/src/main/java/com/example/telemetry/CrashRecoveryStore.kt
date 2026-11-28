package com.example.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.streaming.RtmpPublisher
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Snapshot of an active broadcast session persisted for crash/disconnect recovery.
 * Stream keys are NEVER written into recovery logs or crash files—only read from encrypted/private app DataStore.
 */
data class ActiveLiveSessionSnapshot(
    val sessionId: String,
    val title: String,
    val description: String = "",
    val visibility: String = "PUBLIC",
    val platformLabel: String = "YouTube / RTMP",
    val rtmpUrl: String = "",
    val resolutionName: String = "P720",
    val resolutionLabel: String = "720p",
    val fps: Int = 30,
    val bitrateKbps: Int = 4500,
    val activeSceneSlotName: String = "GAMEPLAY_LIVE",
    val startedAtEpochMs: Long = 0L,
    val lastHeartbeatEpochMs: Long = 0L,
    val lastUpdatedEpochMs: Long = lastHeartbeatEpochMs,
    val durationSec: Long = 0L,
    val wasLiveConfirmed: Boolean = false,
    val cleanlyEnded: Boolean = false,
    val lastError: String? = null
) {
    val sceneSlotName: String get() = activeSceneSlotName

    fun isRecoverable(): Boolean = !cleanlyEnded && sessionId.isNotBlank() && rtmpUrl.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("title", title)
        put("description", description)
        put("visibility", visibility)
        put("platformLabel", platformLabel)
        put("rtmpUrl", rtmpUrl)
        put("resolutionName", resolutionName)
        put("resolutionLabel", resolutionLabel)
        put("fps", fps)
        put("bitrateKbps", bitrateKbps)
        put("activeSceneSlotName", activeSceneSlotName)
        put("startedAtEpochMs", startedAtEpochMs)
        put("lastHeartbeatEpochMs", lastHeartbeatEpochMs)
        put("lastUpdatedEpochMs", lastUpdatedEpochMs)
        put("durationSec", durationSec)
        put("wasLiveConfirmed", wasLiveConfirmed)
        put("cleanlyEnded", cleanlyEnded)
        put("lastError", lastError ?: "")
    }

    companion object {
        fun fromJson(raw: String?): ActiveLiveSessionSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val sid = o.optString("sessionId", "").trim()
                if (sid.isEmpty()) return null
                val hb = o.optLong("lastHeartbeatEpochMs", 0L)
                val upd = o.optLong("lastUpdatedEpochMs", hb)
                ActiveLiveSessionSnapshot(
                    sessionId = sid,
                    title = o.optString("title", "Previous Live Stream"),
                    description = o.optString("description", ""),
                    visibility = o.optString("visibility", "PUBLIC"),
                    platformLabel = o.optString("platformLabel", "YouTube / RTMP"),
                    rtmpUrl = o.optString("rtmpUrl", ""),
                    resolutionName = o.optString("resolutionName", "P720"),
                    resolutionLabel = o.optString("resolutionLabel", "720p"),
                    fps = o.optInt("fps", 30).coerceIn(15, 60),
                    bitrateKbps = o.optInt("bitrateKbps", 4500).coerceIn(1000, 15000),
                    activeSceneSlotName = o.optString("activeSceneSlotName", "GAMEPLAY_LIVE"),
                    startedAtEpochMs = o.optLong("startedAtEpochMs", 0L),
                    lastHeartbeatEpochMs = hb,
                    lastUpdatedEpochMs = upd,
                    durationSec = o.optLong("durationSec", 0L),
                    wasLiveConfirmed = o.optBoolean("wasLiveConfirmed", false),
                    cleanlyEnded = o.optBoolean("cleanlyEnded", false),
                    lastError = o.optString("lastError", "").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }
}

/**
 * Reusable saved stream configuration profile managed inside Live Manager.
 */
data class SavedLiveConfigProfile(
    val id: String,
    val name: String,
    val title: String,
    val description: String = "",
    val rtmpUrl: String = "",
    val platformLabel: String = "YouTube / RTMP",
    val visibilityName: String = "PUBLIC",
    val resolutionName: String = "P720",
    val fps: Int = 30,
    val bitrateKbps: Int = 4500,
    val sceneSlotName: String = "GAMEPLAY_LIVE",
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = savedAtEpochMs
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("title", title)
        put("description", description)
        put("rtmpUrl", rtmpUrl)
        put("platformLabel", platformLabel)
        put("visibilityName", visibilityName)
        put("resolutionName", resolutionName)
        put("fps", fps)
        put("bitrateKbps", bitrateKbps)
        put("sceneSlotName", sceneSlotName)
        put("savedAtEpochMs", savedAtEpochMs)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    companion object {
        fun parseList(raw: String?): List<SavedLiveConfigProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "").trim()
                        if (id.isEmpty()) continue
                        val savedAt = o.optLong("savedAtEpochMs", 0L)
                        add(
                            SavedLiveConfigProfile(
                                id = id,
                                name = o.optString("name", "Saved Profile"),
                                title = o.optString("title", ""),
                                description = o.optString("description", ""),
                                rtmpUrl = o.optString("rtmpUrl", ""),
                                platformLabel = o.optString("platformLabel", "YouTube / RTMP"),
                                visibilityName = o.optString("visibilityName", "PUBLIC"),
                                resolutionName = o.optString("resolutionName", "P720"),
                                fps = o.optInt("fps", 30).coerceIn(15, 60),
                                bitrateKbps = o.optInt("bitrateKbps", 4500).coerceIn(1000, 15000),
                                sceneSlotName = o.optString("sceneSlotName", "GAMEPLAY_LIVE"),
                                savedAtEpochMs = savedAt,
                                updatedAtEpochMs = o.optLong("updatedAtEpochMs", savedAt)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun serializeList(list: List<SavedLiveConfigProfile>): String {
            val arr = JSONArray()
            list.take(20).forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

data class CrashLogEntry(
    val id: String,
    val timestampMs: Long,
    val category: String, // "CRASH", "ENCODER", "NETWORK", "RECOVERY"
    val summary: String,
    val detail: String,
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
)

/**
 * Lightweight, secret-safe crash & recovery log recorder stored inside app-private filesDir.
 * Scrubs any stream keys or bearer tokens before persisting.
 */
object CrashRecoveryStore {
    private const val TAG = "CrashRecoveryStore"
    private const val LOG_FILE_NAME = "zro_crash_logs.json"
    private const val WORKSPACE_BACKUP_FILE = "zro_crash_workspace_backup.json"
    private const val MAX_LOGS = 25

    private val handlerInstalled = AtomicBoolean(false)
    private val fileLock = Any()

    @Volatile
    private var secretScrubList: List<String> = emptyList()

    fun updateSecretsToScrub(vararg secrets: String?) {
        secretScrubList = secrets.mapNotNull { it?.trim()?.takeIf { s -> s.length >= 4 } }
    }

    fun installUncaughtHandler(context: Context) {
        if (!handlerInstalled.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // Synchronously persist the active studio workspace before delegating so user layers/crops/URLs are never lost.
                saveEmergencyWorkspaceBackup(appCtx, ZroStreamCoordinator.activeStudioWorkspace.value)
            }
            runCatching {
                recordEvent(
                    context = appCtx,
                    category = "CRASH",
                    summary = "${throwable.javaClass.simpleName} on ${thread.name}",
                    detail = throwable.stackTraceToString().take(900)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun saveEmergencyWorkspaceBackup(context: Context, workspace: StudioWorkspaceState): Boolean = synchronized(fileLock) {
        runCatching {
            val serialized = StudioPersistence.serializeWorkspace(workspace)
            File(context.filesDir, WORKSPACE_BACKUP_FILE).writeText(serialized)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Unable to persist emergency workspace backup: ${e.message}")
            false
        }
    }

    fun readEmergencyWorkspaceBackup(context: Context): StudioWorkspaceState? = synchronized(fileLock) {
        val file = File(context.filesDir, WORKSPACE_BACKUP_FILE)
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) null else StudioPersistence.deserializeWorkspace(raw)
        }.getOrNull()
    }

    fun clearEmergencyWorkspaceBackup(context: Context) = synchronized(fileLock) {
        runCatching {
            val file = File(context.filesDir, WORKSPACE_BACKUP_FILE)
            if (file.exists()) file.delete()
        }
    }

    fun recordEvent(context: Context, category: String, summary: String, detail: String = "") {
        val cleanSummary = scrubSecrets(summary).take(180)
        val cleanDetail = scrubSecrets(detail).take(900)
        val entry = CrashLogEntry(
            id = "log_${System.currentTimeMillis()}_${(100..999).random()}",
            timestampMs = System.currentTimeMillis(),
            category = category,
            summary = cleanSummary,
            detail = cleanDetail
        )
        synchronized(fileLock) {
            runCatching {
                val existing = readLogsUnlocked(context).toMutableList()
                existing.add(0, entry)
                val trimmed = existing.take(MAX_LOGS)
                val arr = JSONArray()
                trimmed.forEach { item ->
                    arr.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("timestampMs", item.timestampMs)
                            put("category", item.category)
                            put("summary", item.summary)
                            put("detail", item.detail)
                            put("androidVersion", item.androidVersion)
                        }
                    )
                }
                File(context.filesDir, LOG_FILE_NAME).writeText(arr.toString())
            }.onFailure { e ->
                Log.w(TAG, "Unable to persist diagnostic log: ${e.message}")
            }
        }
    }

    fun readLogs(context: Context): List<CrashLogEntry> = synchronized(fileLock) {
        readLogsUnlocked(context)
    }

    fun clearLogs(context: Context) = synchronized(fileLock) {
        runCatching {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
        }
    }

    fun scrubSecrets(input: String): String {
        var out = input
        for (secret in secretScrubList) {
            if (secret.length >= 4 && out.contains(secret)) {
                out = out.replace(secret, RtmpPublisher.maskStreamKey(secret))
            }
        }
        return out
    }

    private fun readLogsUnlocked(context: Context): List<CrashLogEntry> {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        CrashLogEntry(
                            id = o.optString("id", "log_$i"),
                            timestampMs = o.optLong("timestampMs", 0L),
                            category = o.optString("category", "EVENT"),
                            summary = o.optString("summary", ""),
                            detail = o.optString("detail", ""),
                            androidVersion = o.optString("androidVersion", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
