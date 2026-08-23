package ac.onyx.permetic.transport

import ac.onyx.permetic.capability.CapabilityName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeCodecTest {
    @Test
    fun `decodes a BridgeRequest matching the contract field names`() {
        val text =
            """
            {"v":1,"id":"req-1","capability":"auth","method":"getToken",
            "args":[["drive"],true]}
            """.trimIndent()
        val request = EnvelopeCodec.decodeRequest(text)
        assertEquals(1, request.v)
        assertEquals("req-1", request.id)
        assertEquals(CapabilityName.AUTH, request.capability)
        assertEquals("getToken", request.method)
        assertEquals(2, request.args.size)
    }

    @Test
    fun `round-trips a BridgeRequest`() {
        val original =
            BridgeRequest(
                id = "req-2",
                capability = CapabilityName.SYSTEM,
                method = "info",
                args = emptyList(),
            )
        val decoded =
            EnvelopeCodec.decodeRequest(
                Json.encodeToString(BridgeRequest.serializer(), original),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `encodes a successful BridgeResponse with the exact contract field names`() {
        val original =
            BridgeResponse.Success(
                v = CONTRACT_VERSION,
                id = "req-3",
                value = JsonPrimitive("ok"),
            )
        val text = EnvelopeCodec.encodeResponse(original)
        assertEquals("""{"v":1,"id":"req-3","ok":true,"value":"ok"}""", text)
    }

    @Test
    fun `encodes a failed BridgeResponse with the error nested, not the value`() {
        val error =
            BridgeError(
                code = BridgeErrorCode.UNAVAILABLE,
                message = "no auth capability registered",
            )
        val original = BridgeResponse.Failure(v = CONTRACT_VERSION, id = "req-4", error = error)
        val text = EnvelopeCodec.encodeResponse(original)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(false), obj.getValue("ok"))
        assertEquals(
            "UNAVAILABLE",
            obj.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun `round-trips a successful BridgeResponse`() {
        val original =
            BridgeResponse.Success(
                v = CONTRACT_VERSION,
                id = "req-5",
                value = JsonPrimitive(42),
            )
        val decoded =
            Json.decodeFromString(
                BridgeResponseSerializer,
                EnvelopeCodec.encodeResponse(original),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips a failed BridgeResponse`() {
        val original =
            BridgeResponse.Failure(
                v = CONTRACT_VERSION,
                id = "req-6",
                error =
                    BridgeError(
                        code = BridgeErrorCode.CANCELLED,
                        message = "WebView destroyed",
                    ),
            )
        val decoded =
            Json.decodeFromString(
                BridgeResponseSerializer,
                EnvelopeCodec.encodeResponse(original),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips a BridgeEvent`() {
        val original =
            BridgeEvent(
                capability = CapabilityName.PUSH,
                subscription = "sub-1",
                payload = JsonPrimitive("token-value"),
            )
        val decoded =
            Json.decodeFromString(
                BridgeEvent.serializer(),
                EnvelopeCodec.encodeEvent(original),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown fields on a request are ignored, not fatal`() {
        val text =
            """
            {"v":1,"id":"req-7","capability":"system","method":"info","args":[],
            "future":"field"}
            """.trimIndent()
        val request = EnvelopeCodec.decodeRequest(text)
        assertEquals("req-7", request.id)
    }

    @Test
    fun `malformed json throws SerializationException rather than a raw parser crash`() {
        assertFailsWith<SerializationException> {
            EnvelopeCodec.decodeRequest("{not valid json")
        }
    }

    @Test
    fun `a BridgeResponse missing the ok field fails to decode with a clear error`() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(BridgeResponseSerializer, """{"v":1,"id":"req-8"}""")
        }
    }

    @Test
    fun `a BridgeResponse that is not a json object fails to decode`() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(BridgeResponseSerializer, "42")
        }
    }
}
