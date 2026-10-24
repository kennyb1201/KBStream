package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero switches ONCE per settled item, and its logo cannot move the layout.
 *
 * Reported: fast tile-hopping across a rail made the hero change in two beats -
 * the words animated in over the PREVIOUS item's picture, then the art landed
 * and swapped - and when a late clearlogo replaced the fallback title text the
 * slot's height changed (a fixed logo box against up to two lines of text in a
 * bottom-anchored column), so the metadata and the buttons under it jumped.
 *
 * Three rules, all read from the sources because none of them is visible from a
 * JVM test: the copy transition is keyed on the VIEWMODEL's settled key rather
 * than on the focus change; the logo and the fallback title share one fixed
 * box; and the logo fades without taking the column's 10dp rise.
 */
class HeroCopyTransitionContractTest {

    private val screen by lazy { read(SCREEN) }
    private val viewModel by lazy { read(VIEW_MODEL) }

    @Test
    fun `the copy transition waits for the item to settle`() {
        assertTrue(
            "the fade must react to the settled marker, not only to the focused item",
            screen.contains("LaunchedEffect(heroCopyKey, copySettled) {")
        )
        val effect = screen
            .substringAfter("LaunchedEffect(heroCopyKey, copySettled) {")
            .substringBefore("Column(")
        assertTrue(
            "an unsettled item snaps to full opacity and does NOT start the fade",
            effect.indexOf("if (!copySettled || heroCopyReducedMotion || alreadyOnScreen) {") in
                0 until effect.indexOf("snapTo(0f)")
        )
        assertTrue(
            "and it returns before the animation, so a hop that outruns the resolve " +
                "never gets a second act",
            effect.contains("return@LaunchedEffect")
        )
        assertTrue(
            "a copy the viewer already read while the art resolved does not fade in again",
            effect.contains("val alreadyOnScreen = heroCopyShownKey == heroCopyKey")
        )
    }

    @Test
    fun `the settled marker is published with the art it belongs to`() {
        // The words are the item's already-known preview, so they exist from the
        // focus change; the marker is what says the ART joined them. It is
        // written at the end of the resolve, after every state the hero draws
        // from, which is the only way the screen can know the two agree.
        assertTrue(
            "the resolve must publish the settled key",
            viewModel.contains("_heroSettledKey.value = \"\$requestedId:\$requestedType\"")
        )
        val publishIndex = viewModel.indexOf(
            "_heroSettledKey.value = \"\$requestedId:\$requestedType\""
        )
        listOf(
            "_heroBackdropUrl.value = resolvedBackdrop",
            "_heroLogoUrl.value = resolvedLogo",
            "_heroTmdbDetail.value = resolvedTmdbDetail"
        ).forEach { publication ->
            assertTrue(
                "$publication must land BEFORE the settled marker",
                viewModel.indexOf(publication) in 0 until publishIndex
            )
        }
        assertTrue(
            "a resolve for no item clears it again",
            viewModel.contains("_heroSettledKey.value = null")
        )
    }

    @Test
    fun `the hero host asks for the focused item's settled state`() {
        assertTrue(
            "the host compares the settled key against the item it is drawing",
            screen.contains("heroSettledKey.value == \"\${it.id}:\${it.type}\"")
        )
        assertTrue(
            "a folder or a browse chip brings its own art and settles at once",
            screen.contains("copySettled = heroOverridden ||")
        )
    }

    @Test
    fun `the logo and the plain title share one fixed slot`() {
        // The fallback title used to be its own Text in the column flow, so a
        // logo landing (or never landing) changed the block's height and moved
        // everything beneath it. One fixed logo-sized box now holds all three
        // cases.
        val slot = screen
            .substringAfter("// The logo/text slot is ONE fixed box")
            .substringBefore("// Main metadata line.")
        assertTrue(
            "the slot is the logo box's own size",
            slot.contains(".width(heroLogoWidth)") && slot.contains(".height(heroLogoHeight)")
        )
        assertTrue(
            "both logo rungs fill that box rather than sizing themselves",
            Regex("modifier = Modifier\\s*\\.fillMaxSize\\(\\)").findAll(slot).count() == 2
        )
        assertTrue(
            "and the no-logo case draws its title inside the same box",
            slot.contains("text = title,") && slot.contains("maxLines = 2,")
        )
    }

    @Test
    fun `the logo fades but does not rise`() {
        // The 10dp rise stays on the copy column (the text earns it); the logo
        // layer cancels it, so a wordmark never slides vertically.
        assertTrue(
            "the copy column keeps its rise",
            screen.contains("translationY = (1f - heroCopyProgress.value) * 10.dp.toPx()")
        )
        assertTrue(
            "and the logo slot cancels exactly that rise",
            screen.contains("translationY = -(1f - heroCopyProgress.value) * 10.dp.toPx()")
        )
    }

    @Test
    fun `reduced motion still snaps`() {
        val effect = screen
            .substringAfter("LaunchedEffect(heroCopyKey, copySettled) {")
            .substringBefore("Column(")
        assertTrue(
            "the reduced-motion branch must still snap to the settled state",
            effect.contains("heroCopyReducedMotion || alreadyOnScreen") &&
                effect.contains("snapTo(1f)")
        )
        assertEquals(
            "one fade per transition: a single animateTo in the effect",
            1,
            Regex("animateTo\\(").findAll(effect).count()
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
