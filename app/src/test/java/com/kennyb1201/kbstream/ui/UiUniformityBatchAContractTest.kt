package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch A of the UI uniformity sweep, pinned at the source level.
 *
 * Each of these covers a place where the app had grown a second way to do
 * something the rest of the app already did one way: a card whose label
 * changed color on focus, two headings on one settings section, three
 * long-press menus that dropped focus on close, two hand-rolled status lines on
 * Home, an engine with its own toast, and a countdown that counted to ten where
 * both other engines counted to five.
 *
 * Source-level because none of it is observable without a television and a
 * remote - the behavior these guard (focus hand-back, which label changed
 * color) only exists on screen.
 */
class UiUniformityBatchAContractTest {

    private val mainDir: File by lazy { findMainDir() }

    /**
     * Resolves `…/src/main` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
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

    private fun resource(relative: String): String {
        val file = File(mainDir, "res/$relative")
        assertTrue("resource missing: $file", file.isFile)
        return squash(file.readText())
    }

    /** Whitespace-insensitive, so the assertions pin expressions, not layout. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `the shared card keeps its label bright when focused`() {
        val card = source("ui/components/KBCard.kt")
        assertTrue(
            "KBCard must leave contentColor and focusedContentColor the same " +
                "tone: a label that turns accent-colored on focus changes color " +
                "under the D-pad, and an already-accent chip goes unreadable",
            card.contains("focusedContentColor = KBTextHi")
        )
        assertFalse(
            "no KBCard surface may turn its own text accent-colored on focus",
            card.contains("focusedContentColor = KBAccent")
        )
    }

    @Test
    fun `the discover chip row keeps a selected chip readable while focused`() {
        // The same rule as the card above, in the genre/service row the Studio
        // and Decade discover screens are crossed with. A SELECTED chip is an
        // accent wash under an accent label, so the focus colors have to be
        // spelled out: with them unset, the chip the D-pad was on fell back to
        // that pair and the filter the row is currently on - the one the viewer
        // is reading at that moment - was one color on one color.
        val chips = source("ui/components/GenreChipRow.kt")
        assertTrue(
            "a focused discover chip must take the light label, not the accent",
            chips.contains("focusedContentColor = KBTextHi")
        )
        assertFalse(
            "no discover chip may draw the accent label over the accent wash",
            chips.contains("focusedContentColor = KBAccent")
        )
        assertTrue(
            "a focused SELECTED chip must deepen its wash a step instead of " +
                "flattening into one accent",
            chips.contains(
                "focusedContainerColor = if (selected) { KBAccent.copy(alpha = 0.34f) } " +
                    "else { KBSurfaceRaised }"
            )
        )
        assertFalse(
            "and a press must not fall back to that pair either",
            chips.contains("pressedContentColor = KBAccent")
        )
        assertTrue(
            "the unfocused selected state is unchanged: accent label on the wash, " +
                "so the row still says which filter is on while focus is elsewhere",
            chips.contains("contentColor = when { selected -> KBAccent")
        )
    }

    @Test
    fun `the Library filter chip keeps a selected chip readable while focused`() {
        // The same rule as the discover row above, on the strip the report came
        // from (2026-11-05): the focused, already-selected WATCHLIST chip was a
        // solid bright pill with no word on it - accent text on the accent wash
        // it was sitting on. Focus now takes the light label in both states; the
        // wash, the border and the bold label keep saying "selected".
        val chip = source("ui/library/LibraryScreen.kt")
            .substringAfter("internal fun LibraryFilterChip(")
            .substringBefore("scale = ClickableSurfaceDefaults.scale(")
        assertTrue(
            "a focused Library chip must take the light label, selected or not",
            chip.contains("focusedContentColor = KBTextHi")
        )
        assertFalse(
            "no Library filter chip may draw the accent label over the accent " +
                "wash - that is the unreadable one",
            chip.contains("focusedContentColor = if (selected) KBAccent")
        )
        assertTrue(
            "the focused selected chip keeps its deeper wash, so focusing it does " +
                "not flatten selection into the unselected fill",
            chip.contains(
                "focusedContainerColor = if (selected) { KBAccent.copy(alpha = 0.4f) } " +
                    "else { KBSurfaceRaised }"
            )
        )
        assertTrue(
            "and the unfocused states are unchanged: the selected chip still shows " +
                "the accent label on its 28% wash while focus is elsewhere",
            chip.contains("containerColor = when { selected -> KBAccent.copy(alpha = 0.28f)") &&
                chip.contains("contentColor = when { selected -> KBAccent")
        )
    }

    @Test
    fun `the Library list rail keeps its focused row readable and fully in view`() {
        // The fifth site of the accent-on-accent family, and the report that
        // came with it (2026-11-05): the focused first row showed only its count
        // line, and the + NEW LIST row was half-cut on the bottom edge.
        val pane = source("ui/library/LibraryScreen.kt")
            .substringAfter("private fun ListsPane(")
            .substringBefore("private fun LibraryPosterCard(")
        assertFalse(
            "the row's title must not turn accent when the row is selected - a " +
                "selected row is already an accent wash, and the two together are one " +
                "bright pill with no word on it",
            pane.contains("color = if (selected) KBAccent")
        )
        assertTrue(
            "the title takes the light tone in every state",
            pane.contains("fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium") &&
                pane.contains("color = KBTextHi")
        )
        assertTrue(
            "selection still reads from the wash and the focus border, which stay",
            pane.contains(
                "focusedContainerColor = if (selected) { KBAccent.copy(alpha = 0.34f) } " +
                    "else { KBSurfaceRaised }"
            )
        )
        assertTrue(
            "and the count line keeps the half of the old fix that worked",
            pane.contains("KBTextHi.copy(alpha = 0.82f)")
        )
        // The clipping half: room at both ends INSIDE the clip, and a landing
        // computed from the DRAWN (scaled + glowing) row instead of its layout
        // rectangle.
        assertTrue(
            "both ends of the rail carry the row room, and the last row can scroll " +
                "far enough to land on its margin",
            pane.contains("top = KBFocusRowInset") &&
                pane.contains("bottom = KBFocusRowInset + 8.dp")
        )
        assertTrue(
            "the rail is the shared landing column, not the default one",
            pane.contains("RailListColumn(") &&
                pane.contains("CompositionLocalProvider(LocalBringIntoViewSpec provides")
        )
        assertTrue(
            "and that landing accounts for what this rail actually draws past the " +
                "row - the small glow and half the row-scale growth",
            pane.contains("SportsCardBringIntoViewSpec(") &&
                pane.contains("glowPx = with(density) { KBFocusGlowSmall.toPx() }") &&
                pane.contains("focusedScale = KBFocusRow")
        )
        assertTrue(
            "the rail's own geometry is untouched: 280dp wide, 8dp between rows",
            pane.contains("width(280.dp)") && pane.contains("Arrangement.spacedBy(8.dp)")
        )
    }

    @Test
    fun `the sync section draws one heading, not two`() {
        val sync = source("ui/settings/SyncSection.kt")
        assertFalse(
            "the Integrations pane already captions this section, so a heading " +
                "inside SyncSection stacked two labels on the same rows",
            sync.contains("KBSectionHeader")
        )
        // The pane caption moved with the section (settings reorg §10.1, Q1):
        // sign-in is the SYNC pane's "Account" section now. The one-heading rule
        // above is what this test protects - the caption is the pane's, wherever
        // the pane is - and the "(Beta)" qualifier deliberately did NOT come
        // along, because the Account header is not a beta label.
        assertTrue(
            "the section must still be captioned by the pane it lives in",
            source("ui/settings/SettingsScreen.kt")
                .contains("SettingsSectionHeader(\"Account\", first = true)")
        )
    }

    @Test
    fun `the play long-press menu hands focus back to the button`() {
        val detail = source("ui/detail/DetailScreen.kt")
        assertTrue(
            "closing the hero Play button's menu must return focus to the " +
                "button; without it the D-pad restarts at the top of the page - " +
                "and the ask goes through the page's verified hand-back rather " +
                "than a single request, because the menu's scrim is leaving the " +
                "tree in the same frame and a missed request is silent",
            detail.contains("fun dismissPlayButtonMenu() { playButtonMenu = false") &&
                detail.contains("handFocusBack(\"play button menu\", playButtonFocusRequester)")
        )
        assertTrue(
            "both the action rows and the Back/dismiss path share that hand-back",
            detail.contains("onDismiss = { dismissPlayButtonMenu() }")
        )
        assertEquals(
            "the flag is only ever cleared by the hand-back",
            1,
            Regex("playButtonMenu = false").findAll(detail).count()
        )
    }

    @Test
    fun `the studio chip menu hands focus back to the chip`() {
        val detail = source("ui/detail/DetailScreen.kt")
        assertTrue(
            "the network and production chip menus must remember the chip that " +
                "raised them",
            Regex("lastStudioChipFocusRequester = chipFocusRequester")
                .findAll(detail).count() == 2
        )
        assertTrue(
            "and closing them must hand focus back through one helper, whose " +
                "landing is read from the request's own answer instead of assumed",
            detail.contains("fun dismissStudioChipMenu() { val chipKey = studioChipMenu?.let") &&
                detail.contains(
                    "handFocusBack(\"network/production chip menu\", " +
                        "lastStudioChipFocusRequester)"
                ) &&
                detail.contains("val handFocusBack = rememberDetailHandBack()")
        )
        assertEquals(
            "the menu state is only ever cleared by that helper",
            1,
            Regex("studioChipMenu = null").findAll(detail).count()
        )
    }

    @Test
    fun `a closed category tab menu returns focus to the tab`() {
        val search = source("ui/search/SearchScreen.kt")
        assertTrue(
            "the category tab's own menu must name the tab to return to",
            search.contains("categoryTabMenuReturn = category.key") &&
                search.contains("returnCategoryKey = categoryTabMenuReturn")
        )
        val browser = source("ui/search/BrowseBrowser.kt")
        assertTrue(
            "the strip must resolve that key to the tab's focus requester, " +
                "retrying across frames because an unattached request is " +
                "silently missed",
            browser.contains("val requester = tabFocusRequesters[key]") &&
                browser.contains(
                    "focused = runCatching { requester.requestFocus() }.getOrDefault(false)"
                ) &&
                browser.contains("modifier = Modifier.focusRequester(tabFocusRequester)")
        )
        assertTrue(
            "and a tab that has not REGISTERED yet is an arrival, not a missed " +
                "landing: charging the frame budget for it spent all twelve on " +
                "the strip composing and left the arm unconsumed, so the wait is " +
                "bounded by time instead (the same two budgets as the browse " +
                "pages' own hand-back)",
            browser.contains("if (requester == null) {") &&
                browser.contains("BROWSE_RETURN_WAIT_MS")
        )
        assertTrue(
            "the tab composable has to take that modifier to be focusable by it",
            browser.contains("onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier")
        )
    }

    @Test
    fun `home's error and empty states are the shared status card`() {
        val home = source("ui/home/HomeScreen.kt")
        assertTrue(
            "a failed catalog load above the rails must be the same pill every " +
                "browse screen shows, not a bare Text line",
            home.contains("KBStatusMessage( message = \"Error: \$error\",")
        )
        assertTrue(
            "and the empty-catalog state must be the status card, with the " +
                "empty-result icon and its own focusable retry action",
            home.contains(
                "icon = KB_STATUS_ICON_EMPTY, " +
                    "onRetry = { viewModel.refreshRailsOnly() },"
            )
        )
        assertFalse(
            "the hand-rolled error line is gone, not kept beside the pill",
            home.contains("Text( text = \"Error: \$error\"")
        )
    }

    @Test
    fun `the mpv engine raises the platform toast`() {
        val mpv = source("ui/player/MpvPlayerActivity.kt")
        assertTrue(
            "the MPV engine's transient feedback is the platform Toast, the same " +
                "one the native player raises",
            mpv.contains("Toast.makeText(")
        )
        assertFalse(
            "no view to show and time out by hand, so two toasts cannot race one " +
                "another's hide runnable",
            mpv.contains("toastView")
        )
        assertFalse(
            "and the layout must not carry the removed view",
            resource("layout/activity_mpv_player.xml").contains("@+id/mpv_toast")
        )
    }

    @Test
    fun `every engine counts down for the same five seconds`() {
        val shared = source("ui/player/NativePlayerActivity.kt")
        assertTrue(
            "the countdown both engines share is the package-level constant",
            shared.contains("internal const val NEXT_UP_COUNTDOWN_SECONDS = 5")
        )
        assertFalse(
            "the hand-off must not declare a private copy of it - it counted ten " +
                "seconds where the other two engines counted five",
            source("ui/player/ExternalPlayerActivity.kt")
                .contains("private const val NEXT_UP_COUNTDOWN_SECONDS")
        )
    }
}
