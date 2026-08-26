package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Note what is *absent*: nothing here catches a cancellation and turns it into an
 * error code. `signIn`/`authorize`/`authorizeOffline` return null when the user
 * dismissed the chooser, and null encodes to JSON null — so dismissal reaches the page
 * as an ordinary result, per spec 08.
 */
internal suspend fun dispatchAuth(
    auth: AuthCapability,
    method: String,
    args: List<JsonElement>,
    subscriptions: SubscriptionRunner,
): JsonElement =
    when (method) {
        "supported" -> encodeResult(auth.supported())
        "signIn" -> encodeResult(auth.signIn(args.decodeOptional<String?>(0, null)))
        "authorize" -> encodeResult(auth.authorize(args.decode(0)))
        "authorizeOffline" -> encodeResult(auth.authorizeOffline(args.decode(0)))
        "grantedScopes" -> encodeResult(auth.grantedScopes())
        "revoke" -> {
            auth.revoke(args.decodeOptional(0, emptyList()))
            JsonNull
        }
        "signOut" -> {
            auth.signOut()
            JsonNull
        }
        "account" -> encodeResult(auth.account())
        "onAccountChange" ->
            subscriptions.start(CapabilityName.AUTH, auth.onAccountChange()) {
                encodeResult(it)
            }
        else -> throw DispatchException(
            BridgeErrorCode.INVALID_ARGUMENT,
            "unknown method 'auth.$method'",
        )
    }
