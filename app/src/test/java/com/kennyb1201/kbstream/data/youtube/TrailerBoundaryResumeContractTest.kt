package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trailer death at the first chunk boundary, and the bounded ladder that
 * answers it.
 *
 * The 2026-10-22 log is the whole case: the same signed URL served `bytes=0-0`,
 * `0-1M` and `0-<currentPos>` with 206 in the same minute every request starting
 * at a nonzero offset was refused 403. That is not an identity verdict - the
 * identity was demonstrably accepted - it is a property of the URL class the
 * resolve chose (`android_vr` signed it on both devices). Re-signing with the
 * same client therefore cannot help; only a source from a DIFFERENT client can,
 * which is what a resume asks for.
 *
 * The 2026-10-28 hero capture (`resume=none` at 36,840 ms of 140,849 ms, every
 * play) added the second half: one exclusion answers one URL class, so a
 * refusal that survives it needs the NEXT class excluded (a cumulative set, a
 * cap, then poison), and a requested resume must not be losable to a cancelled
 * effect - which is why the death line's `resume=` value now always says what
 * actually happened to the request.
 *
 * Everything here is wiring a unit test cannot drive without a TV and a refused
 * YouTube URL class, so it is read out of the source like the other
 * `*ContractTest`s in this package - plus the pure death-line formatting and the
 * ladder decision, which are driven directly.
 */
class TrailerBoundaryResumeContractTest {

    private val extractor = source(INNER_TUBE)
    private val launcher = source(LAUNCHER)
    private val player = source(NATIVE_PLAYER)
    private val home = source(HOME_SCREEN)
    private val trace = source(PO_TRACE)

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    // ── the extractor can exclude a SET of clients ─────────────────────────

    @Test
    fun `one resolve can exclude clients from the selection`() {
        assertTrue(
            "the resolve has to accept the exclusion as a set - the ladder grows it",
            extractor.contains(
                "suspend fun extractPlaybackSourceWithClient(\n" +
                    "        youtubeUrl: String,\n" +
                    "        excludeClients: Set<String> = emptySet()\n" +
                    "    ): ResolvedSource?"
            )
        )
        assertTrue(
            "the harvest still runs; only the SELECTION skips the clients",
            extractor.contains("val allowedHls = hlsUrls.filterNot { it.first in excludeClients }") &&
                extractor.contains(
                    "val allowedVideo = adaptiveVideo.filterNot { it.client in excludeClients }"
                ) &&
                extractor.contains(
                    "val allowedAudio = adaptiveAudio.filterNot { it.client in excludeClients }"
                ) &&
                extractor.contains(
                    "val allowedProgressive = progressive.filterNot { it.client in excludeClients }"
                )
        )
        assertTrue(
            "every pick reads the allowed pools, or one shape slips the exclusion",
            extractor.contains("pickBestHls(allowedHls)") &&
                extractor.contains(
                    "pickBest(allowedVideo, PREFERRED_SEPARATE_CLIENT, excludeClients)"
                ) &&
                extractor.contains("allowedAudio.sortedWith(") &&
                extractor.contains("allowedProgressive.sortedWith(")
        )
        assertTrue(
            "pickBest's own defence uses the same set",
            extractor.contains("val eligible = items.filterNot { it.client in excludeClients }")
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
                "if (excludeClients.isEmpty()) { sourceCache[videoId]?.let { cached ->"
            )
        )
        assertTrue(
            "and the resolve is handed the exclusion",
            flatLauncher.contains(
                "InnerTubeExtractor.extractPlaybackSourceWithClient( videoId, " +
                    "excludeClients = excludeClients )"
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

    @Test
    fun `the exclusion grows from the cache only, never from the trace fallback`() {
        // The trace answers whatever resolve ran LAST in the process - a
        // pre-warm for another title, a hero rotation - so excluding it would
        // skip a client that never signed this trailer and hide the one that
        // did. The cache answer is the only video-specific one.
        assertEquals(
            "one strict, cache-only accessor",
            1,
            Regex("fun cachedSigningClient\\(").findAll(launcher).count()
        )
        assertTrue(
            "it must not fall back to the trace",
            flat(launcher).contains(
                "fun cachedSigningClient(videoIdOrUrl: String): String? = " +
                    "sourceCache[normalizeId(videoIdOrUrl)]?.client"
            )
        )
        assertTrue(
            "the display answer keeps its fallback",
            flat(launcher).contains(
                "fun signingClient(videoIdOrUrl: String): String? = " +
                    "sourceCache[normalizeId(videoIdOrUrl)]?.client ?: " +
                    "PoTokenTrace.resolveClient()"
            )
        )
        assertTrue(
            "and the ladder's exclusion is fed from the strict one",
            flat(home).contains(
                "deadClient = TrailerPlayerLauncher .cachedSigningClient(key),"
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
            helper.contains(
                ".resolvePlayableUrl(videoId, excludeClients = setOfNotNull(deadClient))"
            )
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
            "no second re-resolve: the fullscreen player keeps its one resume",
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

    // ── the hero: a bounded ladder, with its budget of its own ─────────────

    @Test
    fun `the hero asks its owner for a resume and excludes everything refused so far`() {
        val handler = heroRefusalHandler()

        assertTrue(
            "the composable has to report whether the resume was taken",
            flat(home).contains(
                "onIdentityBlocked: (positionMs: Long, durationMs: Long) -> Boolean = " +
                    "{ _, _ -> false }"
            )
        )
        assertTrue(
            "the exclusion is cumulative: this death ADDS to what was already refused",
            handler.contains("excludedClients = trailerExcludedClients,") &&
                handler.contains("val excluded = decision.nextExcludedClients") &&
                handler.contains("trailerExcludedClients = excluded")
        )
        assertTrue(
            "the request is the owner's own record, cleared only by its outcome",
            handler.contains("trailerPendingResume = request") &&
                handler.contains("requestId = trailerResumeSeq")
        )
        assertTrue(
            "and it is a REQUEST: the counter restarts the resolve effect",
            handler.contains("trailerResumeSeq += 1")
        )
        assertTrue(
            "the exclusion the resolve is told to skip is the grown set",
            handler.contains("excludedClients = excluded,")
        )
        assertTrue(
            "the attempt is counted against the ladder's cap",
            handler.contains("trailerResumeAttempts += 1")
        )
        assertTrue(
            "and the owner records the request as PENDING - never a bare none",
            handler.contains("resume = \"pending\"")
        )
    }

    @Test
    fun `poison is the ladder running out, not a second death`() {
        val handler = heroRefusalHandler()

        assertTrue(
            "one pure decision owns the verdict, so its arithmetic is unit-tested",
            handler.contains("heroTrailerResumeDecision(") &&
                handler.contains("HeroTrailerResumeVerdict.POISON")
        )
        assertTrue(
            "the refusal counter and its >= 2 rule are gone",
            !flat(home).contains("trailerIdentityRefusals")
        )
        assertTrue(
            "the exhausted case marks the video and says so for the capture",
            handler.contains("TrailerPlayerLauncher .markIdentityBlocked(key)") &&
                handler.contains("identity ladder") &&
                handler.contains("excluded=") &&
                handler.contains("attempts=")
        )
        assertTrue(
            "a replacement that resolves to NOTHING poisons only if the ladder was walked",
            flat(home).contains("if (heroTrailerResolveFailurePoisons(pending.excludedClients)) {")
        )
    }

    @Test
    fun `the ordinary retry and the identity ladder do not spend each other's budget`() {
        val handler = heroRefusalHandler()

        assertFalse(
            "a refusal must not be gated by the ordinary retry's one attempt",
            handler.contains("trailerAttempt < 1")
        )
        assertFalse(
            "and must not spend it",
            handler.contains("trailerAttempt += 1")
        )
        val failed = flat(
            home.substringAfter("onFailed = { positionMs ->")
                .substringBefore("onIdentityBlocked = {")
        )
        assertTrue(
            "the ordinary retry keeps its own cap of one",
            failed.contains("if (trailerAttempt < 1) {") &&
                failed.contains("trailerAttempt += 1")
        )
        assertFalse(
            "and counts no identity attempt",
            failed.contains("trailerResumeAttempts")
        )
    }

    // ── the must-run guarantee ─────────────────────────────────────────────

    @Test
    fun `the resolve effect is keyed on the request, not on retry state`() {
        val key = "LaunchedEffect(trailerPlaying, trailerKey, trailerResumeSeq, resumeEpoch) {"
        assertTrue("the effect keys must include the request counter", home.contains(key))
        assertFalse(
            "a key the effect's own callers mutate is how a resume got cancelled",
            home.contains("LaunchedEffect(trailerPlaying, trailerKey, trailerAttempt")
        )
    }

    @Test
    fun `a requested resume is consumed only by its own outcome`() {
        val effect = heroEffect()

        assertTrue(
            "the run SNAPSHOTS the record instead of clearing it at the start",
            flat(effect).contains("val pending = trailerPendingResume")
        )
        val tryBody = effect
            .substringAfter("var outcomeRecorded = false")
            .substringBefore("} finally {")
        assertEquals(
            "exactly the two outcome callbacks clear it - success and failure",
            2,
            Regex("trailerPendingResume = null").findAll(tryBody).count()
        )
        assertTrue(
            "the success branch mounts at the record's position and writes ok",
            tryBody.contains("startPositionMs = resumeFrom") &&
                tryBody.contains("resume = \"client \"")
        )
        assertTrue(
            "the failure branch writes the completed negative outcome",
            tryBody.contains("resume = \"none: no other client\"")
        )
        assertFalse(
            "nothing between the blocked guard and the resolve consumes it",
            effect.substringAfter("keeping the backdrop")
                .substringBefore("var outcomeRecorded = false")
                .contains("trailerPendingResume = null")
        )
    }

    @Test
    fun `a cancelled run records cancelled and leaves the request owed`() {
        val effect = heroEffect()
        val finally = effect.substringAfter("} finally {")

        assertTrue(
            "cancellation is recorded, never silent",
            finally.contains("resume = \"cancelled\"")
        )
        assertTrue(
            "and only when no outcome was recorded",
            finally.contains("if (pending != null && !outcomeRecorded) {")
        )
        assertFalse(
            "the record survives the cancellation, for the next run",
            finally.contains("trailerPendingResume = null")
        )
        assertTrue(
            "a resumable run re-issues the same resolve from the same position",
            flat(effect).contains(
                "val resumeFrom = pending?.positionMs ?: trailerResumeMs"
            ) && flat(effect).contains(
                "excludeClients = pending?.excludedClients ?: emptySet()"
            )
        )
    }

    @Test
    fun `a resume owed to a blocked video is reported, not swallowed`() {
        val effect = flat(heroEffect())
        val blocked = effect.indexOf("TrailerPlayerLauncher.isIdentityBlocked(trailerKey)")
        val blockedWrite = effect.indexOf("resume = \"blocked\"")
        val resolve = effect.indexOf(".resolvePlayableUrl(")

        assertTrue("the blocked guard is the effect's head", blocked > 0)
        assertTrue(
            "a pending record plus a block is a bug state, and it says so",
            blockedWrite > blocked
        )
        assertTrue(
            "and it is resolved BEFORE the resolve starts",
            resolve > blockedWrite
        )
        assertTrue(
            "the plain blocked return still keeps the backdrop",
            effect.contains("is blocked by YouTube this") &&
                effect.contains("session; keeping the backdrop")
        )
    }

    // ── the death line: vocabulary and formatting ──────────────────────────

    @Test
    fun `the death line's resume vocabulary is closed`() {
        listOf(HOME_SCREEN, NATIVE_PLAYER, PO_TRACE).forEach { path ->
            assertFalse(
                "$path must not write the bare placeholder the 2026-10-28 capture " +
                    "could not tell from \"no resume was asked for\"",
                source(path).contains("resume = \"none\"")
            )
        }
        assertTrue(
            "the request site says pending, on both surfaces",
            flat(home).contains("resume = \"pending\"") &&
                flat(player).contains("resume = \"pending\"")
        )
        assertTrue(
            "cancellation is a value, on both surfaces",
            flat(home).contains("resume = \"cancelled\"") &&
                flat(player).contains("resume = \"cancelled\"")
        )
        assertTrue("the bug-state guard has its own value", flat(home).contains("resume = \"blocked\""))
        assertTrue(
            "a replacement's unclassified death is named as the resume's outcome",
            flat(home).contains("resume = \"replacement ended\"") &&
                flat(home).contains("resume = \"replacement failed\"")
        )
        assertTrue(
            "and the completed values are unchanged",
            flat(home).contains("resume = \"client \"") &&
                flat(player).contains("resume = \"none: no other client\"") &&
                flat(player).contains("""${'$'}{deadClient ?: "unknown"} died""")
        )
        assertTrue(
            "the vocabulary is documented where the line is written",
            trace.contains("`pending` - a replacement resolve was requested") &&
                trace.contains("`cancelled` - the requested resolve was cancelled") &&
                trace.contains("`blocked` - the resolve early-returned") &&
                trace.contains("`replacement ended` / `replacement failed`")
        )
    }

    @Test
    fun `a replacement that dies unclassified re-writes the death line`() {
        val ended = flat(
            home.substringAfter("onEnded = {")
                .substringBefore("onFailed = {")
        )
        assertTrue(
            "the end path names the replacement, with the resume it came from",
            ended.contains("if (trailerReplacementMounted && resume != null) {") &&
                ended.contains("trailerLastResume") &&
                ended.contains("resume = \"replacement ended\"")
        )
        val failed = flat(
            home.substringAfter("onFailed = { positionMs ->")
                .substringBefore("onIdentityBlocked = {")
        )
        assertTrue(
            "and the error path uses the position the player reported",
            failed.contains("resume = \"replacement failed\"") &&
                failed.contains("positionMs = positionMs,")
        )
    }

    // ── the death line: direct formatting ──────────────────────────────────

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

        assertEquals(
            "the report's one line for this failure has to name all five facts",
            "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=53510/136166 resume=client tv ok",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `every new vocabulary value renders verbatim`() {
        val cases = listOf(
            "pending" to
                "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=36840/140849 resume=pending",
            "cancelled" to
                "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=36840/140849 resume=cancelled",
            "blocked" to
                "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=36840/140849 resume=blocked",
            "replacement ended" to
                "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=36840/140849 resume=replacement ended",
            "replacement failed" to
                "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive " +
                "at=41200/140849 resume=replacement failed"
        )
        cases.forEach { (resume, expected) ->
            val position = if (resume == "replacement failed") 41_200L else 36_840L
            PoTokenTrace.recordTrailerDeath(
                videoId = VIDEO_ID,
                client = "android_vr",
                kind = "adaptive",
                positionMs = position,
                durationMs = 140_849L,
                resume = resume
            )
            assertEquals(
                "the capture must read the value exactly as the spec pins it",
                expected,
                PoTokenTrace.lines().last()
            )
        }
    }

    @Test
    fun `an unknown client, kind or length is said, not invented`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = null,
            kind = null,
            positionMs = 0L,
            durationMs = -1L,
            resume = "cancelled"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=unknown kind=unknown at=0/? resume=cancelled",
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
            flat(player).contains("resume = \"client \${deadClient ?: ") &&
                flat(player).contains("} died\"")
        )
        assertTrue(
            "the hero records the success too - a capture must show the fix working",
            flat(home).contains("resume = \"client \"")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** The whole resolve effect: from its keys to the line that follows it. */
    private fun heroEffect(): String = home
        .substringAfter(
            "LaunchedEffect(trailerPlaying, trailerKey, trailerResumeSeq, resumeEpoch) {"
        )
        .substringBefore("val year = if (preview.type == \"movie\")")

    /** The owner's refusal handler, from its callback to the next one. */
    private fun heroRefusalHandler(): String = flat(
        home.substringAfter("onIdentityBlocked = { positionMs, durationMs ->")
            .substringBefore("Crossfade(")
    )

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
        const val PO_TRACE = "${YOUTUBE}PoTokenTrace.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val VIDEO_ID = "7YpuMymmiJ8"
    }
}
