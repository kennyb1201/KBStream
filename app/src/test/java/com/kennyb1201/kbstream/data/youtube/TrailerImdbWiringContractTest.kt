package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailer reliability work's wiring, read out of the source.
 *
 * The two halves are rules that can be tested on their own ([probeOrder],
 * [com.kennyb1201.kbstream.data.imdb.ImdbStreamSelector] and the IMDb parsers),
 * but a rule that nothing calls is not a fix: this file pins that the resolve
 * really probes before it mounts, that IMDb really is asked BEFORE the YouTube
 * ladder, that every surface passes the id it has, and that the YouTube path is
 * untouched when the setting is off.
 */
class TrailerImdbWiringContractTest {

    private val launcher = source("com/kennyb1201/kbstream/data/youtube/TrailerPlayerLauncher.kt")
    private val extractor = source("com/kennyb1201/kbstream/data/youtube/InnerTubeExtractor.kt")
    private val detailLaunch = source("com/kennyb1201/kbstream/ui/detail/DetailTrailerLaunch.kt")
    private val resolver = source("com/kennyb1201/kbstream/data/imdb/ImdbTrailerResolver.kt")
    private val api = source("com/kennyb1201/kbstream/data/imdb/ImdbTrailerApi.kt")
    private val prefs = source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
    private val homeTv = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val homePhone = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    private val detailTv = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
    private val detailPhone =
        source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
    private val settingsTv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val settingsPhone =
        source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    // ── F1: probe before mounting ─────────────────────────────────────────

    @Test
    fun `the adaptive and progressive picks probe their top candidates`() {
        val body = flat(extractor)

        assertTrue(
            "the video pick is a probe of the ranked candidates, not a bet on " +
                "the top one",
            body.contains(
                "val bestVideo = firstServable( probeOrder(allowedVideo, " +
                    "PREFERRED_SEPARATE_CLIENT, excludeClients) )"
            )
        )
        assertTrue(
            "and the progressive pick is the same rule",
            body.contains(
                "val bestProgressive = firstServable( probeOrder(allowedProgressive, " +
                    "PREFERRED_SEPARATE_CLIENT, excludeClients) )"
            )
        )
        assertTrue(
            "the single-candidate probe that used to answer for the mount is gone " +
                "from the selection",
            !body.contains("resolvedVideo = bestVideo?.let { resolveReachableUrl(it.url")
        )
    }

    @Test
    fun `the probe waits for the first readable candidate, not for all of them`() {
        val body = flat(extractor)

        assertTrue(
            "every probe starts together",
            body.contains("val probes = top.map { candidate -> async(Dispatchers.IO) {")
        )
        assertTrue(
            "and the winner is the best-ranked READABLE candidate: the answers " +
                "are awaited in rank order",
            body.contains("for (index in top.indices) { if (probes[index].await()) {")
        )
        assertTrue(
            "a probe still running when the pick is made is cancelled",
            body.contains("probes.forEach { it.cancel() }")
        )
        assertTrue(
            "and the pick is bounded as a set",
            body.contains("withTimeoutOrNull(CANDIDATE_PROBE_BUDGET_MS)")
        )
    }

    @Test
    fun `the probe reads bytes under the UA the URL was signed for`() {
        val body = flat(extractor)

        assertTrue(
            "a ranged read, the same shape playback requests",
            body.contains(""".header("Range", "bytes=0-1048575")""")
        )
        assertTrue(
            "with the signing client's UA - googlevideo binds a signed URL to it",
            body.contains(""".header("User-Agent", userAgent ?: YouTubeClients.android.userAgent)""")
        )
        assertTrue(
            "and a 200/206 with no bytes is not a readable source",
            body.contains("val stream = response.body.byteStream()") &&
                body.contains("stream.read(buffer) > 0")
        )
    }

    @Test
    fun `the ladder order and the exclusion retry are unchanged`() {
        val body = flat(extractor)

        assertTrue(
            "HLS is still the first shape",
            body.contains("val bestHls = pickBestHls(allowedHls, PREFERRED_SEPARATE_CLIENT)")
        )
        assertTrue(
            "the exclusion is still applied at selection",
            body.contains("val allowedVideo = adaptiveVideo.filterNot { it.client in excludeClients }")
        )
        assertTrue(
            "and the chain the exclusions grow from is untouched",
            body.contains("for (client in YouTubeClients.all) {")
        )
    }

    // ── F2: IMDb before YouTube ───────────────────────────────────────────

    @Test
    fun `the launcher asks IMDb before the YouTube ladder`() {
        val body = flat(launcher)
        val imdb =
            body.indexOf("val imdb = resolveImdbSource(imdbId, tmdbId, type, excludeClients)")
        val innerTube = body.indexOf("InnerTubeExtractor.extractPlaybackSourceWithClient(")

        assertTrue("the IMDb leg is gone", imdb >= 0)
        assertTrue("the InnerTube leg is gone", innerTube >= 0)
        assertTrue(
            "IMDb is tried FIRST and the ladder is the fallback, which is the " +
                "whole source order this spec asks for",
            imdb < innerTube
        )
        assertTrue(
            "and it plays as an ordinary Muxed source - one progressive file",
            body.contains("val source = PlayableSource.Muxed(imdb.url)")
        )
    }

    @Test
    fun `the IMDb leg is gated by the setting, an id, and not being a resume`() {
        val body = flat(launcher)

        assertTrue(
            "a resume excludes a class that died; IMDb has no class, so its " +
                "recovery is the YouTube path",
            body.contains("if (excludeClients.isNotEmpty()) return null")
        )
        assertTrue(
            "the viewer's switch",
            body.contains("if (!AppPreferences.getImdbTrailerEnabled(context)) return null")
        )
        assertTrue(
            "and there must be an id to look up - the caller's own, else TMDB's " +
                "mapping (most rails are keyed on TMDB, not on tt…)",
            body.contains("val id = imdbIdFor(context, imdbId, tmdbId, type) ?: return null") &&
                body.contains(
                    "TmdbRepository.getInstance(context).resolveImdbId(tmdb, type ?: \"movie\")"
                )
        )
        assertTrue(
            "a play press gets the four-second service exchange, never the " +
                "eighteen-second title page",
            body.contains("ImdbTrailerResolver.resolve(id, allowPage = false)")
        )
        assertTrue(
            "the page leg belongs to the background pre-resolve",
            body.contains("ImdbTrailerResolver.prefetch(id)")
        )
    }

    @Test
    fun `an IMDb mount is named, and its death resends the retry to YouTube`() {
        val body = flat(launcher)

        assertTrue(
            "the trace says which source played, so a capture can answer " +
                "'did it use IMDb?'",
            body.contains("PoTokenTrace.recordTrailerSource(")
        )
        assertTrue(
            "an IMDb mount names a client the resume can exclude - on the inline " +
                "ladder AND on the fullscreen player's own signingClient",
            body.contains("internal const val IMDB_CLIENT = \"imdb\"") &&
                body.contains("return if (imdbMounted.containsKey(id)) IMDB_CLIENT else null") &&
                body.contains("fun signingClient(videoIdOrUrl: String): String? {") &&
                body.contains("if (imdbMounted.containsKey(id)) return IMDB_CLIENT")
        )
        assertTrue(
            "and a YouTube hand-over clears it, so the death read is the source " +
                "that actually mounted last",
            body.countOf("imdbMounted.remove(videoId)") >= 2
        )
    }

    @Test
    fun `the IMDb source caches 30 minutes on a hit and 5 on a miss`() {
        val body = flat(resolver)

        assertTrue(
            "a resolved source is served for half an hour",
            body.contains("internal const val HIT_TTL_MS = 30 * 60_000L")
        )
        assertTrue(
            "a miss is remembered for five, so a title that has no trailer is " +
                "not re-asked on every hover - and comes back on its own",
            body.contains("internal const val MISS_TTL_MS = 5 * 60_000L")
        )
        assertTrue(
            "and a cached URL never outlives its signature",
            body.contains("val validUntil = minOf(now + ttl, urlExpiry - EXPIRY_MARGIN_MS)")
        )
        assertTrue(
            "the title page is only reached when the service did not answer",
            body.contains("if (!allowPage) return null")
        )
    }

    @Test
    fun `the IMDb endpoint carries KBStream's own client name`() {
        val body = flat(api)

        assertTrue(
            "the endpoint is IMDb's own data service",
            body.contains("private const val ENDPOINT = \"https://api.graphql.imdb.com/\"")
        )
        assertTrue(
            "with THIS app's name - borrowing the reference app's would make a " +
                "refusal impossible to attribute",
            body.contains("private const val CLIENT_NAME = \"kbstream\"") &&
                body.contains(""".header("x-imdb-client-name", CLIENT_NAME)""")
        )
        assertTrue(
            "and a four-second budget: IMDb answers fast or the page takes over",
            body.contains("internal const val BUDGET_MS = 4_000L")
        )
        assertTrue(
            "a refusal pauses the service instead of poisoning the title",
            body.contains("ImdbTrailerData.hasErrors(body) -> backoff.refused(")
        )
    }

    @Test
    fun `the title page leg is one gated WebView, destroyed after use`() {
        val body = flat(resolver)

        assertTrue(
            "one at a time, on Main",
            body.contains("private val webViewGate = Semaphore(permits = 1)") &&
                body.contains("withContext(Dispatchers.Main.immediate)")
        )
        assertTrue(
            "destroyed even on cancellation",
            body.contains("withContext(NonCancellable) { destroy(view) }")
        )
        assertTrue(
            "with two load attempts and an 18-second budget",
            body.contains("private const val LOAD_ATTEMPTS = 2") &&
                body.contains("internal const val PAGE_BUDGET_MS = 18_000L")
        )
        assertTrue(
            "and static sub-resources blocked by extension only - never the " +
                "document, a script or an XHR",
            body.contains("private val BLOCK_EXT = Regex(") &&
                body.contains("if (request?.isForMainFrame == true) return null")
        )
    }

    @Test
    fun `the setting is on by default and syncs`() {
        assertTrue(
            "Kenny commissioned this for reliability, so a fresh install gets it",
            prefs.contains("prefs(context).getBoolean(KEY_IMDB_TRAILERS, true)")
        )
        assertTrue(
            "and both settings screens offer it",
            settingsTv.contains("AppPreferences.setImdbTrailerEnabled(context, it)") &&
                settingsPhone.contains("AppPreferences.setImdbTrailerEnabled(context, it)")
        )
    }

    // ── every surface that resolves a trailer passes the id ───────────────

    @Test
    fun `the TV detail hero and media rail pass the id`() {
        assertTrue(
            "the page's own IMDb id, when the add-on's id already is one",
            detailTv.contains(
                "val detailImdbId = com.kennyb1201.kbstream.data.imdb.ImdbTrailerData.imdbIdOf(meta?.id)"
            )
        )
        assertTrue(
            "and the shared launch is handed it, with the TMDB id beside it for " +
                "a TMDB-keyed page",
            flat(detailTv).contains(
                "DetailTrailerLaunch.play( context, scope, video, imdbId = detailImdbId, " +
                    "tmdbId = tmdbDetail?.id, type = normalizedType )"
            )
        )
        assertTrue(
            "which forwards both to the one resolve",
            flat(detailLaunch).contains(
                "TrailerPlayerLauncher.resolvePlayableUrl( " +
                    "\"https://www.youtube.com/watch?v=\$key\", imdbId = imdbId, " +
                    "tmdbId = tmdbId, type = type )"
            )
        )
    }

    @Test
    fun `the phone detail hero and media rail pass the id`() {
        assertTrue(
            detailPhone.contains(
                "val trailerImdbId = com.kennyb1201.kbstream.data.imdb.ImdbTrailerData" +
                    ".imdbIdOf(meta?.id)"
            )
        )
        assertTrue(
            "the inline owner's resolve is handed the id and the TMDB fallback",
            flat(detailPhone).contains(
                "imdbId = trailerImdbId, tmdbId = tmdbDetail?.id, type = normalizedType )"
            )
        )
        assertTrue(
            "the media row's fullscreen launch goes through the shared one too",
            flat(detailPhone).contains(
                "DetailTrailerLaunch.play( context, scope, video, imdbId = trailerImdbId, " +
                    "tmdbId = tmdbDetail?.id, type = normalizedType )"
            )
        )
    }

    @Test
    fun `the TV hero and the phone billboard pass the id`() {
        assertTrue(
            "the hero host's own meta state names the id",
            flat(homeTv).contains(
                "val heroImdbId = com.kennyb1201.kbstream.data.imdb.ImdbTrailerData " +
                    ".imdbIdOf(heroMeta.value?.id)"
            )
        )
        assertTrue(
            "and both its pre-warm and its post-dwell resolve use it, with the " +
                "hero's TMDB id beside it",
            homeTv.countOf("imdbId = heroImdbId") >= 1 &&
                homeTv.countOf("TrailerPlayerLauncher.prefetchImdbTrailer(") >= 1 &&
                flat(homeTv).contains(
                    "imdbId = heroImdbId, tmdbId = tmdbDetail?.id, type = preview.type )"
                )
        )
        assertTrue(
            "the billboard page carries its own id",
            homePhone.contains("imdbId = page.imdbId") &&
                homePhone.contains("tmdbId = page.tmdbId,")
        )
        assertTrue(
            "and pre-resolves the next page's, on the same settle as its tokens",
            flat(homePhone).contains(
                "TrailerPlayerLauncher.prefetchImdbTrailer( imdbId = nextPage?.imdbId, " +
                    "tmdbId = nextPage?.tmdbId, type = nextPage?.preview?.type )"
            )
        )
    }

    @Test
    fun `the billboard's page model carries the id the resolve needs`() {
        val models = source("com/kennyb1201/kbstream/ui/home/HomeViewModelModels.kt")
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

        assertTrue(
            models.contains("val imdbId: String? = null,") &&
                models.contains("val tmdbId: Int? = null,")
        )
        assertTrue(
            "filled from the page's own detail, with the TMDB id beside it for " +
                "the mapping the launcher resolves",
            viewModel.contains(
                "imdbId = com.kennyb1201.kbstream.data.imdb.ImdbTrailerData.imdbIdOf(id),"
            ) && viewModel.contains("tmdbId = detail?.id,")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun String.countOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
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

}
