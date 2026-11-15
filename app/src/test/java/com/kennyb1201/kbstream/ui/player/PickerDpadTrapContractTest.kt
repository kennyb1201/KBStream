package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While the picker is open, no D-pad direction leaves it (spec 2026-11-14, F2).
 *
 * The escape (`8314055d7` landed a guard for it, and it still happened) is a
 * missed path, not a missing implementation: the strip's only wired edge was
 * DOWN, so LEFT/RIGHT at an end and any UP fell to focus search, which walks the
 * window and found the cast row of the overlay behind the panel. The guard did
 * fire - and could do nothing, because the remembered view had been detached by
 * a rebind and the fallback was `getChildAt(0)?.requestFocus()`, which is null in
 * exactly that state. A trap whose last resort is nothing is not a trap.
 *
 * Three things are pinned here, in BOTH engines and in the shared adapter:
 *  - the strip holds LEFT at the first chip, RIGHT at the last, and UP anywhere;
 *  - the reassert never asks a detached view and never ends with a null
 *    fallback - list row, then chip, then strip, then scrim/container;
 *  - the long-press menu still owns the D-pad, and BACK still dismisses.
 *
 * Read out of the source: the decision needs a RecyclerView, a window and a
 * remote, and the run that would settle it is a TV field test (S1-S6).
 */
class PickerDpadTrapContractTest {

    private val adapter by lazy { read("ui/player/SeasonStripAdapter.kt") }
    private val native by lazy { read("ui/player/NativePlayerActivity.kt") }
    private val mpv by lazy { read("ui/player/MpvPlayerActivity.kt") }

    private val nativeReassert by lazy { block(native, "private fun reassertPickerFocus() {") }
    private val mpvReassert by lazy { block(mpv, "private fun reassertPickerFocus() {") }

    // ── The strip's edges ─────────────────────────────────────────────────

    @Test
    fun `the strip consumes its own dead ends and UP, on the shared adapter`() {
        assertTrue(
            "the edge rule is the shared, unit-tested one",
            adapter.contains("PickerDpadRules.holdsWithinStrip(direction, position, items.lastIndex)")
        )
        assertTrue(
            "and it is wired from the chip itself, which is the view that holds focus",
            adapter.contains("chip.setOnKeyListener { _, keyCode, event ->") &&
                adapter.contains("StripDirection.LEFT") &&
                adapter.contains("StripDirection.RIGHT") &&
                adapter.contains("StripDirection.UP") &&
                adapter.contains("StripDirection.DOWN")
        )
        assertTrue(
            "a consumed press is answered with true, so focus search never runs",
            adapter.contains("if (position < 0) return@setOnKeyListener false")
        )
        assertTrue(
            "both engines build the strip from this one adapter",
            native.contains("SeasonStripAdapter(items)") && mpv.contains("SeasonStripAdapter(items)")
        )
        assertTrue(
            "and DOWN stays the wired edge into the list",
            native.contains("pickerSeasons.nextFocusDownId = R.id.picker_list") &&
                mpv.contains("pickerSeasons?.nextFocusDownId = R.id.mpv_picker_list")
        )
    }

    // ── The reassert never ends with nothing ──────────────────────────────

    @Test
    fun `a detached remembered view is dropped, not asked again`() {
        listOf("native" to nativeReassert, "mpv" to mpvReassert).forEach { (engine, body) ->
            assertTrue(
                "$engine: the remembered view must be attached and shown before it is asked",
                body.contains("remembered.isAttachedToWindow") && body.contains("remembered.isShown")
            )
            assertTrue(
                "$engine: and dropped when it is not, so it cannot keep the guard falling through",
                body.contains("pickerLastFocus = null")
            )
        }
    }

    @Test
    fun `the fallback chain always lands inside the picker`() {
        assertTrue(
            "the main player: first row, then the loaded season's chip, then the strip, then the scrim",
            nativeReassert.contains("pickerList.getChildAt(0)?.requestFocus()") &&
                nativeReassert.contains("seasonStripAdapter?.focusSelected(pickerSeasons)") &&
                nativeReassert.contains("pickerSeasons.requestFocus()") &&
                nativeReassert.contains("scrim.requestFocus()")
        )
        assertTrue(
            "the scrim is the last resort, after everything inside the panel",
            nativeReassert.indexOf("scrim.requestFocus()") >
                nativeReassert.indexOf("seasonStripAdapter?.focusSelected(pickerSeasons)")
        )
        assertTrue(
            "mpv: first row, the chip, the strip, then the container's first pill - never null",
            mpvReassert.contains("list.getChildAt(0)?.requestFocus()") &&
                mpvReassert.contains("seasonStripAdapter?.focusSelected(strip)") &&
                mpvReassert.contains("strip.requestFocus()") &&
                mpvReassert.contains("focusFirstPill(it)")
        )
        assertTrue(
            "and the strip is only asked while it is on screen (the track pickers hide it)",
            mpvReassert.contains("strip.visibility == View.VISIBLE")
        )
    }

    // ── What must not change ──────────────────────────────────────────────

    @Test
    fun `the long-press menu still owns the D-pad while it is up`() {
        listOf("native" to native, "mpv" to mpv).forEach { (engine, src) ->
            assertTrue(
                "$engine: the menu branch still comes first and still redirects",
                src.contains("menu.contains(newFocus)") && src.contains("menu.focusFirst()")
            )
        }
    }

    @Test
    fun `BACK still dismisses the picker, and focus is still handed back`() {
        assertTrue(
            "the Back arm is untouched",
            native.contains("isPickerShowing -> { dismissPicker(); showControls(); return }")
        )
        listOf("native" to native, "mpv" to mpv).forEach { (engine, src) ->
            assertTrue(
                "$engine: closing the picker still clears the trap state",
                src.contains("pickerLastFocus = null")
            )
            assertTrue(
                "$engine: and still hands the D-pad back to the player - the main player's " +
                    "restoreControlsFocus(), or mpv's control bar play/pause pill",
                src.contains("restoreControlsFocus()") || src.contains("playPauseButton?.requestFocus()")
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One function's source, balancing braces from its declaration. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
