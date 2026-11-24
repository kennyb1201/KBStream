package com.kennyb1201.kbstream.ui.player

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clearlogo ladder must say which step answered nothing (spec 2026-11-23).
 *
 * The field report was a logo that Detail and the Continue Watching row both
 * had, and a player that showed the plain title on its splash AND on its
 * overlay. Every step of the chain fails silently by design, so the report
 * could not name the failing step and two days of reading the tree did not
 * settle it. The instrumented ladder is the answer to that, and an
 * un-instrumented step is exactly how the regression stayed unidentified - so
 * every outcome shape is pinned here.
 *
 * The launch line is driven through the same function the activities call, so
 * "all five lines" means the real five and not the four the ladder writes by
 * itself.
 */
class PlayerClearLogoLadderTraceTest {

    private companion object {
        const val LOGO_BASE = "https://image.tmdb.org/t/p/w780"
    }

    private val lines = mutableListOf<String>()
    private var fanartCalls = 0
    private var claimedMovie = false
    private var claimedId: Int? = null

    private fun drive(
        detail: LogoDetailAnswer,
        tvdb: Int? = null,
        provenNoTvdb: Boolean = false,
        fanart: FanartLogoAnswer = FanartLogoAnswer(url = null, answered = false),
        type: String = "series",
        parentId: String = "12345"
    ): String? = runBlocking {
        lines += logoLaunchLine(
            engine = "exo",
            carriedLogo = false,
            parentId = parentId,
            parentType = type
        )
        resolveClearLogoChain(
            parentId = parentId,
            type = type,
            logoBase = LOGO_BASE,
            trace = { lines += it },
            fetchDetail = { detail },
            resolveTvdbId = { tvdb },
            provenNoTvdbId = { provenNoTvdb },
            fanartLogo = { isMovie, id ->
                fanartCalls++
                claimedMovie = isMovie
                claimedId = id
                fanart
            }
        )
    }

    private fun steps(prefix: String): List<String> =
        lines.filter { it.startsWith(prefix) }

    /** One line per step, in ladder order, and no line this test did not ask for. */
    private fun assertLadder(expected: List<String>) {
        assertEquals(
            "the ladder's trace lines are the whole read of the field paste - " +
                "one per step, in order:\n" + lines.joinToString("\n"),
            expected,
            lines
        )
    }

    @Test
    fun `a TMDB wordmark applies without ever asking fanart`() {
        val url = drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = "/cops.png",
                detailId = 42,
                source = "mem"
            )
        )

        assertEquals("$LOGO_BASE/cops.png", url)
        assertEquals("a TMDB hit must not cost a fanart call", 0, fanartCalls)
        assertLadder(
            listOf(
                "LOGO: start engine=exo extra=blank parentId=12345 parentType=series host=none shape=none",
                "LOGO: tmdb hit source=mem detailId=42 logoPath=/cops.png",
                "LOGO: applied $LOGO_BASE/cops.png"
            )
        )
        assertEquals(
            "the tvdb and fanart steps belong BELOW a TMDB hit",
            0,
            steps("LOGO: tvdb").size + steps("LOGO: fanart").size
        )
    }

    @Test
    fun `TMDB without a wordmark falls through the tvdb twin to fanart`() {
        val url = drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = null,
                detailId = 42,
                source = "disk"
            ),
            tvdb = 99,
            fanart = FanartLogoAnswer("https://assets.fanart.tv/cops.png", answered = true)
        )

        assertEquals("https://assets.fanart.tv/cops.png", url)
        assertEquals(1, fanartCalls)
        assertEquals("a series asks fanart's TV endpoint", false, claimedMovie)
        assertEquals("fanart's TV endpoint is keyed by the TVDB twin, not the TMDB id", 99, claimedId)
        assertLadder(
            listOf(
                "LOGO: start engine=exo extra=blank parentId=12345 parentType=series host=none shape=none",
                "LOGO: tmdb hit source=disk detailId=42 logoPath=none",
                "LOGO: tvdb 99 provenNone=false",
                "LOGO: fanart https://assets.fanart.tv/cops.png reason=hit",
                "LOGO: applied https://assets.fanart.tv/cops.png"
            )
        )
    }

    @Test
    fun `a total miss leaves every step on the record`() {
        val url = drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = null,
                detailId = 42,
                source = "net"
            ),
            tvdb = null,
            fanart = FanartLogoAnswer(url = null, answered = true)
        )

        assertNull(url)
        assertLadder(
            listOf(
                "LOGO: start engine=exo extra=blank parentId=12345 parentType=series host=none shape=none",
                "LOGO: tmdb hit source=net detailId=42 logoPath=none",
                "LOGO: tvdb none provenNone=false",
                "LOGO: fanart none reason=no-twin",
                "LOGO: none text-stands"
            )
        )
    }

    @Test
    fun `a failed fanart call is not filed as no-logo`() {
        drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = null,
                detailId = 42,
                source = "net"
            ),
            tvdb = 99,
            fanart = FanartLogoAnswer(url = null, answered = false)
        )

        assertEquals(
            "answered=false is a failed call, and the paste must say so",
            listOf("LOGO: fanart none reason=failed"),
            steps("LOGO: fanart")
        )
    }

    @Test
    fun `a remembered tvdb miss is a different fact from a fresh one`() {
        drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = null,
                detailId = 42,
                source = "mem"
            ),
            tvdb = null,
            provenNoTvdb = true
        )

        assertEquals(listOf("LOGO: tvdb none provenNone=true"), steps("LOGO: tvdb"))
    }

    @Test
    fun `a detail fetch that failed says so instead of claiming no record`() {
        drive(
            detail = LogoDetailAnswer(
                answered = false,
                callFailed = true,
                logoPath = null,
                detailId = 0,
                source = null
            )
        )

        assertLadder(
            listOf(
                "LOGO: start engine=exo extra=blank parentId=12345 parentType=series host=none shape=none",
                "LOGO: tmdb miss source=none detailId=none logoPath=none reason=call-failed",
                "LOGO: fanart none reason=no-twin",
                "LOGO: none text-stands"
            )
        )
    }

    @Test
    fun `a movie asks fanart by its own TMDB id and skips the tvdb step`() {
        val url = drive(
            detail = LogoDetailAnswer(
                answered = true,
                callFailed = false,
                logoPath = null,
                detailId = 550,
                source = "mem"
            ),
            fanart = FanartLogoAnswer("https://assets.fanart.tv/fightclub.png", answered = true),
            type = "movie"
        )

        assertEquals("https://assets.fanart.tv/fightclub.png", url)
        assertEquals(true, claimedMovie)
        assertEquals(550, claimedId)
        assertEquals(0, steps("LOGO: tvdb").size)
    }

    @Test
    fun `the launch line reports the id flavor as seen`() {
        val line = logoLaunchLine(
            engine = "mpv",
            carriedLogo = true,
            parentId = "tt1234567",
            parentType = "series",
            carriedUrl = "https://image.tmdb.org/t/p/w780/cops.png"
        )

        assertEquals(
            "a launch carrying its own logo still names what it carried, and " +
                "the id must appear unnormalized - that is what tells an IMDb id " +
                "apart from a TMDB one in the paste",
            "LOGO: start engine=mpv extra=present parentId=tt1234567 parentType=series " +
                "host=image.tmdb.org shape=/t/p/w780/cops.png",
            line
        )
    }

    /**
     * The carried URL's shape is the other half of F2: `extra=present` used to
     * be the whole answer, so a bare path, the image host and a stale URL on
     * somebody else's host all printed the same line.
     */
    @Test
    fun `a carried URL is named by host and path, and its query never prints`() {
        val full = logoLaunchShape(
            "https://image.tmdb.org/t/p/w780/cops.png?token=SECRET&x=1#frag"
        )
        assertEquals("image.tmdb.org", full.host)
        assertEquals("/t/p/w780/cops.png", full.path)

        // The shape a Continue Watching launch carries: the app prefixes it.
        val bare = logoLaunchShape("/t/p/w780/cops.png")
        assertEquals("none", bare.host)
        assertEquals("/t/p/w780/cops.png", bare.path)

        // A stale URL from some other host has to read as a different line.
        assertEquals(
            "cdn.somewhere-else.example",
            logoLaunchShape("https://cdn.somewhere-else.example/a/b/c.png").host
        )

        assertEquals(LogoLaunchShape("none", "none"), logoLaunchShape(null))
        assertEquals(LogoLaunchShape("none", "none"), logoLaunchShape("  "))
    }

    @Test
    fun `a long carried path is bounded, so a paste never carries a URL`() {
        val long = "/" + "a".repeat(200)
        assertEquals(LOGO_SHAPE_MAX_CHARS, logoLaunchShape(long).path.length)
    }

    @Test
    fun `a blank launch id is written as blank, not as an empty field`() {
        assertTrue(
            logoLaunchLine("exo", carriedLogo = false, parentId = "", parentType = "")
                .contains("parentId=blank parentType=blank ")
        )
    }

    /**
     * The wordmark URL the player applies must be TMDB's real image base. The
     * ladder takes it as a parameter so it can run off-device, which makes this
     * the one place that could quietly stop using the constant.
     */
    @Test
    fun `the adapter hands the ladder TMDB's own image base`() {
        val source = File(
            File(System.getProperty("user.dir") ?: ".").absoluteFile,
            "app/src/main/java/com/kennyb1201/kbstream/ui/player/ClearLogoResolve.kt"
        )
        val text = if (source.isFile) {
            source.readText()
        } else {
            File("src/main/java/com/kennyb1201/kbstream/ui/player/ClearLogoResolve.kt").readText()
        }

        assertTrue(
            "resolveClearLogoUrl must pass TmdbRepository.LOGO_BASE, not a literal",
            text.contains("logoBase = TmdbRepository.LOGO_BASE")
        )
    }
}
