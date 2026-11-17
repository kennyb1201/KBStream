package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotlight's dwell and its panel wordmark (spec 2026-11-16, Parts A and B).
 *
 * The field report was two symptoms with two causes: cards that only expanded
 * after "scrolling past them and back" (the dwell clock was a single slot wiped
 * by any card change), and a wordmark that was "tiny on all of them and
 * centered" (it filled a two-line `titleSmall` Text instead of a fixed logo box).
 *
 * [SpotlightDwellClock] is plain Kotlin, so the accounting is tested for real:
 * time adds up per card, a pause is not a reset, and a spent wait is forgiven.
 * The parts that only exist on a TV - the effect wiring and the panel's box -
 * are pinned as source, because this host has no Android, TV or emulator.
 */
class SpotlightExpandAndLogoContractTest {

    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt") }
    private val preview by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt") }
    private val screen by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val clock by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightDwellClock.kt") }

    // ── A1/A3: the clock is per card ─────────────────────────────────────

    @Test
    fun `time held adds up per card, across a move to another card`() {
        val clock = SpotlightDwellClock()
        assertEquals("a card starts at zero", 0L, clock.start("A", 1_000L))
        // The viewer reads B for half a second, then comes back to A.
        assertEquals("B has its own clock", 0L, clock.start("B", 2_000L))
        assertEquals(
            "returning to A resumes the second it already earned - the whole " +
                "point of A1: no scrolling past and back to start over",
            1_000L,
            clock.start("A", 2_500L)
        )
        // A run replaced by a rebuild of the SAME card also adds up.
        assertEquals(
            "a re-created card node resumes rather than restarts",
            1_500L,
            clock.start("A", 3_000L)
        )
    }

    @Test
    fun `cards do not share their earned time`() {
        val clock = SpotlightDwellClock()
        clock.start("A", 0L)
        clock.start("B", 2_000L)
        assertEquals("B saw none of A's hold", 0L, clock.earnedMsFor("B"))
        assertEquals("and A keeps all of its own", 2_000L, clock.earnedMsFor("A"))
    }

    @Test
    fun `a pause is not a reset, a spent wait is`() {
        val clock = SpotlightDwellClock()
        clock.start("A", 0L)
        clock.pause(900L)
        assertEquals("the blur banked the time", 900L, clock.earnedMsFor("A"))
        assertFalse("and the run is over", clock.running)

        clock.start("B", 1_000L)
        clock.pause(1_500L)
        clock.spend("B")
        assertEquals(
            "a panel's wait is spent: re-holding that card waits the full delay",
            0L,
            clock.earnedMsFor("B")
        )
        assertEquals("the card that did not open keeps its time", 900L, clock.earnedMsFor("A"))

        clock.spend(null)
        assertEquals("a deliberate close clears everything", 0L, clock.earnedMsFor("A"))
    }

    @Test
    fun `the clock remembers the last few cards and no more`() {
        val clock = SpotlightDwellClock()
        repeat(SpotlightDwellClock.CAPACITY + 3) { index ->
            clock.start("card$index", index * 100L)
        }
        // Bank the run in flight, which is the only way time becomes "earned".
        clock.pause((SpotlightDwellClock.CAPACITY + 3) * 100L)
        assertEquals(
            "the oldest cards are dropped, so a rail of thousands cannot grow it",
            0L,
            clock.earnedMsFor("card0")
        )
        assertTrue(
            "the card just held is remembered",
            clock.earnedMsFor("card${SpotlightDwellClock.CAPACITY + 2}") > 0L
        )
        assertEquals("eight cards is a rail either side of the focused one", 8, SpotlightDwellClock.CAPACITY)
    }

    @Test
    fun `the clock stays pure so it can be tested here`() {
        assertFalse(
            "no Android or Compose import in the accounting",
            clock.lines().any { line ->
                line.startsWith("import ") && !line.contains("java.") && !line.contains("kotlin.")
            }
        )
    }

    // ── A1/A2: the effect wiring ────────────────────────────────────────

    @Test
    fun `a card change banks the time instead of wiping it`() {
        assertTrue(
            "the effect owns one clock, in composition",
            home.contains("val dwellClock = remember { SpotlightDwellClock() }")
        )
        val cardChange = home
            .substringAfter("if (collapseGuard == \"cardChanged\") {")
            .substringBefore("} else {")
        assertTrue(
            "moving to another card BANKS what the card earned (A1)",
            cardChange.contains("dwellClock.pause(")
        )
        assertFalse(
            "and does not spend it, which is what started the wait over",
            cardChange.contains("dwellClock.spend(")
        )
        assertFalse(
            "the old single-slot wipe is gone",
            home.contains("fun resetDwellClock(")
        )
        assertTrue(
            "a snapshot that blinks while the card still holds focus is a pause (A2)",
            home.contains("val armedOn = tracker.snapshot ?: run {") &&
                home.contains("dwellClock.pause(SystemClock.uptimeMillis())")
        )
        assertTrue(
            "the panel's own wait is spent, so a re-hold waits the delay again",
            home.contains("dwellClock.spend(cardNow)")
        )
    }

    @Test
    fun `the dwell is still the viewer's delay, and CW still never expands`() {
        assertTrue(
            "the setting lengths the wait - so the fix is forgiveness, not a shorter dwell (A3)",
            home.contains("delay((expandDelayMs - alreadyHeldMs).coerceAtLeast(0L))") &&
                home.contains("!HomeExpandDelay.expands(expandDelayMs) -> \"!expands\"")
        )
        assertFalse(
            "no new dwell setting was introduced",
            home.contains("dwell_ms") || home.contains("expandDelayMs = 1")
        )
        assertTrue(
            "CW and Upcoming are still reported as non-expandable (A4)",
            screen.contains("spotlightExpandable = false") &&
                screen.contains("spotlightExpandable = false,")
        )
        assertTrue(
            "and the cards still gate on it",
            source("com/kennyb1201/kbstream/ui/components/PosterCard.kt")
                .contains("if (spotlightFocus == null || !spotlightExpandable)")
        )
    }

    // ── B1-B3: the panel's wordmark box ─────────────────────────────────

    @Test
    fun `the panel's logo has the hero's fixed box, left aligned`() {
        assertTrue(
            "the box is the panel's width and the HERO's logo height (B1)",
            preview.contains(".height(HeroLogoHeight)")
        )
        assertTrue(
            "the hero's height is shared rather than re-typed",
            screen.contains("internal val HeroLogoHeight = 82.dp")
        )
        assertTrue(
            "and the wordmark sits at the bottom start, lining up with the meta (B2)",
            preview.contains("contentAlignment = Alignment.BottomStart") &&
                preview.contains(".align(Alignment.BottomStart)")
        )
        assertFalse(
            "never centred in the panel's full width - the field photo's bug",
            preview.contains("Modifier.matchParentSize()")
        )
        assertTrue(
            "the logo is drawn in the hero's own box width, not the whole panel",
            preview.contains(".width(HeroLogoWidth)")
        )
    }

    @Test
    fun `the title text stays as the fallback and never moves the copy`() {
        assertTrue(
            "the title Text is still composed inside the logo's box (B3)",
            preview.contains("text = titleText") &&
                preview.contains("if (logoPainted) Color.Transparent else KBTextHi")
        )
        assertTrue(
            "a missing or failed mark keeps showing the title",
            preview.contains("showPlaceholder = false") &&
                preview.contains("if (settledLogo != null)")
        )
        assertTrue(
            "and the logo still FITS rather than crops (B3)",
            screen.contains("contentScale = ContentScale.Fit")
        )
        assertTrue(
            "the hero's own logo box is untouched (B4)",
            screen.contains(".width(heroLogoWidth)") &&
                screen.contains(".height(heroLogoHeight)")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
