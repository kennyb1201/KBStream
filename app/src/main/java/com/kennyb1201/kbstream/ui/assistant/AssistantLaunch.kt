package com.kennyb1201.kbstream.ui.assistant

/**
 * The three places Google Assistant and the launcher shortcuts can send the app
 * (spec 2026-11-13, "KBStream — Google Assistant control spec", F1 and F3).
 *
 * One vocabulary with two carriers, because Assistant hands a destination over
 * in two different ways and the app must not learn two:
 *
 *  - **An extra.** The `actions.intent.OPEN_APP_FEATURE` fulfillment passes the
 *    matched inline-inventory shortcut's `shortcutId` under the capability's own
 *    parameter key ([EXTRA_FEATURE]); the ids in `res/xml/shortcuts.xml` are
 *    these constants.
 *  - **A link.** A launcher long-press shortcut launches its own intent, and
 *    that intent carries [LINK_PREFIX] + the same id, so a shortcut and a spoken
 *    "open Continue Watching on KBStream" land on the same code path.
 *
 * Deliberately free of Android imports: this is the part of the wiring worth
 * pinning with a plain unit test, and it is the part a second caller (a widget,
 * a remote) would reuse. Nothing here searches, resolves a title or plays:
 * "play <title>" is the existing `MEDIA_PLAY_FROM_SEARCH` handler in
 * `VoiceSearchActivity`, untouched by this feature.
 */
internal object AssistantLaunch {

    /**
     * The action the "play <title>" custom intent is fulfilled with.
     *
     * The app's own action rather than the platform's `MEDIA_PLAY_FROM_SEARCH`,
     * because that one is implicit and another installed media app may declare
     * the same filter - an Assistant fulfillment resolved into a chooser is a
     * worse experience than one this app owns. `VoiceSearchActivity` filters
     * this action and routes it into the SAME `resolveSpokenTitle` the media
     * action uses; it is a second doorway, not a second resolver.
     */
    const val ACTION_PLAY_TITLE = "com.kennyb1201.kbstream.action.PLAY_TITLE"

    /** The intent extra an `OPEN_APP_FEATURE` fulfillment fills in. */
    const val EXTRA_FEATURE = "feature"

    /** The intent extra a plain "open KBStream" launch may fill in instead. */
    const val EXTRA_DESTINATION = "kbstream.assistant.destination"

    /** Home, at the Continue Watching rail (see [HomeContinueWatchingRequest]). */
    const val CONTINUE_WATCHING = "continue_watching"

    /** The Search screen. */
    const val SEARCH = "search"

    /** The Library screen. */
    const val LIBRARY = "library"

    /**
     * The link every launcher shortcut carries: `kbstream://assistant/<id>`.
     *
     * A custom scheme rather than the `kbstream://title` host, because that one
     * is filtered by `VoiceSearchActivity` and resolves a TMDB id; these are
     * destinations inside `MainActivity`, which is targeted explicitly.
     */
    const val LINK_PREFIX = "kbstream://assistant/"

    /**
     * The destination an intent names, or null when it names none.
     *
     * [feature] is read first: an `OPEN_APP_FEATURE` fulfillment fills it in,
     * and when both are present (a shortcut that also matched an inventory
     * entity) it is the one Assistant actually chose. Unknown values return
     * null rather than a default - an app feature this build does not have must
     * open the app as usual, never a wrong screen.
     */
    fun destination(feature: String?, link: String?): String? =
        normalize(feature)
            ?: normalize(
                link?.takeIf { it.startsWith(LINK_PREFIX, ignoreCase = true) }
                    ?.substring(LINK_PREFIX.length)
            )

    /** The link a launcher shortcut for [destination] carries. */
    fun linkFor(destination: String): String = LINK_PREFIX + destination

    private fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase() ?: return null
        return when (text) {
            CONTINUE_WATCHING, "continue-watching", "continue watching" -> CONTINUE_WATCHING
            SEARCH -> SEARCH
            LIBRARY -> LIBRARY
            else -> null
        }
    }
}
