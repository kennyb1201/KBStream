package com.kennyb1201.kbstream.ui.components.touch

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1's two invariants, both of which only source text can see:
 *
 *  1. **The touch twins are new files.** Every mobile component lives under
 *     `ui/components/touch`, is built on regular `androidx.compose.material3`,
 *     and no TV component imports it. The port's hard rule is that the TV app
 *     does not regress, so a tv-material3 call site is never edited in place;
 *     a phone screen chooses a twin by importing it.
 *
 *  2. **The twins keep the TV call-site shape.** Each twin declares the same
 *     function name and the parameters a ported screen passes (including the
 *     ones that only mean something on a remote, such as `focusedScale`, which
 *     is accepted and ignored). That is what lets a Phase 2 screen read like
 *     its TV sibling instead of inventing a second component API.
 *
 * There is no compose-ui-test harness in this module, so this cannot press a
 * card; it pins the structure the field check (and Phase 2's screens) rely on.
 */
class MobileTouchTwinContractTest {

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

    /** Whitespace-collapsed source, so a wrapped signature still matches. */
    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun touch(file: String): String =
        source("com/kennyb1201/kbstream/ui/components/touch/$file")

    private fun tv(file: String): String =
        source("com/kennyb1201/kbstream/ui/components/$file")

    private val touchFiles = listOf(
        "TouchCard.kt",
        "TouchButton.kt",
        "TouchTextField.kt",
        "TouchChips.kt",
        "TouchDialog.kt",
        "TouchPosterCard.kt",
        "TouchLandscapeCard.kt",
        "TouchContextMenu.kt"
    )

    @Test
    fun `every touch twin is a new file on regular material3`() {
        touchFiles.forEach { file ->
            val text = touch(file)
            assertTrue(
                "$file must build on androidx.compose.material3: a tv-material3 " +
                    "control is a focus surface, not a touch one",
                text.contains("import androidx.compose.material3.")
            )
            assertFalse(
                "$file must not import androidx.tv.material3 - that would make the " +
                    "touch twin depend on the TV widget layer it exists to replace",
                text.contains("import androidx.tv.material3.")
            )
        }
    }

    @Test
    fun `no tv component routes through a touch twin`() {
        // The port's hard rule: existing TV files are not edited in place. If a
        // TV component ever imported the touch package, the TV build would be
        // running phone widgets - exactly the regression the invariant forbids.
        listOf(
            "KBCard.kt",
            "KBButton.kt",
            "KBTextField.kt",
            "GenreChipRow.kt",
            "StudioChip.kt",
            "KBDialogPanel.kt",
            "PosterCard.kt",
            "LandscapeCard.kt",
            "PosterContextMenu.kt"
        ).forEach { file ->
            val text = tv(file)
            assertFalse(
                "$file must not import the touch twins",
                text.contains("ui.components.touch")
            )
            assertTrue(
                "$file must still be on androidx.tv.material3",
                text.contains("import androidx.tv.material3.")
            )
        }
        assertTrue(
            "KBCard.kt must still be the tv-material3 Card the TV screens use",
            tv("KBCard.kt").contains("androidx.tv.material3.Card")
        )
    }

    @Test
    fun `the touch card keeps tap and long press`() {
        val card = touch("TouchCard.kt")
        assertTrue(
            "the touch card must use combinedClickable so a hold reaches the " +
                "same actions the TV reaches with a center-key hold",
            card.contains("combinedClickable(")
        )
        assertTrue("TouchCard must keep onLongClick", card.contains("onLongClick"))
        assertTrue(
            "TouchCard must keep focusedScale in its signature for call-site " +
                "parity with KBCard, even though touch has no focus scale",
            card.contains("focusedScale")
        )
    }

    @Test
    fun `the poster and landscape twins keep the tv parameters`() {
        val poster = touch("TouchPosterCard.kt")
        assertTrue("PosterCard twin missing", poster.contains("fun PosterCard("))
        listOf("posterUrl", "contentDescription", "isWatched", "onClick", "onLongClick")
            .forEach { name ->
                assertTrue("TouchPosterCard must keep $name", poster.contains(name))
            }
        listOf("isPartiallyWatched", "onPosterError", "overlayContent").forEach { name ->
            assertTrue("TouchPosterCard must keep $name", poster.contains(name))
        }

        val landscape = touch("TouchLandscapeCard.kt")
        assertTrue("LandscapeCard twin missing", landscape.contains("fun LandscapeCard("))
        listOf("backdropUrl", "logoUrl", "fallbackTitle", "isWatched", "rank")
            .forEach { name ->
                assertTrue("TouchLandscapeCard must keep $name", landscape.contains(name))
            }
    }

    @Test
    fun `the small controls keep their names and key parameters`() {
        val button = touch("TouchButton.kt")
        assertTrue("KBButton twin missing", button.contains("fun KBButton("))
        listOf("label", "enabled", "danger", "onClick").forEach { name ->
            assertTrue("TouchButton must keep $name", button.contains(name))
        }

        val field = touch("TouchTextField.kt")
        assertTrue("KBTextField twin missing", field.contains("fun KBTextField("))
        listOf("focusRequester", "keyboardType", "visualTransformation", "onDone")
            .forEach { name ->
                assertTrue("TouchTextField must keep $name", field.contains(name))
            }

        val chips = touch("TouchChips.kt")
        assertTrue("GenreChipRow twin missing", chips.contains("fun GenreChipRow("))
        assertTrue("StudioChip twin missing", chips.contains("fun StudioChip("))

        val dialog = touch("TouchDialog.kt")
        assertTrue("KBDialogPanel twin missing", dialog.contains("fun KBDialogPanel("))
        assertTrue(
            "the TV panel's fixed width must survive as a cap, not a fixed width",
            dialog.contains("widthIn(max = width)")
        )
    }

    @Test
    fun `the touch context menu keeps the shared menu contract`() {
        val menu = touch("TouchContextMenu.kt")
        assertTrue("PosterContextMenu twin missing", menu.contains("fun PosterContextMenu("))
        listOf("hideTarget", "libraryTarget", "onBeforeHide", "actions", "onDismiss")
            .forEach { name ->
                assertTrue("TouchContextMenu must keep $name", menu.contains(name))
            }
        assertTrue(
            "the canonical section order must be reused, not re-spelled",
            menu.contains("contextMenuSectionRank(")
        )
        assertTrue(
            "the library rows must still go through the shared add pipeline",
            menu.contains("LibraryAdds.add(")
        )
    }

    @Test
    fun `the mobile theme reuses the brand tokens instead of re-declaring them`() {
        val theme = source("com/kennyb1201/kbstream/ui/theme/MobileTheme.kt")
        assertTrue(
            "MobileTheme must apply the regular material3 MaterialTheme",
            theme.contains("import androidx.compose.material3.MaterialTheme")
        )
        assertTrue(
            "the type scale must be mapped from the TV theme, not restated, so the " +
                "two form factors cannot drift",
            theme.contains("import androidx.tv.material3.MaterialTheme as TvMaterialTheme") &&
                theme.contains("TvMaterialTheme.typography")
        )
        assertTrue(
            "the palette must come from the shared KB color tokens",
            theme.contains("primary = KBAccent") &&
                theme.contains("background = KBVoid") &&
                theme.contains("surface = KBSurface")
        )
        assertTrue(
            "the tv theme file must still be the TV theme (not replaced by the mobile one)",
            source("com/kennyb1201/kbstream/ui/theme/Theme.kt")
                .contains("import androidx.tv.material3.MaterialTheme")
        )
    }

    /**
     * The sheet's rows are label-only, like the TV menu's. The phone used to
     * print every action's description under its label; that doubled the height
     * of every long-press menu for text nobody reads before tapping - the label
     * already says what the row does.
     */
    @Test
    fun `long press rows are label-only, as on a tv`() {
        val menu = touch("TouchContextMenu.kt")
        assertFalse(
            "the row must not draw a description line again",
            menu.contains("description?.takeIf") ||
                menu.contains("description = action.description")
        )
        assertTrue(
            "the row must still be a full-width tap target with its label",
            menu.contains("private fun SheetActionRow(") &&
                menu.contains("text = label") &&
                menu.contains("clickable(onClick = onClick)")
        )
    }
}
