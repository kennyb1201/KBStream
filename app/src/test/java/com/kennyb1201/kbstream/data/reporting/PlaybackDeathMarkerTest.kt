package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one record that outlives a process killed under playback.
 *
 * Reported as "after clicking play on different episodes it kicks me back to the
 * launcher with no Sentry error recorded", and the reason there is no Sentry
 * error is structural: every other playback record the app keeps lives in the
 * process that dies. What has to be right, then, is not an error path but two
 * things a JVM test can check without a device:
 *
 *  - the sentence must say which engine, which episode, and whether the death
 *    came before a picture or under a running session, because those are the
 *    facts that separate a broken launch from the system reclaiming a player;
 *  - the wiring must be present in all three engines and read at the next
 *    launch, since a marker nobody writes is indistinguishable from a clean
 *    exit, and a marker nobody reads answers nothing.
 */
class PlaybackDeathMarkerTest {

    private val death = PlaybackDeathMarker.Death(
        engine = PlaybackDeathMarker.ENGINE_EXO,
        label = "Severance S2E5",
        stage = PlaybackDeathMarker.STAGE_LOADING,
        positionMs = 0L,
        openForMs = 2_000L,
        sourceHost = "cdn.example.com"
    )

    // ── the sentence ───────────────────────────────────────────────────────

    @Test
    fun `still loading says there was no picture yet`() {
        assertEquals(
            "a death on the way in must not read like a session that stopped",
            "exo playback: the process died with the player still loading " +
                "(no picture yet) — \"Severance S2E5\" at 0:00, open 0:02, " +
                "source cdn.example.com",
            death.sentence()
        )
    }

    @Test
    fun `a running session reports its playhead and how long the player was open`() {
        val playing = death.copy(
            stage = PlaybackDeathMarker.STAGE_PLAYING,
            positionMs = 754_000L,
            openForMs = 780_000L
        )

        assertEquals(
            "exo playback: the process died with the player playing — " +
                "\"Severance S2E5\" at 12:34, open 13:00, source cdn.example.com",
            playing.sentence()
        )
    }

    @Test
    fun `a backgrounded session is not an on-screen death`() {
        assertTrue("still loading is a death in front of the viewer", death.wasOnScreen)
        assertFalse(
            "the system reclaiming a paused player must not read as a crash",
            death.copy(stage = PlaybackDeathMarker.STAGE_BACKGROUND).wasOnScreen
        )
    }

    @Test
    fun `a session with no source host still says source unknown`() {
        assertTrue(death.copy(sourceHost = "").sentence().endsWith("source unknown"))
    }

    @Test
    fun `clock reads mm-ss and h-mm-ss, and never goes negative`() {
        assertEquals("0:00", PlaybackDeathMarker.clock(0L))
        assertEquals("0:00", PlaybackDeathMarker.clock(-5_000L))
        assertEquals("1:05", PlaybackDeathMarker.clock(65_999L))
        assertEquals("1:02:05", PlaybackDeathMarker.clock(3_725_000L))
    }

    // ── the wiring ─────────────────────────────────────────────────────────

    @Test
    fun `every engine arms and clears its own marker, under its own name`() {
        ENGINE_SOURCES.forEach { (engine, path) ->
            val source = read(path)
            assertTrue(
                "$engine must arm the marker at play, or a kill leaves no record",
                source.contains("PlaybackDeathMarker.arm(")
            )
            assertTrue(
                "$engine must name itself, or the report cannot say which player died",
                source.contains("PlaybackDeathMarker.ENGINE_${engine.uppercase()},")
            )
            assertTrue(
                "$engine must clear it on a clean exit, or every exit reports a death",
                source.contains("PlaybackDeathMarker.clear(this, it)")
            )
        }
    }

    @Test
    fun `both in-app engines record the moment the first picture arrives`() {
        listOf(ENGINE_SOURCES.getValue("exo"), ENGINE_SOURCES.getValue("mpv")).forEach { path ->
            val source = read(path)
            assertTrue(
                "$path must move the marker past loading once a picture exists",
                source.contains("PlaybackDeathMarker.STAGE_PLAYING")
            )
            assertTrue(
                "$path must record the screen being left, which is the ordinary reclaim",
                source.contains("PlaybackDeathMarker.STAGE_BACKGROUND")
            )
        }
    }

    @Test
    fun `the next launch reports the surviving marker and only for a death on screen`() {
        val application = read("com/kennyb1201/kbstream/MainApplication.kt")

        assertTrue(
            "the marker is only worth writing if the next launch reads it",
            application.contains("PlaybackDeathMarker.consume(applicationContext)")
        )
        assertTrue(
            "an on-screen death goes to Sentry, or the report stays invisible",
            application.contains("\"source\" to \"playback_process_death\"")
        )
        assertTrue(
            "a background reclaim is a diagnostics line, not a crash event",
            application.contains("death.wasOnScreen")
        )
        assertTrue(
            "the background case must still be readable in the diagnostics export",
            application.contains("\"playback_process_death_background\"")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
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
        /** Engine name → its activity, keyed so the name in the report is checked. */
        val ENGINE_SOURCES = linkedMapOf(
            "exo" to "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "mpv" to "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "external" to "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        )
    }
}
