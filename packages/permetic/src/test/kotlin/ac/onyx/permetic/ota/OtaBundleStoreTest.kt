package ac.onyx.permetic.ota

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The activate/rollback state machine. Each `store()` is a fresh instance over the same
 * root, standing in for a new process — nothing here would catch a bug if state were
 * held in memory rather than persisted, which is the point.
 */
class OtaBundleStoreTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val signer = TestSigner()

    private fun store() = OtaBundleStore(temp.root, signer.publicKeyDer)

    private fun staged(
        version: Long,
        body: String = "bundle $version",
    ): File =
        writeBundle(
            temp.newFolder("staging-$version-${System.nanoTime()}"),
            signer,
            bundleVersion = version,
            files = mapOf("index.html" to body),
        )

    @Test
    fun `with nothing installed the APK assets are used`() {
        assertNull(store().resolve())
    }

    @Test
    fun `an installed bundle is served and its contents are intact`() {
        store().install(staged(1))

        val live = assertNotNull(store().resolve())

        assertEquals("bundle 1", File(live, "index.html").readText())
        assertEquals(1, store().activeBundleVersion())
    }

    @Test
    fun `a bundle that confirms a good boot keeps being served`() {
        store().install(staged(1))

        val first = store()
        first.resolve()
        first.markBootSuccessful()

        val live = assertNotNull(store().resolve())
        assertEquals("bundle 1", File(live, "index.html").readText())
    }

    /**
     * The core rollback case: a launch serves a new bundle, never confirms it booted,
     * and the launch after that has to notice and go back.
     */
    @Test
    fun `a bundle that never confirms a boot is rolled back on the next launch`() {
        store().install(staged(1))
        store().apply { resolve() }.markBootSuccessful()

        store().install(staged(2))

        // The launch that tries v2 and dies before confirming.
        val crashing = assertNotNull(store().resolve())
        assertEquals("bundle 2", File(crashing, "index.html").readText())

        val afterRestart = assertNotNull(store().resolve())
        assertEquals("bundle 1", File(afterRestart, "index.html").readText())
        assertEquals(1, store().activeBundleVersion())
    }

    @Test
    fun `a first-ever bundle that fails falls back to the APK assets`() {
        store().install(staged(1))

        store().resolve() // launches, never confirms

        assertNull(store().resolve())
        assertNull(store().activeBundleVersion())
    }

    @Test
    fun `a rolled-back bundle is not retried on every subsequent launch`() {
        store().install(staged(1))
        store().apply { resolve() }.markBootSuccessful()
        store().install(staged(2))
        store().resolve() // tries v2, dies
        store().resolve() // rolls back to v1

        val stable = assertNotNull(store().resolve())
        assertEquals("bundle 1", File(stable, "index.html").readText())
    }

    /**
     * Without this, an attacker who can write to the staging path could replay a
     * genuinely-signed older bundle to reintroduce a fixed vulnerability — and every
     * signature check would pass, because the bundle really was signed.
     */
    @Test
    fun `an older bundle version cannot supersede a newer one`() {
        store().install(staged(5))

        val rejected = assertFailsWith<BundleRejected> { store().install(staged(4)) }

        assertTrue(rejected.message!!.contains("does not supersede"))
        assertEquals(5, store().activeBundleVersion())
    }

    @Test
    fun `reinstalling the same version is refused`() {
        store().install(staged(3))

        assertFailsWith<BundleRejected> { store().install(staged(3)) }
    }

    @Test
    fun `a bundle that fails verification leaves the live one untouched`() {
        store().install(staged(1))
        store().apply { resolve() }.markBootSuccessful()

        val tampered = staged(2)
        File(tampered, "index.html").writeText("tampered after signing")

        assertFailsWith<BundleRejected> { store().install(tampered) }

        assertEquals(1, store().activeBundleVersion())
        val live = assertNotNull(store().resolve())
        assertEquals("bundle 1", File(live, "index.html").readText())
    }

    @Test
    fun `superseded bundles are eventually cleaned up`() {
        store().install(staged(1))
        store().apply { resolve() }.markBootSuccessful()
        store().install(staged(2))
        store().apply { resolve() }.markBootSuccessful()

        val kept = File(temp.root, "bundles").listFiles()!!.map { it.name }.toSet()

        assertEquals(setOf("2"), kept)
    }
}
