package ac.onyx.permetic.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mirrors `BridgeErrorCode` / `BridgeError` in `permetic-web/src/index.d.ts`.
 *
 * JVM-only, no Android types: this package is the transport layer and stays testable
 * without Robolectric or instrumentation (see spec 01, task 2).
 */
@Serializable
public enum class BridgeErrorCode {
    @SerialName("UNAVAILABLE")
    UNAVAILABLE,

    @SerialName("NOT_FOUND")
    NOT_FOUND,

    @SerialName("CONFLICT")
    CONFLICT,

    @SerialName("UNAUTHENTICATED")
    UNAUTHENTICATED,

    @SerialName("PERMISSION_DENIED")
    PERMISSION_DENIED,

    @SerialName("CANCELLED")
    CANCELLED,

    @SerialName("NETWORK")
    NETWORK,

    @SerialName("INVALID_ARGUMENT")
    INVALID_ARGUMENT,

    @SerialName("INTERNAL")
    INTERNAL,
}

/** [details] never contains a stack trace in release builds. */
@Serializable
public data class BridgeError(
    val code: BridgeErrorCode,
    val message: String,
    val details: Map<String, JsonElement>? = null,
)
