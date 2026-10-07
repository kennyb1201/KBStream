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
    fun `the sync section draws one heading, not two`() {
        val sync = source("ui/settings/SyncSection.kt")
        assertFalse(
            "the Integrations pane already captions this section, so a heading " +
                "inside SyncSection stacked two labels on the same rows",
            sync.contains("KBSectionHeader")
        )
        assertTrue(
            "the section's \"(Beta)\" qualifier must survive on the pane caption",
            source("ui/settings/SettingsScreen.kt")
                .contains("SettingsSectionHeader(\"Sync (Beta)\", first = true)")
        )
    }

    @Test
    fun `the play long-press menu hands focus back to the button`() {
        val detail = source("ui/detail/DetailScreen.kt")
        assertTrue(
            "closing the hero Play button's menu must return focus to the " +
                "button; without it the D-pad restarts at the top of the page",
            detail.contains("fun dismissPlayButtonMenu() { playButtonMenu = false") &&
                detail.contains("playButtonFocusRequester.requestFocus()")
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
            "and closing them must hand focus back through one helper",
            detail.contains("fun dismissStudioChipMenu() { studioChipMenu = null") &&
                detail.contains("lastStudioChipFocusRequester?.requestFocus()")
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
            browser.contains("tabFocusRequesters[key]?.requestFocus() ?: false") &&
                browser.contains("modifier = Modifier.focusRequester(tabFocusRequester)")
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
