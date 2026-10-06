package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.ui.home.UNKNOWN_SHOW_NAME
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail screen is where the player's Up Next show title is produced, and
 * its meta can carry the internal id as the name (an unresolved add-on/TMDB
 * merge, a TMDB record with no name, or the cold-launch hand-off's
 * `displayName = showId` placeholder). Reported: the Up Next popup in the
 * player printed the raw "tmdb:<n>" as the show title on a kids profile.
 *
 * [detailDisplayName] pins the decision; the second test pins that the screen
 * actually consults it, because the assignment is a Compose `remember` block
 * that needs a device to stage.
 */
class DetailDisplayNameRawIdTest {

    @Test
    fun `an id-like meta name is rejected and TMDB's real name wins`() {
        assertEquals(
            "Bluey",
            detailDisplayName(
                metaName = "tmdb:114666",
                tmdbName = "Bluey",
                tmdbTitle = null,
                hasArtwork = true
            )
        )
    }

    @Test
    fun `the TMDB title is the last real name rung`() {
        assertEquals(
            "Bluey",
            detailDisplayName(
                metaName = "tt0111161",
                tmdbName = null,
                tmdbTitle = "Bluey",
                hasArtwork = false
            )
        )
    }

    @Test
    fun `an id-like name with no real name falls back to Unknown Show`() {
        assertEquals(
            UNKNOWN_SHOW_NAME,
            detailDisplayName("tmdb:114666", null, null, hasArtwork = true)
        )
        assertEquals(
            UNKNOWN_SHOW_NAME,
            detailDisplayName("simkl:9", null, null, hasArtwork = true)
        )
        assertEquals(
            UNKNOWN_SHOW_NAME,
            detailDisplayName("tt0111161", null, null, hasArtwork = false)
        )
        // Every rung id-like: the id is never returned anywhere in the chain.
        assertEquals(
            UNKNOWN_SHOW_NAME,
            detailDisplayName("tmdb:114666", "simkl:9", "tvdb:1", hasArtwork = true)
        )
    }

    @Test
    fun `a real meta name passes through untouched`() {
        assertEquals(
            "Bluey",
            detailDisplayName("Bluey", "Something Else", null, hasArtwork = true)
        )
    }

    @Test
    fun `a blank meta name falls through to the resolved names`() {
        assertEquals(
            "Bluey",
            detailDisplayName("", "Bluey", null, hasArtwork = true)
        )
        assertEquals(
            "Bluey",
            detailDisplayName("   ", null, "Bluey", hasArtwork = true)
        )
    }

    @Test
    fun `a numbered real title survives when the card has artwork`() {
        assertEquals(
            "1917",
            detailDisplayName("1917", null, null, hasArtwork = true)
        )
    }

    @Test
    fun `a bare number with no artwork is treated as an id`() {
        // The established heuristic: an id-title and a missing poster travel
        // together, so a bare number only means a title when art identifies it.
        assertEquals(
            UNKNOWN_SHOW_NAME,
            detailDisplayName("1917", null, null, hasArtwork = false)
        )
    }

    @Test
    fun `the Detail screen builds its display name through the resolver`() {
        val detail = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
        val block = block(
            detail,
            startMarker = "val displayName = remember(m, tmdbDetail) {",
            endMarker = "val keywords = remember(tmdbDetail)"
        )
        assertTrue(
            "the display name must go through detailDisplayName, not a raw meta name",
            block.contains("detailDisplayName(")
        )
        assertTrue(
            "and it must pass all three name fields plus the artwork flag",
            block.contains("metaName = m.name,") &&
                block.contains("tmdbName = tmdbDetail?.name,") &&
                block.contains("tmdbTitle = tmdbDetail?.title,") &&
                block.contains("hasArtwork = !m.poster.isNullOrBlank()")
        )
        assertFalse(
            "the old block trusted the raw meta name and could return the id",
            block.contains("m.name.ifBlank")
        )
    }

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun block(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("start marker missing: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("end marker missing: $endMarker", end > start)
        return source.substring(start, end)
    }
}
