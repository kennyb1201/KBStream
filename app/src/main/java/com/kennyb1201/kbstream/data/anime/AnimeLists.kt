package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.kitsu.KitsuClient
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.library.animeListShelfId
import com.kennyb1201.kbstream.data.mal.MalApiClient
import com.kennyb1201.kbstream.data.mal.MalLegs
import com.kennyb1201.kbstream.data.mal.MalListRead
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListSaveOutcome
import com.kennyb1201.kbstream.data.publiclists.PublicListTitle
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import com.kennyb1201.kbstream.data.publiclists.PublicListsAnswer
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The anime trackers' lists, as Library lists and as public lists (spec
 * 2026-11-13, F1/F2).
 *
 * One read per service, in one place, because two features need exactly the same
 * thing: the Library's rail draws "my" AniList/Kitsu/MAL lists, and the
 * public-lists surface draws somebody's by username. Both are the same
 * `MediaListCollection` / library read, grouped the same way, so the grouping
 * lives here instead of in a ViewModel.
 *
 * WHAT A LIST IS:
 *  - **AniList** answers `lists[]` - its six status groups AND the viewer's
 *    custom lists (`isCustomList`). The group's own `name` is the rail title.
 *  - **Kitsu** answers library entries with a status; the rail groups them into
 *    its five buckets.
 *  - **MAL** is read with `/users/@me/animelist` (or `/users/{name}/animelist`
 *    when connected) and grouped by `list_status.status`.
 *
 * THE TMDB BRIDGE: none of the three carries a TMDB id, so every entry is joined
 * to the catalogue by one exact normalized title match - the rule the anime legs
 * already use ([animeTmdbTitleMatch]). An entry with no match is DROPPED: a row
 * with no id could not be opened, badged or pinned, and a blank card is worse
 * than a shorter list. The match is cached by title for the process, because the
 * status lists of one profile overlap heavily.
 *
 * Live, not a snapshot: every read goes to the service. That is the standing
 * Library-list rule, and it is also what keeps a list renamed on AniList from
 * wearing its old name here.
 *
 * A service with no username (AniList, Kitsu) or no connection (MAL) contributes
 * nothing at all - no row, no placeholder. Only a read that ANSWERED is reported
 * as answered, because a failed or private read must never be read as "these
 * lists are gone" by the Home pin reconcile.
 */
internal object AnimeLists {

    private const val TAG = "ANIME_LISTS"

    /** How many TMDB joins run at once. Titles are cached, so this is a first-read cap. */
    private const val RESOLVE_CONCURRENCY = 4

    /**
     * The TMDB answer for one anime title.
     *
     * [tmdbId] 0 means "no exact match", remembered as an answer so a repeat
     * read does not spend the same search again.
     */
    private data class TmdbMatch(
        val tmdbId: Int,
        val name: String,
        val year: Int?,
        val posterPath: String?
    )

    private val tmdbMatches = ConcurrentHashMap<String, TmdbMatch>()

    /** One service list: its ref, its title, and the anime on it. */
    internal data class AnimeListGroup(
        val ref: String,
        val name: String,
        val entries: List<AnimeTrackerEntry>
    )

    /** What one service's list read produced. */
    internal sealed interface AnimeGroupsRead {
        data class Ok(val groups: List<AnimeListGroup>) : AnimeGroupsRead

        /** A username is set but nothing readable: no such user, private, unparseable. */
        data class Unreadable(val reason: String) : AnimeGroupsRead

        /** No username (AniList/Kitsu) or no connection (MAL): contributes nothing. */
        data object NotConfigured : AnimeGroupsRead

        data class Failed(val error: Throwable) : AnimeGroupsRead
    }

    /** One of this profile's own lists, as the Library rail draws it. */
    internal data class OwnList(
        val source: LibrarySource,
        val ref: String,
        val name: String,
        val itemCount: Int
    )

    /** This profile's own anime lists, plus which services actually answered. */
    internal data class OwnLists(
        val lists: List<OwnList>,
        val answered: Set<LibrarySource>
    )

    // ── Own lists (F1) ────────────────────────────────────────────────────

    /**
     * Every anime list this profile has, from every service it has set up.
     *
     * MAL needs a connection and the other two need a username, so an
     * unconfigured service contributes nothing at all. A failed or unreadable
     * read is left OUT of [OwnLists.answered]: it knows nothing about which
     * lists exist, and the Home pin prune may only act on a source that
     * answered.
     */
    suspend fun own(context: Context): OwnLists {
        val appContext = context.applicationContext
        val lists = ArrayList<OwnList>()
        val answered = LinkedHashSet<LibrarySource>()

        aniListUsername(appContext)?.let { username ->
            when (val read = readAniListGroups(username)) {
                is AnimeGroupsRead.Ok -> {
                    answered += LibrarySource.ANILIST_LIST
                    read.groups.forEach { group ->
                        lists += OwnList(
                            source = LibrarySource.ANILIST_LIST,
                            ref = group.ref,
                            name = group.name,
                            itemCount = group.entries.size
                        )
                    }
                }

                else -> logSkipped("anilist", read)
            }
        }

        kitsuUsername(appContext)?.let { username ->
            when (val read = readKitsuGroups(username)) {
                is AnimeGroupsRead.Ok -> {
                    answered += LibrarySource.KITSU_LIST
                    read.groups.forEach { group ->
                        lists += OwnList(
                            source = LibrarySource.KITSU_LIST,
                            ref = group.ref,
                            name = group.name,
                            itemCount = group.entries.size
                        )
                    }
                }

                else -> logSkipped("kitsu", read)
            }
        }

        when (val read = readMalGroups(appContext, username = null)) {
            is AnimeGroupsRead.Ok -> {
                answered += LibrarySource.MAL_LIST
                read.groups.forEach { group ->
                    lists += OwnList(
                        source = LibrarySource.MAL_LIST,
                        ref = group.ref,
                        name = group.name,
                        itemCount = group.entries.size
                    )
                }
            }

            AnimeGroupsRead.NotConfigured -> Unit
            else -> logSkipped("mal", read)
        }

        return OwnLists(lists = lists, answered = answered)
    }

    /**
     * The rows of one anime list, TMDB-resolved, in the service's order.
     *
     * A read that fails or does not know the ref answers empty; the Library
     * treats that exactly like any other list read that produced nothing.
     */
    suspend fun items(context: Context, source: LibrarySource, ref: String): List<LibraryItem> {
        val appContext = context.applicationContext
        val read = when (source) {
            LibrarySource.ANILIST_LIST ->
                readAniListGroups(aniListUsername(appContext).orEmpty())

            LibrarySource.KITSU_LIST ->
                readKitsuGroups(kitsuUsername(appContext).orEmpty())

            LibrarySource.MAL_LIST -> readMalGroups(appContext, username = null)

            else -> return emptyList()
        }
        val group = (read as? AnimeGroupsRead.Ok)?.groups?.firstOrNull { it.ref == ref }
            ?: return emptyList()
        return resolveItems(appContext, source, group)
    }

    // ── Public lists by username (F2) ─────────────────────────────────────

    /**
     * [username]'s public lists on [vendor].
     *
     * AniList and Kitsu read a public profile with no account of ours. MAL is
     * the exception its own API forces: its list routes are OAuth-only, so this
     * answers [AnimeGroupsRead.NotConfigured] until THIS profile connects - the
     * surface says so plainly instead of offering a username box that cannot
     * answer.
     */
    suspend fun groupsFor(
        context: Context,
        vendor: PublicListVendor,
        username: String
    ): AnimeGroupsRead = when (vendor) {
        PublicListVendor.ANILIST -> readAniListGroups(username)
        PublicListVendor.KITSU -> readKitsuGroups(username)
        PublicListVendor.MAL -> readMalGroups(context.applicationContext, username)
        else -> AnimeGroupsRead.Unreadable("${vendor.label} has no anime lists")
    }

    /** The names the browse surface's cards carry, from [groups]. */
    internal fun cardsFor(
        vendor: PublicListVendor,
        username: String,
        groups: List<AnimeListGroup>
    ): List<PublicListCard> = groups.map { group ->
        PublicListCard(
            vendor = vendor,
            ref = group.ref,
            name = group.name,
            owner = username.trim().takeIf { it.isNotEmpty() },
            description = null,
            itemCount = group.entries.size,
            likes = null
        )
    }

    /**
     * One public anime list's titles, in the app's id forms.
     *
     * The rows are the TMDB matches only: an anime with no TMDB id has nothing
     * for Detail to open, which is the same drop rule the Library's own anime
     * rows follow.
     */
    suspend fun titles(
        context: Context,
        card: PublicListCard
    ): PublicListsAnswer<List<PublicListTitle>> {
        val appContext = context.applicationContext
        val read = groupsFor(appContext, card.vendor, card.owner.orEmpty())
        val group = when (read) {
            is AnimeGroupsRead.Ok -> read.groups.firstOrNull { it.ref == card.ref }
                ?: return PublicListsAnswer.Failed("list-not-found")

            is AnimeGroupsRead.Unreadable -> return PublicListsAnswer.Failed("unreadable")
            AnimeGroupsRead.NotConfigured -> return PublicListsAnswer.NotConnected
            is AnimeGroupsRead.Failed -> return PublicListsAnswer.Failed("NETWORK")
        }

        val titles = resolveItems(appContext, LibrarySource.ANILIST_LIST, group)
            .map { item ->
                PublicListTitle(
                    tmdbId = item.tmdbId,
                    imdbId = item.imdbId,
                    isMovie = false,
                    title = item.title,
                    year = item.year
                )
            }
        return PublicListsAnswer.Loaded(titles)
    }

    /**
     * Copies one public anime list into this profile's Library (F2's save).
     *
     * A copy, not a like: none of the three services has a "save somebody's
     * list" write, so the honest leg is a LOCAL list holding the titles that
     * resolved today. It lands on the same rail as every other local list, is
     * labelled with its vendor, and is a SNAPSHOT by the standing Library rule -
     * later edits on the service do not change it. That is exactly what the
     * spec calls a copied list, and it is why the name names the vendor.
     */
    suspend fun copyToLibrary(
        context: Context,
        card: PublicListCard
    ): PublicListSaveOutcome {
        val appContext = context.applicationContext
        val read = groupsFor(appContext, card.vendor, card.owner.orEmpty())
        val group = when (read) {
            is AnimeGroupsRead.Ok -> read.groups.firstOrNull { it.ref == card.ref }
                ?: return PublicListSaveOutcome.Failed("list-not-found")

            is AnimeGroupsRead.Unreadable -> return PublicListSaveOutcome.Failed("unreadable")
            AnimeGroupsRead.NotConfigured -> return PublicListSaveOutcome.NotConnected
            is AnimeGroupsRead.Failed -> return PublicListSaveOutcome.Failed("NETWORK")
        }

        val items = resolveItems(appContext, LibrarySource.LOCAL_LIST, group)
        if (items.isEmpty()) {
            return PublicListSaveOutcome.Failed("no-tmdb-matches")
        }

        val name = AnimeListRules.copyName(card.name, card.vendor.label)
        if (LocalLibraryStore.userLists(appContext)
                .any { it.name.equals(name, ignoreCase = true) }
        ) {
            return PublicListSaveOutcome.AlreadySaved
        }

        val list = LocalLibraryStore.createList(appContext, name)
            ?: return PublicListSaveOutcome.Failed("copy-failed")
        LocalLibraryStore.addItemsToLocalList(appContext, list.id, items)
        return PublicListSaveOutcome.Saved
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    /**
     * AniList's groups for [username].
     *
     * `lists` carries the status groups and the custom lists; each entry is
     * parsed by the tracker leg's own parser, so the Library and the rails never
     * disagree about what an AniList entry is.
     */
    internal suspend fun readAniListGroups(username: String?): AnimeGroupsRead {
        val name = username?.trim().orEmpty()
        if (name.isEmpty()) return AnimeGroupsRead.NotConfigured

        val collection = runCatching { AniListClient.mediaListCollection(name) }
            .getOrElse { error -> return AnimeGroupsRead.Failed(error) }
            ?: return AnimeGroupsRead.Unreadable("AniList returned no list for '$name'")

        val lists = collection.optJSONArray("lists")
            ?: return AnimeGroupsRead.Unreadable("AniList returned no lists for '$name'")

        val groups = ArrayList<AnimeListGroup>(lists.length())
        for (index in 0 until lists.length()) {
            val list = lists.optJSONObject(index) ?: continue
            val status = list.optString("status")
            val isCustom = list.optBoolean("isCustomList", false)
            val ref = AnimeListRules.aniListRef(isCustom, list.optString("name"), status)
                ?: continue
            val entries = ArrayList<AnimeTrackerEntry>()
            val rows = list.optJSONArray("entries")
            if (rows != null) {
                for (rowIndex in 0 until rows.length()) {
                    AnimeTrackerLegs.parseAniListEntry(rows.optJSONObject(rowIndex), status)
                        ?.let { entries += it }
                }
            }
            groups += AnimeListGroup(
                ref = ref,
                name = AnimeListRules.aniListName(list.optString("name"), status),
                entries = entries
            )
        }

        return AnimeGroupsRead.Ok(groups)
    }

    /**
     * Kitsu's library for [username], grouped by the library entry's own status.
     *
     * The status is read from the raw row rather than from
     * [AnimeTrackerStatus]: Kitsu's five buckets collapse to three on the rail,
     * and a Library list has to keep them apart.
     */
    internal suspend fun readKitsuGroups(username: String?): AnimeGroupsRead {
        val name = username?.trim().orEmpty()
        if (name.isEmpty()) return AnimeGroupsRead.NotConfigured

        val userId = runCatching { KitsuClient.userIdByName(name) }
            .getOrElse { error -> return AnimeGroupsRead.Failed(error) }
            ?: return AnimeGroupsRead.Unreadable("Kitsu has no user named '$name'")

        val byStatus = LinkedHashMap<String, MutableList<AnimeTrackerEntry>>()

        for (page in 0 until KITSU_MAX_PAGES) {
            val body = runCatching {
                KitsuClient.libraryEntries(userId, offset = page * KitsuClient.PAGE_LIMIT)
            }.getOrElse { error -> return AnimeGroupsRead.Failed(error) }
                ?: return AnimeGroupsRead.Unreadable("Kitsu library read failed for '$name'")

            val included = AnimeTrackerLegs.includedAnimeById(body)
            val data = body.optJSONArray("data") ?: break

            for (index in 0 until data.length()) {
                val row = data.optJSONObject(index) ?: continue
                val status = row.optJSONObject("attributes")
                    ?.optString("status")
                    ?.trim()
                    ?.lowercase(java.util.Locale.ROOT)
                    .orEmpty()
                if (status.isEmpty()) continue
                AnimeTrackerLegs.parseKitsuEntry(row, included)?.let { entry ->
                    byStatus.getOrPut(status) { ArrayList() } += entry
                }
            }

            if (data.length() < KitsuClient.PAGE_LIMIT) break
        }

        val groups = ArrayList<AnimeListGroup>(byStatus.size)
        AnimeListRules.KITSU_STATUSES.forEach { status ->
            val entries = byStatus.remove(status) ?: return@forEach
            groups += AnimeListGroup(
                ref = AnimeListRules.statusRef(status),
                name = AnimeListRules.displayName(status),
                entries = entries
            )
        }
        // A status Kitsu adds later still draws, after the known five.
        byStatus.forEach { (status, entries) ->
            groups += AnimeListGroup(
                ref = AnimeListRules.statusRef(status),
                name = AnimeListRules.displayName(status),
                entries = entries
            )
        }

        return AnimeGroupsRead.Ok(groups)
    }

    /**
     * MAL's list, grouped by `list_status.status`.
     *
     * [username] null is this profile's own list (`@me`). A username needs this
     * profile's connection - MAL has no public read - and an unconnected profile
     * answers [AnimeGroupsRead.NotConfigured] rather than an empty list.
     */
    internal suspend fun readMalGroups(
        context: Context,
        username: String?
    ): AnimeGroupsRead {
        val name = username?.trim().orEmpty()
        val read = if (name.isEmpty()) {
            MalApiClient.myAnimeList(context)
        } else {
            MalApiClient.userAnimeList(context, name)
        }

        val entries = when (read) {
            is MalListRead.Ok -> read.entries
            MalListRead.NotConfigured, MalListRead.NotConnected -> return AnimeGroupsRead.NotConfigured
            MalListRead.NeedsReconnect -> return AnimeGroupsRead.Unreadable("MAL needs reconnecting")
            MalListRead.Failed -> return AnimeGroupsRead.Failed(
                IllegalStateException("MAL list read failed")
            )
        }

        val byStatus = LinkedHashMap<String, MutableList<AnimeTrackerEntry>>()
        entries.forEach { row ->
            val status = row.optJSONObject("list_status")
                ?.optString("status")
                ?.trim()
                ?.lowercase(java.util.Locale.ROOT)
                .orEmpty()
            if (status.isEmpty()) return@forEach
            MalLegs.parseEntry(row)?.let { entry ->
                byStatus.getOrPut(status) { ArrayList() } += entry
            }
        }

        val groups = ArrayList<AnimeListGroup>(byStatus.size)
        AnimeListRules.MAL_STATUSES.forEach { status ->
            val rows = byStatus.remove(status) ?: return@forEach
            groups += AnimeListGroup(
                ref = AnimeListRules.statusRef(status),
                name = AnimeListRules.displayName(status),
                entries = rows
            )
        }
        byStatus.forEach { (status, rows) ->
            groups += AnimeListGroup(
                ref = AnimeListRules.statusRef(status),
                name = AnimeListRules.displayName(status),
                entries = rows
            )
        }

        return AnimeGroupsRead.Ok(groups)
    }

    // ── TMDB join ─────────────────────────────────────────────────────────

    /**
     * [group]'s entries as Library rows, one TMDB match each.
     *
     * Rows are de-duped by TMDB id (an anime can sit on two custom lists, and a
     * list can hold the same show twice under different titles) and an entry
     * with no exact match is dropped - see this object's doc.
     */
    private suspend fun resolveItems(
        context: Context,
        source: LibrarySource,
        group: AnimeListGroup
    ): List<LibraryItem> {
        val tmdb = TmdbRepository.getInstance(context)
        val semaphore = Semaphore(RESOLVE_CONCURRENCY)
        val resolved = coroutineScope {
            group.entries.map { entry ->
                async {
                    semaphore.withPermit { resolveOne(tmdb, entry) }
                }
            }.awaitAll()
        }

        val seen = HashSet<Int>()
        return resolved.mapNotNull { match ->
            if (match == null || match.tmdbId <= 0) return@mapNotNull null
            if (!seen.add(match.tmdbId)) return@mapNotNull null
            LibraryItem(
                source = source,
                mediaType = "series",
                title = match.name,
                year = match.year,
                posterUrl = match.posterPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TmdbRepository.POSTER_BASE + it },
                tmdbId = match.tmdbId,
                listId = animeListShelfId(source, group.ref),
                listName = group.name,
                listRemoteId = group.ref
            )
        }
    }

    /** One entry's TMDB match, from the process-wide title cache. */
    private suspend fun resolveOne(
        tmdb: TmdbRepository,
        entry: AnimeTrackerEntry
    ): TmdbMatch? {
        for (title in entry.titles) {
            val key = normalizeAnimeTitle(title)
            if (key.isEmpty()) continue

            tmdbMatches[key]?.let { remembered ->
                if (remembered.tmdbId > 0) return remembered
                continue
            }

            val results = runCatching { tmdb.searchTvOrNull(title) }.getOrNull().orEmpty()
            val match = results.firstOrNull { result ->
                animeTmdbTitleMatch(entry.titles, result.name ?: result.title)
            }

            val answer = match?.let { toMatch(it) } ?: TmdbMatch(0, "", null, null)
            tmdbMatches[key] = answer
            if (answer.tmdbId > 0) return answer
        }
        return null
    }

    private fun toMatch(result: TmdbSearchTitleResult): TmdbMatch {
        val name = (result.name ?: result.title)?.takeIf { it.isNotBlank() }
        return TmdbMatch(
            tmdbId = result.id,
            name = name ?: "Untitled",
            year = (result.firstAirDate ?: result.releaseDate)
                ?.take(4)
                ?.toIntOrNull()
                ?.takeIf { it > 0 },
            posterPath = result.posterPath
        )
    }

    private fun logSkipped(source: String, read: AnimeGroupsRead) {
        when (read) {
            is AnimeGroupsRead.Failed ->
                Log.w(TAG, "$source anime lists failed: ${read.error.message}")

            is AnimeGroupsRead.Unreadable ->
                Log.w(TAG, "$source anime lists unreadable: ${read.reason}")

            else -> Unit
        }
    }

    /**
     * The AniList identity this profile reads: its saved username, else the
     * CONNECTED account's name.
     *
     * The spec allows both, and the second is the one that reads private lists:
     * a public-username read and an account read are the same endpoint, and a
     * profile that has connected AniList but never filled the row must still get
     * its lists rather than a Settings trip.
     */
    internal fun aniListUsername(context: Context): String? =
        AppPreferences.getAniListUsername(context)
            ?: runCatching { AniListAuth.viewerName(context) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    /**
     * The Kitsu identity this profile reads: the saved username, else the
     * connected account's name (the same two sources as AniList).
     */
    internal fun kitsuUsername(context: Context): String? =
        AppPreferences.getKitsuUsername(context)
            ?: runCatching {
                com.kennyb1201.kbstream.data.kitsu.KitsuTokenStore(context)
                    .read()
                    ?.viewerName
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Kitsu pages the list read walks (two pages = 40 entries, as the leg does). */
    private const val KITSU_MAX_PAGES = 2

    /** Test hook: forgets the remembered TMDB joins. */
    internal fun clearCacheForTest() {
        tmdbMatches.clear()
    }
}
