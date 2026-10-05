package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next episode's synopsis riding the handoff.
 *
 * Reported bug: on an autoplay/PLAY NEXT handoff the new session's overlay read
 * "S02E11" — the correct number and title — over S02E10's overview. The handoff
 * carried the number and title but not the synopsis, and MainActivity fell back
 * to the finished episode's Screen.Player value.
 *
 * The wire format is the part that has to survive: a persisted handoff is
 * written by one build and read by the next, so an older string must still
 * decode (with the overview null, which the caller's fallback covers) and a
 * synopsis must never be able to smuggle a field separator into the record.
 */
class NextEpisodeResultOverviewContractTest {

    private val pending = NextEpisodeResult.PendingNext(
        season = 2,
        episode = 11,
        title = "S2E11 \u2022 Lights Out",
        streamId = "tt1234567:2:11",
        runtimeMinutes = 48,
        bingeGroup = "grp",
        addonName = "Torrentio",
        overview = "Rick loses the plot."
    )

    // ── round trip ──────────────────────────────────────────────────────

    @Test
    fun `the overview survives a round trip`() {
        val decoded = NextEpisodeResult.decode(NextEpisodeResult.encode(pending))

        assertEquals(pending, decoded)
        assertEquals("Rick loses the plot.", decoded?.overview)
    }

    @Test
    fun `a round trip keeps the random marker`() {
        val random = pending.copy(randomEpisodes = true)

        val decoded = NextEpisodeResult.decode(NextEpisodeResult.encode(random))

        assertEquals(random, decoded)
        assertTrue(decoded?.randomEpisodes == true)
        assertEquals("Rick loses the plot.", decoded?.overview)
    }

    @Test
    fun `a separator in the overview cannot split the record`() {
        // A pipe in the synopsis would add a field and shift every trailing one,
        // so it is stripped - exactly how bingeGroup and addonName are handled.
        val decoded =
            NextEpisodeResult.decode(
                NextEpisodeResult.encode(pending.copy(overview = "A|B|C"))
            )

        assertEquals("ABC", decoded?.overview)
        assertEquals(pending.addonName, decoded?.addonName)
        assertEquals(pending.bingeGroup, decoded?.bingeGroup)
        assertEquals(pending.streamId, decoded?.streamId)
        assertTrue(pending.runtimeMinutes == decoded?.runtimeMinutes)
    }

    @Test
    fun `a pipe in the title is still rejoined`() {
        val decoded =
            NextEpisodeResult.decode(
                NextEpisodeResult.encode(pending.copy(title = "S2E11 | part one"))
            )

        assertEquals("S2E11 | part one", decoded?.title)
        assertEquals("Rick loses the plot.", decoded?.overview)
    }

    @Test
    fun `a blank or absent overview decodes to null`() {
        val blank = NextEpisodeResult.encode(pending.copy(overview = ""))
        val absent = NextEpisodeResult.encode(pending.copy(overview = null))

        assertNull(NextEpisodeResult.decode(blank)?.overview)
        assertNull(NextEpisodeResult.decode(absent)?.overview)
    }

    // ── backward compatibility ──────────────────────────────────────────

    @Test
    fun `a seven-field record from before the overview still decodes`() {
        val decoded = NextEpisodeResult.decode("2|11|Lights Out|tt1234567:2:11|48|grp|Torrentio")

        assertNull("an older handoff has no overview to offer", decoded?.overview)
        assertEquals("Torrentio", decoded?.addonName)
        assertEquals("grp", decoded?.bingeGroup)
        assertEquals("tt1234567:2:11", decoded?.streamId)
        assertTrue(decoded?.runtimeMinutes == 48)
    }

    @Test
    fun `a six-field record still decodes`() {
        val decoded = NextEpisodeResult.decode("2|11|Lights Out|tt1234567:2:11|48|grp")

        assertNull(decoded?.overview)
        assertNull(decoded?.addonName)
        assertEquals("grp", decoded?.bingeGroup)
    }

    @Test
    fun `a five-field record still decodes`() {
        val decoded = NextEpisodeResult.decode("2|11|Lights Out|tt1234567:2:11|48")

        assertNull(decoded?.overview)
        assertNull(decoded?.addonName)
        assertNull(decoded?.bingeGroup)
        assertTrue(decoded?.season == 2)
        assertTrue(decoded?.episode == 11)
    }

    @Test
    fun `a seven-field record with the random marker still decodes`() {
        val decoded =
            NextEpisodeResult.decode("2|11|Lights Out|tt1234567:2:11|48|grp|Torrentio|#rnd")

        assertNull(decoded?.overview)
        assertEquals("Torrentio", decoded?.addonName)
        assertTrue(decoded?.randomEpisodes == true)
    }

    // ── the carrying itself, which the format test cannot see ───────────

    @Test
    fun `both engines fill the overview from the episode lookup`() {
        for (engine in listOf(NATIVE, MPV)) {
            val src = readSource(engine)
            assertTrue(
                "$engine must keep the next episode's overview",
                src.contains("pendingNextEpisodeOverview: String? = null")
            )
            assertTrue(
                "$engine must take it from the resolved episode",
                src.contains(
                    "pendingNextEpisodeOverview = nextEp.overview?.takeIf { it.isNotBlank() }"
                )
            )
            assertTrue(
                "$engine must carry it into the handoff",
                src.contains("overview = episodeOverview,")
            )
            assertTrue(
                "$engine must send it over the result extras",
                src.contains("putExtra(\"next_overview\", pending")
            )
        }
    }

    @Test
    fun `MainActivity prefers the handoff's overview and falls back only when it has none`() {
        val src = readSource(MAIN)

        assertTrue(
            "the persisted branch must prefer the handoff",
            src.contains("overview = next.overview ?: current.overview,")
        )
        assertTrue(
            "the result-extras branch must read the extra",
            src.contains("val nextOverview = data.getStringExtra(\"next_overview\")")
        )
        assertTrue(
            "the result-extras branch must prefer the handoff",
            src.contains("overview = nextOverview ?: current.overview,")
        )
    }

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
