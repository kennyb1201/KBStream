package com.kennyb1201.kbstream.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings search index and its matching rule.
 *
 * Both halves are pure - the filter is a list function and the index is a
 * declared catalogue - so the rule a viewer depends on ("I typed the word, the
 * row came up") is pinned here rather than on a TV. The screen itself only
 * renders whatever this returns.
 */
class SettingsSearchTest {

    private fun entry(label: String, keywords: String = "") = SettingSearchEntry(
        key = "test.${label.lowercase().replace(' ', '-')}",
        pane = SettingsPane.PLAYBACK,
        label = label,
        keywords = keywords
    )

    @Test
    fun `a blank query finds nothing rather than everything`() {
        val all = listOf(entry("Profiles"), entry("Add-ons"))
        assertTrue(filterSettingsSearch(all, "").isEmpty())
        assertTrue(SettingsSearchIndex.search("").isEmpty())
    }

    @Test
    fun `a whitespace-only query is blank too`() {
        val all = listOf(entry("Profiles"))
        assertTrue(filterSettingsSearch(all, "   ").isEmpty())
        assertTrue(filterSettingsSearch(all, "\t\n").isEmpty())
        // Trimming is not just for the blank check: " backup " has to match.
        assertEquals(1, filterSettingsSearch(listOf(entry("Export Backup")), " backup ").size)
    }

    @Test
    fun `matching is a case-insensitive substring of the label`() {
        val all = listOf(entry("Poster Titles"), entry("Export Backup"))
        assertEquals(listOf("Poster Titles"), filterSettingsSearch(all, "POSTER").map { it.label })
        assertEquals(listOf("Poster Titles"), filterSettingsSearch(all, "ster tit").map { it.label })
        assertEquals(listOf("Export Backup"), filterSettingsSearch(all, "backup").map { it.label })
    }

    @Test
    fun `keywords are searched too, so judder finds the frame-rate switch`() {
        val entries = listOf(
            entry("Match Content Frame Rate", "frame rate refresh hz judder")
        )
        assertEquals(
            listOf("Match Content Frame Rate"),
            filterSettingsSearch(entries, "judder").map { it.label }
        )
    }

    @Test
    fun `a query nothing carries comes back empty`() {
        assertTrue(SettingsSearchIndex.search("zzzznothinghere").isEmpty())
    }

    @Test
    fun `every term the manual pass types lands somewhere`() {
        // The five terms the revamp's verification walks through. If one of
        // them stops resolving, the search the viewer was promised is broken
        // even though every individual entry still looks fine.
        listOf("subtitle", "sync", "addon", "backup", "frame").forEach { term ->
            assertTrue(
                "searching \"$term\" finds nothing",
                SettingsSearchIndex.search(term).isNotEmpty()
            )
        }
    }

    @Test
    fun `a search matching several rows keeps them all`() {
        val hits = SettingsSearchIndex.search("backup")
        assertTrue(
            "both backup rows should come up, not just the first",
            hits.map { it.label }.containsAll(listOf("Export Backup", "Import Backup"))
        )
    }

    @Test
    fun `no two rows share a label, or one would silently shadow the other`() {
        // The rows look themselves up by label, so a duplicate label means the
        // second row can be seen but never found.
        val labels = SettingsSearchIndex.entries.filterNot { it.isSection }.map { it.label }
        assertEquals(
            "duplicate row labels: " + labels.groupBy { it }.filterValues { it.size > 1 }.keys,
            labels.size,
            labels.toSet().size
        )
        val keys = SettingsSearchIndex.entries.map { it.key }
        assertEquals("duplicate index keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `entryForLabel finds a drawn row and nothing for anything else`() {
        assertNotNull(SettingsSearchIndex.entryForLabel("Match Content Frame Rate"))
        assertEquals(
            SettingsPane.INTERFACE,
            SettingsSearchIndex.entryForLabel("Poster Titles")?.pane
        )
        // Diagnostics, prose and headings are not rows; they must miss rather
        // than fall into some default entry.
        assertNull(SettingsSearchIndex.entryForLabel("FRAME RATE DIAGNOSTICS"))
        assertNull(SettingsSearchIndex.entryForLabel(""))
        assertNull(SettingsSearchIndex.entryForLabel("Poster"))
    }

    @Test
    fun `the pane sections resolve by key and keep their old names out`() {
        assertEquals(
            SettingsPane.SYNC,
            SettingsSearchIndex.entryFor(SECTION_KEY_PREFIX + "sync-health")?.pane
        )
        assertEquals(
            SettingsPane.ABOUT,
            SettingsSearchIndex.entryFor(SECTION_KEY_PREFIX + "about-updates")?.pane
        )
        // A half-rename would leave the anchor looking for a key that no longer
        // exists, which fails silently at runtime.
        assertNull(SettingsSearchIndex.entryFor("sync.health"))
        assertNull(SettingsSearchIndex.entryFor("about.updates"))
    }

    @Test
    fun `only the sections are flagged as sections`() {
        val sections = SettingsSearchIndex.entries.filter { it.isSection }
        assertEquals(
            setOf(SettingsPane.SYNC, SettingsPane.ABOUT),
            sections.map { it.pane }.toSet()
        )
        sections.forEach { assertTrue(it.key.startsWith(SECTION_KEY_PREFIX)) }
        assertTrue(
            SettingsSearchIndex.entries
                .filterNot { it.isSection }
                .none { it.key.startsWith(SECTION_KEY_PREFIX) }
        )
    }

    @Test
    fun `each entry carries its own anchor so a jump flashes exactly one row`() {
        val anchors = SettingsSearchIndex.entries.map { it.anchor }
        assertEquals(
            "two entries sharing an anchor would highlight two rows at once",
            anchors.size,
            anchors.distinct().size
        )
    }

    @Test
    fun `the indexed panes are a deliberate set, not whatever drifted in`() {
        // Rows are bound by label, so a typo'd label does not fail loudly - it
        // just stops being searchable. Pinning the pane set means dropping a
        // whole pane's rows is a decision someone has to make on purpose. The
        // unindexed panes are the ones whose "rows" are chip groups or a single
        // live-status block; an unregistered row simply never appears.
        assertEquals(
            setOf(
                SettingsPane.INTEGRATIONS,
                SettingsPane.PLAYBACK,
                SettingsPane.INTERFACE,
                SettingsPane.DATA,
                SettingsPane.SYNC,
                SettingsPane.ABOUT
            ),
            SettingsSearchIndex.entries.map { it.pane }.toSet()
        )
    }

    @Test
    fun `the result cap and the flash window are usable numbers`() {
        assertTrue(SETTINGS_SEARCH_MAX_RESULTS > 0)
        assertTrue(SETTINGS_SEARCH_FLASH_MS >= 500L)
    }
}
