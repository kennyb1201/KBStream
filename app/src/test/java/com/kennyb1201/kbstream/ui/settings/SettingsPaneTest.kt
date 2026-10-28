package com.kennyb1201.kbstream.ui.settings

import com.kennyb1201.kbstream.data.library.TasteSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings rail's shape, which the screen itself cannot assert.
 *
 * The rail draws a group heading whenever the group changes while it walks
 * [SettingsPane.entries], and it hands the TV entry focus to the first row of
 * that walk. Both are one-pass loops over the enum, so both silently depend on
 * the declaration ORDER of the panes: a pane left in the wrong place prints its
 * heading twice, and a pane moved above the first group's opener takes the
 * focus request with it. Those are the kind of mistake that only shows up on a
 * TV, so they are pinned here instead.
 */
class SettingsPaneTest {

    @Test
    fun `the panes are declared group-major so no heading prints twice`() {
        val groupsInOrder = SettingsPane.entries
            .map { it.group }
            .fold(mutableListOf<SettingsGroup>()) { seen, group ->
                if (seen.lastOrNull() != group) seen.add(group)
                seen
            }

        assertEquals(
            "a group must be contiguous in SettingsPane.entries",
            groupsInOrder.size,
            groupsInOrder.toSet().size
        )
        assertEquals(
            "the groups must also appear in the order the rail should show them",
            SettingsGroup.entries,
            groupsInOrder
        )
    }

    @Test
    fun `every group has panes and the first pane opens the first group`() {
        SettingsGroup.entries.forEach { group ->
            assertTrue(
                "${group.name} would be an empty heading",
                SettingsPane.entries.any { it.group == group }
            )
        }
        // One FocusRequester is created for the first row of the walk, so that
        // row has to be the top of the rail: the first group's first pane.
        assertEquals(
            SettingsPane.entries.first { it.group == SettingsGroup.entries.first() },
            SettingsPane.entries.first()
        )
    }

    @Test
    fun `the rail reads top to bottom the way it is declared`() {
        assertEquals(
            listOf(
                "INTEGRATIONS", "COLLECTIONS", "HIDDEN", "RATED",
                "PLAYBACK", "VIDEO", "LANGUAGE", "SUBTITLES",
                "INTERFACE", "DATA", "SYNC", "ABOUT"
            ),
            SettingsPane.entries.map { it.name }
        )
    }

    /**
     * The two per-profile title lists sit beside each other, in the LIBRARY
     * group, in this order.
     *
     * Pinned by VALUE and not only by name, because the rail itself is drawn
     * from the enum: a label or blurb edited here reaches the rail, the phone's
     * list and the pane header at once, and the manager's own contract test
     * knows this pane by the label pinned here.
     */
    @Test
    fun `the rated titles pane is declared right after hidden titles`() {
        assertEquals(
            SettingsPane.HIDDEN,
            SettingsPane.entries[SettingsPane.entries.indexOf(SettingsPane.RATED) - 1]
        )
        assertEquals(SettingsGroup.LIBRARY, SettingsPane.RATED.group)
        assertEquals("Rated Titles", SettingsPane.RATED.label)
        assertEquals("Titles you liked or disliked", SettingsPane.RATED.blurb)
    }

    @Test
    fun `every pane explains itself in one short line`() {
        SettingsPane.entries.forEach { pane ->
            assertTrue("${pane.name} has no blurb", pane.blurb.isNotBlank())
            assertTrue(
                "${pane.name}'s blurb is ${pane.blurb.length} chars and will " +
                    "wrap past two lines in the rail",
                pane.blurb.length <= 64
            )
            assertFalse(
                "${pane.name}'s blurb should read as a label, not a sentence",
                pane.blurb.endsWith(".")
            )
            assertFalse(pane.blurb.contains('\n'))
            assertTrue("${pane.name} has no rail label", pane.label.isNotBlank())
        }
    }

    @Test
    fun `no two rows read the same`() {
        val labels = SettingsPane.entries.map { it.label }
        val blurbs = SettingsPane.entries.map { it.blurb }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("two panes describing themselves identically", blurbs.size, blurbs.toSet().size)
    }

    @Test
    fun `a remembered pane name round-trips and junk falls back to the first pane`() {
        SettingsPane.entries.forEach { pane ->
            assertEquals(pane, SettingsPane.fromStored(pane.name))
        }
        // Written by a hand-edited pref, a downgrade, or a profile blob from a
        // build with a different pane list. Opening Settings must never crash
        // and must never land on nothing.
        assertEquals(SettingsPane.entries.first(), SettingsPane.fromStored(null))
        assertEquals(SettingsPane.entries.first(), SettingsPane.fromStored(""))
        assertEquals(SettingsPane.entries.first(), SettingsPane.fromStored("NOPE"))
        assertEquals(SettingsPane.entries.first(), SettingsPane.fromStored("about"))
        // Whitespace survives a SharedPreferences round-trip awkwardly; it must
        // not turn a valid name into a fallback.
        assertEquals(SettingsPane.ABOUT, SettingsPane.fromStored("  ABOUT  "))
    }

    // -- the Rated Titles pane's list rule -----------------------------------

    private fun entry(
        title: String,
        signal: String,
        updatedAt: Long,
        rating: Int? = null
    ) = TasteSignals.Entry(
        keys = listOf("movie::tmdb:1"),
        title = title,
        mediaType = "movie",
        signal = signal,
        rating = rating,
        updatedAt = updatedAt
    )

    /**
     * The order and the groups, both pure: the store's list is sorted HERE (one
     * sort, in the one place both surfaces read), and the chips keep the group
     * each names. Newest means the last signal write, which is what puts a
     * just-flipped title at the top of the manager.
     */
    @Test
    fun `the rated list is newest first, and each filter keeps its own group`() {
        val entries = listOf(
            entry("old like", TasteSignals.LIKE, 10L),
            entry("new dislike", TasteSignals.DISLIKE, 30L),
            entry("middle like", TasteSignals.LIKE, 20L)
        )

        assertEquals(
            listOf("new dislike", "middle like", "old like"),
            ratedList(entries, RatedFilter.ALL).map { it.title }
        )
        assertEquals(
            listOf("middle like", "old like"),
            ratedList(entries, RatedFilter.LIKED).map { it.title }
        )
        assertEquals(
            listOf("new dislike"),
            ratedList(entries, RatedFilter.DISLIKED).map { it.title }
        )
    }

    /**
     * A number IS a verdict: 7-10 is a like and 1-3 is a dislike, whatever thumb
     * was pressed before the number was picked. The filter asks the store's
     * effective signal, so the manager agrees with the detail page, the poster
     * menus and the blend about the same entry.
     */
    @Test
    fun `the filter reads a numeric rating as the verdict it means`() {
        val entries = listOf(
            entry("nine", TasteSignals.NONE, 1L, rating = 9),
            entry("two", TasteSignals.NONE, 2L, rating = 2),
            entry("five", TasteSignals.NONE, 3L, rating = 5)
        )

        assertEquals(
            listOf("nine"),
            ratedList(entries, RatedFilter.LIKED).map { it.title }
        )
        assertEquals(
            listOf("two"),
            ratedList(entries, RatedFilter.DISLIKED).map { it.title }
        )
        assertEquals(
            "a mid-range rating is rated, and belongs to neither verdict group",
            listOf("five", "two", "nine"),
            ratedList(entries, RatedFilter.ALL).map { it.title }
        )
    }
}
