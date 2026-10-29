package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the picker's type guard and its saved-numbering reset are wired.
 *
 * [com.kennyb1201.kbstream.domain.streamengine.RowMediaTypeTest] pins the
 * verdicts; this pins the PLACE they are applied, because a guard in the wrong
 * place is invisible: applied to the merged list only, it would still flash the
 * film through the progressive paint; applied in the picker's own select path
 * only, it would not have stopped the field failure at all, because the film was
 * taken by AUTOPLAY's resolve — the same `fetch()` and the same `loadAddon()`,
 * whose kept hand feeds both.
 *
 * There is no StreamsViewModel unit-test harness in this tree (and this spec
 * does not invent one), so the wiring is read from source, the way this repo
 * pins the rest of the resolve path.
 */
class StreamsTypeGuardWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `addons are guarded one answer at a time, before anything sees them`() {
        val viewModel = read(VIEW_MODEL)
        val guard = viewModel.indexOf("RowMediaType.guard(result, contentType, streamId, runtimeMinutes)")
        val progressive = viewModel.indexOf("onAddonResult?.invoke(guarded.kept)")
        val success = viewModel.indexOf("streams = guarded.kept")

        assertTrue("loadAddon must apply the guard", guard > 0)
        assertTrue(
            "the progressive paint must receive the KEPT rows, or the film flashes",
            progressive > 0 && guard < progressive
        )
        assertTrue(
            "and the add-on's own result must be the kept rows, or the tabs and the " +
                "merge see unfiltered rows",
            success > 0 && guard < success
        )
        assertTrue(
            "the rows RETURNED are still counted, so a capture can tell an empty answer " +
                "from a filtered one",
            viewModel.contains(": returned ") && viewModel.contains(": hidden ")
        )
    }

    @Test
    fun `the request-level tally and the scheme line reach the report`() {
        val viewModel = read(VIEW_MODEL)
        assertTrue(
            "one line per request names what the guard dropped and of which shape",
            viewModel.contains("\"type guard: dropped ")
        )
        assertTrue(
            "and an empty answer on a stored non-1:1 scheme says so",
            viewModel.contains("episode query kept 0 rows (dropped ")
        )
    }

    @Test
    fun `the reset clears the stored scheme through the store's own 1-1 spelling`() {
        val viewModel = read(VIEW_MODEL)
        assertTrue(
            "one entry point, which clears then re-asks the same target",
            viewModel.contains("fun resetSchemeAndReload(")
        )
        assertTrue(
            "clearing is put(ONE_TO_ONE), which removes the key - no second store API",
            viewModel.contains("EpisodeSchemeStore.put(getApplication(), stableShowId, EpisodeScheme.ONE_TO_ONE)")
        )
        assertTrue(
            "a film or a live channel has no scheme, so the reset must refuse them",
            viewModel.contains("if (EpisodeMatch.requestedFrom(streamId) == null) return")
        )
        assertTrue(
            "the reset re-uses load(), which owns the ticket and generation guards",
            viewModel.contains("resetSchemeAndReload") && viewModel.contains("    fun load(")
        )
    }

    @Test
    fun `both pickers offer the reset and say why the list is empty`() {
        listOf(TV_PICKER, MOBILE_PICKER).forEach { path ->
            val source = read(path)
            assertTrue(
                "$path must offer the reset row",
                source.contains("RESET EPISODE NUMBERING FOR THIS SHOW")
            )
            assertTrue(
                "$path must only offer it when the ViewModel says a scheme is the reason",
                source.contains("schemeResetOffer?.let {")
            )
            assertTrue(
                "$path must name the saved numbering in the empty state",
                source.contains("show's saved episode numbering is wrong")
            )
            assertTrue(
                "$path must count what the type guard hid",
                source.contains("source(s) were hidden because they did not")
            )
        }
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
        const val TV_PICKER = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val MOBILE_PICKER = "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
    }
}
