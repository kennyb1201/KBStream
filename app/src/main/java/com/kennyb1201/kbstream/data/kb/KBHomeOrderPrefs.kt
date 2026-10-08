package com.kennyb1201.kbstream.data.kb

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * User-managed home rail ordering that spans BOTH addon catalog rails and
 * KB collection rails, so a collection can sit between two addon
 * catalogs (collection, addon catalog, collection, ...).
 *
 * Stored as one JSON blob:
 *  - [order]: every managed rail key in display order (addons and
 *    collections mixed). Keys missing from it keep their default position
 *    (addon global order, then collection import order).
 *  - [pinned]: rail keys pinned to the top (right after Continue Watching),
 *    in pin order.
 *  - [hidden]: rail keys removed from Home.
 *  - [renames]: per-rail title overrides (key -> the name the viewer gave it).
 *
 * The whole order spans THREE families, not two: addon catalogs, KB
 * collections, and the built-in rails this app draws itself (Continue
 * Watching, Upcoming Schedule). The built-ins are the last family to join, and
 * they join through exactly this structure rather than through a mechanism of
 * their own - one arrangement governs every rail on Home.
 *
 * A Browse rail (a group of browse-menu chips mirrored to Home: genres and
 * tags, services and networks, studios, decades, collections) is a rail in
 * both senses: it takes an entry in [order], it can be pinned, and it can be
 * hidden. They are the only keys outside the two import families that get a
 * pin control, which is why "may this key be pinned?" is asked through
 * [KBHomeOrderPrefs.isPinnableKey] rather than through the collection test.
 */
@JsonClass(generateAdapter = true)
data class KBHomeOrder(
    val order: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    /**
     * Per-rail title overrides: arrangement key -> the name the viewer gave it.
     *
     * A map on the arrangement rather than a field on each rail's own model,
     * because the rails this must cover share no model: a built-in rail has no
     * configuration object at all, a catalog already keeps its name elsewhere,
     * and the arrangement is the one thing every rail on Home has in common.
     *
     * Absent (or blank) means "no override" - a cleared name is removed from
     * the map, never stored as an empty string, so "is this rail renamed?" is
     * just a lookup.
     */
    val renames: Map<String, String> = emptyMap()
) {
    val hiddenSet: Set<String> get() = hidden.toSet()
}

/**
 * Pinned is collections-and-Browse-row, so "is this key a collection" is
 * decided in one place. Catalog rails ("addon:...") get their position from
 * [KBHomeOrder.order] alone; the highest one can sit is the head of that
 * block, below any pinned collections.
 */
internal const val KB_COLLECTION_KEY_PREFIX = "kb:"

/**
 * Built-in rails: the rows this app draws itself instead of fetching them from
 * an addon manifest or importing them from a collection profile, so nothing
 * else could name them.
 *
 * They are arranged like every other rail - orderable and hideable - and their
 * keys are stable strings, which is why they need none of the migration and
 * alias handling collections carry: a built-in key never changes, so an
 * arrangement that names one keeps naming it.
 */
internal const val KB_BUILTIN_KEY_PREFIX = "builtin:"

/**
 * User-built catalog rails: rows composed in the Catalog Builder from the
 * filter set (genre, service, studio, decade, language, rating, ...).
 *
 * Their key space is a family of its own rather than an "addon:" key with a
 * sentinel base URL, because the distinction has to be readable: a rail in this
 * family has no manifest to fall back on at all, and a check for
 * "is this a built catalog?" that reads a fake URL would be a lie the manager,
 * the loader and Home all had to keep telling.
 */
internal const val KB_CUSTOM_CATALOG_KEY_PREFIX = "custom:"

/**
 * Top/bottom moves on the merged arrangement.
 *
 * TOP pins a PINNABLE rail (a collection or a Browse rail) to the head of the
 * pinned block (the absolute first rail), but only moves a CATALOG to the head
 * of the order block — a catalog parked in the pinned list could never be
 * unpinned, because the manager has no pin control on a catalog row, so it
 * stayed above every pinned collection forever. Any catalog found in the
 * pinned list here is lifted out on the way.
 *
 * BOTTOM unpins either kind and appends it after everything else.
 */
internal fun moveRailToEnd(
    value: KBHomeOrder,
    key: String,
    toTop: Boolean
): KBHomeOrder {
    // Start from a repaired arrangement so the result can never carry a stray
    // catalog in pinned (see [normalizeHomeOrder]) — whatever the caller read.
    val prefs = normalizeHomeOrder(value)
    val hidden = prefs.hidden - key
    return when {
        !toTop ->
            prefs.copy(
                pinned = prefs.pinned - key,
                order = (prefs.order - key) + key,
                hidden = hidden
            )

        KBHomeOrderPrefs.isPinnableKey(key) ->
            prefs.copy(
                pinned = listOf(key) + prefs.pinned.filter { it != key },
                order = prefs.order - key,
                hidden = hidden
            )

        else ->
            prefs.copy(
                pinned = prefs.pinned - key,
                order = listOf(key) + prefs.order.filter { it != key },
                hidden = hidden
            )
    }
}

/**
 * The merged rail order one arrangement produces from [defaults].
 *
 * This is the single answer to "which order are the manageable rails in?", and
 * every place that shows or changes that order has to ask it: the home
 * manager's row list, the reorder core, and (through KBHomeSlots) Home itself.
 * They used to each derive it separately, and the manager's own copy put the
 * Browse rails LAST while Home and the reorder core put them FIRST - so a
 * Browse rail was drawn at the bottom of the manager's list while its move
 * buttons treated it as already sitting at the top, and pressing UP on it did
 * nothing at all. Interleaving a Browse rail with the catalogs was therefore
 * impossible from the manager, which is the one thing the manager exists for.
 *
 * [defaults] is every manageable rail key in its DEFAULT order, and that order
 * is part of the contract: Browse rails first, then catalog rails, then
 * collections - which is where Home falls back to for a rail nothing has
 * arranged yet.
 *
 * Pinned keys lead in pin order, then the stored order, then whatever is left
 * in default order. Keys in neither list keep their default slot, which is how
 * a rail added since the last arrangement still appears somewhere.
 */
internal fun mergedHomeRailKeys(
    prefs: KBHomeOrder,
    defaults: List<String>
): List<String> {
    val known = defaults.toSet()
    val positioned = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (key in prefs.pinned) {
        if (key in known && seen.add(key)) positioned += key
    }
    for (key in prefs.order) {
        if (key in known && seen.add(key)) positioned += key
    }
    for (key in defaults) {
        if (seen.add(key)) positioned += key
    }
    return positioned
}

/**
 * Moves one rail one slot in the merged visible order (see
 * [mergedHomeRailKeys]), which is the order the manager draws and Home renders.
 *
 * The whole visible list is written into [KBHomeOrder.order] (pinned keys
 * staying pinned, in their new relative order), so a single move makes the
 * arrangement explicit: from then on the stored order alone decides where every
 * rail sits, and any rail that first appears later takes its default slot until
 * it too is moved. That is what makes the three rail families genuinely
 * interchangeable rather than only movable within their own block.
 *
 * A move that would not change the order - already at that edge, or a key the
 * arrangement does not know - returns [prefs] unchanged, so the caller can skip
 * the write and the dialog does not look like it swallowed the press.
 */
internal fun moveRailInMergedOrder(
    prefs: KBHomeOrder,
    defaults: List<String>,
    key: String,
    delta: Int
): KBHomeOrder {
    val visible = mergedHomeRailKeys(prefs, defaults)
        .filter { it !in prefs.hiddenSet }
    val from = visible.indexOf(key)
    if (from == -1) return prefs
    val to = (from + delta).coerceIn(0, visible.lastIndex)
    if (from == to) return prefs

    val ordered = visible.toMutableList()
    ordered.add(to, ordered.removeAt(from))

    // Re-split by pin membership: a moved PIN keeps its pinned status (it is
    // re-ordered within the pinned block), and a key that is not pinned cannot
    // sneak into the head.
    val pinnedSet = prefs.pinned.toSet()
    return prefs.copy(
        order = ordered.filter { it !in pinnedSet },
        pinned = ordered.filter { it in pinnedSet }
    )
}

/**
 * Whether the given move would actually change the arrangement.
 *
 * The manager uses this for its arrow buttons' enabled state, so an arrow is
 * offered exactly when pressing it does something: the same transforms decide
 * the answer as perform the move, so the two cannot drift.
 *
 * [delta] takes the same values [moveRailInMergedOrder] and [moveRailToEnd]
 * do, with Int.MIN_VALUE meaning "the very top" and Int.MAX_VALUE "the very
 * bottom".
 */
internal fun railMoveChangesOrder(
    prefs: KBHomeOrder,
    defaults: List<String>,
    key: String,
    delta: Int
): Boolean {
    return when (delta) {
        Int.MIN_VALUE -> moveRailToEnd(prefs, key, toTop = true) != prefs
        Int.MAX_VALUE -> moveRailToEnd(prefs, key, toTop = false) != prefs
        else -> moveRailInMergedOrder(prefs, defaults, key, delta) != prefs
    }
}

/**
 * Pin or unpin one PINNABLE rail (a collection or a Browse rail). Keys with
 * no pin control are returned unchanged (a catalog has no pin control, and one
 * sitting in `pinned` could never be taken back out — see [moveRailToEnd]).
 *
 * Pinning lifts the key to the head of the pinned block and takes it out of the
 * order block. Unpinning puts it back at the HEAD of the order block, which is
 * directly under the rails still pinned — where the user was looking.
 *
 * That unpin half is the part worth spelling out, because getting it wrong is
 * silent: a key in NEITHER list is what "never arranged" means, and a
 * never-arranged collection is hidden by default. So an unpin that merely
 * removed the key from [KBHomeOrder.pinned] did not un-pin the rail, it deleted
 * it from Home and dropped its manager row into the HIDDEN section.
 *
 * "Never arranged" has no such consequence for a Browse rail - it defaults to
 * visible (see KBHomeSlots) - but it is pinned and unpinned through this same
 * path so the manager has one control for both.
 */
internal fun toggleCollectionPin(value: KBHomeOrder, key: String): KBHomeOrder {
    if (!KBHomeOrderPrefs.isPinnableKey(key)) return value
    val prefs = normalizeHomeOrder(value)
    return if (key in prefs.pinned) {
        prefs.copy(
            pinned = prefs.pinned - key,
            order = listOf(key) + prefs.order.filter { it != key }
        )
    } else {
        prefs.copy(
            pinned = prefs.pinned + key,
            order = prefs.order - key
        )
    }
}

/**
 * Read-time repair for arrangements written before pinned was
 * collections-only: catalog keys sitting in [KBHomeOrder.pinned] are moved to
 * the head of the order block, in the sequence they were pinned in. Pinned
 * collections and the Browse rails are left where they are - see
 * [KBHomeOrderPrefs.isPinnableKey].
 *
 * This is what makes an already-stuck catalog movable again without the user
 * having to reset their whole arrangement — and the catalog keeps the high
 * position it was given, just below pinned collections instead of above them.
 * Duplicates within either list are dropped (first occurrence wins).
 */
internal fun normalizeHomeOrder(value: KBHomeOrder): KBHomeOrder {
    val pinned = value.pinned
        .filter { KBHomeOrderPrefs.isPinnableKey(it) }
        .distinct()
    val strays = value.pinned
        .filterNot { KBHomeOrderPrefs.isPinnableKey(it) }
        .distinct()
    val order = value.order
        .filterNot { it in pinned }
        .distinct()
    val healedOrder = strays + order.filterNot { it in strays }

    if (pinned == value.pinned && healedOrder == value.order) return value
    return value.copy(pinned = pinned, order = healedOrder)
}

/**
 * Rewrites addon arrangement keys after a manifest refresh replaced a catalog
 * (see `pairReplacedCatalogs` in data/addon).
 *
 * Dynamic addons (BingeCat) swap catalog ids as their content changes, and the
 * arrangement key embeds the id — so without this the position the user gave a
 * rail was orphaned the moment its id changed and the rail dropped to the
 * default tail. Pure so the rename rules are unit tested.
 */
internal fun remapAddonOrderKeys(
    value: KBHomeOrder,
    remap: Map<String, String>
): KBHomeOrder {
    if (remap.isEmpty()) return value
    fun swap(key: String): String = remap[key] ?: key
    // distinct(): if both the old and new key were somehow present, the
    // rewrite would otherwise leave the same rail twice in one list.
    val rewritten = value.copy(
        order = value.order.map(::swap).distinct(),
        pinned = value.pinned.map(::swap).distinct(),
        hidden = value.hidden.map(::swap).distinct()
    )
    return normalizeHomeOrder(rewritten)
}

object KBHomeOrderPrefs {

    private const val PREFS_NAME = "kbstream_kb_home_order"
    private const val KEY_BLOB = "home_order_json"

    /**
     * Last arrangement this device considers already synced (mirrors the key
     * [PrefsPayloadApplier.applyHomeOrder] reads). Written on every local save
     * so our own edit cannot be judged "older than remote" and reverted.
     *
     * It is ALSO the timestamp published as the blob's `updatedAt`, which is
     * why it is internal: an arrangement that has not changed on this device
     * must republish its old timestamp, not the time of the push. Stamping the
     * push time let any device that had merely opened the app win the
     * last-write-wins race and revert a sibling's fresh rail order (the exact
     * "my order didn't sync" failure - see [KBHomeOrderPrefs.save]).
     */
    internal const val SYNCED_AT_KEY = "home_order_synced_at"

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(KBHomeOrder::class.java)

    /** Stable key for one KB collection rail on Home. */
    fun collectionKey(collectionId: String?, title: String?): String =
        KB_COLLECTION_KEY_PREFIX +
            (collectionId?.takeIf { it.isNotBlank() } ?: title.orEmpty())

    /** Title-only alias key, used to detect id changes on re-uploaded profiles. */
    fun collectionTitleAliasKey(title: String?): String =
        KB_COLLECTION_KEY_PREFIX + title.orEmpty()

    /** True for a collection arrangement key (catalog keys are "addon:..."). */
    fun isCollectionKey(key: String): Boolean =
        key.startsWith(KB_COLLECTION_KEY_PREFIX)

    /** Stable arrangement key for a built-in rail (see [KB_BUILTIN_KEY_PREFIX]). */
    fun builtinKey(id: String): String = KB_BUILTIN_KEY_PREFIX + id

    /**
     * Stable arrangement key for one user-built catalog rail (see
     * [KB_CUSTOM_CATALOG_KEY_PREFIX]). Keyed by the catalog's own id, which the
     * builder never reuses, so a renamed catalog keeps its Home position.
     */
    fun customCatalogKey(id: String): String = KB_CUSTOM_CATALOG_KEY_PREFIX + id

    /** True for a user-built catalog rail's arrangement key. */
    fun isCustomCatalogKey(key: String?): Boolean =
        key?.startsWith(KB_CUSTOM_CATALOG_KEY_PREFIX) == true

    /** True for a built-in rail's arrangement key. */
    fun isBuiltinKey(key: String?): Boolean =
        key?.startsWith(KB_BUILTIN_KEY_PREFIX) == true

    /**
     * Every built-in rail key there is: the rows this app builds for every
     * profile, then the rows it builds for one profile's kind (a kids
     * profile's two TMDB rows, a guest profile's fixed set).
     *
     * The registry names ALL of them on purpose, because the arrangement is the
     * one thing every rail on Home has in common: a rail whose key the
     * arrangement does not know could not be placed, moved or renamed at all.
     * Which of them a given profile actually DRAWS is a different question, and
     * it is answered in one place - [builtinKeysFor] - so a rail the profile
     * does not draw is absent from its manager rather than un-movable in it.
     *
     * The order here IS the default position: an arrangement that never
     * mentions these keys falls them back here (see [mergedHomeRailKeys]), which
     * is what keeps the default Home - Continue Watching first, then Upcoming,
     * then the Top Today rows, then the profile's own rails - exactly what it
     * drew before for a viewer who never opens the home manager.
     *
     * The Top Today rows are built-ins too. They used to be a family of their
     * own - addon catalogs whose position the loader FIXED above everything -
     * which is exactly why they could not sit in the manager with the rest: a
     * rail with no position to move from has no row to move. Keying them here
     * gives them one arrangement for every rail on Home, so the viewer can move
     * Continue Watching above them, hide them, or rename them like anything
     * else. Their content is still the addon feed Home fetches (see
     * `loadPinnedTopTodayRails`); only their POSITION joins the arrangement.
     *
     * Built lazily so it can name the three lists below however they are
     * declared: this registry is the one key list the rest of the object reads,
     * and its ORDER is the fallback position of a rail nothing has arranged.
     */
    val BUILTIN_KEYS: List<String> by lazy {
        BASE_BUILTIN_KEYS + KIDS_BUILTIN_KEYS + GUEST_BUILTIN_KEYS
    }

    /**
     * The rows EVERY profile draws, in their default order: Continue Watching,
     * Upcoming, then the two Top Today feed rows.
     *
     * This is the head of Home on every profile - a kids profile is the one
     * exception, and what it swaps is the two Top Today rows (see
     * [builtinKeysFor]).
     */
    private val BASE_BUILTIN_KEYS: List<String> = listOf(
        builtinKey("continue_watching"),
        builtinKey("upcoming_schedule"),
        builtinKey(TOP_TODAY_MOVIES_KEY_ID),
        builtinKey(TOP_TODAY_SHOWS_KEY_ID)
    )

    /**
     * The rails a KIDS profile draws where the Top Today rows would be: the
     * ceiling-filtered TMDB rows built by `loadPinnedKidsRails` - the two
     * standing "Top Kids" rows, then the two recency rows ("New Kids Movies" /
     * "New Kids Shows") and the two popularity rows ("Trending Kids Movies" /
     * "Trending Kids Shows") this app added beside them.
     *
     * They are built-in rails rather than add-on rails because that is what
     * makes them arrangeable and renamable: a rail keyed by an addon URL is a
     * rail no manifest describes, so it could never be listed in the manager
     * (see [builtinKeyForCatalogId] and `KBHomeSlots.buildMergedEntries`).
     *
     * The order here is the order they are drawn in: the standing rows keep
     * their slots, then the recency rows, then the trending rows.
     */
    val KIDS_BUILTIN_KEYS: List<String> = listOf(
        builtinKey(TOP_KIDS_MOVIES_KEY_ID),
        builtinKey(TOP_KIDS_SHOWS_KEY_ID),
        builtinKey(NEW_KIDS_MOVIES_KEY_ID),
        builtinKey(NEW_KIDS_SHOWS_KEY_ID),
        builtinKey(TRENDING_KIDS_MOVIES_KEY_ID),
        builtinKey(TRENDING_KIDS_SHOWS_KEY_ID)
    )

    /**
     * The rails a GUEST profile draws after the Top Today rows: the fixed set
     * of TMDB rows built by `loadPinnedGuestRails`, so a profile with no
     * add-ons installed still opens onto a full screen.
     *
     * Their order here is the order the loader builds them in, which is the
     * order a guest has always seen them in.
     */
    val GUEST_BUILTIN_KEYS: List<String> = listOf(
        builtinKey(GUEST_LATEST_DIGITAL_KEY_ID),
        builtinKey(GUEST_AIRING_NOW_KEY_ID),
        builtinKey(GUEST_TRENDING_WEEK_KEY_ID),
        builtinKey(GUEST_POPULAR_MOVIES_KEY_ID),
        builtinKey(GUEST_POPULAR_SHOWS_KEY_ID),
        builtinKey(GUEST_TOP_RATED_MOVIES_KEY_ID),
        builtinKey(GUEST_TOP_RATED_SHOWS_KEY_ID)
    )

    val BUILTIN_CONTINUE_WATCHING: String = BASE_BUILTIN_KEYS[0]
    val BUILTIN_UPCOMING_SCHEDULE: String = BASE_BUILTIN_KEYS[1]
    val BUILTIN_TOP_MOVIES_TODAY: String = BASE_BUILTIN_KEYS[2]
    val BUILTIN_TOP_SHOWS_TODAY: String = BASE_BUILTIN_KEYS[3]
    val BUILTIN_TOP_KIDS_MOVIES: String = KIDS_BUILTIN_KEYS[0]
    val BUILTIN_TOP_KIDS_SHOWS: String = KIDS_BUILTIN_KEYS[1]
    val BUILTIN_NEW_KIDS_MOVIES: String = KIDS_BUILTIN_KEYS[2]
    val BUILTIN_NEW_KIDS_SHOWS: String = KIDS_BUILTIN_KEYS[3]
    val BUILTIN_TRENDING_KIDS_MOVIES: String = KIDS_BUILTIN_KEYS[4]
    val BUILTIN_TRENDING_KIDS_SHOWS: String = KIDS_BUILTIN_KEYS[5]
    val BUILTIN_GUEST_LATEST_DIGITAL: String = GUEST_BUILTIN_KEYS[0]
    val BUILTIN_GUEST_AIRING_NOW: String = GUEST_BUILTIN_KEYS[1]
    val BUILTIN_GUEST_TRENDING_WEEK: String = GUEST_BUILTIN_KEYS[2]
    val BUILTIN_GUEST_POPULAR_MOVIES: String = GUEST_BUILTIN_KEYS[3]
    val BUILTIN_GUEST_POPULAR_SHOWS: String = GUEST_BUILTIN_KEYS[4]
    val BUILTIN_GUEST_TOP_RATED_MOVIES: String = GUEST_BUILTIN_KEYS[5]
    val BUILTIN_GUEST_TOP_RATED_SHOWS: String = GUEST_BUILTIN_KEYS[6]

    /**
     * The built-in arrangement key a Top Today catalog is keyed by, or null for
     * anything that is not one of the two pinned feed rows.
     *
     * Matched on the catalog id the feed names, which is stable; the two ids
     * are this app's own constants, not a manifest's.
     */
    fun topTodayBuiltinKey(catalogId: String?): String? = when (catalogId) {
        TOP_TODAY_MOVIES_KEY_ID -> BUILTIN_TOP_MOVIES_TODAY
        TOP_TODAY_SHOWS_KEY_ID -> BUILTIN_TOP_SHOWS_TODAY
        else -> null
    }

    /**
     * The built-in rails THIS profile draws on Home, in their default order.
     *
     * The order is the same shape for every profile: Continue Watching,
     * Upcoming, the two Top Today rows, then the rails built for this profile's
     * kind - and every one of them is a row the viewer can move, hide and
     * rename, because every one of them is a built-in key (see
     * [builtinKeyForCatalogId]).
     *
     * The one substitution is the kids profile's: Home does not load the two
     * Top Today rows for it at all - it swaps its pinned batch for the
     * ceiling-filtered kids rails instead (see the pinned branch of
     * HomeViewModel's rail load) - so a kids profile draws [KIDS_BUILTIN_KEYS]
     * where those two would be. A guest profile draws the Top Today rows and
     * then its own fixed set ([GUEST_BUILTIN_KEYS]), and an ordinary profile
     * draws neither of the profile-specific families.
     *
     * Listing a rail the profile does not draw would offer a row whose arrows
     * are worse than inert: a move would be computed against a rail the viewer
     * cannot see, so the rail they DID press on would jump two slots at once.
     * Both the manager's list and the ViewModel's arrangement defaults
     * therefore ask this, not [BUILTIN_KEYS].
     *
     * [BUILTIN_KEYS] stays the full list on purpose: the arrangement still has
     * to know every key, so a profile that later loses its ceiling (or stops
     * being the guest) comes back to an arrangement that names them - a rename
     * or a hide written under the other profile is honoured again.
     */
    fun builtinKeysFor(kidsMaxAge: Int?, isGuest: Boolean = false): List<String> = when {
        kidsMaxAge != null ->
            BASE_BUILTIN_KEYS.filterNot {
                it == BUILTIN_TOP_MOVIES_TODAY || it == BUILTIN_TOP_SHOWS_TODAY
            } + KIDS_BUILTIN_KEYS

        // Kids Mode wins over the guest flag, exactly as Home's rail load does
        // (a profile that is both gets the ceiling-filtered kids rails).
        isGuest -> BASE_BUILTIN_KEYS + GUEST_BUILTIN_KEYS

        else -> BASE_BUILTIN_KEYS
    }

    /**
     * The section title a built-in rail draws when the viewer has not renamed
     * it.
     *
     * The profile rails' names are the ones the loaders build them with
     * (`loadPinnedKidsRails`, `loadPinnedGuestRails`), repeated here because the
     * manager has to show a row's name - and the default it offers to restore -
     * whether or not anything has been loaded into it yet.
     */
    fun builtinDefaultTitle(key: String): String? = when (key) {
        BUILTIN_CONTINUE_WATCHING -> "Continue Watching"
        BUILTIN_UPCOMING_SCHEDULE -> "Upcoming"
        BUILTIN_TOP_MOVIES_TODAY -> "Top Movies Today"
        BUILTIN_TOP_SHOWS_TODAY -> "Top Shows Today"
        BUILTIN_TOP_KIDS_MOVIES -> "Top Kids Movies"
        BUILTIN_TOP_KIDS_SHOWS -> "Top Kids Shows"
        BUILTIN_NEW_KIDS_MOVIES -> "New Kids Movies"
        BUILTIN_NEW_KIDS_SHOWS -> "New Kids Shows"
        BUILTIN_TRENDING_KIDS_MOVIES -> "Trending Kids Movies"
        BUILTIN_TRENDING_KIDS_SHOWS -> "Trending Kids Shows"
        BUILTIN_GUEST_LATEST_DIGITAL -> "Latest Digital Releases"
        BUILTIN_GUEST_AIRING_NOW -> "Airing Now"
        BUILTIN_GUEST_TRENDING_WEEK -> "Trending This Week"
        BUILTIN_GUEST_POPULAR_MOVIES -> "Popular Movies"
        BUILTIN_GUEST_POPULAR_SHOWS -> "Popular Shows"
        BUILTIN_GUEST_TOP_RATED_MOVIES -> "Top Rated Movies"
        BUILTIN_GUEST_TOP_RATED_SHOWS -> "Top Rated Shows"
        else -> null
    }

    /** The title a rail draws: the viewer's override, else [defaultTitle]. */
    fun railTitle(order: KBHomeOrder, key: String, defaultTitle: String): String =
        order.renames[key]?.takeIf { it.isNotBlank() } ?: defaultTitle

    /**
     * Sets (or clears) one rail's title override. A blank name clears it rather
     * than storing blank, so an override is always a name.
     */
    fun withRename(value: KBHomeOrder, key: String, name: String?): KBHomeOrder {
        val clean = name?.trim().orEmpty()
        val renames = value.renames.toMutableMap()
        if (clean.isEmpty()) {
            renames.remove(key)
        } else {
            renames[key] = clean
        }
        return value.copy(renames = renames)
    }

    /**
     * Hides a BUILT-IN rail, or shows a hidden one. Pure, so the manager's
     * hide toggle is testable without an Activity or a prefs file.
     *
     * SHOW only clears the flag: a built-in is already known to the merged
     * order (its key is one of [BUILTIN_KEYS]), so unlike a collection it does
     * not have to be added to `order` to count as arranged.
     *
     * HIDE also lifts the key out of `pinned` and `order`, so the hidden state
     * is the only thing remembered about it and it comes back at its default
     * slot rather than at an arrangement the viewer cannot see. (The key is not
     * pinnable - see [isPinnableKey] - so the pinned strip is defensive.)
     */
    fun toggleBuiltinHidden(value: KBHomeOrder, key: String): KBHomeOrder =
        toggleRailHidden(value, key)

    /**
     * Hides a rail that is visible until it is hidden, or shows a hidden one.
     * Pure, and shared by every rail whose default is VISIBLE: the built-in
     * rows, and now the user-built catalogs composed in the Catalog Builder.
     *
     * SHOW only clears the flag: such a rail is already known to the merged
     * order (its key is one of the caller's defaults), so unlike a collection it
     * does not have to be added to `order` to count as arranged.
     *
     * HIDE also lifts the key out of `pinned` and `order`, so the hidden state
     * is the only thing remembered about it and it comes back at its default
     * slot rather than at an arrangement the viewer cannot see.
     */
    fun toggleRailHidden(value: KBHomeOrder, key: String): KBHomeOrder =
        if (key in value.hiddenSet) {
            value.copy(hidden = value.hidden - key)
        } else {
            value.copy(
                hidden = value.hidden + key,
                pinned = value.pinned - key,
                order = value.order - key
            )
        }

    /**
     * True for a rail the manager can PIN: a collection, or a Browse rail.
     *
     * Catalog rails are deliberately not pinnable - the manager offers no pin
     * control on a catalog row, so one that reached the pinned list could
     * never be taken back out (see [normalizeHomeOrder]).
     */
    fun isPinnableKey(key: String): Boolean =
        isCollectionKey(key) || BrowseHomeShortcuts.isShortcutKey(key)

    /** Catalog ids of the two pinned "Top Today" feed rows (see `loadPinnedTopTodayRails`). */
    private const val TOP_TODAY_MOVIES_KEY_ID = "top_movies_today"
    private const val TOP_TODAY_SHOWS_KEY_ID = "top_shows_today"

    /** Catalog ids of the two standing kids rails (see `loadPinnedKidsRails`). */
    private const val TOP_KIDS_MOVIES_KEY_ID = "top_kids_movies"
    private const val TOP_KIDS_SHOWS_KEY_ID = "top_kids_shows"

    /**
     * Catalog ids of the two "new kids" recency rails, which sit after the
     * standing pair. Stable on purpose: they are the arrangement keys of rows
     * this app fetches and names itself (see [builtinKeyForCatalogId]).
     */
    private const val NEW_KIDS_MOVIES_KEY_ID = "new_kids_movies"
    private const val NEW_KIDS_SHOWS_KEY_ID = "new_kids_shows"

    /**
     * Catalog ids of the two "trending kids" rows, which follow the new pair.
     * Stable on purpose: they are the arrangement keys of rows this app fetches
     * and names itself (see [builtinKeyForCatalogId]).
     */
    private const val TRENDING_KIDS_MOVIES_KEY_ID = "trending_kids_movies"
    private const val TRENDING_KIDS_SHOWS_KEY_ID = "trending_kids_shows"

    /**
     * Catalog ids of the guest rails (see `loadPinnedGuestRails`), in the order
     * that loader builds them.
     */
    private const val GUEST_LATEST_DIGITAL_KEY_ID = "guest_latest_digital"
    private const val GUEST_AIRING_NOW_KEY_ID = "guest_airing_now"
    private const val GUEST_TRENDING_WEEK_KEY_ID = "guest_trending_week"
    private const val GUEST_POPULAR_MOVIES_KEY_ID = "guest_popular_movies"
    private const val GUEST_POPULAR_SHOWS_KEY_ID = "guest_popular_shows"
    private const val GUEST_TOP_RATED_MOVIES_KEY_ID = "guest_top_rated"
    private const val GUEST_TOP_RATED_SHOWS_KEY_ID = "guest_top_rated_shows"

    /**
     * The arrangement key an APP-BUILT rail is placed by, matched on the catalog
     * id the loader builds it with, or null for anything that is not one.
     *
     * The two Top Today feed rows and the profile rails (`loadPinnedKidsRails`,
     * `loadPinnedGuestRails`) are rows this app fetches and names itself: they
     * have no manifest to key against, so keying them by an add-on URL would be
     * keying them by nothing - which is exactly why they used to be fixed above
     * every arranged rail. Under their own built-in keys they take a position in
     * the one arrangement, which is what lets the viewer move, hide and rename
     * them like anything else on Home.
     */
    fun builtinKeyForCatalogId(catalogId: String?): String? = when (catalogId) {
        TOP_TODAY_MOVIES_KEY_ID -> BUILTIN_TOP_MOVIES_TODAY
        TOP_TODAY_SHOWS_KEY_ID -> BUILTIN_TOP_SHOWS_TODAY
        TOP_KIDS_MOVIES_KEY_ID -> BUILTIN_TOP_KIDS_MOVIES
        TOP_KIDS_SHOWS_KEY_ID -> BUILTIN_TOP_KIDS_SHOWS
        NEW_KIDS_MOVIES_KEY_ID -> BUILTIN_NEW_KIDS_MOVIES
        NEW_KIDS_SHOWS_KEY_ID -> BUILTIN_NEW_KIDS_SHOWS
        TRENDING_KIDS_MOVIES_KEY_ID -> BUILTIN_TRENDING_KIDS_MOVIES
        TRENDING_KIDS_SHOWS_KEY_ID -> BUILTIN_TRENDING_KIDS_SHOWS
        GUEST_LATEST_DIGITAL_KEY_ID -> BUILTIN_GUEST_LATEST_DIGITAL
        GUEST_AIRING_NOW_KEY_ID -> BUILTIN_GUEST_AIRING_NOW
        GUEST_TRENDING_WEEK_KEY_ID -> BUILTIN_GUEST_TRENDING_WEEK
        GUEST_POPULAR_MOVIES_KEY_ID -> BUILTIN_GUEST_POPULAR_MOVIES
        GUEST_POPULAR_SHOWS_KEY_ID -> BUILTIN_GUEST_POPULAR_SHOWS
        GUEST_TOP_RATED_MOVIES_KEY_ID -> BUILTIN_GUEST_TOP_RATED_MOVIES
        GUEST_TOP_RATED_SHOWS_KEY_ID -> BUILTIN_GUEST_TOP_RATED_SHOWS
        else -> null
    }

    /**
     * Resolves the arrangement key for a collection, honoring history: when
     * the collection's id-key has never been arranged but a previous upload
     * of the SAME TITLE was (its title-alias key exists in the saved sets —
     * e.g. BingeCat profiles re-exported with a new id), the new id-key is
     * migrated onto the old slot so the collection keeps its Home position,
     * pin, and visibility instead of dropping to the default-hidden tail.
     * One-time: writes the migrated key back into prefs.
     */
    fun resolveArrangementKey(context: Context, collectionId: String?, title: String?): String {
        val idKey = collectionKey(collectionId, title)
        val aliasKey = collectionTitleAliasKey(title)
        if (aliasKey == idKey) return idKey

        val prefs = get(context)
        val arranged = prefs.pinned.toSet() + prefs.order.toSet() + prefs.hiddenSet
        if (idKey in arranged || aliasKey !in arranged) return idKey

        // Migrate: replace the alias key with the id-key wherever it appears.
        val migrated = prefs.copy(
            pinned = prefs.pinned.map { if (it == aliasKey) idKey else it },
            order = prefs.order.map { if (it == aliasKey) idKey else it },
            hidden = prefs.hidden.map { if (it == aliasKey) idKey else it }
        )
        save(context, migrated)
        return idKey
    }

    /** Stable key for one addon catalog rail on Home (must match HomeScreen's rail fields). */
    fun addonKey(baseUrl: String?, type: String, catalogId: String?): String =
        "addon:${baseUrl.orEmpty()}:$type:${catalogId.orEmpty()}"

    /**
     * The exact addon rail key Home renders, derived from the manifest URL
     * the same way HomeViewModel derives Rail.baseUrl (manifest URL minus
     * the "/manifest.json" suffix and any trailing slash). Every manager
     * write path must use this so arrangement keys match the rails Home
     * actually builds — keying by addon id or by the raw manifest URL
     * silently writes keys Home can never match.
     */
    fun addonKeyFromManifest(manifestUrl: String?, type: String, catalogId: String?): String =
        addonKey(
            manifestUrl.orEmpty()
                .removeSuffix("/manifest.json")
                .removeSuffix("/"),
            type,
            catalogId
        )

    /** Context-free read: uses the most recent get(context) caller's app context. */
    @Volatile
    private var lastContext: Context? = null

    fun readOrder(): KBHomeOrder =
        lastContext?.let { get(it) } ?: KBHomeOrder()

    fun get(context: Context): KBHomeOrder {
        lastContext = context.applicationContext
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return KBHomeOrder()
        val parsed = runCatching { adapter.fromJson(raw) }.getOrNull()
            ?: KBHomeOrder()
        // Repairs the catalogs an older build pinned, on every read path
        // (manager dialog, Home's merged order, the cloud blob): they come
        // back out of pinned and take the top of the order block instead.
        return normalizeHomeOrder(parsed)
    }

    fun save(context: Context, value: KBHomeOrder) {
        // Write the local blob FIRST. buildHomeOrder() below reads this same
        // pref, so enqueuing before the write pushed the PREVIOUS arrangement
        // to the cloud — and because it stamped it "now", the next pull
        // judged that stale copy newer than what the user had just set and
        // reverted the edit (every reorder looked like it did not stick).
        //
        // SYNCED_AT_KEY is stamped too, so the pull's "is the remote older
        // than my last sync?" guard treats this edit as already synced and an
        // older sibling-device copy can never clobber it - and so the pushed
        // blob carries THIS edit's time rather than the push's.
        prefs(context).edit()
            .putString(KEY_BLOB, adapter.toJson(value) ?: "{}")
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()

        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_HOME_ORDER,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildHomeOrder(appContext)
            )
        }
    }

    /**
     * Rewrites arrangement keys after a manifest refresh replaced catalogs (see
     * [remapAddonOrderKeys]). Called from the addon merge, the one place that
     * sees both the old and the new catalog ids.
     */
    fun remapAddonKeys(context: Context, remap: Map<String, String>) {
        if (remap.isEmpty()) return
        val current = get(context)
        val updated = remapAddonOrderKeys(current, remap)
        if (updated != current) save(context, updated)
    }

    /** Drop every manual arrangement (used by "Reset order" in Settings). */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )
}
