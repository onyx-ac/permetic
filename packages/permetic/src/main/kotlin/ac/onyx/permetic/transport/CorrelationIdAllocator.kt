package ac.onyx.permetic.transport

import java.util.UUID

/**
 * Allocates [BridgeRequest] correlation ids. UUIDv4 rather than an incrementing
 * counter: ids must stay unique across a WebView reload or native process death
 * without native and JS coordinating a shared counter.
 */
public class CorrelationIdAllocator {
    public fun allocate(): String = UUID.randomUUID().toString()
}
