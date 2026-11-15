package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-14 scrolling spec's wiring, pinned where a device would be needed.
 *
 * The report was "scrolling is laggy on every screen, on every TV, and smooth on
 * the phone". Nothing in the app could say WHY, so the spec starts with evidence
 * (F1) and then fixes the two causes the code can prove: a decoded-bitmap cache
 * that was being emptied during ordinary browsing (F2), and a focus treatment
 * that rasterizes a blurred shadow per D-pad step at 4K (F4). The Spotlight
 * bounds reporting (F5) is the third.
 *
 * What is pinned here is the part no unit test can press: Compose modifiers, an
 * Application trim callback, and a Compose `LaunchedEffect`. The behaviour they
 * carry is stated in each test's message, and the decision that drives them -
 * capping the UI layer's resolution vs. cutting per-pixel cost - is F3's, taken
 * from the code because a UI-only 1080p cap has no safe API on these boxes (see
 * the commit for the reasoning).
 */
class ScrollSmoothnessContractTest {

    private val app by lazy { read("MainApplication.kt") }
    private val diagnostics by lazy { read("data/reporting/Diagnostics.kt") }
    private val card by lazy { read("ui/components/KBCard.kt") }
    private val posterCard by lazy { read("ui/components/PosterCard.kt") }
    private val landscapeCard by lazy { read("ui/components/LandscapeCard.kt") }
    private val mobileHome by lazy { read("ui/mobile/HomeScreenMobile.kt") }
    private val spotlightHome by lazy { read("ui/home/SpotlightHome.kt") }
    private val homeScreen by lazy { read("ui/home/HomeScreen.kt") }

    // ── F1: the evidence reaches the paste ────────────────────────────────

    @Test
    fun `every completed image request is counted by where its pixels came from`() {
        assertTrue(
            "one global listener, not a per-request one: the counters are what " +
                "separate a warm scroll (memory=) from a cache that is being " +
                "emptied (disk= growing) in a paste",
            app.contains(".eventListener(") &&
                app.contains("ImageLoadTrace.noteImage(") &&
                app.contains("coil3.decode.DataSource.MEMORY_CACHE ->") &&
                app.contains("coil3.decode.DataSource.DISK ->") &&
                app.contains("coil3.decode.DataSource.NETWORK ->")
        )
        assertTrue(
            "and the decoded PIXELS are what it records, so a 3840-wide decode " +
                "on a rail tile is visible instead of implied",
            app.contains("width = result.image.width") &&
                app.contains("height = result.image.height")
        )
    }

    @Test
    fun `the paste names the framebuffer, the cache and the loads`() {
        assertTrue(
            "F1: three lines beside the memory line, because that is where a " +
                "reader of the paste looks for this",
            diagnostics.contains("report.appendLine(displayLine(app))") &&
                diagnostics.contains("imageCacheLine(app)?.let { report.appendLine(it) }") &&
                diagnostics.contains(
                    "ImageLoadTrace.lines().forEach { report.appendLine(\"image: \$it\") }"
                )
        )
        assertTrue(
            "the rendered size and the panel mode are reported SEPARATELY: a 4K " +
                "panel upscaling a 1080p UI and a 4K UI on a 4K panel are " +
                "different facts and only one of them is four times the fill-rate",
            diagnostics.contains("val ui = context.resources.displayMetrics") &&
                diagnostics.contains(
                    "manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode"
                ) &&
                diagnostics.contains("display: ui=")
        )
    }

    // ── F2: the cache survives a browse ───────────────────────────────────

    @Test
    fun `the bitmap cache is dropped at real pressure only`() {
        val trim = app.substringAfter("override fun onTrimMemory(level: Int) {")
            .substringBefore("private fun trimLevelName")
        val critical = trim.indexOf("ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL")
        // F4 of the 2026-11-15 scrolling round-two spec: CRITICAL trims HALF the
        // bitmap cache (biggest first) instead of clearing it. The full clear is
        // onLowMemory now - the level that means the process is about to die.
        val graded = trim.indexOf("trimImageMemoryCache(this)")
        assertTrue("the graded trim is under the critical guard", graded > 0)
        assertTrue(
            "F2: RUNNING_LOW is a hint, and on these boxes it fires during " +
                "ordinary browsing - releasing ~40 MB of decoded art there is the " +
                "scroll/wipe/re-decode loop the field reports as jank. The " +
                "release now waits for RUNNING_CRITICAL",
            critical in 0 until graded
        )
        assertTrue(
            "and CRITICAL must not clear the whole cache any more: that is what " +
                "blanked the rail being scrolled",
            !trim.contains("releaseImageMemoryCache(this)")
        )
        assertTrue(
            "the level is still recorded, which is the evidence that a wipe " +
                "happened and when",
            trim.contains("ImageLoadTrace.noteTrim(trimLevelName(level))")
        )
        assertTrue(
            "and onLowMemory still hands everything back: that really is the end",
            app.contains("override fun onLowMemory() {") &&
                app.substringAfter("override fun onLowMemory() {")
                    .substringBefore("override fun onTrimMemory")
                    .contains("releaseImageMemoryCache(this)")
        )
    }

    @Test
    fun `the cache is sized to the heap it lives in, and the 64 MB cap stays`() {
        assertTrue(
            "25% of the reported class, so a 256 MB-class box reaches the cap " +
                "instead of stopping at 38 MB - which is what made a Home screen " +
                "plus a Detail page turn the cache over mid-scroll",
            app.contains("heapBytes * 25 / 100")
        )
        assertTrue(
            "the cap that replaced the 150 MB blowout is untouched",
            app.contains("coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)")
        )
    }

    // ── F4: the focus step is cheap while the viewer is moving ────────────

    @Test
    fun `the glow waits for the focus to settle, and is not removed`() {
        assertTrue(
            "the deferred half: the shadow is a blurred, re-rasterized layer per " +
                "focus step, so it arms only after the focus has stopped",
            card.contains("private const val FOCUS_GLOW_SETTLE_MS = 220L") &&
                card.contains("delay(FOCUS_GLOW_SETTLE_MS)") &&
                card.contains("glowArmed = true")
        )
        assertTrue(
            "and while it is not armed the elevation is ZERO - no shadow at all, " +
                "not a cheaper one",
            card.contains("elevation = if (glowArmed) KBFocusGlow else 0.dp")
        )
        assertTrue(
            "at rest the look is today's: the same accent glow, the same scale",
            card.contains("Glow(") &&
                card.contains("elevationColor = KBAccent") &&
                card.contains("focusedScale = focusedScale") &&
                card.contains(".onFocusChanged { focused = it.isFocused }")
        )
    }

    @Test
    fun `the Ken Burns sweep holds while a scroll is in flight`() {
        assertTrue(
            "the billboard takes the scroll as a LAMBDA: read at the call site it " +
                "would recompose the whole Home on every scroll start and stop",
            mobileHome.contains("scrollHeld: () -> Boolean = { false },")
        )
        assertTrue(
            "and it is part of the same pause list as the pager and the trailer",
            mobileHome.contains("scrollHeld() ||")
        )
        assertTrue(
            "both callers hand it their own list state - the phone's page list " +
                "and the TV's rails list",
            mobileHome.contains("scrollHeld = { listState.isScrollInProgress },") &&
                spotlightHome.contains("scrollHeld = scrollHeld,") &&
                homeScreen.contains("scrollHeld = { railListState.isScrollInProgress },")
        )
        assertFalse(
            "the sweep's duration is NOT touched: the 10s pace is the look",
            mobileHome.contains("BILLBOARD_KEN_BURNS_MANUAL_MS =")
        )
    }

    // ── F5: bounds on focus, not on every layout ──────────────────────────

    @Test
    fun `Spotlight bounds are measured on layout but reported on focus`() {
        listOf("poster card" to posterCard, "landscape card" to landscapeCard)
            .forEach { (name, src) ->
                assertTrue(
                    "$name: the measurement writes a plain field, so a scrolling " +
                        "rail no longer invalidates every card per frame",
                    src.contains("spotlightBounds.rect = coordinates.boundsInRoot()") &&
                        src.contains("val spotlightBounds = remember { CardBoundsHolder() }")
                )
                assertTrue(
                    "$name: and the snapshot is built from it when the focus arrives",
                    src.contains("spotlightBounds.rect") &&
                        src.contains("spotlightFocus.updateBounds(spotlightBounds.rect)")
                )
                assertFalse(
                    "$name: no per-layout state write survives - that is the cost " +
                        "F5 removes",
                    src.contains("mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)")
                )
            }
        assertTrue(
            "the holder itself says why it is not Compose state",
            posterCard.contains("internal class CardBoundsHolder")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun read(relative: String): String =
        normalized(
            stripComments(
                File(findMainSourceRoot(), "com/kennyb1201/kbstream/$relative").readText()
            )
        )

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
