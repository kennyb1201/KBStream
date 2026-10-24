package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two buffer profiles' START gates, and who is allowed to get the short one.
 *
 * The field report this pins ("press play, the video starts, buffers for about
 * a second, then plays normally") is what a gate shorter than the source's own
 * jitter produces: playback begins on the cushion the gate promised, runs it
 * dry, and stalls once before settling. Two things had to be true for that to
 * happen on a VOD session:
 *
 *  1. Auto sent every HLS manifest to the low-latency profile, not just live
 *     channels - so an addon's VOD HLS started on the live gate.
 *  2. That gate was 1_500 ms, shorter than the profile's own after-rebuffer
 *     figure (3_000), which is the profile telling itself it cannot resume on
 *     less than it starts on.
 *
 * The rule here is the one both halves have to keep: a start gate is never
 * shorter than the after-rebuffer gate beside it, and the low-latency profile is
 * chosen by LIVENESS, not by container.
 *
 * Source text is what a JVM test can see for a player config (the module has no
 * instrumented harness for ExoPlayer); the behaviour is field-verified.
 */
class BufferStartGateContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun player(): String =
        File(
            sourceRoot,
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        ).readText().replace(Regex("\\s+"), " ")

    /**
     * Both `intArrayOf(min, max, start, afterRebuffer)` tuples, low profile
     * first (the order the two arms are written in).
     */
    private fun profiles(source: String): List<List<Long>> {
        val block = source.substringAfter("val bufferDurations =")
            .substringBefore("val maxBufferBudgetBytes")
        return Regex("intArrayOf\\(([^)]*)\\)").findAll(block).map { match ->
            match.groupValues[1].split(",").map { it.trim().replace("_", "").toLong() }
        }.toList()
    }

    @Test
    fun `every buffer profile starts on at least the cushion it resumes on`() {
        val tuples = profiles(player())
        assertTrue("both profile tuples must be found, got $tuples", tuples.size == 2)
        val low = tuples[0]
        val balanced = tuples[1]

        listOf("low" to low, "balanced" to balanced).forEach { (name, values) ->
            assertTrue("$name profile must have four figures, got $values", values.size == 4)
            val start = values[2]
            val afterRebuffer = values[3]
            assertTrue(
                "the $name profile's start gate (${start}ms) must not be shorter than " +
                    "its after-rebuffer gate (${afterRebuffer}ms): a session that starts " +
                    "on less than it will resume on drains at once and stalls",
                start >= afterRebuffer
            )
            assertTrue(
                "the $name profile's min buffer (${values[0]}ms) must not be shorter " +
                    "than its start gate ($start ms)",
                values[0] >= start
            )
        }

        // The low-latency profile is a live profile: its whole budget is 10s.
        assertTrue("the low profile keeps its short 10s ceiling", low[1] == 10_000L)
        assertTrue("the balanced profile keeps its 60s ceiling", balanced[1] == 60_000L)
    }

    @Test
    fun `Auto picks the low-latency profile by liveness, not by container`() {
        val auto = player().substringAfter("val resolvedBufferMode =")
            .substringBefore("} else bufferMode")

        assertTrue(
            "a live channel must still take the low-latency profile",
            auto.contains("if (isLiveChannel) 1 else 0")
        )
        assertFalse(
            "an HLS *manifest* is a container, not a liveness verdict: a VOD HLS must " +
                "get the balanced profile and its deeper start gate",
            auto.contains("APPLICATION_M3U8")
        )
    }
}
