package ac.onyx.permetic.capability

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-side half of the contract-freeze parity check. Both this test and
 * `packages/permetic-web/test/contract-parity.test.ts` assert against the same
 * `packages/permetic-web/contract/manifest.json` — change `index.d.ts` alone, or
 * [Contract]/[CapabilityName] alone, without updating the manifest, and the side
 * that didn't change fails its own test. See spec 01, task 1.
 */
class ContractParityTest {
    private val manifest: Map<String, List<String>> by lazy {
        val file = File("../permetic-web/contract/manifest.json")
        check(file.exists()) { "contract manifest not found at ${file.absolutePath}" }
        Json.decodeFromString(file.readText())
    }

    @Test
    fun `capability names match the manifest`() {
        assertEquals(manifest.keys, CapabilityName.entries.map { it.contractName }.toSet())
    }

    @Test
    fun `auth methods match the manifest`() = assertCapability(CapabilityName.AUTH, Contract.AUTH)

    @Test
    fun `push methods match the manifest`() = assertCapability(CapabilityName.PUSH, Contract.PUSH)

    @Test
    fun `billing methods match the manifest`() =
        assertCapability(
            CapabilityName.BILLING,
            Contract.BILLING,
        )

    @Test
    fun `background methods match the manifest`() =
        assertCapability(CapabilityName.BACKGROUND, Contract.BACKGROUND)

    @Test
    fun `storage methods match the manifest`() =
        assertCapability(
            CapabilityName.STORAGE,
            Contract.STORAGE,
        )

    @Test
    fun `system methods match the manifest`() =
        assertCapability(
            CapabilityName.SYSTEM,
            Contract.SYSTEM,
        )

    @Test
    fun `Contract byCapability covers every CapabilityName entry`() {
        assertEquals(CapabilityName.entries.toSet(), Contract.byCapability.keys)
    }

    private fun assertCapability(
        name: CapabilityName,
        methods: List<String>,
    ) {
        assertEquals(manifest.getValue(name.contractName).toSet(), methods.toSet())
    }
}
