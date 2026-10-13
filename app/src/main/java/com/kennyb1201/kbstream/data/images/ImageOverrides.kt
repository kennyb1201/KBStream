package com.kennyb1201.kbstream.data.images

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The image a viewer chose for a title, where TMDB's own is not the one they
 * want (see [ImageOverrideEntity]).
 *
 * The write side is the Media row's long-press menu; the read side is every
 * surface that draws a title's own art. Those two are far apart - a card in a
 * rail is a composable several layers below the screen that could query a
 * database, and asking Room for a row per card per frame is not a thing to do -
 * so the table is mirrored into memory here and the surfaces read the mirror.
 * [paths] IS that mirror, a `key -> file_path` map published as a StateFlow: a
 * change republishes it and every surface drawing an overridden title
 * recomposes on the next frame, with no plumbing between them.
 *
 * The mirror is loaded once per profile ([ensureLoaded], called by whoever reads
 * it) and kept in step by the two writes the menu makes - [choose] and [reset] -
 * which publish the choice FIRST and persist it behind that. The order is
 * deliberate: the press happens on a menu that closes as it fires, and a viewer
 * who picks a still is owed the surface changing on the next frame rather than
 * after a database write they cannot see. Persisting on the store's own scope is
 * the other half of it - the caller's composition is gone by then - and a write
 * that fails is logged, leaving the session showing a choice that a restart
 * drops back to TMDB's art, which is the trade this buys.
 *
 * Profile-scoped: which backdrop a viewer picks is a property of the viewer, so
 * it lives in the same per-profile database as their watch history and is kept
 * out of a kids profile's copy (see [WatchHistoryDatabase.getInstanceScoped]).
 */
object ImageOverrides {

    private const val TAG = "IMAGE_OVERRIDES"

    /** A backdrop: a 16:9 still, chosen per surface. */
    const val TYPE_BACKDROP = "backdrop"

    /** A poster: 2:3 portrait artwork, chosen once for every surface. */
    const val TYPE_POSTER = "poster"

    /** The detail screen's hero. */
    const val SCREEN_DETAIL = "detail"

    /** The streams screen's backdrop. */
    const val SCREEN_STREAMS = "streams"

    /**
     * The pre-playback load splash, in every hand-off that paints one: the
     * "Finding sources" cover, the Continue Watching / Up Next deep-link cover,
     * and the frame the Player route paints before the player Activity lands
     * (see [com.kennyb1201.kbstream.ui.components.AutoPlayLoadSplash]).
     */
    const val SCREEN_PLAYER_SPLASH = "player_splash"

    /** The Home hero, when the title is the one being featured. */
    const val SCREEN_HOME_HERO = "home_hero"

    /** Landscape cards in rails (More Like This, Home rails, catalog grids). */
    const val SCREEN_LANDSCAPE_CARD = "landscape_card"

    /**
     * Every backdrop surface at once - the still a viewer wants everywhere,
     * rather than the same still chosen five times.
     *
     * It is a SURFACE like any other row in the table (the schema is unchanged),
     * and it sits at the END of the chain every backdrop lookup walks: a
     * surface's own choice wins, then this one, then TMDB's art (see
     * [backdropPathFor]). That is what makes it reach surfaces that have no
     * choice of their own at all - the actor page's header still, a screen that
     * never offered a row - without inventing a key per screen.
     */
    const val SCREEN_ALL = "all"

    /** The title's poster, everywhere a poster is drawn. */
    const val SCREEN_POSTER = "poster"

    /** The table's primary key: the title, the kind of art, and the surface. */
    fun key(tmdbId: Int, type: String, screen: String): String = "$tmdbId:$type:$screen"

    /**
     * Every surface [type] can be chosen for, in the order the menu offers
     * them.
     *
     * The one place the screens of a kind are listed, so "reset this title's
     * backdrops" cannot clear three of the four and leave the hero on an image
     * the viewer has just asked to be rid of.
     */
    fun screensFor(type: String): List<String> = when (type) {
        TYPE_BACKDROP -> listOf(
            SCREEN_DETAIL,
            SCREEN_STREAMS,
            SCREEN_PLAYER_SPLASH,
            SCREEN_HOME_HERO,
            SCREEN_LANDSCAPE_CARD,
            // Last, and here for a reason that is easy to lose: "Reset to
            // default" clears every surface this list names. A title whose ONLY
            // choice is the all-screens one would otherwise keep drawing the
            // still the viewer just asked to be rid of - on every screen at
            // once. It also makes the reset row appear for that choice, because
            // [hasOverride] reads the same list.
            SCREEN_ALL
        )
        TYPE_POSTER -> listOf(SCREEN_POSTER)
        else -> emptyList()
    }

    /**
     * The scope [ensureLoaded]'s read runs on.
     *
     * Process-lifetime and supervised, like the other stores' scopes: the
     * caller is a composable that may be gone by the time Room answers, and a
     * read launched on `rememberCoroutineScope` would be canceled with it -
     * which would leave the mirror empty exactly when a screen was waiting for
     * it (the same failure the library mirror's own scope documents).
     */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())

    /** `"${tmdbId}:${type}:${screen}"` -> the chosen `file_path`. */
    val paths: StateFlow<Map<String, String>> = _paths.asStateFlow()

    /** Bumped on every change, so a reader can re-read without the map. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** The scoped database file the mirror was last read for, so a switch re-reads. */
    @Volatile
    private var loadedFor: String? = null

    /**
     * Starts the mirror's read if it is not already loaded for the active
     * profile, and does nothing at all when it is.
     *
     * This is what the UI calls - it is synchronous and cheap, so a composable
     * can call it while composing - and it is deliberately keyed on the
     * PROFILE-SCOPED database file name rather than on a boolean: which image a
     * viewer chose is a property of the profile, so a profile switch has to
     * re-read, and every surface that draws art calls this.
     */
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        val name = WatchHistoryDatabase.activeFileName(app)
        synchronized(this) {
            if (loadedFor == name) return
            // Claimed before the read is launched: two screens composing in the
            // same frame must not both start the whole-table read.
            loadedFor = name
        }
        loadScope.launch { refresh(app) }
    }
    /**
     * Re-reads the whole table into the mirror.
     *
     * Cheap - the table holds one row per choice the viewer has made, not one
     * per title - and idempotent, so the screens that read overrides call it
     * when they compose rather than the app trying to keep a warm cache alive
     * across profile switches it cannot see.
     */
    suspend fun refresh(context: Context) {
        val result = runCatchingCancellable { dao(context).getAll() }
        val rows = result.getOrElse { error ->
            Log.w(TAG, "image overrides could not be read", error)
            return
        }
        _paths.value = rows.associate { row -> row.key to row.imagePath }
        _version.value++
    }

    /**
     * Makes [imagePath] this title's image for [screen] - the write behind a
     * menu row like "Set as Detail backdrop".
     *
     * Returns immediately with the choice published; the table is written
     * afterwards, on [loadScope]. The caller is the menu's own row press, so it
     * cannot wait for a write (and must not: see this file's header), and a
     * failure is only ever "the choice is gone after a restart", which the log
     * line is for.
     */
    fun choose(
        context: Context,
        tmdbId: Int,
        type: String,
        screen: String,
        imagePath: String
    ) {
        val row = ImageOverrideEntity(
            key = key(tmdbId, type, screen),
            tmdbId = tmdbId,
            type = type,
            screen = screen,
            imagePath = imagePath
        )
        _paths.value = _paths.value + (row.key to imagePath)
        _version.value++
        val app = context.applicationContext
        loadScope.launch { persistSet(app, row) }
    }

    /**
     * Forgets EVERY choice of [type] for this title - "Reset to default".
     *
     * All of them, not the one surface the viewer happened to be looking at:
     * a viewer who asks for the default art back is asking about the title, and
     * leaving three surfaces on a still they just rejected would read as the
     * reset not having worked.
     */
    fun reset(context: Context, tmdbId: Int, type: String) {
        val screens = screensFor(type)
        _paths.value = _paths.value - screens.map { screen -> key(tmdbId, type, screen) }.toSet()
        _version.value++
        val app = context.applicationContext
        loadScope.launch {
            screens.forEach { screen -> persistClear(app, tmdbId, type, screen) }
        }
    }

    private suspend fun persistSet(context: Context, row: ImageOverrideEntity) {
        runCatchingCancellable { dao(context).set(row) }.onFailure { error ->
            Log.w(TAG, "image override could not be saved", error)
        }
    }

    private suspend fun persistClear(
        context: Context,
        tmdbId: Int,
        type: String,
        screen: String
    ) {
        runCatchingCancellable { dao(context).clear(tmdbId, type, screen) }.onFailure { error ->
            Log.w(TAG, "image override could not be cleared", error)
        }
    }

    /**
     * Whether this title has ANY choice of [type] stored - the condition the
     * menu's "Reset to default" row is offered under, and read from the mirror
     * so the row appears without a query.
     */
    fun hasOverride(paths: Map<String, String>, tmdbId: Int?, type: String): Boolean =
        screensFor(type).any { screen -> pathFor(paths, tmdbId, type, screen) != null }

    /**
     * The chosen `file_path` for one surface, or null when the viewer has made
     * no choice - which is every title until they make one.
     *
     * Pure, so the rule can be pinned without a device.
     */
    fun pathFor(
        paths: Map<String, String>,
        tmdbId: Int?,
        type: String,
        screen: String
    ): String? {
        if (tmdbId == null) return null
        return paths[key(tmdbId, type, screen)]
    }

    /**
     * [pathFor] for a surface that holds an id rather than a number - the
     * spelling a rail, a catalog card or a route handed it.
     */
    fun pathFor(
        paths: Map<String, String>,
        rawId: String?,
        type: String,
        screen: String
    ): String? = pathFor(paths, tmdbIdOf(rawId), type, screen)

    /**
     * The backdrop [screen] should draw, walking the whole chain: the still
     * chosen FOR that surface first, then the one chosen for every screen
     * ([SCREEN_ALL]), then nothing - which every caller turns into TMDB's own
     * art (see [applied]).
     *
     * The order is the point. A surface with a choice of its own keeps it, so
     * setting five surfaces still works exactly as it did and "all screens" is
     * a fallback rather than an override of them. A surface with no row at all -
     * the actor page's header, anything that has never offered one - draws the
     * all-screens still instead of TMDB's, which is what "applies everywhere"
     * means for a screen nobody listed.
     *
     * Pure, so the chain is pinned without a device.
     */
    fun backdropPathFor(
        paths: Map<String, String>,
        tmdbId: Int?,
        screen: String
    ): String? = pathFor(paths, tmdbId, TYPE_BACKDROP, screen)
        ?: pathFor(paths, tmdbId, TYPE_BACKDROP, SCREEN_ALL)

    /** [backdropPathFor] for a surface that holds an id rather than a number. */
    fun backdropPathFor(
        paths: Map<String, String>,
        rawId: String?,
        screen: String
    ): String? = backdropPathFor(paths, tmdbIdOf(rawId), screen)

    /**
     * The TMDB id a surface's own id spelling refers to, or null when it names
     * something TMDB does not - so a card that has an id to look a choice up by
     * can find one whatever namespace its caller is in.
     *
     * The app runs two id namespaces (TMDB's "tmdb:603" and an add-on's
     * "tt0133093"), and a bare numeric id is the same spelling the choices are
     * written under ([ImageOverrideEntity.tmdbId]). An IMDB id is deliberately
     * NOT resolved: the bridge between the namespaces is a network lookup, and
     * a card is not the place to make one.
     */
    fun tmdbIdOf(raw: String?): Int? {
        val id = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bare = id.removePrefix("tmdb:").removePrefix("tmdb_")
        return bare.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * The path a surface should draw: the viewer's choice when there is one,
     * TMDB's own ([fallback]) otherwise.
     *
     * The fallback is what makes "Reset to default" mean anything - a cleared
     * choice is not a blank image, it is TMDB's art again.
     */
    fun applied(
        paths: Map<String, String>,
        tmdbId: Int?,
        type: String,
        screen: String,
        fallback: String?
    ): String? = pathFor(paths, tmdbId, type, screen) ?: fallback

    private fun dao(context: Context): ImageOverrideDao =
        WatchHistoryDatabase.getInstanceScoped(context).imageOverrideDao()
}
