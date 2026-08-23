package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.BillingCapability
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal suspend fun dispatchBilling(
    billing: BillingCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "queryProducts" -> encodeResult(billing.queryProducts(args.decode(0), args.decode(1)))
        "purchase" -> encodeResult(billing.purchase(args.decode(0), args.decodeOptional(1, null)))
        "queryPurchases" -> encodeResult(billing.queryPurchases())
        "acknowledge" -> {
            billing.acknowledge(args.decode(0))
            JsonNull
        }
        "consume" -> {
            billing.consume(args.decode(0))
            JsonNull
        }
        "onPurchaseUpdate" ->
            subscriptions.start(
                CapabilityName.BILLING,
                billing.onPurchaseUpdate(),
            ) { encodeResult(it) }
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'billing.$method'",
        )
    }
