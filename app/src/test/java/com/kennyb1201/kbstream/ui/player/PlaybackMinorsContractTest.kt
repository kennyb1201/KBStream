package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback minors of 2026-11-11, read out of the source.
 *
 * Three unrelated-looking symptoms, one shape: work the code already knows how
 * to do was not done at the one call site that needed it.
 *
 *  - m19: the batched cached-check existed and the pickers called it, but the
 *    autoplay walk did not - so a fresh-process walk paid one live
 *    `checkCached` per hash row out of its own budget;
 *  - m21: the trailer resolve's mutex surrounded the network waits, so one slow
 *    hero stalled every trailer queued behind it;
 *  - m17's residual: four MPV cross-thread flags were still plain `var`s, so a
 *    stale `pauseState` could tick the Up Next countdown on a paused session.
 *
 * Source-reading, like the rest of this module's contracts: all three live
 * inside Android-bound classes. The F1 and F2 pins fail against the 0.9.223
 * source; the F3 pin fails there too (`@Volatile` arrived with m17).
 */
class PlaybackMinorsContractTest {

    private val activity by lazy { source(ACTIVITY) }
    private val torbox by lazy { source(TORBOX) }
    private val launcher by lazy { source(LAUNCHER) }
    private val mpvView by lazy { source(MPV_VIEW) }

    // ── F1 (m19): one cached check before the walk ─────────────────────────

    @Test
    fun `the autoplay walk warms the cached memo before it starts`() {
        val candidates = activity.indexOf("val walkCandidates =")
        val prefetch = activity.indexOf("TorBoxResolve.prefetchCached(context, walkCandidates)")
        val budget = activity.indexOf("val walkStartedAt = System.currentTimeMillis()")
        val walk = activity.indexOf("AutoplayWalk.start(")
        assertTrue("F1: the walk warms the memo the walk itself reads", prefetch >= 0)
        assertTrue(
            "F1: with the candidates the walk is about to use",
            candidates >= 0 && candidates < prefetch
        )
        assertTrue("F1: before the walk starts", prefetch < walk)
        assertTrue(
            "F1: and before its budget starts, so the prefetch is not charged to the walk",
            budget >= 0 && prefetch < budget
        )
    }

    @Test
    fun `the prefetch asks once for the whole set and cannot fail the walk`() {
        val body = torbox
            .substringAfter("suspend fun prefetchCached(")
            .substringBefore("/**\n     * The CDN link for one file of a cloud row")
        val calls = Regex("TorBoxClient\\.checkCached\\(").findAll(body).count()
        assertTrue("F1: the prefetch is a check, not a resolve path", calls == 1)
        assertTrue(
            "F1: ONE request for every hash in the set",
            body.contains("TorBoxClient.checkCached(context, hashes)")
        )
        assertTrue(
            "F1: only rows that have a hash, each hash once",
            body.contains(".filter { isUnresolved(it) }") &&
                body.contains(".mapNotNull { normalizedHash(it.infoHash) }") &&
                body.contains(".distinct()")
        )
        assertTrue(
            "F1: best-effort - a failed prefetch is logged, not thrown into the walk",
            body.contains("runCatching {") && body.contains("cached prefetch failed:")
        )
    }

    @Test
    fun `the per-row check and the chipless rows are untouched`() {
        val body = torbox.substringAfter("suspend fun playableStream(")
        assertTrue(
            "F1: playableStream still asks per row - the prefetch makes it a memo hit",
            body.contains("TorBoxClient.checkCached(context, listOf(hash))")
        )
        assertTrue(
            "F1: and still answers an unavailable check as unplayable, as it did",
            body.contains("TorBoxCachedAnswer.Unavailable -> {")
        )
        assertTrue(
            "F1: a chipless row is not a hash row and never reaches the check",
            body.contains("if (!isUnresolved(stream)) return@withContext null") &&
                body.contains("val hash = normalizedHash(stream.infoHash) ?: return@withContext null")
        )
        assertTrue(
            "F1: a cloud row is prepared from the account, before any cached check",
            body.indexOf("TorBoxCloudPicker.refOf(stream.url)") <
                body.indexOf("TorBoxClient.checkCached(context, listOf(hash))")
        )
    }

    // ── F2 (m21): the lock covers the bookkeeping, not the waits ───────────

    @Test
    fun `no network leg of a resolve runs under the lock`() {
        val resolve = resolveBody()
        assertFalse(
            "F2: the resolve is no longer one big withLock",
            launcher.contains("): Result<PlayableSource> = resolveMutex.withLock {")
        )
        val blocks = lockBlocks(resolve)
        assertTrue("F2: the resolve still takes the lock for its bookkeeping", blocks.size >= 4)
        val network = listOf(
            "resolveImdbSource(",
            "ImdbTrailerResolver.",
            "InnerTubeExtractor.extractPlaybackSourceWithClient(",
            "NewPipeManager.getPlayableUrl("
        )
        blocks.forEachIndexed { index, block ->
            network.forEach { call ->
                assertFalse(
                    "F2: lock block #$index must not hold the mutex across $call",
                    block.contains(call)
                )
            }
        }
    }

    @Test
    fun `the bookkeeping runs under the lock`() {
        val resolve = resolveBody()
        val refusal = launcher.substringAfter("private fun refusalFor(")
        assertTrue(
            "F2: the prune is under the lock, in the refusal unit",
            refusal.contains("pruneCaches()")
        )
        assertTrue(
            "F2: the identity refusal reads its set under the lock",
            refusal.contains("identityBlocked.contains(videoId)")
        )
        assertTrue(
            "F2: the failure throttle reads, drops and records under the lock",
            refusal.contains("resolutionFailures[videoId]?.let { failedAt ->") &&
                refusal.contains("resolutionFailures.remove(videoId)") &&
                resolve.contains("resolveMutex.withLock { markFailed(videoId) }")
        )
        assertTrue(
            "F2: the cache read is the locked unit, stale entries included",
            resolve.contains("resolveMutex.withLock { freshCachedSource(videoId) }") &&
                launcher.contains("private fun freshCachedSource(videoId: String)") &&
                launcher.contains("sourceCache.remove(videoId)")
        )
        assertTrue(
            "F2: both cache writes are under the lock",
            resolve.contains("resolveMutex.withLock {") &&
                resolve.contains("sourceCache[videoId] = CachedSource(") &&
                resolve.contains("sourceCache[videoId] = CachedSource(source, client = null)")
        )
        val blocks = lockBlocks(resolve)
        assertTrue(
            "F2: every sourceCache write in the resolve happens inside a lock block",
            blocks.count { it.contains("sourceCache[videoId] = CachedSource") } == 2
        )
        assertTrue(
            "F2: the IMDb mount markers are under the lock",
            blocks.any {
                it.contains("resolutionFailures.remove(videoId)") &&
                    it.contains("imdbMounted[videoId] = System.currentTimeMillis()")
            }
        )
    }

    @Test
    fun `the cache is re-read under the lock after the IMDb wait`() {
        val resolve = resolveBody()
        val imdb = resolve.indexOf("val imdb = resolveImdbSource(")
        val cache = resolve.indexOf("resolveMutex.withLock { freshCachedSource(videoId) }")
        val extractor = resolve.indexOf("InnerTubeExtractor.extractPlaybackSourceWithClient(")
        assertTrue("F2: IMDb is still first", imdb >= 0)
        assertTrue(
            "F2: the cache is consulted after the IMDb wait, so a concurrent store is seen",
            imdb in 0 until cache
        )
        assertTrue("F2: and before the extractor, as it was", cache in 0 until extractor)
        assertTrue(
            "F2: the resolve order is unchanged - IMDb, then extractor, then the fallback",
            resolve.indexOf("NewPipeManager.getPlayableUrl(") > extractor
        )
        assertTrue(
            "F2: the exclusion resolve still skips the cache",
            resolve.contains("if (excludeClients.isEmpty()) {\n" +
                "            resolveMutex.withLock { freshCachedSource(videoId) }?.let { return it }")
        )
    }

    // ── F3 (P1, m17 residual): the four flags are volatile ─────────────────

    @Test
    fun `the four MPV cross-thread flags are volatile`() {
        listOf("pauseState", "cacheStall", "lastPositionMs", "lastDurationMs").forEach { name ->
            val declaration = mpvView.indexOf("private var $name")
            assertTrue("F3: $name is declared", declaration >= 0)
            val before = mpvView.substring(0, declaration).trimEnd()
            assertTrue(
                "F3: $name is @Volatile, like the rest of the m17 set",
                before.endsWith("@Volatile")
            )
            val comment = before.substringBeforeLast("@Volatile")
            assertTrue(
                "F3: $name carries the same written-here/read-there comment",
                comment.contains("Volatile: written on mpv's event thread") &&
                    comment.contains("read")
            )
        }
    }

    @Test
    fun `the seek flag is left out of the annotation`() {
        assertTrue(
            "F3: pendingSeekMs is written and read on the main-thread side only",
            mpvView.contains("private var pendingSeekMs = 0L")
        )
        assertFalse(
            "F3: so it is not annotated",
            mpvView.contains("@Volatile\n    private var pendingSeekMs")
        )
        assertTrue(
            "F3: the readers are still the main-thread ones",
            mpvView.contains("fun positionMs(): Long = lastPositionMs") &&
                mpvView.contains("fun durationMs(): Long = lastDurationMs") &&
                mpvView.contains("fun isPaused(): Boolean = pauseState")
        )
    }

    // ── slices ─────────────────────────────────────────────────────────────

    private fun resolveBody(): String =
        launcher
            .substringAfter("    ): Result<PlayableSource> {")
            .substringBefore("/**\n     * The IMDb leg of a resolve")

    /** Every `resolveMutex.withLock { … }` block in [text], braces balanced. */
    private fun lockBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("resolveMutex.withLock {", from)
            if (at < 0) break
            val open = text.indexOf('{', at)
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            blocks += text.substring(open, i)
            from = i + 1
        }
        return blocks
    }

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
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val TORBOX = "com/kennyb1201/kbstream/data/debrid/TorBoxResolve.kt"
        const val LAUNCHER = "com/kennyb1201/kbstream/data/youtube/TrailerPlayerLauncher.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
