@file:Suppress("MatchingDeclarationName")

package ac.onyx.permetic.ota

import ac.onyx.permetic.transport.CONTRACT_VERSION
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Builds real signed bundles with a real P-256 key pair, so the verifier is exercised
 * against genuine ECDSA signatures rather than a stubbed-out check. Signing here is
 * the same operation `permetic-ota sign` performs (spec 06); if these ever disagree,
 * this is the side that is wrong.
 */
internal class TestSigner(
    val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair(),
) {
    val publicKeyDer: ByteArray get() = keyPair.public.encoded

    fun sign(bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(bytes)
            sign()
        }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * Writes a complete, correctly-signed bundle into [dir]. [extraManifestFiles] injects
 * entries the tree does not actually back, for the negative cases.
 */
@Suppress("LongParameterList")
internal fun writeBundle(
    dir: File,
    signer: TestSigner,
    bundleVersion: Long = 1,
    contractVersion: Int = CONTRACT_VERSION,
    files: Map<String, String> = mapOf("index.html" to "<html>bundle $bundleVersion</html>"),
    extraManifestFiles: Map<String, String> = emptyMap(),
): File {
    dir.mkdirs()
    val digests = mutableMapOf<String, String>()
    for ((path, content) in files) {
        val file = File(dir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        digests[path] = sha256Hex(content.toByteArray())
    }
    digests += extraManifestFiles

    val manifest =
        BundleManifest(
            bundleVersion = bundleVersion,
            contractVersion = contractVersion,
            files = digests,
        )
    val manifestBytes =
        otaJson.encodeToString(BundleManifest.serializer(), manifest).toByteArray()
    File(dir, MANIFEST_NAME).writeBytes(manifestBytes)
    File(dir, SIGNATURE_NAME).writeBytes(signer.sign(manifestBytes))
    return dir
}
