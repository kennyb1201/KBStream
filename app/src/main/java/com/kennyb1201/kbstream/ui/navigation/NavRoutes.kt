package com.kennyb1201.kbstream.ui.navigation

import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.decodeScreen
import com.kennyb1201.kbstream.encodeScreen
import com.kennyb1201.kbstream.stableBackDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject

/**
 * The Navigation Compose route table: one route type per [Screen] destination.
 *
 * This is phase F0 of the strangler migration, and it is deliberately a table
 * and nothing else - no destination renders through it yet. What it buys before
 * anything is wired:
 *
 *  - [NavRoutes.of] is an exhaustive `when` over the sealed [Screen] hierarchy,
 *    so adding a screen without giving it a route is a COMPILE error, not a
 *    screen that silently cannot be reached once the seam is in;
 *  - the route names are fixed here (`@SerialName`), so they are not the
 *    generated class names and stay stable across renames and packages;
 *  - every field that is a primitive is a route argument, and the two things
 *    that cannot be an argument are named as such: the return destination is
 *    the EXISTING JSON codec (see [returnRoute]), and a heavy payload is one
 *    `payloadJson` string (unused until its destination's phase).
 *
 * `Screen.Player` is not a destination and therefore has no route: it launches
 * a player Activity and is held as an overlay by `MainActivity`. [NavRoutes.of]
 * answers null for it, which is the seam's signal to keep it out of Nav.
 *
 * `returnRoute` is a `String?` rather than a nested route type on purpose.
 * Today's navigation is not a back stack - it is one current value plus an
 * explicit `returnTo` - and `returnTo` is DATA. Encoding it with
 * [encodeScreen] reuses the codec that already survives process death, keeps
 * `MAX_RETURN_DEPTH`, and means `stableBackDestination` can strip a one-shot
 * autoplay from the decoded destination on Back exactly as it does now. A
 * second serialization of the same graph is the thing this file exists to
 * avoid.
 */

// ── Top-level destinations ────────────────────────────────────────────────

@Serializable
@SerialName("home")
internal data object HomeRoute

@Serializable
@SerialName("search")
internal data object SearchRoute

@Serializable
@SerialName("guide")
internal data object GuideRoute

@Serializable
@SerialName("library")
internal data object LibraryRoute

@Serializable
@SerialName("settings")
internal data object SettingsRoute

/** Phone-only remote control; reached from Settings and nowhere else. */
@Serializable
@SerialName("remote")
internal data object RemoteRoute

/** Viewing stats; Back is hardwired to Settings. */
@Serializable
@SerialName("stats")
internal data object StatsRoute

/**
 * The sports hub. Its Back landing is Guide and is NOT data: the hub is
 * reached from the guide's header and the playlist it resolves against is
 * configured there, so no `returnRoute` is carried (see the back table).
 *
 * [detailGameId] / [detailTournamentId] name the detail sheet the hub reopens
 * on entry, set only by the player launched from that sheet, and neither
 * survives process death - a restore opens the plain hub.
 */
@Serializable
@SerialName("sports")
internal data class SportsRoute(
    val detailGameId: String? = null,
    val detailTournamentId: String? = null
)

// ── Leaf pages that carry their own return ────────────────────────────────

@Serializable
@SerialName("addons")
internal data class AddonsRoute(val returnRoute: String? = null)

@Serializable
@SerialName("catalog-builder")
internal data class CatalogBuilderRoute(val returnRoute: String? = null)

@Serializable
@SerialName("simkl")
internal data class SimklRoute(val returnRoute: String? = null)

@Serializable
@SerialName("trakt")
internal data class TraktRoute(val returnRoute: String? = null)

@Serializable
@SerialName("bingecat")
internal data class BingeCatRoute(val returnRoute: String? = null)

/** [vendor] names which public-list provider the page is showing. */
@Serializable
@SerialName("public-lists")
internal data class PublicListsRoute(
    val vendor: String = "trakt",
    val returnRoute: String? = null
)

/**
 * The year in review. `year == null` means "this year" and must stay null:
 * the screen resolves it at render, so a route left behind in December does
 * not pin December's year in June.
 */
@Serializable
@SerialName("year-review")
internal data class YearReviewRoute(
    val year: Int? = null,
    val returnRoute: String? = null
)

// ── Browse drill-downs ────────────────────────────────────────────────────

/**
 * A title's page.
 *
 * The two one-shot fields on [Screen.Detail] - `autoPlay` and `pendingTarget` -
 * are deliberately NOT route arguments, and their absence here is the whole
 * point of this note:
 *
 *  - a type-safe argument is PERSISTED in the entry's saved state, so a
 *    process-death restore would hand the page a play instruction the viewer
 *    never gave. That is the incident `NavStateSerialization` records against
 *    `pendingTarget` (a recreated app silently auto-playing the episode, which
 *    reads as the binge restarting);
 *  - it is also IMMUTABLE once the entry exists, so "clear it after the first
 *    frame" cannot be done by editing the argument - it means replacing the
 *    entry, which re-enters the destination and replays its transition and
 *    focus request, the exact flash F4's acceptance forbids.
 *
 * They ride in [NavSeam]'s transient slot instead: set by the navigation that
 * means "play on arrival", taken once by this page, and gone with the process.
 */
@Serializable
@SerialName("detail")
internal data class DetailRoute(
    val type: String,
    val id: String,
    val itemPoster: String? = null,
    val itemBackdrop: String? = null,
    val itemClearLogo: String? = null,
    val itemOverview: String? = null,
    val returnRoute: String? = null
)

@Serializable
@SerialName("actor")
internal data class ActorRoute(
    val personId: Int,
    val returnRoute: String? = null
)

@Serializable
@SerialName("studio")
internal data class StudioRoute(
    val id: Int,
    val name: String,
    val isNetwork: Boolean,
    val providerId: Int? = null,
    val networkOrCompanyId: Int? = null,
    val networkIsCompany: Boolean = false,
    val originalsCompanyId: Int? = null,
    val returnRoute: String? = null
)

@Serializable
@SerialName("decade")
internal data class DecadeRoute(
    val decadeStart: Int,
    val name: String,
    val returnRoute: String? = null
)

@Serializable
@SerialName("tag")
internal data class TagRoute(
    val id: Int,
    val name: String,
    val isKeyword: Boolean,
    val mediaType: String,
    val returnRoute: String? = null
)

@Serializable
@SerialName("collection")
internal data class CollectionRoute(
    val id: Int,
    val name: String,
    val returnRoute: String? = null
)

/**
 * A whole add-on catalog as a poster grid. (addonName, catalogId, type) is the
 * rail's real identity - the name pair alone is not unique, which is why the
 * grid re-finds its rail by all three.
 */
@Serializable
@SerialName("catalog-grid")
internal data class CatalogGridRoute(
    val title: String,
    val addonName: String,
    val catalogId: String? = null,
    val type: String? = null,
    val returnRoute: String? = null
)

@Serializable
@SerialName("kb-folder")
internal data class KBFolderRoute(
    val folderId: String,
    val returnRoute: String? = null
)

// ── Profiles ──────────────────────────────────────────────────────────────

/**
 * `returnRoute == null` is the cold-launch entry picker: Back exits the app
 * and must never navigate into Home. A non-null return is the in-app picker
 * opened from Settings or the top bar.
 */
@Serializable
@SerialName("profile-picker")
internal data class ProfilePickerRoute(val returnRoute: String? = null)

@Serializable
@SerialName("profile-edit")
internal data class ProfileEditRoute(
    val editingProfileId: String? = null,
    val returnRoute: String? = null
)

// ── Streams (payload-heavy, no top bar) ───────────────────────────────────

/**
 * The source picker. Its target, its cast list and its several strings are too
 * much for route arguments, so the whole transient bundle rides in
 * [payloadJson] (the existing codec) and only the return destination is a typed
 * argument. Filled at this destination's phase (F4).
 */
@Serializable
@SerialName("streams")
internal data class StreamsRoute(
    val parentId: String,
    val parentType: String,
    val returnRoute: String? = null,
    val payloadJson: String? = null
)

// ── The translation ───────────────────────────────────────────────────────

internal object NavRoutes {

    /** The route name each destination is registered under in the graph. */
    const val HOME = "home"
    const val SEARCH = "search"
    const val GUIDE = "guide"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val REMOTE = "remote"
    const val STATS = "stats"
    const val SPORTS = "sports"
    const val ADDONS = "addons"
    const val CATALOG_BUILDER = "catalog-builder"
    const val SIMKL = "simkl"
    const val TRAKT = "trakt"
    const val BINGECAT = "bingecat"
    const val PUBLIC_LISTS = "public-lists"
    const val YEAR_REVIEW = "year-review"
    const val DETAIL = "detail"
    const val ACTOR = "actor"
    const val STUDIO = "studio"
    const val DECADE = "decade"
    const val TAG = "tag"
    const val COLLECTION = "collection"
    const val CATALOG_GRID = "catalog-grid"
    const val KB_FOLDER = "kb-folder"
    const val PROFILE_PICKER = "profile-picker"
    const val PROFILE_EDIT = "profile-edit"
    const val STREAMS = "streams"

    /**
     * The route for one [Screen] value, or null when it is not a destination.
     *
     * Exhaustive by construction: a new [Screen] variant does not compile until
     * it is answered here, which is what keeps the seam honest as screens are
     * migrated one at a time.
     */
    fun of(screen: Screen): Any? = when (screen) {
        is Screen.Home -> HomeRoute
        is Screen.Search -> SearchRoute
        is Screen.Guide -> GuideRoute
        is Screen.Library -> LibraryRoute
        is Screen.Settings -> SettingsRoute
        is Screen.Remote -> RemoteRoute
        is Screen.Stats -> StatsRoute
        is Screen.Sports -> SportsRoute(
            detailGameId = screen.detailGameId,
            detailTournamentId = screen.detailTournamentId
        )
        is Screen.Addons -> AddonsRoute(returnRoute = returnOf(screen.returnTo))
        is Screen.CatalogBuilder ->
            CatalogBuilderRoute(returnRoute = returnOf(screen.returnTo))
        is Screen.Simkl -> SimklRoute(returnRoute = returnOf(screen.returnTo))
        is Screen.Trakt -> TraktRoute(returnRoute = returnOf(screen.returnTo))
        is Screen.BingeCat -> BingeCatRoute(returnRoute = returnOf(screen.returnTo))
        is Screen.PublicLists -> PublicListsRoute(
            vendor = screen.vendor,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.YearReview -> YearReviewRoute(
            year = screen.year,
            returnRoute = returnOf(screen.returnTo)
        )
        // What survives is what the route carries. The play-on-arrival
        // instruction is transient by definition, so `navigateTo` hands it to
        // the seam's one-shot slot while this line hands over the page itself.
        is Screen.Detail -> DetailRoute(
            type = screen.type,
            id = screen.id,
            itemPoster = screen.itemPoster,
            itemBackdrop = screen.itemBackdrop,
            itemClearLogo = screen.itemClearLogo,
            itemOverview = screen.itemOverview,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.Actor -> ActorRoute(
            personId = screen.personId,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.Studio -> StudioRoute(
            id = screen.id,
            name = screen.name,
            isNetwork = screen.isNetwork,
            providerId = screen.providerId,
            networkOrCompanyId = screen.networkOrCompanyId,
            networkIsCompany = screen.networkIsCompany,
            originalsCompanyId = screen.originalsCompanyId,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.Decade -> DecadeRoute(
            decadeStart = screen.decadeStart,
            name = screen.name,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.Tag -> TagRoute(
            id = screen.id,
            name = screen.name,
            isKeyword = screen.isKeyword,
            mediaType = screen.mediaType,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.Collection -> CollectionRoute(
            id = screen.id,
            name = screen.name,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.CatalogGrid -> CatalogGridRoute(
            title = screen.title,
            addonName = screen.addonName,
            catalogId = screen.catalogId,
            type = screen.type,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.KBFolder -> KBFolderRoute(
            folderId = screen.folderId,
            returnRoute = returnOf(screen.returnTo)
        )
        is Screen.ProfilePicker ->
            ProfilePickerRoute(returnRoute = screen.returnTo?.let { returnOf(it) })
        is Screen.ProfileEdit -> ProfileEditRoute(
            editingProfileId = screen.editingProfileId,
            returnRoute = screen.returnTo?.let { returnOf(it) }
        )
        is Screen.Streams -> StreamsRoute(
            parentId = screen.parentId,
            parentType = screen.parentType,
            returnRoute = returnOf(screen.returnTo),
            // The picker's whole transient bundle - the target, the parent id,
            // the strings and the cast - and the RESTORE codec already carries
            // all of it for this screen, so a restored Streams is restored with
            // its target exactly as it is today.
            payloadJson = encodeScreen(screen).toString()
        )

        // Not a destination: it launches a player Activity and lives as an
        // overlay above the renderer. Nothing to route, nothing to restore.
        is Screen.Player -> null
    }

    /**
     * A return destination as a route argument: the one codec that already
     * survives process death, so the return chain keeps its depth cap and Back
     * can decode it and run `stableBackDestination` over it.
     */
    private fun returnOf(screen: Screen): String = encodeScreen(screen).toString()

    /**
     * The landing a route's [returnRoute] argument names, for a destination's
     * Back: decoded with that same codec (an unreadable or older argument lands
     * on Home, never a crash) and run through [stableBackDestination], so the
     * autoplay strip and the depth cap hold on a migrated destination exactly as
     * they do on the arm it replaced.
     */
    fun landingOf(returnRoute: String?): Screen =
        stableBackDestination(
            if (returnRoute == null) {
                Screen.Home
            } else {
                runCatching { decodeScreen(JSONObject(returnRoute)) }.getOrDefault(Screen.Home)
            }
        )

    /**
     * The screen a route's [returnRoute] names, decoded but NOT sanitized.
     *
     * [landingOf] answers "where does Back go", so it runs the sanitizer. This
     * answers "which screen is my opener", which is data a child screen is
     * handed verbatim (the `returnTo` a Detail or a player carries), exactly as
     * the arm passed `current`. Null when there is no argument or it cannot be
     * read, and the caller then uses that screen's own default - which is what
     * encoding the default would have produced anyway.
     */
    fun decoded(returnRoute: String?): Screen? =
        returnRoute?.let { encoded ->
            runCatching { decodeScreen(JSONObject(encoded)) }.getOrNull()
        }

    /**
     * The [Screen] a route stands for: the inverse of [of].
     *
     * Every destination renders from ITS OWN entry with this, never from a
     * shell-level "current screen" - that is what lets one host own every
     * destination and the shell stop holding navigation state at all. It is
     * also what makes the graph testable: `screenOf(of(x))` is `x` for every
     * destination, so a route that loses a field is a wrong screen rather than a
     * subtle one.
     *
     * `returnTo` decodes through [decoded], not [landingOf]: a screen's own
     * `returnTo` is DATA that a child screen is handed verbatim, and sanitizing
     * it here would strip a Detail's one-shot before the page that carries it
     * has even been built.
     */
    fun screenOf(route: Any): Screen = when (route) {
        is HomeRoute -> Screen.Home
        is SearchRoute -> Screen.Search
        is GuideRoute -> Screen.Guide
        is LibraryRoute -> Screen.Library
        is SettingsRoute -> Screen.Settings
        is RemoteRoute -> Screen.Remote
        is StatsRoute -> Screen.Stats
        is SportsRoute -> Screen.Sports(
            detailGameId = route.detailGameId,
            detailTournamentId = route.detailTournamentId
        )
        is AddonsRoute -> Screen.Addons(returnTo = decoded(route.returnRoute) ?: Screen.Home)
        is CatalogBuilderRoute ->
            Screen.CatalogBuilder(returnTo = decoded(route.returnRoute) ?: Screen.Home)
        is SimklRoute -> Screen.Simkl(returnTo = decoded(route.returnRoute) ?: Screen.Home)
        is TraktRoute -> Screen.Trakt(returnTo = decoded(route.returnRoute) ?: Screen.Home)
        is BingeCatRoute -> Screen.BingeCat(returnTo = decoded(route.returnRoute) ?: Screen.Home)
        is PublicListsRoute -> Screen.PublicLists(
            vendor = route.vendor,
            returnTo = decoded(route.returnRoute) ?: Screen.Settings
        )
        is YearReviewRoute -> Screen.YearReview(
            year = route.year,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is DetailRoute -> Screen.Detail(
            type = route.type,
            id = route.id,
            itemPoster = route.itemPoster,
            itemBackdrop = route.itemBackdrop,
            itemClearLogo = route.itemClearLogo,
            itemOverview = route.itemOverview,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is ActorRoute -> Screen.Actor(
            personId = route.personId,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is StudioRoute -> Screen.Studio(
            id = route.id,
            name = route.name,
            isNetwork = route.isNetwork,
            providerId = route.providerId,
            networkOrCompanyId = route.networkOrCompanyId,
            networkIsCompany = route.networkIsCompany,
            originalsCompanyId = route.originalsCompanyId,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is DecadeRoute -> Screen.Decade(
            decadeStart = route.decadeStart,
            name = route.name,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is TagRoute -> Screen.Tag(
            id = route.id,
            name = route.name,
            isKeyword = route.isKeyword,
            mediaType = route.mediaType,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is CollectionRoute -> Screen.Collection(
            id = route.id,
            name = route.name,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is CatalogGridRoute -> Screen.CatalogGrid(
            title = route.title,
            addonName = route.addonName,
            catalogId = route.catalogId,
            type = route.type,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is KBFolderRoute -> Screen.KBFolder(
            folderId = route.folderId,
            returnTo = decoded(route.returnRoute) ?: Screen.Home
        )
        is ProfilePickerRoute -> Screen.ProfilePicker(returnTo = decoded(route.returnRoute))
        is ProfileEditRoute -> Screen.ProfileEdit(
            editingProfileId = route.editingProfileId,
            returnTo = decoded(route.returnRoute)
        )
        is StreamsRoute -> {
            val carried = route.payloadJson
                ?.let { runCatching { decodeScreen(JSONObject(it)) }.getOrNull() }
                as? Screen.Streams
            // No payload means an entry nothing can open (the picker is only
            // ever reached with a target), so it lands Home rather than building
            // a picker with no stream to pick.
            if (carried == null) {
                Screen.Home
            } else {
                carried.copy(
                    parentId = route.parentId,
                    parentType = route.parentType,
                    returnTo = decoded(route.returnRoute) ?: Screen.Home
                )
            }
        }
        // Not a destination, and no route names it: the overlay's launch spec.
        else -> Screen.Home
    }
}
