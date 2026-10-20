package com.kennyb1201.kbstream.data.settings

/**
 * Which tracker's resume point the Continue Watching rail believes.
 *
 * Every linked tracker keeps receiving scrobbles and watched marks - authority
 * changes only which provider's position/episode a card shows when more than
 * one of them knows the title. The others stay in the merge as the fallback
 * for titles the authoritative source has not heard about, so choosing one
 * never empties the rail.
 *
 * [LOCAL] is the app's own history, and the default: it is the only source
 * that carries an exact millisecond position from this device.
 */
enum class ResumeAuthority(
    /** The string stored in prefs and in the synced display blob. */
    val storageValue: String,
    /** How the settings row names it. */
    val label: String
) {
    LOCAL("local", "This device"),
    SIMKL("simkl", "Simkl"),
    MDBLIST("mdblist", "MDBList"),
    TRAKT("trakt", "Trakt");

    companion object {
        val DEFAULT = LOCAL

        /**
         * A stored value, or [DEFAULT] for anything this build does not know.
         * The pref syncs, so a value can arrive from a newer build that offers
         * a source this one cannot read; an unknown answer must not break the
         * rail.
         */
        fun fromStorage(value: String?): ResumeAuthority =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
                ?: DEFAULT
    }
}
