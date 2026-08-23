package ac.onyx.permetic.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `HostKind` in `index.d.ts`. */
@Serializable
public enum class HostKind {
    @SerialName("webview")
    WEBVIEW,

    @SerialName("headless")
    HEADLESS,
}

@Serializable
public enum class AppLifecycleState {
    @SerialName("foreground")
    FOREGROUND,

    @SerialName("background")
    BACKGROUND,
}

@Serializable
public enum class LogLevel {
    @SerialName("debug")
    DEBUG,

    @SerialName("info")
    INFO,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR,
}

/** Mirrors the anonymous return shape of `SystemCapability.info()`. */
@Serializable
public data class SystemInfo(
    val host: HostKind,
    val appVersion: String,
    val appVersionCode: Int,
    val osVersion: Int,
    val locale: String,
    val lifecycle: AppLifecycleState,
)

/** Mirrors the anonymous `share()` payload shape. */
@Serializable
public data class SharePayload(
    val title: String? = null,
    val text: String? = null,
    val url: String? = null,
)

/** Mirrors `SystemCapability` in `index.d.ts`. */
public interface SystemCapability : PermeticCapability {
    public override val name: CapabilityName get() = CapabilityName.SYSTEM

    public suspend fun info(): SystemInfo

    public fun log(
        level: LogLevel,
        message: String,
    )

    public suspend fun share(payload: SharePayload)

    public suspend fun openUrl(url: String)

    public fun onLifecycle(): Flow<AppLifecycleState>
}
