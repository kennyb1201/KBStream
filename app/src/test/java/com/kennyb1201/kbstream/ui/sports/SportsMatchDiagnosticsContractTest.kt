package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sports match pass, now that its temporary instrumentation is gone.
 *
 * The hub's whole failure mode is invisible from the outside - a hub that could
 * not read a playlist looks exactly like a hub whose playlist carries none of
 * today's games, because both draw "not in your playlist" on every card - which
 * is why a run of `SPORTS DIAG` lines was added while the golf and lineup bugs
 * were being chased. Those bugs are fixed (the tournament tier matches by name
 * alone, the lineup status is a fact of its own), so the diagnostic is removed
 * with them: these tests replace the formats they used to pin with the two
 * things that had to survive the removal - that no `SPORTS DIAG` line, its
 * per-row reject token, or the debug gate that carried it is left in the app,
 * and that the pass still matches a LIST of feeds per card and still hands all
 * of them to the one launch a card tap takes.
 *
 * These are source contracts because the numbers themselves need a playlist, a
 * guide and ESPN - there is no TV in CI.
 */
class SportsMatchDiagnosticsContractTest {

    private fun flat(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    private val model: String by lazy { flat(VIEW_MODEL) }
    private val hub: String by lazy { flat(HUB) }
    private val activity: String by lazy { flat(ACTIVITY) }
    private val matcher: String by lazy { flat(MATCHER) }

    /** A literal `$` in the Kotlin source being asserted about. */
    private val d = '$'

    // ------------------------------------------------- the diagnostic is gone --

    @Test
    fun `the SPORTS DIAG instrumentation is gone from the app`() {
        // The same rule the FOCUS_DIAG removal followed: a Log.w left behind is
        // a release-build log line on every card of every pass - and the golf
        // one was per ROW - so the lines go, and everything that existed only to
        // serve them goes with them rather than sitting dead in the source.
        listOf(model, hub, matcher, activity).forEach { src ->
            assertFalse(
                "SPORTS DIAG must not survive in the app",
                src.contains("SPORTS DIAG")
            )
        }
        assertFalse(
            "the debug gate that carried the per-row dump goes with it",
            model.contains("verboseDiagEnabled")
        )
        assertFalse(
            "and so does the cap that existed only to size that dump",
            model.contains("MAX_TOURNAMENT_DIAG_LINES")
        )
        assertFalse(
            "the reject token was the dump's whole vocabulary: the helper that produced it goes too",
            matcher.contains("tournamentRejectReason")
        )
        listOf("\"not-in-playlist\"", "\"channel-name-mismatch\"", "\"duplicate\"")
            .forEach { token ->
                assertFalse("no reason token may survive either: $token", matcher.contains(token))
            }
    }

    @Test
    fun `the one line the pass still writes is the match summary`() {
        // Not a diagnostic and not removed with them: the summary is the pass's
        // own accounting, and the feed count on it is what a capture is read for
        // once the per-card lines are gone.
        assertTrue(
            "the card count and the matched count are unchanged",
            model.contains(
                "SPORTS MATCHES cards=${d}{games.size + events.size} matched=${d}{found.size}"
            )
        )
        assertTrue(
            "and the feed count is what says whether backups came back with them",
            model.contains("feeds=${d}{found.values.sumOf { it.size }}")
        )
    }

    // -------------------------------------------------- what it protected --

    @Test
    fun `the hub matches lists of feeds, not a single one`() {
        assertTrue(
            "the ViewModel publishes a list per card",
            model.contains("MutableStateFlow<Map<String, List<IptvChannel>>>(emptyMap())") &&
                model.contains("val matches: StateFlow<Map<String, List<IptvChannel>>>")
        )
        assertTrue(
            "and resolves them through the ordered matcher",
            // Both card shapes are matched with the correction memory handed in
            // (the viewer's own past pick beats the tiers): a game keyed by its
            // teams, a tournament by its own stable name.
            model.contains("SportsChannelMatcher.matches(game, channels, programs, remembered,") &&
                model.contains("SportsChannelMatcher.matches(event, channels, programs, remembered,")
        )
        assertTrue(
            "an id absent from the map is still no match, never a guess",
            model.contains(".takeIf { it.isNotEmpty() }")
        )
    }

    @Test
    fun `a card plays the head of the list and the launch carries the rest`() {
        assertTrue(
            "the card's feed is the head, resolved in one place",
            hub.contains("channel = matches.primaryChannel(game.id)") &&
                hub.contains("this[id]?.firstOrNull()")
        )
        assertTrue(
            "and the launch is handed the whole ordered list",
            hub.contains("onPlay = { feeds -> detailGame = null onPlayChannels(feeds) }")
        )
        assertTrue(
            "which the hub turns into the channel plus its backups",
            activity.contains("backups = channels.drop(1)")
        )
        assertTrue(
            "and the launch loads them behind the chosen feed as the player's own ladder",
            activity.contains("sources = listOf(directSource) + backupSources")
        )
    }

    private fun source(relative: String): String {
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
        const val HUB = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val MATCHER = "com/kennyb1201/kbstream/data/sports/SportsChannelMatcher.kt"
    }
}
