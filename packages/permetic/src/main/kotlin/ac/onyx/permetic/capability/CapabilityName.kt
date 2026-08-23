package ac.onyx.permetic.capability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `CapabilityName` in `permetic-web/src/index.d.ts`. Kept as an enum (not
 * string constants) so [ac.onyx.permetic.internal.Dispatcher]'s `when` over this type
 * can be non-exhaustive-checked by the compiler: adding a capability here without
 * adding its dispatch branch is a compile error, not a runtime `UNAVAILABLE`.
 */
@Serializable
public enum class CapabilityName(public val contractName: String) {
    @SerialName("auth")
    AUTH("auth"),

    @SerialName("push")
    PUSH("push"),

    @SerialName("billing")
    BILLING("billing"),

    @SerialName("background")
    BACKGROUND("background"),

    @SerialName("storage")
    STORAGE("storage"),

    @SerialName("system")
    SYSTEM("system"),
}
