package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.PushCapability
import ac.onyx.permetic.capability.SystemCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The load-bearing test for this round's core guarantee: an unregistered capability
 * is indistinguishable, at the registry level, from one that was simply never asked
 * about — there is no separate "registered but broken" state to get wrong. See
 * [DispatcherTest] for the same guarantee exercised end to end through [Dispatcher].
 */
class CapabilityRegistryTest {
    @Test
    fun `an unregistered capability is not available and get returns null`() {
        val registry = CapabilityRegistry()
        registry.register(FakeSystemCapability())
        registry.register(FakeAuthCapability())

        assertFalse(registry.isAvailable(CapabilityName.PUSH))
        assertNull(registry.get<PushCapability>(CapabilityName.PUSH))
    }

    @Test
    fun `a registered capability is available and get returns the same instance`() {
        val registry = CapabilityRegistry()
        val system = FakeSystemCapability()
        registry.register(system)

        assertTrue(registry.isAvailable(CapabilityName.SYSTEM))
        assertEquals(system, registry.get<SystemCapability>(CapabilityName.SYSTEM))
    }

    @Test
    fun `availableNames reflects exactly what was registered, nothing more`() {
        val registry = CapabilityRegistry()
        registry.register(FakeSystemCapability())
        registry.register(FakeAuthCapability())

        assertEquals(setOf(CapabilityName.SYSTEM, CapabilityName.AUTH), registry.availableNames())
    }

    @Test
    fun `an empty registry has nothing available`() {
        val registry = CapabilityRegistry()

        assertEquals(emptySet(), registry.availableNames())
        CapabilityName.entries.forEach { assertFalse(registry.isAvailable(it)) }
    }
}
