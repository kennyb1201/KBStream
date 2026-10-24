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
    fun `a hub that opens with teams followed lands on the favourites tab`() {
        // The initial default only. The whole branch sits inside the guard that
        // skips a selection which is still valid, which is what makes this the
        // FIRST tab and nothing else:
        //  - a manual switch has already set selectedPath, so a re-open keeps
        //    the league that was picked (the tab is not bounced to FAVOURITES);
        //  - `tabs` is the only key, so a star toggled in-session does not move
        //    the viewer off the tab they are reading;
        //  - and clearing the follow set restores the old first-league default,
        //    since the empty branch is untouched.
        assertTrue(
            "the follow set picks FAVOURITES, and an empty one still falls to the first league",
            hubFlat.contains(
                "val current = selectedPath " +
                    "if (current == null || tabs.none { it.path == current }) { " +
                    "if (favoriteKeys.isNotEmpty()) { " +
                    "viewModel.selectLeague(SportsLeagues.FAVORITES.path) } else { " +
                    "leagues.firstOrNull()?.let { viewModel.selectLeague(it.path) } } }"
            )
        )
        assertTrue(
            "only the tab row is the key, so neither an in-session star nor a manual switch re-runs it",
            hubFlat.contains("LaunchedEffect(tabs) {") &&
                !hubFlat.contains("LaunchedEffect(tabs, favoriteKeys)") &&
                !hubFlat.contains("LaunchedEffect(favoriteKeys")
        )
        assertTrue(
            "the follow set comes from the hub's state, read from the store when the ViewModel is " +
                "built, so the first composition already has it and the default cannot race a load",
            hubFlat.contains(
                "val favoriteKeys by viewModel.favoriteTeamKeys.collectAsStateWithLifecycle()"
            ) && vmFlat.contains("MutableStateFlow( AppPreferences.getSportsFavoriteTeams(app) )")
        )
        assertTrue(
            "and FAVOURITES really is in the row the selection is checked against",
            hubFlat.contains("val tabs = remember(leagues) { listOf(SportsLeagues.FAVORITES) + leagues }")
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
    fun `each league group leads with the viewer's own games`() {
        assertTrue(
            "all three of a league's groups are ordered by the pure rule",
            3 == Regex("SportsGameOrder\\.favoritesFirst\\(").findAll(hubFlat).count()
        )
        assertTrue(
            "and each group is ordered AFTER it is cut and date-sorted - ordering the SECTION first " +
                "was thrown away by the partition's own sort, which is why the star only ever led the " +
                "slate by luck",
            hubFlat.contains(
                "SportsGameOrder.upcoming(section.games.filter { it.state == GameState.UPCOMING })"
            ) &&
                hubFlat.contains(
                    "section.games.filter { it.state == GameState.FINAL }" +
                        ".sortedByDescending { it.dateMs }"
                )
        )
        assertTrue(
            "and each result is remembered on its own inputs, so a 30-second live pulse does not " +
                "re-filter and re-sort every league's slate",
            hubFlat.contains("val live = remember(section.games, favoriteKeys) {") &&
                hubFlat.contains("val upcoming = remember(section.games, favoriteKeys) {") &&
                hubFlat.contains("val final = remember(section.games, favoriteKeys) {")
        )
        assertTrue(
            "and the hub groups the loaded sections, in the feed's own order",
            hubFlat.contains("val sections by viewModel.sections.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "no ordering is left in the ViewModel to be silently discarded by that partition",
            !vmFlat.contains("displaySections") &&
                !vmFlat.contains("SportsGameOrder.favoritesFirst")
        )
    }

    @Test
    fun `no favourites is the slate it always was, and a tournament is untouched`() {
        assertTrue(
            "the ordering is driven by the live follow set, so an empty one falls to the rule's own " +
                "identity case - the feed's list back, instance for instance (see SportsGameOrderTest)",
            hubFlat.contains("favoriteKeys = favoriteKeys,")
        )
        assertTrue(
            "a tournament section carries no games, so the ordering is applied to games only",
            hubFlat.contains("if (section.tournaments.isNotEmpty()) {")
        )
        assertTrue(
            "the FAVORITES tab still reads its own section, which this ordering never touches",
            hubFlat.contains("val favoritesSection by viewModel.favoritesSection.collectAsStateWithLifecycle()")
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
