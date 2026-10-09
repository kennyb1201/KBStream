package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnosis switch: the `SPORTS DIAG` log lines, pinned as formats.
 *
 * The hub's whole failure mode is invisible from the outside - a hub that could
 * not read a playlist looks exactly like a hub whose playlist carries none of
 * today's games, because both draw "not in your playlist" on every card. The
 * three counts on one line are what tells the two apart from a logcat capture,
 * so the formats are a contract, not a debugging leftover: rename a field or
 * shuffle the order and the greps everyone was told to run stop working.
 *
 * These are source contracts because the numbers themselves need a playlist, a
 * guide and ESPN - there is no TV in CI. What is pinned is that each line exists,
 * carries the named fields in the documented order, and that the hub reads the
 * list of feeds rather than a single one.
 */
class SportsMatchDiagnosticsContractTest {

    private fun flat(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    private val model: String by lazy { flat(VIEW_MODEL) }
    private val hub: String by lazy { flat(HUB) }
    private val activity: String by lazy { flat(ACTIVITY) }

    /** A literal `$` in the Kotlin source being asserted about. */
    private val d = '$'

    @Test
    fun `the three counts are logged as one greppable line`() {
        assertTrue(
            "the lineup, the guide index and the EPG rows, in that order",
            model.contains(
                "SPORTS DIAG channels=${d}{channels.size} guideIndex=${d}{guideIndex.size}"
            )
        )
        assertTrue(
            "with the program count on the same line",
            model.contains("\"programs=${d}{programs.size}\"")
        )
        assertTrue(
            "and the lineup-is-empty case logging the same shape, so one grep answers it first",
            model.contains("\"SPORTS DIAG channels=0 guideIndex=0 programs=0\"")
        )
        assertTrue(
            "the per-card line names the game and the strings tiers 2 and 3 match on",
            model.contains("SPORTS DIAG game=${d}{game.id} broadcasts=${d}{game.broadcastNames}")
        )
        assertTrue(
            "and a tournament event gets its own",
            model.contains("SPORTS DIAG event=${d}{event.id} broadcasts=${d}{event.broadcastNames}")
        )
    }

    @Test
    fun `the line says which stage is empty, so the log needs no interpreter`() {
        assertTrue(
            "a lineup with no guide index is called out",
            model.contains("channels=N guideIndex=0")
        )
        assertTrue(
            "and a guide with nothing to say about these games is distinguished from it",
            model.contains("channels=N guideIndex=M programs=0")
        )
    }

    @Test
    fun `the summary line counts feeds as well as cards`() {
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

    @Test
    fun `the hub matches lists of feeds, not a single one`() {
        assertTrue(
            "the ViewModel publishes a list per card",
            model.contains("MutableStateFlow<Map<String, List<IptvChannel>>>(emptyMap())") &&
                model.contains("val matches: StateFlow<Map<String, List<IptvChannel>>>")
        )
        assertTrue(
            "and resolves them through the ordered matcher",
            // A game is matched with the correction memory handed in (the viewer's
            // own past pick beats the tiers); a tournament has no team to key it
            // by, so it is matched without one.
            model.contains("SportsChannelMatcher.matches(game, channels, programs, remembered)") &&
                model.contains("SportsChannelMatcher.matches(event, channels, programs)")
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
    }
}
