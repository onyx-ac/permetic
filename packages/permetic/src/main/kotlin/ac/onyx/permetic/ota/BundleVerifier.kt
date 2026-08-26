package ac.onyx.permetic.ota

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Decides whether a downloaded tree is allowed to become live. Spec 01: OTA content
 * runs at the same privilege as the bundled content, with no boundary between them, so
 * an unverified download is a remote code execution channel into the app.
 *
 * Four things are checked, and all four have to pass:
 *  1. the manifest's detached signature, against a public key pinned in the app;
 *  2. every file listed in the manifest is present with the digest claimed;
 *  3. no file is present that the manifest does not list — otherwise a tampered
 *     bundle could smuggle in an extra script that nothing has vouched for;
 *  4. the manifest's `contractVersion` matches the native side's, so OTA'd JS can
 *     never outrun the installed capabilities (spec 01 makes this handshake
 *     load-bearing precisely here).
 *
 * ECDSA over P-256 rather than Ed25519: `Signature.getInstance("Ed25519")` needs API
 * 33, against this module's `minSdk 24`.
 */
internal object BundleVerifier {
    // Each throw is a distinct, separately-diagnosable reason a bundle was refused.
    // Collapsing them into one exit would make a rejected update far harder to explain,
    // and this is code whose failures people will have to explain.
    @Suppress("ThrowsCount")
    fun verify(
        bundleDir: File,
        publicKeyDer: ByteArray,
        expectedContractVersion: Int,
    ): BundleManifest {
        val manifestFile = File(bundleDir, MANIFEST_NAME)
        val signatureFile = File(bundleDir, SIGNATURE_NAME)
        if (!manifestFile.isFile) throw BundleRejected("bundle has no $MANIFEST_NAME")
        if (!signatureFile.isFile) throw BundleRejected("bundle has no $SIGNATURE_NAME")

        val manifestBytes = manifestFile.readBytes()
        verifySignature(manifestBytes, signatureFile.readBytes(), publicKeyDer)

        // Only parsed *after* the signature checks out: everything below this line is
        // acting on attacker-supplied structure otherwise.
        // Broad on purpose: any failure to make sense of the manifest is a rejection,
        // and enumerating the ways a parser can object is not a safety property worth
        // depending on.
        @Suppress("TooGenericExceptionCaught")
        val manifest =
            try {
                otaJson.decodeFromString(
                    BundleManifest.serializer(),
                    manifestBytes.decodeToString(),
                )
            } catch (e: Exception) {
                throw BundleRejected("$MANIFEST_NAME is not a valid manifest", e)
            }

        if (manifest.contractVersion != expectedContractVersion) {
            throw BundleRejected(
                "bundle targets contract version ${manifest.contractVersion}, " +
                    "this build speaks $expectedContractVersion",
            )
        }
        verifyTree(bundleDir, manifest)
        return manifest
    }

    private fun verifySignature(
        signed: ByteArray,
        signature: ByteArray,
        publicKeyDer: ByteArray,
    ) {
        val valid =
            try {
                val key =
                    KeyFactory.getInstance(
                        "EC",
                    ).generatePublic(X509EncodedKeySpec(publicKeyDer))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(signed)
                    verify(signature)
                }
            } catch (e: java.security.GeneralSecurityException) {
                // A malformed key or signature is a rejection, not a crash — but it is
                // never silently a *pass*.
                throw BundleRejected("manifest signature could not be checked", e)
            }
        if (!valid) throw BundleRejected("manifest signature does not match the pinned key")
    }

    @Suppress("ThrowsCount")
    private fun verifyTree(
        bundleDir: File,
        manifest: BundleManifest,
    ) {
        val onDisk =
            bundleDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(bundleDir).invariantSeparatorsPath }
                .filterNot { it == MANIFEST_NAME || it == SIGNATURE_NAME }
                .toMutableSet()

        for ((path, expectedHex) in manifest.files) {
            if (!isSafeRelativePath(path)) {
                throw BundleRejected("manifest lists an unsafe path '$path'")
            }
            val file = File(bundleDir, path)
            if (!file.isFile) throw BundleRejected("manifest lists a missing file '$path'")

            val expected =
                decodeHex(expectedHex)
                    ?: throw BundleRejected("manifest has a malformed digest for '$path'")
            // Constant-time: digests are attacker-influenced, and a timing oracle on
            // comparison is exactly how a forged bundle would be tuned.
            if (!MessageDigest.isEqual(sha256(file), expected)) {
                throw BundleRejected("file '$path' does not match its manifest digest")
            }
            onDisk.remove(path)
        }

        if (onDisk.isNotEmpty()) {
            throw BundleRejected(
                "bundle contains ${onDisk.size} file(s) the manifest does not cover, " +
                    "first: '${onDisk.first()}'",
            )
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private const val DIGEST_BUFFER_BYTES = 8 * 1024
}

/**
 * Manifest paths are joined onto a directory, so they are a traversal surface. Anything
 * absolute, parent-relative, or carrying a Windows separator or drive letter is refused
 * outright rather than normalised — normalising attacker input is how traversal bugs
 * get written.
 */
internal fun isSafeRelativePath(path: String): Boolean {
    val rooted = path.isEmpty() || path.startsWith("/")
    // A Windows separator or drive letter is interpreted by File(), so both are
    // traversal vectors even on a platform where they look inert.
    val platformEscape = path.contains('\\') || path.contains(':')
    val segmentsSafe = path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    return !rooted && !platformEscape && segmentsSafe
}

/** Null on anything malformed; callers treat that as a rejection. */
@Suppress("ReturnCount")
internal fun decodeHex(hex: String): ByteArray? {
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        // Character.digit accepts either case, so no normalisation is needed first.
        val high = Character.digit(hex[i * 2], HEX_RADIX)
        val low = Character.digit(hex[i * 2 + 1], HEX_RADIX)
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

private const val HEX_RADIX = 16
