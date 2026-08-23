package ac.onyx.permetic.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Mirrors `AuthToken` in `index.d.ts`. [expiresAt] is epoch millis. */
@Serializable
public data class AuthToken(
    val accessToken: String,
    val expiresAt: Long,
    val scopes: List<String>,
)

/** Mirrors the anonymous `{ id, email? }` shape returned by `currentAccount()`. */
@Serializable
public data class Account(
    val id: String,
    val email: String? = null,
)

/** Mirrors `AuthCapability` in `index.d.ts`. */
public interface AuthCapability {
    /**
     * webview: may show an interactive account picker when [interactive] is true.
     * headless: MUST NOT show UI. Rejects UNAUTHENTICATED if no cached token.
     */
    public suspend fun getToken(
        scopes: List<String>,
        interactive: Boolean = false,
    ): AuthToken

    /** Forces a refresh, e.g. after Drive returns 401. */
    public suspend fun refresh(scopes: List<String>): AuthToken

    public suspend fun signOut()

    public suspend fun currentAccount(): Account?

    public fun onAccountChange(): Flow<String?>
}
