package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token-free client: the visionos identity that lets a trailer play through
 * the stock loader, and the ranking rule that prefers it.
 *
 * The field case this exists for (2026-11-10, Head of State): a gated adaptive
 * pick stalled at 2.4 MB with `playerToken=false pot=false` - the URL had passed
 * its probe, so the failure the probe exists for was not what happened. A gated
 * URL can serve its opening ranges and still die at the token wall mid-play, and
 * that is invisible to a 1 KB read. So the preference cannot be "let the probe
 * sort it out": a token-free client's source has to rank above every gated one,
 * whatever the scores, because it is the source that does not need the token
 * this build sometimes cannot mint.
 *
 * Everything here is a pure decision or a wiring pin: the network behaviour
 * (ranges, open-ended reads, whether a visionos URL needs a UA) was measured
 * directly and is documented on `trailerDataSourceFactory`.
 */
class TrailerTokenFreeClientTest {

    // ── the identity ─────────────────────────────────────────────────────

    @Test
    fun `visionos is the fork's client, not an invented one`() {
        val visionos = YouTubeClients.visionos

        assertEquals("visionos", visionos.key)
        assertEquals("101", visionos.id)
        assertEquals("1.02", visionos.version)
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            visionos.userAgent
        )
        val context = visionos.context
        assertEquals("VISIONOS", context.getString("clientName"))
        assertEquals("1.02", context.getString("clientVersion"))
        assertEquals("Apple", context.getString("deviceMake"))
        assertEquals("RealityDevice17,1", context.getString("deviceModel"))
        assertEquals("visionOS", context.getString("osName"))
        assertEquals("26.5.23O471", context.getString("osVersion"))
        assertTrue("the URL-signing identity decides whether it plays", visionos.tokenFree)
        assertEquals(0, visionos.priority)
        assertEquals(YouTubeClients.all.first(), visionos)
    }

    @Test
    fun `its kind is the platform UA exception, not an app identity`() {
        // The UA is macOS Safari's and carries no "1.02": pretending it is an
        // APP entry would trip the table's own version-in-UA require.
        assertEquals(ClientKind.APPLE_DEVICE, YouTubeClients.visionos.kind)
        assertFalse(YouTubeClients.visionos.userAgent.contains("1.02"))
    }

    @Test
    fun `only visionos is token-free`() {
        assertTrue(YouTubeClients.isTokenFree("visionos"))

        listOf("android", "tv", "ios", "web_embedded", "android_vr", "imdb", null).forEach {
            assertFalse(
                "a gated client's URL still needs its mint: $it",
                YouTubeClients.isTokenFree(it)
            )
        }
        assertEquals(
            "the table's token-free entries are exactly the ranking's",
            setOf("visionos"),
            YouTubeClients.all.filter { it.tokenFree }.map { it.key }.toSet()
        )
    }

    // ── the ranking ──────────────────────────────────────────────────────

    @Test
    fun `a token-free candidate leads every gated pool`() {
        val order = probeOrder(
            listOf(
                candidate("android", score = 1.0e13, url = "android-best"),
                candidate("visionos", score = 10.0, url = "visionos"),
                candidate("tv", score = 5.0e12, url = "tv-best")
            ),
            preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT
        )

        assertEquals(
            "the pick that needs no mint outranks a gated source however it scores: " +
                "the gated URL can pass its probe and still die at the token wall",
            listOf("visionos", "android-best", "tv-best"),
            order.map { it.url }
        )
        assertEquals(
            "and the head of that order is what plays",
            "visionos",
            pickBest(
                listOf(
                    candidate("android", score = 1.0e13, url = "android-best"),
                    candidate("visionos", score = 10.0, url = "visionos")
                ),
                preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT
            )?.url
        )
    }

    @Test
    fun `a token-free death still excludes it and re-picks gated`() {
        val order = probeOrder(
            listOf(
                candidate("visionos", score = 90.0, url = "visionos"),
                candidate("android", score = 10.0, url = "android")
            ),
            preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT,
            excludeClients = setOf("visionos")
        )

        assertEquals(
            "the ladder's exclusion outranks the preference: a visionos death must " +
                "re-resolve gated, not hand back the class that just died",
            listOf("android"),
            order.map { it.url }
        )
    }

    @Test
    fun `every eligible candidate appears exactly once`() {
        val order = probeOrder(
            listOf(
                candidate("android", score = 10.0, url = "android-low"),
                candidate("visionos", score = 10.0, url = "visionos-low"),
                candidate("android", score = 90.0, url = "android-high"),
                candidate("visionos", score = 90.0, url = "visionos-high")
            ),
            preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT
        )

        assertEquals(
            "hoisting the token-free pool must not duplicate it through the pools below",
            listOf("visionos-high", "visionos-low", "android-high", "android-low"),
            order.map { it.url }
        )
    }

    @Test
    fun `a token-free manifest is the first HLS pool, under the same ceiling`() {
        val eligible = Triple("visionos", 0, "visionos-manifest")
        val gated = Triple("android", 1, "android-manifest")

        val pick = pickHlsManifest(
            listOf(
                gated to listOf(ManifestVariant("android-1080", height = 1080, bandwidth = 5_000_000)),
                eligible to listOf(ManifestVariant("visionos-1080", height = 1080, bandwidth = 5_000_000))
            ),
            preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT
        )

        assertEquals(
            "HLS is the first KIND, so a gated manifest would make every pick gated " +
                "before the token-free client was consulted",
            "visionos-manifest",
            pick?.third
        )

        // The ceiling still rules across pools: an above-ceiling token-free
        // manifest is not a source, and the gated one under it plays.
        val aboveCeiling = pickHlsManifest(
            listOf(
                Triple("visionos", 0, "visionos-4k") to
                    listOf(ManifestVariant("visionos-2160", height = 2160, bandwidth = 9_000_000)),
                gated to listOf(ManifestVariant("android-720", height = 720, bandwidth = 2_000_000))
            ),
            preferredClient = InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT
        )

        assertEquals("android-manifest", aboveCeiling?.third)
    }

    // ── the resolve wiring ───────────────────────────────────────────────

    @Test
    fun `a token-free winner is not refused for a cold mint`() {
        assertTrue(
            "the cold-mint guard exists for URLs the missing token would have signed",
            extractorSource.contains(
                "internal fun tokenlessWall(coldMintTokenless: Boolean, client: String?): Boolean = " +
                    "coldMintTokenless && !YouTubeClients.isTokenFree(client)"
            )
        )
        assertTrue(
            "and the winner's own client decides it, or the visionos pick is the " +
                "one a cold mint throws away",
            extractorSource.contains(
                "potlessByColdMint = tokenlessWall(potlessByColdMint, client)"
            )
        )
    }

    @Test
    fun `the resolve carries the winning client on its source`() {
        assertTrue(
            "the player decides its loader from the source, so the client has to travel with it",
            extractorSource.contains("client = bestHls.first")
        )
        assertTrue(extractorSource.contains("client = bestVideo?.client"))
        assertTrue(extractorSource.contains("client = bestProgressive.client"))
    }

    @Test
    fun `the new client is asked by the existing loop, not a path of its own`() {
        assertTrue(
            "visionos joins the same harvest and probe as every other client",
            extractorSource.contains("for (client in YouTubeClients.all) {")
        )
        assertFalse(
            "no visionos-shaped request path may exist beside the chain",
            extractorSource.contains("YouTubeClients.visionos")
        )
    }

    /** [StreamCandidate] with the client's real chain position. */
    private fun candidate(
        client: String,
        score: Double,
        url: String,
        hasN: Boolean = false,
        height: Int = 1080
    ): StreamCandidate = StreamCandidate(
        client = client,
        priority = YouTubeClients.clientFor(client)?.priority ?: 9,
        url = url,
        score = score,
        hasN = hasN,
        height = height,
        ext = "mp4"
    )

    /** The extractor's own source, whitespace flattened for the wiring checks. */
    private val extractorSource: String by lazy {
        val relative = "src/main/java/com/kennyb1201/kbstream/data/youtube/InnerTubeExtractor.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return@lazy candidate.readText().replace(Regex("\\s+"), " ")
            }
            dir = dir.parentFile
        }
        throw AssertionError("InnerTubeExtractor.kt not found walking up from the project root")
    }
}
