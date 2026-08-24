package ac.onyx.permetic.auth

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AuthToken

/**
 * The identity-provider half of `auth`, kept deliberately separate from
 * [CachingAuthCapability] so that caching, single-flight and account-change
 * bookkeeping are provider-independent and unit-testable without an emulator or a
 * Google account.
 *
 * This split is also what keeps `permetic-core` free of Play Services: the concrete
 * Google implementation lives in the optional `:permetic-auth-google` module (spec 01,
 * open decision D-1), and a non-GMS build can supply its own.
 *
 * Implementations do **no caching of their own** — every [fetchToken] call should
 * really go and get a token. [CachingAuthCapability] decides when that is necessary.
 */
public interface TokenProvider {
    /**
     * @param scopes OAuth scopes, or empty when the caller just wants an identity
     *   token. Providers that cannot satisfy a non-empty scope set should throw
     *   `CapabilityException(UNAVAILABLE, ...)` rather than silently returning an
     *   unscoped token.
     * @param interactive when false the provider must not show UI, and should throw
     *   `CapabilityException(UNAUTHENTICATED, ...)` if it cannot proceed silently.
     */
    public suspend fun fetchToken(
        scopes: List<String>,
        interactive: Boolean,
    ): AuthToken

    /**
     * Drops [token] from whatever provider-side cache exists (GMS keeps its own,
     * independent of ours), so the next [fetchToken] is a genuine round trip. Called
     * on the `refresh()` path — i.e. after a downstream API rejected [token] with a
     * 401 — before the replacement is requested.
     */
    public suspend fun invalidate(token: AuthToken)

    public suspend fun currentAccount(): Account?

    public suspend fun signOut()
}
