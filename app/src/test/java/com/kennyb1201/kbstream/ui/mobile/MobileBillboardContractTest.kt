package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import org.junit.Assert.assertEquals
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

    /**
     * The body of the first `{ … }` after [marker], brace-matched, so a test can
     * say what a collapsed block does and does not contain rather than only
     * where its text sits.
     */
    private fun blockOf(text: String, marker: String): String {
        val markerAt = text.indexOf(marker)
        assertTrue("marker not found: $marker", markerAt >= 0)
        val open = text.indexOf('{', markerAt)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced block after $marker")
    }

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
        // Top Picks holds BLEND PICKS, not rail items, so its entry is mapped
        // through the same preview mapper its cards use - feeding the raw
        // picks would be a type error and a second id spelling.
        assertTrue(
            home.contains(
                "KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU, topPicks.map { pick -> " +
                    "mobileBlendPickPreview(pick) }"
            )
        )
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
    fun `manual navigation is the swipe and the progress segments`() {
        val home = mobileHome()

        // The chevrons are GONE (spec 2.7): on a phone they sat on the artwork
        // and collided with the clearlogo, so the two ways a thumb actually
        // navigates - the swipe and the segments - have the job alone. This is
        // the spec's "no chevron in the mobile composition", pinned rather than
        // left to the eye on a device.
        assertFalse("no chevron composable", home.contains("BillboardChevron"))
        assertFalse("no previous chevron", home.contains("description = \"Previous\""))
        assertFalse("no next chevron", home.contains("description = \"Next\""))

        // The segmented bar kept the dots' navigation: a segment jumps to its
        // own page and names the item it goes to.
        assertTrue(home.contains("pagerState.animateScrollToPage(index)"))
        assertTrue(home.contains("contentDescription = page.meta.name"))
        assertTrue(home.contains("segment = index,"))
    }

    // ── the premium refresh (spec 2.7) ───────────────────────────────

    @Test
    fun `the hero is a rounded card with its pills and its bar on the art`() {
        val home = mobileHome()
        val hero = home.substring(
            home.indexOf("private fun MobileHero("),
            home.indexOf("private fun MobileBillboard(")
        )
        val action = home.substring(
            home.indexOf("private fun BillboardAction("),
            home.indexOf("private fun Rail.billboardFeedKey()")
        )

        // The frame: 16dp off the screen's edge, clipped to 20dp, so the page's
        // own background shows round the hero and the sweep, the scrim and the
        // trailer are all cut by the same radius.
        assertTrue(home.contains("// The frame (spec 2.7): 16dp off the screen's edge"))
        assertTrue(home.contains(".clip(RoundedCornerShape(20.dp))"))

        // The pills are DRAWN, not plated: a solid accent fill for Play, a
        // translucent glass fill with a hairline for the secondaries. No KBCard
        // anywhere in the action - its plate is the app's surface fill, which
        // on a backdrop is no plate at all (the pre-refresh screenshot's bare
        // Play/Details text).
        assertTrue(action.contains("val contentColor = if (accent) KBVoid else KBTextHi"))
        assertTrue(action.contains("if (accent) KBAccent else KBTextHi.copy(alpha = 0.16f)"))
        assertTrue(
            action.contains("if (accent) Color.Transparent else KBTextHi.copy(alpha = 0.30f)")
        )
        assertTrue(action.contains(".clip(CircleShape)"))
        assertTrue(action.contains(".height(42.dp)"))
        assertTrue(action.contains(".padding(horizontal = 18.dp)"))
        assertTrue("the house press scale", action.contains("KB_PRESS_SPRING"))
        assertFalse("the plate must be gone", action.contains("KBCard("))

        // ...inside the image, under the meta line, riding the page's own
        // reveal, and only on the page in front.
        assertTrue(
            home.contains(
                "actions = if (index == pagerState.currentPage) pageActions else null"
            )
        )
        assertTrue(hero.contains("actions?.let { pills ->"))
        assertTrue(hero.contains("pills()"))
        assertTrue(
            "the column clears the segments overlay",
            hero.contains("padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)")
        )

        // The description is gone from a hero page, the meta line is near-white,
        // and the scrim is lighter because the controls now live on it.
        assertFalse(hero.contains("meta.description?.takeIf"))
        assertTrue(hero.contains("color = KBTextHi.copy(alpha = 0.90f)"))
        assertTrue(hero.contains("KBVoid.copy(alpha = 0.30f)"))
        assertTrue(hero.contains("KBVoid.copy(alpha = 0.85f)"))
        assertFalse("the heavy scrim stops must be gone", hero.contains("0.94f"))

        // The segments overlay the image's bottom edge, and the count the visible
        // "N of M" line used to draw is announced by the row instead.
        assertTrue(home.contains(".align(Alignment.BottomCenter)"))
        assertTrue(home.contains("padding(start = 16.dp, end = 16.dp, bottom = 10.dp)"))
        assertTrue(
            home.contains("\"Page \${pagerState.currentPage + 1} of \${pages.size}\"")
        )
        assertFalse(
            "the visible counter Text must be deleted",
            home.contains("text = \"\${pagerState.currentPage + 1} of \${pages.size}\"")
        )
    }

    // ── the Ken Burns look (spec 2.6) ────────────────────

    @Test
    fun `the backdrop sweeps on a layer and the bar fills from the same value`() {
        val home = mobileHome()

        // ONE animation drives the sweep and the current segment's fill, which
        // is what makes "they pause together and resume from the held
        // fraction" true by construction rather than by two clocks agreeing.
        assertTrue(home.contains("val sweepProgress = remember { Animatable(0f) }"))
        assertTrue(
            home.contains(
                "val totalMs = if (cycleMs > 0L) cycleMs else BILLBOARD_KEN_BURNS_MANUAL_MS"
            )
        )
        assertTrue(home.contains("progress = sweepProgress.value"))
        assertTrue(home.contains("animated = animateFill"))

        // The sweep is the image layer's transform only: no re-measure of the
        // pager, and the scrim/clearlogo/copy above it do not move.
        assertTrue(home.contains("remember(index, cycleMs) { billboardSweep(index, cycleMs) }"))
        assertTrue(home.contains("val sweep = backdropSweep ?: return@graphicsLayer"))
        assertTrue(home.contains("translationX = ("))

        // Paused by exactly the hold's conditions, and resumed - not restarted -
        // from where it stopped: only a PAGE change snaps back to the start.
        assertTrue(home.contains("val sweepPaused = reducedMotion ||"))
        assertTrue(home.contains("if (sweptPage != page) {"))
        assertTrue(home.contains("sweepProgress.stop()"))
        assertTrue(
            home.contains(
                "val remainingMs = ((1f - sweepProgress.value) * totalMs).toInt().coerceAtLeast(1)"
            )
        )

        // The content entrance, once per page entry, replayed on a return.
        assertTrue(home.contains("durationMillis = BILLBOARD_CONTENT_REVEAL_MS"))
        assertTrue(home.contains("contentReveal = { reveal.value }"))

        // Animations off (animator duration scale 0) : static backdrop, no
        // entrance, segments showing position only.
        assertTrue(home.contains("val reducedMotion = rememberReducedMotion()"))
        // A still backdrop, not a slower one - and never a `remember` inside
        // the `if`, which would corrupt the slot table if the setting flipped.
        assertTrue(home.contains("backdropSweep = sweep.takeUnless { reducedMotion }"))
        assertTrue(home.contains("val animateFill = !reducedMotion && cycleMs > 0L"))
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

    /**
     * The feed chips are one chip per catalog, so expanded they buried HOLD and
     * TRAILER far enough down that the billboard's settings were reported
     * missing (field 2026-10-30). They fold away on every open, behind a header
     * that says what is merged - and the two rows somebody actually came for
     * stay beside the shut block, not inside it.
     */
    @Test
    fun `the feed chips fold away on open, and hold and trailer stay outside`() {
        val dialog = managerDialog()

        // The dialog's own rule for a long block (IMPORT above): collapsed on
        // every open, dialog-local, not a pref.
        assertTrue(dialog.contains("var feedsExpanded by remember { mutableStateOf(false) }"))

        val chips = blockOf(dialog, "if (feedsExpanded) {")
        // Expanded, the block is the chip list it always was...
        assertTrue(chips.contains("AppPreferences.BILLBOARD_TRENDING_DAY_FEED in feeds"))
        assertTrue(chips.contains("onClick = { toggleFeed(row.key) }"))
        assertTrue(chips.contains("orderLabel(feeds.indexOf(row.key), shortFeedLabel(row.title))"))
        // ...and nothing else, so the shut section reads BILLBOARD -> FEED ->
        // HOLD -> TRAILER with no catalog list in between.
        assertFalse(chips.contains("HOLD —"))
        assertFalse(chips.contains("TRAILER —"))
        assertFalse(chips.contains("BILLBOARD_CYCLE_OPTIONS_MS"))
        assertFalse(chips.contains("BillboardTrailerMode.entries"))

        // The header carries the selection without expanding, keeps the merge
        // wording reachable, and is the thing that opens the block.
        val header = dialog.substring(
            dialog.indexOf("RowLabelMobile(feedSummary)"),
            dialog.indexOf("if (feedsExpanded) {")
        )
        assertTrue(header.contains("Pick several to merge them, in this order"))
        assertTrue(header.contains("if (feedsExpanded) \"CLOSE FEEDS\" else \"PICK FEEDS\""))
        assertTrue(header.contains("onClick = { feedsExpanded = !feedsExpanded }"))
    }

    @Test
    fun `a shut feed block names one pick and counts several`() {
        val titles = mapOf("kb_anime" to "Anime")

        assertEquals(
            "FEED — Trending today",
            billboardFeedSummary(listOf(AppPreferences.BILLBOARD_TRENDING_DAY_FEED), titles)
        )
        assertEquals("FEED — Anime", billboardFeedSummary(listOf("kb_anime"), titles))
        // A key this manager no longer lists still reads as one pick.
        assertEquals("FEED — 1 selected", billboardFeedSummary(listOf("gone"), titles))
        assertEquals(
            "FEED — 3 selected",
            billboardFeedSummary(listOf("trending_day", "kb_anime", "kb_x"), titles)
        )
        assertEquals("FEED — 0 selected", billboardFeedSummary(emptyList(), titles))
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
