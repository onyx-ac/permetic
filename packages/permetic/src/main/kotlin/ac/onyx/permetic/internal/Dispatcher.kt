package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.BackgroundCapability
import ac.onyx.permetic.capability.BillingCapability
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.PushCapability
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.transport.BridgeError
import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.BridgeEvent
import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import ac.onyx.permetic.transport.CONTRACT_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The one dispatcher on the Kotlin side (ADR-0002). Looks up whatever is registered
 * in [registry] under a [BridgeRequest]'s capability, decodes [BridgeRequest.args],
 * calls into it, and encodes the result — or maps a [DispatchException] / an
 * unregistered capability to a [BridgeError]. Never lets a raw exception escape:
 * every path through [dispatch] returns a [BridgeResponse].
 *
 * The `when (request.capability)` below has no `else` branch — adding a
 * [CapabilityName] entry without adding its dispatch line here is a compile error,
 * per the contract-freeze design (spec 01 task 1).
 */
internal class Dispatcher(
    private val registry: CapabilityRegistry,
    subscriptionScope: CoroutineScope,
    emitEvent: (BridgeEvent) -> Unit,
) {
    private val subscriptions = SubscriptionRunner(subscriptionScope, emitEvent)

    /**
     * Every path returns a [BridgeResponse] — including the unexpected ones. A
     * capability that throws something arbitrary must not escape here: the caller
     * (`PermeticController.trackedDispatch`) launches this in a separate coroutine
     * and awaits the result, so an escaping exception would leave that `await()`
     * hanging forever and the web app's promise would never settle. The broad catch
     * below is that guarantee, not laziness.
     *
     * [CancellationException] is deliberately re-thrown ahead of it: swallowing it
     * would break structured concurrency and leave `PermeticController.onDestroy()`
     * unable to actually stop work in flight.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun dispatch(request: BridgeRequest): BridgeResponse {
        return try {
            val value = dispatchToCapability(request)
            BridgeResponse.Success(v = CONTRACT_VERSION, id = request.id, value = value)
        } catch (e: DispatchException) {
            failure(request, e.code, e.message ?: e.code.name)
        } catch (e: CapabilityException) {
            failure(request, e.code, e.message ?: e.code.name)
        } catch (e: SerializationException) {
            failure(request, BridgeErrorCode.INVALID_ARGUMENT, e.message ?: "malformed args")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the type name crosses the bridge. A capability's own exception
            // message is not part of the contract and may carry internals we must
            // not hand to the web app (root CLAUDE.md: never raw exception strings).
            failure(request, BridgeErrorCode.INTERNAL, "internal error (${e.javaClass.simpleName})")
        }
    }

    private fun failure(
        request: BridgeRequest,
        code: BridgeErrorCode,
        message: String,
    ): BridgeResponse =
        BridgeResponse.Failure(
            v = CONTRACT_VERSION,
            id = request.id,
            error = BridgeError(code = code, message = message),
        )

    /** Cancels every subscription this dispatcher started. Called on `PermeticController.onDestroy()`. */
    fun cancelAllSubscriptions() {
        subscriptions.cancelAll()
    }

    private suspend fun dispatchToCapability(request: BridgeRequest): JsonElement {
        if (request.method == UNSUBSCRIBE_METHOD) {
            subscriptions.cancel(request.args.decode(0))
            return JsonNull
        }
        return when (request.capability) {
            CapabilityName.SYSTEM ->
                dispatchSystem(
                    registry.get<SystemCapability>(CapabilityName.SYSTEM) ?: unavailable(request),
                    request.method,
                    request.args,
                    subscriptions,
                )
            CapabilityName.AUTH ->
                dispatchAuth(
                    registry.get<AuthCapability>(CapabilityName.AUTH) ?: unavailable(request),
                    request.method,
                    request.args,
                    subscriptions,
                )
            CapabilityName.PUSH ->
                dispatchPush(
                    registry.get<PushCapability>(CapabilityName.PUSH) ?: unavailable(request),
                    request.method,
                    request.args,
                    subscriptions,
                )
            CapabilityName.BILLING ->
                dispatchBilling(
                    registry.get<BillingCapability>(CapabilityName.BILLING) ?: unavailable(request),
                    request.method,
                    request.args,
                    subscriptions,
                )
            CapabilityName.BACKGROUND -> {
                val registered = registry.get<BackgroundCapability>(CapabilityName.BACKGROUND)
                val background = registered ?: unavailable(request)
                dispatchBackground(background, request.method, request.args, subscriptions)
            }
            CapabilityName.STORAGE ->
                unavailable(request)
        }
    }

    private fun unavailable(request: BridgeRequest): Nothing =
        throw DispatchException(
            BridgeErrorCode.UNAVAILABLE,
            "capability '${request.capability.contractName}' is not registered",
        )

    private companion object {
        /**
         * Not part of the public contract (`packages/permetic-web/src/index.d.ts`) —
         * a reserved method name every capability recognises here, established by
         * the `permetic-web` runtime (spec 01 task 4) as the wire convention for
         * cancelling an `onXxx` subscription: `{capability, method: "unsubscribe",
         * args: [subscriptionId]}`.
         */
        const val UNSUBSCRIBE_METHOD = "unsubscribe"
    }
}
