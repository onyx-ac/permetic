package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.AuthToken
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.BridgeEvent
import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun request(
    capability: CapabilityName,
    method: String,
    args: List<kotlinx.serialization.json.JsonElement> = emptyList(),
) = BridgeRequest(id = "req-1", capability = capability, method = method, args = args)

/**
 * Exercises spec 01's core guarantee end to end through the real [Dispatcher], not
 * just the registry lookup ([CapabilityRegistryTest]) in isolation. Subscription
 * tests use [kotlinx.coroutines.test.TestScope.backgroundScope] for the
 * `Dispatcher`'s Flow-collecting coroutines, since `flow.collect` never completes on
 * its own — using the test's own scope would make `runTest` hang forever waiting
 * for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherTest {
    @Test
    fun `dispatching to an unregistered capability returns UNAVAILABLE, not a crash`() =
        runTest {
            val dispatcher = Dispatcher(CapabilityRegistry(), backgroundScope) {}

            val response = dispatcher.dispatch(request(CapabilityName.PUSH, "getToken"))

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.UNAVAILABLE, response.error.code)
        }

    @Test
    fun `storage is always UNAVAILABLE - this build never registers it`() =
        runTest {
            val dispatcher = Dispatcher(CapabilityRegistry(), backgroundScope) {}

            val response = dispatcher.dispatch(request(CapabilityName.STORAGE, "info"))

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.UNAVAILABLE, response.error.code)
        }

    @Test
    fun `dispatches a one-shot method to the registered capability and encodes the result`() =
        runTest {
            val registry = CapabilityRegistry().apply { register(FakeAuthCapability()) }
            val dispatcher = Dispatcher(registry, backgroundScope) {}

            val response =
                dispatcher.dispatch(
                    request(
                        CapabilityName.AUTH,
                        "getToken",
                        listOf(JsonArray(listOf(JsonPrimitive("drive")))),
                    ),
                )

            assertTrue(response is BridgeResponse.Success)
            val token =
                kotlinx.serialization.json.Json.decodeFromJsonElement(
                    AuthToken.serializer(),
                    response.value,
                )
            assertEquals("tok", token.accessToken)
        }

    @Test
    fun `an unknown method returns INVALID_ARGUMENT`() =
        runTest {
            val registry = CapabilityRegistry().apply { register(FakeAuthCapability()) }
            val dispatcher = Dispatcher(registry, backgroundScope) {}

            val response = dispatcher.dispatch(request(CapabilityName.AUTH, "bogusMethod"))

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.INVALID_ARGUMENT, response.error.code)
        }

    @Test
    fun `a missing required argument returns INVALID_ARGUMENT instead of crashing`() =
        runTest {
            val registry = CapabilityRegistry().apply { register(FakeAuthCapability()) }
            val dispatcher = Dispatcher(registry, backgroundScope) {}

            val response = dispatcher.dispatch(request(CapabilityName.AUTH, "getToken"))

            assertTrue(response is BridgeResponse.Failure)
            assertEquals(BridgeErrorCode.INVALID_ARGUMENT, response.error.code)
        }

    @Test
    fun `subscribing returns a subscription id and a later Flow emission is pushed as an event`() =
        runTest {
            val fakeSystem = FakeSystemCapability()
            val registry = CapabilityRegistry().apply { register(fakeSystem) }
            val events = mutableListOf<BridgeEvent>()
            val dispatcher = Dispatcher(registry, backgroundScope) { events.add(it) }

            val response = dispatcher.dispatch(request(CapabilityName.SYSTEM, "onLifecycle"))
            assertTrue(response is BridgeResponse.Success)
            val subscriptionId = (response.value as JsonPrimitive).content
            // The collector coroutine is only scheduled, not yet running - runCurrent()
            // (not advanceUntilIdle(), which doesn't reliably do this on its own right
            // after a synchronous dispatch() call - verified empirically) lets it start
            // and actually subscribe to the SharedFlow before emitting into it.
            runCurrent()

            fakeSystem.lifecycle.emit(AppLifecycleState.BACKGROUND)
            advanceUntilIdle()

            assertEquals(1, events.size)
            assertEquals(subscriptionId, events[0].subscription)
            assertEquals(CapabilityName.SYSTEM, events[0].capability)
        }

    @Test
    fun `unsubscribe stops further event delivery for that subscription`() =
        runTest {
            val fakeSystem = FakeSystemCapability()
            val registry = CapabilityRegistry().apply { register(fakeSystem) }
            val events = mutableListOf<BridgeEvent>()
            val dispatcher = Dispatcher(registry, backgroundScope) { events.add(it) }

            val subscribeResponse =
                dispatcher.dispatch(
                    request(CapabilityName.SYSTEM, "onLifecycle"),
                )
            val subscriptionId =
                ((subscribeResponse as BridgeResponse.Success).value as JsonPrimitive).content
            runCurrent()

            dispatcher.dispatch(
                request(
                    CapabilityName.SYSTEM,
                    "unsubscribe",
                    listOf(JsonPrimitive(subscriptionId)),
                ),
            )
            advanceUntilIdle()
            fakeSystem.lifecycle.emit(AppLifecycleState.FOREGROUND)
            advanceUntilIdle()

            assertTrue(events.isEmpty())
        }

    @Test
    fun `cancelAllSubscriptions stops every running subscription`() =
        runTest {
            val fakeSystem = FakeSystemCapability()
            val registry = CapabilityRegistry().apply { register(fakeSystem) }
            val events = mutableListOf<BridgeEvent>()
            val dispatcher = Dispatcher(registry, backgroundScope) { events.add(it) }

            dispatcher.dispatch(request(CapabilityName.SYSTEM, "onLifecycle"))
            runCurrent()
            dispatcher.cancelAllSubscriptions()
            advanceUntilIdle()
            fakeSystem.lifecycle.emit(AppLifecycleState.FOREGROUND)
            advanceUntilIdle()

            assertTrue(events.isEmpty())
        }

    @Test
    fun `a void-returning method still produces a well-formed Success response`() =
        runTest {
            val registry = CapabilityRegistry().apply { register(FakeAuthCapability()) }
            val dispatcher = Dispatcher(registry, backgroundScope) {}

            val response = dispatcher.dispatch(request(CapabilityName.AUTH, "signOut"))

            assertTrue(response is BridgeResponse.Success)
        }
}
