package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: the frame-time meter reaches the TV.
 *
 * Round five landed the sampler and wired it into three phone surfaces. Every TV
 * paste since then has been silent about frames - the 09:41 walk/fling on build
 * 357 carried `recomp: railItems=1111, cardDiskDecodes=1096` and no `frame:` line -
 * because nothing on TV ever started a session. This class pins the two calls that
 * fix that, and pins that they are the SAME meter: one label per surface, one line
 * per gesture, no second measurement bolted on beside it.
 *
 * S1-S4 (a paste that actually carries the line, a paste with no scroll that does
 * not) need the TCL; the meter's own session rules are already pinned by the
 * phone's dig and unchanged here.
 */
class TvScrollFrameMeterContractTest {

    private val tvHome = main("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val tvGuide = main("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
    private val meter = main("com/kennyb1201/kbstream/ui/components/ScrollFrameMeter.kt")
    private val phoneHome = main("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    private val phoneCatalog = main("com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt")
    private val phoneGuide = main("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")

    // ── F1: TV Home meters the rails list ─────────────────────────────────

    @Test
    fun `the TV Home meters its page list, under the phone's label`() {
        assertEquals(
            "one surface, one session",
            1,
            Regex("""TrackScrollFrames\(""").findAll(tvHome).count()
        )
        assertTrue(
            "the rails LazyColumn's own state, not a rail's",
            tvHome.contains("TrackScrollFrames(label = \"home\", state = railListState)")
        )
        assertTrue(
            "and that is the state the page scrolls with",
            tvHome.contains("state = railListState") &&
                tvHome.contains("val railListState = rememberIdleLazyListState(")
        )
        assertTrue(
            "the spec's own label, so a TV paste and a phone paste read the same",
            tvHome.contains("label = \"home\"")
        )
    }

    @Test
    fun `the per-rail horizontal lists are not metered`() {
        // F2/out of scope: the phone meters the page list, not a rail, and so does
        // the TV. The individual rail states keep their names and get no call.
        assertTrue(
            "no session started off a rail's own state",
            !tvHome.contains("TrackScrollFrames(label = \"rail") &&
                !tvHome.contains("TrackScrollFrames(label = \"cw")
        )
    }

    // ── F2: TV Guide meters the channel list ──────────────────────────────

    @Test
    fun `the TV Guide meters its channel list`() {
        assertEquals(
            "one surface, one session",
            1,
            Regex("""TrackScrollFrames\(""").findAll(tvGuide).count()
        )
        assertTrue(
            "the vertical channel list's state",
            tvGuide.contains("TrackScrollFrames(label = \"guide\", state = channelListState)")
        )
        assertTrue(
            "which is the guide's own rememberLazyListState",
            tvGuide.contains("val channelListState = rememberLazyListState()")
        )
    }

    // ── both call sites must be able to see the composable ──────────────────

    @Test
    fun `both TV surfaces import the meter`() {
        // ui.home and ui.iptv are different packages from ui.components: without
        // the import the call does not resolve, which is the kind of break the
        // harness cannot see and CI finds ten minutes later.
        val import = "import com.kennyb1201.kbstream.ui.components.TrackScrollFrames"
        assertTrue("Home's import", tvHome.contains(import))
        assertTrue("Guide's import", tvGuide.contains(import))
    }

    // ── F3: nothing else changes ──────────────────────────────────────────

    @Test
    fun `it is the same meter the phone calls, and it still samples only while scrolling`() {
        assertTrue(
            "one implementation, one session per surface - and the close is the " +
                "per-label endIf, so stopping a scroll does not end a walk's session",
            meter.contains("snapshotFlow { state.isScrollInProgress }") &&
                meter.contains(
                    "if (scrolling) UiFrameTrace.begin(label) else UiFrameTrace.endIf(label)"
                )
        )
        assertTrue(
            "read inside a flow, so the meter adds no recomposition of its own",
            meter.contains("distinctUntilChanged()")
        )
        assertTrue(
            "the TV files call the composable rather than starting a session by hand",
            !tvHome.contains("UiFrameTrace.begin(") && !tvGuide.contains("UiFrameTrace.begin(")
        )
        val phone = listOf(
            "home" to phoneHome,
            "catalog" to phoneCatalog,
            "guide" to phoneGuide
        )
        phone.forEach { (label, source) ->
            assertTrue(
                "S4: the phone's \"$label\" line is untouched",
                source.contains("TrackScrollFrames(label = \"$label\"")
            )
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun main(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }
}
