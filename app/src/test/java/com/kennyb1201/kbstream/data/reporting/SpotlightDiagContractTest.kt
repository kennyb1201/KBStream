package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The billboard / Spotlight block in COPY DIAGNOSTICS (spec 2026-11-12).
 *
 * Two open TV reports - a billboard that vanishes, cards that never expand on a
 * two-second dwell - were unanswerable from a paste that carried no billboard
 * lines at all. The trace itself has no Android dependency, so the shape the
 * paste depends on is tested directly; the write points and the read point are
 * pinned by source.
 */
class SpotlightDiagContractTest {

    @Before
    fun resetTrace() = SpotlightDiagTrace.reset()

    // ── S3/S4: an untouched trace writes nothing ──────────────────────────

    @Test
    fun `a device that never opened Spotlight adds no lines`() {
        assertEquals(emptyList<String>(), SpotlightDiagTrace.summaries())
        assertEquals(emptyList<String>(), SpotlightDiagTrace.lines())
    }

    // ── F2 (render-path spec): the gate inputs ───────────────────────────

    @Test
    fun `the billboard summary names the render gate ahead of the pool`() {
        SpotlightDiagTrace.billboardGate(enabled = true, layout = "spotlight", spotlightActive = true)
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)
        SpotlightDiagTrace.billboardDealt(pages = 20, pool = 20, epoch = 1)

        val lines = SpotlightDiagTrace.summaries()
        val gate = lines.first()
        assertEquals(
            "the gate is the first billboard line, because it explains every other one",
            true,
            gate.startsWith("billboard: gate ")
        )
        assertTrue(gate, gate.contains("billboardEnabled=yes"))
        assertTrue(gate, gate.contains("layout=spotlight"))
        assertTrue(gate, gate.contains("spotlightActive=yes"))

        // The failure this exists for: a settled pool with the layout gate shut.
        SpotlightDiagTrace.billboardGate(enabled = true, layout = "modern", spotlightActive = false)
        val shut = SpotlightDiagTrace.summaries().first()
        assertTrue(shut, shut.contains("layout=modern"))
        assertTrue(shut, shut.contains("spotlightActive=no"))
        assertTrue(
            "and the pool still reads settled beside it - which is the whole point",
            SpotlightDiagTrace.summaries().any { it.contains("dealt=20 settled=0") }
        )
    }

    // ── F1 (follow-up spec): the render line ─────────────────────────────

    @Test
    fun `the render line separates not-in-the-list from scrolled-away from blank`() {
        // B2/B5: the item is in the list but the viewport does not show it.
        SpotlightDiagTrace.billboardRenderList(
            heroVisible = false,
            heroIndex = null,
            heroHeightDp = null,
            viewportHeightDp = 720,
            firstIndex = 3,
            firstOffset = 120,
            itemCount = 21
        )
        SpotlightDiagTrace.billboardRenderHero(
            composed = true,
            gate = null,
            pageKey = "series:tt1",
            art = "borrowed",
            settled = 20,
            total = 20
        )
        val line = SpotlightDiagTrace.summaries().first { it.contains("render ") }
        assertTrue("the list half: $line", line.contains("list=hero-not-visible"))
        assertTrue("where the list rests: $line", line.contains("first=#3+120px items=21"))
        assertTrue("the section half: $line", line.contains("section=composed page=series:tt1"))
        assertTrue("what art it has: $line", line.contains("art=borrowed settled=20/20"))

        // B5: present and laid out, with a measured height against the viewport.
        SpotlightDiagTrace.billboardRenderList(
            heroVisible = true,
            heroIndex = 0,
            heroHeightDp = 374,
            viewportHeightDp = 720,
            firstIndex = 0,
            firstOffset = 0,
            itemCount = 21
        )
        val shown = SpotlightDiagTrace.summaries().first { it.contains("render ") }
        assertTrue(shown, shown.contains("list=hero#0 374dp/720dp"))

        // B2: the section never composed, and which gate returned.
        SpotlightDiagTrace.billboardRenderHero(
            composed = false,
            gate = "!settled",
            pageKey = null,
            art = null,
            settled = 0,
            total = 20
        )
        val gated = SpotlightDiagTrace.summaries().first { it.contains("render ") }
        assertTrue(gated, gated.contains("section=gated (!settled)"))
    }

    @Test
    fun `the render line sits right after the gate and before the pool`() {
        SpotlightDiagTrace.billboardGate(enabled = true, layout = "spotlight", spotlightActive = true)
        SpotlightDiagTrace.billboardRenderList(true, 0, 374, 720, 0, 0, 21)
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)
        val kinds = SpotlightDiagTrace.summaries().map { it.removePrefix("billboard: ").substringBefore(" ") }
        assertEquals(listOf("gate", "render", "feed"), kinds)
    }

    // ── S1: empty feed vs never settled ──────────────────────────────────

    @Test
    fun `the billboard summary separates an empty feed from pages that never settled`() {
        SpotlightDiagTrace.billboardFeed(emptyList(), trendingDaySize = 0, merged = 0)
        SpotlightDiagTrace.billboardDealt(pages = 0, pool = 0, epoch = 3)
        SpotlightDiagTrace.billboardCleared("empty feed", epoch = 3)

        val summary = SpotlightDiagTrace.summaries()
        assertTrue(summary.all { it.startsWith("billboard: ") })
        val feed = summary.first { it.contains("feed ") }
        assertTrue("the selected feeds are named: $feed", feed.contains("selected=(none)"))
        assertTrue("an empty trending feed is named as empty: $feed", feed.contains("trendingDay=empty"))
        assertTrue("the merged pool is counted: $feed", feed.contains("merged=0"))
        val pages = summary.first { it.contains("pages ") }
        assertTrue("dealt and settled are both there: $pages", pages.contains("dealt=0 settled=0"))
        val clear = summary.first { it.contains("clear ") }
        assertTrue("the reason and epoch travel: $clear", clear.contains("reason=empty feed"))
        assertTrue(clear.contains("epoch=3"))

        // The other half of the question: a feed that DID deal pages but had
        // some of them resolve to null - the pages that never settle.
        SpotlightDiagTrace.reset()
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)
        SpotlightDiagTrace.billboardDealt(pages = 3, pool = 20, epoch = 4)
        SpotlightDiagTrace.billboardPageResolved("movie:1", settledNow = 1, total = 3)
        SpotlightDiagTrace.billboardPageNull("show:2")

        val second = SpotlightDiagTrace.summaries().joinToString("\n")
        assertTrue(second.contains("trendingDay=size=20"))
        assertTrue(second.contains("dealt=3 settled=1"))
        assertTrue("the null count is the diagnosis: $second", second.contains("resolved=1 null=1 of 3"))
        assertTrue(
            "and the unsettled page is named in the transitions",
            SpotlightDiagTrace.lines().any { it.contains("resolve null show:2") }
        )
    }

    // ── 2026-11-16: the walk's own evidence, and the resolve slot ─────────

    @Test
    fun `the walk records raw and survived per page, and why it stopped`() {
        SpotlightDiagTrace.billboardWalkStart()
        SpotlightDiagTrace.billboardWalkPage(
            page = 1,
            moviesFetched = 20,
            moviesKept = 0,
            showsFetched = 20,
            showsKept = 1,
            pool = 1
        )
        SpotlightDiagTrace.billboardWalkPage(
            page = 2,
            moviesFetched = 20,
            moviesKept = 0,
            showsFetched = 0,
            showsKept = 0,
            pool = 1
        )
        SpotlightDiagTrace.billboardWalkEnded("empty pages", pages = 2, pool = 1)

        val walk = SpotlightDiagTrace.summaries().first { it.contains("walk ") }
        assertTrue(walk, walk.contains("pages=2"))
        assertTrue(
            "what came back and what the ceiling kept, page by page: $walk",
            walk.contains("p1 m20>0 s20>1") && walk.contains("p2 m20>0 s0>0")
        )
        assertTrue("the pool and the stop reason travel: $walk", walk.contains("pool=1 end=empty pages"))

        val transitions = SpotlightDiagTrace.lines().joinToString("\n")
        assertTrue(
            "and each page is its own transition: $transitions",
            transitions.contains("walk page=1 movies=20->0 shows=20->1 pool=1")
        )
        assertTrue(transitions.contains("walk end reason=empty pages pages=2 pool=1"))
    }

    @Test
    fun `a failed page is traced as -1, never as an empty one`() {
        SpotlightDiagTrace.billboardWalkStart()
        SpotlightDiagTrace.billboardWalkPage(
            page = 1,
            moviesFetched = -1,
            moviesKept = 0,
            showsFetched = 20,
            showsKept = 0,
            pool = 0
        )
        SpotlightDiagTrace.billboardWalkEnded("null page", pages = 1, pool = 0)
        val walk = SpotlightDiagTrace.summaries().first { it.contains("walk ") }
        assertTrue(walk, walk.contains("p1 m-1>0 s20>0"))
        assertTrue(walk, walk.contains("end=null page"))
    }

    @Test
    fun `a fresh deal rewrites the resolve slot`() {
        SpotlightDiagTrace.billboardDealt(pages = 16, pool = 20, epoch = 1)
        repeat(16) { i ->
            SpotlightDiagTrace.billboardPageResolved("movie:$i", settledNow = i + 1, total = 16)
        }
        assertTrue(
            SpotlightDiagTrace.summaries().first { it.contains("resolve ") }.contains("resolved=16")
        )

        // The next deal is empty (another profile, a stricter ceiling): the slot
        // must not still claim the previous deal's sixteen.
        SpotlightDiagTrace.billboardDealt(pages = 0, pool = 0, epoch = 2)
        val line = SpotlightDiagTrace.summaries().first { it.contains("resolve ") }
        assertTrue(line, line.contains("resolved=0 null=0 of 0"))
    }

    @Test
    fun `a failed resolve job carries the exception message, not a stack`() {
        SpotlightDiagTrace.billboardDealt(pages = 2, pool = 5, epoch = 1)
        SpotlightDiagTrace.billboardJobFailed("HTTP 429 from tmdb")
        val line = SpotlightDiagTrace.summaries().first { it.contains("resolve ") }
        assertTrue(line, line.contains("failed=\"HTTP 429 from tmdb\""))
        assertFalse("no stack frames in the paste", line.contains("\tat "))
    }

    // ── S2: the last snapshot survives the blur ───────────────────────────

    @Test
    fun `the spotlight summary still names the held card after it blurred`() {
        SpotlightDiagTrace.spotlightDwell(
            expandDelayMs = 2_000L,
            menuOpen = false,
            token = 7,
            card = "movie:550"
        )
        SpotlightDiagTrace.spotlightClaimGate(claimEntryFocus = true)
        SpotlightDiagTrace.spotlightFocused(token = 7, key = "movie:550", title = "Fight Club")
        SpotlightDiagTrace.spotlightClaim(false)
        SpotlightDiagTrace.spotlightBlurred()

        val summary = SpotlightDiagTrace.summaries()
        assertTrue(summary.all { it.startsWith("spotlight: ") })
        val tracker = summary.first { it.contains("tracker ") }
        assertTrue("focus has left: $tracker", tracker.contains("held=no"))
        assertTrue("but the last card is kept: $tracker", tracker.contains("Fight Club"))
        assertTrue("with its key: $tracker", tracker.contains("movie:550"))
        assertTrue("and its token: $tracker", tracker.contains("focusToken=7"))
        assertTrue("never expanded for it: $tracker", tracker.contains("expanded=no"))

        val dwell = summary.first { it.contains("dwell ") }
        assertTrue("the live delay: $dwell", dwell.contains("delay=2000ms"))
        assertTrue("the menu state: $dwell", dwell.contains("menuOpen=no"))
        assertTrue("the entry claim flag: $dwell", dwell.contains("claimEntryFocus=yes"))
        assertTrue("and the claim's outcome: $dwell", dwell.contains("lastClaim=not-taken"))
    }

    @Test
    fun `a blur after an expand says how long after it came`() {
        SpotlightDiagTrace.spotlightFocused(1, "movie:1", "Heat")
        SpotlightDiagTrace.spotlightBlurred()
        assertEquals(
            "with no panel up there is no expand to date it against",
            "spotlight: blur (last snapshot kept)",
            SpotlightDiagTrace.lines().last()
        )

        SpotlightDiagTrace.reset()
        SpotlightDiagTrace.spotlightFocused(1, "movie:1", "Heat")
        SpotlightDiagTrace.spotlightExpanded("Heat")
        SpotlightDiagTrace.spotlightBlurred()
        val line = SpotlightDiagTrace.lines().last()
        assertTrue(
            "the number the collapse report is about: $line",
            Regex("""spotlight: blur \(last snapshot kept\) \+\d+ms after expand""")
                .matches(line)
        )
    }

    @Test
    fun `a cancelled dwell names the input that cancelled it`() {
        SpotlightDiagTrace.spotlightDwell(2_000L, menuOpen = false, token = 5, card = "series:tt1")
        SpotlightDiagTrace.spotlightDwellCancelled(
            "hasSnapshot",
            from = "series:tt1",
            to = "none"
        )

        val dwell = SpotlightDiagTrace.summaries().first { it.contains("dwell ") }
        assertTrue("the card it armed on: $dwell", dwell.contains("card=series:tt1"))
        assertTrue("the token is context, not a key: $dwell", dwell.contains("token=5"))
        assertTrue(
            "and the cancellation survives in the summary, with its transition: $dwell",
            dwell.contains("lastCancel=hasSnapshot (series:tt1\u2192none)")
        )
        assertTrue(
            "the ring carries it too, so the sequence is readable",
            SpotlightDiagTrace.lines().any {
                it == "spotlight: dwell cancelled key=hasSnapshot (series:tt1\u2192none)"
            }
        )
    }

    @Test
    fun `the claim latch is one line, not a second one`() {
        SpotlightDiagTrace.spotlightDwell(
            expandDelayMs = 2_000L,
            menuOpen = false,
            token = 7,
            card = "movie:550"
        )
        SpotlightDiagTrace.spotlightClaimGate(false)
        val dwell = SpotlightDiagTrace.summaries().filter { it.contains("dwell ") }
        assertEquals("the latch updates the dwell line in place", 1, dwell.size)
        assertTrue(dwell.first(), dwell.first().contains("claimEntryFocus=no"))
    }

    @Test
    fun `an expand flips the last snapshot's record and names the guard that collapses it`() {
        SpotlightDiagTrace.spotlightFocused(token = 1, key = "movie:1", title = "Heat")
        SpotlightDiagTrace.spotlightExpanded("Heat")
        val tracker = SpotlightDiagTrace.summaries().first { it.contains("tracker ") }
        assertTrue(tracker, tracker.contains("expanded=yes"))

        SpotlightDiagTrace.spotlightCollapsed("menuOpen")
        val panel = SpotlightDiagTrace.summaries().first { it.contains("panel ") }
        assertTrue(panel, panel.contains("expanded=no"))
        assertTrue(panel, panel.contains("guard=menuOpen"))

        SpotlightDiagTrace.spotlightCollapsed("!hasSnapshot")
        val again = SpotlightDiagTrace.summaries().first { it.contains("panel ") }
        assertTrue("the latest guard wins: $again", again.contains("guard=!hasSnapshot"))
    }

    // ── S3: the block stays readable ─────────────────────────────────────

    @Test
    fun `the ring is capped and keeps the newest transitions`() {
        SpotlightDiagTrace.billboardDealt(pages = 1, pool = 1, epoch = 1)
        repeat(40) { i -> SpotlightDiagTrace.spotlightFocused(i, "movie:$i", "Title $i") }
        val lines = SpotlightDiagTrace.lines()
        assertEquals(32, lines.size)
        assertTrue("the newest is last: ${lines.last()}", lines.last().contains("token=39"))
        assertTrue("transitions only, one per event", lines.count { it.contains("focus card=") } == 32)
        assertTrue(
            "every line is prefixed, so the paste reads as one block",
            lines.all { it.startsWith("billboard: ") || it.startsWith("spotlight: ") }
        )
        assertTrue(
            "the summary kinds are fixed, so two pastes read alike",
            SpotlightDiagTrace.summaries().size <= 9
        )
    }

    @Test
    fun `reset clears every store`() {
        SpotlightDiagTrace.billboardDealt(pages = 2, pool = 2, epoch = 1)
        SpotlightDiagTrace.spotlightFocused(1, "movie:1", "Heat")
        SpotlightDiagTrace.reset()
        assertEquals(emptyList<String>(), SpotlightDiagTrace.summaries())
        assertEquals(emptyList<String>(), SpotlightDiagTrace.lines())
    }

    // ── F6: the read point ───────────────────────────────────────────────

    @Test
    fun `the paste prints the block right after upcoming, and nothing else changes`() {
        val diag = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        val upcoming = diag.indexOf("UpcomingDiagTrace.lines().forEach")
        val block = diag.indexOf("SpotlightDiagTrace.summaries().forEach")
        assertTrue("the block exists", block > 0)
        assertTrue("and sits with the Home family, after upcoming", block > upcoming)
        assertTrue(
            diag.contains("SpotlightDiagTrace.lines().forEach { report.appendLine(it) }")
        )
    }

    // ── F4/F5: the write points ──────────────────────────────────────────

    @Test
    fun `the billboard records its feed, pages, nulls, failure and clear reason`() {
        val vm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        listOf(
            "SpotlightDiagTrace.billboardFeed(",
            "SpotlightDiagTrace.billboardDealt(",
            "SpotlightDiagTrace.billboardPageResolved(",
            "SpotlightDiagTrace.billboardPageNull(",
            "SpotlightDiagTrace.billboardJobFailed(",
            "SpotlightDiagTrace.billboardCleared(reason, billboardResolveEpoch)"
        ).forEach { call -> assertTrue("missing $call", vm.contains(call)) }
        assertTrue(
            "the clear names its reason at the signature, so no caller can clear silently",
            vm.contains("fun clearBillboard(reason: String)")
        )
        assertTrue(
            "the empty deal is cleared with its reason",
            vm.contains("billboardCleared(\"empty feed\", dealEpoch)")
        )
        assertTrue(
            "the reason travels from both screens",
            source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt")
                .contains("clearBillboard(\"billboard disabled\")") &&
                source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
                    .contains("clearBillboard(\"billboard disabled\")")
        )
    }

    @Test
    fun `the tracker and the host record focus, claim and the dwell guard`() {
        val preview = source("com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt")
        assertTrue(preview.contains("SpotlightDiagTrace.spotlightFocused("))
        assertTrue(preview.contains("SpotlightDiagTrace.spotlightBlurred()"))
        val bounds = preview.substringAfter("fun updateBounds(bounds: Rect)").substringBefore("fun onCardFocus")
        assertFalse(
            "bounds copies are per-scroll and must never reach the ring",
            bounds.contains("SpotlightDiagTrace")
        )

        val host = source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt")
        assertTrue(host.contains("SpotlightDiagTrace.spotlightDwell("))
        assertTrue(
            "the claim latch is written where the latch lives, and folded into the " +
                "dwell line rather than a second one",
            host.contains("SpotlightDiagTrace.spotlightClaimGate(claimEntryFocus)")
        )
        assertTrue(
            "the claim's outcome is recorded with the frames it spent (F4, 2026-11-13)",
            host.contains("SpotlightDiagTrace.spotlightClaim(landed, frames)")
        )
        assertTrue(host.contains("SpotlightDiagTrace.spotlightExpanded("))
        listOf("\"menuOpen\"", "\"cardChanged\"", "\"!expands\"").forEach { guard ->
            assertTrue("the guard is named: $guard", host.contains(guard))
        }
    }

    // ── F4 (rest-offset spec): the page at rest ──────────────────────────

    @Test
    fun `a rest line reads the offset in hundredths, with a dot`() {
        SpotlightDiagTrace.billboardRest(page = 3, offset = 0f, corrected = false)

        val line = SpotlightDiagTrace.summaries().first { it.contains("billboard: rest ") }
        assertTrue(line, line.contains("page=3"))
        assertTrue("S4 asks for exactly this shape", line.contains("offset=0.00"))
        assertTrue("a paste can already see nothing has been corrected", line.contains("corrections=0"))
        assertTrue(
            "and a settled page is never ALSO a correction",
            SpotlightDiagTrace.lines().none { it.contains("rest corrected") }
        )
    }

    @Test
    fun `a corrected rest keeps the offset it started at, and counts`() {
        SpotlightDiagTrace.billboardRest(page = 7, offset = 0.0312f, corrected = true)

        val line = SpotlightDiagTrace.summaries().first { it.contains("billboard: rest ") }
        assertTrue("the summary shows the correction happened at all", line.contains("corrections=1"))
        assertTrue("and the offset it was taken at", line.contains("offset=0.03"))

        val transition = SpotlightDiagTrace.lines().last()
        assertTrue(
            "the ring keeps the event itself: page and the offset that was put back",
            transition == "billboard: rest corrected page=7 from=0.03"
        )

        // The next settled frame overwrites the summary slot - the evidence has to
        // survive it, or a paste taken a minute later would deny the correction.
        SpotlightDiagTrace.billboardRest(page = 8, offset = 0f, corrected = false)
        val after = SpotlightDiagTrace.summaries().first { it.contains("billboard: rest ") }
        assertTrue(after, after.contains("page=8") && after.contains("corrections=1"))
        assertTrue(
            "the correction line is still in the ring",
            SpotlightDiagTrace.lines().any { it.contains("rest corrected page=7 from=0.03") }
        )
    }

    @Test
    fun `a negative rest offset keeps its sign`() {
        SpotlightDiagTrace.billboardRest(page = 1, offset = -0.02f, corrected = true)
        val line = SpotlightDiagTrace.summaries().first { it.contains("billboard: rest ") }
        assertTrue(
            "a strip at the LEFT edge is the mirror of the reported one - the sign is the " +
                "only thing that tells the reader which edge",
            line.contains("offset=-0.02")
        )
    }

    @Test
    fun `the rest line sits after the render line, before the feed`() {
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)
        SpotlightDiagTrace.billboardRest(page = 2, offset = 0f, corrected = false)
        SpotlightDiagTrace.billboardDealt(pages = 20, pool = 20, epoch = 1)

        val summaries = SpotlightDiagTrace.summaries()
        val rest = summaries.indexOfFirst { it.contains("billboard: rest ") }
        val feed = summaries.indexOfFirst { it.contains("billboard: feed ") }
        assertTrue("both present", rest >= 0 && feed >= 0)
        assertTrue("the frame's rest state reads with the frame, ahead of the pool", rest < feed)
    }

    // ── F4 (first-entry flash spec): the hero's art swaps ────────────────

    @Test
    fun `the first art a page draws is not a swap`() {
        SpotlightDiagTrace.billboardHeroArt(
            page = 0,
            source = "borrowed",
            url = "https://image.example/a.jpg"
        )

        val line = SpotlightDiagTrace.summaries()
            .first { it.contains("billboard: art ") }
        assertTrue(line, line.contains("page=0"))
        assertTrue(line, line.contains("source=borrowed"))
        assertTrue("drawing the first frame is not a swap", line.contains("swaps=0"))
        assertTrue(line, line.contains("session=0"))
        assertTrue("and the per-page table carries it", line.contains("perPage=[0:borrowed/0]"))
        assertTrue(
            "nothing swapped, so the ring stays quiet",
            SpotlightDiagTrace.lines().none { it.contains("art swap") }
        )
    }

    @Test
    fun `the borrowed to own swap counts once and names both sources`() {
        SpotlightDiagTrace.billboardHeroArt(0, "borrowed", "https://image.example/a.jpg")
        SpotlightDiagTrace.billboardHeroArt(0, "backdrop", "https://image.example/b.jpg")

        val line = SpotlightDiagTrace.summaries()
            .first { it.contains("billboard: art ") }
        assertTrue("the page in front now draws its own art", line.contains("source=backdrop"))
        assertTrue("one swap, which is the most a first entry may show", line.contains("swaps=1"))
        assertTrue(line, line.contains("session=1"))
        assertTrue(line, line.contains("perPage=[0:backdrop/1]"))
        assertEquals(
            "the ring keeps the event: which page, from what, to what",
            "billboard: art swap page=0 from=borrowed to=backdrop swaps=1",
            SpotlightDiagTrace.lines().last()
        )
    }

    @Test
    fun `a borrowed art moving under a page is still a swap - the old bug's signature`() {
        SpotlightDiagTrace.billboardHeroArt(0, "borrowed", "https://image.example/a.jpg")
        SpotlightDiagTrace.billboardHeroArt(0, "borrowed", "https://image.example/z.jpg")

        val line = SpotlightDiagTrace.summaries()
            .first { it.contains("billboard: art ") }
        assertTrue(
            "the source never changed, but the IMAGE did - which is exactly what the " +
                "live fallback used to do on every settle: $line",
            line.contains("swaps=1")
        )
        assertEquals(
            "so the transition names borrowed -> borrowed, the flashing signature",
            "billboard: art swap page=0 from=borrowed to=borrowed swaps=1",
            SpotlightDiagTrace.lines().last()
        )
    }

    @Test
    fun `the session count survives a new deal, the per-page table does not`() {
        SpotlightDiagTrace.billboardHeroArt(0, "backdrop", "https://image.example/a.jpg")
        SpotlightDiagTrace.billboardHeroArt(0, "backdrop", "https://image.example/b.jpg")
        assertEquals(
            "one swap so far",
            1,
            SpotlightDiagTrace.lines().count { it.contains("art swap") }
        )

        // A fresh deal: page 0 is a different title, so its page-indexed row is
        // dropped - but the SESSION total is the session's.
        SpotlightDiagTrace.billboardDealt(pages = 20, pool = 20, epoch = 2)
        SpotlightDiagTrace.billboardHeroArt(0, "borrowed", "https://image.example/c.jpg")

        val line = SpotlightDiagTrace.summaries()
            .first { it.contains("billboard: art ") }
        assertTrue("a new deal's first draw is not a swap", line.contains("swaps=0"))
        assertTrue(line, line.contains("session=1"))
        assertTrue(
            "and the table restarted for the new deal's page 0",
            line.contains("perPage=[0:borrowed/0]")
        )
    }

    @Test
    fun `the art line sits beside the render line, before the pool`() {
        SpotlightDiagTrace.billboardGate(enabled = true, layout = "spotlight", spotlightActive = true)
        SpotlightDiagTrace.billboardRenderHero(
            composed = true,
            gate = null,
            pageKey = "movie:1",
            art = "backdrop",
            settled = 1,
            total = 20
        )
        SpotlightDiagTrace.billboardHeroArt(0, "backdrop", "https://image.example/a.jpg")
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)

        val kinds = SpotlightDiagTrace.summaries()
            .map { it.removePrefix("billboard: ").substringBefore(" ") }
        assertEquals(listOf("gate", "render", "art", "feed"), kinds)
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
}
