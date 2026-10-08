package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch F of the UI uniformity sweep: the screens a viewer reaches from
 * somewhere else - the profile picker and editor, Simkl connect, a collection.
 *
 * None of them is visited as often as Home, which is exactly why they drifted:
 * each one named itself, sized its own text and lit its own focus, so the app
 * changed its voice whenever the viewer stepped off the main path. The fixes
 * here are the shared page title, the shared avatar-tile focus treatment, and
 * the hero title of a collection reading like a screen title rather than a
 * section label.
 *
 * Source-level because it is type, focus and chrome, which no compiler checks.
 */
class UiUniformityBatchFContractTest {

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
    fun `every secondary screen title is the shared page title`() {
        assertTrue(
            "the profile picker's name for itself goes through KBPageTitle, not " +
                "a headlineMedium+Bold of its own",
            source("ui/profiles/ProfilePickerScreen.kt")
                .contains("KBPageTitle(text = \"Who's watching?\")")
        )
        assertTrue(
            "and so does the editor's",
            source("ui/profiles/ProfileEditScreen.kt")
                .contains("KBPageTitle(text = if (editing == null) \"New profile\" else \"Edit profile\")")
        )
        assertTrue(
            "and Simkl's, accent-colored like Library's, where it used to be a " +
                "labelLarge caption - the one screen that asks for an account " +
                "named itself smaller than a rail heading",
            source("ui/simkl/SimklConnectScreen.kt")
                .contains("KBPageTitle(text = \"SIMKL CONNECT\", color = KBAccent)")
        )
    }

    @Test
    fun `a collection hero is a screen title`() {
        assertTrue(
            "the collection hero draws its name at headlineLarge, the page-title " +
                "size, instead of the headlineSmall section size it used",
            source("ui/collection/CollectionScreen.kt")
                .contains("text = name, // A collection hero is a screen title")
        )
        assertFalse(
            "and no longer at the section size",
            source("ui/collection/CollectionScreen.kt")
                .contains("text = name, style = MaterialTheme.typography.headlineSmall")
        )
    }

    @Test
    fun `the library's empty states are the shared status card`() {
        val library = source("ui/library/LibraryScreen.kt")
        assertTrue(
            "an empty grid draws the app's status card, with the shared " +
                "empty-result icon and the shared spinner while it loads",
            library.contains(
                "KBStatusMessage( message = emptyText, icon = KB_STATUS_ICON_EMPTY, loading = loading, modifier = modifier )"
            )
        )
        assertFalse(
            "and the loose centered line it used to drop into the pane is gone",
            library.contains(
                "text = emptyText, style = MaterialTheme.typography.bodyLarge, color = KBTextLo"
            )
        )
        assertFalse(
            "no retry card is added here: this screen always has its sort strip " +
                "and list rail to focus, so a card that grabbed focus the way " +
                "Home's and Detail's retry card does would move the D-pad",
            library.contains("onRetry =")
        )
    }

    @Test
    fun `the tail items landed`() {
        assertTrue(
            "the ASCII plus, not U+FF0B: the full-width form was the app's only " +
                "one and renders differently from the plus beside it",
            source("ui/profiles/ProfileEditScreen.kt").contains("text = \"+\",")
        )
        assertFalse(
            "and the dead onBack parameter is gone from the library screen",
            source("ui/library/LibraryScreen.kt").contains("onBack: () -> Unit")
        )
        assertTrue(
            "a cast circle rings at the app's 2dp, not a heavier 3dp of its own",
            source("ui/detail/DetailScreen.kt")
                .contains("// 2dp, the ring every focused surface in the app draws")
        )
        assertTrue(
            "a keyword chip's label is a chip label (labelMedium), like the genre " +
                "chip beside it",
            source("ui/detail/DetailScreen.kt")
                .contains("\"#\$name\", // A chip label, like the genre chip beside it.")
        )
        assertTrue(
            "the player's SKIP INTRO grows by the button step of the focus scale",
            source("ui/player/NativePlayerActivity.kt")
                .contains("v.scaleX = if (focused) 1.04f else 1f")
        )
        assertFalse(
            "and no player chrome is left on a 1.06 chip scale",
            Regex("scale[XY] = if \\(focused\\) 1\\.06f").containsMatchIn(
                source("ui/player/NativePlayerActivity.kt")
            )
        )
        assertFalse(
            "the player's own text colours are the shared resources, not literals",
            File(mainDir, "res/layout/activity_player.xml").readText()
                .let { it.contains("#FFF3EFE4") || it.contains("#FF8891A0") }
        )
    }

    @Test
    fun `status copy uses one ellipsis glyph`() {
        // A whole-tree scan rather than a list of known strings: the fifteen
        // fixed occurrences were spread over seven files (screens, a player and
        // four view models), and the next one will be written wherever the next
        // overlay is.
        //
        // Comments are excluded on purpose. This tree quotes ids as "tt..."
        // and "tmdb:..." in KDoc, and the copy rule is about what a viewer
        // reads - a doc comment is not a violation.
        //
        // The scan looks for three periods *inside a string literal*, not just
        // before a closing quote: the first version of this guard only matched
        // `..."`, which missed the two mid-string cases that prompted it
        // ("Reconnecting... (2/6)", "... Just Player, ...)"). A `"..."` regex
        // catches the whole family, and no non-copy literal in this package
        // contains three periods, so it has no false positives here.
        val threePeriodsInAString = Regex("\"[^\"\n]*\\.\\.\\.[^\"\n]*\"")
        val offenders = File(mainDir, "java/com/kennyb1201/kbstream/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val trimmed = line.trim()
                    val isComment =
                        trimmed.startsWith("*") || trimmed.startsWith("/*") ||
                            trimmed.startsWith("//")
                    if (!isComment && threePeriodsInAString.containsMatchIn(line.substringBefore("//"))) {
                        "${file.name}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertEquals(
            "the app writes \"…\" - one glyph - and never three periods in " +
                "anything a viewer can read",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `the guide says one thing about a channel with no program information`() {
        val guide = source("ui/iptv/GuideScreen.kt")
        // Matched on the expression, not the bare phrase: the comments above
        // these lines quote the retired copy on purpose (they are the record of
        // why it changed), so a plain `contains("\"No program data\"")` would
        // fail on its own documentation.
        assertFalse(
            "the channel row and the panel over it must agree: the row said " +
                "\"No program data\" while the card said \"No guide data\" for the " +
                "same absence",
            guide.contains("else \"No program data\"")
        )
        assertTrue(
            "and the phrase is the card's",
            guide.contains("else \"No guide data\"")
        )
    }

    @Test
    fun `the dead scrim colour is gone`() {
        assertFalse(
            "kb_overlay_scrim was referenced by nothing - the players' overlays " +
                "use the kb_overlay_gradient_* triple - so it is deleted rather " +
                "than left for the next screen to pick up",
            File(mainDir, "res/values/colors.xml").readText()
                .contains("kb_overlay_scrim")
        )
    }

    @Test
    fun `avatar tiles take the shared focus treatment`() {
        val picker = source("ui/profiles/ProfilePickerScreen.kt")
        assertTrue(
            "a focused avatar tile draws the app's 2dp accent ring, not a 3dp " +
                "one of its own",
            picker.contains(
                "BorderStroke(2.dp, KBAccent), shape = CircleShape"
            )
        )
        assertTrue(
            "and it glows: the biggest targets on the screen were the only " +
                "focusable surface in the app with no glow at all",
            picker.contains("focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)")
        )
        assertTrue(
            "the tile's initial is Oswald through the type scale",
            picker.contains("style = MaterialTheme.typography.displayMedium")
        )
        assertTrue(
            "and so is the name under the tile",
            picker.contains("style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center")
        )
        assertFalse(
            "no 34sp or 15sp literal is left on this screen - neither is a slot " +
                "in the type scale",
            picker.contains("34.sp") || picker.contains("15.sp")
        )
    }

    @Test
    fun `the avatar tile's glow is round like the tile`() {
        val picker = source("ui/profiles/ProfilePickerScreen.kt")

        // Reported: the focused tile drew a rounded SQUARE around a round
        // avatar. The ring was never the problem - tv-material draws a focused
        // border with the Border's own shape, and this screen already passed
        // CircleShape there. The GLOW is: SurfaceGlowNode paints the shape the
        // SURFACE was handed, so the tile's glow was the surface's default
        // rounded rectangle. The two have to agree, and the surface is the one
        // that was left unset.
        val glowAt = picker.indexOf(
            "focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)"
        )
        assertTrue("the tile's glow is still declared", glowAt >= 0)

        val surfaceAt = picker.lastIndexOf("Surface(", glowAt)
        assertTrue("the glow is still declared on the tile's Surface", surfaceAt >= 0)

        assertTrue(
            "an avatar tile is a circle, so its Surface has to declare the " +
                "circular shape: the glow is painted from the surface's shape, " +
                "so leaving it unset lights a square plate behind a round avatar",
            picker.substring(surfaceAt, glowAt)
                .contains("shape = ClickableSurfaceDefaults.shape(shape = CircleShape)")
        )
    }

    @Test
    fun `onboarding setup rows grow like rows`() {
        val onboarding = source("ui/onboarding/OnboardingScreen.kt")
        assertTrue(
            "a setup hand-off is a row, so it takes the row focus step - the " +
                "card step made three stacked rows breathe like posters",
            onboarding.contains("focusedScale = KBFocusRow")
        )
        assertFalse(
            "and no surface on the first-run screen still uses the card step",
            onboarding.contains("focusedScale = KBFocusCard")
        )
        assertTrue(
            "the one primary action starts the app, so it is the button step",
            onboarding.contains("focusedScale = KBFocusButton")
        )
    }

    @Test
    fun `the chosen avatar color keeps its ring without focus`() {
        val editor = source("ui/profiles/ProfileEditScreen.kt")
        assertTrue(
            "the color tile's own ring is drawn on the swatch, not on " +
                "focusedBorder, so the selection stays visible while focus is " +
                "somewhere else on the screen",
            editor.contains(
                ".border( width = if (selected) 3.dp else 0.dp, color = KBTextHi, shape = CircleShape )"
            )
        )
        assertTrue(
            "and its initial is Oswald too",
            editor.contains("style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold")
        )
    }

    @Test
    fun `both players show one clock and one unknown-duration glyph`() {
        val helpers = source("ui/player/PlayerFormatHelpers.kt")
        assertTrue(
            "the unknown-duration readout is one named constant, so the two " +
                "players cannot drift apart on it again",
            helpers.contains("internal const val UNKNOWN_DURATION_CLOCK = \"--:--\"")
        )
        assertTrue(
            "and durations go through the duration formatter, which can never " +
                "claim 00:00 for an unknown length",
            helpers.contains(
                "internal fun formatDurationMillis(ms: Long): String = " +
                    "if (ms <= 0L) UNKNOWN_DURATION_CLOCK else formatMillis(ms)"
            )
        )
        val playerDir = File(mainDir, "java/com/kennyb1201/kbstream/ui/player")
        assertFalse(
            "the MPV player's private formatClock is gone: it wrote a duration " +
                "under an hour as \"5:03\" while the main player wrote \"05:03\", " +
                "so the same title read differently depending on which engine " +
                "played it",
            playerDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .any { it.readText().contains("fun formatClock") }
        )
        val native = source("ui/player/NativePlayerActivity.kt")
        assertTrue(
            "and the main player's remaining duration readout is the duration " +
                "formatter, not the position one (the other moved into the shared " +
                "chrome, see below)",
            count(native, "totalTime.text = formatDurationMillis(") >= 1 &&
                !native.contains("totalTime.text = formatMillis(")
        )
        assertTrue(
            "as is the shared chrome the MPV engine drives",
            source("ui/player/PlayerChrome.kt")
                .contains("durationView?.text = formatDurationMillis(durationMs)")
        )
        // One label for the whole app now: both engines draw the shared
        // player_chrome.xml, so its duration and position readouts are theirs.
        val chromeLayout = File(mainDir, "res/layout/player_chrome.xml").readText()
        assertTrue(
            "the duration label starts as the unknown glyph: the view is painted " +
                "before a duration exists, and a 00:00 default read as a zero-length " +
                "title",
            chromeLayout.substring(chromeLayout.indexOf("@+id/chrome_duration")).take(600)
                .contains("android:text=\"--:--\"")
        )
        assertTrue(
            "while the position label beside it keeps a real 00:00 - a position of " +
                "zero is a fact, not a missing value",
            chromeLayout.substring(chromeLayout.indexOf("@+id/chrome_position")).take(600)
                .contains("android:text=\"00:00\"")
        )
    }
}
