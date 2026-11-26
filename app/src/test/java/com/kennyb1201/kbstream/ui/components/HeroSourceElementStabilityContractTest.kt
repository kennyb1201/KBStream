package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf spec 2026-11-22, item 4 remainder, the blocker the item-4 sweep hit: a
 * hero-carrying card could not remember its modifier chain, because
 * [KBHeroTransition] built a fresh shared-element node - and a fresh
 * `boundsTransform` lambda with it - on every composition, and
 * `heroSourceElement` chained a fresh `onFocusChanged` listener on top. A card
 * handed two unequal chains recomposes whatever else it does.
 *
 * So the chain is now assembled from remembered pieces:
 *
 *  - `sharedElementModifier` remembers the node on its inputs (the shared
 *    content state, the visibility scope, the bounds transform, the key) and
 *    the transform itself on the motion duration;
 *  - `heroSourceElement` remembers its focus listener per key, and attaches or
 *    detaches the flight end only when the card really gains or loses the role.
 *
 * With that, the cards that carry a flight can remember their whole chains, and
 * this test pins both halves: the element's stability and the sites that now
 * rely on it.
 *
 * Read from the source like the rest of the dashboard's contract tests: the
 * "same instance twice" half needs a recomposition rule this harness does not
 * have, and the on-device check is the `recomp railItems` paste.
 */
class HeroSourceElementStabilityContractTest {

    @Test
    fun `the shared element node is remembered on its inputs`() {
        val hero = stripComments(source(KB_HERO_TRANSITION))
        assertTrue(
            "the node itself has to be remembered, or every card gets a new " +
                "element per pass: " + SHARED_ELEMENT_REMEMBER,
            hero.contains(SHARED_ELEMENT_REMEMBER)
        )
        assertTrue(
            "and it has to be built from the remembered state, scope and " +
                "transform - a node built outside the remember would just be " +
                "cached stale",
            hero.contains("sharedContentState = sharedContentState") &&
                hero.contains("animatedVisibilityScope = visibilityScope") &&
                hero.contains("boundsTransform = boundsTransform")
        )
        assertTrue(
            "the bounds transform is one instance per screen, not one per card",
            hero.contains("val boundsTransform: BoundsTransform = remember(durationMs) {")
        )
        assertFalse(
            "an inline `boundsTransform = { _, _ -> tween(durationMs) }` is a new " +
                "lambda on every call, which is exactly the inequality this fixes",
            hero.contains("boundsTransform = { _, _ -> tween(durationMs) }")
        )
    }

    @Test
    fun `the source card's focus listener is remembered per key`() {
        val hero = stripComments(source(KB_HERO_TRANSITION))
        assertTrue(
            "`Modifier.onFocusChanged { }` made twice is two elements, so the " +
                "card's chain never compared equal",
            hero.contains("val focusListener = remember(key) {") &&
                hero.contains("Modifier.onFocusChanged { focusState ->")
        )
        assertTrue(
            "and the chain is assembled from the remembered halves",
            hero.contains(".then(focusListener)") &&
                hero.contains(".then(flight)")
        )
    }

    @Test
    fun `the cards that were blocked on it remember their chains`() {
        val home = stripComments(source(HOME))
        assertEquals(
            "every Home rail card builds its flight end once, from the " +
                "remembered element",
            3,
            count(home, "val hero = Modifier")
        )
        assertEquals(
            "the Library rail, Top Picks and Continue Watching (which carries no " +
                "flight) remember their whole focus chains",
            3,
            count(home, "val cardModifier = remember(")
        )
        assertEquals(
            "and the catalog rail remembers its own",
            1,
            count(home, "val posterModifier = remember(")
        )

        val grid = stripComments(source(CATALOG_GRID))
        assertEquals(
            "the catalog grid's cell remembers its chain around the hero",
            1,
            count(grid, "val cardModifier = remember(cardRequester, heroModifier) {")
        )

        val phone = stripComments(source(PHONE_HOME))
        assertEquals(
            "each of the phone's three arranged rails builds its flight end " +
                "once, not once per branch",
            3,
            count(phone, "heroSourceOnPress(")
        )
        assertTrue(
            "and wears the remembered size chain on both of its branches",
            count(phone, "modifier = landscapeModifier") == 3 &&
                count(phone, "modifier = cardModifier") == 3
        )
    }

    private fun count(text: String, needle: String): Int {
        var found = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            found++
            at = text.indexOf(needle, at + needle.length)
        }
        return found
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val KB_HERO_TRANSITION =
            "com/kennyb1201/kbstream/ui/components/KBHeroTransition.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val CATALOG_GRID = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val PHONE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"

        const val SHARED_ELEMENT_REMEMBER =
            "return remember(sharedContentState, visibilityScope, boundsTransform, key) {"
    }
}
