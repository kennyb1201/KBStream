package com.kennyb1201.kbstream.ui.mobile

/**
 * The title identity of the Streams route that is on screen.
 *
 * The download feature needs one fact the phone's picker is not handed: the id
 * the player will carry as `parent_id` for this title, which is what makes a
 * downloaded play file the same watch-history row a streamed one does. The route
 * owns it (MainActivity's `Screen.Streams.parentId`), and the phone's picker
 * composable receives only the target's own contentType/streamId - and it cannot
 * be handed the id as a new parameter, because its call site lives in the shared
 * TV/phone dispatch file that this feature must leave byte-identical (spec §8).
 *
 * So the route publishes it here instead, and only on a phone: on a television
 * nothing writes this at all, which is what keeps the TV path provably untouched
 * rather than merely unread.
 *
 * Keyed on the picker's own navigation key (`streamNavigationKey`), so a stale
 * entry can never be applied to a different target - the reader asks for the key
 * it is showing and gets nothing when it does not match.
 */
internal object CurrentStreamsRoute {

    data class Route(
        val key: String,
        val parentId: String,
        val parentType: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val clearLogoUrl: String?,
        val overview: String?
    )

    @Volatile
    private var route: Route? = null

    fun remember(route: Route) {
        this.route = route
    }

    /** The remembered route, when it is the one being asked about. */
    fun current(key: String?): Route? {
        if (key.isNullOrBlank()) return null
        return route?.takeIf { it.key == key }
    }
}
