package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports Hub's header controls, pinned as faces rather than words
 * (spec 2026-11-18, "Sports Hub header goes icon-only").
 *
 * The change is deliberately both surfaces at once, and none of it can be
 * proved by running anything in CI - there is no TV, and this screen wants a
 * ViewModel, a live tick and ESPN data. So, like the hub's other contracts, it
 * is read out of the source: the words are gone from both headers, the same
 * three glyphs are drawn in the same order, the search field only exists while
 * the magnifier says so, and the view switch is the same matched pair of icon
 * chips with its selected state exposed.
 */
class SportsHubHeaderIconsContractTest {

    private val hub: String by lazy { flat(HUB) }
    private val mobile: String by lazy { flat(MOBILE) }

    @Test
    fun `both surfaces carry the same three header controls, and no words`() {
        val tvHeader = slice(hub, "private fun SportsHeader(", "private fun HeaderIconButton(")
        val phoneHeader = slice(
            mobile,
            "private fun SportsHeaderMobile(",
            "private fun HeaderIconButtonMobile("
        )
        listOf("TV" to tvHeader, "phone" to phoneHeader).forEach { (surface, header) ->
            assertFalse(
                "$surface must not print a REFRESH button label any more",
                header.contains("\"REFRESH\"")
            )
            assertFalse(
                "$surface must not print a LEAGUES button label any more",
                header.contains("\"LEAGUES\"")
            )
            val search = header.indexOf("contentDescription = \"Search\"")
            val refresh = header.indexOf("contentDescription = \"Refresh\"")
            val leagues = header.indexOf("contentDescription = \"Leagues\"")
            assertTrue(
                "$surface must name all three icon-only controls for TalkBack",
                search >= 0 && refresh >= 0 && leagues >= 0
            )
            assertTrue(
                "$surface must lay them out search, refresh, leagues - the order the D-pad crosses",
                search < refresh && refresh < leagues
            )
        }
    }

    @Test
    fun `the glyphs come from the icon dependency the app already ships`() {
        listOf(hub, mobile).forEach { source ->
            listOf(
                "androidx.compose.material.icons.filled.Search",
                "androidx.compose.material.icons.filled.Refresh",
                "androidx.compose.material.icons.automirrored.filled.List",
                "androidx.compose.material.icons.filled.SportsScore",
                "androidx.compose.material.icons.filled.Leaderboard"
            ).forEach { glyph ->
                assertTrue("$glyph must be the shipped icon import, not a new dependency", source.contains(glyph))
            }
        }
    }

    @Test
    fun `the TV search field appears only when the magnifier opens it`() {
        assertTrue(
            "the field's own visibility is screen state",
            hub.contains("var searchExpanded by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "and it is drawn from that state, below the league tabs where it always sat",
            hub.contains("if (searchExpanded) { SportsSearchField(")
        )
        assertTrue(
            "opening it claims focus, which is what makes OK raise the TV keyboard",
            hub.contains("searchFocusRequests++") &&
                hub.contains("searchFieldFocus.requestFocus()")
        )
        assertTrue(
            "a filtered hub shows why the cards changed",
            hub.contains("searchActive = searchQuery.isNotEmpty()") &&
                hub.contains("tint = if (searchActive) KBAccent else KBTextHi")
        )
    }

    @Test
    fun `the phone hides the field too, and opens the keyboard with it`() {
        assertTrue(
            "the phone's field is behind the magnifier as well",
            mobile.contains("var searchExpanded by remember { mutableStateOf(false) }") &&
                mobile.contains("if (searchExpanded) { KBTextField(")
        )
        assertTrue(
            "expanding it focuses the field and asks for the keyboard",
            mobile.contains("searchFieldFocus.requestFocus()") &&
                mobile.contains("keyboardController?.show()")
        )
        assertTrue(
            "the field's own clear action collapses it",
            mobile.contains("onClear = { searchQuery = \"\" searchExpanded = false }")
        )
        assertTrue(
            "Back clears the query first and collapses the empty field after",
            mobile.contains("BackHandler(enabled = searchExpanded) {") &&
                mobile.contains(
                    "if (searchQuery.isNotEmpty()) searchQuery = \"\" else searchExpanded = false"
                )
        )
    }

    @Test
    fun `collapsed search still filters nothing, because the rule is unchanged`() {
        listOf("TV" to hub, "phone" to mobile).forEach { (surface, source) ->
            assertTrue(
                "$surface still draws its results branch from the blank-aware rule",
                source.contains("if (SportsSearchRules.isActive(searchQuery)) {")
            )
        }
        assertFalse(
            "the TV field never filters behind the icon's back",
            hub.contains("isActive(searchExpanded)") || mobile.contains("isActive(searchExpanded)")
        )
    }

    @Test
    fun `the view switch is a matched pair of icon chips on both surfaces`() {
        listOf("TV" to hub, "phone" to mobile).forEach { (surface, source) ->
            assertTrue(
                "$surface draws a scoreboard glyph for GAMES",
                source.contains("Icons.Filled.SportsScore")
            )
            assertTrue(
                "$surface draws a table glyph for STANDINGS",
                source.contains("Icons.Filled.Leaderboard")
            )
            assertTrue(
                "$surface names both chips for TalkBack",
                source.contains("contentDescription = \"Games\"") &&
                    source.contains("contentDescription = \"Standings\"")
            )
            assertTrue(
                "$surface exposes the selected chip's state as well as its accent fill",
                source.contains("modifier = Modifier.semantics { selected = isSelected }")
            )
        }
        assertTrue(
            "the switch still renders only where the selected league publishes a table",
            hub.contains(
                "val standingsAvailable = !favoritesTab && selectedLeague?.hasStandings == true"
            )
        )
        assertTrue(
            "and both surfaces still switch views through the same setter",
            hub.contains("LeagueViewToggle(selected = leagueView, onSelect = viewModel::setView)") &&
                mobile.contains("onSelect = viewModel::setView")
        )
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    private fun flat(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
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
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt"
    }
}
