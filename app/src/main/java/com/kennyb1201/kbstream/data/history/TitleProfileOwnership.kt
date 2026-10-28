package com.kennyb1201.kbstream.data.history

import android.content.Context
import org.json.JSONObject

/**
 * Which profiles a title belongs to ON THIS DEVICE.
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
 * history for. Every such card therefore names one of its title's owners, and
 * the tracker cards whose title is owned ONLY by OTHER profiles are dropped
 * from the rails (see
 * [com.kennyb1201.kbstream.ui.home.trackerCardOwnedByAnotherProfile]).
 *
 * MULTI-OWNER (spec C10/D4). A title watched by two profiles is owned by both,
 * so both profiles keep their tracker card. The map used to hold exactly one
 * owner per title, last writer wins, which meant the second household member to
 * watch a show took it away from the first - and which owner won depended on
 * pull order (the lexicographically-greatest profile id in a batch), not on
 * recency. Each entry is now a SET of profile ids, each with the latest local
 * activity seen for it, so order can never decide ownership again.
 *
 * OWNER SET semantics:
 * - empty set = nobody on this device watched it ("no local owner"), which
 *   suppresses nothing, exactly as an absent entry always did;
 * - the set shrinks again when a profile's evidence is removed - its history
 *   rows deleted, its history cleared, its tombstones applied, the profile
 *   itself deleted (see [removeOwner], [removeProfile]).
 *
 * Titles with no owner at all are deliberately left alone: a show watched on
 * another device, or before this device had profiles, is not something this TV
 * can attribute, and hiding it would break the cross-device Continue Watching
 * the tracker feeds exist for.
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

    /**
     * key -> (profileId -> latest local activity for that profile, ms).
     *
     * A nested map rather than one owner string: see the multi-owner note
     * above. The timestamp is bookkeeping - it makes "latest activity wins"
     * the rule for a re-record, and it is what the pull writes from a history
     * row's own `updatedAt` - and is never consulted by the suppression rule.
     */
    private var cache: MutableMap<String, MutableMap<String, Long>>? = null

    @Synchronized
    private fun map(context: Context): MutableMap<String, MutableMap<String, Long>> {
        cache?.let { return it }

        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OWNERS, null)

        val loaded = LinkedHashMap<String, MutableMap<String, Long>>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(raw)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.isBlank()) continue
                    when (val value = json.opt(key)) {
                        // The pre-multi-owner shape: one profile id string.
                        // Migrated in place as a one-element set, with no
                        // activity stamp (nothing recorded one then, and
                        // inventing "now" would beat a real later record).
                        is String -> if (value.isNotBlank()) {
                            loaded[key] = linkedMapOf(value to 0L)
                        }

                        is JSONObject -> {
                            val owners = LinkedHashMap<String, Long>()
                            val profiles = value.keys()
                            while (profiles.hasNext()) {
                                val profileId = profiles.next()
                                if (profileId.isBlank()) continue
                                owners[profileId] = value.optLong(profileId, 0L)
                            }
                            if (owners.isNotEmpty()) loaded[key] = owners
                        }

                        else -> Unit
                    }
                }
            }
        }

        cache = loaded
        return loaded
    }

    /**
     * The whole map for one rail pass, as key -> owner profile ids. Copied:
     * callers never see the memo, and the timestamps stay inside this object.
     */
    fun snapshot(context: Context): Map<String, Set<String>> =
        synchronized(this) {
            map(context).mapValues { (_, owners) -> owners.keys.toSet() }
        }

    /**
     * Note that [profileId] watched [title] at [atMs]. Only a CHANGE is
     * written: the rails call this on every publish and the player writes
     * progress rows constantly, so a prefs write per call would be paid for by
     * every tick of playback.
     */
    fun record(
        context: Context,
        type: String?,
        title: String?,
        profileId: String?,
        atMs: Long = System.currentTimeMillis()
    ) {
        val key = titleProfileKey(type, title) ?: return
        val owner = profileId?.takeIf { it.isNotBlank() } ?: return
        recordAll(context, mapOf(key to mapOf(owner to atMs)))
    }

    /**
     * Notes many owners at once, with a single prefs write.
     *
     * The sync pull knows every profile's history rows for the account at the
     * same moment (each row's key is scoped to the profile that wrote it), so
     * the titles of profiles THIS device has not opened since the map was
     * introduced arrive in one batch - and a batch must not turn into one
     * prefs write per title.
     *
     * [ownersByKey] is key -> (profileId -> activity ms). A profile already
     * recorded keeps the LATER of the two stamps, so a rebuild from an older
     * pull can never walk a newer local activity backwards.
     */
    fun recordAll(
        context: Context,
        ownersByKey: Map<String, Map<String, Long>>
    ) {
        if (ownersByKey.isEmpty()) return

        synchronized(this) {
            val owners = map(context)
            var changed = false

            ownersByKey.forEach { (key, additions) ->
                if (key.isBlank()) return@forEach
                val existing = owners[key]
                var entry = existing
                var entryChanged = false

                additions.forEach { (profileId, atMs) ->
                    if (profileId.isBlank()) return@forEach
                    val stored = entry?.get(profileId)
                    if (entry == null) {
                        entry = LinkedHashMap()
                    }
                    if (stored == null || atMs > stored) {
                        entry?.put(profileId, atMs)
                        entryChanged = true
                    }
                }

                if (entryChanged && entry != null) {
                    owners[key] = entry
                    changed = true
                }
            }

            if (!changed) return

            persist(context, owners)
        }
    }

    /**
     * Removes ONE profile's claim on one title - a history row of theirs was
     * deleted, or a tombstone for it was applied.
     *
     * The whole entry goes when it was their only claim, so an un-owned title
     * is absent rather than present-and-empty: both answer "nobody here owns
     * it", and absent keeps the file small.
     */
    fun removeOwner(
        context: Context,
        type: String?,
        title: String?,
        profileId: String?
    ) {
        val key = titleProfileKey(type, title) ?: return
        removeOwner(context, key, profileId)
    }

    /** [removeOwner] by an already-built key (the sync paths have one). */
    fun removeOwner(context: Context, titleKey: String, profileId: String?) {
        val owner = profileId?.takeIf { it.isNotBlank() } ?: return
        if (titleKey.isBlank()) return

        synchronized(this) {
            val owners = map(context)
            val entry = owners[titleKey] ?: return
            if (entry.remove(owner) == null) return
            if (entry.isEmpty()) {
                owners.remove(titleKey)
            }
            persist(context, owners)
        }
    }

    /**
     * Forgets one profile everywhere: their history was cleared, or the profile
     * itself was deleted. A profile that no longer exists (or no longer has any
     * history) owns nothing, and leaving its claim behind would keep hiding the
     * title from the profiles that DO own it.
     */
    fun removeProfile(context: Context, profileId: String?) {
        val owner = profileId?.takeIf { it.isNotBlank() } ?: return

        synchronized(this) {
            val owners = map(context)
            var changed = false
            val iterator = owners.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.remove(owner) != null) {
                    changed = true
                    if (entry.isEmpty()) iterator.remove()
                }
            }
            if (!changed) return
            persist(context, owners)
        }
    }

    /** Forgets every owner. Used when the whole device's history is wiped. */
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

    /** One prefs write for the whole map. Callers hold this object's lock. */
    private fun persist(
        context: Context,
        owners: Map<String, Map<String, Long>>
    ) {
        val json = JSONObject()
        owners.forEach { (storedKey, byProfile) ->
            val entry = JSONObject()
            byProfile.forEach { (profileId, atMs) ->
                runCatching { entry.put(profileId, atMs) }
            }
            runCatching { json.put(storedKey, entry) }
        }

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OWNERS, json.toString())
            .apply()
    }
}

/**
 * Whether [title] is owned ONLY by profiles other than [activeProfileId].
 *
 * The rule Continue Watching's tracker filter applies
 * ([com.kennyb1201.kbstream.ui.home.trackerCardOwnedByAnotherProfile]), stated
 * once over the pieces any caller has: the ownership map, a type and a name.
 * The TorBox cloud→Library sync is the second consumer - one shared household
 * TorBox account holds the kids' titles as well, and a round used to mirror all
 * of it into whichever profile was ambient.
 *
 * MULTI-OWNER (spec C10/D4): a title this profile has watched TOO is not
 * "owned by another profile", whatever a sibling also did with it. Only a set
 * that excludes the viewing profile suppresses anything.
 *
 * Dropping only on POSITIVE evidence is the whole rule: no live profile, no key
 * for this title, no recorded owner, or an EMPTY owner set all answer false,
 * because an unattributed title is not somebody else's.
 */
internal fun titleOwnedByAnotherProfile(
    ownerByTitleKey: Map<String, Set<String>>,
    type: String?,
    title: String?,
    activeProfileId: String?
): Boolean {
    val active = activeProfileId?.takeIf { it.isNotBlank() } ?: return false
    val key = titleProfileKey(type, title) ?: return false
    val owners = ownerByTitleKey[key] ?: return false
    if (owners.isEmpty()) return false
    return active !in owners
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
    val name = titleIdentityName(title) ?: return null
    return "title:${titleProfileMediaType(type)}:$name"
}

/**
 * A title reduced to its IDENTITY: lowercased letters, digits and single
 * spaces, with every other character dropped.
 *
 * One normaliser, shared by both title keys - the rail card's
 * ([com.kennyb1201.kbstream.ui.home.upNextTitleKey]) and the ownership key
 * above. They must produce the same key or the tracker filter silently stops
 * matching, which is what the pinning test exists for; they also must not
 * disagree about what punctuation means, and they did: the ownership key was
 * never able to see a name the other side had punctuated differently.
 *
 * Punctuation is not identity. The reported case: a Continue Watching card
 * reading "R.J. Decker" and a history row reading "RJ Decker" were one show
 * that could never be paired - the finished show's card survived every merge.
 * Letters and digits stay, because an accent, a digit and a space are all how a
 * title is actually spelled; dropping them would merge different shows.
 *
 * Null for a name with nothing left to key on (blank, "!!!").
 */
internal fun titleIdentityName(title: String?): String? =
    title
        ?.trim()
        ?.lowercase()
        ?.filter { character -> character.isLetterOrDigit() || character.isWhitespace() }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

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
