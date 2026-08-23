package ac.onyx.permetic.transport

import java.util.concurrent.atomic.AtomicLong

/**
 * Allocates subscription ids for `onXxx(...)`-shaped capability methods. Backed by an
 * [AtomicLong] rather than UUIDs: allocation only ever happens natively (never
 * contested with JS), so a simple counter is sufficient and needs no locking.
 */
public class SubscriptionIdAllocator {
    private val next = AtomicLong(0)

    public fun allocate(): String = "sub-${next.incrementAndGet()}"
}
