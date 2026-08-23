package ac.onyx.permetic.internal

import ac.onyx.permetic.transport.BridgeErrorCode

/**
 * Thrown by per-capability dispatch code (unknown method, malformed argument,
 * unregistered capability). [Dispatcher.dispatch] maps this to a
 * [ac.onyx.permetic.transport.BridgeError] — never lets it escape as a raw
 * exception.
 */
internal class DispatchException(
    val code: BridgeErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
