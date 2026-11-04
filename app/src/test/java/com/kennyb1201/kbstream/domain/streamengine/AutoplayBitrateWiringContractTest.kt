package com.kennyb1201.kbstream.domain.streamengine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the learned bitrate ceiling is wired: who passes the debrid set, where the
 * gate sits, and who may write.
 *
 * [AutoplayBitrateTest] pins the numbers. This pins the three ways the feature
 * can be silently wrong while every number stays right:
 *
 *  - **a caller that passes no debrid set** disables the exemption, and then an
 *    AIOStreams row - a debrid pick with an ordinary-looking link - is gated
 *    like a hoster link. §0 of the spec is explicit that this is a violation,
 *    not a default, so every call is read here rather than trusted;
 *  - **the gate sitting anywhere but the pick** would change the picker's order,
 *    the badges, or a manual press. Only [AutoplayPick.pick] may call it;
 *  - **the ceiling syncing** would let one box's bad night cap another's
 *    auto-play, so the key must be in the device-local exclusion list and never
 *    in the synced one - the same contract the resolution ceiling has.
 *
 * Source-read because the store and the two engines are Android objects: the
 * pick needs a Context, and the players' sessions cannot be driven in a JVM
 * test. The rule itself is pure and tested beside this file.
 */
class AutoplayBitrateWiringContractTest {

    private val root: File by lazy { findSourceRoot() }

    /** [AutoplayPick.pick]'s signature and body, bracketed. */
    private val pickBody: String by lazy { functionBody(mainSource("AutoplayPick.kt"), "fun pick(") }

    @Test
    fun `every pick caller passes the debrid set its ranker was built with`() {
        val callers = mainSources()
            .filter { it.name != "AutoplayPick.kt" }
            .flatMap { file ->
                calls(file.readText(), "AutoplayPick.pick(").map { call -> file.name to call }
            }
        assertTrue("no callers found at all - the scan is broken", callers.isNotEmpty())
        val names = callers.map { it.first }.toSet()
        assertTrue(
            "the three known surfaces must each be read: found $names",
            names.containsAll(
                setOf("MainActivity.kt", "StreamsScreen.kt", "DownloadActions.kt")
            )
        )
        callers.forEach { (file, call) ->
            assertTrue(
                "$file calls AutoplayPick.pick without a debridAddons set, which silently " +
                    "gates debrid picks: $call",
                call.contains("debridAddons")
            )
            assertTrue(
                "$file must pass a set read from this install, not an empty set",
                call.contains("DebridAddons.forDevice(")
            )
        }
    }

    @Test
    fun `the gate is the pick's last step, on top of the ceiling and the episode rules`() {
        val pick = pickBody
        assertTrue(
            "the learned ceiling is applied where the resolution ceiling already is",
            pick.contains("AutoplayBitrate.gatedPick(") &&
                pick.contains("AutoPlayQuality.cappedPick(") &&
                pick.contains("EpisodeMatch.autoplayPick(")
        )
        assertTrue(
            "the gate wraps the other two, so it runs on the pick they made",
            pick.indexOf("AutoplayBitrate.gatedPick(") <
                pick.indexOf("AutoPlayQuality.cappedPick(") &&
                pick.indexOf("AutoPlayQuality.cappedPick(") <
                pick.indexOf("EpisodeMatch.autoplayPick(")
        )
        assertTrue(
            "and it reads this device's learned ceiling, not a parameter a surface could forget",
            pick.contains("getLearnedSustainableMbps(")
        )
    }

    @Test
    fun `the gate is a pick rule - nothing else in the app may call it`() {
        val others = mainSources()
            .filter { it.name != "AutoplayPick.kt" && it.name != "AutoplayBitrate.kt" }
            .filter { it.readText().contains("gatedPick(") }
            .map { it.name }
        assertTrue(
            "the picker's order, its badges and every manual press must stay as they are; " +
                "only the auto-play pick may consult the gate. Found: $others",
            others.isEmpty()
        )
    }

    @Test
    fun `both engines write both outcomes, through the one shared rule`() {
        listOf("NativePlayerActivity.kt", "MpvPlayerActivity.kt").forEach { name ->
            val source = mainSource(name)
            assertTrue(
                "$name must record its qualified stall (Exo's downshift, MPV's watchdog giving up)",
                source.contains("noteQualifiedStallForCeiling()") &&
                    source.contains("AppPreferences.noteQualifiedStall(")
            )
            assertTrue(
                "$name must record a clean play at session end",
                source.contains("noteCleanPlaybackForCeiling()") &&
                    source.contains("AppPreferences.noteCleanPlayback(")
            )
            assertTrue(
                "$name must refuse to write twice in one session",
                source.contains("ceilingWritten") && source.contains("ceilingStallSeen")
            )
            assertTrue(
                "$name must read its session through the shared rule, so a debrid session " +
                    "cannot teach the ceiling",
                source.contains("AutoplayBitrate.sessionEstimateMbps(")
            )
        }
        val native = mainSource("NativePlayerActivity.kt")
        assertTrue(
            "Exo's write hangs off the downshift's own due branch, after the stall is qualified",
            native.indexOf("rebufferDownshift.due(now)") <
                native.indexOf("noteQualifiedStallForCeiling()")
        )
        val mpv = mainSource("MpvPlayerActivity.kt")
        val gaveUp = mpv.indexOf("MPV stall gave up")
        val mpvWrite = mpv.indexOf("noteQualifiedStallForCeiling()")
        assertTrue("MPV's give-up branch must still be there", gaveUp >= 0)
        assertTrue(
            "MPV's write hangs off the watchdog giving up, and lands before the card it shows",
            gaveUp < mpvWrite && mpvWrite < mpv.indexOf("showError(", gaveUp)
        )
    }

    @Test
    fun `the ceiling is device-local, exactly like the resolution ceiling`() {
        val payload = mainSource("SyncPrefsPayload.kt")
        val excluded = braced(payload, "internal val EXCLUDED_PREF_KEYS = setOf(")
        val synced = braced(payload, "internal val SYNCED_PREF_KEYS = setOf(")
        assertTrue(
            "the learned ceiling describes THIS box's line, so it may never sync",
            excluded.contains("\"learned_sustainable_mbps\"")
        )
        assertFalse(
            "and it must not be on the synced allow-list",
            synced.contains("\"learned_sustainable_mbps\"")
        )
        val prefs = mainSource("AppPreferences.kt")
        assertTrue(
            "the key the store writes is the key that is excluded",
            prefs.contains("private const val KEY_LEARNED_SUSTAINABLE_MBPS = " +
                "\"learned_sustainable_mbps\"")
        )
    }

    @Test
    fun `the estimate reuses the ranker's size reading instead of a second parser`() {
        val bitrate = mainSource("AutoplayBitrate.kt")
        assertTrue(
            "one size parser: the ranker's",
            bitrate.contains("StreamRanker.sizeGb(")
        )
        assertFalse(
            "no second size parser, and no resolution label read as a bitrate",
            bitrate.contains("videoSize") ||
                bitrate.contains("Regex(") ||
                bitrate.contains("resolutionRank")
        )
    }

    // ── Source access ───────────────────────────────────────────────────

    private fun mainSource(name: String): String {
        val match = mainSources().firstOrNull { it.name == name }
            ?: throw AssertionError("source missing: $name")
        return match.readText()
    }

    private fun mainSources(): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Every call of [marker] in [source], as the whole bracketed call.
     *
     * Balanced on purpose: an argument list that grows a lambda is exactly the
     * call this has to read whole, and a fixed character window would cut it.
     */
    private fun calls(source: String, marker: String): List<String> {
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf(marker, from)
            if (at < 0) return found
            var depth = 0
            var end = source.length
            for (i in at until source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = i + 1
                            break
                        }
                    }
                }
            }
            found += source.substring(at, end)
            from = end
        }
    }

    /** A function's signature and body: [marker] to the matching closing brace. */
    private fun functionBody(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("$marker is missing", start >= 0)
        val signatureEnd = matching(text, text.indexOf('(', start), '(', ')')
        assertTrue("$marker must have a signature", signatureEnd > start)
        val open = text.indexOf('{', signatureEnd)
        assertTrue("$marker must have a body", open > signatureEnd)
        val end = matching(text, open, '{', '}')
        return text.substring(start, end)
    }

    /** The index just past the bracket that closes the one at [open]. */
    private fun matching(text: String, open: Int, opener: Char, closer: Char): Int {
        if (open < 0) return -1
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return -1
    }

    /**
     * The bracketed block that starts at [startMarker]: from the marker to the
     * parenthesised argument list that follows it, balanced.
     *
     * A fixed `")"` search would cut a `setOf( ... )` at its first element on a
     * wrapped line, which is how a contract test ends up asserting against a
     * fragment of the code it means to read.
     */
    private fun braced(text: String, startMarker: String): String {
        val start = text.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val open = text.indexOf('(', start)
        assertTrue("$startMarker must have a bracketed block", open > start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("$startMarker's block is not closed")
    }

    private companion object {
        fun findSourceRoot(): File {
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
}
