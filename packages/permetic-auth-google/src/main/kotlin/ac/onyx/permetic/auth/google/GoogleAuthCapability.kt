package ac.onyx.permetic.auth.google

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.AuthorizationResult
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.transport.BridgeErrorCode
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference

/**
 * Google identity via Credential Manager (spec 08; ADR 0009). Implements
 * [AuthCapability] directly — there is no provider SPI between them, because with
 * caching gone there is nothing for a middle layer to do.
 *
 * **Returns a Google ID token, not an OAuth access token.** [signIn] yields the signed
 * JWT the page hands to Firebase `signInWithCredential`, which is what a
 * Firestore-backed app needs. [authorize] and [authorizeOffline] are declared by the
 * contract but not implemented here yet: they need the Play Services authorization
 * client plus an activity-result consent flow, and spec 08 defers them until Drive or
 * Calendar are actually built. They answer `UNAVAILABLE` rather than quietly returning
 * an unscoped token.
 *
 * **Nothing is cached.** Every [signIn] really asks Credential Manager, which mints a
 * freshly signed token each time. See [AuthCapability]'s KDoc for why a cache here
 * would be actively wrong rather than merely unnecessary.
 */
@Suppress("TooManyFunctions") // The count is AuthCapability's, not this class's.
public class GoogleAuthCapability(
    activity: Activity,
    private val serverClientId: String,
) : AuthCapability {
    private val appContext: Context = activity.applicationContext
    private val credentialManager = CredentialManager.create(appContext)

    @Volatile
    private var activityRef = WeakReference(activity)

    @Volatile
    private var currentAccount: Account? = null

    private val accountChanges =
        MutableSharedFlow<String?>(
            extraBufferCapacity = ACCOUNT_EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Re-points at the post-configuration-change `Activity`. */
    public fun rebind(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /**
     * A heuristic, and deliberately a cheap one: whether Play Services is installed and
     * enabled. Credential Manager exposes no "can you supply a Google ID token" query,
     * and the authoritative answer only arrives when [signIn] runs. Checking the
     * package keeps this module free of `play-services-base` for one boolean.
     */
    @Suppress("SwallowedException") // "package not installed" is the answer, not an error.
    override suspend fun supported(): Boolean =
        try {
            appContext.packageManager
                .getApplicationInfo(PLAY_SERVICES_PACKAGE, 0)
                .enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    // The cancellation catch below deliberately discards the exception: a dismissed
    // chooser is a result, and propagating it as a cause would drag an error object
    // through a success path (spec 08).
    @Suppress("SwallowedException")
    override suspend fun signIn(nonce: String?): String? {
        // Credential Manager renders over an Activity; the application context will not
        // do, so an absent Activity is a genuine UNAVAILABLE.
        val activity =
            activityRef.get()
                ?: throw CapabilityException.unavailable("an Activity for the sign-in UI")

        val option =
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                // False so the user can pick a different account or add one; this is an
                // explicit sign-in, not a silent resume.
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .apply { nonce?.let { setNonce(it) } }
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response =
            try {
                credentialManager.getCredential(activity, request)
            } catch (e: GetCredentialCancellationException) {
                // The single most common outcome after success. A result, not an error.
                return null
            } catch (e: NoCredentialException) {
                throw CapabilityException(
                    BridgeErrorCode.UNAUTHENTICATED,
                    "no Google account is available on this device",
                    e,
                )
            } catch (e: GetCredentialException) {
                // e.type is Credential Manager's own stable code, not free text, so it
                // is safe to pass across the bridge.
                throw CapabilityException(
                    BridgeErrorCode.INTERNAL,
                    "credential manager failed (${e.type})",
                    e,
                )
            }
        return idTokenFrom(response)
    }

    override suspend fun authorize(scopes: List<String>): AuthorizationResult? =
        throw notImplemented("authorize")

    override suspend fun authorizeOffline(scopes: List<String>): String? =
        throw notImplemented("authorizeOffline")

    override suspend fun grantedScopes(): List<String> = throw notImplemented("grantedScopes")

    /**
     * With no scoped authorization implemented there is nothing scope-shaped to revoke,
     * so a scoped call is refused rather than silently treated as a sign-out. Revoking
     * everything is the sign-out path.
     */
    override suspend fun revoke(scopes: List<String>) {
        if (scopes.isNotEmpty()) throw notImplemented("revoke(scopes)")
        signOut()
    }

    override suspend fun signOut() {
        val had = currentAccount != null
        currentAccount = null
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        if (had) accountChanges.emit(null)
    }

    override suspend fun account(): Account? = currentAccount

    /**
     * Currently fires only on changes this capability made — sign-in, sign-out. The
     * case that justifies the member existing at all, an account removed on the device
     * or access revoked at myaccount.google.com, needs an `AccountManager` listener and
     * a decision about `GET_ACCOUNTS`; until then those changes surface as a failure on
     * the next call rather than as an event. Recorded in spec 08 as a follow-up.
     */
    override fun onAccountChange(): Flow<String?> = accountChanges.asSharedFlow()

    private suspend fun idTokenFrom(response: GetCredentialResponse): String {
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
        // GoogleIdTokenCredential.id is the account's email. The stable subject id lives
        // in the JWT's `sub` claim, which is deliberately not parsed — this capability
        // never verifies that signature, so reading claims from it would be theatre.
        val account = Account(id = google.id, email = google.id)
        if (account != currentAccount) {
            currentAccount = account
            accountChanges.emit(account.id)
        }
        return google.idToken
    }

    private fun notImplemented(member: String): CapabilityException =
        CapabilityException(
            BridgeErrorCode.UNAVAILABLE,
            "$member needs scoped OAuth, which this provider does not implement yet",
        )

    private companion object {
        const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        const val ACCOUNT_EVENT_BUFFER = 8
    }
}
