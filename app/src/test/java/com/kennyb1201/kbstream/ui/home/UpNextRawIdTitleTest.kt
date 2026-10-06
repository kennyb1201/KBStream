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
    fun `multi-segment ids are still ids`() {
        // tmdb:12345:3:17 leaked through the old single-segment check: the
        // substringAfter("tt") test was false on the colons.
        assertTrue(looksLikeRawMediaId("tmdb:12345:3:17"))
        // A real title that merely starts with a prefix is untouched.
        assertFalse(looksLikeRawMediaId("tmdb: the movie"))
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

    // --- the name the card hands the PLAYER ------------------------------
    //
    // Keeping the id off the card was not enough: the play path copied the raw
    // field into StreamsTarget.displayName, so the id became the session's
    // itemName, was printed as the show title on the Up Next panel, and rode
    // the autoplay handoff into every episode after it.

    @Test
    fun `the audited card hands the player a literal, never the id`() {
        // title = "tmdb:114666", no show name, no artwork.
        val name = upNextPlayerDisplayName(
            cardTitle = "tmdb:114666",
            showTitle = null,
            composedTitle = "tmdb:114666",
            hasArtwork = false
        )
        assertEquals(UNKNOWN_SHOW_NAME, name)
        assertFalse(looksLikeRawMediaId(name, hasArtwork = false))
    }

    @Test
    fun `a real card title is handed over unchanged`() {
        assertEquals(
            "Breaking Bad",
            upNextPlayerDisplayName(
                cardTitle = "Breaking Bad",
                showTitle = null,
                composedTitle = "Breaking Bad S4 E41",
                hasArtwork = true
            )
        )
    }

    @Test
    fun `an id-like card title yields the show name when there is one`() {
        assertEquals(
            "Severance",
            upNextPlayerDisplayName(
                cardTitle = "tmdb:95396",
                showTitle = "Severance",
                composedTitle = "tmdb:95396 S2 E7",
                hasArtwork = false
            )
        )
    }

    @Test
    fun `the composed fallback cannot inherit an id`() {
        // targetTitle is the CARD title with the marker appended, so an id-like
        // card title makes it "tmdb:114666 S4 E41" - which looksLikeRawMediaId
        // does NOT flag (the marker stops it being all-numeric) while an id is
        // still on screen. Trusting it would satisfy the letter of "not an id"
        // and fail the point.
        val name = upNextPlayerDisplayName(
            cardTitle = "tmdb:114666",
            showTitle = null,
            composedTitle = "tmdb:114666 S4 E41",
            hasArtwork = false
        )
        assertEquals(UNKNOWN_SHOW_NAME, name)
        assertFalse("no id may survive into the name", name.contains("114666"))
    }

    @Test
    fun `the composed fallback is used when there was no id to inherit from`() {
        // Nothing else names the card, but the composed field is not derived
        // from an id, so it is the best answer available.
        assertEquals(
            "S4 E41",
            upNextPlayerDisplayName(
                cardTitle = null,
                showTitle = null,
                composedTitle = "S4 E41",
                hasArtwork = false
            )
        )
    }

    @Test
    fun `a numbered real title still reaches the player`() {
        // "1917" with artwork is a name, not an id - the bare-number rule must
        // not eat it on the way into the player either.
        assertEquals(
            "1917",
            upNextPlayerDisplayName(
                cardTitle = "1917",
                showTitle = null,
                composedTitle = "1917",
                hasArtwork = true
            )
        )
    }

    @Test
    fun `the handoff helper substitutes the literal for an id`() {
        assertEquals("Ted Lasso", upNextPlayerNameOrUnknown("Ted Lasso", hasArtwork = true))
        assertEquals("1917", upNextPlayerNameOrUnknown("1917", hasArtwork = true))
        assertEquals(UNKNOWN_SHOW_NAME, upNextPlayerNameOrUnknown("tmdb:114666", hasArtwork = true))
        assertEquals(UNKNOWN_SHOW_NAME, upNextPlayerNameOrUnknown("12345", hasArtwork = false))
        assertEquals(UNKNOWN_SHOW_NAME, upNextPlayerNameOrUnknown("   ", hasArtwork = true))
        assertEquals(UNKNOWN_SHOW_NAME, upNextPlayerNameOrUnknown(null, hasArtwork = true))
    }
}
