package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external-player picker row, pinned where it can be seen.
 *
 * Field 2026-10-11 (Kenny's TV photo, Settings -> Player engine):
 *
 *  - **Chips stack weird.** The picker was a plain `Row` with no horizontal
 *    scrolling, so with several players installed and one very long label the
 *    early items consumed the width, the last chip got the leftover sliver, its
 *    `Text` wrapped mid-word into a tall column and the selected accent
 *    background filled the wrapped box - the "Vimu / Medi / a / Playe / r"
 *    tower.
 *  - **A non-player was listed.** Amazon's dummy gallery activity answers the
 *    video VIEW probe; with no human label it showed its raw component class
 *    name.
 *
 * There is no device in CI and a Compose screen cannot be driven in a JVM test,
 * so the structure that fixes them is read out of the source the way this
 * tree's other contracts are.
 */
class ExternalPlayerPickerRowContractTest {

    private val settings: String by lazy { read(SETTINGS) }
    private val externalPlayer: String by lazy { read(EXTERNAL_PLAYER) }

    @Test
    fun `the picker row scrolls, so no label is ever squeezed into a wrapped column`() {
        assertTrue(
            "the picker must be a horizontal scroller: a plain Row runs out of width and the " +
                "last chip's label wraps mid-word into the Vimu/Medi/a/Playe/r tower",
            settings.contains(".horizontalScroll(rememberScrollState())")
        )
        assertTrue(
            "and it still lays every installed player out in one row",
            settings.contains("externalPlayers.forEach { player ->")
        )
    }

    @Test
    fun `the focus inset is inside the scroll and its cancel-out offset outside it`() {
        val offset = settings.indexOf(".offset(x = -KBFocusRowInset)")
        val scroll = settings.indexOf(".horizontalScroll(rememberScrollState())")
        val inset = settings.indexOf(".padding(horizontal = KBFocusRowInset),")
        assertTrue("the cancel-outside offset must be on the row", offset >= 0)
        assertTrue("the row still scrolls", scroll >= 0)
        assertTrue("and the inset must be declared", inset >= 0)
        assertTrue(
            "the inset padding must sit AFTER the scroll: that is the only place it is inside " +
                "the clip, where it can hold the focused card's ring and glow - otherwise the " +
                "first and last chip are cut flat",
            inset > scroll
        )
        assertTrue(
            "and the negative offset must come BEFORE the scroll, so the chips stay flush with " +
                "the label above them while the room is available inside",
            offset < scroll
        )
    }

    @Test
    fun `the row holds KBCards, which is why the row-scale inset is the right one`() {
        val row = settings
            .substringAfter("// Scrollable, not a plain Row")
            .substringBefore("Spacer(modifier = Modifier.height(6.dp))")
        assertTrue("the picker row must still be there", row.contains("KBFocusRowInset"))
        assertTrue(
            "each entry is a KBCard wrapping a PillChip",
            row.contains("KBCard(onClick = {") &&
                row.contains("PillChip(player.label, externalPackage == player.packageName)")
        )
    }

    @Test
    fun `selection, click and the remembered-player line are unchanged`() {
        assertTrue(
            "the selected chip is still the remembered package",
            settings.contains("PillChip(player.label, externalPackage == player.packageName)")
        )
        assertTrue(
            "and tapping one still remembers it and turns asking off",
            settings.contains("AppPreferences.setExternalPlayer(") &&
                settings.contains("AppPreferences.setExternalPlayerAsk(context, false)")
        )
    }

    @Test
    fun `the stub filter runs in the one funnel both probes share`() {
        assertTrue(
            "remember() is where the MIME-probe and scheme-player matches both land",
            externalPlayer.contains("fun remember(pkg: String, label: String, scheme: String?)")
        )
        assertTrue(
            "the stub rule must be applied there, or a dummy gallery that answers the probe shows " +
                "up under its raw class name",
            externalPlayer.contains("if (isStubLabel(pkg, label)) return")
        )
        assertTrue(
            "the MIME probes go through it",
            externalPlayer.contains("remember(pkg, labelFor(resolve, pm), scheme = null)")
        )
        assertTrue(
            "and so do the scheme-only players",
            externalPlayer.contains("remember(pkg, labelFor(resolve, pm), scheme = player.scheme)")
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
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val EXTERNAL_PLAYER = "com/kennyb1201/kbstream/data/player/ExternalPlayer.kt"
    }
}
