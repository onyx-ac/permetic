package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.AuthorizationResult
import ac.onyx.permetic.capability.HostKind
import ac.onyx.permetic.capability.LogLevel
import ac.onyx.permetic.capability.SharePayload
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.capability.SystemInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared across [CapabilityRegistryTest] and [DispatcherTest] — top-level `private`
 * classes don't get per-file name mangling in Kotlin, so declaring these twice in
 * the same package is a genuine binary-name collision, not just a style choice.
 * Empty method bodies below are deliberate no-op fakes, not oversights.
 */
@Suppress("EmptyFunctionBlock")
internal class FakeSystemCapability(
    val lifecycle: MutableSharedFlow<AppLifecycleState> = MutableSharedFlow(),
) : SystemCapability {
    var lastLog: Pair<LogLevel, String>? = null

    override suspend fun info(): SystemInfo =
        SystemInfo(HostKind.WEBVIEW, "1.0", 1, 34, "en-US", AppLifecycleState.FOREGROUND)

    override fun log(
        level: LogLevel,
        message: String,
    ) {
        lastLog = level to message
    }

    override suspend fun share(payload: SharePayload) {}

    override suspend fun openUrl(url: String) {}

    override fun onLifecycle(): Flow<AppLifecycleState> = lifecycle
}

@Suppress("EmptyFunctionBlock")
internal class FakeAuthCapability(
    private val idToken: String? = "id-token",
) : AuthCapability {
    var signInCalls: Int = 0
    var lastNonce: String? = null

    override suspend fun supported(): Boolean = true

    override suspend fun signIn(nonce: String?): String? {
        signInCalls++
        lastNonce = nonce
        return idToken
    }

    override suspend fun authorize(scopes: List<String>): AuthorizationResult? =
        AuthorizationResult("access-token", expiresAt = null, grantedScopes = scopes)

    override suspend fun authorizeOffline(scopes: List<String>): String? = "server-auth-code"

    override suspend fun grantedScopes(): List<String> = emptyList()

    override suspend fun revoke(scopes: List<String>) {}

    override suspend fun signOut() {}

    override suspend fun account(): Account? = null

    override fun onAccountChange(): Flow<String?> = emptyFlow()
}
