package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching must never show an internal media id as a card title.
 *
 * Reported bug: a show appeared twice on the rail, one card with no thumbnail
 * and "tmdb:<n>" where its name should be. That phantom is the same card whose
 * enrichment failed, so the id-title and the missing artwork travel together -
 * which is exactly what lets the guard treat a bare number as a title when the
 * card has a poster ("1917", "2012" are real names) and as an id when it does
 * not.
 */
class UpNextRawIdTitleTest {

    @Test
    fun `prefixed ids are always ids, poster or not`() {
        assertTrue(looksLikeRawMediaId("tmdb:12345"))
        assertTrue(looksLikeRawMediaId("tmdb:12345", hasArtwork = true))
        assertTrue(looksLikeRawMediaId("simkl:9"))
        assertTrue(looksLikeRawMediaId("tvdb:456"))
        assertTrue(looksLikeRawMediaId("imdb:tt0111161"))
    }

    @Test
    fun `bare imdb ids are ids`() {
        assertTrue(looksLikeRawMediaId("tt0111161"))
        assertTrue(looksLikeRawMediaId("TT0111161"))
    }

    @Test
    fun `a bare number is only an id without artwork`() {
        // "1917" / "2012" are real titles; a numbered tile WITH a poster is
        // kept, the same tile with no poster is the failed-enrichment phantom.
        assertFalse(looksLikeRawMediaId("1917", hasArtwork = true))
        assertTrue(looksLikeRawMediaId("1917", hasArtwork = false))
    }

    @Test
    fun `real titles are never ids`() {
        assertFalse(looksLikeRawMediaId("Ted Lasso"))
        assertFalse(looksLikeRawMediaId("Breaking Bad"))
        assertFalse(looksLikeRawMediaId("tt Something"))
        assertFalse(looksLikeRawMediaId("", hasArtwork = false))
        assertFalse(looksLikeRawMediaId("  "))
    }

    @Test
    fun `display title drops id-like names and keeps real ones`() {
        assertNull(upNextDisplayTitleOrNull("tmdb:12345"))
        assertNull(upNextDisplayTitleOrNull("tt0111161"))
        assertNull(upNextDisplayTitleOrNull("12345", hasArtwork = false))
        assertNull(upNextDisplayTitleOrNull("   "))
        assertNull(upNextDisplayTitleOrNull(null))

        assertEquals("Ted Lasso", upNextDisplayTitleOrNull("Ted Lasso"))
        assertEquals("1917", upNextDisplayTitleOrNull("1917", hasArtwork = true))
        // The title is trimmed before it reaches the rail.
        assertEquals("Severance", upNextDisplayTitleOrNull("  Severance  "))
    }
}
