package com.kennyb1201.kbstream.data.kb

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * One browse-menu chip the user mirrored onto Home.
 *
 * The Search screen's browse browser is the app's deepest door into the
 * catalog, but reaching a genre or a network meant walking Search -> its
 * category tab -> the chip, every time. Long-pressing a chip now offers
 * "Add to Home", and what it stores is exactly what re-opening that chip's
 * discover screen needs - the same fields the browse browser's own press
 * handler routes on - so the Home tile opens the live screen and is not a
 * copy of it.
 *
 * [categoryKey] is the browse category ("genres", "services", ...) and is what
 * decides which screen the tile opens. [id] is whatever that category's screen
 * is keyed by: a TMDB genre id, a watch-provider id, a network / company id, a
 * collection id, or a decade's start year - the same value the chip carries.
 */
@JsonClass(generateAdapter = true)
data class BrowseHomeShortcut(
    val categoryKey: String,
    val id: Int,
    val name: String,
    /** Services only: the watch-provider id, for the "On Now" rails. */
    val providerId: Int? = null,
    /** Services only: the network / company id, for the Originals rail. */
    val networkOrCompanyId: Int? = null,
    /** Services only: whether that id is a company rather than a network. */
    val networkIsCompany: Boolean = false,
    /** Services only: the brand's production company, for the extra rail. */
    val originalsCompanyId: Int? = null
)

/** The stored blob: one list of chips, so a read is a single pref lookup. */
@JsonClass(generateAdapter = true)
internal data class BrowseHomeShortcutsBlob(
    val shortcuts: List<BrowseHomeShortcut> = emptyList()
)

/**
 * Per-profile record of the browse chips shown on Home.
 *
 * Stored under the profile-scoped prefs name (see [ProfileStorage.prefsName])
 * like the hidden-chip record, so a chip added on one profile never shows up
 * on another profile's Home.
 *
 * The chips render as rails by KIND, not one row per chip and not one shared
 * row for everything: genres and tags together, services and networks
 * together, then studios, decades and collections each on their own (see
 * [BrowseShortcutRail]). A viewer adding six genres wants one rail of six
 * tiles, not six rails wedged between Continue Watching and the catalogue - and
 * a genre next to a network is two different doors wearing the same tile.
 *
 * Each rail is its own arrangement key, so it is pinned, moved and hidden on
 * its own, and the tiles inside it keep the order they were added in. Before
 * that split there was a single shared row keyed [LEGACY_ROW_KEY]; an
 * arrangement that still names it is honoured for every rail that has no
 * arrangement of its own (see [browseRailArrangementOf]).
 */
object BrowseHomeShortcuts {

    private const val PREFS = "kbstream_browse_home_shortcuts"
    private const val KEY_BLOB = "browse_home_shortcuts_json"

    /** Where an unparseable blob is preserved, beside the original. */
    private const val KEY_BLOB_CORRUPT = "browse_home_shortcuts_json_corrupt"

    /** The unreadable blob already reported, so a read on every Home load is not a log storm. */
    @Volatile
    private var loggedUnreadable: String? = null

    /**
     * The profile's raw chip blob, verbatim, for the cross-device payload.
     * The store owns its own encoding (see [save]) and the sync layer hands
     * the string straight back, so a blob written here and a blob adopted
     * from the cloud can never disagree about the shape.
     */
    internal const val SYNC_STORE = PREFS
    internal const val SYNC_BLOB_KEY = KEY_BLOB

    /**
     * Local bookkeeping: when this device last edited OR adopted the blob.
     * Never published — it is what lets the pull tell "a sibling's copy"
     * from "my own newer edit", the same job KBHomeOrderPrefs.SYNCED_AT_KEY
     * does for the rail arrangement.
     */
    const val SYNCED_AT_KEY = "browse_shortcuts_synced_at"

    /** Chip identity: a browse category plus the chip's name within it. */
    private const val SEPARATOR = "\u0001"

    /** Key space for this row's arrangement entry ("addon:...", "kb:..."). */
    private const val PREFIX = "browse:"

    /**
     * Arrangement key of the single row the chips used to share.
     *
     * Kept as the fallback for an arrangement written before the rails were
     * split by kind (see [browseRailArrangementOf]) - and as the rail key of
     * chips whose category this build does not know, so a blob written by a
     * newer build still has somewhere to render.
     *
     * Prefixed so the home order prefs can tell a browse rail apart from an
     * addon catalog key ("addon:...") and a collection key ("kb:...") - and,
     * in particular, so it can be PINNED. Pinned is otherwise
     * collections-only, because a catalog found there could never be taken
     * back out again (the manager has no pin control on a catalog row); a
     * browse rail has one, since the manager shows it as a row of its own.
     */
    const val LEGACY_ROW_KEY = PREFIX + SEPARATOR + "row"

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(BrowseHomeShortcutsBlob::class.java)

    /**
     * Stable identity for one added chip, matching the hidden-chip key shape.
     *
     * The ID is part of the key, not just the name: two chips in one category
     * can share a display name (two TMDB companies of the same name; a provider
     * and a network named for the same brand under Services), and a name-only
     * key silently refused the second add - and deleted BOTH on removal. The id
     * is the field that decides which screen the tile opens, so it is the field
     * that decides identity.
     */
    fun chipKey(categoryKey: String, id: Int, name: String): String =
        "$categoryKey$SEPARATOR$id$SEPARATOR${name.trim()}"

    /** True when [key] belongs to a Browse rail's arrangement key space. */
    fun isShortcutKey(key: String?): Boolean =
        key?.startsWith(PREFIX) == true

    /** True for the legacy shared row's arrangement key specifically. */
    fun isLegacyRowKey(key: String?): Boolean = key == LEGACY_ROW_KEY

    fun list(context: Context): List<BrowseHomeShortcut> {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return emptyList()
        val parsed = runCatching { adapter.fromJson(raw) }.getOrNull()
        if (parsed == null) {
            archiveUnreadable(context, raw)
            return emptyList()
        }
        return parsed.shortcuts
    }

    /**
     * Whether the stored chip blob exists but this build cannot read it.
     *
     * The state a caller needs to say so rather than report a write that did
     * not happen - and the one [save] refuses on (sweep 7 3.11).
     */
    fun isBlobUnreadable(context: Context): Boolean {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return false
        return runCatching { adapter.fromJson(raw) }.getOrNull() == null
    }

    /**
     * Keeps an unreadable blob where the reader looks, and a copy beside it.
     *
     * The blob is NOT removed: it is the viewer's whole chip row, and it is
     * unreadable because of a shape THIS build does not know (a sideloaded
     * newer one, a schema drift) - so the next build that can read it restores
     * the row with no user action. The copy is written once, so a second
     * corruption cannot overwrite the first one's evidence.
     */
    private fun archiveUnreadable(context: Context, raw: String) {
        if (loggedUnreadable != raw) {
            loggedUnreadable = raw
            android.util.Log.w(
                "BROWSE_SHORTCUTS",
                "chip blob will not parse; keeping it in place (restored by a build that " +
                    "can read it), a copy under $KEY_BLOB_CORRUPT, and starting from an " +
                    "empty row. Writes are refused until it parses again."
            )
        }
        val store = prefs(context)
        if (store.contains(KEY_BLOB_CORRUPT)) return
        store.edit().putString(KEY_BLOB_CORRUPT, raw).apply()
    }

    fun contains(
        shortcuts: List<BrowseHomeShortcut>,
        categoryKey: String,
        id: Int,
        name: String
    ): Boolean = shortcuts.any { it.chipKey() == chipKey(categoryKey, id, name) }

    /**
     * Adds one chip; returns the updated set. Adding a chip that is already in
     * the row leaves it where it is, so a double press cannot reshuffle it.
     */
    fun add(
        context: Context,
        shortcut: BrowseHomeShortcut
    ): List<BrowseHomeShortcut> {
        val current = list(context)
        if (current.any { it.chipKey() == shortcut.chipKey() }) return current
        val updated = current + shortcut
        // The list returned is what is STORED, so a refused write cannot read
        // as an added chip on the screen that asked for it (see [save]).
        return if (save(context, updated)) updated else current
    }

    /** Removes one chip; returns the updated set. */
    fun remove(
        context: Context,
        categoryKey: String,
        id: Int,
        name: String
    ): List<BrowseHomeShortcut> {
        val current = list(context)
        val updated = current.filterNot { it.chipKey() == chipKey(categoryKey, id, name) }
        if (updated.size == current.size) return current
        // As in [add]: the returned list is what is stored.
        return if (save(context, updated)) updated else current
    }

    /**
     * Writes [shortcuts]; false when the write was refused.
     *
     * Refused while the stored blob is unreadable (see [archiveUnreadable]):
     * `list()` has just served an empty row, so saving would replace the
     * viewer's real chips with that emptiness plus this one edit - the blob wipe
     * this store's read-side fix stopped from happening silently (sweep 7 3.11).
     * Nothing is lost by refusing: the blob stays exactly where a build that can
     * read it will find it, and this session's row is already empty either way.
     * The cloud adoption path (PrefsPayloadApplier.applyBrowseShortcuts) writes
     * the blob wholesale, so a valid row from another device still restores it.
     */
    private fun save(context: Context, shortcuts: List<BrowseHomeShortcut>): Boolean {
        if (isBlobUnreadable(context)) {
            android.util.Log.w(
                "BROWSE_SHORTCUTS",
                "refusing to write ${shortcuts.size} chip(s): the stored blob is " +
                    "unreadable on this build and is the only copy of the viewer's row"
            )
            return false
        }
        // Write the blob AND stamp the local sync marker BEFORE enqueuing, so
        // the pushed payload carries this edit and the pull's staleness guard
        // treats it as already synced — an older sibling-device copy can then
        // never revert a chip the user just added (the same ordering bug that
        // made every rail reorder look like it had not stuck; see
        // KBHomeOrderPrefs.save).
        prefs(context).edit()
            .putString(
                KEY_BLOB,
                adapter.toJson(BrowseHomeShortcutsBlob(shortcuts)) ?: "{}"
            )
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()

        // Mirror to the account so the chips show up on the other TV. A
        // deliberate last-chip removal still publishes: it goes through this
        // path, not the bulk push that skips an empty blob.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_BROWSE_SHORTCUTS,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
                    .buildBrowseShortcuts(appContext)
            )
        }
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS),
        Context.MODE_PRIVATE
    )
}

/** Identity of this chip as an entry in its rail. */
internal fun BrowseHomeShortcut.chipKey(): String =
    BrowseHomeShortcuts.chipKey(categoryKey, id, name)

/**
 * The kind of browse chip a rail holds, and therefore where a chip added from
 * Browse - or from a Detail screen's own chips - lands on Home.
 *
 * The grouping is by KIND, which is not the same thing as by browse tab:
 * "genres" and "keywords" are two tabs of the same kind of door (browse by
 * subject), and "services" covers both watch providers and networks, which is
 * why a network chip added from a show's Detail page and a service chip added
 * from Browse end up beside each other. Studios (production companies) and
 * collections are each their own kind, and decades are a fourth.
 */
enum class BrowseShortcutRail(
    /** Stable slug, part of the rail's arrangement key. */
    val slug: String,
    /** What the rail is called on Home and in the home manager. */
    val title: String
) {
    GENRES_TAGS("genres", "Genres & Tags"),
    SERVICES("services", "Services & Networks"),
    STUDIOS("studios", "Studios"),
    DECADES("decades", "Decades"),
    COLLECTIONS("collections", "Collections");

    /**
     * Arrangement key for this rail ("browse:\u0001rail:genres", ...).
     *
     * Spelled out per rail rather than derived from [slug] through a private
     * name in the object, so the key space is readable from one place - the
     * home manager, the order prefs and Home all have to agree on it, and a
     * silent disagreement is a rail that can never be moved.
     */
    val key: String
        get() = "browse:\u0001rail:$slug"
}

/**
 * One rail of mirrored browse chips on Home: its kind, and the chips in it in
 * the order they were added.
 *
 * [key] and [title] are plain strings rather than the enum's own, because one
 * rail is not a kind at all: chips whose category this build does not know
 * ride the legacy shared row, so a blob written by a newer build still renders
 * (and can still be hidden) instead of vanishing.
 */
data class BrowseHomeRail(
    val key: String,
    val title: String,
    val shortcuts: List<BrowseHomeShortcut>
)

/** The rail one browse category's chips belong to, or null when unknown. */
internal fun browseShortcutRail(categoryKey: String?): BrowseShortcutRail? =
    when (categoryKey?.trim()?.lowercase()) {
        "genres", "keywords" -> BrowseShortcutRail.GENRES_TAGS
        "services" -> BrowseShortcutRail.SERVICES
        "studios" -> BrowseShortcutRail.STUDIOS
        "decades" -> BrowseShortcutRail.DECADES
        "collections" -> BrowseShortcutRail.COLLECTIONS
        else -> null
    }

/**
 * The rails Home draws, in their fixed order, carrying only the rails that
 * have chips in them.
 *
 * A rail with nothing in it is not a rail - the same rule a single shared row
 * followed (see [browseRowVisible]) - and the order is fixed rather than
 * "however the chips were added", so adding a studio never moves the genres
 * rail.
 */
internal fun browseHomeRails(
    shortcuts: List<BrowseHomeShortcut>
): List<BrowseHomeRail> {
    val grouped = BrowseShortcutRail.entries.mapNotNull { rail ->
        val chips = shortcuts.filter { browseShortcutRail(it.categoryKey) == rail }
        if (chips.isEmpty()) {
            null
        } else {
            BrowseHomeRail(key = rail.key, title = rail.title, shortcuts = chips)
        }
    }

    val unknown = shortcuts.filter { browseShortcutRail(it.categoryKey) == null }

    return if (unknown.isEmpty()) {
        grouped
    } else {
        // A category this build has no rail for (a blob written by a newer
        // one): keep the chips together on the legacy row rather than
        // dropping tiles the user added.
        grouped + BrowseHomeRail(
            key = BrowseHomeShortcuts.LEGACY_ROW_KEY,
            title = "Browse",
            shortcuts = unknown
        )
    }
}

/**
 * The chip that should take the focus when [removed] is taken off Home.
 *
 * Mirrors the Continue Watching delete's landing order: the next chip in the
 * same rail, else the previous chip in it (removing the last chip of a rail
 * lands on the new last), else - because the rail itself disappears once it has
 * no chips ([browseHomeRails] drops empty rails) - the first chip of the NEXT
 * rail, and finally the last chip of the previous rail. Null means the removal
 * left nothing anywhere to land on.
 *
 * [shortcuts] is the list as it stands BEFORE the removal, so the successor is
 * computed against what is actually on screen.
 */
internal fun browseChipSuccessor(
    removed: BrowseHomeShortcut,
    shortcuts: List<BrowseHomeShortcut>
): BrowseHomeShortcut? {
    val rails = browseHomeRails(shortcuts)
    val railIndex = rails.indexOfFirst { rail ->
        rail.shortcuts.any { it.chipKey() == removed.chipKey() }
    }
    if (railIndex < 0) return null

    val rail = rails[railIndex]
    val chipIndex = rail.shortcuts.indexOfFirst {
        it.chipKey() == removed.chipKey()
    }
    rail.shortcuts.getOrNull(chipIndex + 1)?.let { return it }
    rail.shortcuts.getOrNull(chipIndex - 1)?.let { return it }

    rails.getOrNull(railIndex + 1)?.shortcuts?.firstOrNull()?.let { return it }
    return rails.getOrNull(railIndex - 1)?.shortcuts?.lastOrNull()
}

/**
 * The arrangement flags that apply to one browse rail.
 *
 * A rail's OWN key wins as soon as the arrangement names it. Until then the
 * legacy shared row's flags apply, so an arrangement saved before the rails
 * were split keeps its pin or its place (and a hidden row stays hidden) for
 * every rail that has not been arranged since.
 */
internal data class BrowseRailArrangement(
    val hidden: Boolean,
    val pinned: Boolean,
    val arranged: Boolean
)

internal fun browseRailArrangementOf(
    key: String,
    legacyKey: String,
    pinnedKeys: Set<String>,
    order: List<String>,
    hiddenSet: Set<String>
): BrowseRailArrangement {
    val arrangedHere =
        key in pinnedKeys || key in hiddenSet || order.contains(key)

    return if (arrangedHere) {
        BrowseRailArrangement(
            hidden = key in hiddenSet,
            pinned = key in pinnedKeys,
            arranged = order.contains(key)
        )
    } else {
        BrowseRailArrangement(
            hidden = legacyKey in hiddenSet,
            pinned = legacyKey in pinnedKeys,
            arranged = order.contains(legacyKey)
        )
    }
}

/**
 * Whether a Browse rail should be offered at all: a row with nothing in it is
 * not a row, and a row the user hid in the home manager stays hidden. Pure so
 * the rule is reachable by a test.
 */
internal fun browseRowVisible(
    shortcuts: List<BrowseHomeShortcut>,
    hidden: Boolean
): Boolean = shortcuts.isNotEmpty() && !hidden

/**
 * The caption under a Browse tile: the name of the category the chip came
 * from, matching the browse browser's own tab labels.
 *
 * Kept next to the key space rather than read from the live category list,
 * because the tile has to say where it came from even when the browser that
 * produced it is not on screen (and a chip can be added on one profile and
 * shown on the same profile long after). An unknown key - a category added
 * later, or a blob written by a newer build - falls back to the raw key
 * rather than an empty tile.
 */
/**
 * Where a Browse rail belongs on Home for one arrangement state.
 *
 * The order of the cases is the whole rule: hidden or empty wins over every
 * position, then an explicit pin, then an explicit place in the stored order,
 * and only a row the user has never touched defaults to the spot under the
 * Top Today rows. Written out as a value rather than left as three separate
 * conditions inside the merge, because "where does my row go?" is the one
 * thing about this feature a viewer can see and complain about.
 */
internal enum class BrowseRowPlacement {
    /** Nothing added, or the row is hidden: it does not render at all. */
    NONE,

    /** Pinned in the home manager: it sits with the other pinned rails. */
    PINNED,

    /** Placed by the stored order: wherever the manager put it. */
    IN_ORDER,

    /** Never arranged (by its own key or the legacy one): under Top Today. */
    BELOW_TOP_TODAY
}

internal fun browseRowPlacement(
    shortcuts: List<BrowseHomeShortcut>,
    hidden: Boolean,
    pinned: Boolean,
    arranged: Boolean
): BrowseRowPlacement = when {
    !browseRowVisible(shortcuts, hidden) -> BrowseRowPlacement.NONE
    pinned -> BrowseRowPlacement.PINNED
    arranged -> BrowseRowPlacement.IN_ORDER
    else -> BrowseRowPlacement.BELOW_TOP_TODAY
}

internal fun browseShortcutCategoryLabel(categoryKey: String): String =
    when (categoryKey) {
        "genres" -> "Genres"
        "keywords" -> "Keywords"
        "services" -> "Services & Networks"
        "studios" -> "Studios"
        "decades" -> "Decades"
        "collections" -> "Collections"
        else -> categoryKey.replaceFirstChar { it.uppercase() }
    }
