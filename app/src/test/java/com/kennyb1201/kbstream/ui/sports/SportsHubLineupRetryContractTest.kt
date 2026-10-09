package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lineup read's retry, and the two ways it used to lose.
 *
 * `retryLineup` dropped the in-flight read WITHOUT cancelling it. The orphan
 * kept running on `Dispatchers.IO`, and `readPlaylistChannels` writes its result
 * straight into the lineup field - so the stale read could finish after the fresh
 * one and put the pre-retry lineup back. The retry then "succeeded" while showing
 * the very channels the viewer pressed it to get rid of. `prewarmLineup` could not
 * help: it only checks that the job reference is non-null, and the reference had
 * just been nulled.
 *
 * Two halves are pinned here: the replaced read is CANCELLED before the reference
 * is dropped, and the publish is generation-guarded, so a read already past its
 * last suspension point (cancellation cannot stop it) still cannot overwrite a
 * newer read's result.
 *
 * There is no TV in CI and this ViewModel needs an Application, an IPTV
 * repository and a network, so - like the hub's other contracts - the wiring is
 * read out of the source. `isCancelled` on the old job follows from `cancel()`
 * being called on it before its reference is replaced, which is what is checked.
 */
class SportsHubLineupRetryContractTest {

    private val vm: String by lazy { flat(VM) }

    // ── the orphan is cancelled ────────────────────────────────────────────

    @Test
    fun `retryLineup cancels the read it replaces, before dropping it`() {
        val body = bodyOf("fun retryLineup()")

        assertTrue(
            "an orphaned read keeps running and writes the stale lineup into the field " +
                "(readPlaylistChannels writes it directly) - it has to be cancelled",
            body.contains("channelsJob?.cancel()")
        )
        assertTrue(
            "and cancelled BEFORE the reference is nulled, which is the order that makes the " +
                "old job's isCancelled true while the new read is still to be started",
            body.indexOf("channelsJob?.cancel()") < body.indexOf("channelsJob = null")
        )
        assertTrue(
            "a retry with no read in flight must not crash on a null job",
            body.contains("channelsJob?.cancel()")
        )
        assertTrue(
            "the retry still drops what the old read filled and starts fresh",
            body.contains("playlistChannels = null") &&
                body.contains("guideIndexCache = null") &&
                body.contains("prewarmLineup()") &&
                body.contains("refresh()")
        )
    }

    @Test
    fun `retry is the only path that replaces an in-flight read`() {
        assertEquals(
            "the prewarm shares its read with the matching pass; only the retry may take one away",
            1,
            Regex("channelsJob\\?\\.cancel\\(\\)").findAll(vm).count()
        )
        val prewarm = bodyOf("private fun prewarmLineup()")
        assertTrue(
            "which is what keeps the happy path from reading the playlist twice",
            prewarm.contains("if (channelsJob != null || playlistChannels != null) return")
        )
        assertTrue(prewarm.contains("startLineupRead()"))
    }

    // ── a superseded read cannot publish ───────────────────────────────────

    @Test
    fun `the write is generation-guarded, so a stale read cannot clobber a newer one`() {
        val read = bodyOf("private suspend fun readPlaylistChannels(")

        assertTrue(
            "the read has to know which generation it was started in (the parameter is in the " +
                "signature, ahead of the body this slice holds)",
            vm.contains("private suspend fun readPlaylistChannels(generation: Int):") &&
                read.contains("generation == lineupGeneration")
        )
        assertTrue(
            "and only the newest one may publish: a coroutine past its last suspension point " +
                "cannot be cancelled, so cancel alone does not cover this",
            read.contains("if (generation == lineupGeneration && distinct.isNotEmpty()) {")
        )
        val guard = read.indexOf("if (generation == lineupGeneration")
        val close = read.indexOf('}', guard)
        assertTrue(
            "the field write must sit INSIDE the guard, or the guard is decorative",
            guard >= 0 && close > guard &&
                read.substring(guard, close).contains("playlistChannels = distinct")
        )
        assertFalse(
            "and there must be no unguarded write anywhere else in the read",
            read.replace(Regex("\\s+"), " ").split("playlistChannels = distinct").size - 1 > 1
        )
    }

    @Test
    fun `the generation is stamped when a read starts, not when its body runs`() {
        val start = bodyOf("private fun startLineupRead()")

        assertTrue(
            "a read is stamped with the generation it starts in",
            start.contains("val generation = ++lineupGeneration")
        )
        assertTrue(
            "stamped on the caller's thread, BEFORE the async: the order reads are STARTED in is " +
                "what orders their writes, and an async body does not run until its dispatcher does",
            start.indexOf("++lineupGeneration") < start.indexOf("viewModelScope.async")
        )
        assertTrue(
            "and handed to the read that writes the field",
            start.contains("readPlaylistChannels(generation)")
        )
        assertTrue(
            "the counter is visible across threads - the guard reads it from Dispatchers.IO",
            vm.contains("@Volatile private var lineupGeneration = 0")
        )
    }

    // ── the paths that must not change ─────────────────────────────────────

    @Test
    fun `a failed read still degrades to empty, and an empty lineup is reported as missing`() {
        val start = bodyOf("private fun startLineupRead()")

        assertTrue(
            "a failed read is an empty lineup, not a failed pass",
            start.contains("runCatchingCancellable { readPlaylistChannels(generation) }")
        )
        assertTrue(start.contains(".getOrDefault(emptyList())"))
        assertTrue(
            "and cancellation stays a NORMAL path, not a failure: the helper rethrows it instead " +
                "of turning a cancelled read into \"the read failed\"",
            flat(RUN_CATCHING)
                .contains("catch (cancellation: CancellationException) { throw cancellation }")
        )

        val resolve = slice("private suspend fun resolveMatches(", "private suspend fun cachedGuideIndex(")
        assertTrue(
            "an empty lineup is MISSING - the hub has nothing to compare against - never " +
                "twenty cards claiming \"not in your playlist\"",
            resolve.contains("_lineupStatus.value = LineupStatus.MISSING")
        )
        assertTrue(
            "and that is the branch the empty read takes",
            resolve.indexOf("channels.isEmpty()") < resolve.indexOf("LineupStatus.MISSING")
        )
    }

    @Test
    fun `retryStandings is a separate path and stays out of this one`() {
        val standings = bodyOf("fun retryStandings()")
        assertFalse(standings.contains("channelsJob"))
        assertFalse(standings.contains("lineupGeneration"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The flat source of the body that follows [marker], braces matched. The
     * marker must stop before the `{` - a return type may sit in between.
     */
    private fun bodyOf(marker: String): String {
        val start = vm.indexOf(marker)
        assertTrue("$marker is missing", start >= 0)
        val open = vm.indexOf('{', start + marker.length)
        assertTrue("no body after $marker", open >= 0)
        var depth = 0
        for (i in open until vm.length) {
            when (vm[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return vm.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces after $marker")
    }

    private fun slice(startMarker: String, endMarker: String): String {
        val start = vm.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = vm.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return vm.substring(start, end)
    }

    private fun flat(relative: String): String = source(relative).replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val VM = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val RUN_CATCHING = "com/kennyb1201/kbstream/data/RunCatchingCancellable.kt"
    }
}
