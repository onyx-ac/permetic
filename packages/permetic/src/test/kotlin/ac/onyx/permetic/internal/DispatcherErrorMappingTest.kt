package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.LogLevel
import ac.onyx.permetic.capability.SharePayload
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.capability.SystemInfo
import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 6 is the first round with real capability implementations behind the
 * dispatcher, so it is the first time a capability can throw. Two guarantees matter:
 * a declared [CapabilityException] reaches JS as its own contract code, and anything
 * else becomes an opaque `INTERNAL` — never an escaping exception, which would leave
 * `PermeticController.trackedDispatch` awaiting a result that never arrives and hang
 * the web app's promise forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherErrorMappingTest {
    private class ThrowingSystemCapability(private val failure: Throwable) : SystemCapability {
        override suspend fun info(): SystemInfo = throw failure

        override fun log(
            level: LogLevel,
            message: String,
        ) = throw failure

        override suspend fun share(payload: SharePayload): Unit = throw failure

        override suspend fun openUrl(url: String): Unit = throw failure

        override fun onLifecycle(): Flow<AppLifecycleState> = emptyFlow()
    }

    private fun dispatcherFor(failure: Throwable): Dispatcher {
        val registry = CapabilityRegistry()
        registry.register(ThrowingSystemCapability(failure))
        return Dispatcher(registry, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())) {}
    }

    private val infoRequest =
        BridgeRequest(
            id = "req-1",
            capability = CapabilityName.SYSTEM,
            method = "info",
            args = emptyList(),
        )

    @Test
    fun `a CapabilityException crosses as its own contract error code`() =
        runTest {
            val dispatcher =
                dispatcherFor(CapabilityException.unauthenticated("no cached token"))

            val response = dispatcher.dispatch(infoRequest)

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.UNAUTHENTICATED, response.error.code)
            assertEquals("no cached token", response.error.message)
        }

    @Test
    fun `an unexpected exception becomes INTERNAL instead of escaping the dispatcher`() =
        runTest {
            val dispatcher = dispatcherFor(IllegalStateException("boom"))

            val response = dispatcher.dispatch(infoRequest)

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.INTERNAL, response.error.code)
        }

    @Test
    fun `an unexpected exception's own message never crosses the bridge`() =
        runTest {
            val secret = "postgres://user:hunter2@db.internal/prod"
            val dispatcher = dispatcherFor(IllegalStateException(secret))

            val response = dispatcher.dispatch(infoRequest)

            assertTrue(response is BridgeResponse.Failure)
            assertFalse(response.error.message.contains("hunter2"))
            assertFalse(response.error.message.contains(secret))
            // The type name is deliberately kept: enough to triage, nothing to leak.
            assertTrue(response.error.message.contains("IllegalStateException"))
        }
}
