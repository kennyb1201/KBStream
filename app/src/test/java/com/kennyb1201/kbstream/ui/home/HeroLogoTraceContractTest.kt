package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's clear-logo DRAW trace (spec 2026-11-24).
 *
 * The field case: Chappelle's Show's hero draws a solid white slab where the
 * wordmark should be, Detail draws the same title's logo correctly, and the
 * paste Kenny copied carries no line for the title at all. The `heroart:` trace
 * is a four-entry RESOLVE trace - it says a URL was handed over, never that a
 * bitmap painted, and the walk from the hero to Settings resolves more than four
 * heroes on the way, so the entry is evicted before the copy.
 *
 * What this pins is the draw half: the six facts recorded at the paint (id,
 * masked file, layer, decoded size, bitmap config, tint verdict), the failure
 * line that makes a silent no-logo answerable, the cap and the rendering, and
 * that none of it changed the request, the decode or the tint.
 *
 * Read from the source and the trace object: the paint itself needs a TV, a
 * network and Coil's decoder.
 */
class HeroLogoTraceContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private val screen: String by lazy { source(SCREEN) }

    private val trace: String by lazy { source(TRACE) }

    private val diagnostics: String by lazy { source(DIAGNOSTICS) }

    /** The hero's logo drawer, and nothing else in HomeScreen. */
    private val logoDrawer: String by lazy {
        screen
            .substringAfter("internal fun HeroClearLogo(")
            .substringBefore("private fun isDarkMonochromeArtwork(")
    }

    @Test
    fun `the draw records what painted, at the paint`() {
        assertTrue(
            "the paint records into its own trace",
            logoDrawer.contains("HeroLogoTrace.recordDraw(")
        )
        assertTrue(
            "with all six facts: which title, which file, which layer, what was " +
                "decoded and at what depth, and whether the dark-mark tint fired",
            logoDrawer.contains("tmdbId = tmdbId,") &&
                logoDrawer.contains("shape = logoShape,") &&
                logoDrawer.contains("layer = heroLogoLayer(dataSource),") &&
                logoDrawer.contains("config = bitmap?.config?.name ?: \"none\",") &&
                logoDrawer.contains("tint = dark")
        )
        assertTrue(
            "the size is the decoded bitmap's own, in the paste's w x h form",
            trace.contains("append(\" size=\")") &&
                trace.contains("if (width > 0 && height > 0) \"\${width}x\$height\" else \"none\"")
        )
        assertTrue(
            "and the entry is written AFTER the verdict, in the coroutine that " +
                "already materialized the bitmap - so the tint is in the same " +
                "line and no second readback is paid for",
            logoDrawer.indexOf("logoIsDark = dark") in 0 until
                logoDrawer.indexOf("HeroLogoTrace.recordDraw(")
        )
    }

    @Test
    fun `the file is named the way the player names it, never the URL`() {
        assertTrue(
            "the shape comes from the player's own mask (host + bounded path, " +
                "no query), so the two traces read the same in one paste",
            logoDrawer.contains("val logoShape = remember(url) {") &&
                logoDrawer.contains("logoLaunchShape(url)") &&
                logoDrawer.contains("\"\${shape.host}\${shape.path}\"")
        )
        assertFalse(
            "and the URL itself never reaches the trace: a paste is public",
            trace.contains("url") && trace.contains("append(url")
        )
    }

    @Test
    fun `a logo that fails to load says so and why`() {
        assertTrue(
            "the drawer has an error path at all - it had none, so a failed " +
                "logo read exactly like a title with no wordmark",
            logoDrawer.contains("onError = { state ->") &&
                logoDrawer.contains("HeroLogoTrace.recordFailure(")
        )
        assertTrue(
            "and it names the failure by class, the app's one token for a load " +
                "failure - never the throwable's message",
            logoDrawer.contains("reason = logoLoadFailureReason(state.result.throwable)")
        )
        assertTrue(
            "a failure and a paint are the same queue, so one outcome per slot",
            trace.contains("fun recordFailure(") && trace.contains("fun recordDraw(")
        )
    }

    @Test
    fun `the trace is capped and rendered beside the resolve, which is untouched`() {
        assertTrue(
            "eight entries: a report is not a log file, and the trace turns over " +
                "only on hero paints so it survives the walk to Settings",
            trace.contains("private const val MAX_ENTRIES = 8")
        )
        assertTrue(
            "oldest first, so the last line is the logo on screen",
            trace.contains("fun lines(): List<String> = entries.toList()")
        )
        assertTrue(
            "the report renders it as its own block",
            diagnostics.contains("heroLogoLine()?.let { report.appendLine(it) }") &&
                diagnostics.contains("return \"herologo: \" + lines.joinToString(\" · \")")
        )
        assertTrue(
            "and the resolve trace beside it is still its own four-entry trace: " +
                "resolve and draw are different requests and may disagree",
            diagnostics.contains("return \"heroart: \" + lines.joinToString(\" · \")") &&
                source(ART_TRACE).contains("private const val MAX_ENTRIES = 4")
        )
    }

    @Test
    fun `one entry per URL, not one per focus step`() {
        assertEquals(
            "exactly one draw record in the whole screen",
            1,
            Regex("HeroLogoTrace\\.recordDraw\\(").findAll(screen).count()
        )
        assertEquals(
            "and exactly one failure record",
            1,
            Regex("HeroLogoTrace\\.recordFailure\\(").findAll(screen).count()
        )
        assertTrue(
            "the record lives inside the request's own onSuccess, which Coil " +
                "answers once per request - and the request is still memoized " +
                "per URL, so a focus step does not re-run it",
            logoDrawer.contains("model = remember(url, context) {") &&
                logoDrawer.indexOf("onSuccess = { state ->") in 0 until
                logoDrawer.indexOf("HeroLogoTrace.recordDraw(")
        )
    }

    @Test
    fun `the request, the decode and the tint rule are untouched`() {
        assertTrue(
            "the pre-built request is still pre-built",
            logoDrawer.contains("ImageRequest.Builder(context)") &&
                logoDrawer.contains(".crossfade(true)")
        )
        assertTrue(
            "and still opts out of RGB_565 - the slab is not this spec's to fix, " +
                "and the trace exists to say whether the opt-out reached the draw",
            logoDrawer.contains(".allowRgb565(false)")
        )
        assertFalse(
            "no new request form on the hero path",
            logoDrawer.contains("loadLogo(")
        )
        assertTrue(
            "the tint is still the dark-mark verdict and nothing else",
            logoDrawer.contains("colorFilter = if (logoIsDark) ColorFilter.tint(KBTextHi) else null")
        )
        assertTrue(
            "the layer labels are the resolve trace's own, so the two lines can " +
                "be read side by side",
            // The mapper sits beside the drawer, in the same file.
            screen.contains("private fun heroLogoLayer(dataSource: coil3.decode.DataSource)") &&
                screen.contains("coil3.decode.DataSource.MEMORY_CACHE,") &&
                screen.contains("HeroArtTrace.Layer.DISK") &&
                screen.contains("HeroArtTrace.Layer.NETWORK")
        )
    }

    @Test
    fun `both call sites name the title they are drawing`() {
        assertTrue(
            "the hero passes its own id: TMDB's detail answer first, else the " +
                "meta's own namespace resolved to a TMDB id (an add-on id that " +
                "names no TMDB title answers none rather than being invented)",
            screen.contains("tmdbId = tmdbDetail?.id") &&
                screen.contains(": ImageOverrides.tmdbIdOf(meta?.id ?: preview.id),")
        )
        assertTrue(
            "and the Spotlight panel passes its own, so both land in one trace",
            source(PREVIEW).contains("tmdbId = tmdbId,")
        )
    }

    private fun source(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
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

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val TRACE = "com/kennyb1201/kbstream/data/tmdb/HeroLogoTrace.kt"
        const val ART_TRACE = "com/kennyb1201/kbstream/data/tmdb/HeroArtTrace.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
