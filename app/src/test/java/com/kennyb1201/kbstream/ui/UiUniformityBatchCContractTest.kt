package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch C of the UI uniformity sweep: one status card, and the casing its
 * action labels (and every other button's label) is written in.
 *
 * The guide had grown its own status panel - a bold title over a smaller
 * message on a tall plate - while every browse screen showed
 * [com.kennyb1201.kbstream.ui.components.KBStatusMessage], so the same "nothing
 * here" read as two different things depending on which screen the viewer was
 * on. And labels had drifted into Title Case on the settings and profile
 * buttons while the guide's own SHOW/HIDE and the toast's UNDO were already
 * ALL CAPS.
 *
 * Source-level because it is copy and composition, which no compiler checks.
 */
class UiUniformityBatchCContractTest {

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
    fun `the guide shows the shared status card`() {
        val guide = source("ui/iptv/GuideScreen.kt")
        assertFalse(
            "the guide's own status panel is retired: its title-plus-message " +
                "plate was a second way to say what every browse screen says",
            guide.contains("CenterMessage")
        )
        assertEquals(
            "and its five status surfaces all go through the shared card",
            5,
            count(guide, "KBStatusMessage(")
        )
        assertTrue(
            "the card carries their two-line hierarchy (what happened, then the " +
                "detail) via its title slot, rather than the guide keeping a " +
                "panel of its own for it",
            source("ui/components/KBStatusMessage.kt").contains("title: String? = null,")
        )
        listOf(
            "title = \"Loading guide\",",
            "title = if (groupedChannels.isEmpty()) {",
            "title = \"No groups found\",",
            "title = \"No hidden channels\","
        ).forEach { heading ->
            assertTrue(
                "a guide status lost its heading ($heading)",
                guide.contains(heading)
            )
        }
    }

    @Test
    fun `status card action labels are all caps`() {
        assertTrue(
            "the status card's action is a button, so its default label is " +
                "written like one",
            source("ui/components/KBStatusMessage.kt")
                .contains("actionLabel: String = \"RETRY\",")
        )
        assertTrue(
            "and Home's non-retry action is too",
            source("ui/home/HomeScreen.kt").contains("actionLabel = \"MANAGE RAILS\",")
        )
        val sentenceCase = File(mainDir, "java/com/kennyb1201/kbstream/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("Press OK to retry") }
            .map { it.name }
            .toList()
        assertEquals(
            "no surface may keep the old sentence-case action copy",
            emptyList<String>(),
            sentenceCase
        )
    }

    @Test
    fun `settings and profile buttons are all caps`() {
        val sync = source("ui/settings/SyncSection.kt")
        listOf("SYNC NOW", "SIGN OUT", "SIGN IN", "SIGNING IN…", "CREATE ACCOUNT")
            .forEach { label ->
                assertTrue("Sync's \"$label\" button lost its casing", sync.contains(label))
            }
        val profile = source("ui/profiles/ProfileEditScreen.kt")
        listOf("SAVE", "CANCEL", "REMOVE PHOTO", "DELETE").forEach { label ->
            assertTrue(
                "the profile editor's \"$label\" button lost its casing",
                profile.contains("label = \"$label\",")
            )
        }
    }

    @Test
    fun `the last sentence-case control labels are gone`() {
        assertTrue(
            "the recent-searches strip's clear action is a chip label, so it is " +
                "written like every other control",
            source("ui/search/SearchScreen.kt").contains("label = \"CLEAR RECENT\",")
        )
        assertTrue(
            "and the picker's one action tile reads as an action among the " +
                "viewer's names",
            source("ui/profiles/ProfilePickerScreen.kt").contains("name = \"MANAGE\",")
        )
    }

    @Test
    fun `every dialog draws the one panel`() {
        val panel = source("ui/components/KBDialogPanel.kt")
        assertTrue(
            "the dialog plate is the shared fill, ring, padding and heading",
            panel.contains("fun KBDialogPanel(") &&
                panel.contains("background(KBSurface, KBShapePanel)") &&
                panel.contains("border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)") &&
                panel.contains("padding(22.dp)") &&
                panel.contains("text = title.uppercase(),") &&
                panel.contains("style = MaterialTheme.typography.headlineSmall,")
        )
        val guide = source("ui/iptv/GuideScreen.kt")
        assertEquals(
            "the guide's three dialogs all draw it (the reminder BANNER is not a " +
                "dialog and keeps its own plate)",
            3,
            count(guide, "KBDialogPanel(")
        )
        assertEquals(
            "the profile picker's PIN prompt draws it too",
            1,
            count(source("ui/profiles/ProfilePickerScreen.kt"), "KBDialogPanel(")
        )
        assertEquals(
            "and no guide dialog still hand-rolls the chrome",
            1,
            count(guide, "KBSurfaceRaised, KBShapePanel")
        )
    }

    @Test
    fun `every dialog plate is the canonical plate`() {
        listOf(
            "ui/components/KBDialogPanel.kt",
            "ui/addons/AddonsHomeManagerDialog.kt",
            "ui/iptv/GuideScreen.kt"
        ).forEach { path ->
            assertTrue(
                "$path must draw the canonical plate: the app's own surface, the " +
                    "38% accent ring and 22dp of padding",
                source(path).contains("background(KBSurface, KBShapePanel)") &&
                    source(path)
                        .contains("border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)") &&
                    source(path).contains("padding(22.dp)")
            )
        }
        assertFalse(
            "the home manager is filled with the app surface, not KBVoid: a void " +
                "plate is the one dialog that dissolves into the page behind it " +
                "under the AMOLED toggle",
            source("ui/addons/AddonsHomeManagerDialog.kt").contains("KBVoid, KBShapePanel")
        )
        assertTrue(
            "its heading is the shared dialog heading, not a titleLarge of its " +
                "own",
            source("ui/addons/AddonsHomeManagerDialog.kt")
                .contains("text = \"HOME MANAGER\", color = KBAccent")
        )
        assertTrue(
            "and the shared panel is a focus group, so focus search stays inside " +
                "the dialog before it can walk out behind the scrim",
            source("ui/components/KBDialogPanel.kt").contains(".focusGroup()")
        )
    }

    @Test
    fun `every button draws the one button`() {
        val button = source("ui/components/KBButton.kt")
        assertTrue(
            "the button style is one place: labelLarge, SemiBold, 16x9, and a " +
                "dimmed non-focusable plate when it cannot be pressed",
            button.contains("fun KBButton(") &&
                button.contains("style = MaterialTheme.typography.labelLarge,") &&
                button.contains("fontWeight = FontWeight.SemiBold,") &&
                button.contains("padding(horizontal = 16.dp, vertical = 9.dp)") &&
                button.contains("KBDanger")
        )
        assertFalse(
            "SyncSection's private copy is gone",
            source("ui/settings/SyncSection.kt").contains("private fun SyncActionButton(")
        )
        assertFalse(
            "and so is the profile editor's",
            source("ui/profiles/ProfileEditScreen.kt").contains("private fun ProfileActionButton(")
        )
        assertEquals(
            "the guide's channel-options rows are buttons, not hand-rolled cards",
            6,
            count(source("ui/iptv/GuideScreen.kt"), "KBButton(")
        )
    }
}
