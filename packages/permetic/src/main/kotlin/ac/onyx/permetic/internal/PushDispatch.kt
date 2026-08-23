package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.PushCapability
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement

/**
 * No `PushCapability` method takes a positional argument, so [args] goes unused
 * here — kept in the signature for uniformity with every other `dispatchX`
 * function, all called identically from [Dispatcher].
 */
@Suppress("UnusedParameter")
internal suspend fun dispatchPush(
    push: PushCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "requestPermission" -> encodeResult(push.requestPermission())
        "permissionState" -> encodeResult(push.permissionState())
        "getToken" -> encodeResult(push.getToken())
        "onToken" -> subscriptions.start(CapabilityName.PUSH, push.onToken()) { encodeResult(it) }
        "onMessage" ->
            subscriptions.start(
                CapabilityName.PUSH,
                push.onMessage(),
            ) { encodeResult(it) }
        "initialMessage" -> encodeResult(push.initialMessage())
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'push.$method'",
        )
    }
