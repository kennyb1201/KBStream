package com.kennyb1201.kbstream.data.player

import com.kennyb1201.kbstream.data.reporting.PerfTrace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one leg the playback figures were missing: Play press to the player
 * opening (spec 2026-11-15, Play-to-first-frame F1).
 *
 * Every number the report already had starts at `createPlayer()`, so the add-on
 * probes, the TorBox mint and the "Finding sources" splash were outside all of
 * them. The spec's own warning is the reason this is pinned: a "Play-to-first-
 * frame" figure that silently covers only player-creation-to-first-frame
 * understates the wait the viewer feels.
 *
 * [PlaybackSessionTrace] takes its clock as a parameter so both branches -
 * measured and not-measured - are reachable here.
 */
class PlaybackSessionTraceTest {

    @Test
    fun `the press to the player opening is one measured leg`() {
        PlaybackSessionTrace.markPressed(atMs = 1_000L)
        val leg = PlaybackSessionTrace.beginSession(engine = "exo", kind = "debrid", atMs = 3_400L)

        assertEquals("the splash the viewer watched", 2_400L, leg)
        assertEquals(
            "and it reaches the ring beside the rest of the playback figures",
            2_400L,
            PerfTrace.latestByPrefix("playback.resolve").firstOrNull()?.second
        )
        val facts = PlaybackSessionTrace.facts()
        assertEquals("exo", facts?.engine)
        assertEquals("debrid", facts?.kind)
        assertEquals(2_400L, facts?.resolveMs)
    }

    @Test
    fun `a launch with no press reports no leg rather than a made-up one`() {
        // A handoff, a zap or a download never pressed Play.
        PlaybackSessionTrace.beginSession(engine = "mpv", kind = "http", atMs = 50_000L)
        assertEquals(
            "a dash in the report, never a second leg invented from the last press",
            -1L,
            PlaybackSessionTrace.facts()?.resolveMs
        )
    }

    @Test
    fun `a press that never became a launch is not attributed to the next one`() {
        // A resolve that failed and left the picker up: the press is stale, and
        // whatever opens the player later must not claim the whole wait.
        PlaybackSessionTrace.markPressed(atMs = 1_000L)
        val leg = PlaybackSessionTrace.beginSession(
            engine = "exo",
            kind = "http",
            atMs = 1_000L + 10 * 60_000L
        )
        assertEquals(-1L, leg)
    }

    @Test
    fun `one press measures one launch`() {
        PlaybackSessionTrace.markPressed(atMs = 5_000L)
        assertEquals(1_000L, PlaybackSessionTrace.beginSession("exo", "http", atMs = 6_000L))
        assertEquals(
            "the second session in the same process has no press of its own",
            -1L,
            PlaybackSessionTrace.beginSession("exo", "http", atMs = 9_000L)
        )
    }

    @Test
    fun `nothing has been recorded before the first launch`() {
        PlaybackSessionTrace.beginSession("exo", "http", atMs = 1L)
        // The facts stay for the session; what matters is that a fresh process
        // starts empty, which the type says by returning null.
        assertTrue(
            "facts() answers for a launch that happened, and is the line's source",
            PlaybackSessionTrace.facts() != null
        )
        assertNull(
            "a label nothing recorded yet has no ring entry",
            PerfTrace.latestByPrefix("playback.neverRecorded").firstOrNull()
        )
    }

    // ── The wiring the report depends on ──────────────────────────────────

    @Test
    fun `both engines close the leg and name themselves`() {
        val exo = read("ui/player/NativePlayerActivity.kt")
        val mpv = read("ui/player/MpvPlayerActivity.kt")
        assertTrue(
            "the ExoPlayer engine closes the leg where its own clock starts",
            exo.contains("engine = \"exo\"")
        )
        assertTrue(
            "and MPV closes it too - the resolve leg is the one measurement both " +
                "engines can make",
            mpv.contains("engine = \"mpv\"")
        )
        assertTrue(
            "each classifies the link it actually plays (debrid host or tag), so " +
                "the two engines' lines are comparable",
            exo.contains("StreamRanker.isDebridServed(") && mpv.contains("StreamRanker.isDebridServed(")
        )
    }

    @Test
    fun `the press is stamped where the viewer pressed, not in the player`() {
        val activity = read("MainActivity.kt")
        assertTrue(
            "Play from Detail/Home/Continue Watching resolves in this effect - it " +
                "is the wait, so it is where the press is stamped",
            activity.contains("PlaybackSessionTrace.markPressed()")
        )
        assertTrue(
            "and a picker row press stamps the same wait",
            read("ui/streams/StreamsScreen.kt").contains("PlaybackSessionTrace.markPressed()") &&
                read("ui/mobile/StreamsScreenMobile.kt").contains("PlaybackSessionTrace.markPressed()")
        )
    }

    @Test
    fun `the split is never merged into one number`() {
        val diagnostics = read("data/reporting/Diagnostics.kt")
        // These five strings belong to playbackLine() alone, so the whole file is
        // searched rather than a slice whose end would have to be guessed.
        val line = diagnostics
        assertTrue(
            "source→ready and ready→firstFrame are still printed apart: the split " +
                "is what separates the network from the decoder",
            line.contains("source→ready=") && line.contains("ready→firstFrame=")
        )
        assertTrue(
            "the new leg is printed beside them",
            line.contains("append(\" resolve=\")")
        )
        assertTrue(
            "with the engine and the source kind",
            line.contains("append(\" engine=\")") && line.contains("append(\" src=\")")
        )
        assertTrue(
            "and the MPV session says its split is Exo-only rather than showing a " +
                "bare dash",
            line.contains("split=exo-only")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
