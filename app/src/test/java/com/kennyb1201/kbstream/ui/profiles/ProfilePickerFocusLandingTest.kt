package com.kennyb1201.kbstream.ui.profiles

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile picker can open with focus on NOTHING - and that is a total
 * lockout, not a lost keystroke.
 *
 * The field case: a Fire TV force-closed during playback, relaunched into the
 * picker (no active profile restored), and the picker drew its tiles but no ring
 * appeared anywhere - all four directions dead, OK dead - until a paired mouse or
 * a reinstall. The landing was one unobserved, unretried ask fired at first
 * composition, and this is the one screen where losing it strands the viewer:
 * from "nothing holds focus" no directional press can bootstrap focus, and the
 * picker is the entry gate the whole app sits behind.
 *
 * Two things are pinned here:
 *
 *  - the retry table itself ([driveProfileLanding]): asked every frame, the
 *    TARGET's own focus state is the verdict, an unattached requester costs one
 *    attempt rather than the landing, and a drive that never lands spends a
 *    bounded budget and answers 0 so its caller can name it;
 *  - the picker's wiring: the landing is keyed on the tile it is aimed at, the
 *    watchdog repairs a landing lost afterwards (the startup sync pull rebuilds
 *    the tiles under the focus), both stand down while a PIN/exit dialog owns
 *    focus, and every branch is logged under PROFILE_FOCUS.
 */
class ProfilePickerFocusLandingTest {

    // ── the retry table ───────────────────────────────────────────────────

    /**
     * Runs the driver against a scripted tile, recording the order of the three
     * things it can do, so "asked, then a frame, then the verdict" is pinned and
     * not just the count.
     */
    private class ScriptedTile(private val landsOnAttempt: Int) {
        val events = mutableListOf<String>()
        var asks = 0
        var framesAwaited = 0
        private var checks = 0

        fun ask() {
            asks++
            events += "ask"
        }

        suspend fun awaitFrame() {
            framesAwaited++
            events += "frame"
        }

        fun observed(): Boolean {
            checks++
            events += "check"
            return checks >= landsOnAttempt
        }

        fun drive(): Int = runBlocking {
            driveProfileLanding(
                ask = ::ask,
                awaitFrame = ::awaitFrame,
                observed = ::observed
            )
        }
    }

    @Test
    fun `the landing stops on the frame the target reports focus`() {
        val tile = ScriptedTile(landsOnAttempt = 3)
        assertEquals("the frames it took are what the log names", 3, tile.drive())
        assertEquals("one ask per frame", 3, tile.asks)
        assertEquals("and one frame between each ask and its verdict", 3, tile.framesAwaited)
        assertEquals(
            "the ask comes first, the verdict after a frame - never in the same frame " +
                "the ask was made in, which answers for the frame before",
            listOf("ask", "frame", "check", "ask", "frame", "check", "ask", "frame", "check"),
            tile.events
        )
    }

    @Test
    fun `a tile that never reports focus spends the cap and answers zero`() {
        val tile = ScriptedTile(landsOnAttempt = Int.MAX_VALUE)
        assertEquals(
            "an exhausted landing answers 0 so its caller can warn about it",
            0,
            tile.drive()
        )
        assertEquals(
            "the budget is the whole of it - about a second of frames",
            PROFILE_LANDING_FRAMES,
            tile.asks
        )
        assertEquals(PROFILE_LANDING_FRAMES, tile.framesAwaited)
    }

    @Test
    fun `an ask against a node that is not attached yet costs one attempt, not the landing`() {
        // The field failure itself: the requester's tile has not attached, and
        // requestFocus() throws rather than answering. The old one-shot was
        // spent on exactly this; a driven landing tries again next frame.
        var attempts = 0
        var frames = 0
        val framesTook = runBlocking {
            driveProfileLanding(
                ask = {
                    attempts++
                    if (attempts <= 2) error("FocusRequester is not initialized")
                },
                awaitFrame = { frames++ },
                observed = { attempts >= 3 }
            )
        }
        assertEquals("the landing survives the throw and lands", 3, framesTook)
        assertEquals(3, attempts)
        assertEquals(3, frames)
    }

    @Test
    fun `a target that reports focus immediately is still observed after a frame`() {
        val tile = ScriptedTile(landsOnAttempt = 1)
        assertEquals(1, tile.drive())
        assertEquals(
            "no same-frame landing: the tile's onFocusChanged lands with the layout " +
                "that follows the ask",
            listOf("ask", "frame", "check"),
            tile.events
        )
    }

    // ── the picker's wiring ───────────────────────────────────────────────

    private val picker by lazy { normalized(PICKER) }
    private val driver by lazy { normalized(DRIVER) }

    @Test
    fun `the one-shot landing is gone`() {
        assertFalse(
            "a single unobserved ask at first composition is what locked the Fire TV " +
                "out of the app",
            picker.contains("LaunchedEffect(Unit) { firstRequester.requestFocus() }")
        )
        assertTrue(
            "the landing is keyed on the tile it is aimed at, or the arrival of the " +
                "profiles - the very data the ask is for - never re-runs it",
            picker.contains("LaunchedEffect(landingKey) {")
        )
        assertTrue(
            "which is the first profile's own id, or MANAGE when the list is empty",
            picker.contains(
                "val landingKey = firstProfile?.let { \"profile:\${it.id}\" } ?: MANAGE_TILE_KEY"
            )
        )
    }

    @Test
    fun `the landing is driven to the tile's own focus state`() {
        assertTrue(
            "the driving loop is the shared retry table",
            picker.contains("driveProfileLanding(ask = askFirstTile, observed = ::landed)")
        )
        assertTrue(
            "and the verdict is the (0,0) tile's focus state, not the ask's answer",
            picker.contains("fun landed(): Boolean = focusedTileNow() == FIRST_TILE")
        )
        assertTrue(
            "the target is still the grid's first tile",
            picker.contains("focusRequester = if (rowIndex == 0 && colIndex == 0) firstRequester else null")
        )
        assertTrue(
            "every tile reports its own focus, which is what makes the verdict possible",
            picker.contains("onFocused(it.isFocused)")
        )
    }

    @Test
    fun `a landing lost after it worked is repaired`() {
        assertTrue(
            "the watchdog sleeps on the nobody-holds-focus state",
            picker.contains("snapshotFlow { nothingFocused() }.first { it }")
        )
        assertTrue(
            "and only repairs the state where BOTH the tiles and their parent say no " +
                "node holds focus - repairing on a single signal could fight a viewer " +
                "whose focus merely moved",
            picker.contains("fun nothingFocused(): Boolean =") &&
                picker.contains("!pickerHasFocus && focusedTileNow() == null && !gateOpen()")
        )
        assertTrue(
            "the screen-wide signal is the tiles' own parent",
            picker.contains(".onFocusChanged { pickerHasFocus = it.hasFocus }")
        )
        assertTrue(
            "a stale tile index from a rebuilt list must not read as \"still focused\"",
            picker.contains("fun focusedTileNow(): Int? =") &&
                picker.contains("focusedTile?.takeIf { it in 0 until profiles.size + 1 }")
        )
        assertTrue(
            "and the hold is a few frames, so a rebuilt frame is not a lost viewer",
            picker.contains(
                "while (emptyFrames < PROFILE_NO_FOCUS_FRAMES && nothingFocused()) {"
            )
        )
        assertTrue(
            "the repair re-drives the same landing",
            picker.contains("Log.d(PROFILE_FOCUS_TAG, \"nobody holds focus: re-driving the landing\")")
        )
    }

    @Test
    fun `the watchdog never competes with the PIN or exit gate`() {
        assertTrue(
            "both gates are what \"a dialog owns focus\" means here",
            picker.contains("fun gateOpen(): Boolean = pinTarget != null || exitGateAction != null")
        )
        assertTrue(
            "and nothing is repaired while either is up",
            picker.contains("!gateOpen()")
        )
    }

    @Test
    fun `every branch of the landing names itself in the log`() {
        assertEquals(
            "the tag the field capture looks for",
            "\"PROFILE_FOCUS\"",
            Regex("internal const val PROFILE_FOCUS_TAG = (\"[^\"]*\")")
                .find(driver)
                ?.groupValues
                ?.get(1)
        )
        listOf(
            "\"landed: \$landingName after \$frames frame(s)\"",
            "\"landing exhausted \$PROFILE_LANDING_FRAMES frames: \$landingName\"",
            "\"re-driven landing: \$landingName after \$redriven frame(s)\"",
            "\"re-driven landing exhausted \$PROFILE_LANDING_FRAMES frames: \""
        ).forEach { line ->
            assertTrue("the log is missing: $line", picker.contains(line))
        }
        assertTrue(
            "a failed drive is a warn, so a normal session has none of them",
            picker.contains(
                "Log.w(\nPROFILE_FOCUS_TAG,\n\"landing exhausted " +
                    "\$PROFILE_LANDING_FRAMES frames: \$landingName\""
            )
        )
    }

    @Test
    fun `what the tiles do is untouched`() {
        assertTrue(
            "the PIN gate still asks for the destination profile's own code",
            picker.contains("ProfileManager.hasPin(profile) -> {\npinTarget = profile")
        )
        assertTrue(
            "the kids exit gate still records what to run afterwards",
            picker.contains("exitGateAction = ExitGateAction.Switch(profile)")
        )
        assertTrue(
            "and the trailing MANAGE tile is still an action among the names",
            picker.contains("name = \"MANAGE\",")
        )
    }

    /** Whitespace-collapsed source with whole-line comments dropped. */
    private fun normalized(path: String): String {
        val root = findSourceRoot()
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().lineSequence()
            .joinToString("\n") { line -> if (line.trimStart().startsWith("//")) "" else line.trim() }
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/ui/profiles"
        const val PICKER = "$PACKAGE/ProfilePickerScreen.kt"
        const val DRIVER = "$PACKAGE/ProfilePickerFocus.kt"
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
}
