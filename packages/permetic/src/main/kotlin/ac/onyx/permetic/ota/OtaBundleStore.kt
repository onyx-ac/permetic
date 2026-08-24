package ac.onyx.permetic.ota

import ac.onyx.permetic.transport.CONTRACT_VERSION
import java.io.File

/**
 * The on-device read/verify/activate/rollback half of OTA (spec 01, task 10; spec 06
 * owns the publishing half). Pure JVM and filesystem — no Android types — so the state
 * machine below is exercised by real unit tests against a temp directory.
 *
 * Deliberately **not** a downloader. How a bundle arrives (WorkManager poll, push
 * wakeup, launch check) is spec 06's open D-1 and none of this class's business; the
 * seam is [install], which takes an already-downloaded directory.
 *
 * **Updates apply on next launch only** (spec 01, D-5, resolved in favour of the
 * simpler option): [install] moves the pointer, but the live directory was chosen by
 * [resolve] at startup, so a running session never changes underneath itself.
 *
 * The public key is passed as raw DER (`X.509 SubjectPublicKeyInfo`) rather than
 * base64 because `java.util.Base64` needs API 26 — an Android caller decodes with
 * `android.util.Base64` and keeps this class free of Android types.
 */
public class OtaBundleStore(
    private val root: File,
    private val publicKeyDer: ByteArray,
    private val expectedContractVersion: Int = CONTRACT_VERSION,
) {
    private val pointerFile = PointerFile(root)
    private val bundlesDir = File(root, "bundles")

    /**
     * Verifies [staging] completely and, only then, makes it the bundle for the *next*
     * launch. Throws [BundleRejected] without touching any live state otherwise.
     *
     * Rejects a [BundleManifest.bundleVersion] that is not strictly newer than the
     * active one. That is a downgrade guard, not tidiness: without it, an attacker who
     * can write to the staging path could replay a genuinely-signed *older* bundle to
     * reintroduce a fixed vulnerability, and every signature check would pass.
     */
    public fun install(staging: File): Long {
        val manifest = BundleVerifier.verify(staging, publicKeyDer, expectedContractVersion)
        val pointer = pointerFile.read()

        val activeVersion = pointer.active?.toLongOrNull()
        if (activeVersion != null && manifest.bundleVersion <= activeVersion) {
            throw BundleRejected(
                "bundle version ${manifest.bundleVersion} does not supersede " +
                    "the active $activeVersion",
            )
        }

        val id = manifest.bundleVersion.toString()
        // Land the tree under a name nothing resolves, then rename into place, so a
        // crash mid-copy can only leave garbage that pruning collects — never a
        // half-written directory that the pointer already points at.
        val incoming = File(bundlesDir, ".incoming-$id")
        incoming.deleteRecursively()
        incoming.mkdirs()
        staging.copyRecursively(incoming, overwrite = true)

        val target = bundleDir(id)
        target.deleteRecursively()
        if (!incoming.renameTo(target)) {
            incoming.deleteRecursively()
            throw BundleRejected("could not move the verified bundle into place")
        }

        pointerFile.write(pointer.copy(active = id, trial = false))
        return manifest.bundleVersion
    }

    /**
     * Call once at startup, before the WebView loads anything. Returns the directory to
     * serve, or null when the bundled APK assets should be used — which is always a
     * valid answer, since spec 01 requires the APK to ship a complete working bundle.
     *
     * This is also where rollback happens: see [BundlePointer.trial].
     */
    @Suppress("ReturnCount")
    public fun resolve(): File? {
        val pointer = pointerFile.read()

        if (pointer.trial) {
            // The previous launch served `active` and never confirmed a good boot.
            val reverted =
                pointerFile.write(
                    pointer.copy(active = pointer.lastGood, trial = false),
                )
            prune(reverted)
            return reverted.active?.let(::readableBundleDir)
        }

        val active = pointer.active ?: return null
        if (active != pointer.lastGood) {
            // First launch on this bundle: hand it out, but on trial.
            pointerFile.write(pointer.copy(trial = true))
        }
        return readableBundleDir(active)
    }

    /**
     * Confirms the bundle handed out by [resolve] actually boots, promoting it to the
     * rollback target. Call it when the web app has signalled it is running — not
     * merely when the WebView finished loading, which a white-screened bundle also
     * does.
     */
    public fun markBootSuccessful() {
        val pointer = pointerFile.read()
        if (!pointer.trial) return
        val confirmed = pointerFile.write(pointer.copy(lastGood = pointer.active, trial = false))
        prune(confirmed)
    }

    /** The `bundleVersion` currently being served, or null when running from APK assets. */
    public fun activeBundleVersion(): Long? = pointerFile.read().active?.toLongOrNull()

    private fun bundleDir(id: String): File = File(bundlesDir, id)

    /** Null rather than a phantom directory if the tree went missing under us. */
    private fun readableBundleDir(id: String): File? = bundleDir(id).takeIf { it.isDirectory }

    private fun prune(pointer: BundlePointer) {
        val keep = setOfNotNull(pointer.active, pointer.lastGood)
        bundlesDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in keep) dir.deleteRecursively()
        }
    }
}
