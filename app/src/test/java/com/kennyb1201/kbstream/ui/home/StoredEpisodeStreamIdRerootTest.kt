package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.player.rerootedEpisodeStreamId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A STORED episode stream id must not name a request under the root it was
 * filed with when the show's own record says the add-ons know it by another.
 *
 * The field case is PAW Patrol: the catalog hands out `tt3121722` while the
 * show's TMDB record says its IMDB id is `tt4873036`, so a Continue Watching row
 * filed before the root was verified carries `tt3121722:7:3` - and every
 * episode of that show then asks for the Dino Movie's streams, on the row's own
 * episode number.
 *
 * The detail screen already re-reads such an id against the root it resolved
 * (`DetailScreen`'s `filedUnder`). The rail has two reads the detail screen
 * cannot cover, because neither opens it: the resolver's resume branch, which
 * is what the card hands the player, and the local card builder, whose card is
 * what "Play Manually" from the long-press menu sends straight to the streams
 * picker. Both now re-root the stored id first, and the rule they share is
 * [rerootedEpisodeStreamId].
 *
 * The rule is pure and driven directly here. The two call sites are read out of
 * the source, the shape every other wiring test in this package uses, because a
 * rail that needs a database, a tracker session and a network cannot be run in
 * a JVM test.
 */
class StoredEpisodeStreamIdRerootTest {

    // ── the rule ──────────────────────────────────────────────────────────

    @Test
    fun `a stale catalog root is swapped for the verified one`() {
        assertEquals(
            "the row's catalog id must not reach the add-ons",
            "tt4873036:7:3",
            rerootedEpisodeStreamId("tt3121722:7:3", "tt4873036")
        )
    }

    @Test
    fun `the file's own season and episode are kept verbatim`() {
        // Only the root moves. The suffix is the FILE the viewer watched, and
        // it is the one the add-ons answered under that root - re-deriving it
        // from the TMDB episode would run the learned file numbering over a
        // file that has already been played.
        assertEquals(
            "the whole file part must survive a re-root",
            "tt4873036:0:312",
            rerootedEpisodeStreamId("tt3121722:0:312", "tt4873036")
        )
    }

    @Test
    fun `an id already filed under the verified root is untouched`() {
        assertEquals(
            "an id the app itself built needs no rewrite",
            "tt4873036:7:3",
            rerootedEpisodeStreamId("tt4873036:7:3", "tt4873036")
        )
    }

    @Test
    fun `a prefixed root is swapped whole`() {
        // The show part is whatever the add-on calls the show - "tt...", a
        // "tmdb:<n>" flavor, a kitsu id - so the split keeps everything but the
        // last two segments and the swap replaces all of it.
        assertEquals(
            "a multi-segment root moves as one piece",
            "tt4873036:7:3",
            rerootedEpisodeStreamId("tmdb:57532:7:3", "tt4873036")
        )
        assertEquals(
            "and a prefixed root that IS the verified one stays put",
            "tmdb:57532:7:3",
            rerootedEpisodeStreamId("tmdb:57532:7:3", "tmdb:57532")
        )
    }

    @Test
    fun `only the stored root's exact prefix is dropped`() {
        // removePrefix, not a substring replace: a resolved root that merely
        // CONTAINS the stored one must not eat into the file part.
        assertEquals(
            "the resolved root is prepended whole, with one separator",
            "tt3121722x:7:3",
            rerootedEpisodeStreamId("tt3121722:7:3", "tt3121722x")
        )
    }

    @Test
    fun `an unresolved root leaves the stored id exactly as it was`() {
        // Blank is how "nothing resolved" reaches this - no API key, offline, a
        // show TMDB has no record for. The stored id is then the only id there
        // is, which is the same answer streamRootIdFor gives its own callers.
        listOf("", "   ").forEach { unresolved ->
            assertEquals(
                "an unresolvable root must not rewrite the id",
                "tt3121722:7:3",
                rerootedEpisodeStreamId("tt3121722:7:3", unresolved)
            )
        }
    }

    @Test
    fun `an id that names no episode is never rewritten`() {
        // Movies carry the root alone; a show-level id, a live channel's id and
        // a two-segment id name no season/episode pair, so there is no file
        // part a swap could preserve and the id is handed on untouched.
        listOf(
            "tt603",
            "tt3121722",
            "live:channel-3",
            "series/tt3121722",
            "tt3121722:7"
        ).forEach { notAnEpisode ->
            assertEquals(
                "only a season/episode request is re-rootable",
                notAnEpisode,
                rerootedEpisodeStreamId(notAnEpisode, "tt4873036")
            )
        }
    }

    // ── the wiring: the rail's two reads of a stored id ───────────────────

    private val home =
        normalized(stripComments(source(HOME)))

    @Test
    fun `the rail's card re-roots the row's own stream id`() {
        assertTrue(
            "the card's play press names its request by the row's id, so the row's id " +
                "is re-rooted before the card carries it",
            home.contains(
                "rerootStoredStreamId( entry.episodeStreamId .takeIf { namedRowEpisode != null }, capturedTmdbId )"
            )
        )
        assertFalse(
            "an id handed on as-is is how every episode of one show asks for " +
                "another title's streams",
            home.contains("entry.episodeStreamId .takeIf { namedRowEpisode != null } ?:")
        )
    }

    @Test
    fun `the resolver re-roots the resume row it files a card against`() {
        assertTrue(
            "a resume row's id is what the card hands the player, so it is re-rooted " +
                "in the resolver rather than at each card that reads it",
            home.contains("rerootStoredStreamId( resume.episodeStreamId, tmdbId ) ?: matchedResumeEpisode")
        )
        assertFalse(
            "the listing's own id is the fallback, and it is already rooted in the verified id",
            home.contains("resume.episodeStreamId ?: matchedResumeEpisode")
        )
    }

    @Test
    fun `the re-root asks the show's own verified root, not a catalog's`() {
        val helper =
            section(
                home,
                "rerootStoredStreamId( storedStreamId: String?, tmdbId: Int? )",
                "private fun observeUpNext("
            )
        assertTrue(
            "the root has to come from the id the resolver verified",
            helper.contains(
                "val resolvedRoot = tmdbRepository.streamRootIdFor( routeId = storedRoot, " +
                    "tmdbId = tmdbId, type = \"series\" )"
            )
        )
        assertTrue(
            "and the split has to be the one EpisodeMatch owns, so the rail and the " +
                "detail screen can never disagree about where an id points",
            helper.contains("EpisodeMatch.seriesRootFrom(stored)")
        )
        assertTrue(
            "an id that names no episode must be answered unchanged, before any lookup",
            helper.contains("?: return storedStreamId")
        )
        assertTrue(
            "and the rule itself is applied to what the resolver answered",
            helper.contains("return rerootedEpisodeStreamId(stored, resolvedRoot)")
        )
    }

    @Test
    fun `the instant snapshot is the one place a stored id is still used as filed`() {
        // The cold-start seed builds its cards from the history query alone: no
        // TMDB id, so nothing to resolve the root against - and the enriched
        // pass replaces these cards a moment later. A seed that ever learns the
        // show's TMDB id has to go through the same helper, which is what this
        // pins: the bare read lives here and nowhere else.
        val snapshot =
            section(
                home,
                "private suspend fun publishInstantUpNextSnapshot()",
                "private fun previousSimklUpNextItems()"
            )
        assertTrue(
            "the seed has no TMDB id to verify a root with",
            snapshot.contains("episodeStreamId = entry.episodeStreamId")
        )
        assertFalse(
            "and it therefore must not re-root anything",
            snapshot.contains("rerootStoredStreamId(")
        )
        assertEquals(
            "no other read of a history row's id may skip the re-root",
            2,
            Regex("entry\\.episodeStreamId").findAll(home).count()
        )
    }

    /** The text between two anchors, with both anchors asserted to exist. */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor not found: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor not found after $from: $to", end > start)
        return text.substring(start, end)
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
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
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
