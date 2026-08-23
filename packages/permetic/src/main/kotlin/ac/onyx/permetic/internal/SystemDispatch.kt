package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal suspend fun dispatchSystem(
    system: SystemCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "info" -> encodeResult(system.info())
        "log" -> {
            system.log(args.decode(0), args.decode(1))
            JsonNull
        }
        "share" -> {
            system.share(args.decode(0))
            JsonNull
        }
        "openUrl" -> {
            system.openUrl(args.decode(0))
            JsonNull
        }
        "onLifecycle" ->
            subscriptions.start(CapabilityName.SYSTEM, system.onLifecycle()) {
                encodeResult(it)
            }
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'system.$method'",
        )
    }
