package ac.onyx.permetic.transport

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionIdAllocatorTest {
    @Test
    fun `ids are prefixed and monotonically increasing per allocation`() {
        val allocator = SubscriptionIdAllocator()

        assertEquals("sub-1", allocator.allocate())
        assertEquals("sub-2", allocator.allocate())
        assertEquals("sub-3", allocator.allocate())
    }

    @Test
    fun `allocations are unique under concurrent access from multiple OS threads`() {
        val allocator = SubscriptionIdAllocator()
        val ids = Collections.synchronizedSet(mutableSetOf<String>())
        val threadCount = 16
        val perThread = 500

        val threads =
            (0 until threadCount).map {
                Thread {
                    repeat(perThread) { ids.add(allocator.allocate()) }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(threadCount * perThread, ids.size)
    }
}
