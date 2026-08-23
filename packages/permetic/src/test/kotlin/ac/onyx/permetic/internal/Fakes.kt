package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.AuthToken
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
internal class FakeAuthCapability : AuthCapability {
    override suspend fun getToken(
        scopes: List<String>,
        interactive: Boolean,
    ): AuthToken = AuthToken("tok", 0, scopes)

    override suspend fun refresh(scopes: List<String>): AuthToken = AuthToken("tok2", 0, scopes)

    override suspend fun signOut() {}

    override suspend fun currentAccount(): Account? = null

    override fun onAccountChange(): Flow<String?> = emptyFlow()
}
