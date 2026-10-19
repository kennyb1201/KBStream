package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.EnterExitState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero flight is one-way: card -> Detail on enter, and a plain fade on
 * pop.
 *
 * There is no compose-ui-test harness in this module (no `ui-test-junit4`), so
 * the composable itself cannot be driven here; this pins what can be seen
 * without one - the predicate that decides whether the destination attaches
 * the shared element, the source of `modifierFor` reading the enclosing
 * scope's `targetState` and returning before the shared element is built, and
 * the source card's unconditional `sourceModifierFor` (the outgoing screen is
 * already `PostExit` during the enter, so gating it would delete that end).
 * Together they are the contract the field check exercises on the TV: Back
 * fades the backdrop with the screen, and the enter flight still runs.
 */
class HeroTransitionOneWayContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun heroTransition(): String =
        source("com/kennyb1201/kbstream/ui/components/KBHeroTransition.kt")

    @Test
    fun `the hero joins the shared element only while the screen is visible`() {
        assertTrue(
            "an entering screen is Visible, and that is the flight we keep",
            KBHeroTransition.joinsFlight(EnterExitState.Visible)
        )
        assertFalse(
            "a popping screen is PostExit; keeping the shared element there is " +
                "what re-crops the backdrop against the card's rect on the way back",
            KBHeroTransition.joinsFlight(EnterExitState.PostExit)
        )
    }

    @Test
    fun `modifierFor reads the scope's target state and bails out before the flight`() {
        val hero = heroTransition()
        val targetRead = hero.indexOf("visibilityScope.transition.targetState")
        val bailOut = hero.indexOf("return Modifier")
        val sharedElement = hero.indexOf("Modifier.sharedElement(")
        assertTrue(
            "modifierFor must read visibilityScope.transition.targetState: that is " +
                "the only thing that says the screen is popping",
            targetRead >= 0
        )
        assertTrue(
            "the popping (PostExit) branch must return a plain Modifier",
            bailOut >= 0
        )
        assertTrue(
            "the plain-Modifier return must come before the shared element is built, " +
                "or the flight is registered and still flies back",
            bailOut < sharedElement
        )
        assertTrue(
            "the target-state read must drive that early return",
            targetRead < bailOut
        )
        assertTrue(
            "the exit branch must go through joinsFlight, so the rule is one predicate",
            hero.substringBefore("return Modifier").contains("joinsFlight(")
        )
        assertTrue(
            "the Visible path must still build the shared element",
            hero.substringAfter("return Modifier").contains("sharedElementModifier(key)")
        )
    }

    @Test
    fun `the source card keeps its end of the enter flight while its own screen exits`() {
        val hero = heroTransition()
        val sourceBlock = hero.substringAfter("fun Modifier.heroSourceElement")
        assertTrue(
            "heroSourceElement must use sourceModifierFor: on the way in the card's " +
                "own screen is already PostExit, so a gated modifier would drop the " +
                "flight's source and the enter animation would stop running",
            sourceBlock.contains("transition.sourceModifierFor(key)")
        )
        assertFalse(
            "heroSourceElement must not consult targetState",
            sourceBlock.contains("targetState")
        )
        assertTrue(
            "the source must still attach the shared element unconditionally",
            hero.substringAfter("fun sourceModifierFor")
                .contains("sharedElementModifier(key)")
        )
    }

    @Test
    fun `the enter flight keeps its content size placeholder and matched duration`() {
        val hero = heroTransition()
        assertTrue(
            "placeholderSize = ContentSize is what stops the rail shuffling mid-flight",
            hero.contains("placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize")
        )
        assertTrue(
            "boundsTransform must stay matched to the screen transition",
            hero.contains("boundsTransform = { _, _ -> tween(durationMs) }")
        )
        assertTrue(
            "the two ends still pair by key",
            hero.contains("rememberSharedContentState(key = key)")
        )
    }

    @Test
    fun `the detail hero still opts in on the way in`() {
        val detail = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
        assertTrue(
            "the backdrop must keep heroSharedElement(type, id), or nothing flies in",
            detail.contains(".heroSharedElement(type, id)")
        )
        assertTrue(
            "the backdrop is still the full-bleed crop the one-way rule is about",
            detail.contains("contentScale = ContentScale.Crop")
        )
    }
}
