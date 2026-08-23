package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.capability.PermeticCapability

/**
 * `Map<CapabilityName, PermeticCapability>`, and the whole "unregistered ->
 * UNAVAILABLE" mechanism: [Dispatcher] does `registry.get<X>(NAME) ?: unavailable()`
 * per branch. There is only one state, present or absent — no separate "registered
 * but unimplemented" path to get wrong. [get] is a checked-by-construction cast: the
 * only way to register a capability under a given [CapabilityName] is via its own
 * default `name` property (see each `*Capability` interface), so what comes back out
 * always matches what went in under that key.
 */
internal class CapabilityRegistry {
    private val entries = mutableMapOf<CapabilityName, PermeticCapability>()

    fun register(capability: PermeticCapability) {
        entries[capability.name] = capability
    }

    fun isAvailable(name: CapabilityName): Boolean = entries.containsKey(name)

    fun availableNames(): Set<CapabilityName> = entries.keys.toSet()

    @Suppress("UNCHECKED_CAST")
    fun <T : PermeticCapability> get(name: CapabilityName): T? = entries[name] as T?
}
