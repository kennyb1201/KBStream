package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mobile billboard's wiring, pinned where a JVM test can see it.
 *
 * A Compose pager and its timers are not observable from a unit test, so what
 * each assertion names is the DECISION at its call site - the gate that decides
 * whether the slot exists, the effect keys that make a swipe re-arm the hold,
 * the one handoff that lets Play be Detail's own Play. All of them are things a
 * refactor can silently delete while the app still compiles, which is why they
 * are pinned rather than only exercised on a phone.
 *
 * The TV half is pinned by ABSENCE: no billboard in `HomeScreen.kt`, and the
 * billboard's own block in `HomeViewModel` never touching the hero flows the TV
 * reads. Those flows still have their own tests; what is new here is that the
 * phone's new driver cannot have borrowed them.
 */
class MobileBillboardContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String =
        File(sourceRoot, path).readText().replace(Regex("\\s+"), " ")

    private fun mobileHome(): String =
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")

    private fun mobileDetail(): String =
        source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")

    private fun homeViewModel(): String =
        source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

    private fun prefs(): String =
        source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

    private fun sync(): String =
        source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")

    private fun tmdbApi(): String =
        source("com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt")

    private fun managerDialog(): String =
        source("com/kennyb1201/kbstream/ui/mobile/HomeManagerDialogMobile.kt")

    /** The billboard's own block in the view model, between its two markers. */
    private fun billboardBlock(): String {
        val text = File(sourceRoot, "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
            .readText()
        val start = text.indexOf("── The phone Home billboard")
        val end = text.indexOf("// Prefetches hero art")
        assertTrue("billboard block markers not found", start >= 0 && end > start)
        return text.substring(start, end).replace(Regex("\\s+"), " ")
    }

    // ── the slot ────────────────────────────────────────────────────────

    @Test
    fun `the hero slot renders the billboard and nothing when it cannot`() {
        val home = mobileHome()

        // Appears on the first settled page (spec 2.1), not on the seed, and
        // only while the toggle is on.
        assertTrue(
            home.contains("billboardEnabled && billboardPages.any { page -> page.settled }")
        )
        assertTrue(home.contains("item(key = \"billboard\", contentType = \"hero\")"))
        assertTrue(home.contains("MobileBillboard( pages = billboardPages,"))
        assertTrue(home.contains("onPlay = { page ->"))
        assertTrue(home.contains("onDetails = { page -> onItemClick(page.preview) }"))
    }

    @Test
    fun `the feed comes from the rails Home already shows, or the day feed`() {
        val home = mobileHome()

        assertTrue(home.contains("billboardChosenFeed("))
        assertTrue(home.contains("railsShown.forEach { rail -> rail.billboardFeedKey()?.let { key -> put(key, rail.items) } }"))
        assertTrue(home.contains("KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU, topPicks"))
        assertTrue(home.contains("KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING"))
        assertTrue(home.contains("KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE"))
        // Hidden titles are dropped, exactly as they are from the rails.
        assertTrue(home.contains("HiddenTitles.hides( hiddenTitleKeys, item.type, item.name, item.yearOrNull, item.id )"))
        assertTrue(home.contains(".take(BILLBOARD_MAX_PAGES)"))
        // The default feed is loaded only when the slot is armed.
        assertTrue(home.contains("if (billboardEnabled) viewModel.loadBillboardTrendingDay()"))
        // A missing driver would leave the slot inert again.
        assertTrue(home.contains("viewModel.resolveBillboard(billboardFeed)"))
        assertTrue(home.contains("viewModel.clearBillboard()"))
    }

    // ── motion ──────────────────────────────────────────────────────────

    @Test
    fun `auto-advance is a pager hold keyed on page, finger, background and trailer`() {
        val home = mobileHome()

        assertTrue(home.contains("HorizontalPager( state = pagerState,"))
        // Manual only is a real option: 0 never advances.
        assertTrue(home.contains("if (cycleMs <= 0L || pages.size <= 1) return@LaunchedEffect"))
        // A trailer owns the page it is on (or is about to), so the hold waits.
        assertTrue(
            home.contains("if (backgrounded || playbackPageKey != null || playback != null) {")
        )
        assertTrue(home.contains("if (autoTrailerArmed) return@LaunchedEffect"))
        // The pace follows the SETTLED page and the finger, read as state: a
        // finger is the pause and its release is the re-arm, and the advance is
        // launched on the composition's scope so the collector that started it
        // cannot cancel it (animateScrollToPage sets isScrollInProgress).
        assertTrue(
            home.contains("snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }")
        )
        assertTrue(home.contains("if (scrolling) return@collectLatest"))
        assertTrue(home.contains("scope.launch { pagerState.animateScrollToPage(next) }"))
        // Backgrounding is a lifecycle event, resumed on the way back.
        assertTrue(home.contains("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)"))
    }

    @Test
    fun `manual navigation is swipe, chevrons and dots`() {
        val home = mobileHome()

        assertTrue(home.contains("description = \"Previous\""))
        assertTrue(home.contains("description = \"Next\""))
        assertTrue(home.contains("pagerState.animateScrollToPage(previous)"))
        assertTrue(home.contains("pagerState.animateScrollToPage(next)"))
        // Dots jump to their own page and each one names the item it goes to.
        assertTrue(home.contains("pagerState.animateScrollToPage(index)"))
        assertTrue(home.contains("contentDescription = page.meta.name"))
        assertTrue(home.contains("if (selected) KBAccent else KBTextLo.copy(alpha = 0.35f)"))
    }

    // ── buttons ─────────────────────────────────────────────────────────

    @Test
    fun `Play is Detail's own Play, not a second resolve`() {
        val home = mobileHome()

        assertTrue(
            home.contains(
                "onPlay = { page -> BillboardPlaySelection.request(page.preview.id) onItemClick(page.preview) }"
            )
        )
        // The billboard must not build a streams target or resolve a source: it
        // carries no copy of Detail's autoplay/episode rules.
        val billboard = home.substring(
            home.indexOf("private fun MobileBillboard("),
            home.indexOf("private fun Rail.billboardFeedKey()")
        )
        assertFalse(billboard.contains("StreamsTarget("))
        assertFalse(billboard.contains("awaitStreamRootId"))
        assertFalse(billboard.contains("ManualSourceSelection"))

        // Detail consumes the request and presses the SAME function its Play
        // button calls.
        val detail = mobileDetail()
        assertTrue(detail.contains("billboardPlay = BillboardPlaySelection.consume(id)"))
        val effect = detail.substring(
            detail.indexOf("billboardPlayDelivered = true"),
            detail.indexOf("billboardPlayDelivered = true") + 80
        )
        assertTrue(effect.contains("playCurrent()"))
        // Once, and only after the page has its title (and a series its listing).
        assertTrue(detail.contains("if (normalizedType == \"series\" && episodesLoading) return@LaunchedEffect"))
        assertTrue(detail.contains("if (!billboardPlay || billboardPlayDelivered || isLoading) return@LaunchedEffect"))
    }

    // ── trailers ────────────────────────────────────────────────────────

    @Test
    fun `trailer modes reuse the hero-trailer stack and its settings`() {
        val home = mobileHome()

        // Auto-play waits the EXISTING delay, not a second pref.
        assertTrue(home.contains("AppPreferences.getHeroTrailerDelayMs(context)"))
        assertTrue(home.contains("AppPreferences.getHeroTrailerMuted(context)"))
        assertTrue(home.contains("delay(trailerDelayMs)"))
        assertTrue(home.contains("startTrailer(pagerState.currentPage)"))
        // Manual is the only mode that draws a Trailer affordance.
        assertTrue(home.contains("trailerMode == BillboardTrailerMode.MANUAL &&"))
        assertTrue(home.contains("label = if (playback != null) \"Stop\" else \"Trailer\""))
        // Off draws none: the row's Other two buttons are unconditional, so a
        // trailer button outside that gate would show in every mode.
        assertTrue(
            home.contains("if ( trailerMode == BillboardTrailerMode.MANUAL && !currentPage?.trailerKey.isNullOrBlank() ) {")
        )
        // Playback is the app's one pooled stack, and a failure is silent.
        assertTrue(home.contains("TrailerPlayerLauncher.resolvePlayableUrl(key)"))
        assertTrue(home.contains("HeroInlineTrailerPlayer("))
        assertTrue(home.contains("TrailerPlayerPool.releaseForReuse()"))
        assertTrue(home.contains("// Silent: the page keeps its backdrop, with no banner and no"))
        // Only the current page may play, and a page change stops it.
        assertTrue(home.contains("LaunchedEffect(pagerState.currentPage) { stopTrailer() }"))
        assertTrue(
            home.contains("trailerPlayback = if (index == pagerState.currentPage) playback else null")
        )
        assertTrue(home.contains("onFailed = { onStopTrailer() }"))
    }

    // ── the driver ──────────────────────────────────────────────────────

    @Test
    fun `pages are built off-composition, two or three at a time, and swapped whole`() {
        val vm = billboardBlock()

        assertTrue(vm.contains("private val billboardResolveSemaphore = Semaphore(permits = 3)"))
        assertTrue(vm.contains("withPermit { resolveBillboardPage(item) }"))
        // Per-page atomic publish: one object carries art AND copy.
        assertTrue(vm.contains("_billboardPages.update { pages -> pages.map { page -> if (page.key == key) resolved else page } }"))
        assertTrue(vm.contains("settled = true"))
        // Seeded in feed order, so the pager's count and order never move.
        assertTrue(vm.contains("_billboardPages.value = feed.map { item -> BillboardPage("))
        // The default feed is the day pair, interleaved by rank.
        assertTrue(vm.contains("tmdbRepository.trendingDay(\"movie\")"))
        assertTrue(vm.contains("tmdbRepository.trendingDay(\"tv\")"))
        assertTrue(vm.contains("billboardInterleaveFeeds("))
        // A recomposition carrying the same feed resolves nothing new.
        assertTrue(vm.contains("if (currentKeys == wantedKeys) {"))
        // A failed reload never blanks a published feed.
        assertTrue(vm.contains("if (pages.isNotEmpty()) _billboardTrendingDay.value = pages"))
    }

    @Test
    fun `the TV hero's flows are not touched by the billboard`() {
        val vm = billboardBlock()

        assertFalse(vm.contains("_heroMeta"))
        assertFalse(vm.contains("_heroBackdropUrl"))
        assertFalse(vm.contains("_heroLogoUrl"))
        assertFalse(vm.contains("_heroSettledKey"))
        assertFalse(vm.contains("resolveHeroMeta("))

        // ...and the TV hero's own publish is still there, unchanged in shape.
        val all = homeViewModel()
        assertTrue(all.contains("_heroSettledKey.value = \"\$requestedId:\$requestedType\""))

        // The television screen has no billboard at all.
        assertFalse(source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt").contains("illboard"))
    }

    // ── prefs and settings ──────────────────────────────────────────────

    @Test
    fun `the four prefs are device-local, defaulted and read back`() {
        val p = prefs()

        assertTrue(p.contains("private const val KEY_BILLBOARD_ENABLED = \"home_billboard_enabled\""))
        assertTrue(p.contains("private const val KEY_BILLBOARD_FEEDS = \"home_billboard_feeds\""))
        assertTrue(p.contains("private const val KEY_BILLBOARD_CYCLE_MS = \"home_billboard_cycle_ms\""))
        assertTrue(p.contains("private const val KEY_BILLBOARD_TRAILER_MODE = \"home_billboard_trailer_mode\""))
        assertTrue(p.contains("getBoolean(KEY_BILLBOARD_ENABLED, true)"))
        assertTrue(p.contains("getLong(KEY_BILLBOARD_CYCLE_MS, DEFAULT_BILLBOARD_CYCLE_MS)"))
        assertTrue(p.contains("const val BILLBOARD_TRENDING_DAY_FEED = \"trending_day\""))
        assertTrue(p.contains("val BILLBOARD_CYCLE_OPTIONS_MS = listOf(0L, 3_000L, 6_000L, 10_000L, 15_000L)"))
        // Every setter stops at this device (no syncDisplayPrefsBlob).
        val setters = p.substring(
            p.indexOf("fun setHomeBillboardEnabled"),
            p.indexOf("internal fun billboardFeedsFromStored")
        )
        assertTrue(setters.contains("notifyDisplayPrefChanged()"))
        assertFalse(setters.contains("syncDisplayPrefsBlob("))

        val exclusions = sync()
        listOf(
            "\"home_billboard_enabled\"",
            "\"home_billboard_feeds\"",
            "\"home_billboard_cycle_ms\"",
            "\"home_billboard_trailer_mode\""
        ).forEach { key -> assertTrue("$key not excluded from sync", exclusions.contains(key)) }
    }

    @Test
    fun `the manager offers every feed, a hold and a trailer mode`() {
        val dialog = managerDialog()

        assertTrue(dialog.contains("item(key = \"billboard\") { BillboardSectionMobile(rows = rows) }"))
        assertTrue(dialog.contains("TouchChoiceChip( label = \"On\","))
        assertTrue(dialog.contains("TouchChoiceChip( label = \"Off\","))
        // Trending today plus every catalog the manager lists: built-ins, add-on
        // catalogs and built catalogs (collections and browse rows have no items).
        assertTrue(dialog.contains("BILLBOARD_DEFAULT_FEED_LABEL"))
        assertTrue(dialog.contains(".filter { row -> row.config != null || row.isBuiltinRail }"))
        // Deselecting everything restores the default feed.
        assertTrue(dialog.contains("val normalized = next.ifEmpty { AppPreferences.DEFAULT_BILLBOARD_FEEDS }"))
        assertTrue(dialog.contains("AppPreferences.setHomeBillboardFeeds(context, normalized)"))
        // The hold and the trailer mode, each written where the screen reads it.
        assertTrue(dialog.contains("AppPreferences.BILLBOARD_CYCLE_OPTIONS_MS.forEach"))
        assertTrue(dialog.contains("AppPreferences.setHomeBillboardCycleMs(context, option)"))
        assertTrue(dialog.contains("BillboardTrailerMode.entries.forEach"))
        assertTrue(dialog.contains("AppPreferences.setHomeBillboardTrailerMode(context, mode)"))
        // No second delay/mute control: those live in Settings.
        assertFalse(dialog.contains("setHeroTrailerDelayMs"))
        assertFalse(dialog.contains("setHeroTrailerMuted"))
    }

    @Test
    fun `the default feed is TMDB's day pair, not the week pair`() {
        val api = tmdbApi()

        assertTrue(api.contains("@GET(\"trending/movie/day\")"))
        assertTrue(api.contains("@GET(\"trending/tv/day\")"))
        assertTrue(api.contains("getTrendingMoviesDayDiscover"))
        assertTrue(api.contains("getTrendingTvDayDiscover"))
        // The screen's default sentinel is the day feed, and its default value
        // is that sentinel (checked here rather than in a Context-bound test).
        assertTrue(AppPreferences.BILLBOARD_TRENDING_DAY_FEED == "trending_day")
    }
}
