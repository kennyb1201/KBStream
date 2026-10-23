package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trailer death at the first chunk boundary, and the one resume that answers
 * it.
 *
 * The 2026-10-22 log is the whole case: the same signed URL served `bytes=0-0`,
 * `0-1M` and `0-<currentPos>` with 206 in the same minute every request starting
 * at a nonzero offset was refused 403. That is not an identity verdict - the
 * identity was demonstrably accepted - it is a property of the URL class the
 * resolve chose (`android_vr` signed it on both devices). Re-signing with the
 * same client therefore cannot help; only a source from a DIFFERENT client can,
 * and that is what a resume now asks for.
 *
 * Everything here is wiring a unit test cannot drive without a TV and a refused
 * YouTube URL class, so it is read out of the source like the other
 * `*ContractTest`s in this package - plus the pure death-line formatting, which
 * is driven directly.
 */
class TrailerBoundaryResumeContractTest {

    private val extractor = source(INNER_TUBE)
    private val launcher = source(LAUNCHER)
    private val player = source(NATIVE_PLAYER)
    private val home = source(HOME_SCREEN)

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    // ── the extractor can exclude one client ───────────────────────────────

    @Test
    fun `one resolve can exclude a client from the selection`() {
        assertTrue(
            "the resolve has to accept the exclusion",
            extractor.contains(
                "suspend fun extractPlaybackSourceWithClient(\n" +
                    "        youtubeUrl: String,\n" +
                    "        excludeClient: String? = null\n" +
                    "    ): ResolvedSource?"
            )
        )
        assertTrue(
            "the harvest still runs; only the SELECTION skips the client",
            extractor.contains("val allowedHls = hlsUrls.filter { it.first != excludeClient }") &&
                extractor.contains(
                    "val allowedVideo = adaptiveVideo.filter { it.client != excludeClient }"
                ) &&
                extractor.contains(
                    "val allowedAudio = adaptiveAudio.filter { it.client != excludeClient }"
                ) &&
                extractor.contains(
                    "val allowedProgressive = progressive.filter { it.client != excludeClient }"
                )
        )
        assertTrue(
            "every pick reads the allowed pools, or one shape slips the exclusion",
            extractor.contains("pickBestHls(allowedHls)") &&
                extractor.contains(
                    "pickBest(allowedVideo, PREFERRED_SEPARATE_CLIENT, excludeClient)"
                ) &&
                extractor.contains("allowedAudio.sortedWith(") &&
                extractor.contains("allowedProgressive.sortedWith(")
        )
        assertTrue(
            "the single-argument entry point still behaves byte-identically",
            extractor.contains(
                "suspend fun extractPlaybackSource(youtubeUrl: String): PlayableSource? =\n" +
                    "        extractPlaybackSourceWithClient(youtubeUrl)?.source"
            )
        )
    }

    @Test
    fun `the winning source names its client and its shape`() {
        assertTrue(
            "a resume cannot exclude a client it was not told about",
            extractor.contains("internal data class ResolvedSource(") &&
                extractor.contains("val client: String?")
        )
        assertTrue(
            "each of the three shapes names itself as it is recorded",
            extractor.contains(
                ".withResolveTokens(videoId, tokens, bestHls.first, KIND_HLS)"
            ) && extractor.contains(
                ".withResolveTokens(videoId, tokens, bestVideo?.client, KIND_ADAPTIVE)"
            ) && flat(extractor).contains(
                "bestProgressive.client, KIND_PROGRESSIVE"
            )
        )
        assertTrue(
            "the trace's resolve line carries the shape, for the death line that follows",
            extractor.contains("kind = kind")
        )
    }

    // ── the launcher serves the exclusion ──────────────────────────────────

    @Test
    fun `an exclusion resolve never answers from the cache`() {
        val flatLauncher = flat(launcher)

        assertTrue(
            "the cached entry may be exactly the class being excluded",
            flatLauncher.contains(
                "if (excludeClient == null) { sourceCache[videoId]?.let { cached ->"
            )
        )
        assertTrue(
            "and the resolve is handed the exclusion",
            flatLauncher.contains(
                "InnerTubeExtractor.extractPlaybackSourceWithClient( videoId, " +
                    "excludeClient = excludeClient )"
            )
        )
        assertTrue(
            "the client is cached beside the source, so a death can name it",
            flatLauncher.contains("CachedSource(resolved.source, resolved.client)")
        )
        assertTrue(
            "with the trace's last resolve as the fallback for an evicted entry",
            flatLauncher.contains(
                "sourceCache[normalizeId(videoIdOrUrl)]?.client ?: " +
                    "PoTokenTrace.resolveClient()"
            )
        )
    }

    // ── the player: resume first, exit on the second death ─────────────────

    @Test
    fun `a boundary death is resumed on a different client before the session ends`() {
        val helper = player.substringAfter(
            "private fun refreshIdentityOnTrailerRefusal(error: PlaybackException): Boolean {"
        )

        assertTrue(
            "the death point is read before anything rebuilds",
            helper.indexOf("val positionMs = player?.currentPosition") in 0 until
                helper.indexOf("applyTrailerReplacement(")
        )
        assertTrue(
            "the session spends exactly one resume",
            helper.contains("trailerRefusalResumed = true") &&
                helper.contains("if (trailerRefusalResumed) {")
        )
        assertTrue(
            "the replacement is resolved EXCLUDING the client that signed the dead URL",
            helper.contains(".resolvePlayableUrl(videoId, excludeClient = deadClient)")
        )
        assertTrue(
            "and it continues the session at the death position",
            helper.contains("applyTrailerReplacement(replacement, positionMs)")
        )
        // The second death is today's path, and it must be the WHOLE of it: the
        // already-resumed branch marks, stamps and finishes without asking for
        // another resolve.
        val secondDeath = helper
            .substringAfter("if (trailerRefusalResumed) {")
            .substringBefore("trailerRefusalResumed = true")
        assertTrue(
            "the video is marked refused for the session",
            secondDeath.contains("TrailerPlayerLauncher.markIdentityBlocked(videoId)")
        )
        assertTrue(
            "and the session ends right there",
            secondDeath.contains("finish()")
        )
        assertFalse(
            "no second re-resolve: walking clients on one clip is what the spec forbids",
            secondDeath.contains("resolvePlayableUrl(")
        )
    }

    @Test
    fun `the replacement rebuild carries the playhead`() {
        val apply = player.substringAfter("private fun applyTrailerReplacement(")

        assertTrue(
            "the link, its audio track and its UA are what a swap replaces",
            apply.contains("currentUrl = when (source)") &&
                apply.contains("currentAudioUrl = (source as? PlayableSource.Adaptive)?.audioUrl") &&
                apply.contains("mapOf(\"User-Agent\" to ua)")
        )
        assertTrue(
            "and the position rides across, so it reads as a rebuffer not a restart",
            apply.contains("carryPositionMs = positionMs.coerceAtLeast(0L)")
        )
        assertTrue(
            "the rebuild is the trusted source-swap path",
            apply.contains("recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)")
        )
    }

    // ── the hero: the same single resume, on its own budget ────────────────

    @Test
    fun `the hero asks its owner for the one resume and excludes the dead client`() {
        val flatHome = flat(home)

        assertTrue(
            "the composable has to report whether the resume was taken",
            flatHome.contains(
                "onIdentityBlocked: (positionMs: Long, durationMs: Long) -> Boolean = " +
                    "{ _, _ -> false }"
            )
        )
        assertTrue(
            "the owner spends the same one-attempt budget the ordinary retry uses",
            flatHome.contains("} else if (key != null && trailerAttempt < 1) {")
        )
        assertTrue(
            "and hands the exclusion to the resolve it triggers",
            flatHome.contains("trailerExcludeClient = deadClient") &&
                flatHome.contains("trailerResumeMs = positionMs")
        )
        assertTrue(
            "the exclusion is consumed with that resolve, never inherited",
            fullEffect().contains("val excludeClient = trailerExcludeClient") &&
                fullEffect().contains("trailerExcludeClient = null")
        )
        assertTrue(
            "and the resolve it triggers is handed the exclusion",
            flatHome.contains(
                "resolvePlayableUrl(trailerKey, excludeClient = excludeClient)"
            )
        )
        assertTrue(
            "and the owner records the refusal it took, with the death point",
            flatHome.contains("resume = \"none\"") &&
                flatHome.contains("trailerDeathDurationMs = durationMs")
        )
    }

    @Test
    fun `the refused hero trailer never re-signs the same client`() {
        val handler = flat(
            home.substringAfter("override fun onPlayerError(error: PlaybackException) {")
                .substringBefore("exoPlayer.addListener(listener)")
        )

        assertTrue(
            "the plain retry stays off for a 403 - a re-signed URL cannot resume",
            handler.contains("isIdentityBlocked = identityBlocked")
        )
        assertTrue(
            "and the exclusion resume is the branch that runs instead",
            handler.contains("resumeRequested = onIdentityBlocked(positionMs, durationMs)")
        )
        assertTrue(
            "the log tells the two apart, so a capture is unambiguous",
            handler.contains("re-resolving once with another client") &&
                handler.contains("skipping the retry and leaving the backdrop up")
        )
    }

    // ── the death line ─────────────────────────────────────────────────────

    @Before
    fun clearTrace() {
        PoTokenTrace.clear()
    }

    @Test
    fun `the death line carries the video, client, kind, position and outcome`() {
        PoTokenTrace.recordResolve(
            videoId = VIDEO_ID,
            playerToken = true,
            pot = true,
            client = "android_vr",
            kind = "adaptive"
        )
        assertEquals("android_vr", PoTokenTrace.resolveClient())
        assertEquals("adaptive", PoTokenTrace.resolveKind())

        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = PoTokenTrace.resolveClient(),
            kind = PoTokenTrace.resolveKind(),
            positionMs = 53_510L,
            durationMs = 136_166L,
            resume = "client tv ok"
        )

        val line = PoTokenTrace.lines().last()
        assertEquals(
            "the report's one line for this failure has to name all five facts",
            "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=53510/136166 resume=client tv ok",
            line
        )
    }

    @Test
    fun `an unknown client, kind or length is said, not invented`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = null,
            kind = null,
            positionMs = 0L,
            durationMs = -1L,
            resume = "none"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=unknown kind=unknown at=0/? resume=none",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `no death means no line and an ordinary capture is unchanged`() {
        PoTokenTrace.recordResolve(VIDEO_ID, playerToken = true, pot = true, client = "android")
        PoTokenTrace.recordInvalidated()

        assertTrue(
            "a session no trailer died in must not gain a death line",
            PoTokenTrace.lines().none { it.startsWith("trailer.death:") }
        )
        assertEquals(PoTokenTrace.CLEARED_EVENT, PoTokenTrace.lines().last())
    }

    @Test
    fun `the death is recorded on both surfaces and on both outcomes`() {
        listOf(NATIVE_PLAYER, HOME_SCREEN).forEach { path ->
            assertTrue("$path must record the death", source(path).contains("recordTrailerDeath("))
        }
        assertTrue(
            "the player names a failed replacement rather than pretending it resumed",
            flat(player).contains("resume = \"none: no other client\"")
        )
        assertTrue(
            "and a second death says the replacement died",
            flat(player).contains("resume = \"client \${deadClient ?: \"unknown\"} died\"")
        )
        assertTrue(
            "the hero records the success too - a capture must show the fix working",
            flat(home).contains("resume = \"client \"")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun fullEffect(): String {
        val start = home.indexOf(
            "LaunchedEffect(trailerPlaying, trailerKey, trailerAttempt, resumeEpoch) {"
        )
        assertTrue("the resolve effect must exist", start >= 0)
        val end = home.indexOf("if (trailerPlaying && !trailerKey.isNullOrBlank()) {", start)
        assertTrue(end > start)
        return home.substring(start, end)
    }

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
        const val LAUNCHER = "${YOUTUBE}TrailerPlayerLauncher.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val VIDEO_ID = "7YpuMymmiJ8"
    }
}
