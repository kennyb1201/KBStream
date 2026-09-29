package com.kennyb1201.kbstream.ui.settings

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
                "INTEGRATIONS", "HIDDEN",
                "PLAYBACK", "VIDEO", "LANGUAGE", "SUBTITLES",
                "INTERFACE", "DATA", "SYNC", "ABOUT"
            ),
            SettingsPane.entries.map { it.name }
        )
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
}
