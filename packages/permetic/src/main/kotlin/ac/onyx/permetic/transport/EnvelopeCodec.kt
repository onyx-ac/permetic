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
 *
 * [json] is exposed (not just the top-level encode/decode functions above) because
 * the dispatcher (spec 01 task 5) needs the same configured instance to decode
 * individual [BridgeRequest.args] elements and encode individual method results —
 * one shared `Json`, not a second differently-configured one.
 */
public object EnvelopeCodec {
    public val json: Json =
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
