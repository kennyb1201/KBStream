package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every player that decodes video inside THIS process must hold the guide
 * write gate (`EpgWriteGate`) while it owns the screen, and release it on stop
 * and on destroy.
 *
 * The gate exists because a guide import inserts thousands of rows and then
 * re-keys the table in one staging→live swap, on the same SQLite pool and the
 * same heap the decoder is filling — the field log for it is a 6.9 s rebuffer
 * stall on a stream that otherwise played cleanly. ExoPlayer held the gate from
 * the start; mpv decoded for its whole life without ever holding it, and
 * nothing about writing an Activity makes that omission visible.
 *
 * A new engine, or a dropped release, now fails here instead of shipping as a
 * stall someone has to reproduce on a TV box.
 *
 * ExternalPlayerActivity is deliberately not in the list: it hands the stream
 * to another app, which does its own decoding, so there is no playback of ours
 * to keep the guide out of the way of. (It still enforces the Kids Mode lock
 * on create, like the other two — see KidsTimeGuard.)
 */
class PlayerGuideWriteGateContractTest {

    private companion object {
        const val SET_ACTIVE = "EpgWriteGate.setPlayerActive(true)"
        const val SET_IDLE = "EpgWriteGate.setPlayerActive(false)"
        const val IMPORT = "import com.kennyb1201.kbstream.data.iptv.EpgWriteGate"

        /** Activities that decode video inside this process. */
        val InProcessPlayers = listOf("NativePlayerActivity", "MpvPlayerActivity")
    }

    private val playerDir: File by lazy { findPlayerSourceDir() }

    /**
     * Resolves `…/ui/player` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
    private fun findPlayerSourceDir(): File {
        val relative = "com/kennyb1201/kbstream/ui/player"
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "player sources not found walking up from " +
                System.getProperty("user.dir")
        )
    }

    private fun source(activity: String): String {
        val file = File(playerDir, "$activity.kt")
        assertTrue("player source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `in-process players hold the guide gate while decoding`() {
        InProcessPlayers.forEach { activity ->
            assertTrue(
                "$activity decodes video in this process but never calls $SET_ACTIVE",
                source(activity).contains(SET_ACTIVE)
            )
            assertTrue(
                "$activity must import EpgWriteGate",
                source(activity).contains(IMPORT)
            )
        }
    }

    @Test
    fun `in-process players release the guide gate on stop and on destroy`() {
        InProcessPlayers.forEach { activity ->
            val src = source(activity)
            // onStop normally, onDestroy as the safety net for a player that
            // never reached onStop — leaving it set holds guide writes back
            // (until the per-session budget runs out) with no player on screen.
            val releases = src.split(SET_IDLE).size - 1
            assertTrue(
                "$activity releases the gate $releases time(s): expected one in " +
                    "onStop and one in onDestroy",
                releases >= 2
            )
        }
    }
}
