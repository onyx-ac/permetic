package ac.onyx.permetic.transport

import ac.onyx.permetic.capability.CapabilityName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property-style tests: arbitrary nested [JsonElement] shapes round-trip through the
 * envelope, and malformed/truncated input always fails as a caught
 * [SerializationException] rather than an uncaught crash — the envelope boundary
 * never lets a raw parser exception escape (see root CLAUDE.md: errors cross as
 * [BridgeError] codes, never raw exceptions).
 */
class EnvelopeFuzzTest {
    private fun randomJsonElement(
        random: Random,
        depth: Int = 0,
    ): JsonElement {
        val kinds = if (depth >= 3) 4 else 6
        return when (random.nextInt(kinds)) {
            0 -> JsonNull
            1 -> JsonPrimitive(random.nextBoolean())
            2 -> JsonPrimitive(random.nextInt(-100_000, 100_000))
            3 -> JsonPrimitive(randomString(random))
            4 ->
                JsonArray(
                    (0 until random.nextInt(0, 4)).map { randomJsonElement(random, depth + 1) },
                )
            else ->
                JsonObject(
                    (0 until random.nextInt(0, 4)).associate {
                        randomString(random) to randomJsonElement(random, depth + 1)
                    },
                )
        }
    }

    private fun randomString(random: Random): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789 \"'\\{}[]:,\t\n"
        return (0 until random.nextInt(0, 12)).map {
            chars[
                random.nextInt(
                    chars.length,
                ),
            ]
        }.joinToString("")
    }

    @Test
    fun `BridgeRequest args round-trip through random nested JSON shapes`() {
        val random = Random(42)
        repeat(200) { iteration ->
            val args = (0 until random.nextInt(0, 5)).map { randomJsonElement(random) }
            val original =
                BridgeRequest(
                    id = "fuzz-$iteration",
                    capability =
                        CapabilityName.entries[
                            random.nextInt(
                                CapabilityName.entries.size,
                            ),
                        ],
                    method = "method$iteration",
                    args = args,
                )
            val decoded =
                EnvelopeCodec.decodeRequest(
                    Json.encodeToString(BridgeRequest.serializer(), original),
                )
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `BridgeResponse success value round-trips through random nested JSON shapes`() {
        val random = Random(7)
        repeat(200) { iteration ->
            val original =
                BridgeResponse.Success(
                    v = CONTRACT_VERSION,
                    id = "fuzz-$iteration",
                    value = randomJsonElement(random),
                )
            val decoded =
                Json.decodeFromString(
                    BridgeResponseSerializer,
                    EnvelopeCodec.encodeResponse(original),
                )
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `BridgeEvent payload round-trips through random nested JSON shapes`() {
        val random = Random(13)
        repeat(200) { iteration ->
            val original =
                BridgeEvent(
                    capability =
                        CapabilityName.entries[
                            random.nextInt(
                                CapabilityName.entries.size,
                            ),
                        ],
                    subscription = "sub-$iteration",
                    payload = randomJsonElement(random),
                )
            val decoded =
                Json.decodeFromString(
                    BridgeEvent.serializer(),
                    EnvelopeCodec.encodeEvent(original),
                )
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `truncated request json never throws anything other than SerializationException`() {
        val random = Random(99)
        val validText =
            Json.encodeToString(
                BridgeRequest.serializer(),
                BridgeRequest(
                    id = "x",
                    capability = CapabilityName.SYSTEM,
                    method = "info",
                    args = emptyList(),
                ),
            )
        repeat(50) {
            val truncated = validText.substring(0, random.nextInt(1, validText.length))
            runCatching { EnvelopeCodec.decodeRequest(truncated) }
                .onFailure {
                        e ->
                    assert(e is SerializationException) { "unexpected exception type: $e" }
                }
        }
    }
}
