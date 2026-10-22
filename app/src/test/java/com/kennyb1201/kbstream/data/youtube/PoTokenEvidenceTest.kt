package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The field-evidence half of the PO-token layer: what a capture must still say
 * after a refusal, that the mint windows actually nest, that a token bound to
 * another visitor is not spent, and that the mint page is made to BE the watch
 * config's visitor.
 *
 * Why this exists at all: the 0.9.20 capture that followed the first trailer
 * deaths contained exactly one token line, `pot.cache: cleared`. The trace had
 * deleted the mint and resolve facts at the moment they finally mattered, the
 * resolve's 4 s budget had been cancelling the 7 s mint on a slow box, and the
 * token - when it was minted - had been minted for a different visitor and
 * spent anyway. Each of those is a wiring decision that compiles cleanly
 * whether or not it is right, which is why each is pinned here.
 */
@RunWith(AndroidJUnit4::class)
class PoTokenEvidenceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearTrace() {
        PoTokenTrace.clear()
    }

    // ── the trace survives the refusal ─────────────────────────────────────

    @Test
    fun `a refusal keeps the mint and resolve lines that explain it`() {
        PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.MINTED, 1_800L, bind = "match")
        PoTokenTrace.record(PoTokenContext.PLAYER, PoTokenState.MINTED, bind = "match")
        PoTokenTrace.recordResolve(VIDEO_ID, playerToken = true, pot = true, client = "android")

        PoTokenTrace.recordInvalidated()
        val lines = PoTokenTrace.lines()

        assertTrue(
            "the gvs state must survive the refusal that drops the tokens",
            lines.contains("pot.gvs: state=minted latency=1800ms bind=match")
        )
        assertTrue(
            "and the player state with it",
            lines.contains("pot.player: state=minted bind=match")
        )
        val resolve = lines.single { it.startsWith("pot.resolve:") }
        assertTrue(
            "the resolve is stamped, not erased: $resolve",
            resolve.contains(PoTokenTrace.INVALIDATED_FLAG)
        )
        assertTrue(
            "and it still names the signing client and whether the URL carried pot",
            resolve.contains("client=android") && resolve.contains("pot=true")
        )
        assertEquals(
            "the event line is unchanged",
            PoTokenTrace.CLEARED_EVENT,
            lines.last()
        )
    }

    @Test
    fun `a second refusal does not stamp the resolve twice`() {
        PoTokenTrace.recordResolve(VIDEO_ID, playerToken = false, pot = false, client = "ios")

        PoTokenTrace.recordInvalidated()
        PoTokenTrace.recordInvalidated()

        val resolve = PoTokenTrace.lines().single { it.startsWith("pot.resolve:") }
        assertEquals(
            "one stamp is the fact; two would read as two refusals of one resolve",
            1,
            Regex(PoTokenTrace.INVALIDATED_FLAG).findAll(resolve).count()
        )
    }

    @Test
    fun `a refusal before any resolve does not invent a resolve line`() {
        PoTokenTrace.recordInvalidated()

        val lines = PoTokenTrace.lines()
        assertTrue(
            "the pre-warm line is printed even when nothing warmed - a refusal before any " +
                "resolve must still not invent a resolve: $lines",
            lines.none { it.startsWith("pot.resolve:") }
        )
        assertEquals(PoTokenTrace.CLEARED_EVENT, lines.last())
    }

    // ── one ladder: script inside budget inside timeout ───────────────────

    @Test
    fun `the mint windows nest script inside budget inside timeout`() {
        val script = WebViewPoTokenMinter.MINT_SCRIPT_FALLBACK_MS
        val gvsBudget = InnerTubeExtractor.GVS_TOKEN_BUDGET_MS
        val providerTimeout = WebViewPoTokenProvider.DEFAULT_MINT_TIMEOUT_MS

        assertTrue(
            "the script's own window must close before the resolve stops waiting " +
                "for it, or every slow mint is cancelled just short of reporting: " +
                "script=${script}ms budget=${gvsBudget}ms",
            script < gvsBudget
        )
        assertTrue(
            "and the budget must close before the provider's own timeout, so the " +
                "inner window decides and the resolve keeps the time it needs",
            gvsBudget < providerTimeout
        )
        assertEquals(5_000L, script)
        assertEquals(6_000L, gvsBudget)
        assertEquals(10_000L, providerTimeout)
        assertTrue(
            "the resolve's overall cap still wins over the mint",
            gvsBudget < 15_000L
        )
    }

    @Test
    fun `the script's fallback actually uses that constant`() {
        // A constant nothing reads is documentation. The script is a private
        // string, so this reads it the way the other wiring tests read source.
        val source = read(WEB_VIEW_PROVIDER).replace(Regex("\\s+"), " ")

        assertTrue(
            "the injected script must finish on MINT_SCRIPT_FALLBACK_MS",
            source.contains("setTimeout(finish, \${MINT_SCRIPT_FALLBACK_MS});")
        )
        assertFalse(
            "the old 7 s window must not come back",
            source.contains("setTimeout(finish, 7000)")
        )
    }

    @Test
    fun `the GVS budget is the one the extractor spends on the mint`() {
        val source = read(INNER_TUBE).replace(Regex("\\s+"), " ")

        assertTrue(
            "the gvs mint must run under GVS_TOKEN_BUDGET_MS",
            source.contains(
                "val gvs = tokenWithinBudget(GVS_TOKEN_BUDGET_MS) { " +
                    "provider.gvsToken(videoId, visitorData) }"
            )
        )
        assertTrue(
            "and the player token keeps its own (cache-read) budget",
            source.contains("tokenWithinBudget(TOKEN_BUDGET_MS)")
        )
    }

    // ── a mismatched token is not spent ───────────────────────────────────

    @Test
    fun `a token minted for another visitor is withheld from the resolve`() = runBlocking {
        val minter = EchoMinter(visitor = "someone-else")
        val provider = WebViewPoTokenProvider(minter)

        assertNull(
            "a token bound to another session is a refusal with extra steps",
            provider.gvsToken(VIDEO_ID, "vis")
        )
        assertNull(
            "and the player token minted in that same session goes too",
            provider.playerToken("vis")
        )

        // What the resolve then sends: today's tokenless shapes, byte for byte.
        assertFalse(
            InnerTubeExtractor.playerRequestPayload(VIDEO_ID, YouTubeClients.android, null)
                .has("serviceIntegrityDimensions")
        )
        assertEquals(
            SIGNED_URL,
            (PlayableSource.Muxed(SIGNED_URL, userAgent = "ua").withPotToken(null)
                as PlayableSource.Muxed).url
        )

        val lines = PoTokenTrace.lines()
        assertTrue(
            "the capture must say the token was minted AND withheld, not that " +
                "nothing happened: $lines",
            lines.any { it.contains("bind=mismatch") && it.contains("spent=no") }
        )
    }

    @Test
    fun `a matching or unknown visitor is spent as before`() = runBlocking {
        listOf<String?>(
            // match: the page echoes the seeded visitor.
            "vis",
            // unknown: the page exposed no visitor at all.
            null
        ).forEach { pageVisitor ->
            PoTokenTrace.clear()
            val provider = WebViewPoTokenProvider(EchoMinter(visitor = pageVisitor))

            assertEquals("gvs", provider.gvsToken(VIDEO_ID, "vis"))
            assertEquals("player", provider.playerToken("vis"))
            assertFalse(
                "nothing is withheld: $pageVisitor",
                PoTokenTrace.lines().any { it.contains("spent=no") }
            )
        }
    }

    // ── the mint page is the watch config's visitor ───────────────────────

    @Test
    fun `the minter seeds VISITOR_INFO1_LIVE before the page loads`() {
        val written = mutableListOf<Pair<String, String>>()
        val minter = WebViewPoTokenMinter(
            context,
            setCookie = { url, cookie -> written += url to cookie }
        )

        minter.seedVisitor("vis-abc")

        val (url, cookie) = written.single()
        assertEquals(WebViewPoTokenMinter.YOUTUBE_ORIGIN, url)
        assertTrue(
            "the page's BotGuard mints for the visitor its cookie jar presents: $cookie",
            cookie.contains("VISITOR_INFO1_LIVE=vis-abc")
        )
        assertTrue(
            "and the cookie must be for the whole site, not one path",
            cookie.contains("Domain=.youtube.com") && cookie.contains("Path=/")
        )

        minter.seedVisitor("   ")
        assertEquals("a blank visitor seeds nothing", 1, written.size)
    }

    @Test
    fun `the cookie is written before loadUrl, not after`() {
        val source = read(WEB_VIEW_PROVIDER).replace(Regex("\\s+"), " ")

        val seedAt = source.indexOf("seedVisitor(visitorData)")
        val loadAt = source.indexOf("loadUrl(youtubeEmbedUrl(videoId))")
        assertTrue("the seeding must be in the mint path", seedAt >= 0)
        assertTrue("the load must still be there", loadAt >= 0)
        assertTrue(
            "seeding after the load would hand the page a session that already " +
                "decided which visitor it is",
            seedAt < loadAt
        )
    }

    /** A mint whose page presents [visitor]; null = the page exposed none. */
    private class EchoMinter(private val visitor: String?) : PoTokenMinter {
        override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens =
            MintedPoTokens(
                playerToken = "player",
                gvsToken = "gvs",
                visitorData = visitor
            )
    }

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
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
        const val WEB_VIEW_PROVIDER = "${YOUTUBE}WebViewPoTokenProvider.kt"

        const val VIDEO_ID = "gc0t8AfMP6k"

        const val SIGNED_URL =
            "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1800000000&id=o-abc" +
                "&itag=18&sig=abc%3D&lsig=x&n=zzz"
    }
}
