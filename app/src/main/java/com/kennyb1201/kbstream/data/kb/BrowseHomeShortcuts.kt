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
 * The whole set renders as ONE row ("Browse"), not one row per chip: a viewer
 * adding six genres wants six tiles in a place they can find, not six rails
 * wedged between Continue Watching and the catalog. The row is a single
 * arrangement key (see [ROW_KEY]), so it is pinned and moved as a unit, and
 * the tiles inside it keep the order they were added in.
 */
object BrowseHomeShortcuts {

    private const val PREFS = "kbstream_browse_home_shortcuts"
    private const val KEY_BLOB = "browse_home_shortcuts_json"

    /** Chip identity: a browse category plus the chip's name within it. */
    private const val SEPARATOR = "\u0001"

    /** Key space for this row's arrangement entry ("addon:...", "kb:..."). */
    private const val PREFIX = "browse:"

    /**
     * Arrangement key for the one shared row.
     *
     * Prefixed so the home order prefs can tell this rail apart from an addon
     * catalog key ("addon:...") and a collection key ("kb:...") - and, in
     * particular, so it can be PINNED. Pinned is otherwise collections-only,
     * because a catalog found there could never be taken back out again (the
     * manager has no pin control on a catalog row); this row has one, since
     * the manager shows it as a row of its own.
     */
    const val ROW_KEY = PREFIX + SEPARATOR + "row"

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(BrowseHomeShortcutsBlob::class.java)

    /** Stable identity for one added chip, matching the hidden-chip key shape. */
    fun chipKey(categoryKey: String, name: String): String =
        "$categoryKey$SEPARATOR${name.trim()}"

    /** True when [key] belongs to the Browse row's arrangement key space. */
    fun isShortcutKey(key: String?): Boolean =
        key?.startsWith(PREFIX) == true

    /** True for the shared row's arrangement key specifically. */
    fun isRowKey(key: String?): Boolean = key == ROW_KEY

    fun list(context: Context): List<BrowseHomeShortcut> {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw) }
            .getOrNull()
            ?.shortcuts
            .orEmpty()
    }

    fun contains(
        shortcuts: List<BrowseHomeShortcut>,
        categoryKey: String,
        name: String
    ): Boolean = shortcuts.any { it.chipKey() == chipKey(categoryKey, name) }

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
        save(context, updated)
        return updated
    }

    /** Removes one chip; returns the updated set. */
    fun remove(
        context: Context,
        categoryKey: String,
        name: String
    ): List<BrowseHomeShortcut> {
        val current = list(context)
        val updated = current.filterNot { it.chipKey() == chipKey(categoryKey, name) }
        if (updated.size == current.size) return current
        save(context, updated)
        return updated
    }

    private fun save(context: Context, shortcuts: List<BrowseHomeShortcut>) {
        prefs(context).edit()
            .putString(
                KEY_BLOB,
                adapter.toJson(BrowseHomeShortcutsBlob(shortcuts)) ?: "{}"
            )
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS),
        Context.MODE_PRIVATE
    )
}

/** Identity of this chip as an entry in the row. */
internal fun BrowseHomeShortcut.chipKey(): String =
    BrowseHomeShortcuts.chipKey(categoryKey, name)

/**
 * Whether the shared Browse row should be offered at all: a row with nothing
 * in it is not a row, and a row the user hid in the home manager stays hidden.
 * Pure so the rule is reachable by a test.
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
 * Where the shared Browse row belongs on Home for one arrangement state.
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

    /** Never arranged: directly under the Top Today rows. */
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
