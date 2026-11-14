package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.data.publiclists.PublicListRules
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anime tracker lists are Library lists (spec 2026-11-13, F1-F3).
 *
 * The field report: the anime trackers did nothing with the Library. The fix is
 * not a new surface - it is the lists the Library already knows how to draw,
 * with the anime services behind them:
 *
 *  - F1: each configured service contributes its own lists (AniList's status
 *    groups AND custom lists, Kitsu's and MAL's status buckets), TMDB-resolved,
 *    with every entry that has no TMDB match DROPPED rather than drawn blank,
 *    and a service with no username/connection contributing no row at all.
 *  - F2: public lists are browsed by USERNAME on the shared public-lists surface
 *    (there is no trending directory for anime), and a save is a COPY into the
 *    Library - a snapshot, named after its vendor.
 *  - F3: every anime list pins to Home through the ONE existing path
 *    (`libraryHomeRailPinFor` -> `LibraryHomeRails`), and the pinned rail reads
 *    its service live, with the pin kept alive by the same reconcile every other
 *    remote list has.
 *
 * Pure rules run for real; the wiring is read from the source, the way this
 * repo pins navigation and device-silent behaviour.
 */
class AnimeLibraryListsContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    // ── Pure rules ────────────────────────────────────────────────────────

    @Test
    fun `a custom list never collapses onto a status group`() {
        assertEquals(
            "a status group is addressed by its service status",
            // Lower-cased so AniList's CURRENT and MAL's watching share one
            // shape; the service's own word is still what the row is named from.
            "status:current",
            AnimeListRules.aniListRef(isCustomList = false, name = "Watching", status = "CURRENT")
        )
        assertEquals(
            "a custom list is addressed by its own name, even when it is called 'Completed'",
            "custom:completed",
            AnimeListRules.aniListRef(isCustomList = true, name = "Completed", status = "COMPLETED")
        )
        assertNotEquals(
            "the two must be different rows - one identity for both would merge two lists",
            AnimeListRules.aniListRef(true, "Completed", "COMPLETED"),
            AnimeListRules.statusRef("COMPLETED")
        )
        assertNull(
            "a custom list with no name has no identity to pin, so it is not invented",
            AnimeListRules.aniListRef(isCustomList = true, name = "  ", status = "COMPLETED")
        )
    }

    @Test
    fun `status groups are named the way the rail names them`() {
        assertEquals("Watching", AnimeListRules.aniListName("Watching", "CURRENT"))
        assertEquals(
            "a group the service left unnamed still gets its status word",
            "Planning",
            AnimeListRules.aniListName("", "PLANNING")
        )
        assertEquals(
            "a real custom name is kept as it is",
            "Comfort shows",
            AnimeListRules.aniListName("Comfort shows", null)
        )
        assertEquals("On hold", AnimeListRules.displayName("on_hold"))
        assertEquals("Planning", AnimeListRules.displayName("plan_to_watch"))
        assertEquals("Completed", AnimeListRules.displayName("COMPLETED"))
    }

    @Test
    fun `each service keeps its five buckets, in rail order`() {
        listOf(AnimeListRules.KITSU_STATUSES, AnimeListRules.MAL_STATUSES).forEach { statuses ->
            assertEquals("five buckets, as the services expose them", 5, statuses.size)
            assertEquals(
                "no bucket is listed twice - two rows would show the same entries: $statuses",
                statuses.size,
                statuses.distinct().size
            )
        }
        assertEquals("current", AnimeListRules.KITSU_STATUSES.first())
        assertEquals("watching", AnimeListRules.MAL_STATUSES.first())
        assertTrue(AnimeListRules.MAL_STATUSES.contains("plan_to_watch"))
        assertTrue(AnimeListRules.KITSU_STATUSES.contains("on_hold"))
    }

    @Test
    fun `a copied public list names its vendor and is recognised again`() {
        assertEquals(
            "the copy's name says which service it came from",
            "Watching · AniList",
            AnimeListRules.copyName("Watching", "AniList")
        )
        assertEquals(
            "trimmed and deterministic, so a second save is the same list",
            AnimeListRules.copyName(" Watching ", "AniList"),
            AnimeListRules.copyName("Watching", "AniList")
        )
        assertEquals(
            "a nameless list still gets a usable title",
            "Anime list · Kitsu",
            AnimeListRules.copyName("   ", "Kitsu")
        )
        assertEquals(
            "two vendors' lists of the same name cannot collide in the Library",
            false,
            AnimeListRules.copyName("Watching", "AniList") ==
                AnimeListRules.copyName("Watching", "Kitsu")
        )
    }

    @Test
    fun `the anime vendors browse by username, and only MAL needs a connection`() {
        listOf(
            PublicListVendor.ANILIST,
            PublicListVendor.KITSU,
            PublicListVendor.MAL
        ).forEach { vendor ->
            assertTrue("${vendor.label} has no directory: it is a username browse", vendor.byUsername)
            assertTrue(
                "${vendor.label}'s rule must say a copy is a snapshot, not a live feed",
                vendor.attributionRule.contains("snapshot")
            )
        }
        assertTrue("MAL's list routes are OAuth-only", PublicListVendor.MAL.needsConnection)
        assertFalse(
            "AniList reads a public profile with no account of ours",
            PublicListVendor.ANILIST.needsConnection
        )
        assertFalse(PublicListVendor.KITSU.needsConnection)
        assertFalse("Trakt keeps its directories", PublicListVendor.TRAKT.byUsername)
        assertFalse(PublicListVendor.MDBLIST.byUsername)
        assertTrue(
            "the MAL gate says connecting is what unlocks the box",
            PublicListRules.notConnectedMessage(PublicListVendor.MAL).contains("connect")
        )
        assertTrue(
            "a copy that landed says where it went",
            PublicListRules.copySavedText(PublicListVendor.ANILIST).contains("Library")
        )
    }

    // ── F1: the Library rail ──────────────────────────────────────────────

    @Test
    fun `the three anime sources are Library sources that hold their own lists`() {
        val models = normalized(read(MODELS))
        listOf(
            "ANILIST_LIST(\"AniList\")",
            "KITSU_LIST(\"Kitsu\")",
            "MAL_LIST(\"MyAnimeList\")"
        ).forEach { pin ->
            assertTrue("LibrarySource must carry $pin", models.contains(pin))
        }
        assertTrue(
            "one predicate names the family, so no screen has to list three values",
            models.contains("val isAnimeList: Boolean")
        )
        assertTrue(
            "the shelf id is derived from (source, ref) - not from the row's position",
            models.contains("fun animeListShelfId(source: LibrarySource, ref: String): Int")
        )
        val manage = models.substringAfter("val canManageList")
            .substringBefore("val deleteDestinationLabel")
        listOf("ANILIST_LIST", "KITSU_LIST", "MAL_LIST").forEach { source ->
            assertFalse(
                "an anime list belongs to its service: the rail must not offer rename/delete ($source)",
                manage.contains(source)
            )
        }
    }

    @Test
    fun `the rail carries the anime lists, and only from sources that answered`() {
        val vm = normalized(read(VIEW_MODEL))
        assertTrue("the refresh reads the anime lists live", vm.contains("AnimeLists.own(appContext)"))
        assertTrue(
            "the rows join the one lists rail every other list is drawn from",
            vm.contains("bingecatLists + animeLists + watchlistLists")
        )
        assertTrue(
            "an anime list's items are read through the same fetch path on selection",
            vm.contains("if (list.source.isAnimeList)")
        )
        assertTrue(
            "a local-list mutation must not drop the anime shelves",
            vm.substringAfter("private fun refreshListsOnly")
                .contains("animeLists")
        )
        assertTrue(
            "only a source that ANSWERED may prune a pin",
            vm.contains("animeRead?.answered?.forEach { source ->")
        )
        assertTrue(
            "an anime row offers no remove the service cannot honour",
            vm.contains("LibrarySource.ANILIST_LIST,") &&
                vm.contains("LibrarySource.MAL_LIST -> Unit")
        )
    }

    @Test
    fun `an entry with no TMDB match is dropped, never drawn as a blank card`() {
        val anime = normalized(read(ANIME_LISTS))
        assertTrue(
            "the bridge is one exact normalized title match",
            anime.contains("animeTmdbTitleMatch(entry.titles, result.name ?: result.title)")
        )
        assertTrue(
            "the resolved row keeps only a real TMDB id",
            anime.contains("if (match == null || match.tmdbId <= 0) return@mapNotNull null")
        )
        assertTrue(
            "a search that found nothing is remembered as an answer, not re-searched",
            anime.contains("TmdbMatch(0, \"\", null, null)")
        )
        assertTrue(
            "the public detail read maps only resolved rows",
            anime.contains("resolveItems(appContext, LibrarySource.ANILIST_LIST, group)")
        )
        assertFalse(
            "never a TMDB-less placeholder on the way in",
            anime.contains("tmdbId = null,\n                listName")
        )
    }

    // ── F2: public lists by username ──────────────────────────────────────

    @Test
    fun `the public surface gained the anime vendors without a second browser`() {
        val vendors = normalized(read(PUBLIC_LISTS))
        listOf(
            "ANILIST(\"anilist\", \"AniList\", byUsername = true)",
            "KITSU(\"kitsu\", \"Kitsu\", byUsername = true)",
            "MAL(\"mal\", \"MyAnimeList\", byUsername = true)"
        ).forEach { pin ->
            assertTrue("PublicListVendor must carry $pin", vendors.contains(pin))
        }
        assertTrue(
            "the username level is the anime root",
            normalized(read(PUBLIC_LISTS_VM)).contains("PublicListLevel.Username")
        )
        listOf(SETTINGS_TV, SETTINGS_MOBILE).forEach { path ->
            val settings = normalized(read(path))
            listOf("anilist", "kitsu", "mal").forEach { wire ->
                assertTrue(
                    "$path must open the shared surface for '$wire'",
                    settings.contains("onOpenPublicLists(\"$wire\")")
                )
            }
        }
        // A row the search index does not know is visible and unfindable on TV.
        val search = normalized(read(SETTINGS_SEARCH))
        listOf("anilist", "kitsu", "mal").forEach { wire ->
            assertTrue(
                "the settings index must find the '$wire' public-lists row",
                search.contains("\"integrations.$wire-public-lists\"")
            )
        }
    }

    @Test
    fun `a save is a copy into a local list, not a like`() {
        val anime = normalized(read(ANIME_LISTS))
        val save = anime.substringAfter("suspend fun copyToLibrary(")
            .substringBefore("// ── Reads")
        assertTrue("the copy is a local list", save.contains("LocalLibraryStore.createList("))
        assertTrue(
            "one bulk write, not one per title",
            save.contains("LocalLibraryStore.addItemsToLocalList(")
        )
        assertTrue(
            "its name names the vendor (see AnimeListRules.copyName)",
            save.contains("AnimeListRules.copyName(card.name, card.vendor.label)")
        )
        assertTrue(
            "a second save of the same list is recognised, not duplicated",
            save.contains("PublicListSaveOutcome.AlreadySaved")
        )
        assertFalse(
            "no anime service has a save-someone's-list write, so nothing may pretend to like one",
            save.contains("likePublicList")
        )
        assertTrue(
            "the ViewModel routes the anime save through the copy leg",
            normalized(read(PUBLIC_LISTS_VM))
                .contains("AnimeLists.copyToLibrary(context, card)")
        )
    }

    @Test
    fun `MAL is gated on the viewer's own connection before any username is read`() {
        val api = normalized(read(MAL_API))
        val userRead = api.substringAfter("suspend fun userAnimeList(")
            .substringBefore("private suspend fun readAnimeList(")
        assertTrue(
            "the other user's read is OAuth-only: no session, no request",
            userRead.indexOf("store.hasSession()") in 0 until userRead.indexOf("readAnimeList(")
        )
        assertTrue(
            "the username is encoded into the documented route",
            userRead.contains("/users/\$encoded/animelist")
        )
        val vm = normalized(read(PUBLIC_LISTS_VM))
        assertTrue(
            "the surface's gate is the MAL session, not a username box that cannot answer",
            vm.contains("PublicListVendor.MAL -> runCatching {") &&
                vm.contains("MalTokenStore(context)")
        )
        assertTrue(
            "and the read itself is grouped through the same gated leg",
            normalized(read(ANIME_LISTS)).contains(
                "PublicListVendor.MAL -> readMalGroups(context.applicationContext, username)"
            )
        )
    }

    @Test
    fun `the AniList read asks for the group name and custom flag`() {
        val client = normalized(read(ANILIST_CLIENT))
        assertTrue(
            "the collection carries custom lists, but only if their name/isCustomList is asked for",
            client.contains("{ lists { name isCustomList status entries")
        )
    }

    // ── F3: pin + live Home rail ──────────────────────────────────────────

    @Test
    fun `an anime list pins through the one pin path`() {
        listOf(LIBRARY_TV, LIBRARY_MOBILE).forEach { path ->
            val screen = normalized(read(path))
            assertEquals(
                "$path must build the pin from the row through the shared builder, once",
                1,
                screen.count("libraryHomeRailPinFor(list)")
            )
            assertTrue(
                "$path's menu must offer the toggle for any list row",
                screen.contains("LibraryHomeRails.setPinned(context, pin, true)")
            )
        }
    }

    @Test
    fun `a pinned anime rail reads its service live, and its pin is kept alive`() {
        val rails = normalized(read(RAIL_ITEMS))
        listOf("LibrarySource.ANILIST_LIST,", "LibrarySource.KITSU_LIST,", "LibrarySource.MAL_LIST ->")
            .forEach { pin ->
                assertTrue("the Home loader must read $pin", rails.contains(pin))
            }
        assertTrue(
            "the rail reads through the same per-list read the Library uses",
            rails.contains("AnimeLists.items(context, source, pin.id)")
        )
        val reconcile = normalized(read(RAIL_RECONCILE))
        listOf("ANILIST_LIST", "KITSU_LIST", "MAL_LIST").forEach { source ->
            assertTrue(
                "the launch reconcile must know $source's lists live at a service",
                reconcile.contains("LibrarySource.$source.name")
            )
        }
        assertTrue(
            "and it may only prune from an answer that really arrived",
            reconcile.contains("own?.answered?.forEach { source ->")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun normalized(text: String): String {
        val stripped = text.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker < 0) line else line.substring(0, marker)
        }
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
        const val PKG = "com/kennyb1201/kbstream"
        const val MODELS = "$PKG/data/library/LibraryModels.kt"
        const val VIEW_MODEL = "$PKG/ui/library/LibraryViewModel.kt"
        const val LIBRARY_TV = "$PKG/ui/library/LibraryScreen.kt"
        const val LIBRARY_MOBILE = "$PKG/ui/mobile/LibraryScreenMobile.kt"
        const val ANIME_LISTS = "$PKG/data/anime/AnimeLists.kt"
        const val ANILIST_CLIENT = "$PKG/data/anime/AniListClient.kt"
        const val MAL_API = "$PKG/data/mal/MalApiClient.kt"
        const val PUBLIC_LISTS = "$PKG/data/publiclists/PublicLists.kt"
        const val PUBLIC_LISTS_VM = "$PKG/ui/publiclists/PublicListsViewModel.kt"
        const val RAIL_ITEMS = "$PKG/data/library/LibraryHomeRails.kt"
        const val RAIL_RECONCILE = "$PKG/data/library/LibraryHomeRailReconcile.kt"
        const val SETTINGS_TV = "$PKG/ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE = "$PKG/ui/mobile/SettingsScreenMobile.kt"
        const val SETTINGS_SEARCH = "$PKG/ui/settings/SettingsSearch.kt"
    }
}
