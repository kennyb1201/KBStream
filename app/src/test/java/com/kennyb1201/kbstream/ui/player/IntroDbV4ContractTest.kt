package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TheIntroDB retired v1 and froze v2/v3 (`GET /media` only, no fixes, being
 * removed); v4 is the only surface under development. This pins the two things
 * that could silently rot the skip buttons:
 *
 *  - the lookup points at `/v4` and nowhere older,
 *  - a v4 body still reads as stamps, and
 *  - a credits row with no `end_ms` survives as an open-ended stamp instead of
 *    being thrown away (which is what it was: a null end used to fail the
 *    parse, so most films had no SKIP CREDITS button at all).
 *
 * The body shape is the one part of the migration that is genuinely easy to
 * get wrong on paper, so the fixtures below are real v4 responses (captured
 * 2026-10-18), not hand-written guesses: v4 keys them by `tvdb_id` and carries
 * no `tmdb_id` at all, but the segment arrays are unchanged, which is why the
 * reader needed no new parsing.
 */
class IntroDbV4ContractTest {

    /** `GET /v4/media?imdb_id=tt0111161`, verbatim: credits with no stated end. */
    private val movieBody = """
        {"tvdb_id":190,"type":"movie","credits":[{"start_ms":8300000,"end_ms":null}]}
    """.trimIndent()

    /** `GET /v4/media?imdb_id=tt0903747&season=1&episode=1`, verbatim. */
    private val tvEpisodeBody = """
        {"tvdb_id":81189,"type":"tv","season":1,"episode":1,
         "intro":[{"start_ms":228664,"end_ms":246143}],
         "credits":[{"start_ms":3431000,"end_ms":null}]}
    """.trimIndent()

    @Test
    fun `media lookups target v4`() {
        assertEquals("https://api.theintrodb.org/v4/media", INTRO_DB_MEDIA_URL)
    }

    /**
     * Issue #79: without a `User-Agent` the API saw `okhttp/…` and could not
     * attribute a single request to this app - which is why its developer could
     * not warn anyone before changing the API.
     *
     * The header belongs to the request builder, so it is asserted on the
     * request both hosts actually use: the v4 `/media` read and the legacy
     * `/segments` first try.
     */
    @Test
    fun `every IntroDB request names the app and its build version`() {
        val expected = "KBStream/" + BuildConfig.VERSION_NAME

        assertEquals(
            "the constant is the app name plus the generated version",
            expected,
            INTRO_DB_USER_AGENT
        )
        assertTrue(
            "a stale literal would misreport which release called: $INTRO_DB_USER_AGENT",
            Regex("^KBStream/\\d+\\.\\d+").containsMatchIn(INTRO_DB_USER_AGENT)
        )
        listOf(
            "$INTRO_DB_MEDIA_URL?imdb_id=tt0903747&season=1&episode=1",
            "https://api.introdb.app/segments?imdb_id=tt0903747"
        ).forEach { url ->
            assertEquals(
                "the request to $url must identify the app",
                expected,
                introDbRequest(url).header("User-Agent")
            )
        }
    }

    /**
     * The header is only sent where the request is built. A second bare
     * `Request.Builder()` in this file would be a silent `okhttp/…` path back
     * into the API, so the fetch has to go through [introDbRequest].
     */
    @Test
    fun `the fetch path uses the extracted request, not a bare builder`() {
        val src = readSource(HELPERS)
        assertTrue(
            "fetchIntroDbJson must send introDbRequest(url), not build its own request",
            src.contains("newCall(introDbRequest(url))")
        )
        assertEquals(
            "one request builder, so every IntroDB call carries the header",
            1,
            Regex("Request\\.Builder\\(").findAll(src).count()
        )
    }

    /**
     * A pinned base URL is only worth pinning if the source actually uses it -
     * the old v2 literal living on next to the constant would pass the assertion
     * above while every request still went to the retired surface.
     */
    @Test
    fun `the lookup calls v4 and no retired version`() {
        val src = readSource(HELPERS)
        assertTrue(
            "the v4 URL is no longer declared as the constant",
            src.contains("\"$INTRO_DB_MEDIA_URL\"")
        )
        assertTrue(
            "the lookup no longer builds its URL from INTRO_DB_MEDIA_URL",
            src.contains("\$INTRO_DB_MEDIA_URL?")
        )
        listOf("/v1/", "/v2/", "/v3/").forEach { retired ->
            assertFalse(
                "source still calls the retired surface: $retired",
                src.contains("theintrodb.org$retired")
            )
        }
    }

    @Test
    fun `a v4 episode body keyed by tvdb_id reads its intro`() {
        val stamps = readIntroDbMedia(JSONObject(tvEpisodeBody))
        val intro = stamps.firstOrNull { it.type == IntroDbMarkerType.Intro }

        assertTrue("v4 intro segment was dropped", intro != null)
        assertEquals(228_664L, intro?.startMs)
        assertEquals(246_143L, intro?.endMs ?: 0L)
    }

    @Test
    fun `a v4 credits row with no end survives as open-ended`() {
        val stamps = readIntroDbMedia(JSONObject(movieBody))

        assertEquals(1, stamps.size)
        val credits = stamps.single()
        assertEquals(IntroDbMarkerType.Credits, credits.type)
        assertEquals(8_300_000L, credits.startMs)
        // No end stated means "to the end of the file"; the runtime resolves it
        // at playback (AutoSkipRules.segmentEndMs), never here.
        assertNull(credits.endMs)
    }

    @Test
    fun `only a segment that ends with the file may be open-ended`() {
        // An intro with no end is a skip with no target, so it is still a row
        // this app cannot act on - dropped, as before.
        assertNull(JSONObject("""{"start_ms":20000}""").readIntroDbStamp(IntroDbMarkerType.Intro))
        assertNull(JSONObject("""{"start_ms":0,"end_ms":null}""").readIntroDbStamp(IntroDbMarkerType.Recap))
        assertNull(JSONObject("""{"start_ms":2800000}""").readIntroDbStamp(IntroDbMarkerType.Preview))
        // Credits and outros are exactly the segments that run to the file's
        // end, so their missing end is meaningful rather than incomplete.
        assertNotNull(JSONObject("""{"start_ms":8300000}""").readIntroDbStamp(IntroDbMarkerType.Credits))
        assertNotNull(JSONObject("""{"start_ms":8300000,"end_ms":null}""").readIntroDbStamp(IntroDbMarkerType.Outro))
    }

    @Test
    fun `a v4 body needs no tmdb_id to parse`() {
        assertFalse("fixture should prove the tmdb_id removal", tvEpisodeBody.contains("tmdb_id"))
        val body = JSONObject(tvEpisodeBody)

        assertTrue(body.has("tvdb_id"))
        assertTrue(readIntroDbMedia(body).isNotEmpty())
    }

    /** v4 serves a segment type as an array; every segment it lists is a marker. */
    @Test
    fun `every segment in a v4 array is kept`() {
        val stamps = readIntroDbMedia(
            JSONObject(
                """
                {"tvdb_id":71663,"type":"tv","season":1,"episode":1,
                 "intro":[{"start_ms":30000,"end_ms":90000},
                          {"start_ms":120000,"end_ms":150000}]}
                """.trimIndent()
            )
        )

        assertEquals(2, stamps.size)
        assertEquals(listOf(30_000L, 120_000L), stamps.map { it.startMs })
        assertTrue(stamps.all { it.type == IntroDbMarkerType.Intro })
    }

    @Test
    fun `v4 segment keys map to their skip types`() {
        val stamps = readIntroDbMedia(
            JSONObject(
                """
                {"tvdb_id":71663,"type":"tv","season":1,"episode":1,
                 "recap":[{"start_ms":0,"end_ms":30000}],
                 "outro":[{"start_ms":1300000,"end_ms":1400000}],
                 "preview":[{"start_ms":1450000,"end_ms":1500000}]}
                """.trimIndent()
            )
        )

        assertEquals(
            listOf(
                IntroDbMarkerType.Recap,
                IntroDbMarkerType.Outro,
                IntroDbMarkerType.Preview
            ),
            stamps.map { it.type }
        )
    }

    /**
     * v4 answers `GET /media` with one of three bodies. Only the grouped one
     * carries timestamps; the release-version list and the episode-count
     * fallback must read as "nothing to skip" rather than as junk markers.
     */
    @Test
    fun `the alternative v4 bodies produce no stamps`() {
        val versions = JSONObject(
            """
            {"tvdb_id":169,"type":"movie",
             "versions":[{"duration_ms":7200000,"average_duration_ms":7195000,"submission_count":14}]}
            """.trimIndent()
        )
        val episodes = JSONObject(
            """
            {"tvdb_id":71663,"type":"tv",
             "episodes":[{"season":1,"episode":1,"total_submission_count":4,"intro_accepted_count":4}]}
            """.trimIndent()
        )

        assertTrue(readIntroDbMedia(versions).isEmpty())
        assertTrue(readIntroDbMedia(episodes).isEmpty())
    }

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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
        const val HELPERS = "com/kennyb1201/kbstream/ui/player/IntroDbHelpers.kt"
    }
}
