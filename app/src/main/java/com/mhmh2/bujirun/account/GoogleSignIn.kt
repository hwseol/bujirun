package com.mhmh2.bujirun.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import android.util.Log
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mhmh2.bujirun.BuildConfig
import com.mhmh2.bujirun.tr

/**
 * "구글로 계속하기": the system's Google account sheet (Credential Manager) gives an ID token for
 * our web client, which the server checks with Google (/auth/google). Off when the build has
 * no client ID.
 */
object GoogleSignIn {
    val available: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    /** Signs in; returns false if the user closed the sheet. Throws [AuthException] otherwise. */
    suspend fun signIn(activityContext: Context): Boolean {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            return false
        } catch (e: NoCredentialException) {
            throw AuthException(tr("이 폰에서 쓸 수 있는 구글 계정이 없어요.", "No Google account on this phone can be used.") + detail(e))
        } catch (e: GetCredentialException) {
            throw AuthException(tr("구글 계정을 가져오지 못했어요.", "Couldn't get a Google account.") + detail(e))
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AuthException(tr("구글 로그인을 하지 못했어요.", "Google sign-in didn't work."))
        }
        Account.googleLogIn(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        return true
    }

    // The reason, for reports ("[28444] Developer console is not set up correctly" = the
    // Android OAuth client's package/SHA-1 doesn't match, or the ID isn't a web client).
    private fun detail(e: GetCredentialException): String {
        Log.w("GoogleSignIn", "getCredential failed", e)
        return "\n(${e.type.substringAfterLast('.')}: ${e.errorMessage ?: ""})"
    }
}
