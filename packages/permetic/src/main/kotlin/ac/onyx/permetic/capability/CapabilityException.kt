package ac.onyx.permetic.capability

import ac.onyx.permetic.transport.BridgeError
import ac.onyx.permetic.transport.BridgeErrorCode

/**
 * The one way a capability implementation reports a contract error code to the web
 * app. The dispatcher maps this to a `BridgeResponse.Failure` carrying [code]
 * verbatim; anything else a capability throws becomes an opaque `INTERNAL` (root
 * `CLAUDE.md`: errors cross as [BridgeError] codes, never raw exception strings).
 *
 * [message] **crosses the bridge into JS**, so it must stay a short, safe
 * diagnostic — never a stack trace, a token, or a file path.
 *
 * Capabilities that need an `Activity` throw [unavailable] when it has been
 * collected, rather than throwing something arbitrary, per spec 01's lifecycle rule.
 */
public open class CapabilityException(
    public val code: BridgeErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public companion object {
        /** The Activity a capability needs is gone (configuration change, or destroyed). */
        public fun unavailable(what: String): CapabilityException =
            CapabilityException(BridgeErrorCode.UNAVAILABLE, "$what is unavailable")

        /** No cached credential and the caller asked for a non-interactive path. */
        public fun unauthenticated(what: String): CapabilityException =
            CapabilityException(BridgeErrorCode.UNAUTHENTICATED, what)

        /** The web app passed something the capability will not act on. */
        public fun invalidArgument(what: String): CapabilityException =
            CapabilityException(BridgeErrorCode.INVALID_ARGUMENT, what)
    }
}
