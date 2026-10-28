package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
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
 * Two product calls are DECIDED, and both are pinned here:
 *
 *  - the grace (2026-10-27 weak-head amendment): the collection closes early
 *    only on a hand autoplay could start with a LINK, and a weak hand keeps it
 *    open for the add-ons still out — pinned by the cases below, with the
 *    rule's own cases in AutoPlayQualityTest;
 *  - the tab filter (2026-10-27 tab-scope spec): MANUAL SCRATCHES THE TAB,
 *    AUTOMATIC DOES NOT. A manual "next source" walks only the tab the viewer
 *    is looking at, and opens the in-player SOURCES picker once that tab runs
 *    out; every automatic recovery walks the merged list and crosses add-ons.
 *    Pinned at the end of this file, with the rule's own cases in
 *    SourceAddonSessionTest.
 */
class StreamsDeepDiveContractTest {

    private val tv: String by lazy { read(TV_SCREEN) }

    private val mobile: String by lazy { read(MOBILE_SCREEN) }

    private val viewModel: String by lazy { read(VIEW_MODEL) }

    private val mainActivity: String by lazy { read(MAIN_ACTIVITY) }

    private val mpv: String by lazy { read(MPV_PLAYER) }

    private val exo: String by lazy { read(EXO_PLAYER) }

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
    fun `a weak head keeps the collection open for the add-on that has not answered`() {
        val loop = slice(
            viewModel,
            "while (collected.size < streamAddons.size) {",
            "if (next == null) {"
        )
        assertTrue(
            "the grace has to be earned by a head autoplay could start with a LINK, not by " +
                "any row the walk would merely have to resolve: the fastest add-on's hashes " +
                "closed the collection before a slower add-on's direct row landed",
            loop.contains("hasStrongHead(collected, streamId, runtimeMinutes)")
        )
        assertTrue(
            "and it is only the auto-play path that shortens: the picker's own load passes " +
                "no grace, so its wait stays the full window",
            loop.contains("firstResultGraceMs != null") &&
                loop.contains("withTimeoutOrNull(ADDON_TIMEOUT_MS + 2_000L)")
        )
        assertTrue(
            "the empty-hand rule still stands beside the new one rather than being " +
                "replaced by it",
            loop.contains("hasStartableRow(collected)")
        )
        assertTrue(
            "and a strong hand still waits the short grace, so the common case is untouched",
            loop.contains("if (graceMs != null)") &&
                loop.contains("withTimeoutOrNull(graceMs)")
        )
    }

    @Test
    fun `the strong-head test is autoplay's own pick, asked with no IO`() {
        val predicate = slice(
            viewModel,
            "private fun hasStrongHead(",
            "private fun requestCachedBadges("
        )
        assertTrue(
            "the episode identity comes from the id the request carries, the runtime from " +
                "the same parameter the ranker reads, and the ceiling from the pref the " +
                "pickers read",
            predicate.contains("EpisodeMatch.requestedFrom(streamId)") &&
                predicate.contains("runtimeMinutes = runtimeMinutes") &&
                // The effective ceiling: the viewer's own cap, narrowed by the
                // cellular data saver while the connection is metered.
                predicate.contains("AppPreferences.getEffectiveAutoPlayQuality(getApplication())")
        )
        assertTrue(
            "and the verdict is AutoPlayQuality's own, so the grace's question and the " +
                "walk's cannot become two opinions about the same hand",
            predicate.contains("AutoPlayQuality.hasStrongHead(")
        )
        val loop = slice(
            viewModel,
            "while (collected.size < streamAddons.size) {",
            "if (next == null) {"
        )
        listOf("TorBoxResolve", "checkCached", "knownCached").forEach { io ->
            assertFalse(
                "no I/O in the grace decision - whether a hash is cached is the walk's " +
                    "business, and a round trip here is the cost the grace exists to bound: $io",
                loop.contains(io) || predicate.contains(io)
            )
        }
        assertFalse(
            "and the old one-way hold-open flag is gone from the file, not just the loop",
            viewModel.contains("graceHeldOpen")
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
            "val addonGroupList = results",
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

    // ── the tab filter's scope (decided above) ──────────────────────

    /**
     * The picker's tab travels on the MANUAL press and on nothing else. It is
     * threaded through `selectSource` into `onStreamSelected`; the auto-play
     * start keeps its shape, so a cold start's recovery is never confined to a
     * tab nobody chose.
     */
    @Test
    fun `the picker's tab goes with a manual press and with nothing else`() {
        listOf("tv" to tv, "mobile" to mobile).forEach { (which, screen) ->
            assertTrue(
                "$which: the picker's launch callback carries the selected tab",
                screen.contains("selectedAddon: String?")
            )
            assertTrue(
                "$which: the press path forwards it to its direct launch",
                screen.contains("onStreamSelected(selected, allSources, addons, selectedAddon)")
            )
            assertTrue(
                "$which: and to the launch that resolved a hash row first",
                screen
                    .substringAfter("allSources.map { if (it === selected) resolved else it },")
                    .substringBefore(")")
                    .contains("selectedAddon")
            )
            assertTrue(
                "$which: and the row's press is the only caller that hands it over",
                1 == Regex("selectSource\\(\\s*stream,").findAll(screen).count() &&
                    screen.contains("selectedAddonName\n")
            )
            assertFalse(
                "$which: the tab is the add-on NAME, never the tab index",
                Regex("selectSource\\([^)]*selectedAddonIndex").containsMatchIn(screen)
            )
            assertFalse(
                "$which: auto-play carries no tab, so a cold start is never scoped",
                Regex("autoplayStart\\([^)]*selectedAddon").containsMatchIn(screen)
            )
        }
    }

    @Test
    fun `the selected tab reaches the player, and only a manual press carries it`() {
        assertTrue(
            "the player screen state carries the tab, defaulted so a no-picker launch is merged",
            mainActivity.contains("val selectedAddon: String? = null,")
        )
        assertTrue(
            "the picker's manual press is what fills it",
            mainActivity.contains("selectedAddon = selectedAddon,")
        )
        assertTrue(
            "and the launch intent writes it beside the source list and its add-ons",
            mainActivity.contains("putExtra(\"selected_addon\", it)") &&
                mainActivity.contains(
                    "putExtra(\"source_addons\", sourceAddonsJson(current.sourceAddons))"
                )
        )
        assertTrue(
            "the player still gets the merged list - only the name is added",
            mainActivity.contains("sources = allSources,") &&
                mainActivity.contains("sourceAddons = sourceAddons,")
        )
        assertTrue(
            "both engines read the extra beside sources_json/source_addons",
            mpv.contains("intent.getStringExtra(\"selected_addon\")") &&
                exo.contains("intent.getStringExtra(\"selected_addon\")")
        )
    }

    @Test
    fun `a manual advance is tab-scoped and automatic recovery is not`() {
        assertTrue(
            "the MPV card's own button is the manual press, so it passes the session's tab",
            mpv.contains("tryNextSource(tab = selectedAddon)")
        )
        assertTrue(
            "the open-failure advance and the rebuffer downshift are recovery: merged",
            mpv.contains("tryNextSource(tab = null)") &&
                mpv.contains("val next = nextSourceOrNull(tab = null)")
        )
        assertTrue(
            "the card's button VISIBILITY stays merged, or a tab-exhausted viewer loses it",
            mpv.contains("if (nextSourceOrNull(tab = null) != null) View.VISIBLE else View.GONE")
        )
        val manualWalk = mpv
            .substringAfter("private fun tryNextSource(tab: String?) {")
            .substringBefore("\n    }")
        assertTrue(
            "an exhausted tab dismisses the card and opens the SOURCES picker instead of switching",
            manualWalk.contains("if (tab != null) {") &&
                manualWalk.contains("showPicker(PickerMode.SOURCE)")
        )
        assertEquals(
            "every Exo advance is automatic, so all four name the merged scope",
            4,
            Regex("tab = null").findAll(exo).count()
        )
        assertTrue(
            "and both engines walk the tabbed rule rather than a second copy of it",
            mpv.contains("SourceAddonSession.tabbedNextIndex(") &&
                exo.contains("SourceAddonSession.tabbedNextIndex(")
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
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val MPV_PLAYER = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXO_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
