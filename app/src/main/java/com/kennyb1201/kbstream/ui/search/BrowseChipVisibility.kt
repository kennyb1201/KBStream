package com.kennyb1201.kbstream.ui.search

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * Per-profile record of the browse chips the user hid from the long-press
 * menu on a submenu chip.
 *
 * Stored as a SharedPreferences string set under the profile-scoped name
 * (see [ProfileStorage.prefsName]) exactly like the watched overrides, so
 * hiding a chip on one profile never removes it from another profile's
 * sidebar.
 *
 * A key is "<categoryKey>\u0001<chip name>". The NAME is the identity
 * rather than the TMDB id on purpose: keyword and collection chips are
 * resolved at runtime, so their ids are not known until the resolver has
 * run, and a name survives a resolve, a disk-cache refresh, and the
 * kids/adult list swap unchanged. Names are unique within a category.
 */
object BrowseChipVisibility {

    private const val PREFS = "kbstream_hidden_browse_chips"
    private const val KEY_HIDDEN = "hidden"
    private const val SEPARATOR = "\u0001"

    /** Stable identity for one chip. */
    fun key(categoryKey: String, name: String): String =
        "$categoryKey$SEPARATOR${name.trim()}"

    fun hiddenKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    /** Hides one chip; returns the updated set. */
    fun hide(context: Context, categoryKey: String, name: String): Set<String> {
        val updated = hiddenKeys(context).toMutableSet().apply {
            add(key(categoryKey, name))
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN, updated).apply()
        return updated
    }

    /**
     * Brings back every chip hidden in one category; returns the updated
     * set. Scoped to the category so "unhide" on the Studios strip can
     * never resurrect a chip the user hid under Services & Networks.
     */
    fun unhideAll(context: Context, categoryKey: String): Set<String> {
        val prefix = "$categoryKey$SEPARATOR"
        val updated = hiddenKeys(context).filterNot { it.startsWith(prefix) }.toSet()
        prefs(context).edit().putStringSet(KEY_HIDDEN, updated).apply()
        return updated
    }

    /** How many chips of [categoryKey] are currently hidden. */
    fun countHidden(categoryKey: String, hidden: Set<String>): Int {
        val prefix = "$categoryKey$SEPARATOR"
        return hidden.count { it.startsWith(prefix) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS),
        Context.MODE_PRIVATE
    )
}
