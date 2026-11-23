package com.example.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.model.YouTubeChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Authentic YouTube Data API v3 Live Chat client.
 * - Resolves `activeLiveChatId` and `concurrentViewers` from a real YouTube Live Video ID.
 * - Polls `/youtube/v3/liveChat/messages` using either an OAuth Bearer token or API Key.
 * - Sends live chat messages via POST `/youtube/v3/liveChat/messages` when an OAuth Bearer token is supplied.
 * - Never fabricates fake viewer messages; reports honest API/auth status and provides direct YouTube Live fallback.
 */
class YouTubeLiveChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class LiveDetailsResult(
        val liveChatId: String,
        val concurrentViewers: Int?
    )

    data class ChatPollResult(
        val messages: List<YouTubeChatMessage>,
        val nextPageToken: String?,
        val pollingIntervalMs: Long
    )

    private fun isLikelyOAuthToken(credential: String): Boolean {
        val trimmed = credential.trim()
        return trimmed.startsWith("ya29.") || trimmed.startsWith("Bearer ", ignoreCase = true)
    }

    private fun applyCredential(urlBuilder: StringBuilder, reqBuilder: Request.Builder, credential: String) {
        val trimmed = credential.trim()
        if (isLikelyOAuthToken(trimmed)) {
            val bearer = if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
            reqBuilder.header("Authorization", bearer)
        } else {
            val sep = if (urlBuilder.contains("?")) "&" else "?"
            urlBuilder.append("${sep}key=${URLEncoder.encode(trimmed, "UTF-8")}")
        }
    }

    suspend fun resolveLiveBroadcastDetails(
        videoIdRaw: String,
        apiKeyOrToken: String
    ): Result<LiveDetailsResult> = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoIdRaw)
        val cred = apiKeyOrToken.trim()
        if (videoId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid YouTube Live Video ID or URL."))
        }
        if (cred.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Enter a YouTube Data API Key (read-only) or OAuth Access Token (read + send), or open YouTube Live directly.")
            )
        }
        try {
            val urlBuilder = StringBuilder(
                "https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=${URLEncoder.encode(videoId, "UTF-8")}"
            )
            val reqBuilder = Request.Builder()
            applyCredential(urlBuilder, reqBuilder, cred)
            val request = reqBuilder.url(urlBuilder.toString()).get().build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val apiMsg = parseGoogleApiError(bodyStr) ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IllegalStateException("YouTube API error: $apiMsg"))
                }
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    return@withContext Result.failure(IllegalStateException("Video '$videoId' was not found on YouTube."))
                }
                val liveDetails = items.getJSONObject(0).optJSONObject("liveStreamingDetails")
                    ?: return@withContext Result.failure(IllegalStateException("This video is not currently an active YouTube Live broadcast."))
                val activeChatId = liveDetails.optString("activeLiveChatId", "")
                if (activeChatId.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Live chat is not active or is disabled on this broadcast."))
                }
                val viewersStr = liveDetails.optString("concurrentViewers", "")
                val viewers = viewersStr.toIntOrNull()
                Result.success(LiveDetailsResult(activeChatId, viewers))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not reach YouTube Live Chat API: ${e.localizedMessage ?: "Network error"}"))
        }
    }

    suspend fun pollMessages(
        liveChatId: String,
        apiKeyOrToken: String,
        pageToken: String?
    ): Result<ChatPollResult> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder(
                "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=${URLEncoder.encode(liveChatId, "UTF-8")}&part=snippet,authorDetails"
            )
            if (!pageToken.isNullOrBlank()) {
                urlBuilder.append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
            }
            val reqBuilder = Request.Builder()
            applyCredential(urlBuilder, reqBuilder, apiKeyOrToken)
            val request = reqBuilder.url(urlBuilder.toString()).get().build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val apiMsg = parseGoogleApiError(bodyStr) ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IllegalStateException("Chat sync error: $apiMsg"))
                }
                val json = JSONObject(bodyStr)
                val nextToken = json.optString("nextPageToken", "").ifBlank { null }
                val interval = json.optLong("pollingIntervalMillis", 5000L).coerceIn(3000L, 15000L)
                val items = json.optJSONArray("items")
                val list = mutableListOf<YouTubeChatMessage>()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val snippet = item.optJSONObject("snippet") ?: continue
                        val author = item.optJSONObject("authorDetails")
                        val text = snippet.optString("displayMessage", "")
                        if (text.isBlank()) continue
                        list.add(
                            YouTubeChatMessage(
                                id = item.optString("id", "msg_$i"),
                                authorName = author?.optString("displayName", "Viewer") ?: "Viewer",
                                authorAvatarUrl = author?.optString("profileImageUrl", "")?.ifBlank { null },
                                messageText = text,
                                publishedAt = snippet.optString("publishedAt", ""),
                                isOwner = author?.optBoolean("isChatOwner", false) ?: false,
                                isModerator = author?.optBoolean("isChatModerator", false) ?: false,
                                isHighlightedNew = pageToken != null
                            )
                        )
                    }
                }
                Result.success(ChatPollResult(list, nextToken, interval))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        liveChatId: String,
        oauthToken: String,
        messageText: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val text = messageText.trim()
        if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException("Message cannot be empty."))
        if (!isLikelyOAuthToken(oauthToken)) {
            return@withContext Result.failure(
                IllegalStateException("Sending YouTube Live Chat messages requires an OAuth 2.0 Access Token (starting with 'ya29.'). API keys are read-only.")
            )
        }
        try {
            val payload = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("liveChatId", liveChatId)
                    put("type", "textMessageEvent")
                    put("textMessageDetails", JSONObject().apply {
                        put("messageText", text.take(200))
                    })
                })
            }
            val bearer = if (oauthToken.trim().startsWith("Bearer ", ignoreCase = true)) {
                oauthToken.trim()
            } else {
                "Bearer ${oauthToken.trim()}"
            }
            val req = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet")
                .header("Authorization", bearer)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val apiMsg = parseGoogleApiError(resp.body?.string().orEmpty()) ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IllegalStateException("Failed to send message: $apiMsg"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun extractVideoId(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return ""
            val vMatch = Regex("[?&]v=([a-zA-Z0-9_\\-]{11})").find(trimmed)
            if (vMatch != null) return vMatch.groupValues[1]
            val shortMatch = Regex("youtu\\.be/([a-zA-Z0-9_\\-]{11})").find(trimmed)
            if (shortMatch != null) return shortMatch.groupValues[1]
            val liveMatch = Regex("/live/([a-zA-Z0-9_\\-]{11})").find(trimmed)
            if (liveMatch != null) return liveMatch.groupValues[1]
            return trimmed
        }

        fun openYouTubeLiveChatExternally(context: Context, videoIdRaw: String) {
            val vid = extractVideoId(videoIdRaw)
            val url = if (vid.isNotBlank()) {
                "https://www.youtube.com/live_chat?v=$vid"
            } else {
                "https://studio.youtube.com/channel/UC/livestreaming"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        fun openYouTubeStudioExternally(context: Context) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        private fun parseGoogleApiError(body: String): String? {
            return runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")?.ifBlank { null }
            }.getOrNull()
        }
    }
}
