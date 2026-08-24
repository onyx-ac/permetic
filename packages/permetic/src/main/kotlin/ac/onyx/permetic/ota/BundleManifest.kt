package ac.onyx.permetic.ota

import kotlinx.serialization.Serializable

/**
 * The manifest `permetic-ota build` writes into every bundle (spec 06). Deliberately
 * JVM-only, like the transport layer: the whole verify/activate/rollback state machine
 * is then testable with a temp directory and no emulator, which matters for code whose
 * failure mode is "an unverified download becomes remote code execution".
 *
 * The signature is **detached** — `manifest.sig` alongside `manifest.json` — rather
 * than a field inside the manifest as spec 06 sketches. Signing bytes that contain the
 * signature requires agreeing on a canonical serialization of everything-except-that-field,
 * and any disagreement between the CLI's serializer and this one silently becomes a
 * verification failure. Signing the manifest file's raw bytes has no such ambiguity.
 */
@Serializable
public data class BundleManifest(
    /** Monotonic, CLI-assigned. Also the on-device downgrade guard: see `OtaBundleStore.install`. */
    val bundleVersion: Long,
    /** Must match the native side's `CONTRACT_VERSION`, or the bundle never goes live. */
    val contractVersion: Int,
    /** Relative path -> lowercase hex SHA-256, covering every file the bundle ships. */
    val files: Map<String, String>,
)

/**
 * A bundle was refused. Every path that rejects a bundle throws this rather than
 * returning a flag, so no caller can accidentally proceed with an unverified tree.
 */
public class BundleRejected(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal const val MANIFEST_NAME = "manifest.json"
internal const val SIGNATURE_NAME = "manifest.sig"
