package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenless-resolve wall, wired: where the re-mint runs, what happens to a
 * source it could not save, and how each trailer surface answers a death.
 *
 * The decisions themselves are pinned by plain JVM tests (TokenlessResolveWallTest,
 * HeroTrailerRetryPlanTest). What is left - and what this file is for - is the
 * CALL SITE, which a refactor can silently invert while everything still
 * compiles: a re-mint after the harvest, a tokenless source cached anyway, a hold
 * written into the failure memory, a surface that still re-signs the class that
 * just died.
 *
 * The settler is the 2026-11-03 phone capture: a GVS timeout must no longer
 * produce a MOUNTED tokenless source on any surface. The 2026-11-05 TV capture
 * then showed the adaptive-only spelling was too narrow - a tokenless
 * PROGRESSIVE winner sailed through the same gate - so the flag is now carried
 * by every InnerTube winner and the gate covers both kinds.
 */
class TokenlessResolveWiringContractTest {

    private val extractor = source(INNER_TUBE)
    private val launcher = source(TRAILER_LAUNCHER)
    private val mobileHome = source(MOBILE_HOME)
    private val home = source(HOME_SCREEN)

    // ── F1a: one GVS-only re-mint, before the winners exist ─────────────────

    @Test
    fun `the re-mint runs before the harvest and before the winners are picked`() {
        val mintAt = extractor.indexOf("val tokens = remintGvsWhenTokenless(")
        val harvestAt = extractor.indexOf("for (client in YouTubeClients.all) {")

        assertTrue("the re-mint must be wired into the resolve", mintAt >= 0)
        assertTrue(
            "the first mint's elapsed time is what the re-mint's budget is " +
                "accounted against, so it is read where that mint starts",
            extractor.contains("val mintStartedAtMs = System.nanoTime()")
        )
        assertTrue(
            "a tokenless resolve must be answered BEFORE the requests go out: the " +
                "tokens ride the player request",
            mintAt < harvestAt
        )
    }

    @Test
    fun `every InnerTube winner is judged by the cold-mint flag, whatever its kind`() {
        val hls = between(extractor, "Using HLS trailer manifest", "Prefer adaptive video + audio")
        val adaptive = between(extractor, "Using adaptive video+audio", "fall through to")
        val progressive = between(
            extractor,
            "Falling back to muxed progressive",
            "return null"
        )

        // The wall is a property of the URL the missing token would have signed,
        // not of the shape the winner took. The 2026-11-05 capture is the proof:
        // `pot.resolve ... pot=false kind=progressive` mounted and died at 41.9 s
        // of a 138.5 s trailer, because only the adaptive return carried the
        // flag and its gate was the only one the launcher had.
        listOf("hls" to hls, "adaptive" to adaptive, "progressive" to progressive)
            .forEach { (kind, block) ->
                assertTrue(
                    "a tokenless $kind winner must reach the gate, not the wall",
                    flat(block).contains("potlessByColdMint = tokenlessByColdMint")
                )
            }
        assertEquals(
            "and every return that can mount carries it - no shape keeps the old default",
            3,
            Regex("potlessByColdMint = tokenlessByColdMint").findAll(extractor).count()
        )
    }

    // ── F1b/F1c: never mount it; fall back, and hold without poisoning ──────

    @Test
    fun `a tokenless winner is neither mounted nor cached`() {
        val mountAt = launcher.indexOf("if (resolved != null && !resolved.potlessByColdMint) {")
        val cacheAt = launcher.indexOf("sourceCache[videoId] = CachedSource(resolved.source")
        val tokenlessAt = launcher.indexOf("not mounting it into the wall")

        assertTrue("the mount must be guarded by the flag", mountAt >= 0)
        assertTrue(
            "the cache write belongs to the mounted branch - a cached tokenless " +
                "source would be served back as a cache hit by the next resolve",
            cacheAt > mountAt && cacheAt < tokenlessAt
        )
        assertEquals(
            "and nothing else stores an InnerTube source",
            1,
            Regex("CachedSource\\(resolved.source").findAll(launcher).count()
        )
        assertTrue(
            "the fallback chain is what answers it, exactly as the extraction-null " +
                "path does",
            tokenlessAt in 0 until launcher.indexOf(".getPlayableUrl(videoId)")
        )
    }

    @Test
    fun `a hold is not a failure the next resolve has to wait out`() {
        val holdAt = launcher.indexOf("val heldTokenless = resolved != null")
        val holdReturnAt = launcher.indexOf("return@withLock Result.failure(TokenlessHoldException(")
        val failureWriteAt = launcher.indexOf("markFailed(videoId)")

        assertTrue("the hold has to be remembered as a shape", holdAt >= 0)
        assertTrue(
            "and it returns BEFORE the failure memory can be written: the 10-minute " +
                "throttle would turn one cold mint into ten minutes of misses",
            holdReturnAt > holdAt && failureWriteAt > holdReturnAt
        )
        assertTrue(
            "the write is what a real failure does, and it is guarded",
            flat(launcher).contains("if (recordFailure) { markFailed(videoId) }")
        )
        assertEquals(
            "no other failure write exists on this path",
            1,
            Regex("markFailed\\(videoId\\)").findAll(launcher).count()
        )
        assertTrue(
            "and the hold is TYPED: an owner that poisons a spent ladder must not " +
                "poison a video that merely ran out of mint",
            flat(launcher).contains(
                "return@withLock Result.failure(TokenlessHoldException(videoId, error))"
            ) &&
                flat(home).contains("if (error is TokenlessHoldException) {") &&
                flat(mobileHome).contains("if (it is TokenlessHoldException) {")
        )
    }

    // ── F2: the billboard pre-warms the page after this one ─────────────────

    @Test
    fun `the billboard pre-warms the next page as it settles`() {
        assertTrue(
            "the next page's trailer key is what is warmed",
            flat(mobileHome).contains("val nextPageTrailerKey = pages")
        )
        assertTrue(
            "on settle, not on a timer and not on the resolve",
            flat(mobileHome).contains(
                "LaunchedEffect(pagerState.settledPage, nextPageTrailerKey) {"
            )
        )
        assertTrue(
            "through the shared pre-warm, which is fire and forget",
            flat(mobileHome).contains("InnerTubeExtractor.prewarmPoTokens(key)")
        )
    }

    // ── F3: the owner halves ────────────────────────────────────────────────

    @Test
    fun `the billboard plans its deaths instead of re-signing the same class`() {
        val retry = between(
            mobileHome,
            "fun requestTrailerRetry(positionMs: Long, durationMs: Long): Boolean {",
            "return true"
        )

        assertTrue(
            "the shared plan owns the verdict",
            flat(retry).contains("val plan = heroTrailerRetryPlan(")
        )
        assertTrue(
            "a class that has died is excluded from the replacement",
            flat(retry).contains("excludedClients = trailerExcludedClients")
        )
        assertTrue(
            "the death line is written before anything that can fail",
            retry.indexOf("PoTokenTrace.recordTrailerDeath(") <
                retry.indexOf("trailerRetryRequest += 1")
        )
        assertTrue(
            "the replacement starts from the plan's resume point",
            flat(retry).contains("resumeAtMs = plan.resumeAtMs")
        )
        assertTrue(
            "and the resolve it feeds carries the exclusions",
            flat(mobileHome).contains("excludeClients = pending?.excludedClients ?: emptySet()")
        )
        assertTrue(
            "both failure shapes come through the plan",
            flat(mobileHome).contains("onTrailerFailed = { positionMs ->") &&
                flat(mobileHome).contains("onTrailerIdentityBlocked = { positionMs, durationMs ->") &&
                flat(mobileHome).contains("requestTrailerRetry(positionMs, durationMs)")
        )
        assertTrue(
            "a natural end is still a stop, not a retry",
            flat(mobileHome).contains("onTrailerEnded = { stopTrailer() }")
        )
    }

    @Test
    fun `the TV hero's ordinary failure plans like the mobile Detail hero`() {
        val failed = between(
            home,
            "onFailed = { positionMs ->",
            "onIdentityBlocked = { positionMs, durationMs ->"
        )

        assertTrue(
            "the plan owns the verdict",
            flat(failed).contains("requestTrailerRetry(positionMs, durationMs = 0L)")
        )
        assertTrue(
            "and it is the same plan the phone's Detail hero uses",
            flat(home).contains("val plan = heroTrailerRetryPlan(")
        )
        assertFalse(
            "the one-shot same-class retry - which re-fired the read that refused - " +
                "is gone",
            home.contains("trailerAttempt")
        )
        assertTrue(
            "the identity path keeps its own ladder",
            flat(home).contains("heroTrailerResumeDecision(")
        )
    }

    @Test
    fun `a stall is a death and a natural end is not`() {
        assertTrue(
            "the watchdog reports the stall as its own shape",
            flat(home).contains("onStalled(positionMs, durationMs)")
        )
        assertTrue(
            "and the TV hero plans it",
            flat(home).contains("onStalled = { positionMs, durationMs ->") &&
                flat(home).contains("requestTrailerRetry(positionMs, durationMs)")
        )
        assertTrue(
            "the default keeps every other surface exactly as it was",
            flat(home).contains(
                "onStalled: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> onEnded() }"
            )
        )
        assertTrue(
            "a natural completion still drops to the backdrop",
            flat(home).contains("if (playbackState == Player.STATE_ENDED) {") &&
                flat(home).contains("onEnded()")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    private fun source(path: String): String {
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
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
        const val INNER_TUBE = "${YOUTUBE}InnerTubeExtractor.kt"
        const val TRAILER_LAUNCHER = "${YOUTUBE}TrailerPlayerLauncher.kt"
        const val MOBILE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
