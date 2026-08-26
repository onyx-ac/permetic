package ac.onyx.permetic.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Mirrors `AuthorizationResult` in `index.d.ts`. [expiresAt] is epoch millis. */
@Serializable
public data class AuthorizationResult(
    val accessToken: String,
    /** Null when the provider does not report one; the page owns expiry either way. */
    val expiresAt: Long? = null,
    /** What was actually granted — consent can be partial. */
    val grantedScopes: List<String> = emptyList(),
)

/** Mirrors the anonymous `{ id, email? }` shape returned by `account()`. */
@Serializable
public data class Account(
    val id: String,
    val email: String? = null,
)

/**
 * Mirrors `AuthCapability` in `index.d.ts`. See spec 08.
 *
 * Google refuses OAuth from an embedded WebView by policy (`disallowed_useragent`), so
 * this exists because sign-in *cannot* happen in the page — not because it is merely
 * inconvenient there.
 *
 * **Stateless by contract.** No implementation caches tokens. Credential Manager mints
 * a freshly signed ID token per call and the page exchanges it once for a Firebase
 * session, so a cache saves no round trip while creating a second source of truth about
 * who is signed in. Expiry belongs to the page; when a token dies it asks again. ADR
 * 0009 is explicit about this, and it reverses what spec 01 task 6 first built.
 *
 * **Identity and authorization are separate.** [signIn] asks for identity only; scopes
 * are requested by [authorize] when a feature is switched on. Bundling them means one
 * refusal costs the account rather than the feature, and the app never learns which
 * scope was refused.
 */
public interface AuthCapability : PermeticCapability {
    public override val name: CapabilityName get() = CapabilityName.AUTH

    /**
     * Whether this device can do any of this. Distinct from `available('auth')`, which
     * only reports whether a capability was registered — Play Services is absent on
     * some devices, regions and corporate images, so a registered capability can still
     * be unable to act.
     */
    public suspend fun supported(): Boolean

    /**
     * A Google ID token for the page to hand to Firebase `signInWithCredential`.
     *
     * Returns **null when the user dismissed the chooser**. Dismissal is the most
     * common outcome after success, so it is a result rather than an exception —
     * raising it would make every call site catch and discard a normal interaction.
     *
     * @param nonce optional replay guard; the caller checks it comes back in the
     *   token's claims.
     */
    public suspend fun signIn(nonce: String? = null): String?

    /** Short-lived access token for Google APIs, or null if the user refused. */
    public suspend fun authorize(scopes: List<String>): AuthorizationResult?

    /**
     * A one-time server auth code, exchanged by the app's own service. There is
     * deliberately no member returning a refresh token: a credential that lives until
     * revoked must never be reachable from inside the WebView.
     */
    public suspend fun authorizeOffline(scopes: List<String>): String?

    /** What is actually held right now; people revoke at myaccount.google.com. */
    public suspend fun grantedScopes(): List<String>

    /** An empty [scopes] revokes everything. */
    public suspend fun revoke(scopes: List<String> = emptyList())

    public suspend fun signOut()

    public suspend fun account(): Account?

    /**
     * The signed-in Google account changed. Kept even though the page initiates most
     * changes, because the ones it does not initiate are the ones it cannot otherwise
     * see: an account removed on the device, or access revoked at
     * myaccount.google.com. Emitting that is a notification, not the cached credential
     * state this capability refuses to hold.
     */
    public fun onAccountChange(): Flow<String?>
}
