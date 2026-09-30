package com.kennyb1201.kbstream.data.history

import android.content.Context
import org.json.JSONObject

/**
 * Which profile a title belongs to ON THIS DEVICE.
 *
 * Local watch history is already profile-scoped: every profile reads and
 * writes its own history database file. The TRACKER accounts behind Continue
 * Watching are not - Simkl and MDBList hold one library per account, not per
 * profile - so a show watched on a sibling profile came back as a tracker card
 * on every profile. Reported as "there's a kids show in my continue watching
 * on profile 1 that's supposed to be in profile 3": profile 1 had never
 * watched it, had no local history for it at all, and still showed it, because
 * the card came from the account-wide feed.
 *
 * The missing half is attribution, and this device already knows it: a local
 * continue-watching card only exists for a title the ACTIVE profile has
 * history for. Every such card therefore names its title's owner, and the
 * tracker cards whose title is owned by a DIFFERENT profile are dropped from
 * the rails (see
 * [com.kennyb1201.kbstream.ui.home.trackerCardOwnedByAnotherProfile]).
 *
 * Titles with no owner at all are deliberately left alone: a show watched on
 * another device, or before this device had profiles, is not something this
 * TV can attribute, and hiding it would break the cross-device Continue
 * Watching the tracker feeds exist for.
 *
 * The map is small (one entry per title ever watched on this device), is
 * written only when an entry CHANGES, and is memoized in memory because the
 * rails ask for it on every publish.
 *
 * Top-level [titleProfileKey] rather than a member so a test can pin it
 * against the rail's own card key without standing up a Context.
 */
internal object TitleProfileOwnership {

    private const val PREFS = "kbstream_title_profiles"
    private const val KEY_OWNERS = "title_owner_json"

    private var cache: MutableMap<String, String>? = null

    @Synchronized
    private fun map(context: Context): MutableMap<String, String> {
        cache?.let { return it }

        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OWNERS, null)

        val loaded = LinkedHashMap<String, String>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(raw)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val owner = json.optString(key)
                    if (key.isNotBlank() && owner.isNotBlank()) {
                        loaded[key] = owner
                    }
                }
            }
        }

        cache = loaded
        return loaded
    }

    /** The whole map, for one rail pass. Copied: callers never see the memo. */
    fun snapshot(context: Context): Map<String, String> =
        synchronized(this) { HashMap(map(context)) }

    fun ownerOf(
        context: Context,
        titleKey: String
    ): String? = synchronized(this) { map(context)[titleKey] }

    /**
     * Note that [profileId] watched [title]. Only a CHANGE is written: the
     * rails call this on every publish and the player writes progress rows
     * constantly, so a prefs write per call would be paid for by every tick of
     * playback.
     */
    fun record(
        context: Context,
        type: String?,
        title: String?,
        profileId: String?
    ) {
        val key = titleProfileKey(type, title) ?: return
        val owner = profileId?.takeIf { it.isNotBlank() } ?: return

        recordAll(context, mapOf(key to owner))
    }

    /**
     * Notes many owners at once, with a single prefs write.
     *
     * The sync pull knows every profile's history rows for the account at the
     * same moment (each row's key is scoped to the profile that wrote it), so
     * the titles of profiles THIS device has not opened since the map was
     * introduced arrive in one batch - and a batch must not turn into one
     * prefs write per title.
     */
    fun recordAll(
        context: Context,
        ownersByKey: Map<String, String>
    ) {
        if (ownersByKey.isEmpty()) return

        synchronized(this) {
            val owners = map(context)
            var changed = false

            ownersByKey.forEach { (key, owner) ->
                if (key.isNotBlank() && owner.isNotBlank() && owners[key] != owner) {
                    owners[key] = owner
                    changed = true
                }
            }

            if (!changed) return

            val json = JSONObject()
            owners.forEach { (storedKey, storedOwner) ->
                runCatching { json.put(storedKey, storedOwner) }
            }

            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OWNERS, json.toString())
                .apply()
        }
    }

    /** Forgets every owner. Used when a profile's history is wiped. */
    @Synchronized
    fun clear(context: Context) {
        cache = LinkedHashMap()
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_OWNERS)
                .apply()
        }
    }
}

/**
 * The ownership key of a title, from the type and name a stored history row
 * carries. Deliberately the same shape the rail's card key uses
 * ([com.kennyb1201.kbstream.ui.home.upNextTitleKey]) - the two are pinned
 * together by a test, because a drift between them would silently stop the
 * tracker filter from ever matching.
 *
 * Null for a title with no name to key on: an unnamed row owns nothing.
 */
internal fun titleProfileKey(
    type: String?,
    title: String?
): String? {
    val name = title?.trim()?.lowercase().orEmpty()
    if (name.isEmpty()) return null
    return "title:${titleProfileMediaType(type)}:$name"
}

/**
 * Media type as the ownership key sees it. Mirrors the rail's own collapse
 * ([com.kennyb1201.kbstream.ui.home.upNextMediaType]): anything the app cannot
 * place shares the "unknown" bucket rather than inventing a second identity for
 * one show.
 */
internal fun titleProfileMediaType(type: String?): String =
    when (type?.trim()?.lowercase()) {
        "movie" -> "movie"
        "series", "show", "tv" -> "series"
        else -> "unknown"
    }
