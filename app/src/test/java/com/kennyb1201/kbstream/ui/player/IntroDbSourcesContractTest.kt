package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkipDB and AniSkip in the skip-source chain (spec 2026-11-13).
 *
 * The chain's decisions are unit-tested in [IntroDbChainRulesTest]; this covers
 * the parts a JVM test cannot run - the OkHttp readers, the order the sources are
 * asked in, and the wiring into the three engines - plus the one hard rule the
 * spec makes about AniSkip: it is button-only.
 *
 * Read-only by design: no submission endpoints, no API keys, and nothing sent
 * anywhere but a query.
 */
class IntroDbSourcesContractTest {

    private val helpers by lazy { source(HELPERS) }
    private val chainRules by lazy { source(CHAIN_RULES) }
    private val model by lazy { source(MODEL) }
    private val autoSkip by lazy { source(AUTO_SKIP) }

    // ── F1: SkipDB ───────────────────────────────────────────────────────

    @Test
    fun `SkipDB's outro is the credits block and its segments are read as stamps`() {
        val reader = helpers.substringAfter("internal fun readSkipDbSegments(")
            .substringBefore("internal suspend fun fetchAniSkipStamps(")

        assertTrue(
            "the four documented keys, and nothing per-type",
            reader.contains("""one("intro", IntroDbMarkerType.Intro)""") &&
                reader.contains("""one("recap", IntroDbMarkerType.Recap)""") &&
                reader.contains("""one("outro", IntroDbMarkerType.Credits)""") &&
                reader.contains("""one("preview", IntroDbMarkerType.Preview)""")
        )
        assertTrue(
            "a null segment is the normal answer, not an error",
            reader.contains("""root.optJSONObject("segments") ?: return emptyList()""")
        )
        assertTrue(
            "and every row carries SkipDB as its source",
            reader.contains("readIntroDbStamp(type, IntroDbSource.SkipDb)")
        )
        assertTrue(
            "the existing row reader does the parsing, so start_ms/end_ms/confidence " +
                "behave exactly as they do for the other two sources",
            helpers.contains("internal fun JSONObject.readIntroDbStamp(") &&
                helpers.contains("""readMillisField(this, "end_ms")""")
        )
    }

    @Test
    fun `SkipDB is queried once per lookup, only for an IMDb id, and never with a key`() {
        val fetcher = helpers.substringAfter("internal suspend fun fetchSkipDbStamps(")
            .substringBefore("internal fun readSkipDbSegments(")

        assertTrue(
            "one call answers every type",
            fetcher.contains("fetchIntroDbJson(skipDbSegmentsUrl(parentId, season, episode, durationMs))")
        )
        assertTrue(
            "a non-IMDb id skips the leg entirely instead of asking a service that cannot answer",
            fetcher.contains("if (!isSkipDbImdbId(parentId)) return emptyList()")
        )
        assertTrue(
            "no data on any failure - no retry, no throw",
            fetcher.contains("?: return emptyList()")
        )
        assertTrue(
            "the URL builder pins adjust=conservative and omits type",
            chainRules.contains("""add("adjust=conservative")""") &&
                !chainRules.substringAfter("internal fun skipDbSegmentsUrl(")
                    .substringBefore("internal fun aniSkipTimesUrl(")
                    .contains("""add("type=""")
        )
    }

    // ── F2: AniSkip ──────────────────────────────────────────────────────

    @Test
    fun `AniSkip's seconds become milliseconds, once`() {
        val reader = helpers.substringAfter("private fun readAniSkipResult(")
            .substringBefore("internal suspend fun resolveMalId(")

        assertTrue(
            "the one unit change in the chain is called out where it happens",
            reader.contains("// Seconds in, milliseconds out") &&
                reader.contains("""readSecondsField(interval, "startTime")""") &&
                reader.contains("""readSecondsField(interval, "endTime")""")
        )
        assertTrue(
            "and readSecondsField is what multiplies",
            helpers.contains("is Number -> (raw.toDouble() * 1_000.0).toLong()")
        )
        assertTrue(
            "the three usable types, with the mixed variants, and nothing else",
            reader.contains(""""op", "mixed-op" -> IntroDbMarkerType.Intro""") &&
                reader.contains(""""ed", "mixed-ed" -> IntroDbMarkerType.Credits""") &&
                reader.contains(""""recap" -> IntroDbMarkerType.Recap""") &&
                reader.contains("else -> return null")
        )
        assertTrue(
            "no confidence is invented: AniSkip reports none",
            reader.contains("confidence = null") &&
                reader.contains("source = IntroDbSource.AniSkip")
        )
    }

    @Test
    fun `the MAL id is only accepted when the title matches`() {
        val reader = helpers.substringAfter("internal fun readJikanMalId(")
            .substringBefore("/** A remembered Jikan answer")

        assertTrue(reader.contains("""root.optJSONArray("data")?.optJSONObject(0)"""))
        assertTrue(
            "every name the result carries is checked - romanized, English, and titles[]",
            reader.contains("""first.optString("title")""") &&
                reader.contains("""first.optString("title_english")""") &&
                reader.contains("""titles.optJSONObject(index)?.optString("title")""")
        )
        assertTrue(
            "through the shared title gate",
            reader.contains("animeTitleMatches(")
        )
    }

    @Test
    fun `the resolved id is cached per show, and a failed call is not cached`() {
        val resolver = helpers.substringAfter("internal suspend fun resolveMalId(")
            .substringBefore("/**\n * The first Jikan result's MAL id")

        assertTrue(
            "a remembered answer - including 'not this anime' - is reused",
            resolver.contains("malResolutions[normalized]?.let { remembered -> return remembered.malId }")
        )
        assertTrue(
            "a failed fetch returns without storing anything",
            resolver.contains("""fetchIntroDbJson(jikanSearchUrl(showTitle ?: "")) ?: return null""")
        )
        assertTrue(
            "and only a real answer is remembered",
            resolver.contains("malResolutions[normalized] = MalResolution(malId)")
        )
        assertTrue(
            "keyed by the normalized title, so case and spacing cannot split it",
            helpers.contains("""private val malResolutions = ConcurrentHashMap<String, MalResolution>()""") &&
                resolver.contains("normalizeAnimeTitle(showTitle)")
        )
    }

    @Test
    fun `AniSkip is never asked about a movie and never without a title`() {
        val fetcher = helpers.substringAfter("internal suspend fun fetchAniSkipStamps(")
            .substringBefore("internal fun readAniSkipResults(")

        assertTrue(
            "an episode is required: the endpoint is episode-addressed",
            fetcher.contains("val episodeNumber = episode ?: return emptyList()")
        )
        assertTrue(
            "and the MAL resolution needs a title",
            fetcher.contains("val malId = resolveMalId(showTitle) ?: return emptyList()")
        )
        val chain = helpers.substringAfter("internal suspend fun fetchIntroDbStamps(")
            .substringBefore("internal suspend fun fetchSkipDbStamps(")
        assertTrue(
            "the chain only reaches it for TV episodes",
            chain.contains("if (hasEpisode && episodeNumber != null) {")
        )
        assertFalse(
            "a movie is never given an invented episode number",
            chain.contains("episode ?: 1")
        )
    }

    // ── The chain itself ─────────────────────────────────────────────────

    @Test
    fun `the four sources are asked in the documented order, with early exit`() {
        val chain = helpers.substringAfter("internal suspend fun fetchIntroDbStamps(")
            .substringBefore("internal suspend fun fetchSkipDbStamps(")

        val introDbAppAt = chain.indexOf("https://api.introdb.app/segments?")
        val theIntroDbAt = chain.indexOf("INTRO_DB_MEDIA_URL")
        val skipDbAt = chain.indexOf("fetchSkipDbStamps(")
        val aniSkipAt = chain.indexOf("fetchAniSkipStamps(")

        assertTrue(
            "IntroDB.app first, TheIntroDB second, SkipDB third, AniSkip last",
            introDbAppAt in 0 until theIntroDbAt &&
                theIntroDbAt in 0 until skipDbAt &&
                skipDbAt in 0 until aniSkipAt
        )
        assertEquals(
            "each answered leg checks whether anything is still missing before " +
                "going on - the early exit, once per source",
            3,
            chain.windowed("if (!introDbChainStillOpen(merged)) return@withContext merged".length)
                .count { it == "if (!introDbChainStillOpen(merged)) return@withContext merged" }
        )
        assertTrue(
            "and a leg that answers is merged, never returned outright - a first " +
                "source with only an intro no longer ends the lookup",
            chain.contains("merged = mergeIntroDbStamps(") &&
                !chain.contains("if (markers.isNotEmpty()) return@withContext markers")
        )
    }

    @Test
    fun `every request keeps the chain's User-Agent and shares its client`() {
        val request = helpers.substringAfter("internal fun introDbRequest(")
            .substringBefore("internal fun fetchIntroDbJson(")
        assertTrue(
            "one builder, so no source can forget the header",
            request.contains(""".header("User-Agent", INTRO_DB_USER_AGENT)""")
        )
        assertTrue(
            "and one client, with the 5s per-request ceiling that bounds a slow source",
            helpers.contains("internal val introDbHttpClient") &&
                helpers.contains("callTimeout(5, TimeUnit.SECONDS)")
        )
        // Everything goes through the same two: no new OkHttp stack, no retry.
        val newCalls = listOf(
            "fetchSkipDbStamps",
            "fetchAniSkipStamps",
            "resolveMalId"
        ).all { name ->
            helpers.substringAfter("internal suspend fun $name(").contains("fetchIntroDbJson(") ||
                helpers.substringAfter("internal suspend fun $name(").contains("?: return null")
        }
        assertTrue("each new leg reads through fetchIntroDbJson", newCalls)
        assertFalse("no retry/backoff was added", helpers.contains("retryOnConnectionFailure(true)"))
    }

    @Test
    fun `reading is the whole surface - no submissions, no keys`() {
        val all = helpers + chainRules
        listOf(
            "\"submit\"",
            "api_key",
            "apiKey",
            "Authorization",
            "/submissions",
            "POST"
        ).forEach { forbidden ->
            assertFalse("the chain must stay read-only: $forbidden", all.contains(forbidden))
        }
    }

    // ── F2: button-only ──────────────────────────────────────────────────

    @Test
    fun `an AniSkip stamp never auto-skips, whatever the toggles say`() {
        val rule = autoSkip.substringAfter("fun shouldAutoSkip(")
            .substringBefore("fun isSkippableSegment(")
        assertTrue(
            "the source check runs before the kind check, so no toggle can reach it",
            rule.indexOf("stamp.source == IntroDbSource.AniSkip") in 0 until
                rule.indexOf("autoSkipsType(stamp.type, settings)")
        )
        assertTrue(
            "and it is the only source named in the decision",
            rule.windowed("IntroDbSource.".length)
                .count { it == "IntroDbSource." } == 1
        )
        assertTrue(
            "the source marker is what makes it checkable after the merge",
            model.contains("val source: IntroDbSource = IntroDbSource.IntroDbApp")
        )
        assertTrue(
            "the button path is untouched: it offers a row on shape alone",
            autoSkip.contains(
                "fun isSkippableSegment(stamp: IntroDbStamp, durationMs: Long): Boolean {"
            ) &&
                autoSkip.substringAfter("fun isSkippableSegment(")
                    .substringBefore("fun targetMs(")
                    .let { body -> !body.contains("AniSkip") && !body.contains("confidence") }
        )
    }

    // ── F3: duration and title reach the chain ───────────────────────────

    @Test
    fun `all three engines pass the duration and the show title`() {
        listOf(
            EXTERNAL,
            MPV,
            NATIVE
        ).forEach { path ->
            val call = callText(source(path), "fetchIntroDbStamps(")
            // ExoPlayer's duration is read into its own local first (see the
            // call site), so the argument is named for where it came from.
            assertTrue(
                "$path must pass the duration",
                call.contains("durationMs") || call.contains("knownDurationMs")
            )
            assertTrue(
                "$path must pass the title",
                call.contains("itemName") || call.contains("showTitle")
            )
        }
        assertTrue(
            "the signature keeps its old shape for anything else, with the new values defaulted",
            helpers.contains(
                "internal suspend fun fetchIntroDbStamps(\n" +
                    "    parentId: String,\n" +
                    "    season: Int?,\n" +
                    "    episode: Int?,\n" +
                    "    durationMs: Long? = null,\n" +
                    "    showTitle: String? = null\n" +
                    "): List<IntroDbStamp>"
            )
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** The argument list of the first [callee] call, nested calls intact. */
    private fun callText(src: String, callee: String): String {
        val from = src.indexOf(callee)
        if (from < 0) return ""
        var index = from + callee.length
        var depth = 1
        while (index < src.length && depth > 0) {
            when (src[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
            }
            index++
        }
        return src.substring(from + callee.length, index - 1)
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val ROOT = "com/kennyb1201/kbstream/ui/player/"
        const val HELPERS = "${ROOT}IntroDbHelpers.kt"
        const val MODEL = "${ROOT}IntroDbModel.kt"
        const val CHAIN_RULES = "${ROOT}IntroDbChainRules.kt"
        const val AUTO_SKIP = "${ROOT}AutoSkipRules.kt"
        const val EXTERNAL = "${ROOT}ExternalPlayerActivity.kt"
        const val MPV = "${ROOT}MpvPlayerActivity.kt"
        const val NATIVE = "${ROOT}NativePlayerActivity.kt"
    }
}
