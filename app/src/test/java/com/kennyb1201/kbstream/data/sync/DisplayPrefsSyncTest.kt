package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-key merge rules for the display-prefs blob.
 *
 * The bug these pin down: the blob was whole-blob last-write-wins stamped at
 * PUSH time, and every push published every synced key. A device changing one
 * unrelated toggle therefore re-published its own older copy of the language,
 * subtitle-size and eye-badge keys with a brand-new timestamp, and the next
 * pull on the other TV reverted them ("language and subtitles didn't sync").
 */
class DisplayPrefsSyncTest {

    @Test
    fun `unchanged keys keep their timestamps`() {
        val values = mapOf("preferred_audio_language" to "en", "default_subtitle_size" to "2")
        val stamped = DisplayPrefsRules.stampChanged(
            previous = values,
            current = values,
            timestamps = mapOf("preferred_audio_language" to 100L),
            now = 999L,
            hadSnapshot = true
        )
        assertEquals(100L, stamped["preferred_audio_language"])
        assertEquals(null, stamped["default_subtitle_size"])
    }

    @Test
    fun `only the key that changed is stamped`() {
        val stamped = DisplayPrefsRules.stampChanged(
            previous = mapOf(
                "preferred_audio_language" to "en",
                "preferred_subtitle_language" to "en",
                "poster_partial_watch_badge" to "true"
            ),
            current = mapOf(
                "preferred_audio_language" to "fr",   // edited
                "preferred_subtitle_language" to "en", // untouched
                "poster_partial_watch_badge" to "true" // untouched
            ),
            timestamps = mapOf(
                "preferred_audio_language" to 100L,
                "preferred_subtitle_language" to 200L,
                "poster_partial_watch_badge" to 300L
            ),
            now = 999L,
            hadSnapshot = true
        )
        assertEquals(999L, stamped["preferred_audio_language"])
        assertEquals(200L, stamped["preferred_subtitle_language"])
        assertEquals(300L, stamped["poster_partial_watch_badge"])
    }

    @Test
    fun `a pref that disappears locally counts as an edit`() {
        val stamped = DisplayPrefsRules.stampChanged(
            previous = mapOf("preferred_audio_language" to "fr"),
            current = emptyMap(),
            timestamps = mapOf("preferred_audio_language" to 100L),
            now = 999L,
            hadSnapshot = true
        )
        assertEquals(999L, stamped["preferred_audio_language"])
    }

    @Test
    fun `a key that was already empty is not re-stamped`() {
        val stamped = DisplayPrefsRules.stampChanged(
            previous = mapOf("preferred_audio_language" to ""),
            current = emptyMap(),
            timestamps = emptyMap(),
            now = 999L,
            hadSnapshot = true
        )
        assertEquals(emptyMap<String, Long>(), stamped)
    }

    @Test
    fun `first observation claims nothing so the cloud stays authoritative`() {
        val stamped = DisplayPrefsRules.stampChanged(
            previous = emptyMap(),
            current = mapOf("preferred_audio_language" to "fr"),
            timestamps = emptyMap(),
            now = 999L,
            hadSnapshot = false
        )
        assertTrue(stamped.isEmpty())
    }

    @Test
    fun `remote wins on equal or newer timestamps only`() {
        assertTrue(DisplayPrefsRules.remoteWins(remoteTs = 500L, localTs = 500L))
        assertTrue(DisplayPrefsRules.remoteWins(remoteTs = 501L, localTs = 500L))
        assertFalse(DisplayPrefsRules.remoteWins(remoteTs = 499L, localTs = 500L))
    }

    @Test
    fun `blob timestamp tracks the newest local edit, not the push time`() {
        assertEquals(
            300L,
            DisplayPrefsRules.blobUpdatedAt(
                mapOf("a" to 100L, "b" to 300L),
                now = 999L
            )
        )
        assertEquals(999L, DisplayPrefsRules.blobUpdatedAt(emptyMap(), now = 999L))
    }
}
