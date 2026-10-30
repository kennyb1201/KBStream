package com.kennyb1201.kbstream.data.settings

/**
 * How the phone Home billboard treats trailers.
 *
 * The billboard's own choice, and its own pref: the trailER itself is still
 * played by the app's one hero-trailer stack (the pooled ExoPlayer, the
 * existing delay and mute settings), but whether a page offers one at all is
 * the billboard's decision, not the TV hero's. Someone who likes a trailer on
 * the title they are browsing may not want one on a page that swaps itself out
 * every six seconds.
 *
 * Stored as an Int rather than an ordinal: an ordinal is the declaration
 * order's index, so inserting a mode later would silently rename every stored
 * value. Device-local, like the rest of the billboard's prefs - a phone-only
 * surface (see `AppPreferences`'s billboard section).
 */
enum class BillboardTrailerMode(val storageValue: Int) {
    /** No trailers. Pages are backdrops with their copy (the default). */
    OFF(0),

    /** The current page's trailer starts on its own after the hero-trailer delay. */
    AUTO(1),

    /** No autoplay; the page carries a Trailer button that starts it on a press. */
    MANUAL(2);

    companion object {
        /**
         * A stored value as one of the modes.
         *
         * An unknown value is [OFF], not a crash and not a guess: OFF is the
         * mode that plays nothing, so a build that cannot read what an older
         * one wrote stays quiet rather than surprising the viewer with video.
         */
        fun fromStorage(raw: Int?): BillboardTrailerMode =
            entries.firstOrNull { it.storageValue == raw } ?: OFF
    }
}
