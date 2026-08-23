package ac.onyx.permetic.internal

import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.EnvelopeCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/** Positional argument decoding for [ac.onyx.permetic.transport.BridgeRequest.args]. */
internal inline fun <reified T> List<JsonElement>.decode(index: Int): T {
    val element =
        getOrNull(index)
            ?: throw DispatchException(
                BridgeErrorCode.INVALID_ARGUMENT,
                "missing argument at index $index",
            )
    return try {
        EnvelopeCodec.json.decodeFromJsonElement(serializer(), element)
    } catch (e: SerializationException) {
        throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "argument $index: ${e.message}",
            cause = e,
        )
    }
}

/** Like [decode], but for an optional trailing argument the caller may have omitted. */
internal inline fun <reified T> List<JsonElement>.decodeOptional(
    index: Int,
    default: T,
): T {
    val element = getOrNull(index) ?: return default
    return try {
        EnvelopeCodec.json.decodeFromJsonElement(serializer(), element)
    } catch (e: SerializationException) {
        throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "argument $index: ${e.message}",
            cause = e,
        )
    }
}

internal inline fun <reified T> encodeResult(value: T): JsonElement =
    EnvelopeCodec.json.encodeToJsonElement(serializer(), value)
