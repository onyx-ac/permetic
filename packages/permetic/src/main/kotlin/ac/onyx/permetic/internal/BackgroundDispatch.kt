package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.BackgroundCapability
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal suspend fun dispatchBackground(
    background: BackgroundCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "schedule" -> {
            background.schedule(args.decode(0))
            JsonNull
        }
        "cancel" -> {
            background.cancel(args.decode(0))
            JsonNull
        }
        "status" -> encodeResult(background.status(args.decode(0)))
        "onStatusChange" -> {
            val id: String = args.decode(0)
            subscriptions.start(
                CapabilityName.BACKGROUND,
                background.onStatusChange(id),
            ) { encodeResult(it) }
        }
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'background.$method'",
        )
    }
