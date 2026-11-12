package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotlight's hero scrolls away with the rails, and the panel actually opens
 * (spec 2026-11-12).
 *
 * The first Spotlight fix made the hero a fixed banner above the list and the
 * panel's dwell a key on the snapshot object. Both were wrong in the field: the
 * hero is meant to be the TOP OF THE LIST - it leaves as the viewer goes down
 * and comes back as they return - and a snapshot that is copied whenever a card
 * moves (a scroll, the hero landing, a feed re-resolve) restarted the two-second
 * dwell on every copy, so no card ever reached it.
 *
 * These pins are about STRUCTURE - which list the hero is in, which key the
 * dwell watches, which press reaches the top bar - because that is what the
 * screen cannot check for itself without a device. The arithmetic (the hero's
 * 52% and the panel's delay) is already pinned by SpotlightHomeContractTest and
 * HomeExpandDelay's own tests.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../SpotlightScrollHeroContractTest.kt, then
 * JUnitCore with -Duser.dir at the repository root.
 */
class SpotlightScrollHeroContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /** Whitespace collapsed: these pins are about shape, not layout of the source. */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    private fun home(): String = flat(source(HOME))
    private fun section(): String = flat(source(SECTION))
    private fun preview(): String = flat(source(PREVIEW))

    // ── (a) the hero is the list's first item, not a Column child ───────────

    @Test
    fun `the hero is the rails list's first item and no longer a Column child`() {
        assertTrue(
            "the item needs a stable key the landing math can find it by",
            source(SECTION).contains("internal const val SPOTLIGHT_HERO_ITEM_KEY")
        )
        val h = home()
        assertEquals(
            "the hero is drawn exactly once - as a list item",
            1,
            Regex("SpotlightHeroSection\\(").findAll(h).count()
        )
        val heroItemAt = h.indexOf("key = SPOTLIGHT_HERO_ITEM_KEY")
        val spacerAt = h.indexOf("item(key = \"hero_spacer\"")
        assertTrue("the hero item must exist", heroItemAt > 0)
        assertTrue(
            "and it must come before the hero spacer, so it is the list's first row",
            spacerAt > heroItemAt
        )
        assertTrue(
            "the Column branch keeps only the focus work, not the hero",
            between(h, "if (spotlightActive) {", "} else HomeHeroHost(")
                .let { branch -> !branch.contains("SpotlightHeroSection(") }
        )
    }

    // ── (b) the pill effect is a first-entry one-shot ───────────────────────

    @Test
    fun `the pill request is gated to first entry and stands down for a restore`() {
        val s = section()
        assertTrue(
            "the effect obeys the entry latch instead of firing on every composition",
            s.contains("LaunchedEffect(pages.isNotEmpty(), claimEntryFocus)") &&
                s.contains("if (!claimEntryFocus || pages.isEmpty()) return@LaunchedEffect")
        )
        val h = home()
        assertTrue(
            "and the Home closes the latch the moment the request is taken",
            h.contains("claimEntryFocus = !spotlightEntryFocusClaimed &&") &&
                h.contains("onEntryFocusClaimed = { spotlightEntryFocusClaimed = true }")
        )
        assertTrue(
            "the viewer's own first press closes it too",
            h.contains("!viewerMovedFocusSinceEntry &&")
        )
        assertTrue(
            "and a return from Detail never claims it: the remembered card owns " +
                "the landing there",
            h.contains("isFreshEntry")
        )
    }

    // ── (c) the hero's space is reserved before the first page lands ────────

    @Test
    fun `a placeholder of the hero's height holds its space until it settles`() {
        val h = home()
        val item = between(h, "key = SPOTLIGHT_HERO_ITEM_KEY", "item(key = \"hero_spacer\"")
        assertTrue(
            "the item's box is the hero's height, whether or not the feed answered",
            item.contains(".height(spotlightHeroItemHeight)")
        )
        assertTrue(
            "and the billboard still draws nothing until a page has settled - the " +
                "box, not the component, is what reserves the space",
            section().contains("if (!billboardEnabled || pages.none { page -> page.settled }) return")
        )
        assertTrue(
            "one height for the item, the placeholder and the billboard",
            section().contains("internal fun spotlightHeroHeight(screenHeightDp: Int): Dp")
        )
    }

    // ── (d) short UP reaches the hero's pills, hold-UP still opens the bar ──

    @Test
    fun `short Up returns to the hero pills and hold Up still opens the bar`() {
        val h = home()
        val hook = between(h, "fun topRailUpHook(entryIndex: Int)", "One pinned Library list")
        assertTrue("the hook exists", hook.isNotEmpty())
        assertTrue(
            "in Spotlight the first rail must NOT open the top bar on a short Up",
            hook.contains("spotlightActive -> {") &&
                !hook.contains("spotlightActive -> { requester")
        )
        assertTrue(
            "it brings the hero item back and lands on its Play pill",
            hook.contains("railListState.animateScrollToItem(0)") &&
                hook.contains("spotlightHeroPillRequester.requestFocus()")
        )
        assertTrue(
            "one requester for that pill, owned by the Home - the hero item is not " +
                "composed while it is scrolled away, so the request cannot live in it",
            h.contains("val spotlightHeroPillRequester = remember { FocusRequester() }") &&
                h.contains("pillFocusRequester = spotlightHeroPillRequester")
        )
        assertTrue(
            "hold-Up stays: the list's own hold hook is still applied",
            h.contains("rememberHomeTopBarHoldUpHook(") &&
                h.contains("onOpenTopBar = { target -> openTopBarFromHold(target) }")
        )
    }

    // ── (e) the landing inset is dynamic ───────────────────────────────────

    @Test
    fun `the landing sits under the hero on entry and at the panel top once scrolled`() {
        val h = home()
        val spec = between(h, "val railRowsBringIntoViewSpec", "val railCardsBringIntoViewSpec")
        assertTrue("the spec exists", spec.isNotEmpty())
        assertTrue(
            "it reads the hero item's bottom edge from the live list",
            spec.contains("item.key == SPOTLIGHT_HERO_ITEM_KEY") &&
                spec.contains("item.offset + item.size")
        )
        assertTrue(
            "on entry only: a fresh entry nobody has pressed in, at the list's start",
            spec.contains("isFreshEntry") &&
                spec.contains("!viewerMovedFocusSinceEntry") &&
                spec.contains("!railListState.canScrollBackward")
        )
        assertTrue(
            "and the hero's bottom is a FLOOR, not a replacement for the header floor",
            spec.contains("floorPx = max(headerFloorPx, heroFloorPx)")
        )
        assertTrue(
            "which is the rule the landing function already applies",
            spec.contains("railLandingTarget(")
        )
    }

    // ── (f) CW and Upcoming cards report through the same card ─────────────

    @Test
    fun `the Continue Watching and Upcoming cards report focus like any rail card`() {
        // Both cards draw through PosterCard, which is the one component that
        // reports to the tracker - so the expand path already sees them. This is
        // the audit's F6(a) claim, checked rather than assumed.
        val h = home()
        val cw = between(h, "private fun CompactUpNextCard(", "private fun ")
        assertTrue("the CW card is a PosterCard", cw.contains("PosterCard("))
        val upcoming = between(h, "private fun UpcomingEpisodeCard(", "private fun ")
        assertTrue("and so is the Upcoming card", upcoming.contains("PosterCard("))
        assertTrue(
            "with the air date the panel's UNAVAILABLE rule needs",
            upcoming.contains("airDate =")
        )
        assertTrue(
            "the reporting itself is PosterCard's",
            preview().let {
                source(POSTER_CARD).contains("LocalSpotlightCardFocus.current") &&
                    source(POSTER_CARD).contains("onCardFocus(")
            }
        )
    }

    @Test
    fun `the landscape card reports too - with it on, no rail could expand`() {
        // F6(a2), the LIVE cause of the 240 field report: with Landscape Posters
        // (or Home Landscape Cards) on, every Home rail draws LandscapeCard
        // instead of PosterCard - and that card reported nothing, so no card on
        // Home could set a snapshot and the dwell never started on any rail.
        val landscape = flat(source(LANDSCAPE_CARD))
        assertTrue(
            "the landscape card reads the Spotlight tracker",
            landscape.contains("LocalSpotlightCardFocus.current")
        )
        assertTrue(
            "reports its focus, its bounds and its own node",
            landscape.contains("onCardFocus(state.isFocused)") &&
                landscape.contains("spotlightFocus.updateBounds(spotlightBounds)") &&
                landscape.contains("spotlightFocus.cardRequester = spotlightCardRequester")
        )
        assertTrue(
            "and hands the panel the art it actually draws - its backdrop",
            landscape.contains("stillUrl = backdropUrl")
        )
        assertTrue(
            "the rail passes the card's own identity with it",
            home().contains("spotlightId = meta.id") &&
                home().contains("spotlightType = meta.type")
        )
    }

    // ── (g) the dwell watches the card, not the snapshot object ────────────

    @Test
    fun `a bounds change repositions the panel without restarting the dwell`() {
        val p = preview()
        assertTrue(
            "the tracker carries a token that changes only when a card takes focus",
            p.contains("var focusToken by mutableIntStateOf(0)") &&
                p.contains("focusToken++")
        )
        assertTrue(
            "and updateBounds copies the snapshot without touching it - a scroll " +
                "moves the panel, it does not re-arm its delay",
            p.contains("fun updateBounds(bounds: Rect)") &&
                between(p, "fun updateBounds(bounds: Rect)", "fun onCardFocus")
                    .let { body -> !body.contains("focusToken") }
        )
        // Repointed by the 2026-11-12 dwell spec: the key is the card's
        // IDENTITY (debounced), not the focus token - a re-entrant focus on the
        // card already held bumped the token and restarted the two seconds.
        assertTrue(
            "the host's effect is keyed on the held card, not on the live snapshot",
            section().contains("LaunchedEffect(heldCard, expandDelayMs, menuOpen)")
        )
        assertFalse(
            "and not on the snapshot object, which is what restarted it",
            section().contains("LaunchedEffect(snapshot, expandDelayMs, menuOpen)")
        )
    }

    // ── (h) the expand delay is re-read after Settings (by construction) ────

    @Test
    fun `the delay is read per Home composition, and Home is disposed by the screen switch`() {
        // The trace the spec asks for: MainActivity switches screens with a
        // `when (current)` over a screen state, so leaving Home (for Settings or
        // Detail) removes the HomeScreen call from the composition entirely - the
        // remembers inside it are recreated, and the pref is read again on return.
        // No nav host keeps the previous destination composed here.
        val main = flat(source(MAIN))
        assertTrue(
            "the app's screen switch is a when over the current screen",
            main.contains("when (current) {")
        )
        assertTrue(
            "with Home one branch of it, so another destination disposes it",
            main.contains("is Screen.Home -> {") && main.contains("HomeScreen(")
        )
        assertFalse(
            "and no nav host is involved",
            main.contains("NavHost(")
        )
        val h = home()
        assertTrue(
            "the delay is a plain remember keyed on the profile - recreated with " +
                "the screen, so a new value applies on the way back",
            h.contains("val spotlightExpandMs by remember(activeProfileId) {")
        )
        assertFalse(
            "not rememberSaveable, which would survive the disposal and keep the old value",
            h.contains("rememberSaveable { mutableLongStateOf(")
        )
        assertTrue(
            "the finding is stated where the value is read",
            h.contains("re-read after Settings")
        )
    }

    // ── (i) a scrolled-away hero stops its trailer ────────────────────────

    @Test
    fun `a hero that leaves composition reports its trailer stopped`() {
        val mobile = flat(source(MOBILE))
        assertTrue(
            "the billboard drops its playing flag on dispose, so a hero scrolled " +
                "out of the list cannot keep playing unseen",
            mobile.contains("DisposableEffect(Unit) { onDispose { onTrailerPlayingChange(false) } }")
        )
        assertTrue(
            "and the Home hands that signal to the screensaver's hold",
            home().contains("onTrailerPlayingChange = { playing ->") &&
                home().contains("spotlightBillboardTrailerPlaying = playing")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SECTION = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
        const val LANDSCAPE_CARD = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
