package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following teams, end to end through the wiring: the FAVORITES tab, the star on
 * a card, and the long-press dialog that sets it.
 *
 * The pure rules (the key a favourite is stored under, whether a game is one of
 * yours, the tab not being a league) are tested in `SportsFavoritesTest`; what
 * is left is the plumbing, and plumbing is exactly what a source contract can
 * see and a unit test cannot - there is no TV in CI and this screen wants a
 * ViewModel, a live tick and ESPN data.
 */
class SportsHubFavoritesContractTest {

    private val hub: String by lazy { source(HUB) }
    private val model: String by lazy { source(VIEW_MODEL) }

    /** The hub's source with runs of whitespace collapsed to one space. */
    private val hubFlat: String by lazy { hub.replace(Regex("\\s+"), " ") }

    /** The ViewModel's source, same treatment. */
    private val vmFlat: String by lazy { model.replace(Regex("\\s+"), " ") }

    @Test
    fun `the favourites tab leads the tab row and is selectable`() {
        assertTrue(
            "it is the first chip, ahead of the leagues",
            hubFlat.contains("val tabs = remember(leagues) { listOf(SportsLeagues.FAVORITES) + leagues }")
        )
        assertTrue(
            "the row draws the tabs, not the raw catalog",
            hubFlat.contains("LeagueTabRow( leagues = tabs,")
        )
        assertTrue(
            "and a selection that survived is checked against the tabs, so the favourites tab is never bounced back to a league",
            hubFlat.contains("LaunchedEffect(tabs) {") && hubFlat.contains("tabs.none { it.path == current }")
        )
    }

    @Test
    fun `the tab shows the viewer's games, and says so when there are none`() {
        assertTrue(
            "the section comes from the hub's own state",
            hubFlat.contains("val favoritesSection by viewModel.favoritesSection.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "and the tab resolves to it rather than to a fetched league",
            hubFlat.contains("val favoritesTab = selectedPath == SportsLeagues.FAVORITES.path")
        )
        assertTrue(
            "\"you have not picked anyone\" and \"nobody you follow is on\" are answered separately",
            hubFlat.contains("favoritesTab && favoriteKeys.isEmpty() -> KBStatusMessage(") &&
                hubFlat.contains("message = \"None of your teams are playing right now.\"")
        )
    }

    @Test
    fun `the favourites section is a filter over what the hub already fetched`() {
        assertTrue(
            "no fetch of its own: the games come from the sections",
            vmFlat.contains("sections.flatMap { it.games }.filter { it.involvesFavorite(keys) }")
        )
        assertTrue(
            "an empty follow list is an empty tab, not a filter that matches everything",
            vmFlat.contains("games = if (keys.isEmpty()) { emptyList() } else {")
        )
        assertTrue(
            "and it is derived state, so a toggle redraws it without another round trip",
            vmFlat.contains("combine(_sections, _favoriteTeamKeys) { sections, keys ->")
        )
    }

    @Test
    fun `a favourite survives the process`() {
        assertTrue(
            "the store is a preference, read back when the ViewModel is built",
            vmFlat.contains("MutableStateFlow( AppPreferences.getSportsFavoriteTeams(app) )")
        )
        assertTrue(
            "and a toggle writes the set the store handed back",
            vmFlat.contains("AppPreferences.setSportsTeamFavorite( getApplication(), team.favoriteKey, favorite )")
        )
    }

    @Test
    fun `the card marks a followed team and offers the dialog on a long press`() {
        assertTrue(
            "a followed side is starred on the line that names it",
            hubFlat.contains("if (favorite) \"★ \$it\" else it")
        )
        assertTrue(
            "the star is decided per side, from the follow set",
            hubFlat.contains("favorite = game.away.favoriteKey in favoriteKeys") &&
                hubFlat.contains("favorite = game.home.favoriteKey in favoriteKeys")
        )
        assertTrue(
            "a long press on a playable card opens the follow dialog",
            hubFlat.contains("onLongClick = onEditFavorites")
        )
        assertTrue(
            "which lists both sides of that game as toggles",
            hubFlat.contains("listOf(game.away, game.home).forEachIndexed")
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
    }
}
