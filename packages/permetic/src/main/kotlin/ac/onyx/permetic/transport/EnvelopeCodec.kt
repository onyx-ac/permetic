package ac.onyx.permetic.transport

import kotlinx.serialization.json.Json

/**
 * The single shared JSON codec for the envelope. [Json.ignoreUnknownKeys] lets native
 * tolerate a newer JS contract version's added-but-optional fields instead of
 * crashing; [Json.explicitNulls] off keeps omitted optional fields out of the wire
 * format, matching how the TS side naturally omits them.
 *
 * Malformed input throws [kotlinx.serialization.SerializationException], never a raw
 * unchecked parser crash — callers map that to a [BridgeErrorCode.INVALID_ARGUMENT]
 * response rather than letting it propagate as a stack trace.
 */
public object EnvelopeCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    public fun decodeRequest(text: String): BridgeRequest =
        json.decodeFromString(BridgeRequest.serializer(), text)

    public fun encodeResponse(response: BridgeResponse): String =
        json.encodeToString(BridgeResponseSerializer, response)

    public fun encodeEvent(event: BridgeEvent): String =
        json.encodeToString(BridgeEvent.serializer(), event)
}
