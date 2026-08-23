package ac.onyx.permetic.transport

import ac.onyx.permetic.capability.CapabilityName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Mirrors `CONTRACT_VERSION` in `index.d.ts`. */
public const val CONTRACT_VERSION: Int = 1

/** Mirrors `BridgeRequest` in `index.d.ts`. */
@Serializable
public data class BridgeRequest(
    val v: Int = CONTRACT_VERSION,
    val id: String,
    val capability: CapabilityName,
    val method: String,
    val args: List<JsonElement> = emptyList(),
)

/**
 * Mirrors the `BridgeResponse` discriminated union in `index.d.ts`. The TS union
 * discriminates on the `ok` boolean with two entirely different field sets (`value`
 * vs `error`), not a `type` tag — [BridgeResponseSerializer] builds/reads that exact
 * wire shape directly rather than relying on kotlinx.serialization's default sealed
 * polymorphism (which would add its own discriminator field).
 */
@Serializable(with = BridgeResponseSerializer::class)
public sealed interface BridgeResponse {
    public val v: Int
    public val id: String

    public data class Success(
        override val v: Int,
        override val id: String,
        val value: JsonElement,
    ) : BridgeResponse

    public data class Failure(
        override val v: Int,
        override val id: String,
        val error: BridgeError,
    ) : BridgeResponse
}

public object BridgeResponseSerializer : KSerializer<BridgeResponse> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ac.onyx.permetic.transport.BridgeResponse")

    override fun serialize(
        encoder: Encoder,
        value: BridgeResponse,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("BridgeResponse can only be serialized to JSON")
        val element =
            when (value) {
                is BridgeResponse.Success ->
                    buildJsonObject {
                        put("v", value.v)
                        put("id", value.id)
                        put("ok", true)
                        put("value", value.value)
                    }
                is BridgeResponse.Failure ->
                    buildJsonObject {
                        put("v", value.v)
                        put("id", value.id)
                        put("ok", false)
                        put(
                            "error",
                            jsonEncoder.json.encodeToJsonElement(
                                BridgeError.serializer(),
                                value.error,
                            ),
                        )
                    }
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): BridgeResponse {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("BridgeResponse can only be deserialized from JSON")
        val obj =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw IllegalArgumentException("BridgeResponse must be a JSON object")
        val v = obj.getValue("v").jsonPrimitive.int
        val id = obj.getValue("id").jsonPrimitive.content
        val ok =
            obj["ok"]?.jsonPrimitive?.boolean
                ?: throw IllegalArgumentException("BridgeResponse is missing required field 'ok'")
        return if (ok) {
            val value =
                obj["value"]
                    ?: throw IllegalArgumentException("BridgeResponse ok=true is missing 'value'")
            BridgeResponse.Success(v, id, value)
        } else {
            val errorElement =
                obj["error"]
                    ?: throw IllegalArgumentException("BridgeResponse ok=false is missing 'error'")
            val error =
                jsonDecoder.json.decodeFromJsonElement(
                    BridgeError.serializer(),
                    errorElement,
                )
            BridgeResponse.Failure(v, id, error)
        }
    }
}

/** Mirrors `BridgeEvent` in `index.d.ts`. Unsolicited native -> JS message. */
@Serializable
public data class BridgeEvent(
    val v: Int = CONTRACT_VERSION,
    val capability: CapabilityName,
    val subscription: String,
    val payload: JsonElement,
)
