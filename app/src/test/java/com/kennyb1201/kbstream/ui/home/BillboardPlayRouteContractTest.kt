package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Billboard Play carries its autoplay intent on the route (spec 2026-11-17).
 *
 * The reported bug was a Play press that landed on Detail and stopped there: on
 * the TV the handoff was never consumed at all, and on the phone the global
 * one-shot was already gone (twelve seconds, cleared-on-read, and a Detail page
 * that reads it before the load it waits for finishes). The fix makes the press
 * travel WITH the navigation, in the shape the Continue Watching deep link
 * already proved: a flag on `Screen.Detail`.
 *
 * What these pins protect:
 *  - Play and Details are still different presses (S4): only Play sets the flag.
 *  - The flag never survives as a Back destination (F4/S5/S6), so returning to
 *    that Detail - or opening the same title later - never autoplays.
 *  - Both surfaces deliver through the SAME code their own Play button uses, so
 *    the movie resume and the series' resume/next-up cannot fork (F2/F3).
 *  - The global one-shot is gone, because its replacement is the whole point
 *    (F5).
 */
class BillboardPlayRouteContractTest {

    private val activity by lazy { source("com/kennyb1201/kbstream/MainActivity.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val spotlightHome by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt") }
    private val mobileHome by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }
    private val tvDetail by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt") }
    private val mobileDetail by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
    }
    private val rules by lazy { source("com/kennyb1201/kbstream/ui/home/BillboardRules.kt") }

    // ── F1: the route carries the flag ───────────────────────────────────

    @Test
    fun `the detail route carries a plain autoplay flag, off by default`() {
        val route = activity.substringAfter("data class Detail(").substringBefore(") : Screen()")
        assertTrue(
            "the flag is a plain boolean with a false default, so every other " +
                "way into Detail keeps today's behaviour: $route",
            route.contains("val autoPlay: Boolean = false")
        )
        assertFalse(
            "and it is NOT a StreamsTarget: the billboard must not name a stream " +
                "or an episode",
            route.contains("autoPlay: StreamsTarget")
        )
    }

    @Test
    fun `both billboard Play presses navigate with the flag, and Details does not`() {
        val playNav = activity
            .substringAfter("onBillboardPlay = { meta: MetaPreview ->")
            .substringBefore("},")
        assertTrue(
            "the TV and phone billboard Play goes through this one navigation: $playNav",
            playNav.contains("Screen.Detail(") &&
                playNav.contains("meta.type") &&
                playNav.contains("meta.id") &&
                playNav.contains("autoPlay = true")
        )
        assertFalse(
            "the Details press stays the plain onItemClick route",
            playNav.contains("onDetails")
        )
        // The two surfaces hand Play to that callback and keep Details on the
        // ordinary one (S4).
        assertTrue(home.contains("onPlay = onBillboardPlay") && home.contains("onDetails = onItemClick"))
        assertTrue(mobileHome.contains("onPlay = { page -> onBillboardPlay(page.preview) }"))
        assertTrue(mobileHome.contains("onDetails = { page -> onItemClick(page.preview) }"))
        assertTrue(
            "Spotlight forwards the press, it no longer resolves anything",
            spotlightHome.contains("onPlay = { page -> onPlay(page.preview) }")
        )
    }

    // ── F2/F3: delivery is the page's own Play ───────────────────────────

    @Test
    fun `the phone delivers the route's flag through its own playCurrent`() {
        val effect = mobileDetail
            .substringAfter("LaunchedEffect(\n            autoPlay,")
            .substringBefore("/**")
        assertTrue(
            "the flag is the trigger, and the delivery is the button's own function",
            effect.contains("if (!autoPlay || billboardPlayDelivered || isLoading)") &&
                effect.contains("playCurrent()")
        )
        assertTrue(
            "the gates are unchanged: a page still waits for its title",
            effect.contains("if (meta == null && tmdbDetail == null) return@LaunchedEffect")
        )
        assertTrue(
            "and a series still waits for its episode listing before it fires (S3)",
            effect.contains(
                "if (normalizedType == \"series\" && episodesLoading) return@LaunchedEffect"
            )
        )
    }

    @Test
    fun `the TV delivers the route's flag through the target its Play button builds`() {
        assertTrue(
            "the page takes the flag",
            tvDetail.contains("autoPlay: Boolean = false,")
        )
        val effect = tvDetail
            .substringAfter("LaunchedEffect(\n                initialTarget,")
            .substringBefore("Box(\n                modifier = Modifier.fillMaxSize()")
        assertTrue(
            "the flag is a key of the delivery effect, so it runs on arrival",
            effect.contains("autoPlay,")
        )
        assertTrue(
            "and it is what lets a press through the deep-link-only gate",
            effect.contains("initialTarget == null &&") && effect.contains("!autoPlay")
        )
        assertTrue(
            "delivery builds the SAME target the Play button builds",
            effect.contains("playTargetFor(viewModel.awaitStreamRootId(id))")
        )
        val button = tvDetail
            .substringAfter("val openStreams: () -> Unit = {")
            .substringBefore("KBCard(")
        assertTrue(
            "which is the button's own call, so resume/next-up cannot fork (S1-S3)",
            button.contains("playTargetFor(viewModel.awaitStreamRootId(id))")
        )
        assertTrue(
            "the movie path does not wait for a resume row a never-watched title " +
                "does not have",
            effect.contains("!autoPlay &&") &&
                effect.contains("normalizedType != \"series\" &&") &&
                effect.contains("resumeInfo == null")
        )
    }

    // ── F4: the flag never becomes a Back destination ────────────────────

    @Test
    fun `back destinations clear the flag alongside the pending target`() {
        assertTrue(
            "the return-to copy clears both one-shots: ${
                activity.lines().first { it.contains("val stableReturnTo") }
            }",
            activity.contains("val stableReturnTo = current.copy(pendingTarget = null, autoPlay = false)")
        )
    }

    // ── F5: the one-shot is gone ─────────────────────────────────────────

    @Test
    fun `the global one-shot no longer exists anywhere in the app`() {
        assertFalse(
            "the object, and with it the 12-second window and clear-on-read, is deleted",
            rules.contains("object BillboardPlaySelection") ||
                rules.contains("VALID_FOR_MS")
        )
        val sources = kotlinFilesUnder(File(mainRootPath()))
        assertTrue(
            "the scan has to be real, or the empty list below means nothing: " +
                "found ${sources.size} sources",
            sources.size > 100
        )
        val offenders = sources
            .filter { it.readText().contains("BillboardPlaySelection") }
            .map { it.name }
        assertEquals(
            "nothing may re-raise or read a global Play request: the route is the " +
                "only carrier",
            emptyList<String>(),
            offenders
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun kotlinFilesUnder(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
