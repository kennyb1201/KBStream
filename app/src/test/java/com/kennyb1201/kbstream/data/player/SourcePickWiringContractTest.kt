package com.kennyb1201.kbstream.data.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the remembered source pick is written by a PRESS and read by the AUTO
 * route, in exactly those places (spec 2026-11-15).
 *
 * The rules of the key are [SourcePickKeyTest]'s. What is pinned here is the
 * wiring, which is where this kind of memory usually goes wrong: a write on the
 * error walk (an error-driven advance silently becoming "the viewer's choice"),
 * a write on auto-play's own pick (the app remembering itself), or an AUTO route
 * that resolves without ever asking - which is the report this spec answers.
 *
 * Read from the source: these are Compose click handlers, an Activity's picker
 * rows and a suspend resolve.
 */
class SourcePickWiringContractTest {

    private val tvPicker by lazy { read("ui/streams/StreamsScreen.kt") }
    private val phonePicker by lazy { read("ui/mobile/StreamsScreenMobile.kt") }
    private val mpvPlayer by lazy { read("ui/player/MpvPlayerActivity.kt") }
    private val nativePlayer by lazy { read("ui/player/NativePlayerActivity.kt") }
    private val pick by lazy { read("domain/streamengine/AutoplayPick.kt") }
    private val walk by lazy { read("domain/streamengine/AutoplayWalk.kt") }
    private val activity by lazy { read("MainActivity.kt") }
    private val ranker by lazy { read("domain/streamengine/StreamRanker.kt") }

    /** The one hand-off for an explicit row press, in the TV picker. */
    private val tvSelect by lazy { block(tvPicker, "fun selectSource(") }

    /** The same, phone. */
    private val phoneSelect by lazy { block(phonePicker, "fun selectSource(") }

    // F1: the write

    @Test
    fun `both pickers write the pick on an explicit row press`() {
        assertTrue(
            "the television picker's selectSource is the single hand-off for a " +
                "row press, so it is where the press is written down",
            tvSelect.contains("rememberManualPick(selected)")
        )
        assertTrue(
            "and the phone's, through the same rule",
            phoneSelect.contains("rememberManualPick(selected)")
        )
        assertTrue(
            "the write is the viewer's press, keyed by this episode",
            tvPicker.contains("SourcePickMemory.remember(context, parentId, season, episode, selected)") &&
                phonePicker.contains(
                    "SourcePickMemory.remember(context, parentId, season, episode, selected)"
                )
        )
    }

    @Test
    fun `the player's own SOURCES list writes too`() {
        // The report's exact path: the link was switched inside the player, not
        // in the picker. Both engines draw the same SOURCES list.
        assertTrue(
            "the mpv engine's picker writes the pressed row",
            mpvPlayer.contains("rememberManualSourcePick(stream)")
        )
        assertTrue(
            "and so does the Media3 engine's",
            nativePlayer.contains("rememberManualSourcePick(stream)")
        )
        listOf(mpvPlayer, nativePlayer).forEach { player ->
            assertTrue(
                "the pressed row - never the resolved link a hash row becomes",
                player.contains("SourcePickMemory.remember(this, parentId, season, episode, stream)")
            )
        }
    }

    @Test
    fun `the AUTO route and the error walk never write`() {
        assertFalse(
            "auto-play picking the top link is not a quality choice - if it " +
                "wrote, the app would remember its own guess as the viewer's",
            walk.contains("SourcePickMemory.remember")
        )
        assertFalse(
            "the launch/advance resolve does not write either",
            activity.contains("SourcePickMemory.remember")
        )
        assertFalse(
            "and the ranker knows nothing about this at all - the tiers are " +
                "untouched, cached debrid still outranks HTTP for everyone else",
            ranker.contains("SourcePickMemory")
        )
        // A hash row's preparation inside the player is not a pick: only the
        // press above it is.
        val resolveAndSwitch = block(mpvPlayer, "private fun resolveAndSwitch(")
        assertFalse(
            "preparing a row the walk chose must not write a pick",
            resolveAndSwitch.contains("SourcePickMemory")
        )
    }

    // F1: the replay cache would otherwise answer first

    @Test
    fun `a manual pick drops the three-hour replay entry`() {
        assertTrue(
            "PlayedLinkCache replays the link that last STARTED without " +
                "resolving, so a stale entry would start the old source and the " +
                "pick would never be consulted - the picker forgets it",
            tvPicker.contains("PlayedLinkCache.forget(context, streamNavigationKey(contentType, streamId))") &&
                phonePicker.contains("PlayedLinkCache.forget(context, routeKey)")
        )
        listOf(mpvPlayer, nativePlayer).forEach { player ->
            assertTrue(
                "the players forget on the launching profile's store, the same " +
                    "way their dead-link heal does",
                player.contains("PlayedLinkCache.forgetForProfile(this, key, sessionProfileId)")
            )
        }
    }

    // F2: the AUTO consult

    @Test
    fun `the AUTO pick asks the memory before it ranks`() {
        val recall = pick.indexOf("SourcePickMemory.recall(")
        val rank = pick.indexOf("AutoplayBitrate.gatedPick(")
        assertTrue("the memory is consulted", recall >= 0)
        assertTrue(
            "and it is consulted BEFORE any ranking: a manual press is not a " +
                "ranking outcome, and the ranker puts a cached debrid copy above " +
                "a better HTTP link by design",
            rank > recall
        )
        assertTrue(
            "the match is against the FRESH list, so a link the add-on no longer " +
                "returns falls back to the ranker instead of being played",
            pick.contains("fresh = remaining")
        )
        assertTrue(
            "the episode is named, so a pick can only answer for its own episode",
            pick.contains("parentId = parentId") && pick.contains("season = season")
        )
    }

    @Test
    fun `every auto-play surface hands the memory the show`() {
        assertTrue(
            "the television picker",
            tvPicker.contains("parentId = parentId\n        )") ||
                tvPicker.contains("parentId = parentId")
        )
        assertTrue("the phone picker", phonePicker.contains("parentId = parentId"))
        assertTrue(
            "and the background resolve behind Play from Detail",
            activity.contains("parentId = pending.parentId")
        )
    }

    // F3: the guards

    @Test
    fun `a remembered pick still passes the viewer's ceiling`() {
        assertTrue(
            "the remembered row is checked against max_auto_play_quality, not " +
                "honoured blindly - the ceiling is the viewer's other choice",
            pick.contains("AutoPlayQuality.atOrUnderCap(rememberedPick, cap)")
        )
        assertTrue(
            "and a pick that breaks it falls through to the ranker, which is the " +
                "very next statement",
            pick.indexOf("AutoPlayQuality.atOrUnderCap(rememberedPick, cap)") <
                pick.indexOf("AutoplayBitrate.gatedPick(")
        )
    }

    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) return ""
        val end = source.indexOf("\n    }\n", start)
        return if (end < 0) source.substring(start) else source.substring(start, end)
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
