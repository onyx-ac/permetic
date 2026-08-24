package ac.onyx.permetic.ota

import ac.onyx.permetic.transport.CONTRACT_VERSION
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Spec 01: OTA content runs at the same privilege as bundled content, so an unverified
 * download is a remote code execution channel into the app. Every test here is a way
 * that channel could be opened.
 */
class BundleVerifierTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val signer = TestSigner()

    private fun bundle(): File = temp.newFolder("bundle")

    @Test
    fun `a correctly signed bundle verifies`() {
        val dir = writeBundle(bundle(), signer, bundleVersion = 7)

        val manifest = BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)

        assertEquals(7, manifest.bundleVersion)
    }

    @Test
    fun `a file edited after signing is rejected`() {
        val dir = writeBundle(bundle(), signer)
        File(dir, "index.html").writeText("<html>tampered</html>")

        val rejected =
            assertFailsWith<BundleRejected> {
                BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
            }

        assertTrue(rejected.message!!.contains("does not match its manifest digest"))
    }

    /**
     * The one a per-file-digest check alone would miss: every listed file is intact,
     * but an extra script nothing vouched for has been dropped into the tree.
     */
    @Test
    fun `a file the manifest does not cover is rejected`() {
        val dir = writeBundle(bundle(), signer)
        File(dir, "smuggled.js").writeText("fetch('https://evil.example')")

        val rejected =
            assertFailsWith<BundleRejected> {
                BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
            }

        assertTrue(rejected.message!!.contains("the manifest does not cover"))
    }

    @Test
    fun `a missing file is rejected`() {
        val dir = writeBundle(bundle(), signer)
        File(dir, "index.html").delete()

        assertFailsWith<BundleRejected> {
            BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
        }
    }

    @Test
    fun `an edited manifest no longer matches its signature`() {
        val dir = writeBundle(bundle(), signer)
        val manifestFile = File(dir, MANIFEST_NAME)
        manifestFile.writeText(
            manifestFile.readText().replace("\"bundleVersion\":1", "\"bundleVersion\":99"),
        )

        val rejected =
            assertFailsWith<BundleRejected> {
                BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
            }

        assertTrue(rejected.message!!.contains("signature does not match"))
    }

    @Test
    fun `a bundle signed by a different key is rejected`() {
        val dir = writeBundle(bundle(), TestSigner())

        assertFailsWith<BundleRejected> {
            BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
        }
    }

    @Test
    fun `a bundle built against a different contract version never goes live`() {
        val dir = writeBundle(bundle(), signer, contractVersion = CONTRACT_VERSION + 1)

        val rejected =
            assertFailsWith<BundleRejected> {
                BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
            }

        assertTrue(rejected.message!!.contains("contract version"))
    }

    @Test
    fun `a manifest path escaping the bundle directory is rejected`() {
        val dir =
            writeBundle(
                bundle(),
                signer,
                extraManifestFiles = mapOf("../../evil.js" to sha256Hex("x".toByteArray())),
            )

        val rejected =
            assertFailsWith<BundleRejected> {
                BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
            }

        assertTrue(rejected.message!!.contains("unsafe path"))
    }

    @Test
    fun `an absent signature is a rejection, not a pass`() {
        val dir = writeBundle(bundle(), signer)
        File(dir, SIGNATURE_NAME).delete()

        assertFailsWith<BundleRejected> {
            BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
        }
    }

    @Test
    fun `a corrupt signature file is a rejection, not a crash`() {
        val dir = writeBundle(bundle(), signer)
        File(dir, SIGNATURE_NAME).writeBytes(byteArrayOf(1, 2, 3))

        assertFailsWith<BundleRejected> {
            BundleVerifier.verify(dir, signer.publicKeyDer, CONTRACT_VERSION)
        }
    }
}
