package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings revamp's wiring, which the screen itself cannot assert.
 *
 * This module carries no Compose test harness, so the contract is pinned by
 * reading the source - the same approach the sync-row, add-on and guide
 * contracts take. Everything checked here is something that fails SILENTLY on
 * a TV: a row the index promises but the screen does not draw (a result that
 * jumps nowhere), a badge that is secretly focusable, a section heading that
 * leaks into the results, a search jump that changes a setting.
 */
class SettingsSearchContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
    }

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    /** Whitespace-insensitive haystack, so indentation is not the test. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /**
     * Kotlin escapes resolved to the characters they draw.
     *
     * A row's label is a runtime string, but the screen spells a few of them
     * with an escape - the Profile 5 row's arrow is written as an escape, not as
     * the arrow - so the two spellings have to be reconciled before they can be
     * compared. Resolving does not loosen the check: the escape and the
     * character are the same label, and a row that draws neither still fails.
     */
    private fun resolveEscapes(text: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""").replace(text) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

    private fun screen(): String = source(SCREEN)

    private fun toggleRow(): String =
        between(screen(), "private fun ToggleRow(", "private fun FrameRateDiagnosticRow()")

    private fun navigationRow(): String = between(
        screen(),
        "private fun NavigationRow(",
        "private fun androidx.compose.foundation.layout.ColumnScope.HiddenTitlesSection()"
    )

    private fun rail(): String =
        between(screen(), "private fun SettingsNavRail(", "private fun SettingsContentHost(")

    private fun railBadge(): String =
        between(screen(), "private fun SettingsRailBadge(pane: SettingsPane) {", "private fun RailDot(")

    private fun jump(): String = between(
        screen(),
        "val jumpToSetting: (SettingSearchEntry) -> Unit = { entry ->",
        "val exportLauncher ="
    )

    // ── The index and the screen have to agree ────────────────────────────

    @Test
    fun `every searchable row label is a label the screen actually draws`() {
        val text = resolveEscapes(screen())
        SettingsSearchIndex.entries.filterNot { it.isSection }.forEach { entry ->
            assertTrue(
                "no row in SettingsScreen.kt is labelled \"${entry.label}\", so that " +
                    "index entry would be a result that jumps nowhere",
                text.contains("\"${entry.label}\"")
            )
        }
    }

    @Test
    fun `every drawn toggle row is in the index, so none is visible but unfindable`() {
        // The check above runs index -> screen. This is the other way round, and
        // it is the direction that let SE-2 through: a row can be drawn,
        // focusable and reachable by scrolling, yet no query can ever name it.
        // The label literal is read with its escapes resolved, so a row written
        // with an arrow escape is matched as the arrow it draws.
        val drawn = Regex("""ToggleRow\(\s*label = \x22([^\x22]+)\x22""")
            .findAll(screen())
            .map { resolveEscapes(it.groupValues[1]) }
            .toList()
        assertTrue(
            "the pattern no longer finds known rows, so it proves nothing",
            drawn.containsAll(
                listOf("P5 \u2192 HDR10", "AMOLED Black", "Match Content Frame Rate")
            )
        )
        val indexed = SettingsSearchIndex.entries.map { it.label }.toSet()
        assertEquals(
            "these rows are drawn but no query can find them",
            emptyList<String>(),
            drawn.filterNot { indexed.contains(it) }
        )
    }

    @Test
    fun `every pane section is anchored to the block it stands for`() {
        val text = squash(screen())
        SettingsSearchIndex.entries.filter { it.isSection }.forEach { entry ->
            val suffix = entry.key.removePrefix(SECTION_KEY_PREFIX)
            val literal = "SettingsSearchIndex.entryFor(\"${'$'}{SECTION_KEY_PREFIX}$suffix\")"
            assertTrue(
                "section \"${entry.label}\" is not attached with $literal, so jumping " +
                    "to it would switch panes and never scroll",
                text.contains(squash("searchAnchor( $literal )"))
            )
        }
    }

    @Test
    fun `both row primitives bind their entry by label, with no per-call keys`() {
        listOf("ToggleRow" to toggleRow(), "NavigationRow" to navigationRow()).forEach { (name, row) ->
            assertTrue(
                "$name does not look its entry up by its own label, so no existing " +
                    "call site would be searchable",
                row.contains("SettingsSearchIndex.entryForLabel(label)")
            )
        }
        assertFalse(
            "a per-call searchKey is a second registration site that can drift from " +
                "the index; binding is by label",
            screen().contains("searchKey = \"")
        )
    }

    // ── Track A: the search rail ──────────────────────────────────────────

    @Test
    fun `the rail draws a search field that does not raise the TV keyboard on focus`() {
        val rail = rail()
        assertTrue(
            "no search field in the rail",
            rail.contains("placeholder = \"Search settings\"")
        )
        assertTrue(
            "focus alone would raise the full-screen Fire TV keyboard over the results",
            rail.contains("openKeyboardOnFocus = false")
        )
    }

    @Test
    fun `a non-blank query replaces the pane list with flat results`() {
        val rail = rail()
        assertTrue(
            "the results only appear under a query",
            rail.contains("if (searchQuery.isNotBlank())")
        )
        assertTrue(
            "the results are filtered from the declared index",
            rail.contains("SettingsSearchIndex.search(searchQuery)")
        )
        assertTrue(
            "the grouped pane list must not render underneath the results",
            rail.contains("return@Column")
        )
        // ...and the ordinary rail is still the fall-through, so an empty query
        // renders exactly what it always did.
        assertTrue(rail.contains("SettingsPane.entries.forEach { pane ->"))
    }

    @Test
    fun `results are grouped by pane and capped`() {
        val results = between(screen(), "private fun SettingsSearchResults(", "private fun SettingsRailBadge(")
        assertTrue(
            "a result has to say which pane it switches to",
            results.contains("entry.pane.label")
        )
        assertTrue(
            "the flat list is capped so a one-letter query cannot fill the rail",
            results.contains(".take(SETTINGS_SEARCH_MAX_RESULTS)")
        )
        assertTrue(
            "an empty result set has to say so instead of drawing nothing",
            results.contains("No settings match")
        )
    }

    @Test
    fun `a jump is navigation only and can never change a setting`() {
        val block = squash(jump())
        assertTrue(
            "the jump has to remember the pane it landed on",
            block.contains("AppPreferences.setLastSettingsPane")
        )
        val writes = Regex("AppPreferences\\.(\\w+)").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(
            "a search jump must not write anything but the last-read pane",
            setOf("setLastSettingsPane"),
            writes
        )
        assertTrue(
            "the target row is scrolled into view",
            block.contains("entry.anchor.bringIntoView()")
        )
        assertTrue("the jump has to switch panes", block.contains("selectedPane = entry.pane"))
        assertTrue("the query is cleared once a result is picked", block.contains("settingsSearchQuery = \"\""))
    }

    @Test
    fun `the jumped-to row is flashed by identity through a composition local`() {
        val text = screen()
        assertTrue(
            "the flash target is handed down without touching every call site",
            text.contains("CompositionLocalProvider(LocalFlashingSetting provides flashing)")
        )
        assertTrue(
            "the screen passes its own flash state into the host",
            text.contains("flashing = flashingSetting")
        )
        // Identity, not equality: two entries could share a label's text, and
        // only the picked one may light up.
        val helper = between(text, "private fun isFlashingSetting(", "private fun Modifier.searchAnchor(")
        assertTrue("the flash test must be by identity", helper.contains("=== entry"))
        assertTrue(
            "rows draw the accent wash while flashing",
            text.contains("if (flashing) KBAccent.copy(alpha = 0.25f) else KBSurfaceRaised")
        )
        assertTrue(
            "the wash is cleared on a timer",
            text.contains("delay(SETTINGS_SEARCH_FLASH_MS)")
        )
    }

    // ── Track B: rail icons and badges ────────────────────────────────────

    @Test
    fun `every pane carries its own rail icon and the rail draws it`() {
        val text = screen()
        assertTrue("SettingsPane has no icon", text.contains("val icon: ImageVector"))
        listOf(
            "Extension", "VisibilityOff", "PlayArrow", "OndemandVideo", "Language",
            "ClosedCaption", "Palette", "Storage", "Sync", "Info"
        ).forEach { icon ->
            assertTrue(
                "no pane uses Icons.Filled.$icon",
                text.contains("Icons.Filled.$icon")
            )
        }
        assertTrue(
            "the rail does not draw the pane's icon",
            rail().contains("imageVector = pane.icon")
        )
    }

    @Test
    fun `the rail badges read the same live state their panes do`() {
        val badge = railBadge()
        assertTrue("ABOUT badge is not wired to the updater", badge.contains("AppUpdater.state"))
        listOf("Available", "Downloading", "ReadyToInstall").forEach { state ->
            assertTrue("an $state update should badge ABOUT", badge.contains("UpdateState.$state"))
        }
        assertTrue("SYNC badge is not wired to the sync state", badge.contains("SupabaseSync.isSyncing"))
        assertTrue("SYNC badge does not read the auth state", badge.contains("SupabaseSync.authState"))
        assertTrue("SYNC badge does not read the outbox", badge.contains("SupabaseSync.pendingOutboxCount"))
        assertTrue("SYNC badge has no warning tint", badge.contains("KBDanger"))
        assertTrue(
            "INTEGRATIONS badge does not count installed add-ons",
            badge.contains("installedAddons")
        )
        assertTrue(
            "the add-on count must come from the shared singleton, not a static list",
            badge.contains("AddonManager") && badge.contains("getInstance(")
        )
    }

    @Test
    fun `a badge is an indicator and cannot take focus or change the pane`() {
        val badge = railBadge()
        assertFalse("a badge must not be clickable", badge.contains("onClick"))
        assertFalse("a badge must not request focus", badge.contains("focusRequester"))
        assertFalse("a badge must not be focusable", badge.contains("focusable("))
    }

    // ── Track C: section headings ─────────────────────────────────────────

    @Test
    fun `the section heading exists with the specified look`() {
        val text = screen()
        assertTrue(
            "no ColumnScope section heading",
            text.contains("private fun ColumnScope.SettingsSectionHeader(text: String, first: Boolean = false)")
        )
        val heading = between(
            text,
            "private fun ColumnScope.SettingsSectionHeader(",
            "private fun ToggleRow("
        )
        assertTrue("headings are uppercase", heading.contains("text.uppercase()"))
        assertTrue("headings are accent-coloured", heading.contains("color = KBAccent"))
        assertTrue("headings are small", heading.contains("MaterialTheme.typography.labelSmall"))
        assertTrue(
            "the first heading of a pane sits flush, the rest are spaced",
            heading.contains("top = if (first) 0.dp else 16.dp")
        )
    }

    @Test
    fun `every content pane is broken into clusters by a heading`() {
        val text = screen()
        listOf(
            "SettingsSectionHeader(\"Sync\", first = true)",
            "SettingsSectionHeader(\"Backup & Restore\", first = true)",
            "SettingsSectionHeader(\"Audio\", first = true)",
            "SettingsSectionHeader(\"Notifications\", first = true)",
            "SettingsSectionHeader(\"Buffering & Playback\", first = true)",
            "SettingsSectionHeader(\"Behavior\", first = true)"
        ).forEach { opener ->
            assertTrue("missing pane-opening heading: $opener", text.contains(opener))
        }
    }

    @Test
    fun `a section heading is never a search result`() {
        val headings = Regex("SettingsSectionHeader\\(\"([^\"]+)\"")
            .findAll(screen())
            .map { it.groupValues[1].lowercase() }
            .toSet()
        assertTrue("headings were not found at all", headings.isNotEmpty())
        val labels = SettingsSearchIndex.entries.map { it.label.lowercase() }.toSet()
        assertEquals(
            "a heading text is also an index label, so the heading would show up as a result",
            emptySet<String>(),
            headings intersect labels
        )
    }

    // ── The index's own file ──────────────────────────────────────────────

    @Test
    fun `the index declares its model, its cap and its flash window`() {
        val search = source(SEARCH)
        assertTrue(search.contains("internal class SettingSearchEntry("))
        assertTrue(search.contains("internal fun filterSettingsSearch("))
        assertTrue(search.contains("internal object SettingsSearchIndex"))
        assertTrue(search.contains("internal val LocalFlashingSetting"))
        assertTrue(search.contains("internal const val SETTINGS_SEARCH_MAX_RESULTS"))
        assertTrue(search.contains("internal const val SETTINGS_SEARCH_FLASH_MS"))
    }

    @Test
    fun `a picked result hands the D-pad back instead of stranding it`() {
        // Picking a result unmounts the very card that had focus, and a TV remote
        // has no pointer to press from: focus left nowhere is a screen the viewer
        // cannot steer. The search field is the one node the jump knows is still
        // there, so it is where the focus has to land - deliberately, not by
        // whatever Compose happens to pick next.
        val block = squash(jump())
        assertTrue(
            "the jump must move focus to a node it can prove exists",
            block.contains("settingsSearchFocus.requestFocus()")
        )
        assertTrue(
            "and the field it targets has to carry that requester",
            squash(screen()).contains("focusRequester = searchFocus")
        )
    }

    @Test
    fun `the jump keeps asking for its row for a time budget, not a frame count`() {
        // A cold pane can take longer to attach its anchor than any fixed number
        // of frames, and a frame is not a fixed amount of work: the old fixed
        // count gave up silently, switching the pane and never scrolling.
        val block = jump()
        assertFalse(
            "a frame count is not a bound on anything",
            block.contains("repeat(4)")
        )
        assertTrue(
            "the loop has to be bounded by time - the EXPRESSION, not a mention " +
                "of the constant in a comment",
            squash(block).contains(
                "if (withFrameNanos { it } - startedAt >= SETTINGS_SEARCH_SCROLL_BUDGET_MS) {"
            )
        )
        assertTrue(
            "and it must still ask every frame",
            squash(block).contains("entry.anchor.bringIntoView()")
        )
        assertTrue(
            "the budget is declared once, next to the flash window",
            source(SEARCH).contains("internal const val SETTINGS_SEARCH_SCROLL_BUDGET_MS = 250L")
        )
    }

    @Test
    fun `the flash turns on after the scroll and always turns off again`() {
        val block = jump()
        val order = listOf(
            "pane switch" to "selectedPane = entry.pane",
            "scroll" to "entry.anchor.bringIntoView()",
            "flash on" to "flashingSetting = entry",
            "flash off" to "if (flashingSetting === entry) flashingSetting = null"
        )
        var last = -1
        order.forEach { (label, anchor) ->
            val at = block.indexOf(anchor)
            assertTrue("jump is missing: $label", at >= 0)
            assertTrue("jump steps are out of order at: $label", at > last)
            last = at
        }
    }
}
