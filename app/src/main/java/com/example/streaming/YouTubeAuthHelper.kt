package com.example.streaming

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.data.model.YouTubeAccountState
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Real Google / YouTube account sign-in helper powered by Jetpack CredentialManager
 * and FirebaseAuth. Keeps sign-in strictly optional so RTMP broadcasting always works
 * while allowing users to link their YouTube/Google channel identity inside Live Setup.
 */
object YouTubeAuthHelper {
    private const val TAG = "YouTubeAuthHelper"

    fun currentAccountState(): YouTubeAccountState {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null && !user.isAnonymous) {
                YouTubeAccountState(
                    signedIn = true,
                    displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "YouTube Creator",
                    email = user.email.orEmpty(),
                    photoUrl = user.photoUrl?.toString(),
                    isLoading = false,
                    statusMessage = "Connected to Google / YouTube account"
                )
            } else {
                YouTubeAccountState(signedIn = false)
            }
        }.getOrDefault(YouTubeAccountState(signedIn = false))
    }

    private fun resolveWebClientId(context: Context): String? {
        return runCatching {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                context.getString(resId).trim().takeIf { it.isNotBlank() && !it.contains("REPLACE", ignoreCase = true) }
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun signInWithGoogleForYouTube(activityContext: Context): Result<YouTubeAccountState> {
        val webClientId = resolveWebClientId(activityContext)
            ?: return Result.failure(
                IllegalStateException("Google Sign-In client ID is not configured in google-services.json yet. You can still stream directly using your YouTube Stream Key.")
            )

        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val response = credentialManager.getCredential(
                request = request,
                context = activityContext
            )
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdCred = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCred = GoogleAuthProvider.getCredential(googleIdCred.idToken, null)
                val authResult = FirebaseAuth.getInstance().signInWithCredential(firebaseCred).await()
                val user = authResult.user
                Result.success(
                    YouTubeAccountState(
                        signedIn = user != null,
                        displayName = user?.displayName?.takeIf { it.isNotBlank() }
                            ?: googleIdCred.displayName?.takeIf { it.isNotBlank() }
                            ?: "YouTube Creator",
                        email = user?.email ?: googleIdCred.id,
                        photoUrl = (user?.photoUrl ?: googleIdCred.profilePictureUri)?.toString(),
                        isLoading = false,
                        statusMessage = "YouTube / Google account connected"
                    )
                )
            } else {
                Result.failure(IllegalStateException("Unsupported sign-in credential type returned."))
            }
        } catch (cancelled: GetCredentialCancellationException) {
            Result.failure(IllegalStateException("YouTube sign-in cancelled."))
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google CredentialManager sign-in failed: ${e.message}")
            Result.failure(IllegalStateException(e.errorMessage?.toString() ?: "Google Sign-In failed on this device."))
        } catch (e: Exception) {
            Log.w(TAG, "YouTube account sign-in error: ${e.message}")
            Result.failure(IllegalStateException(e.localizedMessage ?: "Unable to connect YouTube account."))
        }
    }

    suspend fun signOut(context: Context): YouTubeAccountState {
        runCatching { FirebaseAuth.getInstance().signOut() }
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
        return YouTubeAccountState(
            signedIn = false,
            statusMessage = "YouTube account disconnected"
        )
    }
}
