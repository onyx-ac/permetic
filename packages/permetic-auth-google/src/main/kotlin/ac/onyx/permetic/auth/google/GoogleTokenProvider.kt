package ac.onyx.permetic.auth.google

import ac.onyx.permetic.auth.CachingAuthCapability
import ac.onyx.permetic.auth.TokenProvider
import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AuthToken
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.transport.BridgeErrorCode
import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.lang.ref.WeakReference

/**
 * Google identity for [CachingAuthCapability], via Credential Manager (spec 01, open
 * decision D-1, resolved in favour of Google + Credential Manager).
 *
 * **Returns a Google ID token, not an OAuth access token.** [AuthToken.accessToken]
 * carries the signed JWT that the web app hands to Firebase Auth's
 * `signInWithCredential`, which is what a Firestore-backed app needs. Non-empty
 * [fetchToken] scopes are therefore rejected with `UNAVAILABLE` rather than quietly
 * returning an unscoped token: scoped OAuth needs `AuthorizationClient` and an
 * activity-result consent flow, neither of which this build has a caller for.
 *
 * **Expiry is assumed, not read from the token.** Google ID tokens last an hour;
 * [tokenLifetimeMillis] defaults to slightly less. The alternative — decoding the
 * JWT's `exp` — would mean base64-decoding and trusting a payload we never verify
 * the signature of, to save a round trip the `refresh()` path already handles
 * properly. `java.util.Base64` also needs API 26 against this module's `minSdk 24`.
 *
 * **"Non-interactive" is best-effort.** Credential Manager offers no hard silent
 * guarantee; `interactive = false` filters to already-authorized accounts and asks
 * for auto-select, which avoids UI when exactly one such account exists. The genuinely
 * silent path is [CachingAuthCapability]'s cache — this provider is only reached on a
 * miss.
 */
public class GoogleTokenProvider(
    activity: Activity,
    private val serverClientId: String,
    private val tokenLifetimeMillis: Long = DEFAULT_ID_TOKEN_LIFETIME_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenProvider {
    private val credentialManager = CredentialManager.create(activity.applicationContext)

    @Volatile
    private var activityRef = WeakReference(activity)

    @Volatile
    private var account: Account? = null

    /** Re-points at the post-configuration-change `Activity`; see `AndroidSystemCapability.rebind`. */
    public fun rebind(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override suspend fun fetchToken(
        scopes: List<String>,
        interactive: Boolean,
    ): AuthToken {
        if (scopes.isNotEmpty()) {
            throw CapabilityException(
                BridgeErrorCode.UNAVAILABLE,
                "this provider issues Google ID tokens only; scoped OAuth is not implemented",
            )
        }
        // Credential Manager renders its UI over an Activity; the application context
        // will not do, so an absent Activity is a genuine UNAVAILABLE here (unlike
        // AndroidSystemCapability, which can fall back).
        val activity =
            activityRef.get()
                ?: throw CapabilityException.unavailable("an Activity for the sign-in UI")

        val option =
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(!interactive)
                .setAutoSelectEnabled(!interactive)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response =
            try {
                credentialManager.getCredential(activity, request)
            } catch (e: NoCredentialException) {
                throw CapabilityException(
                    BridgeErrorCode.UNAUTHENTICATED,
                    if (interactive) {
                        "no Google account is available on this device"
                    } else {
                        "no previously authorized Google account; sign in interactively first"
                    },
                    e,
                )
            } catch (e: GetCredentialCancellationException) {
                throw CapabilityException(BridgeErrorCode.CANCELLED, "sign-in was dismissed", e)
            } catch (e: GetCredentialException) {
                // e.type is Credential Manager's own stable code, not a free-text
                // message, so it is safe to pass across the bridge.
                throw CapabilityException(
                    BridgeErrorCode.INTERNAL,
                    "credential manager failed (${e.type})",
                    e,
                )
            }
        return toAuthToken(response)
    }

    /**
     * No-op by design. Credential Manager mints a freshly signed ID token on every
     * [fetchToken]; there is no provider-side cache holding the rejected one, and
     * [CachingAuthCapability] has already dropped ours by the time this is called.
     */
    override suspend fun invalidate(token: AuthToken) {
        // Intentionally empty — see KDoc.
    }

    override suspend fun currentAccount(): Account? = account

    override suspend fun signOut() {
        account = null
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }

    private fun toAuthToken(response: GetCredentialResponse): AuthToken {
        val credential = response.credential
        val isGoogleId =
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        if (!isGoogleId) {
            throw CapabilityException(
                BridgeErrorCode.INTERNAL,
                "unexpected credential type '${credential.type}'",
            )
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        // GoogleIdTokenCredential.id is the account's email address. The stable
        // subject id lives in the JWT's `sub` claim, which we deliberately do not
        // parse (see the class KDoc), so the email serves as the account identity.
        account = Account(id = google.id, email = google.id)
        return AuthToken(
            accessToken = google.idToken,
            expiresAt = clock() + tokenLifetimeMillis,
            scopes = emptyList(),
        )
    }

    private companion object {
        /** Google ID tokens are valid for an hour; stop five minutes short of that. */
        const val DEFAULT_ID_TOKEN_LIFETIME_MILLIS = 55 * 60 * 1000L
    }
}
