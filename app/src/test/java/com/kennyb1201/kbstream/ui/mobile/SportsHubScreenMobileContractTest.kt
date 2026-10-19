package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sports hub's turn at Phase 2's rule: one `FormFactor` branch at the TV
 * entry, the phone layout in a tv-free file under `ui/mobile/`, and the same
 * activity-scoped ViewModel behind both.
 *
 * A JVM test cannot compose either layout, so what is pinned here is the
 * contract: the dispatch and what it hands over, the touch widget layer, the
 * list of ViewModel actions a phone can reach, and the parts that must not be
 * lost in the port - the shared rule objects (so a phone card and a TV card
 * cannot disagree), the sheet's pinned WATCH, the long-press follow, and the
 * one-shot reopen of a sheet after the player.
 */
class SportsHubScreenMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun tvScreen(): String =
        source("com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt")

    private fun mobileScreen(): String =
        source("com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt")

    @Test
    fun `the TV entry dispatches on the form factor and hands the launch over`() {
        val tv = tvScreen()
        val dispatch = tv.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val call = tv.indexOf("SportsHubScreenMobile(")
        assertTrue("SportsHubScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call SportsHubScreenMobile", call > dispatch)
        val branch = tv.substring(call).substringBefore("return")
        listOf(
            "viewModel = viewModel",
            "onPlayChannels = onPlayChannels",
            "initialDetailGameId = initialDetailGameId",
            "initialDetailTournamentId = initialDetailTournamentId"
        ).forEach { handover ->
            assertTrue("the branch must hand over $handover", branch.contains(handover))
        }
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            tv.substring(call).substringBefore("val ").contains("return")
        )
        assertTrue(
            "the TV page must still be below the branch",
            tv.substringAfter("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
                .substringAfter("return")
                .contains("val enabled by viewModel.enabledLeagues")
        )
    }

    @Test
    fun `the phone page is built on the touch twins and the mobile theme`() {
        val mobile = mobileScreen()
        assertTrue("the phone page must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "the phone page must not branch on the form factor itself",
            !mobile.contains("FormFactor")
        )
        listOf(
            "ui.components.touch.KBTextField",
            "ui.components.touch.KBButton",
            "ui.components.touch.KBCard",
            "ui.components.touch.KBDialogPanel"
        ).forEach { component ->
            assertTrue("the phone page must use $component", mobile.contains(component))
        }
        assertFalse(
            "no tv material in a phone page",
            mobile.contains("androidx.tv.material3")
        )
        assertFalse(
            "and no D-pad focus plumbing",
            mobile.contains("FocusRequester") || mobile.contains("onPreviewKeyEvent")
        )
        assertTrue(
            "the phone page must drive the shared SportsHubViewModel",
            mobile.contains("viewModel: SportsHubViewModel")
        )
    }

    @Test
    fun `every hub action a TV viewer has is reachable on a phone`() {
        val mobile = mobileScreen()
        // One entry per ViewModel action the TV page wires.
        val actions = listOf(
            "selectLeague",
            "setView",
            "refresh",
            "retryLineup",
            "retryStandings",
            "setTeamFavorite",
            "setLeagueEnabled",
            "setGameReminders",
            "moveLeague",
            "clearChannelMemory",
            "rememberChannel",
            "rememberTournamentChannel",
            "openDetail",
            "closeDetail",
            "rememberHubScroll",
            "forgetHubScroll",
            "resumeLivePoll",
            "pauseLivePoll"
        )
        actions.forEach { action ->
            assertTrue(
                "the phone hub must reach viewModel.$action",
                mobile.contains("viewModel::$action") || mobile.contains(".${action}(")
            )
        }
    }

    @Test
    fun `the phone slate is judged by the TV rules, not a second copy`() {
        val mobile = mobileScreen()
        listOf(
            "SportsDetailRules",
            "SportsGameOrder",
            "SportsSearchRules",
            "SportsReopenRules",
            "SportsStandingsLayout"
        ).forEach { rule ->
            assertTrue("the phone hub must import $rule", mobile.contains(rule))
        }
        assertFalse(
            "a local status-line spelling is how the card and the sheet drift apart",
            mobile.contains("fun statusLine(")
        )
        assertTrue(
            "the partition and favourites-first order must be the shared rule",
            mobile.contains("SportsGameOrder.favoritesFirst(")
        )
        assertTrue(
            "the sheet's status, score, rows and watch label must come from the shared rules",
            mobile.contains("SportsDetailRules.statusLine(") &&
                mobile.contains("SportsDetailRules.scoreLine(") &&
                mobile.contains("SportsDetailRules.detailRows(") &&
                mobile.contains("SportsDetailRules.watchLabel(")
        )
        assertTrue(
            "search must read across the loaded leagues, as on TV",
            mobile.contains("SportsSearchRules.results(searchSections, searchQuery, searchLeague)")
        )
        assertTrue(
            "a return from the player must reopen the same sheet",
            mobile.contains("SportsReopenRules.game(") &&
                mobile.contains("SportsReopenRules.tournament(")
        )
        assertTrue(
            "standings stay one column on a phone (the shared layout's own threshold)",
            mobile.contains("SportsStandingsLayout.columns(groups, widthDp = 0)")
        )
    }

    @Test
    fun `the card opens its sheet on a tap and the follow dialog on a long press`() {
        val mobile = mobileScreen()
        assertTrue(
            "a tap opens the sheet the card belongs to",
            mobile.contains("onClick = onOpenDetail") &&
                mobile.contains("onLongClick = onEditFavorites")
        )
        assertTrue(
            "a played tournament hands the same launch callback to the guide's player",
            mobile.contains("onPlayChannels(null, null, channels)") ||
                mobile.contains("onPlayChannels(")
        )
        assertTrue(
            "a tournament the playlist cannot carry opens its sheet instead",
            mobile.contains("if (channel != null) onPlayChannels(null, null, channels)")
        )
        assertTrue(
            "a manually picked backup is remembered as a correction",
            mobile.contains("viewModel.rememberChannel(game, chosen.id)") &&
                mobile.contains("viewModel.rememberTournamentChannel(event, chosen.id)")
        )
    }

    @Test
    fun `the sheet pins WATCH below the scrolling body`() {
        val mobile = mobileScreen()
        val sheet = mobile.substringAfter("private fun GameDetailSheetMobile(")
            .substringBefore("private fun DetailHeaderMobile(")
        val scroll = sheet.indexOf("verticalScroll(rememberScrollState())")
        val watch = sheet.lastIndexOf("label = SportsDetailRules.watchLabel(true)")
        assertTrue("the sheet must scroll its middle", scroll >= 0)
        assertTrue("and pin the WATCH action after it", watch > scroll)
        assertTrue(
            "the disabled action must carry the honest reason, not a bare label",
            mobile.contains(
                "SportsDetailRules.watchLabel( hasChannel = false, lineupMissing = lineupMissing, " +
                    "matchingDone = matchingDone )"
            )
        )
        assertTrue(
            "stats are drawn under the shared hasStats gate, so an empty summary draws nothing",
            mobile.contains("takeIf { SportsDetailRules.hasStats(it) }")
        )
        assertTrue(
            "the LIVE beat is one shared value, paused while a sheet is open",
            mobile.contains("rememberLivePulseMobile(enabled = pulseEnabled)") &&
                mobile.contains("rememberReducedMotion()")
        )
    }
}
