package ac.onyx.permetic.capability

/**
 * Marker every capability interface extends, so `PermeticController.Builder
 * .capability(x)` is one overload and [ac.onyx.permetic.internal.CapabilityRegistry]
 * can key generically on [name] rather than needing a separate registration method
 * per capability. Sealed: every direct implementor lives in this module, alongside
 * the closed capability set itself (spec 01 non-goals — "not a generic RPC bridge").
 * `permetic-push`/`permetic-billing` implement [PushCapability]/[BillingCapability]
 * from their own modules later; that's unaffected, since sealed only restricts
 * direct subtypes of *this* interface.
 */
public sealed interface PermeticCapability {
    public val name: CapabilityName
}
