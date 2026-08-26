package ac.onyx.permetic.ota

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

internal val otaJson = Json { ignoreUnknownKeys = true }

/**
 * Which bundle is live, which one is known to boot, and whether the current one is
 * still on trial.
 *
 * [trial] is the rollback mechanism. `resolve()` sets it when it hands out a bundle
 * that has not yet proved it can boot; `markBootSuccessful()` clears it. A launch that
 * finds it still set therefore knows the *previous* launch handed out that bundle and
 * never came back — so the bundle is bad and [lastGood] wins.
 */
@Serializable
internal data class BundlePointer(
    val generation: Long = 0,
    val active: String? = null,
    val lastGood: String? = null,
    val trial: Boolean = false,
)

/**
 * Two-slot ("A/B superblock") persistence for [BundlePointer].
 *
 * The obvious implementation — write to a temp file, then atomically rename over the
 * real one — needs `java.nio.file.Files.move`, which is API 26 against this module's
 * `minSdk 24`; `File.renameTo` is not atomic across a pre-existing target on every
 * platform. So instead each write goes to whichever slot is *older*, and a read takes
 * the highest generation that parses. A crash mid-write can only ever corrupt the slot
 * that wasn't being read from, leaving the previous state intact — there is no window
 * in which both slots are unusable.
 */
internal class PointerFile(root: File) {
    private val slots = listOf(File(root, "pointer.a.json"), File(root, "pointer.b.json"))

    fun read(): BundlePointer =
        slots.mapNotNull(::readSlot).maxByOrNull { it.generation } ?: BundlePointer()

    fun write(pointer: BundlePointer): BundlePointer {
        val generations = slots.map { readSlot(it)?.generation ?: -1L }
        val next = pointer.copy(generation = generations.max() + 1)
        // Write to the slot we did not just read the live state from.
        val target = if (generations[0] >= generations[1]) slots[1] else slots[0]
        target.parentFile?.mkdirs()
        target.writeText(otaJson.encodeToString(BundlePointer.serializer(), next))
        return next
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun readSlot(file: File): BundlePointer? =
        try {
            if (file.isFile) {
                otaJson.decodeFromString(
                    BundlePointer.serializer(),
                    file.readText(),
                )
            } else {
                null
            }
        } catch (e: Exception) {
            // A torn or truncated slot is exactly what this scheme exists to survive:
            // it is not an error, it just means the other slot is the live one.
            null
        }
}
