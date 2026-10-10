package com.kennyb1201.kbstream.ui.kb

import androidx.compose.foundation.ExperimentalFoundationApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection rail's focus landing, and the temporary instrumentation that
 * found the bug it fixes.
 *
 * Reported (2026-10-09, build 0.6.4, FOCUS_DIAG):
 *
 * ```
 * 20:45:32.396 LOST Cars scrollIdx=2 scrollOff=234
 * 20:45:34.329 LOST Cars scrollIdx=2 scrollOff=249
 * 20:45:34.794 LOST Cars scrollIdx=2 scrollOff=234
 * ```
 *
 * The rail slid under a stationary focus - the offset bouncing 234 <-> 249 with
 * no focus change, and only one "tiles rebuilt" line for the whole scroll, so
 * the tile list was NOT churning. The default [BringIntoViewSpec] lands the
 * focused node's LAYOUT rectangle flush with the viewport edge, but a focused
 * tile draws its accent border and glow outside that rectangle, so the landing
 * read as one step short and every pass asked for another micro-scroll.
 *
 * [CollectionBringIntoViewSpec] insets the viewport by the app's own focus-row
 * inset at each edge - the sports hub's fix - and the property that ends the
 * oscillation is arithmetic, so it is asserted here directly rather than
 * through a device and a D-pad: a tile already clear of both margins asks for
 * no scroll, and the position the spec scrolls TO also asks for no scroll when
 * it is re-measured. If either of those stops holding, the rail bounces again.
 */
@OptIn(ExperimentalFoundationApi::class)
class CollectionRailFocusLandingContractTest {

    private val slots: String by lazy { source(SLOTS) }

    /** The rail's own body, from its declaration to the spec installed for it. */
    private val rail: String by lazy {
        slots.substringAfter("fun KBHomeCollectionRail(")
            .substringBefore("class CollectionBringIntoViewSpec(")
    }

    /** The tile's own body, from its declaration to the next top-level function. */
    private val tile: String by lazy {
        slots.substringAfter("private fun CollectionFolderTile(")
            .substringBefore("\nfun KBHomeBrowseRail(")
    }

    // --------------------------------------------------------- the landing --

    @Test
    fun `a tile already clear of both margins is not scrolled at all`() {
        // The resting case, and the one the oscillation lived in: the focused
        // tile is fully inside the row, so the spec must return exactly 0f.
        // Anything else is a scroll that fires on every recomposition under a
        // stationary focus.
        assertEquals(
            0f,
            spec.calculateScrollDistance(offset = 234f, size = 150f, containerSize = VIEWPORT),
            TOLERANCE
        )
    }

    @Test
    fun `the position the spec scrolls to is a fixed point, so the bounce cannot repeat`() {
        // The whole bug in one assertion: measure, scroll by the answer, and
        // measure again from where the tile now is. The second measurement has
        // to be 0f - the landing is where the tile STAYS. A landing that asked
        // for another scroll is exactly the 234 <-> 249 loop.
        val overshoot =
            spec.calculateScrollDistance(offset = 1800f, size = 150f, containerSize = VIEWPORT)
        assertTrue("a tile past the trailing margin must be pulled back", overshoot > 0f)
        assertEquals(
            "the tile lands exactly on the margin the spec was given",
            VIEWPORT - INSET_PX,
            1800f - overshoot + 150f,
            TOLERANCE
        )
        assertEquals(
            "and the landing asks for no further scroll",
            0f,
            spec.calculateScrollDistance(
                offset = 1800f - overshoot,
                size = 150f,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
    }

    @Test
    fun `a tile scrolling in from before the leading margin is backed off, then rests`() {
        // The other direction: a tile whose leading edge is under the margin is
        // pushed out to it, and the pushed-to position is stable in the same way.
        val distance = spec.calculateScrollDistance(offset = 5f, size = 150f, containerSize = VIEWPORT)
        assertTrue("a tile under the leading margin must be backed off", distance < 0f)
        assertEquals("lands on the margin", INSET_PX, 5f - distance, TOLERANCE)
        assertEquals(
            0f,
            spec.calculateScrollDistance(offset = INSET_PX, size = 150f, containerSize = VIEWPORT),
            TOLERANCE
        )
    }

    @Test
    fun `the margin can never eat the viewport`() {
        // A viewport narrower than three margins: the margin is clamped, so a
        // node that fits inside what is left is left alone rather than being
        // dragged off the edge it is still visible on. 16f unclamped against a
        // 30f viewport would move this node (trailing 20f is past 30f - 16f).
        val narrow = spec.calculateScrollDistance(offset = 10f, size = 10f, containerSize = 30f)
        assertEquals(0f, narrow, TOLERANCE)
    }

    // --------------------------------------------------------- the wiring --

    @Test
    fun `the rail wraps its own LazyRow in the landing spec`() {
        assertTrue(
            "the rail has to install the spec itself - an unset one falls back " +
                "to the enclosing column's, which is built for a vertical list of rails",
            rail.contains("CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec)")
        )
        assertTrue(
            "resolved from the rail's density, so the 16dp margin is a real margin",
            rail.contains(
                "CollectionBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })"
            )
        )
        assertTrue(
            "memoised on the density, as the sports hub's own spec is",
            rail.contains("val bringIntoViewSpec = remember(density) {")
        )
        assertTrue(
            "and the LazyRow is inside that provider, not beside it",
            rail.substringAfter("CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec)")
                .contains("LazyRow(")
        )
    }

    @Test
    fun `the spec is supplied around the rail, not on the screen`() {
        // The invariant the spec asks for: no other rail's bring-into-view may
        // move with this fix. The rail installs its own; the screen knows nothing
        // about it, and the two specs it DOES install (the rows' vertical landing
        // and the cards' horizontal one) are still there.
        val home = source(HOME_SCREEN)
        assertFalse(
            "the screen must not know about the collection rail's spec",
            home.contains("CollectionBringIntoViewSpec")
        )
        assertTrue(
            "and the cart rail's own horizontal spec must be untouched",
            home.contains("railCardsBringIntoViewSpec")
        )
        assertTrue(home.contains("railRowsBringIntoViewSpec"))
    }

    @Test
    fun `focus navigation and the tile identity the fix must not touch are unchanged`() {
        assertTrue(
            "the positional key is what makes a tile's state follow the tile",
            rail.contains("key = { index, tile -> collectionRailKey(index, tile) }")
        )
        assertTrue(
            "the tile list is still the memoised, null-id-filtered rule",
            rail.contains("remember(collection) { collectionRailTiles(collection.folders) }")
        )
        assertTrue(
            "the screen's focus callback still reaches the tile",
            rail.contains("onFocus = onFolderFocused?.let { callback -> { callback(folder) } }")
        )
        assertTrue(
            "and the first-rail up hook goes with it",
            rail.contains("homeTopRailUpHook(requester, onUpPressed)")
        )
    }

    // ------------------------------------------- the temporary logs are gone --

    @Test
    fun `the FOCUS_DIAG instrumentation is gone from the app`() {
        // The logs were diagnostic, and the spec removes them with the fix: a
        // Log.w left behind is a release-build log line on every focus change,
        // and the tile's listState parameter (read only to print the scroll
        // position) goes with them.
        val stragglers = mainSources().filter { it.readText().contains("FOCUS_DIAG") }
        assertTrue(
            "FOCUS_DIAG must not survive in the app: " +
                stragglers.joinToString { it.path },
            stragglers.isEmpty()
        )
        assertTrue(
            "the FOCUS_DIAG files are exactly the two that carried it",
            mainSources().map { it.name }.toSet()
                .containsAll(listOf("KBHomeSlots.kt", "HomeScreen.kt"))
        )
        assertTrue("the tile body was located", tile.isNotEmpty())
        assertFalse(
            "the tile no longer takes the list state it only logged",
            tile.contains("listState")
        )
    }

    // ------------------------------------------------------------- test plumbing --

    private val spec: CollectionBringIntoViewSpec =
        CollectionBringIntoViewSpec(insetPx = INSET_PX)

    private fun mainSources(): List<File> =
        File(findMainSourceRoot(), "").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"

        /** The value the rail resolves [KBFocusRowInset] to on a TV-density screen. */
        const val INSET_PX = 16f

        /** A 1080p viewport, in the raw pixels the spec is handed. */
        const val VIEWPORT = 1920f

        const val TOLERANCE = 0.001f
    }
}
