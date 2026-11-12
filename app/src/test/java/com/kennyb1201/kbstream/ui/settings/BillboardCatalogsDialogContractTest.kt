package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard catalogs control is one row that opens a multi-select dialog
 * (spec 2026-11-12).
 *
 * The chooser fix made this control honest - it now lists every rail the Home
 * can draw, not the four builtins - and that is exactly what broke the page: a
 * chip per catalog ran to many rows and pushed Settings' Home section into a
 * scroll of chips between two unrelated settings. The row is the fix; the same
 * options, labels, exclusions, normalization and empty->default rule stay in the
 * same place they were.
 *
 * There is no device in CI and a Compose screen cannot be driven in a JVM test,
 * so the structure that carries the behaviour is read out of the source the way
 * this tree's other contracts are: which control the section composes, what the
 * dialog is handed, and which path a confirm takes to prefs.
 */
class BillboardCatalogsDialogContractTest {

    private val settings: String by lazy { read(SETTINGS) }
    private val rules: String by lazy { read(RULES) }
    private val prefs: String by lazy { read(PREFS) }

    /** The catalogs control, from the section's start to the dialog's. */
    private fun section(): String = between(
        settings,
        "private fun BillboardSettingsSection()",
        "private fun BillboardCatalogsDialog"
    )

    /** The dialog itself, from its declaration to the next top-level composable. */
    private fun dialog(): String = between(
        settings,
        "private fun BillboardCatalogsDialog",
        "private fun LanguageChipGrid("
    )

    // ── (a) one row, no chip grid in the pane ──────────────────────────────

    @Test
    fun `the catalogs control is a single row, and no chip grid is composed in Settings`() {
        assertFalse(
            "the section may not lay the catalogs out as a grid any more - that grid is " +
                "what made Settings' Home section a scroll of chips",
            settings.contains("catalogOptions.chunked(3)")
        )
        val s = section()
        // The row opens the dialog, and only while the billboard is on (the 2026-11-12
        // toggle spec: the dependent rows are inert while it is Off).
        assertTrue(
            "the control is one focusable row that opens the dialog",
            s.contains("onClick = { if (billboardOn) catalogsOpen = true }")
        )
        assertTrue(
            "and it is the row the search entry points at",
            s.contains("searchAnchor(SettingsSearchIndex.entryForLabel(\"Billboard catalogs\"))")
        )
        assertEquals(
            "the catalogs are chipped exactly once in the file: inside the dialog",
            1,
            Regex("PillChip\\(label, selected = key in draft\\)").findAll(settings).count()
        )
        assertFalse(
            "the pane no longer composes a chip per catalog outside the dialog",
            s.contains("PillChip(label, key in feeds)")
        )
    }

    // ── (b) the dialog offers every offered catalog, and only those ────────

    @Test
    fun `the dialog lists every billboard feed option except the progress rails`() {
        val d = dialog()
        assertTrue(
            "the dialog is handed the same option list the chips were",
            section().contains("options = catalogOptions") &&
                section().contains("billboardFeedOptions(context)")
        )
        assertTrue(
            "one row per option, keyed by the feed key",
            d.contains("items(options, key = { (key, _) -> key }) { (key, label) ->")
        )
        assertTrue(
            "the option list is still filtered by the shared exclusion rule",
            settings.contains(".filter { key -> com.kennyb1201.kbstream.ui.home.billboardFeedOffered(key) }")
        )
        assertTrue(
            "which excludes Continue Watching and Upcoming - progress rails, not catalogs",
            rules.contains("BILLBOARD_EXCLUDED_FEED_KEYS") &&
                rules.contains("KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING") &&
                rules.contains("KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE")
        )
    }

    // ── (c) a confirm writes the normalized list ───────────────────────────

    @Test
    fun `toggling rows and pressing Done writes the normalized list`() {
        val d = dialog()
        assertTrue(
            "a row toggles the draft selection",
            d.contains("draft = if (key in draft) draft - key else draft + key")
        )
        assertTrue(
            "DONE commits the draft - the only path out that writes",
            d.contains("KBButton(label = \"DONE\", onClick = { onConfirm(draft) })")
        )
        val s = section()
        assertTrue(
            "and the confirm goes through the section's write path",
            s.contains("onConfirm = { picked ->") && s.contains("writeFeeds(picked)")
        )
        assertTrue(
            "which normalizes before it stores (the shared rule, not a second one)",
            s.contains("val normalized = com.kennyb1201.kbstream.ui.home.normalizeBillboardFeeds(next)") &&
                s.contains("AppPreferences.setHomeBillboardFeeds(context, normalized)")
        )
        assertTrue(
            "and that normalizer drops excluded keys before writing",
            rules.contains("feeds.filter(::billboardFeedOffered)") &&
                rules.contains(".distinct()")
        )
    }

    // ── (d) cancel writes nothing ──────────────────────────────────────────

    @Test
    fun `cancelling the dialog leaves the stored selection untouched`() {
        val d = dialog()
        assertTrue(
            "the dialog's draft is local state, so it cannot edit prefs on its own",
            d.contains("var draft by remember { mutableStateOf(selected) }")
        )
        assertTrue(
            "CANCEL and BACK both dismiss without a write",
            d.contains("KBButton(label = \"CANCEL\", onClick = onDismiss)") &&
                d.contains("androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss)")
        )
        val dismiss = section().substringAfter("onDismiss = {").substringBefore("}")
        assertTrue(
            "and the section's dismiss only closes the dialog",
            dismiss.contains("catalogsOpen = false") && !dismiss.contains("writeFeeds")
        )
        assertFalse(
            "nothing writes on open, either",
            dialog().contains("AppPreferences.setHomeBillboardFeeds")
        )
    }

    // ── (e) an empty pick is the default, not an empty hero ────────────────

    @Test
    fun `unchecking every catalog falls back to trending-day`() {
        assertTrue(
            "the dialog allows an empty selection - there is no minimum guard",
            !dialog().contains("draft.isEmpty()") && !dialog().contains("if (draft.isEmpty())")
        )
        assertTrue(
            "and the normalizer restores the default for it",
            rules.contains(".ifEmpty { AppPreferences.DEFAULT_BILLBOARD_FEEDS }")
        )
        assertTrue(
            "which is trending-day",
            prefs.contains("const val BILLBOARD_TRENDING_DAY_FEED = \"trending_day\"") &&
                prefs.contains("val DEFAULT_BILLBOARD_FEEDS: List<String> = listOf(BILLBOARD_TRENDING_DAY_FEED)")
        )
        assertTrue(
            "the dialog says so, so the rule is not a surprise",
            dialog().contains("unchecking every catalog restores the default")
        )
    }

    // ── (f) the row says what the selection is ─────────────────────────────

    @Test
    fun `the summary row reflects the stored selection`() {
        assertTrue(
            "the row's text is the shared summary rule",
            section().contains("com.kennyb1201.kbstream.ui.home.billboardFeedsSummary(")
        )
        assertTrue(
            "computed from the stored keys, in the dialog's own order",
            section().contains("selectedLabels = catalogOptions.filter { (key, _) -> key in feeds }")
        )
        assertTrue(
            "so it re-reads on every write (feeds is the state the write sets)",
            section().contains("var feeds by remember { mutableStateOf(AppPreferences.getHomeBillboardFeeds(context)) }")
        )
        assertTrue(
            "one label names itself, several are counted rather than truncated",
            rules.contains("if (labels.size <= BILLBOARD_SUMMARY_LABELS) return labels.joinToString(\", \")") &&
                rules.contains("+\${labels.size - BILLBOARD_SUMMARY_LABELS} more")
        )
        assertTrue(
            "and an empty stored selection names the default instead of \"none\"",
            rules.contains("if (labels.isEmpty()) return defaultLabel")
        )
        assertTrue(
            "the default label is the trending-day option the dialog lists",
            section().contains("key == AppPreferences.BILLBOARD_TRENDING_DAY_FEED")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("end marker missing: $end", to >= 0)
        return text.substring(from, to)
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/home/BillboardRules.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
