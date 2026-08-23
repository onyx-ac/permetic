package ac.onyx.permetic.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingRequestTableTest {
    @Test
    fun `resolve completes the matching pending deferred`() =
        runTest {
            val table = PendingRequestTable()
            val deferred = table.register("req-1")

            table.resolve(
                "req-1",
                BridgeResponse.Success(
                    v = CONTRACT_VERSION,
                    id = "req-1",
                    value = JsonPrimitive("ok"),
                ),
            )

            assertEquals(
                BridgeResponse.Success(
                    v = CONTRACT_VERSION,
                    id = "req-1",
                    value = JsonPrimitive("ok"),
                ),
                deferred.await(),
            )
            assertEquals(0, table.size())
        }

    @Test
    fun `resolve for an unknown id is a no-op`() {
        val table = PendingRequestTable()

        table.resolve(
            "missing",
            BridgeResponse.Success(
                v = CONTRACT_VERSION,
                id = "missing",
                value = JsonPrimitive("x"),
            ),
        )

        assertEquals(0, table.size())
    }

    @Test
    fun `cancelAll resolves every still-pending request as the given error and never drops any`() =
        runTest {
            val table = PendingRequestTable()
            val ids = (1..50).map { "req-$it" }
            val deferreds = ids.map { it to table.register(it) }
            val cause = BridgeError(code = BridgeErrorCode.CANCELLED, message = "WebView destroyed")

            table.cancelAll(cause)

            deferreds.forEach { (id, deferred) ->
                val result = deferred.await()
                assertTrue(result is BridgeResponse.Failure)
                assertEquals(BridgeErrorCode.CANCELLED, result.error.code)
                assertEquals(id, result.id)
            }
            assertEquals(0, table.size())
        }

    @Test
    fun `already-resolved requests are unaffected by a later cancelAll`() =
        runTest {
            val table = PendingRequestTable()
            val deferred = table.register("req-1")
            table.resolve(
                "req-1",
                BridgeResponse.Success(
                    v = CONTRACT_VERSION,
                    id = "req-1",
                    value = JsonPrimitive("done"),
                ),
            )

            table.cancelAll(BridgeError(code = BridgeErrorCode.CANCELLED, message = "teardown"))

            assertTrue(deferred.await() is BridgeResponse.Success)
        }

    @Test
    fun `cancelAll on an empty table is a no-op`() {
        val table = PendingRequestTable()

        table.cancelAll(BridgeError(code = BridgeErrorCode.CANCELLED, message = "teardown"))

        assertEquals(0, table.size())
    }

    @Test
    fun `concurrent register and resolve from multiple coroutines never loses a response`() =
        runTest {
            val table = PendingRequestTable()
            val count = 200

            val jobs =
                (1..count).map { i ->
                    async {
                        val id = "req-$i"
                        val deferred = table.register(id)
                        table.resolve(
                            id,
                            BridgeResponse.Success(
                                v = CONTRACT_VERSION,
                                id = id,
                                value = JsonPrimitive(i),
                            ),
                        )
                        deferred.await()
                    }
                }
            val results = jobs.awaitAll()

            assertEquals(count, results.size)
            assertTrue(results.all { it is BridgeResponse.Success })
            assertEquals(0, table.size())
        }
}
