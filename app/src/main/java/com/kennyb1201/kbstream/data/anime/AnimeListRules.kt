package com.kennyb1201.kbstream.data.anime

/**
 * The anime lists' pure rules (spec 2026-11-13, F1/F2), free of JSON and Android.
 *
 * An anime tracker's list is named and addressed by the pair (which group it is,
 * what the service calls it). One ref shape is what the Library shelf, the Home
 * pin and the public-list card all carry, so a list cannot be found by one name
 * on the rail and another at the pin:
 *
 *  - a STATUS group is `status:<service status>` - AniList `status:CURRENT`,
 *    Kitsu `status:current`, MAL `status:watching`;
 *  - an AniList CUSTOM list is `custom:<name>`, because that is the only
 *    identity AniList gives it (custom lists have no status of their own).
 *
 * The ref is deliberately not the group's position: the rail reindexes whenever
 * a list is added, and a pin keyed on position would silently start pointing at
 * a neighbour.
 */
internal object AnimeListRules {

    const val STATUS_PREFIX = "status:"
    const val CUSTOM_PREFIX = "custom:"

    /** The ref of a status group, from the service's own status word. */
    fun statusRef(rawStatus: String?): String =
        STATUS_PREFIX + rawStatus.orEmpty().trim().lowercase(java.util.Locale.ROOT)

    /** The ref of an AniList custom list, from its name. */
    fun customRef(name: String): String = CUSTOM_PREFIX + name.trim().lowercase(java.util.Locale.ROOT)

    /**
     * An AniList group's ref.
     *
     * `isCustomList` is the field that decides it: a custom list can be named
     * anything, including "Completed", so the name alone cannot say which kind
     * of group this is - and two groups must never collapse onto one ref.
     */
    fun aniListRef(isCustomList: Boolean, name: String?, status: String?): String? {
        if (isCustomList) {
            val trimmed = name?.trim().orEmpty()
            return if (trimmed.isEmpty()) null else customRef(trimmed)
        }
        val raw = status?.trim()?.takeIf { it.isNotEmpty() }
            ?: statusForAniListName(name)
            ?: return null
        return statusRef(raw)
    }

    /** The rail title of an AniList group: its own name, else the status word. */
    fun aniListName(name: String?, status: String?): String {
        val named = name?.trim().takeIf { !it.isNullOrEmpty() }
        if (named != null && statusForAniListName(named) == null) return named
        val raw = status?.trim()?.takeIf { it.isNotEmpty() }
            ?: statusForAniListName(named.orEmpty())
        return displayName(raw)
    }

    /** AniList's six status words, as the rail names them. */
    fun displayName(rawStatus: String?): String =
        when (rawStatus.orEmpty().trim().uppercase(java.util.Locale.ROOT)) {
            "CURRENT" -> "Watching"
            "PLANNING" -> "Planning"
            "COMPLETED" -> "Completed"
            "PAUSED" -> "Paused"
            "DROPPED" -> "Dropped"
            "REPEATING" -> "Repeating"
            "ON_HOLD", "ON HOLD" -> "On hold"
            "PLAN_TO_WATCH" -> "Planning"
            "WATCHING" -> "Watching"
            else -> rawStatus.orEmpty().trim().ifEmpty { "List" }
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        }

    /** The status word an AniList group NAME stands for, when it is one. */
    private fun statusForAniListName(name: String?): String? =
        when (name.orEmpty().trim().uppercase(java.util.Locale.ROOT)) {
            "WATCHING", "CURRENT" -> "CURRENT"
            "PLANNING" -> "PLANNING"
            "COMPLETED" -> "COMPLETED"
            "PAUSED" -> "PAUSED"
            "DROPPED" -> "DROPPED"
            "REPEATING" -> "REPEATING"
            else -> null
        }

    /** Kitsu's own status words, in the order the rail lists them. */
    val KITSU_STATUSES = listOf(
        "current",
        "planned",
        "completed",
        "on_hold",
        "dropped"
    )

    /** MAL's own `list_status.status` words, in the rail's order. */
    val MAL_STATUSES = listOf(
        "watching",
        "plan_to_watch",
        "completed",
        "on_hold",
        "dropped"
    )

    /**
     * The name of a saved (copied) public anime list in the Library.
     *
     * A copy is a LOCAL list, so it must not read as the service's own row: the
     * vendor is in the name for the same reason the Library's source line names
     * it, and the name is deterministic so a second save of the same list is
     * recognised as one.
     */
    fun copyName(listName: String, vendorLabel: String): String =
        "${listName.trim().ifEmpty { "Anime list" }} · $vendorLabel"
}
