package com.example.data

import android.content.Context
import com.example.R
import com.example.data.model.StreamSessionDoc
import com.example.data.model.UserProfileDoc
import com.example.data.model.VoiceParticipantDoc
import com.example.data.model.VoiceRoomDoc
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class ZroFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        }
        return uid
    }

    // --- USER PROFILE & AGGREGATE STATS ---

    suspend fun ensureUserProfile(displayName: String, avatarUrl: String?): Result<Unit> {
        var path = "users/unknown"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            val safeName = displayName.trim().ifBlank { "Streamer" }.take(60)
            val safeAvatar = avatarUrl?.takeIf { it.isNotBlank() }?.take(500)
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "avatarUrl" to safeAvatar,
                    "totalStreamDurationSec" to 0L,
                    "totalSessions" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to safeName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (safeAvatar != null) {
                    updates["avatarUrl"] = safeAvatar
                }
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfileDoc?> = flow {
        if (userId.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserProfileDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val fsError = unwrapFirestoreException(error)
                    if (fsError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsError ?: error, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    // --- STREAM SESSIONS ---

    fun observeStreamSessions(userId: String): Flow<List<StreamSessionDoc>> = flow {
        if (userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$userId/stream_sessions"
        emitAll(
            db.collection("users").document(userId).collection("stream_sessions")
                .snapshots()
                .map { snap ->
                    snap.toObjects(StreamSessionDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsError = unwrapFirestoreException(error)
                    if (fsError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createStreamSession(
        title: String,
        description: String?,
        visibility: String,
        resolutionLabel: String,
        fps: Int,
        bitrateKbps: Int
    ): Result<String> {
        var path = "users/unknown/stream_sessions"
        return try {
            val uid = requireUserId()
            val colRef = db.collection("users").document(uid).collection("stream_sessions")
            val docRef = colRef.document()
            path = docRef.path
            val safeTitle = title.trim().ifBlank { "Zro live Stream" }.take(120)
            val safeDesc = description?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
            val safeVis = visibility.takeIf { it in setOf("PUBLIC", "UNLISTED", "PRIVATE") } ?: "PUBLIC"
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "description" to safeDesc,
                "visibility" to safeVis,
                "resolutionLabel" to resolutionLabel.take(20).ifBlank { "720p" },
                "fps" to fps.coerceIn(15, 120).toLong(),
                "bitrateKbps" to bitrateKbps.coerceIn(250, 25000).toLong(),
                "durationSec" to 0L,
                "droppedFrames" to 0L,
                "peakBitrateKbps" to bitrateKbps.coerceIn(0, 30000).toLong(),
                "status" to "LIVE",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun finalizeStreamSession(
        sessionId: String,
        durationSec: Long,
        droppedFrames: Long,
        peakBitrateKbps: Int
    ): Result<Unit> {
        var path = "users/unknown/stream_sessions/$sessionId"
        return try {
            val uid = requireUserId()
            require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
            val sessionRef = db.collection("users").document(uid).collection("stream_sessions").document(sessionId)
            val userRef = db.collection("users").document(uid)
            path = sessionRef.path
            val safeDuration = durationSec.coerceIn(0L, 604800L)
            val safeDropped = droppedFrames.coerceIn(0L, 10000000L)
            val safePeak = peakBitrateKbps.coerceIn(0, 30000).toLong()

            val batch = db.batch()
            batch.update(
                sessionRef,
                mapOf(
                    "durationSec" to safeDuration,
                    "droppedFrames" to safeDropped,
                    "peakBitrateKbps" to safePeak,
                    "status" to "ENDED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                userRef,
                mapOf(
                    "totalStreamDurationSec" to FieldValue.increment(safeDuration),
                    "totalSessions" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- TEAM VOICE ROOMS ---

    fun observePublicVoiceRooms(currentUserId: String): Flow<List<VoiceRoomDoc>> = flow {
        if (currentUserId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "voice_rooms"
        emitAll(
            db.collection("voice_rooms")
                .whereEqualTo("visibility", "PUBLIC")
                .snapshots()
                .map { snap ->
                    snap.toObjects(VoiceRoomDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.active }
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsError = unwrapFirestoreException(error)
                    if (fsError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == currentUserId) {
                        handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeMyVoiceRooms(currentUserId: String): Flow<List<VoiceRoomDoc>> = flow {
        if (currentUserId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "voice_rooms"
        emitAll(
            db.collection("voice_rooms")
                .whereArrayContains("memberUids", currentUserId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(VoiceRoomDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.active }
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsError = unwrapFirestoreException(error)
                    if (fsError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == currentUserId) {
                        handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeVoiceRoom(roomId: String): Flow<VoiceRoomDoc?> = flow {
        if (roomId.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "voice_rooms/$roomId"
        emitAll(
            db.collection("voice_rooms").document(roomId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(VoiceRoomDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val fsError = unwrapFirestoreException(error)
                    if (fsError == null && error is CancellationException) throw error
                    handleFirestoreError(fsError ?: error, OperationType.GET, path)
                    throw error
                }
        )
    }

    /**
     * Observes all participants in an active voice room using per-member `get` listeners
     * derived from the parent room's bounded `memberUids` list (<= 12), satisfying
     * Parent-Gate security rules with zero unfiltered collection `list` queries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRoomParticipants(roomId: String): Flow<List<VoiceParticipantDoc>> {
        if (roomId.isBlank()) return flowOf(emptyList())
        return observeVoiceRoom(roomId).flatMapLatest { room ->
            val members = room?.memberUids.orEmpty().filter { it.isNotBlank() }
            if (room == null || !room.active || members.isEmpty()) {
                flowOf(emptyList())
            } else {
                val memberFlows = members.map { memberUid ->
                    db.collection("voice_rooms").document(roomId)
                        .collection("participants").document(memberUid)
                        .snapshots()
                        .map { snap ->
                            if (snap.exists()) {
                                snap.toObject(
                                    VoiceParticipantDoc::class.java,
                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                )
                            } else null
                        }
                        .catch { error ->
                            val fsError = unwrapFirestoreException(error)
                            if (fsError == null && error is CancellationException) throw error
                            emit(null)
                        }
                }
                combine(memberFlows) { arr -> arr.filterNotNull() }
            }
        }
    }

    suspend fun createVoiceRoom(
        roomName: String,
        isPrivate: Boolean,
        maxParticipants: Int,
        displayName: String,
        avatarUrl: String?,
        streamConsent: Boolean,
        udpEndpoint: String?
    ): Result<VoiceRoomDoc> {
        val code = generateRoomCode()
        val roomPath = "voice_rooms/$code"
        return try {
            val uid = requireUserId()
            val safeName = roomName.trim().ifBlank { "Squad Comms" }.take(60)
            val safeDisplayName = displayName.trim().ifBlank { "Streamer" }.take(60)
            val safeAvatar = avatarUrl?.takeIf { it.isNotBlank() }?.take(500)
            val safeMax = maxParticipants.coerceIn(2, 12).toLong()
            val visibility = if (isPrivate) "PRIVATE" else "PUBLIC"

            val roomRef = db.collection("voice_rooms").document(code)
            val participantRef = roomRef.collection("participants").document(uid)

            val roomPayload = mapOf(
                "hostUid" to uid,
                "roomCode" to code,
                "name" to safeName,
                "visibility" to visibility,
                "active" to true,
                "maxParticipants" to safeMax,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val participantPayload = mapOf(
                "userId" to uid,
                "displayName" to safeDisplayName,
                "avatarUrl" to safeAvatar,
                "muted" to false,
                "speaking" to false,
                "audioLevel" to 0L,
                "streamConsent" to streamConsent,
                "connectionQuality" to "EXCELLENT",
                "udpEndpoint" to udpEndpoint?.take(64),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            val batch = db.batch()
            batch.set(roomRef, roomPayload)
            batch.set(participantRef, participantPayload)
            batch.commit().await()

            Result.success(
                VoiceRoomDoc(
                    id = code,
                    hostUid = uid,
                    roomCode = code,
                    name = safeName,
                    visibility = visibility,
                    active = true,
                    maxParticipants = safeMax,
                    memberUids = listOf(uid)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, roomPath)
            Result.failure(e)
        }
    }

    suspend fun joinVoiceRoom(
        rawRoomCode: String,
        displayName: String,
        avatarUrl: String?,
        streamConsent: Boolean,
        udpEndpoint: String?
    ): Result<VoiceRoomDoc> {
        val code = rawRoomCode.trim().uppercase().replace(" ", "")
        val roomPath = "voice_rooms/$code"
        return try {
            val uid = requireUserId()
            if (code.length < 4 || !code.matches(Regex("^[A-Z0-9\\-]+$"))) {
                return Result.failure(IllegalArgumentException("Enter a valid Room ID (e.g. ZRO-4829)."))
            }
            val roomRef = db.collection("voice_rooms").document(code)
            val snap = roomRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("Voice room '$code' was not found."))
            }
            val room = snap.toObject(VoiceRoomDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Unable to read voice room data."))
            if (!room.active) {
                return Result.failure(IllegalStateException("This voice room has ended."))
            }
            val alreadyMember = uid in room.memberUids
            if (!alreadyMember && room.memberUids.size >= room.maxParticipants) {
                return Result.failure(IllegalStateException("Voice room is full (${room.maxParticipants} max)."))
            }

            val safeDisplayName = displayName.trim().ifBlank { "Player" }.take(60)
            val safeAvatar = avatarUrl?.takeIf { it.isNotBlank() }?.take(500)
            val participantRef = roomRef.collection("participants").document(uid)
            val existingParticipant = participantRef.get().await()

            val batch = db.batch()
            if (!alreadyMember) {
                batch.update(
                    roomRef,
                    mapOf(
                        "memberUids" to (room.memberUids + uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            if (!existingParticipant.exists()) {
                val participantPayload = mapOf(
                    "userId" to uid,
                    "displayName" to safeDisplayName,
                    "avatarUrl" to safeAvatar,
                    "muted" to false,
                    "speaking" to false,
                    "audioLevel" to 0L,
                    "streamConsent" to streamConsent,
                    "connectionQuality" to "GOOD",
                    "udpEndpoint" to udpEndpoint?.take(64),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                batch.set(participantRef, participantPayload)
            } else {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to safeDisplayName,
                    "streamConsent" to streamConsent,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (safeAvatar != null) updates["avatarUrl"] = safeAvatar
                if (!udpEndpoint.isNullOrBlank()) updates["udpEndpoint"] = udpEndpoint.take(64)
                batch.update(participantRef, updates)
            }
            batch.commit().await()
            Result.success(room.copy(memberUids = if (alreadyMember) room.memberUids else room.memberUids + uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, roomPath)
            Result.failure(e)
        }
    }

    suspend fun updateParticipantState(
        roomId: String,
        muted: Boolean? = null,
        speaking: Boolean? = null,
        audioLevel: Int? = null,
        streamConsent: Boolean? = null,
        connectionQuality: String? = null
    ): Result<Unit> {
        var path = "voice_rooms/$roomId/participants/unknown"
        return try {
            val uid = requireUserId()
            if (roomId.isBlank()) return Result.success(Unit)
            val participantRef = db.collection("voice_rooms").document(roomId)
                .collection("participants").document(uid)
            path = participantRef.path
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (muted != null) updates["muted"] = muted
            if (speaking != null) updates["speaking"] = speaking
            if (audioLevel != null) updates["audioLevel"] = audioLevel.coerceIn(0, 100).toLong()
            if (streamConsent != null) updates["streamConsent"] = streamConsent
            if (connectionQuality != null && connectionQuality in setOf("EXCELLENT", "GOOD", "FAIR", "POOR")) {
                updates["connectionQuality"] = connectionQuality
            }
            participantRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun leaveVoiceRoom(roomId: String): Result<Unit> {
        val roomPath = "voice_rooms/$roomId"
        return try {
            val uid = requireUserId()
            if (roomId.isBlank()) return Result.success(Unit)
            val roomRef = db.collection("voice_rooms").document(roomId)
            val snap = roomRef.get().await()
            if (!snap.exists()) return Result.success(Unit)
            val room = snap.toObject(VoiceRoomDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.success(Unit)

            val remaining = room.memberUids.filter { it != uid }
            val participantRef = roomRef.collection("participants").document(uid)
            val batch = db.batch()
            batch.delete(participantRef)
            if (uid in room.memberUids) {
                val roomUpdates = mutableMapOf<String, Any>(
                    "memberUids" to remaining,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (room.hostUid == uid && remaining.isEmpty()) {
                    roomUpdates["active"] = false
                }
                batch.update(roomRef, roomUpdates)
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, roomPath)
            Result.failure(e)
        }
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val suffix = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "ZRO-$suffix"
    }

    companion object {
        fun unwrapFirestoreException(error: Throwable): FirebaseFirestoreException? =
            generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
    }
}
