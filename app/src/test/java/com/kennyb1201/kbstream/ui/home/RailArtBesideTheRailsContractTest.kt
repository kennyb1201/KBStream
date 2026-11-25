package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landscape artwork is filed BESIDE the rail list, not inside it (perf spec
 * 2026-11-22, item 3b).
 *
 * The art used to live on `Rail`, so one rail's artwork arriving replaced the
 * whole rail list. Every `remember(rails)` on the screen is keyed on that list's
 * identity - the hidden-titles pass, the hide-watched pass, the rail column's
 * own derivations - so on a cold load the art alone re-ran them once per rail,
 * on Main, while the rows were still arriving. The cadence gate
 * ([RailStreamGate]) covers the rows; this covers the artwork, which never went
 * through it.
 *
 * Pinned from the source because the cost is a composition one: a JVM test
 * cannot watch a rail repaint. [RailStreamGateTest] covers the cadence itself.
 */
class RailArtBesideTheRailsContractTest {

    private val home by lazy { read("ui/home/HomeViewModel.kt") }
    private val screen by lazy { read("ui/home/HomeScreen.kt") }
    private val mobile by lazy { read("ui/mobile/HomeScreenMobile.kt") }

    /** Just the art merge's publish, which is what used to replace the list. */
    private val merge: String by lazy {
        home.substringAfter("private suspend fun applyLandscapeArt(")
            .substringBefore("private var railLoadRetriesLeft")
    }

    @Test
    fun `art is published to the side flow, not into the rail list`() {
        assertFalse("the merge must exist", merge.isBlank())
        assertTrue(
            "the artwork lands in its own flow",
            merge.contains("_railArt.update { current ->")
        )
        assertFalse(
            "and NOT through the rail list: an art publish that replaces _rails " +
                "re-runs every remember(rails) derivation on the screen, once per " +
                "rail of a cold load - the cost this item removes",
            merge.contains("_rails.update")
        )
    }

    @Test
    fun `both surfaces read the filed art first and the rail's own art second`() {
        assertTrue(
            "TV: filed art, then what the rail itself carries (the art a rebuild " +
                "inherits), so neither route can blink a landscape card",
            screen.contains("railArt?.get(") &&
                screen.contains(") ?: rail.landscapeArt[")
        )
        assertTrue(
            "phone: the same chain. Mobile drew from Rail.landscapeArt alone, so " +
                "without this every phone landscape row would have lost the art " +
                "this item moved",
            mobile.contains("railArtState.value[") &&
                mobile.contains("}.value ?: rail.landscapeArt[")
        )
    }

    @Test
    fun `an art publish invalidates the rows it changed, not the screen`() {
        // The collected State object is handed down unread: reading `.value` in
        // the screen body would recompose the whole surface on every art
        // publish, which is the cascade in the other direction.
        assertTrue(
            "the state is collected once",
            screen.contains("val railArtState = viewModel.railArt.collectAsStateWithLifecycle()") &&
                mobile.contains(
                    "val railArtState = viewModel.railArt.collectAsStateWithLifecycle()"
                )
        )
        assertTrue(
            "and read inside a per-rail derived state, whose reader is notified " +
                "only when THAT rail's map changes",
            screen.contains("derivedStateOf {\n                                                        railArtState.value[railArtKey]") ||
                screen.contains("railArtState.value[railArtKey]")
        )
        assertTrue(
            "the derived state is keyed on the rail's identity",
            screen.contains("catalogRailKey(")
        )
    }

    @Test
    fun `the filed art cannot outlive the rails it describes`() {
        assertTrue(
            "a profile switch empties the art flow with the rails, or a rail of " +
                "the same key on the profile just entered would paint the " +
                "outgoing profile's artwork",
            home.contains("_railArt.value = emptyMap()")
        )
        assertTrue(
            "and a completed build drops the keys it no longer publishes, so the " +
                "flow cannot grow one entry per build for the session",
            home.contains("art.filterKeys { it in publishedRailKeys }")
        )
    }

    @Test
    fun `a rebuild still inherits the art the previous screen had`() {
        assertTrue(
            "the snapshot is the union of the flow and the rails on screen - the " +
                "art is no longer only on the rails, so reading either alone would " +
                "re-resolve every card the previous screen had already answered",
            home.contains("key to (rail.landscapeArt + artOnScreen[key].orEmpty())")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
