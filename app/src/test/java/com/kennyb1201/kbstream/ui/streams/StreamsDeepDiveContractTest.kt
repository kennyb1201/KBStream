package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streams deep dive's fixes, pinned where a JVM test can see them.
 *
 * There is no Compose UI harness in this module and the ViewModel needs an
 * Application plus a network round trip, so the structure that carries each
 * fix is read out of the source the way this tree's other UI contracts are.
 * Each test names the viewer-visible failure it prevents, not the line it
 * matches.
 *
 * Deliberately NOT pinned here (product calls, still open): whether autoplay's
 * pick may come from the grace-truncated set, and whether a tab filter scopes
 * "next source".
 */
class StreamsDeepDiveContractTest {

    private val tv: String by lazy { read(TV_SCREEN) }

    private val mobile: String by lazy { read(MOBILE_SCREEN) }

    private val viewModel: String by lazy { read(VIEW_MODEL) }

    @Test
    fun `the grace is re-armed from a startable hand, not held open from a bad first answer`() {
        val loop = slice(
            viewModel,
            "while (collected.size < streamAddons.size) {",
            "if (next == null) {"
        )
        assertTrue(
            "the wait must be decided from the hand in front of it, every time round",
            loop.contains("val graceMs =") &&
                loop.contains("hasStartableRow(collected)")
        )
        assertTrue(
            "a startable hand waits the short grace",
            loop.contains("if (graceMs != null)") &&
                loop.contains("withTimeoutOrNull(graceMs)")
        )
        assertFalse(
            "and the old one-way hold-open flag must be gone: it never re-armed, so a " +
                "playable second answer arriving at 9 s still paid the rest of a 17 s wait",
            loop.contains("graceHeldOpen")
        )
    }

    @Test
    fun `a hash row counts as startable, so a cold start does not wait for everyone`() {
        val body = slice(
            viewModel,
            "private fun hasStartableRow(",
            "private fun requestCachedBadges("
        )
        assertTrue(
            "a hash row the walk can resolve is startable",
            body.contains("!it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()")
        )
        assertFalse(
            "and the cached memo must not gate it: that memo is filled AFTER collection " +
                "(requestCachedBadges), so consulting it made every cold-start hash-first " +
                "answer unstartable by construction",
            body.contains("knownCached")
        )
    }

    @Test
    fun `the tv tab is remembered by add-on name, so a refresh cannot point it at another add-on`() {
        assertTrue(
            "the selection is a name",
            tv.contains("var selectedAddonName by remember(loadedKey) { mutableStateOf<String?>(null) }")
        )
        assertTrue(
            "and resolved against the groups on every composition",
            tv.contains("addonGroups.indexOfFirst { it.addonName == name }")
        )
        assertFalse(
            "an index survives REFRESH (loadedKey is unchanged) while the groups can shrink, " +
                "which is how a tab showed a DIFFERENT add-on's rows",
            tv.contains("remember(loadedKey) { mutableIntStateOf(ALL_ADDONS_TAB) }")
        )
        assertTrue(
            "a press on a chip still selects that chip's add-on",
            tv.contains("else addonGroups.getOrNull(index)?.addonName")
        )
        // The phone had already fixed this; the TV had not.
        assertTrue(
            "the phone keeps the same rule",
            mobile.contains("var selectedAddonName by remember(loadedKey) { mutableStateOf<String?>(null) }") &&
                mobile.contains("addonGroups.indexOfFirst { it.addonName == name }")
        )
    }

    @Test
    fun `a tab list is prepared by All's rules, so its head is not a demoted row`() {
        val groups = slice(
            viewModel,
            "_addonGroups.value = results",
            ".filter { it.streams.isNotEmpty() }"
        )
        assertTrue(
            "the title's addon memory must reorder a tab too",
            groups.contains("SourceAddonPreference.ordered(") &&
                groups.contains("outcomes = addonOutcomes")
        )
        assertTrue(
            "and the same torrent must collapse once inside a tab, as it does in All",
            groups.contains("StreamDedup.collapse(ordered)")
        )
        assertTrue(
            "ranked by the same ranker, with this add-on's own identity",
            groups.contains("StreamRanker.rank(") &&
                groups.contains("addonOf = addonOfGroup")
        )
    }

    @Test
    fun `rows appear as add-ons answer, instead of every row waiting for the slowest one`() {
        assertTrue(
            "the TV list renders rows while the fetch is still in flight",
            tv.contains("visibleStreams.isEmpty() && isLoading ->")
        )
        assertTrue(
            "and says the list is still being completed",
            tv.contains("item(key = \"streams-loading\")") &&
                tv.contains("Still finding sources")
        )
        assertFalse(
            "a bare isLoading branch is the gate that hid every arriving row",
            tv.contains("when {\n                    isLoading ->")
        )

        assertTrue(
            "the phone renders rows while the fetch is still in flight",
            mobile.contains("visibleStreams.isEmpty() && isLoading ->")
        )
        assertTrue(
            "and keeps a progress row under them",
            mobile.contains("item(key = \"loading-more\")") &&
                mobile.contains("Still finding sources")
        )
    }

    @Test
    fun `the header counts the rows on screen, not All's rows`() {
        listOf("tv" to tv, "mobile" to mobile).forEach { (which, screen) ->
            assertTrue(
                "$which: the count follows the visible list",
                screen.contains("visibleStreams.isNotEmpty() -> \"${'$'}{visibleStreams.size} sources found\"")
            )
            assertFalse(
                "$which: and no longer the merged list behind a tab",
                screen.contains("streams.isNotEmpty() -> \"${'$'}{streams.size} sources found\"")
            )
        }
    }

    @Test
    fun `both explanations reach the header when both apply`() {
        listOf("tv" to tv, "mobile" to mobile).forEach { (which, screen) ->
            assertTrue(
                "$which: the two notices stack instead of one hiding the other",
                screen.contains("notices = listOfNotNull(mismatchNotice, ceilingNotice)")
            )
            assertFalse(
                "$which: the either-or handoff must be gone",
                screen.contains("mismatchNotice = mismatchNotice ?: ceilingNotice")
            )
        }
        assertTrue(
            "and the header renders every notice it is given",
            tv.contains("notices.forEach { notice ->") &&
                mobile.contains("notices.forEach { notice ->")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
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

    private companion object {
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
    }
}
