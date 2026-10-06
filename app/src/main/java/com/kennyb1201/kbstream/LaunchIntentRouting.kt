package com.kennyb1201.kbstream

/**
 * What a launch intent's extras ask the shell to show.
 *
 * Resolved in one pure place because the same extras arrive by two routes: as
 * the activity's launch intent on a cold start, and through
 * `MainActivity.onNewIntent` when the app is already up
 * (`android:launchMode="singleTop"`). Four entry points deliver them — a TV
 * launcher Watch Next card, an OS global-search suggestion
 * (ui.search.SearchSuggestionsProvider), a spoken / system search
 * (ui.search.VoiceSearchActivity) and a live-TV reminder tap — and a shared
 * resolver is what keeps the warm path from drifting from the cold one.
 *
 * Precedence is the order the extras were read in before this was extracted: a
 * spoken query beats a launcher card, which beats a reminder. Only one of them
 * ever rides an intent in practice.
 *
 * Deliberately free of Android imports, like ui.search.SearchSuggestions: the
 * decision is what has to be testable, and an intent can only be built on a
 * device or under Robolectric. [resolveLaunchIntentRoute] therefore takes the
 * extras themselves.
 */
internal sealed interface LaunchIntentRoute {

    /** A live-TV reminder tap. The guide resolves the channel from its lineup. */
    data class Channel(val channelId: String) : LaunchIntentRoute

    /** A launcher Watch Next card / global-search suggestion. */
    data class Title(val type: String, val id: String) : LaunchIntentRoute

    /** Voice or system search, seeded into the Search screen. */
    data class Query(val query: String) : LaunchIntentRoute
}

/**
 * The destination a launch intent's extras name, or null when they name none.
 *
 * Blank extras are treated as absent, matching how the routing read them before
 * this was extracted: an empty `EXTRA_ID` is not a deep link, it is an intent
 * that happens to carry the key.
 */
internal fun resolveLaunchIntentRoute(
    launcherType: String?,
    launcherId: String?,
    reminderChannelId: String?,
    spokenQuery: String?
): LaunchIntentRoute? {
    if (!spokenQuery.isNullOrBlank()) return LaunchIntentRoute.Query(spokenQuery)
    if (!launcherType.isNullOrBlank() && !launcherId.isNullOrBlank()) {
        // The launcher's own type vocabulary is "tv"/"movie"; everywhere else in
        // the app (and Detail in particular) says "series".
        val type = if (launcherType == "tv") "series" else launcherType
        return LaunchIntentRoute.Title(type, launcherId)
    }
    if (!reminderChannelId.isNullOrBlank()) {
        return LaunchIntentRoute.Channel(reminderChannelId)
    }
    return null
}
