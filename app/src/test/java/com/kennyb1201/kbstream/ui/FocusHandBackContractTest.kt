package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How every rail screen gives the focus BACK to the card that raised a menu or
 * an overlay.
 *
 * One complaint, one rule, applied to every screen that has rails: closing a
 * long-press menu put the D-pad at the top of the page. A menu is drawn in its
 * own focus group over the page, so its dismiss has to hand the focus to the
 * remembered card - and a single `requestFocus()` cannot do that job, because it
 * is made in the same frame the menu's scrim is leaving the tree and answers
 * FALSE for a node that cannot take focus yet rather than throwing. The miss was
 * silent, so the framework filled the empty focus owner with the page's first
 * focusable (the category strip) and the screen scrolled up.
 *
 * Two rules, both in [com.kennyb1201.kbstream.ui.browse.rememberFocusHandBack]:
 * the landing is retried and read from the request's own answer, and a newer
 * hand-back cancels the older one so a superseded dismiss cannot land its focus
 * after the next one moved.
 *
 * Read from the source: these are Compose modifiers and click handlers, which
 * need a device and a remote to press.
 *
 * NOT covered on purpose: a menu row that hands focus to the successor tile
 * BEFORE the hidden tile leaves the tree (`PosterContextMenu`'s `onBeforeHide`,
 * in Search and the catalog grid). That node is known to be attached and the ask
 * has to happen in that same frame, so a direct request is correct there.
 */
class FocusHandBackContractTest {

    private fun source(relativePath: String): String =
        normalized(stripComments(File(findMainSourceRoot(), relativePath).readText()))

    @Test
    fun `the shared hand-back is verified and cancels a superseded one`() {
        val browse = source("com/kennyb1201/kbstream/ui/browse/BrowseReturnFocus.kt")
        assertTrue(
            "the helper reads the request's own answer through the app's one " +
                "landing rule instead of assuming a single ask landed",
            browse.contains("internal fun rememberFocusHandBack(): FocusHandBack") &&
                browse.contains("inFlight = scope.launch { landFocusOn(requester) }")
        )
        assertTrue(
            "and a newer hand-back cancels the landing still waiting on a frame, " +
                "so a retry the viewer has superseded cannot move the focus back",
            browse.contains("internal class FocusHandBack(") &&
                browse.contains("inFlight?.cancel()")
        )
    }

    @Test
    fun `every rail screen hands the focus back through it`() {
        // Each screen, and the hand-backs it owes.
        mapOf(
            "com/kennyb1201/kbstream/ui/tag/TagScreen.kt" to
                listOf("handFocusBack(lastRailFocusRequester)"),
            "com/kennyb1201/kbstream/ui/studio/StudioScreen.kt" to
                listOf("handFocusBack(lastRailFocusRequester)"),
            "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt" to
                listOf("handFocusBack(lastRailFocusRequester)"),
            "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt" to
                listOf("handFocusBack(lastCreditFocusRequester)"),
            "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt" to
                listOf("handFocusBack(lastPartFocusRequester)"),
            "com/kennyb1201/kbstream/ui/search/SearchScreen.kt" to
                listOf(
                    "handFocusBack(lastPosterFocusRequester)",
                    "handFocusBack(lastChipFocusRequester)"
                ),
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt" to
                listOf(
                    "handFocusBack(lastStudioChipFocusRequester)",
                    "handFocusBack(lastEpisodeFocusRequester)",
                    "handFocusBack(playButtonFocusRequester)"
                )
        ).forEach { (file, handBacks) ->
            val text = source(file)
            assertTrue(
                "every screen's dismiss goes through the one helper, or it " +
                    "re-implements the retry (or worse, asks once): $file",
                text.contains("val handFocusBack = rememberFocusHandBack()")
            )
            handBacks.forEach { handBack ->
                assertTrue(
                    "the hand-back must be the verified one: $handBack in $file",
                    text.contains(handBack)
                )
            }
        }
    }

    @Test
    fun `no rail screen still asks a remembered card once and reads nothing`() {
        listOf(
            "com/kennyb1201/kbstream/ui/tag/TagScreen.kt",
            "com/kennyb1201/kbstream/ui/studio/StudioScreen.kt",
            "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt",
            "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt",
            "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt",
            "com/kennyb1201/kbstream/ui/search/SearchScreen.kt",
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        ).forEach { file ->
            assertFalse(
                "the one-shot spelling is the silent miss this rule exists to " +
                    "end: $file",
                Regex("\\w+\\?\\.requestFocus\\(\\)")
                    .containsMatchIn(source(file))
            )
        }
    }

    @Test
    fun `Home's hand-back is the same rule with its own cadence`() {
        val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
        assertTrue(
            "Home's landing reads the request's own answer across frames (see " +
                "restoreFocus), which is the rule; it keeps its frame cadence " +
                "because the bar it lands in is an AnimatedVisibility",
            home.contains("fun restoreFocus(requester: FocusRequester?)") &&
                home.contains("landed = runCatching { requester.requestFocus() }")
        )
        assertTrue(
            "and a newer hand-back cancels the one still retrying, so a " +
                "superseded dismiss cannot move the focus back",
            home.contains("var focusRestoreJob by remember") &&
                home.contains("focusRestoreJob?.cancel()")
        )
        assertTrue(
            "the rail menu's watched row hands back through it",
            home.contains("restoreFocus(lastPosterFocusRequester)")
        )
        assertFalse(
            "with no one-shot asks left on the rail menus",
            home.contains("lastPosterFocusRequester?.requestFocus()")
        )
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
