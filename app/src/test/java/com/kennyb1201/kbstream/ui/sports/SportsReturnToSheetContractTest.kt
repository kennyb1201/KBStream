package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.decodeScreen
import com.kennyb1201.kbstream.encodeScreen
import com.kennyb1201.kbstream.stableBackDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back from a game played out of the sports hub returns to the sheet it was
 * played from.
 *
 * Two causes were stacked here, and both are pinned. The player hardcoded the
 * guide for every `parentType == "channel"` launch, so the hub's own
 * `returnTo` was never read - the destination is now the launch's own, as it is
 * for every other screen. And the sheet itself was composable state that the
 * sheet's WATCH nulled on the way out, so it could not come back on its own -
 * the Screen now carries the game's (or tournament's) id, and the hub reopens
 * the sheet from it once, via the same state a card tap sets.
 *
 * What is deliberately NOT changed is pinned too: the guide's launches still
 * state `Screen.Guide`, and a Screen.Sports persists as the plain hub, so a
 * restored app never reopens a sheet the viewer did not open.
 *
 * The two branches that used to answer "channel" with the guide are pinned as
 * well, because they are the other half of the same report. A channel whose
 * sources were picked by hand, or played with auto-select off, left the player
 * through a branch that had never heard of the hub - so it went to Live TV no
 * matter how the game was launched.
 */
class SportsReturnToSheetContractTest {

    private val hub: String by lazy { source(HUB).replace(Regex("\\s+"), " ") }
    private val activity: String by lazy { source(ACTIVITY).replace(Regex("\\s+"), " ") }

    /** A hub channel launch, which is what a game played from the guide or the hub is. */
    private fun channelPlayer(returnTo: Screen) = Screen.Player(
        url = "http://example.test/live.m3u8",
        parentId = "chan-1",
        parentType = "channel",
        season = null,
        episode = null,
        episodeStreamId = null,
        itemName = "Celtics at Lakers",
        itemPoster = null,
        startPositionMs = 0,
        returnTo = returnTo
    )

    // ── 1. the back destination ───────────────────────────────────────────

    @Test
    fun `back from a hub channel player lands on the hub, sheet and all`() {
        val launchedFrom = Screen.Sports(detailGameId = "401", detailTournamentId = null)

        assertEquals(
            "the destination is the launch's own returnTo, ids included",
            launchedFrom,
            stableBackDestination(channelPlayer(launchedFrom))
        )
    }

    @Test
    fun `back from a guide channel player still lands on the guide`() {
        assertEquals(
            "every guide launch states the guide, so nothing about it changes",
            Screen.Guide,
            stableBackDestination(channelPlayer(Screen.Guide))
        )
    }

    @Test
    fun `the player branch no longer hardcodes the guide for channel launches`() {
        assertTrue(
            "the branch delegates to the launch's own destination",
            activity.contains("is Screen.Player -> stableBackDestination(current.returnTo)")
        )
        assertFalse(
            "which is exactly what the channel-means-guide hardcode prevented",
            activity.contains("is Screen.Player -> if (current.parentType == \"channel\")")
        )
        assertTrue(
            "and every channel launch still states its own destination: the guide's own say Guide",
            activity.contains("returnTo = Screen.Guide")
        )
        assertTrue(
            "the Streams branch reads the same launch: a channel's picker is the same session, and the " +
                "hub reaches it whenever auto-select is off",
            activity.contains("is Screen.Streams -> stableBackDestination(current.returnTo)")
        )
    }

    @Test
    fun `backing out of a playing channel reads the launch, guide or hub alike`() {
        assertTrue(
            "a channel always exits to its launch's own destination, so a game's sheet comes with it",
            activity.contains(
                "if ( current.parentType == \"channel\" || AppPreferences.getAutoSelectStream(context) ) " +
                    "{ screen = stableBackDestination(current.returnTo) }"
            )
        )
        assertFalse(
            "and the channel-means-guide hardcode is gone from the exit path too, not just the Back " +
                "handler",
            activity.contains("if (current.parentType == \"channel\") { screen = Screen.Guide }")
        )
    }

    @Test
    fun `the hub is launched with the id of the sheet WATCH was pressed in`() {
        assertTrue(
            "the launch carries the ids back as the hub's destination",
            activity.contains(
                "returnTo = Screen.Sports( detailGameId = gameId, detailTournamentId = tournamentId )"
            )
        )
        assertTrue(
            "and the hub reads them back when it is entered",
            activity.contains("initialDetailGameId = current.detailGameId") &&
                activity.contains("initialDetailTournamentId = current.detailTournamentId")
        )
        assertTrue(
            "the game sheet's WATCH hands over its own game, and only its game",
            hub.contains(
                "onPlay = { feeds -> detailGame = null onPlayChannels(game.id, null, feeds) }"
            )
        )
        assertTrue(
            "the tournament sheet does the same for its event",
            hub.contains(
                "onPlay = { feeds -> detailTournament = null " +
                    "onPlayChannels(null, event.id, feeds) }"
            )
        )
    }

    @Test
    fun `a card that plays without a sheet opens no sheet on the way back`() {
        assertTrue(
            "the tournament card's own playback carries no ids, because it raised no sheet",
            hub.contains("onClick = { onPlayChannels(null, null, channels) }")
        )
    }

    // ── 2. the one-shot reopen ────────────────────────────────────────────

    @Test
    fun `the reopen fires once, then stays closed`() {
        assertTrue(
            "the effect bails out once it has fired, so closing the sheet sticks",
            hub.contains("if (reopenConsumed) return@LaunchedEffect")
        )
        assertTrue(
            "the latch is per entry - a fresh hub starts unconsumed",
            hub.contains(
                "var reopenConsumed by remember(initialDetailGameId, initialDetailTournamentId) " +
                    "{ mutableStateOf(false) }"
            )
        )
        assertTrue(
            "and it is set exactly where the sheet is raised",
            hub.contains(
                "detailGame = game detailTournament = tournament reopenConsumed = true"
            )
        )
        assertEquals(
            "and the sheet is raised from exactly one place, so nothing else can reopen it",
            1,
            Regex("reopenConsumed = true").findAll(hub).count()
        )
    }

    @Test
    fun `the reopened sheet goes through the path a card tap takes`() {
        assertTrue(
            "a card tap and the reopen raise the same state",
            hub.contains("onOpenDetail = { detailGame = it }") &&
                hub.contains("detailGame?.let { game ->")
        )
        assertTrue(
            "so the sheet's own fetch policy - openDetail on a live game, closeDetail on close - is unchanged",
            hub.contains("LaunchedEffect(detailGame) {") &&
                hub.contains("if (open == null) viewModel.closeDetail() else viewModel.openDetail(open)")
        )
        assertTrue(
            "and the hub's Back ladder is untouched: Back on the hub is still the guide",
            activity.contains("is Screen.Sports -> Screen.Guide")
        )
    }

    @Test
    fun `the reopen is looked up with the pure rules, not re-implemented`() {
        assertTrue(
            "the hub asks the rules which game the id names",
            hub.contains("SportsReopenRules.game(sections, initialDetailGameId)")
        )
        assertTrue(
            "and which tournament",
            hub.contains("SportsReopenRules.tournament(sections, initialDetailTournamentId)")
        )
        assertFalse(
            "with no second copy of the lookup in the composable",
            hub.contains("sections.flatMap { it.games }")
        )
    }

    // ── 4. what is persisted ──────────────────────────────────────────────

    @Test
    fun `a Screen Sports persists as the plain hub`() {
        val withSheet = encodeScreen(Screen.Sports(detailGameId = "401", detailTournamentId = null))

        assertEquals("the type the restore reads is unchanged", "sports", withSheet.getString("type"))
        assertFalse(
            "the one-shot ids are never persisted",
            withSheet.has("detailGameId") || withSheet.has("detailTournamentId")
        )
        assertEquals(
            "so a restored app opens the plain hub and reopens no sheet",
            Screen.Sports(),
            decodeScreen(withSheet)
        )
        assertEquals(
            "a tournament id is dropped the same way",
            encodeScreen(Screen.Sports()).toString(),
            encodeScreen(Screen.Sports(detailTournamentId = "ncaa-9")).toString()
        )
        assertEquals(
            "and a hub opened from the guide's header encodes identically",
            encodeScreen(Screen.Sports()).toString(),
            encodeScreen(Screen.Sports(detailGameId = "401", detailTournamentId = "ncaa-9")).toString()
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
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
