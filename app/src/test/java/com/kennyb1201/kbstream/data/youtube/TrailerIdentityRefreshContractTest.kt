package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the identity-blocked classification is ACTED on, read out of the source.
 *
 * The bug this pins (Kenny's 2026-10-18 11:17 log, Onn 4K Plus, hero trailer
 * `gc0t8AfMP6k`): the trailer 403'd at 36726ms, [YoutubeChunkedDataSourceFactory]
 * cycled every mode inside the player, the one-shot retry re-resolved through
 * [TrailerPlayerLauncher.invalidate] and resumed from 36726ms - and the FRESH
 * signed URL 403'd on its first request too. The retry worked; the identity it
 * re-signed with was the one YouTube had just refused, cached for up to
 * [InnerTubeExtractor]'s 3 hours.
 *
 * Each assertion below is a half of the fix that a wiring mistake could undo
 * silently: the classification has to run BEFORE the retry, only for a real
 * 403, and the second refusal has to stop the re-arm rather than buy a resolve
 * per foreground return. None of it can be driven without a TV and a refused
 * YouTube identity, so it is read from the source like the other
 * `*ContractTest`s here.
 */
class TrailerIdentityRefreshContractTest {

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

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

    private val home: String by lazy { readSource(HOME_SCREEN) }
    private val launcher: String by lazy { readSource(TRAILER_LAUNCHER) }
    private val player: String by lazy { readSource(NATIVE_PLAYER) }
    private val detail: String by lazy { readSource(DETAIL_SCREEN) }
    private val extractor: String by lazy { readSource(INNER_TUBE) }

    /** The hero player's error handler, from its first statement to the listener's end. */
    private fun heroErrorHandler(): String {
        val start = home.indexOf("override fun onPlayerError(error: PlaybackException) {")
        assertTrue("the hero error handler must exist", start >= 0)
        val end = home.indexOf("exoPlayer.addListener(listener)", start)
        assertTrue("the hero handler must end before the listener is added", end > start)
        return home.substring(start, end)
    }

    // ------------------------------------------------------------- the hero --

    @Test
    fun `the hero invalidates the watch config on the identity-blocked path`() {
        val handler = flat(heroErrorHandler())

        assertTrue(
            "the verdict must come from the shared classifier",
            handler.contains("val identityBlocked = error.isYouTubeIdentityBlocked()")
        )
        assertTrue(
            "a player-visible googlevideo 403 must invalidate the cached identity",
            handler.contains("if (identityBlocked) { InnerTubeExtractor.invalidateConfig()")
        )
    }

    @Test
    fun `the invalidation happens before the retry is handed over`() {
        val handler = flat(heroErrorHandler())
        val invalidateAt = handler.indexOf("InnerTubeExtractor.invalidateConfig()")
        val failedAt = handler.indexOf("onFailed(positionMs)")

        assertTrue("the invalidation must be there", invalidateAt >= 0)
        assertTrue("the retry hook must be there", failedAt >= 0)
        assertTrue(
            "the retry resolves through resolvePlayableUrl, so the fresh watch config has to be " +
                "in place BEFORE it - otherwise the retry re-signs with the refused identity",
            invalidateAt < failedAt
        )
    }

    @Test
    fun `nothing else in the handler invalidates the watch config`() {
        val handler = flat(heroErrorHandler())

        // Exactly ONE call, and it sits inside the classification: a timeout, a
        // 5xx or an addon host's own 403 keeps the cached identity and the
        // plain retry, so an unguarded call at the top of the handler would be
        // the regression this pins.
        assertEquals(
            "one invalidation, on the 403 path only",
            1,
            handler.split("InnerTubeExtractor.invalidateConfig()").size - 1
        )
    }

    @Test
    fun `the second 403 marks the video blocked for the session`() {
        val handler = flat(home)

        // A refusal no longer spends the retry (a refused identity cannot be
        // recovered by re-signing - see TrailerGracefulFallbackContractTest),
        // so the evidence that the fresh identity is refused too is the SECOND
        // refusal of the same video: the first one already invalidated the
        // watch config, which makes the resolve between them a fresh visitor.
        assertTrue(
            "refusals of the same trailer have to be counted",
            handler.contains("trailerIdentityRefusals += 1")
        )
        assertTrue(
            "and the second one is what marks the video blocked",
            handler.contains(
                "if (trailerIdentityRefusals >= 2) { trailerKey?.let { " +
                    "TrailerPlayerLauncher.markIdentityBlocked(it) }"
            )
        )
    }

    @Test
    fun `the hero checks the block before it invalidates or resolves`() {
        val blockedAt = home.indexOf("TrailerPlayerLauncher.isIdentityBlocked(trailerKey)")
        val invalidateAt = home.indexOf("TrailerPlayerLauncher.invalidate(trailerKey)")
        val resolveAt = home.indexOf(".resolvePlayableUrl(trailerKey)", blockedAt)

        assertTrue("the resolve effect must consult the blocked set", blockedAt >= 0)
        assertTrue("the retry still busts the cached URL", invalidateAt > blockedAt)
        assertTrue(
            "and the check must guard the resolve itself, not follow it",
            resolveAt > blockedAt
        )
    }

    // --------------------------------------------------------- the launcher --

    @Test
    fun `a blocked video is not resolved at all`() {
        val source = flat(launcher)
        val blockedAt = source.indexOf("if (identityBlocked.contains(videoId)) {")
        val fetchAt = source.indexOf("InnerTubeExtractor.extractPlaybackSource(videoId)")

        assertTrue("the blocked check must exist", blockedAt >= 0)
        assertTrue("the resolve must still be there", fetchAt > 0)
        assertTrue(
            "the check has to come first - skipping it means a refused video burns a " +
                "watch-page fetch and a full resolve every time it cycles back around",
            blockedAt < fetchAt
        )
        assertTrue(
            "and it must skip the whole chain",
            source.substring(blockedAt, fetchAt).contains("return@withLock Result.failure(")
        )
    }

    @Test
    fun `a manual invalidate clears the block`() {
        val source = flat(launcher)
        val invalidateAt = source.indexOf("fun invalidate(videoId: String) {")

        assertTrue("invalidate must exist", invalidateAt >= 0)
        assertTrue(
            "an explicit invalidation is a caller saying 'try again'",
            source.substring(invalidateAt, invalidateAt + 200)
                .contains("identityBlocked.remove(normalizeId(videoId))")
        )
    }

    // -------------------------------------------- the detail-screen trailers --

    @Test
    fun `a trailer session is identifiable in the player`() {
        assertTrue(
            "the detail screen must tag the launch as a trailer",
            flat(detail).contains(
                "putExtra(TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID, videoId)"
            )
        )
        assertTrue(
            "and the player must read that tag",
            flat(player).contains(
                "trailerVideoId = intent .getStringExtra(TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID)"
            )
        )
    }

    /**
     * The trailer branch of the player's error handler: the call site, and the
     * helper it runs (which holds the guard, so the handler's own opening stays
     * as small as the tail-completion contract needs it).
     */
    private fun trailerRefusalBranch(): String {
        val start = player.indexOf("override fun onPlayerError(error: PlaybackException) {")
        assertTrue("the native error handler must exist", start >= 0)
        val end = player.indexOf("lastPlaybackError = error", start)
        assertTrue("the error handler must reach its failure bookkeeping", end > start)
        val handler = flat(player.substring(start, end))

        val helperAt = player.indexOf(
            "private fun refreshIdentityOnTrailerRefusal(error: PlaybackException): Boolean {"
        )
        assertTrue(
            "the trailer-identity branch must live in its own helper",
            helperAt >= 0
        )
        val helperEnd = player.indexOf("private fun fileErrorTailCompletion()", helperAt)
        assertTrue(helperEnd > helperAt)

        return handler + " || " + flat(player.substring(helperAt, helperEnd))
    }

    @Test
    fun `a trailer session invalidates the watch config at the error entry`() {
        val branch = trailerRefusalBranch()
        val guardAt = branch.indexOf(
            "if (trailerVideoId == null || !error.isYouTubeIdentityBlocked()) return"
        )

        assertTrue("the classification must gate the branch", guardAt >= 0)
        assertTrue(
            "and invalidate there, so the NEXT trailer resolve gets a fresh visitor",
            branch.substring(guardAt).contains("InnerTubeExtractor.invalidateConfig()")
        )
        assertTrue(
            "the handler must run it before the recovery ladder touches the stream",
            branch.indexOf("refreshIdentityOnTrailerRefusal(error)") in 0 until
                branch.indexOf("PlaybackRecoveryRules.isLinkFailure(error)")
        )
    }

    // -------------------------------------------------------- what is fixed --

    @Test
    fun `the pieces this change deliberately leaves alone are unchanged`() {
        // The 3h watch-config TTL, the one-shot retry cap, and the mode ladder
        // are all deliberate non-goals: the identity is invalidated, but the
        // retry budget and the fallback chain stay exactly as they were.
        assertTrue(
            "the watch-config TTL must stay at 3 hours",
            flat(extractor).contains(
                "private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours"
            )
        )
        assertTrue(
            "the one-shot retry cap must stay",
            flat(home).contains("if (trailerAttempt < 1) {")
        )
    }

    @Test
    fun `the invalidation is visible on a device`() {
        // The field check for this change reads "Watch config invalidated" out
        // of a log taken on a Fire OS box, which suppresses DEBUG. A debug-level
        // line would make the field check unverifiable even when the fix works.
        assertTrue(
            "the invalidation must log at warning level",
            flat(extractor).contains("Log.w(TAG, \"Watch config invalidated\")")
        )
    }

    private companion object {
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val TRAILER_LAUNCHER = "${YOUTUBE}TrailerPlayerLauncher.kt"
        const val INNER_TUBE = "${YOUTUBE}InnerTubeExtractor.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val DETAIL_SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
