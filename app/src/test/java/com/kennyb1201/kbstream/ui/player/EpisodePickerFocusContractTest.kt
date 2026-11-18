package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season strip's focus and the picker menu's surfaces (spec 2026-11-14).
 *
 * Two field reports, one panel. The strip's chips never took focus: its ring was
 * correct and never ran, because the panel built a brand-new adapter on every
 * rebind and nothing ever asked a chip for focus. The long-press menu ignored
 * AMOLED and the theme because its panel was a fixed drawable and its dim a
 * fixed black.
 *
 * Pinned by reading the sources: the players are Activities over a real
 * RecyclerView and a real window, which this module's JVM tests cannot drive. The
 * settlers (S1-S8) are the visual proof.
 */
class EpisodePickerFocusContractTest {

    private val strip by lazy { source("ui/player/SeasonStripAdapter.kt") }
    private val chipStyle by lazy { source("ui/player/SeasonChipStyle.kt") }
    private val native by lazy { source("ui/player/NativePlayerActivity.kt") }
    private val mpv by lazy { source("ui/player/MpvPlayerActivity.kt") }
    private val menu by lazy { source("ui/player/PickerEpisodeMenu.kt") }
    private val composeMenu by lazy { source("ui/components/PosterContextMenu.kt") }

    private val nativeBind by lazy { block(native, "private fun bindEpisodeSeasonStrip()") }
    private val mpvBind by lazy { block(mpv, "private fun bindEpisodeSeasonStrip()") }

    @Test
    fun `the focused view is the chip, so a center press lands on the season`() {
        assertTrue(
            "the click listener must be on the chip: a center press calls " +
                "performClick() on the FOCUSED view, and the root's listener never fires",
            strip.contains("chip.setOnClickListener { item.onClick() }")
        )
        assertTrue(
            "and the root keeps one for a touch on the chip's margin",
            strip.contains("holder.itemView.setOnClickListener { item.onClick() }")
        )
    }

    @Test
    fun `focused always reads as focused, whatever the input`() {
        // Spec 2026-11-17, amendment to F2: the touch-mode gate was the one
        // thing that could zero the ring, the bold label and the scale out - a
        // chip focused in touch mode drew like an unfocused one, which is the
        // field's "still no visible focus indicator".
        assertFalse(
            "the touch-mode gate is gone from the strip's code (its comments may " +
                "still name the history)",
            strip.lineSequence()
                .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("*") } }
                .any { line -> line.contains("isInTouchMode") }
        )
        assertTrue(
            "and the focused state drives all three marks unconditionally",
            strip.contains("SeasonChipStyle.apply(chip, item.isSelected, focused)")
        )
        assertTrue(
            "the ring itself is untouched - the bug was never the ring (it lives " +
                "in SeasonChipStyle since spec 2026-11-15, and 3dp since 2026-11-17)",
            chipStyle.contains("RING_DP = 3f") && chipStyle.contains("(RING_DP * density).toInt()")
        )
        assertTrue(
            "the ring is put on the chip's foreground while it holds focus and " +
                "taken off otherwise - and since the tap that focuses a chip also " +
                "SELECTS its season, a ring left by a tap marks the selection",
            strip.contains(
                "chip.foreground = if (focused) SeasonChipStyle.ring(chip.context, item.isSelected) else null"
            )
        )
    }

    @Test
    fun `one adapter per panel, updated in place`() {
        assertTrue("the adapter can be updated without being replaced", strip.contains("fun submit(newItems: List<PickerItem>)"))
        listOf("the main player" to nativeBind, "MPV" to mpvBind).forEach { (name, bind) ->
            assertTrue(
                "$name: the existing adapter is updated in place",
                bind.contains("existing.submit(items)")
            )
            assertTrue(
                "$name: and the adapter is only built when there is none",
                bind.contains("if (existing == null)") &&
                    bind.contains("SeasonStripAdapter(items)")
            )
            assertFalse(
                "$name: a study new adapter per bind is the bug",
                bind.contains("pickerSeasons.adapter = SeasonStripAdapter(") ||
                    bind.contains("pickerSeasons?.adapter = SeasonStripAdapter(")
            )
        }
        assertTrue(
            "both engines keep it as a field on the activity",
            native.contains("private var seasonStripAdapter: SeasonStripAdapter? = null") &&
                mpv.contains("private var seasonStripAdapter: SeasonStripAdapter? = null")
        )
    }

    @Test
    fun `a rebind keeps the D-pad in the strip`() {
        assertTrue(
            "the adapter can answer whether a chip holds it",
            strip.contains("fun hasChipFocus(parent: RecyclerView): Boolean")
        )
        listOf("the main player" to nativeBind, "MPV" to mpvBind).forEach { (name, bind) ->
            assertTrue(
                "$name: a focused strip is re-focused on the loaded season after a rebind",
                bind.contains("hasChipFocus(") &&
                    bind.contains("focusSelected(") &&
                    bind.contains("hadChipFocus")
            )
        }
        assertTrue(
            "and focusing the loaded season is the adapter's job, not a blind position",
            strip.contains("items.indexOfFirst { it.isSelected }") &&
                strip.contains("?.findViewById<TextView>(R.id.picker_season_chip)")
        )
    }

    @Test
    fun `the strip and the list hand focus to a chip or a row, never to a holder`() {
        listOf("the main player" to native, "MPV" to mpv).forEach { (name, source) ->
            assertTrue(
                "$name: the strip forwards focus to the loaded season's chip",
                source.contains("pickerSeasons.setOnFocusChangeListener") ||
                    source.contains("pickerSeasons?.setOnFocusChangeListener")
            )
            assertTrue(
                "$name: and the list forwards it to its first row",
                source.contains("pickerList.setOnFocusChangeListener") ||
                    source.contains("pickerList?.setOnFocusChangeListener")
            )
        }
        assertTrue(
            "the strip's forward is what makes UP out of the rows show a ring",
            native.substringAfter("pickerSeasons.setOnFocusChangeListener")
                .substringBefore("addOnChildAttachStateChangeListener")
                .contains("focusSelected(pickerSeasons)")
        )
        assertTrue(
            "the list's forward parks on a row",
            native.substringAfter("pickerList.setOnFocusChangeListener")
                .substringBefore("pickerSeasons.addOnChildAttachStateChangeListener")
                .contains("parkPickerFocus()")
        )
    }

    @Test
    fun `the long-press menu resolves the active surface instead of a fixed drawable`() {
        assertTrue(
            "the panel is the player's own runtime raised surface",
            menu.contains("setColor(playerPanelRaisedColor(activity))")
        )
        assertFalse(
            "the fixed @drawable/panel_bg is what ignored AMOLED",
            menu.contains("R.drawable.panel_bg")
        )
        assertTrue(
            "the dim is the app's modal scrim - KBVoid at 60%, as PosterContextMenu draws it - " +
                "not a hardcoded black",
            menu.contains("(0x99 shl 24) or (themeVoidWindowColor(activity) and 0x00FFFFFF)") &&
                composeMenu.contains("KBVoid.copy(alpha = 0.60f)")
        )
        assertFalse("no fixed black dim is left", menu.contains("#99000000"))
        assertTrue(
            "rows keep their own plate, which already has a focused state",
            menu.contains("R.drawable.picker_item_bg")
        )
    }

    @Test
    fun `the fix is the player's menu only - Detail's is the Compose one`() {
        // Verified, not assumed (F2(d)): Detail's long-press menu is
        // PosterContextMenu, which already uses the theme's KBVoid/KBSurfaceRaised
        // tokens, so there is no shared View-side panel to fix.
        assertTrue(composeMenu.contains("KBSurfaceRaised.copy(alpha = 0.97f)"))
        assertFalse(
            "the player's menu class is not used by Detail",
            composeMenu.contains("PickerEpisodeMenu")
        )
        assertEquals(
            "and the player's menu does not import a Compose surface token to fake it",
            0,
            countOf(menu, "androidx.compose")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        var i = bodyStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "$prefix" + "src/main/java/com/kennyb1201/kbstream/$relativePath")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relativePath")
    }

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }
}
