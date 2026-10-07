package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch D of the UI uniformity sweep: chips drawn like chips.
 *
 * A chip is not a card. It is a small interactive label that sits in a strip
 * with a dozen siblings, so it takes the chip corner (10dp), the chip focus
 * scale (1.06) and the chip glow (8dp) - while the badges and pills built on
 * the same surfaces had collected 3dp, 4dp, 5dp and 8dp corners of their own,
 * and two of the strips were drawing their chips on the 12dp card shape at the
 * card's 1.03 scale with the card's larger glow.
 *
 * Source-level because the difference between 4dp and 10dp of corner on a
 * badge, and between an 8dp and a 12dp focus glow, is only visible on a
 * television at ten feet.
 */
class UiUniformityBatchDContractTest {

    private val mainDir: File by lazy { findMainDir() }

    private fun findMainDir(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainDir, "java/com/kennyb1201/kbstream/$relative")
        assertTrue("source missing: $file", file.isFile)
        return squash(file.readText())
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun count(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun `badges and pills use the chip corner`() {
        listOf(
            "ui/detail/DetailScreen.kt",
            "ui/home/HomeScreen.kt",
            "ui/iptv/GuideScreen.kt"
        ).forEach { path ->
            val text = source(path)
            assertFalse(
                "$path still draws a badge or pill on a 3dp/4dp/5dp corner of " +
                    "its own instead of KBShapeChip",
                text.contains("RoundedCornerShape(3.dp)") ||
                    text.contains("RoundedCornerShape(4.dp)") ||
                    text.contains("RoundedCornerShape(5.dp)")
            )
        }
        assertTrue(
            "the stream badge chip is a chip, not the 8dp small shape",
            source("ui/components/StreamBadgeChip.kt")
                .contains("private val BadgeChipShape = KBShapeChip")
        )
    }

    @Test
    fun `chips in a strip get the chip scale and corner`() {
        assertTrue(
            "Detail's season chips are chips",
            source("ui/detail/DetailScreen.kt")
                .contains("focusedScale = KBFocusChip, shape = KBShapeChip,")
        )
        assertTrue(
            "and so are the guide's group chips",
            source("ui/iptv/GuideScreen.kt")
                .contains("focusedScale = KBFocusChip, shape = KBShapeChip,")
        )
    }

    @Test
    fun `chips glow with the chip glow`() {
        val detail = source("ui/detail/DetailScreen.kt")
        assertTrue(
            "Detail's chip strips use KBFocusGlowSmall (8dp)",
            detail.contains("elevation = KBFocusGlowSmall")
        )
        // \b, not a substring: KBFocusGlowSmall CONTAINS KBFocusGlow, so a
        // plain `contains("elevation = KBFocusGlow")` passes for the very glow
        // this assertion exists to rule out.
        assertFalse(
            "and no chip is left on the card's 12dp glow",
            Regex("elevation = KBFocusGlow\\b").containsMatchIn(detail)
        )
    }

    @Test
    fun `focus never floods a chip with accent`() {
        val browser = source("ui/search/BrowseBrowser.kt")
        assertFalse(
            "the browse action chip must raise its plate and ring it, not " +
                "invert to an accent fill with a void label - that inversion was " +
                "the one place focus recoloured a surface instead of lighting " +
                "its border",
            browser.contains("focusedContentColor = KBVoid")
        )
        assertTrue(
            "it raises instead",
            browser.contains("focusedContainerColor = KBSurfaceRaised,")
        )
    }

    @Test
    fun `the guide's channel row draws one focus border`() {
        val guide = source("ui/iptv/GuideScreen.kt")
        assertFalse(
            "the channel row is a KBCard, which already draws the 2dp accent " +
                "focus border; a second inner 1dp border on the same focus drew " +
                "two rings at once",
            guide.contains("color = if (isFocused) KBAccent.copy(alpha = 0.32f)")
        )
        assertTrue(
            "and the group chip strip takes its side room from the chip inset, " +
                "cancelled so the strip stays aligned with the column above it",
            guide.contains("contentPadding = PaddingValues(horizontal = KBFocusChipInset),") &&
                guide.contains("modifier = Modifier .offset(x = -KBFocusChipInset)")
        )
    }

    @Test
    fun `the resume bar is one bar`() {
        val bar = source("ui/components/KBProgressBar.kt")
        assertTrue(
            "the bar has one treatment: 4dp tall, a KBTextLo track at 45%, a " +
                "KBAccent fill, on the hairline 2dp radius",
            bar.contains("fun KBProgressBar(") &&
                bar.contains("private val PROGRESS_HEIGHT = 4.dp") &&
                bar.contains("KBTextLo.copy(alpha = 0.45f)") &&
                bar.contains("background(KBAccent, PROGRESS_SHAPE)") &&
                bar.contains("private val PROGRESS_SHAPE = RoundedCornerShape(2.dp)")
        )
        listOf(
            "ui/detail/DetailScreen.kt",
            "ui/home/HomeScreen.kt",
            "ui/iptv/GuideScreen.kt"
        ).forEach { path ->
            assertTrue(
                "$path must draw the shared bar",
                source(path).contains("KBProgressBar(")
            )
        }
        assertFalse(
            "Home's own track tone is gone (it was KBTextHi at 28%)",
            source("ui/home/HomeScreen.kt").contains("KBTextHi.copy(alpha = 0.28f)")
        )
        assertFalse(
            "and the guide's (a KBVoid track on a capsule clip)",
            source("ui/iptv/GuideScreen.kt").contains("KBVoid.copy(alpha = 0.55f)")
        )
    }

    @Test
    fun `the guide paints the app's void`() {
        val guide = source("ui/iptv/GuideScreen.kt")
        assertTrue(
            "the screen root is KBVoid, so the AMOLED toggle reaches it",
            guide.contains("fillMaxSize() .background(KBVoid)")
        )
        assertFalse(
            "and no longer a gradient whose top stop was a third background " +
                "tone of its own",
            guide.contains("Brush.verticalGradient")
        )
    }

    @Test
    fun `no text is written at 13sp`() {
        val offenders = File(mainDir, "java/com/kennyb1201/kbstream/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("13.sp") }
            .map { it.name }
            .toList()
        assertEquals(
            "no slot in the type scale is 13sp, so a text that needed one was " +
                "inventing a size instead of naming a role - a meta line takes " +
                "bodyMedium, a chip label labelMedium, an on-card title titleSmall",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `on-card titles take the title slot`() {
        assertTrue(
            "the landscape card's corner title is an on-card title",
            source("ui/components/LandscapeCard.kt")
                .contains("text = fallbackTitle, color = KBTextHi, style = MaterialTheme.typography.titleSmall,")
        )
        assertEquals(
            "and Home's two Up-Next cards agree on the slot, where the upcoming " +
                "card wrote 12sp and its sibling 14sp",
            2,
            count(source("ui/home/HomeScreen.kt"), "style = MaterialTheme.typography.titleSmall,")
        )
        assertTrue(
            "and so does an episode card's name on Detail, which was the same " +
                "14sp but at body weight",
            source("ui/detail/DetailScreen.kt")
                .contains("text = episodeName, color = KBTextHi, style = MaterialTheme.typography.titleSmall,")
        )
    }

    @Test
    fun `the detail action row is drawn as buttons`() {
        assertEquals(
            "Play, Random and Trailer are buttons, not cards: the pill edge and " +
                "the button step of the focus scale, instead of the card's 12dp " +
                "corner at 1.03",
            3,
            count(
                source("ui/detail/DetailScreen.kt"),
                "shape = KBShapePill, focusedScale = KBFocusButton"
            )
        )
    }

    @Test
    fun `the card fades run on KBVoid`() {
        val home = source("ui/home/HomeScreen.kt")
        assertEquals(
            "both Up-Next card fades start on the app's own void - they ended on " +
                "a KBVoid stop while beginning on black, so the AMOLED toggle " +
                "repainted the bottom of the fade and not the top",
            2,
            count(home, "KBVoid.copy(alpha = 0.08f)")
        )
        assertEquals(
            "and both ramp through the same mid stop",
            2,
            count(home, "KBVoid.copy(alpha = 0.34f)")
        )
    }

    @Test
    fun `one separator spelling in the UI`() {
        listOf(
            "ui/home/HomeScreen.kt",
            "ui/iptv/GuideScreen.kt",
            "ui/addons/AddonsComponents.kt"
        ).forEach { path ->
            assertFalse(
                "$path still splits a run of facts with the wide separator " +
                    "(two spaces around the bullet) where Detail uses one",
                source(path).contains("\" \u2022  \"")
            )
        }
    }

    @Test
    fun `a loading page draws skeletons, not a status card`() {
        assertFalse(
            "Home's loading state is placeholders shaped like the rails that " +
                "are coming, not a spinner in the middle of an empty page",
            source("ui/home/HomeScreen.kt").contains("KB_STATUS_LOADING")
        )
        assertTrue(
            "and it reserves exactly the space those rails need, at Home's own " +
                "12dp edge",
            source("ui/home/HomeScreen.kt").contains("KBSkeletonRailStack(") &&
                source("ui/home/HomeScreen.kt")
                    .contains("horizontalPadding = TvSafeAreaHorizontal,")
        )
        assertFalse(
            "the search screen's loading state is placeholders too - its results " +
                "are titled rails of poster tiles",
            source("ui/search/SearchScreen.kt").contains("KB_STATUS_LOADING")
        )
    }

    @Test
    fun `a skeleton tile takes the poster's own edge`() {
        val skeleton = source("ui/components/KBSkeleton.kt")
        assertTrue(
            "every placeholder entry point accepts the poster edge and hands it " +
                "to the tiles",
            3 <= count(skeleton, "shape: androidx.compose.ui.graphics.Shape = KBShapeCard") &&
                skeleton.contains("shape = shape")
        )
        listOf(
            "ui/actor/ActorScreen.kt",
            "ui/home/CatalogGridScreen.kt",
            "ui/studio/StudioScreen.kt",
            "ui/tag/TagScreen.kt",
            "ui/decade/DecadeScreen.kt",
            "ui/kb/KBFolderLayouts.kt"
        ).forEach { path ->
            assertTrue(
                "$path still draws its placeholders on the hardcoded rounded " +
                    "shape, so the rail changes shape when the posters land",
                source(path).contains("shape = posterEdgeShape()")
            )
        }
    }

    @Test
    fun `the IMDb yellow lives in one place`() {
        assertTrue(
            "the brand tint is a shared const",
            source("ui/components/MdbListRatingChips.kt")
                .contains("internal val KBImdbTint = Color(0xFFF5C518)")
        )
        assertFalse(
            "and the detail screen no longer retypes the literal",
            source("ui/detail/DetailScreen.kt").contains("0xFFF5C518")
        )
    }
}
