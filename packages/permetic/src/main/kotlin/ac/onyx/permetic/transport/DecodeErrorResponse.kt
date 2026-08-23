package ac.onyx.permetic.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the [BridgeResponse.Failure] sent back when [text] failed to decode as a
 * [BridgeRequest] — an unrecognised capability/method name, or a schema violation.
 * Best-effort id extraction keeps the reply correlated with the request that caused
 * it; truly unparseable JSON has no id to correlate against, so the response falls
 * back to a fixed placeholder rather than the reply being dropped entirely.
 *
 * Shared by `WebViewCarrier` and `JavascriptInterfaceFallback` (transport/android/)
 * so there is exactly one decode-error mapping behind the two transport entry
 * adapters, per ADR-0002.
 */
public fun decodeErrorResponse(
    text: String,
    error: Throwable,
): BridgeResponse {
    val id =
        runCatching { Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content }
            .getOrNull() ?: "unknown"
    return BridgeResponse.Failure(
        v = CONTRACT_VERSION,
        id = id,
        error =
            BridgeError(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = error.message ?: "malformed BridgeRequest",
            ),
    )
}
