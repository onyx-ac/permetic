package ac.onyx.permetic.ota

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two-slot scheme exists so that a crash part-way through a pointer write can
 * never leave the app unable to decide what to boot. These tests corrupt a slot on
 * purpose to prove the other one still carries the app.
 */
class PointerFileTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun pointerFile() = PointerFile(temp.root)

    @Test
    fun `reads back what was written`() {
        pointerFile().write(BundlePointer(active = "3", lastGood = "2", trial = true))

        val read = pointerFile().read()

        assertEquals("3", read.active)
        assertEquals("2", read.lastGood)
        assertEquals(true, read.trial)
    }

    @Test
    fun `an empty root reads as a clean slate rather than failing`() {
        assertEquals(BundlePointer(), pointerFile().read())
    }

    @Test
    fun `successive writes alternate slots so the previous state always survives`() {
        pointerFile().write(BundlePointer(active = "1"))
        pointerFile().write(BundlePointer(active = "2"))

        val slotA = File(temp.root, "pointer.a.json").readText()
        val slotB = File(temp.root, "pointer.b.json").readText()

        assertEquals(true, slotA.contains("\"1\"") != slotB.contains("\"1\""))
        assertEquals("2", pointerFile().read().active)
    }

    @Test
    fun `a torn slot is ignored in favour of the intact older one`() {
        pointerFile().write(BundlePointer(active = "1"))
        pointerFile().write(BundlePointer(active = "2"))

        // Whichever slot holds the newest generation is the one a crash would have
        // been writing; truncate it.
        val newest =
            listOf("pointer.a.json", "pointer.b.json")
                .map { File(temp.root, it) }
                .maxByOrNull {
                    otaJson.decodeFromString(
                        BundlePointer.serializer(),
                        it.readText(),
                    ).generation
                }!!
        newest.writeText("{\"generation\": 9, \"active\":")

        assertEquals("1", pointerFile().read().active)
    }

    @Test
    fun `a write after a torn slot still advances the generation`() {
        pointerFile().write(BundlePointer(active = "1"))
        File(temp.root, "pointer.b.json").writeText("not json at all")

        pointerFile().write(BundlePointer(active = "2"))

        assertEquals("2", pointerFile().read().active)
    }
}
