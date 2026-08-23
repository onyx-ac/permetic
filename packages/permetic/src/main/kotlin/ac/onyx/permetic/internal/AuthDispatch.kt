package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal suspend fun dispatchAuth(
    auth: AuthCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "getToken" -> encodeResult(auth.getToken(args.decode(0), args.decodeOptional(1, false)))
        "refresh" -> encodeResult(auth.refresh(args.decode(0)))
        "signOut" -> {
            auth.signOut()
            JsonNull
        }
        "currentAccount" -> encodeResult(auth.currentAccount())
        "onAccountChange" ->
            subscriptions.start(CapabilityName.AUTH, auth.onAccountChange()) {
                encodeResult(it)
            }
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'auth.$method'",
        )
    }
