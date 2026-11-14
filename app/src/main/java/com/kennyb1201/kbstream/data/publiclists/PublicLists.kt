package com.kennyb1201.kbstream.data.publiclists

/**
 * The community list a browse surface is reading.
 *
 * Two vendors, one surface: Trakt's and MDBList's public lists answer the same
 * questions (a directory of lists, a search over list names, a list's titles, a
 * save) with different wire shapes, so the shape differences stop in the data
 * layer and everything above it - the ViewModel, both screens, the Settings
 * entry - is written once per [PublicListVendor], not once per service.
 *
 * Simkl is deliberately absent: its API serves watchlist statuses only, so
 * there is no public-list directory to browse. A vendor added here without a
 * data leg would be a screen with nothing behind it.
 *
 * The three ANIME vendors (spec 2026-11-13, F2) join the same surface because
 * they answer the same questions - whose list is it, what is on it, save it to
 * Library - even though their browse shape differs: there is no trending-lists
 * directory for anime, so those vendors are browsed BY USERNAME ([byUsername]).
 * One surface, not a second browser: the tabs become a username box and the
 * cards are the same cards.
 */
enum class PublicListVendor(
    val wire: String,
    val label: String,
    /**
     * True when this vendor is browsed by a username instead of a directory.
     *
     * AniList and Kitsu read a public profile with no account of ours; MAL's
     * list routes are OAuth-only, so it needs THIS profile's connection before
     * any username can be read ([needsConnection]).
     */
    val byUsername: Boolean = false
) {

    TRAKT("trakt", "Trakt"),

    MDBLIST("mdblist", "MDBList"),

    ANILIST("anilist", "AniList", byUsername = true),

    KITSU("kitsu", "Kitsu", byUsername = true),

    MAL("mal", "MyAnimeList", byUsername = true);

    /** The surface's own heading, e.g. "TRAKT PUBLIC LISTS". */
    val browseTitle: String get() = "$label PUBLIC LISTS"

    /**
     * True when browsing needs this profile's own account, not just a name.
     *
     * Only MAL: it has no public list read at all, so the surface asks for the
     * connection instead of showing a box that would fail.
     */
    val needsConnection: Boolean get() = this == MAL

    /** The line that keeps a community list from reading as this app's pick. */
    val attributionRule: String
        get() = if (byUsername) {
            "These are $label profiles' public anime lists, read by username. " +
                "Copying one puts its titles in your Library on this device - a " +
                "snapshot, not a live feed - and KBStream does not pick these titles."
        } else {
            "These are $label community lists, with the owner named on every " +
                "card. Saving one puts it in your Library beside your own lists - " +
                "KBStream does not pick these titles."
        }

    companion object {
        fun fromWire(raw: String?): PublicListVendor? =
            values().firstOrNull { it.wire == raw }
    }
}

/**
 * One browsable directory.
 *
 * The ORDER of the values is the order the tabs are drawn in (spec F2): Trakt's
 * two, then MDBList's three. Each vendor only ever shows its own - see
 * [forVendor].
 */
enum class PublicListDirectory(
    val wire: String,
    val label: String,
    val vendor: PublicListVendor
) {
    TRAKT_POPULAR("traktPopular", "Popular", PublicListVendor.TRAKT),
    TRAKT_TRENDING("traktTrending", "Trending", PublicListVendor.TRAKT),
    MDBLIST_TOP("mdblistTop", "Top", PublicListVendor.MDBLIST),
    MDBLIST_CURATED("mdblistCurated", "Curated", PublicListVendor.MDBLIST),
    MDBLIST_OFFICIAL("mdblistOfficial", "Official", PublicListVendor.MDBLIST);

    companion object {
        fun forVendor(vendor: PublicListVendor): List<PublicListDirectory> =
            values().filter { it.vendor == vendor }
    }
}

/**
 * One public list, as a card and as the detail screen draw it.
 *
 * [ref] is the identity every later call is addressed by: the list's NUMERIC id,
 * for both vendors (Trakt's items and like endpoints are `/lists/{id}` and its
 * docs say "You must use an integer id"; a slug there 404s). It is the card's key
 * as well, so a save's phase is tracked against the same identity the list is
 * opened by.
 *
 * [officialSlug] is MDBList's official-list route (`/lists/official/{slug}/
 * items`); an official list has a numeric id like any other and can be liked
 * through it, but its ITEMS are only served under the slug route.
 */
data class PublicListCard(
    val vendor: PublicListVendor,
    val ref: String,
    val name: String,
    val owner: String?,
    val description: String?,
    val itemCount: Int,
    val likes: Int?,
    val official: Boolean = false,
    val officialSlug: String? = null,
    /**
     * Whether this account has already saved (liked) the list, when the read
     * can say. MDBList sends the flag on every list object; Trakt does not, so a
     * Trakt card leaves this null and the save leg keeps its own liked set.
     */
    val liked: Boolean? = null
) {
    /** Stable identity across a re-read: vendor + the list's own id. */
    val key: String get() = "${vendor.wire}:$ref"
}

/**
 * One title on a public list, in the app's own id forms.
 *
 * Deliberately not the vendors' row types: Trakt hands back one media type per
 * read and MDBList hands back a combined array, and both end up here so the
 * detail screen and the ceiling filter are written once.
 */
data class PublicListTitle(
    val tmdbId: Int?,
    val imdbId: String?,
    val isMovie: Boolean,
    val title: String?,
    val year: Int?
) {
    /**
     * The id a Detail screen takes: IMDb first, then the typed TMDB form - the
     * same order every other row in the app resolves a title by.
     */
    val detailId: String?
        get() = imdbId?.takeIf { it.isNotBlank() } ?: tmdbId?.let { "tmdb:$it" }

    /** The key the rating ceiling is asked about. */
    val ceilingKey: Pair<Int, String>
        get() = (tmdbId ?: -1) to if (isMovie) "movie" else "series"
}

/**
 * One read's answer, in the tree's usual three-way shape.
 *
 * `Failed` is not "empty": an empty directory is a real answer and draws the
 * empty state, while a failure keeps whatever the screen already had and says
 * why (spec F2 - "never a blank screen"). [Blocked] is separate for MDBList's
 * daily allowance, which is not a transient failure and must not be retried.
 */
sealed interface PublicListsAnswer<out T> {

    data class Loaded<T>(val value: T) : PublicListsAnswer<T>

    data class Failed(val reason: String) : PublicListsAnswer<Nothing>

    data object NotConnected : PublicListsAnswer<Nothing>

    data class Blocked(val reason: String) : PublicListsAnswer<Nothing>
}

/**
 * A save's answer.
 *
 * [AlreadySaved] is a success, not an error: a second save of a liked list is a
 * no-op the screen reports as "Already in your lists" (spec F4).
 */
sealed interface PublicListSaveOutcome {

    data object Saved : PublicListSaveOutcome

    data object AlreadySaved : PublicListSaveOutcome

    data object NotConnected : PublicListSaveOutcome

    /**
     * The account is connected but the service refused the save for a reason
     * the viewer can act on - MDBList's liked-list cap (`8` on Free) is the one
     * that exists today. Carries the sentence to show.
     */
    data class Refused(val message: String) : PublicListSaveOutcome

    data class Failed(val reason: String) : PublicListSaveOutcome
}

/**
 * The browse surface's rules, as plain functions.
 *
 * Kept out of the ViewModel and out of both data legs for one reason: they are
 * the parts a test can pin without a network, a token or Compose - which is what
 * this file's test does.
 */
object PublicListRules {

    /** Below this a search is a fetch per keystroke, and MDBList charges for each. */
    const val MIN_SEARCH_CHARS = 2

    /** A username read costs one profile read; a blank one is not a query. */
    fun usernameEnabled(query: String): Boolean = query.trim().isNotEmpty()

    /** The prompt the username box carries on an anime vendor. */
    fun usernamePlaceholder(vendor: PublicListVendor): String =
        "${vendor.label} username"

    /** What a username read that found nothing says. */
    fun emptyUsernameText(vendor: PublicListVendor, username: String): String =
        "$username has no public lists $vendor can show right now."

    /**
     * What a username read that answered with NOTHING READABLE says.
     *
     * A private profile and a misspelled name are the same silence on the wire,
     * so the sentence names both possibilities rather than presenting either as
     * an empty list (the anime legs' own rule).
     */
    fun unreadableUsernameText(vendor: PublicListVendor, username: String): String =
        "$username could not be read on ${vendor.label}: either no such profile, or " +
            "one that keeps its list private."

    /** The snapshot note a copied anime list carries (spec F2's labelling). */
    fun copySnapshotNote(vendor: PublicListVendor): String =
        "A copied list is a snapshot: later edits on $vendor do not change it here."

    /** What a copy that landed says. */
    fun copySavedText(vendor: PublicListVendor): String = "Copied to your Library ($vendor)"

    /** The prompt a vendor's connection gate carries, in one place. */
    val notConnectedTitleSuffix: String get() = " is not connected"

    fun notConnectedMessage(vendor: PublicListVendor): String = if (vendor.needsConnection) {
        "${vendor.label} has no public list reads: connect your own ${vendor.label} " +
            "account in Settings first, then any username can be read."
    } else {
        "Connect ${vendor.label} in Settings first: public lists are read with " +
            "your own account."
    }

    /** One Trakt read's page size (Trakt's own maximum). */
    const val PAGE_SIZE = 100

    /** The most directory pages one read will walk. */
    const val MAX_PAGES = 3

    /** Spec F3: a blank (or one-character) query is a disabled button. */
    fun searchEnabled(query: String): Boolean =
        query.trim().length >= MIN_SEARCH_CHARS

    /**
     * The attribution line, mandatory on every card and detail screen.
     *
     * An official list is the provider's own, and says so; everything else is a
     * community list and names its owner. Neither ever reads as KBStream's pick.
     */
    fun attribution(card: PublicListCard): String {
        if (card.official) return "${card.vendor.label} official list"
        val owner = card.owner?.trim()?.takeIf { it.isNotBlank() }
        return if (owner == null) {
            "${card.vendor.label} community list"
        } else {
            "${card.vendor.label} community list · by $owner"
        }
    }

    /** "24 titles · 118 likes", with the half the service did not send left out. */
    fun countsLabel(itemCount: Int, likes: Int?): String = buildString {
        if (itemCount > 0) append(if (itemCount == 1) "1 title" else "$itemCount titles")
        if (likes != null && likes > 0) {
            if (isNotEmpty()) append(" · ")
            append(if (likes == 1) "1 like" else "$likes likes")
        }
        if (isEmpty()) append("No titles yet")
    }

    /** The sentence a spent MDBList allowance is shown as (spec F2, F4). */
    fun allowanceText(vendor: PublicListVendor): String =
        "${vendor.label}'s daily request allowance is used up. The list you already " +
            "had stays on screen; refill at the next UTC midnight."

    /** MDBList's liked-list cap, which is a tier limit rather than a failure. */
    fun likedLimitText(vendor: PublicListVendor): String =
        "${vendor.label} would not take another liked list: your account is at its " +
            "liked-list limit. Unlike a list first, then save this one."

    /** What a save that landed says. */
    fun savedText(vendor: PublicListVendor): String = "Saved to your ${vendor.label} lists"

    /** A second save of a list already saved. */
    const val ALREADY_SAVED = "Already in your lists"

    /**
     * A failure's sentence.
     *
     * The data legs hand back short codes (`401`, `403`, `429`, `NETWORK`), so
     * the wording lives in one place instead of three.
     */
    fun failureText(vendor: PublicListVendor, reason: String): String = when (reason) {
        "401" -> "Reconnect ${vendor.label} to continue: this session was not accepted."
        "403" -> "${vendor.label} refused this request for this account."
        "429" -> "${vendor.label} is rate limiting KBStream. Try again in a minute."
        "NETWORK" -> "Couldn't reach ${vendor.label}. Check the connection, then try again."
        "" -> "Couldn't read ${vendor.label} lists."
        // The anime legs' own answers (spec 2026-11-13, F2). A private profile is
        // not an empty list, and a list whose titles none of which match TMDB has
        // nothing this app could open - both say so plainly.
        "list-not-found" -> "That list is no longer on ${vendor.label}."
        "unreadable" -> "${vendor.label} could not read that profile's list: either no " +
            "such profile, or one that keeps its list private."
        "no-tmdb-matches" -> "None of that list's titles matched KBStream's catalogue, " +
            "so there is nothing to copy."
        "copy-failed" -> "Couldn't create the list in your Library."
        "private-profile" -> "That profile is private: ${vendor.label} will not read it."
        else -> if (reason.startsWith("Unreadable") || reason.contains("private")) {
            reason
        } else {
            "Couldn't read ${vendor.label} lists ($reason)."
        }
    }

    /** The detail screen's note about who decides what a title shows under. */
    const val CEILING_NOTE =
        "This profile's rating ceiling applies to these titles; the list's own " +
            "provider does not age-screen its public lists."

    /** A directory that answered with nothing. */
    fun emptyDirectoryText(vendor: PublicListVendor): String =
        "${vendor.label} returned no lists here right now."

    /** A search that matched nothing (spec F3: the empty state, not an error). */
    const val NO_SEARCH_RESULTS = "No public lists match that search."
}
