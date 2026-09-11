package com.kennyb1201.kbstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.actor.ActorScreen
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.collection.CollectionScreen
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.detail.DetailScreen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.HomeScreen
import com.kennyb1201.kbstream.ui.iptv.GuideScreen
import com.kennyb1201.kbstream.ui.iptv.IptvViewModel
import com.kennyb1201.kbstream.ui.onboarding.OnboardingPrefs
import com.kennyb1201.kbstream.ui.onboarding.OnboardingScreen
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.player.NextEpisodeResult
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.player.PlayerCastMember
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.kennyb1201.kbstream.ui.nuvio.NuvioFolderScreen
import com.kennyb1201.kbstream.ui.settings.SettingsScreen
import com.kennyb1201.kbstream.ui.search.SearchScreen
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.streams.StreamsScreen
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.studio.StudioScreen
import com.kennyb1201.kbstream.ui.tag.TagScreen
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch

sealed class Screen {

    object Home : Screen()

    object Addons : Screen()

    object Search : Screen()

    object Simkl : Screen()

    object Guide : Screen()

    object Settings : Screen()

    data class Detail(
        val type: String,
        val id: String,
        val pendingTarget: StreamsTarget? = null,
        val itemPoster: String? = null,
        val returnTo: Screen = Home,
        val itemBackdrop: String? = null,
        val itemClearLogo: String? = null,
        val itemOverview: String? = null
    ) : Screen()

    data class Actor(
        val personId: Int,
        val returnTo: Screen = Home
    ) : Screen()

    data class Studio(
        val id: Int,
        val name: String,
        val isNetwork: Boolean,
        val returnTo: Screen = Home
    ) : Screen()

    data class Tag(
        val id: Int,
        val name: String,
        val isKeyword: Boolean,
        val mediaType: String,
        val returnTo: Screen = Home
    ) : Screen()

    data class Collection(
        val id: Int,
        val name: String,
        val returnTo: Screen = Home
    ) : Screen()

    /** One imported Nuvio collection folder (its own layout mode). */
    data class NuvioFolder(
        val folderId: String,
        val returnTo: Screen = Home
    ) : Screen()

    data class Streams(
        val target: StreamsTarget,
        val parentId: String,
        val returnTo: Screen = Home,
        val parentType: String,
        val itemPoster: String?,
        val backdropUrl: String? = null,
        val clearLogoUrl: String? = null,
        val overview: String? = null,
        val cast: List<TmdbCastMember> = emptyList()
    ) : Screen()

    data class Player(
        val url: String,
        val audioUrl: String? = null,
        val parentId: String,
        val parentType: String,
        val season: Int?,
        val episode: Int?,
        val episodeStreamId: String?,
        val episodeTitle: String? = null,
        val itemName: String,
        val itemPoster: String?,
        val clearLogoUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val cast: List<PlayerCastMember> = emptyList(),
        val startPositionMs: Long,
        val fromActorReturn: Boolean = false,
        val returnTo: Screen = Home,
        val sources: List<Stream> = emptyList(),
        val streamHeaders: Map<String, String> = emptyMap(),
        val totalEpisodesInSeason: Int? = null,
        val runtimeMinutes: Int? = null,
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap()
    ) : Screen()
}

// ---------------------------------------------------------------------------
// Screen persistence (rememberSaveable).
//
// The Screen sealed class is not directly saveable, so it is encoded to/from
// JSON. Only the navigation-essential fields are kept; returnTo chains are
// capped at MAX_RETURN_DEPTH and anything unparseable restores Home, so a
// restored screen always has a valid back destination and can never crash the
// app on restore. The Player screen runs in its own activity and is mapped to
// the screen underneath it (never relaunch a dead playback URL).
// ---------------------------------------------------------------------------

private const val SCREEN_TYPE_KEY = "type"
private const val MAX_RETURN_DEPTH = 2

private object ScreenSaver : Saver<Screen, String> {
    override fun SaverScope.save(value: Screen): String =
        encodeScreen(
            if (value is Screen.Player) value.returnTo else value
        ).toString()

    override fun restore(value: String): Screen? =
        decodeScreen(
            runCatching { JSONObject(value) }.getOrNull()
        )
}

private fun encodeScreen(
    screen: Screen,
    depth: Int = 0
): JSONObject = JSONObject().apply {
    put(SCREEN_TYPE_KEY, screen.typeName())

    when (screen) {
        is Screen.Detail -> {
            put("type", screen.type)
            put("id", screen.id)
            screen.pendingTarget?.let { put("pendingTarget", encodeTarget(it)) }
            screen.itemPoster?.let { put("itemPoster", it) }
            screen.itemBackdrop?.let { put("itemBackdrop", it) }
            screen.itemClearLogo?.let { put("itemClearLogo", it) }
            screen.itemOverview?.let { put("itemOverview", it) }
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Actor -> {
            put("personId", screen.personId)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Studio -> {
            put("id", screen.id)
            put("name", screen.name)
            put("isNetwork", screen.isNetwork)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Tag -> {
            put("id", screen.id)
            put("name", screen.name)
            put("isKeyword", screen.isKeyword)
            put("mediaType", screen.mediaType)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Collection -> {
            put("id", screen.id)
            put("name", screen.name)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.NuvioFolder -> {
            put("folderId", screen.folderId)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Streams -> {
            put("target", encodeTarget(screen.target))
            put("parentId", screen.parentId)
            put("parentType", screen.parentType)
            screen.itemPoster?.let { put("itemPoster", it) }
            screen.backdropUrl?.let { put("backdropUrl", it) }
            screen.clearLogoUrl?.let { put("clearLogoUrl", it) }
            screen.overview?.let { put("overview", it) }
            put("cast", JSONArray().apply {
                screen.cast.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("id", member.id)
                            put("name", member.name)
                            member.character?.let { put("character", it) }
                            member.profilePath?.let { put("profilePath", it) }
                        }
                    )
                }
            })
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Player -> Unit // mapped to returnTo by the saver; never encoded directly
        else -> Unit // plain objects carry no payload
    }
}

private fun decodeScreen(
    json: JSONObject?,
    depth: Int = 0
): Screen {
    if (json == null || depth > MAX_RETURN_DEPTH) return Screen.Home
    return try {
        when (json.optString(SCREEN_TYPE_KEY)) {
            "home" -> Screen.Home
            "addons" -> Screen.Addons
            "search" -> Screen.Search
            "simkl" -> Screen.Simkl
            "guide" -> Screen.Guide
            "settings" -> Screen.Settings
            "detail" -> Screen.Detail(
                type = json.optString("type", "movie"),
                id = json.optString("id"),
                pendingTarget = json.optJSONObject("pendingTarget")
                    ?.let { decodeTarget(it) },
                itemPoster = json.optNullableString("itemPoster"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1),
                itemBackdrop = json.optNullableString("itemBackdrop"),
                itemClearLogo = json.optNullableString("itemClearLogo"),
                itemOverview = json.optNullableString("itemOverview")
            )
            "actor" -> Screen.Actor(
                personId = json.optInt("personId"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "studio" -> Screen.Studio(
                id = json.optInt("id"),
                name = json.optString("name"),
                isNetwork = json.optBoolean("isNetwork"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "tag" -> Screen.Tag(
                id = json.optInt("id"),
                name = json.optString("name"),
                isKeyword = json.optBoolean("isKeyword"),
                mediaType = json.optString("mediaType", "movie"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "collection" -> Screen.Collection(
                id = json.optInt("id"),
                name = json.optString("name"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "nuvioFolder" -> Screen.NuvioFolder(
                folderId = json.optString("folderId"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            // Legacy saved state from when the manager was its own screen.
            "nuvioManager" -> Screen.Addons
            "streams" -> {
                val target = json.optJSONObject("target")
                    ?.let { decodeTarget(it) }
                    ?: return Screen.Home
                Screen.Streams(
                    target = target,
                    parentId = json.optString("parentId"),
                    returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1),
                    parentType = json.optString("parentType", "movie"),
                    itemPoster = json.optNullableString("itemPoster"),
                    backdropUrl = json.optNullableString("backdropUrl"),
                    clearLogoUrl = json.optNullableString("clearLogoUrl"),
                    overview = json.optNullableString("overview"),
                    cast = decodeCast(json.optJSONArray("cast"))
                )
            }
            else -> Screen.Home
        }
    } catch (t: Throwable) {
        Screen.Home
    }
}

private fun encodeTarget(target: StreamsTarget): JSONObject = JSONObject().apply {
    put("contentType", target.contentType)
    put("streamId", target.streamId)
    put("title", target.title)
    put("displayName", target.displayName)
    target.season?.let { put("season", it) }
    target.episode?.let { put("episode", it) }
    put("resumePositionMs", target.resumePositionMs)
    target.totalEpisodesInSeason?.let { put("totalEpisodesInSeason", it) }
    target.runtimeMinutes?.let { put("runtimeMinutes", it) }
}

private fun decodeTarget(json: JSONObject): StreamsTarget? = try {
    StreamsTarget(
        contentType = json.optString("contentType", "movie"),
        streamId = json.optString("streamId"),
        title = json.optString("title"),
        displayName = json.optString("displayName"),
        season = json.optNullableInt("season"),
        episode = json.optNullableInt("episode"),
        resumePositionMs = json.optLong("resumePositionMs", 0L),
        totalEpisodesInSeason = json.optNullableInt("totalEpisodesInSeason"),
        runtimeMinutes = json.optNullableInt("runtimeMinutes")
    )
} catch (t: Throwable) {
    null
}

private fun decodeCast(array: JSONArray?): List<TmdbCastMember> {
    if (array == null) return emptyList()
    return try {
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    TmdbCastMember(
                        id = obj.optInt("id"),
                        name = obj.optString("name"),
                        character = obj.optNullableString("character"),
                        profilePath = obj.optNullableString("profilePath")
                    )
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun Screen.typeName(): String = when (this) {
    is Screen.Home -> "home"
    is Screen.Addons -> "addons"
    is Screen.Search -> "search"
    is Screen.Simkl -> "simkl"
    is Screen.Guide -> "guide"
    is Screen.Settings -> "settings"
    is Screen.Detail -> "detail"
    is Screen.Actor -> "actor"
    is Screen.Studio -> "studio"
    is Screen.Tag -> "tag"
    is Screen.Collection -> "collection"
    is Screen.NuvioFolder -> "nuvioFolder"
    is Screen.Streams -> "streams"
    is Screen.Player -> "player"
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun streamNavigationKey(contentType: String, streamId: String): String {
    val normalizedType = when (contentType.lowercase()) {
        "tv", "show", "series" -> "series"
        else -> contentType.lowercase()
    }
    return "$normalizedType:$streamId"
}

// A player can be reopened from a picker after source switching. Never keep a
// picker or player in the back chain, and consume one-shot detail targets before
// showing that detail screen again. The depth guard also protects restored state
// from malformed/cyclic return chains.
private fun stableBackDestination(screen: Screen, depth: Int = 0): Screen {
    if (depth >= MAX_RETURN_DEPTH + 2) return Screen.Home
    return when (screen) {
        is Screen.Streams -> stableBackDestination(screen.returnTo, depth + 1)
        is Screen.Player -> stableBackDestination(screen.returnTo, depth + 1)
        is Screen.Detail -> screen.copy(pendingTarget = null)
        else -> screen
    }
}

private fun restoredStreamTitle(
    displayName: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?
): String {
    val episodeMarker = if (season != null && episode != null) {
        "$displayName S$season E$episode"
    } else {
        displayName
    }

    return episodeTitle
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "$episodeMarker • $it" }
        ?: episodeMarker
}

// A playback request whose sources are resolved in the background when
// autoselect is on: the current screen stays visible (with a brief "Finding
// sources" overlay) and playback starts straight from the player. The streams
// picker is only reached as a fallback when nothing playable resolves.
private data class PendingPlay(
    val target: StreamsTarget,
    val parentId: String,
    val parentType: String,
    val itemPoster: String?,
    val backdropUrl: String?,
    val clearLogoUrl: String?,
    val overview: String?,
    val cast: List<TmdbCastMember>,
    val returnTo: Screen,
    val totalEpisodesInSeason: Int? = null
) {
    val streamKey: String
        get() = streamNavigationKey(target.contentType, target.streamId)

    fun toPlayerScreen(stream: Stream, allSources: List<Stream>): Screen.Player {
        return Screen.Player(
            url = stream.url.orEmpty(),
            audioUrl = stream.audioUrl,
            parentId = parentId,
            parentType = parentType,
            season = target.season,
            episode = target.episode,
            episodeStreamId = target.streamId,
            episodeTitle = target.title
                .substringAfterLast("•", "")
                .trim()
                .takeIf { it.isNotBlank() && it != target.title },
            itemName = target.displayName,
            itemPoster = itemPoster,
            clearLogoUrl = clearLogoUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            cast = cast.map { member ->
                PlayerCastMember(
                    id = member.id,
                    name = member.name,
                    character = member.character,
                    profilePath = member.profilePath?.let {
                        TmdbRepository.PROFILE_BASE + it
                    }
                )
            },
            startPositionMs = target.resumePositionMs,
            returnTo = returnTo,
            sources = allSources,
            totalEpisodesInSeason = totalEpisodesInSeason,
            runtimeMinutes = target.runtimeMinutes,
            drmLicenseUrl = stream.drm?.licenseUrl,
            drmHeaders = stream.drm?.headers.orEmpty()
        )
    }

    fun toStreamsScreen(): Screen.Streams {
        return Screen.Streams(
            target = target,
            parentId = parentId,
            returnTo = returnTo,
            parentType = parentType,
            itemPoster = itemPoster,
            backdropUrl = backdropUrl,
            clearLogoUrl = clearLogoUrl,
            overview = overview,
            cast = cast
        )
    }
}

// Mirrors the player's clearlogo_pulse animation (1.0 -> 1.08 scale, 0.7 -> 1.0
// alpha, 1200 ms, reverse, infinite) applied to the clear logo.
@Composable
private fun PulsingClearLogo(
    url: String,
    contentDescription: String
) {
    val pulse = rememberInfiniteTransition(label = "clearLogoPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "clearLogoScale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "clearLogoAlpha"
    )
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .width(240.dp)
            .height(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        setContent {
            KBStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AppRoot()
                    }
                }
            }
    }
}

@Composable
fun AppRoot() {

    // Persisted across process death: Android can kill a backgrounded TV app,
    // and without a Saver the app would come back on Home instead of the
    // detail/streams screen the user was on. The Saver encodes the current
    // Screen as JSON; anything unparseable falls back to Home.
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }

    // Guard against the autoplay loop: StreamsScreen auto-selects the top
    // stream once, which navigates to the player. When the user backs OUT of
    // the player, the return-to-streams navigation re-runs the streams loader,
    // whose autoplay would otherwise relaunch the player immediately — trapping
    // the user in player -> back -> loading -> player. Remember the target that
    // has already auto-played and skip autoplay for it from then on (a manual
    // source pick still works; a fresh target still autoplays).
    var autoPlayedStreamKeys by rememberSaveable {
        mutableStateOf(listOf<String>())
    }

    val context = LocalContext.current

    // First-run onboarding: shown over the app until the user taps
    // "Start Browsing". The flag lives in SharedPreferences so it survives
    // restarts and process death (and restoring a saved screen can't skip it).
    var onboardingComplete by rememberSaveable {
        mutableStateOf(OnboardingPrefs.isComplete(context))
    }

    val tmdbRepository = remember {
        TmdbRepository(context)
    }

    val iptvViewModel: IptvViewModel = viewModel()

    val streamsViewModel: StreamsViewModel = viewModel()

    // Activity-scoped search state, referenced by the BackHandler so exiting
    // Search commits the query to history and clears the session.
    val searchViewModel: SearchViewModel = viewModel()

    // Hoisted here (not inside SearchScreen) so the scroll position survives
    // Search -> Detail -> Back: the when-branch swap decomposes SearchScreen,
    // which would otherwise discard a locally-remembered LazyListState. Back
    // from a drill-down lands on the same title; exiting Search itself resets
    // it (in the BackHandler below) so a fresh entry starts at the top.
    val searchListState = rememberLazyListState()
    val searchListScope = rememberCoroutineScope()

    // When autoselect is on, sources resolve here in the background while the
    // current screen stays visible (a brief "Finding sources" overlay), then
    // playback starts straight from the player — the streams picker never
    // flashes before playback. The picker is only shown as a fallback when
    // nothing playable resolves.
    var pendingAutoPlay by remember {
        mutableStateOf<PendingPlay?>(null)
    }

    LaunchedEffect(pendingAutoPlay) {
        val pending = pendingAutoPlay ?: return@LaunchedEffect
        // Mark the target as auto-played up front so the picker can never
        // auto-select it again if we fall through to it (no playable source).
        autoPlayedStreamKeys =
            (autoPlayedStreamKeys + pending.streamKey).distinct()
        val streams = streamsViewModel.resolve(
            pending.target.contentType,
            pending.target.streamId
        )
        val top = streams.firstOrNull { !it.url.isNullOrBlank() }
        pendingAutoPlay = null
        screen = if (top != null) {
            pending.toPlayerScreen(top, streams)
        } else {
            pending.toStreamsScreen()
        }
    }

    // Launcher deep links (TV Watch Next cards) open the title's detail
    // screen, and on startup the TV launcher Continue Watching rail is
    // reconciled with the in-app watch history (self-healing, cheap).
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        val launcherType = intent?.getStringExtra(TvLauncherPublisher.EXTRA_TYPE)
        val launcherId = intent?.getStringExtra(TvLauncherPublisher.EXTRA_ID)
        if (!launcherType.isNullOrBlank() && !launcherId.isNullOrBlank()) {
            screen = Screen.Detail(
                if (launcherType == "tv") "series" else launcherType,
                launcherId,
                returnTo = Screen.Home
            )
        }

        // Safety net: if the app was killed while a next-episode handoff was
        // pending (process death between the player finishing and this
        // activity's result callback ever running), resurface the next episode
        // instead of silently landing on Home. The pending Detail.target routes
        // through the same one-shot Continue Watching autoplay path. Skipped
        // when a launcher deep link is present - that launch intent wins.
        if (launcherType.isNullOrBlank() && launcherId.isNullOrBlank()) {
            NextEpisodeResult.restoreIfDropped(context)?.let { pending ->
                // PendingNext carries the Stremio stream id ("tt123:S:E"), so
                // the show id is its double-colon prefix; next episodes only
                // exist for series.
                val showId = pending.streamId
                    .substringBeforeLast(':')
                    .substringBeforeLast(':')
                screen = Screen.Detail(
                    type = "series",
                    id = showId,
                    pendingTarget = StreamsTarget(
                        contentType = "series",
                        streamId = pending.streamId,
                        title = pending.title,
                        // Cosmetic placeholder; DetailScreen loads the real
                        // display name from the meta source.
                        displayName = showId,
                        season = pending.season,
                        episode = pending.episode,
                        resumePositionMs = 0L
                    ),
                    returnTo = Screen.Home
                )
                return@LaunchedEffect
            }
        }

        val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()
        val entries = runCatching { dao.getAll() }.getOrDefault(emptyList())
        TvLauncherPublisher.sync(context, entries)
    }

    BackHandler(
        enabled = screen != Screen.Home || pendingAutoPlay != null
    ) {
        // Backing out while "Finding sources" is up cancels the in-flight
        // resolution (the LaunchedEffect above is keyed on pendingAutoPlay) and
        // navigates back from the screen underneath.
        if (pendingAutoPlay != null) {
            pendingAutoPlay = null
        }
        screen = when (val current = screen) {

            is Screen.Addons ->
                Screen.Settings

            is Screen.Player ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    stableBackDestination(current.returnTo)
                }

            is Screen.Streams ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    stableBackDestination(current.returnTo)
                }

            is Screen.Actor ->
                current.returnTo

            is Screen.Studio ->
                current.returnTo

            is Screen.Tag ->
                current.returnTo

            is Screen.Collection ->
                current.returnTo

            is Screen.NuvioFolder ->
                current.returnTo

            // Detail carries the screen it was opened from (Search, Home,
            // an actor page, ...), so Back returns there instead of always
            // bouncing to Home — clicking a Search result and pressing Back
            // lands back on that result rail.
            is Screen.Detail ->
                stableBackDestination(current.returnTo)

            is Screen.Search -> {
                // Leaving Search itself: commit the query to recent-search
                // history and clear the session state, so re-entering Search
                // starts fresh with the query one chip away. Drill-downs
                // (Detail/Actor/...) skip this — Back must restore results.
                // The hoisted list state resets too: re-entering Search
                // re-composes with a fresh top-of-list view.
                searchViewModel.exitSearch()
                searchListScope.launch { searchListState.scrollToItem(0) }
                Screen.Home
            }

            else ->
                Screen.Home
        }
    }

    // Onboarding stands in for Home until the user finishes it — other
    // screens (Add-ons, Live TV, Simkl) render normally so the setup cards
    // genuinely hand off, and backing out to Home brings the guide back
    // until "Start Browsing" is tapped.
    if (!onboardingComplete && screen is Screen.Home) {
        OnboardingScreen(
            onOpenAddons = { screen = Screen.Addons },
            onOpenSimkl = { screen = Screen.Simkl },
            onOpenGuide = { screen = Screen.Guide },
            onFinish = {
                OnboardingPrefs.setComplete(context, true)
                onboardingComplete = true
                screen = Screen.Home
            }
        )
        return
    }

    when (val current = screen) {

        is Screen.Home -> {

            HomeScreen(
                onItemClick = { meta: MetaPreview ->
                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        returnTo = Screen.Home
                    )
                },

                onOpenDetailTarget = {
                        meta,
                        target,
                        poster ->

                    // Continue Watching / Up Next items open the detail
                    // screen with a deep-linked target; DetailScreen
                    // auto-plays it once metadata is ready so the stream
                    // screen still gets the rich backdrop/overview/cast.
                    screen = Screen.Detail(
                        type = meta.type,
                        id = meta.id,
                        pendingTarget = target,
                        itemPoster = poster ?: meta.poster,
                        returnTo = Screen.Home,
                        itemBackdrop = meta.background,
                        itemClearLogo = meta.logo,
                        itemOverview = meta.description
                    )
                },

                // Long-press "Play Manually" / "Play from Beginning" from
                // Continue Watching: skip the detail screen and go straight
                // to the streams picker for the same target. The target keeps
                // its resumePositionMs (or 0 for "from Beginning"), so
                // playback still resumes saved progress unless a fresh start
                // was requested.
                onOpenStreams = {
                        meta,
                        target,
                        poster ->

                    // Home's "Play Manually" / "Play from Beginning" always mean
                    // a manual source pick — remember the target so the streams
                    // picker never auto-selects it, even with auto-select on.
                    val manualKey = "${meta.type}:${target.streamId}"
                    autoPlayedStreamKeys =
                        (autoPlayedStreamKeys + manualKey).distinct()

                    // Home's manual action already opens the picker directly;
                    // consume any shared marker so it cannot affect a later
                    // detail-screen Play request.
                    ManualSourceSelection.requested = false

                    screen = Screen.Streams(
                        target = target,
                        parentId = meta.id,
                        returnTo = Screen.Home,
                        parentType = meta.type,
                        itemPoster = poster ?: meta.poster,
                        backdropUrl = meta.background,
                        clearLogoUrl = meta.logo,
                        overview = meta.description
                    )
                },

                onSearch = {
                    screen = Screen.Search
                },

                onOpenGuide = {
                    screen = Screen.Guide
                },

                onOpenSettings = {
                    screen = Screen.Settings
                },

                onOpenNuvioFolder = { folderId ->
                    screen = Screen.NuvioFolder(
                        folderId = folderId,
                        returnTo = Screen.Home
                    )
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                onBack = { screen = Screen.Home },
                onOpenAddons = { screen = Screen.Addons },
                onOpenSimkl = { screen = Screen.Simkl }
            )
        }

        is Screen.NuvioFolder -> {
            NuvioFolderScreen(
                folderId = current.folderId,
                onBack = { screen = stableBackDestination(current.returnTo) },
                onItemClick = { type, imdbId, poster, backdrop, title ->
                    screen = Screen.Detail(
                        type = type,
                        id = imdbId,
                        itemPoster = poster,
                        itemBackdrop = backdrop,
                        itemOverview = null,
                        returnTo = current
                    )
                }
            )
        }

        is Screen.Addons -> {

            AddonsScreen(
                onBack = {
                    screen = Screen.Home
                }
            )
        }

        is Screen.Search -> {

            SearchScreen(
                // Share the activity-scoped ViewModel the BackHandler uses,
                // so exit-commit and state clearing operate on one instance.
                viewModel = searchViewModel,
                // Hoisted list state: Back from Detail/Actor/... restores the
                // scroll position at the same title.
                listState = searchListState,
                onItemClick = {
                        meta: MetaPreview ->

                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        returnTo = Screen.Search
                    )
                },

                onPersonClick = { person ->
                    screen = Screen.Actor(
                        person.id,
                        Screen.Search
                    )
                },

                onStudioClick = { studio ->
                    screen = Screen.Studio(
                        studio.id,
                        studio.name,
                        false,
                        Screen.Search
                    )
                },

                onCollectionClick = { collection ->
                    screen = Screen.Collection(
                        collection.id,
                        collection.name,
                        Screen.Search
                    )
                }
            )
        }

        is Screen.Simkl -> {

            SimklConnectScreen(
                onBackToHome = {
                    screen = Screen.Settings
                }
            )
        }

        is Screen.Guide -> {

            GuideScreen(
                viewModel = iptvViewModel,
                defaultPlaylistUrl = "",
                defaultEpgUrl = "",
                defaultPlaylistName = "Live TV",

                onPlayChannel = {
                        channelWithEpg ->

                    val channel =
                        channelWithEpg.channel

                    val channelName =
                        channel.displayName
                            .ifBlank {
                                "Live Channel"
                            }

                    val channelId =
                        channel.id
                            .ifBlank {
                                channel.streamUrl
                            }

                    val poster =
                        channel.logoUrl
                            ?: channelWithEpg
                                .epgChannel
                                ?.iconUrl

                    val directSource =
                        Stream(
                            name = channelName,
                            title = channelName,
                            url = channel.streamUrl
                        )

                    screen = Screen.Player(
                        url = channel.streamUrl,
                        audioUrl = directSource.audioUrl,
                        parentId = channelId,
                        parentType = "channel",
                        season = null,
                        episode = null,
                        episodeStreamId = channel.id,
                        itemName = channelName,
                        itemPoster = poster,
                        startPositionMs = 0L,
                        sources = listOf(
                            directSource
                        ),
                        streamHeaders = channel.headers,
                        returnTo = Screen.Guide
                    )
                }
            )
        }

        is Screen.Detail -> {

            DetailScreen(
                type = current.type,
                id = current.id,
                initialTarget = current.pendingTarget,
                initialPoster = current.itemPoster,
                initialBackdrop = current.itemBackdrop,
                initialClearLogo = current.itemClearLogo,
                initialOverview = current.itemOverview,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                },

                onNavigateActor = { personId ->

                    screen = Screen.Actor(
                        personId,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateStudio = {
                        id,
                        name,
                        isNetwork ->

                    screen = Screen.Studio(
                        id,
                        name,
                        isNetwork,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateTag = {
                        id,
                        name,
                        isKeyword,
                        type ->

                    screen = Screen.Tag(
                        id,
                        name,
                        isKeyword,
                        type,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateStreams = {
                        target,
                        parentId,
                        parentType,
                        poster,
                        backdropUrl,
                        clearLogoUrl,
                        overview,
                        cast ->

                    val manualSourceSelection =
                        ManualSourceSelection.requested.also {
                            ManualSourceSelection.requested = false
                        }
                    // A pending target is a one-shot Continue Watching/up-next
                    // request. Do not keep it in the back destination: returning
                    // to that Detail screen would fire its LaunchedEffect again,
                    // reopen Streams, and make Back appear to flash without
                    // leaving the picker.
                    val stableReturnTo = current.copy(pendingTarget = null)

                    if (
                        !manualSourceSelection &&
                        AppPreferences.getAutoSelectStream(context)
                    ) {
                        // Autoselect is on: resolve sources in the background
                        // (this screen stays visible under a brief "Finding
                        // sources" overlay) and jump straight into the player —
                        // the streams picker only appears if nothing playable
                        // resolves.
                        pendingAutoPlay = PendingPlay(
                            target = target,
                            parentId = parentId,
                            parentType = parentType,
                            itemPoster = poster,
                            backdropUrl = backdropUrl,
                            clearLogoUrl = clearLogoUrl,
                            overview = overview,
                            cast = cast,
                            returnTo = stableReturnTo
                        )
                    } else {
                        screen = Screen.Streams(
                            target = target,
                            parentId = parentId,
                            returnTo = stableReturnTo,
                            parentType = parentType,
                            itemPoster = poster,
                            backdropUrl = backdropUrl,
                            clearLogoUrl = clearLogoUrl,
                            overview = overview,
                            cast = cast
                        )
                    }
                }
            )
        }

        is Screen.Actor -> {

            ActorScreen(
                actorId = current.personId,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Studio -> {

            StudioScreen(
                current.id,
                current.name,
                current.isNetwork,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Tag -> {

            TagScreen(
                current.id,
                current.name,
                current.isKeyword,
                current.mediaType,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Collection -> {

            CollectionScreen(
                current.id,
                current.name,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Streams -> {

            val streamsViewModel: StreamsViewModel =
                viewModel()

            LaunchedEffect(
                current.target.contentType,
                current.target.streamId
            ) {
                val targetKey = "${current.target.contentType}:${current.target.streamId}"
                // Skip a redundant re-fetch when this target was just resolved
                // in the background (autoselect path) — its result is already
                // in the ViewModel, so the picker appears instantly without a
                // loading flash.
                if (streamsViewModel.loadedKey.value != targetKey) {
                    streamsViewModel.load(
                        current.target.contentType,
                        current.target.streamId
                    )
                }
            }

            val streamKey = streamNavigationKey(
                current.target.contentType,
                current.target.streamId
            )

            StreamsScreen(
                title = current.target.title,
                displayName = current.target.displayName,
                season = current.target.season,
                episode = current.target.episode,
                runtimeMinutes = current.target.runtimeMinutes,
                backdropUrl = current.backdropUrl,
                clearLogoUrl = current.clearLogoUrl,
                suppressAutoSelect =
                    streamKey in autoPlayedStreamKeys,

                onStreamSelected = {
                        stream,
                        allSources ->

                    val streamUrl =
                        stream.url.orEmpty()

                    if (streamUrl.isNotBlank()) {

                        screen = Screen.Player(
                            url = streamUrl,
                            audioUrl = stream.audioUrl,
                            parentId = current.parentId,
                            parentType = current.parentType,
                            season = current.target.season,
                            episode = current.target.episode,
                            episodeStreamId =
                                current.target.streamId,
                            episodeTitle = current.target.title
                                .substringAfterLast("•", "")
                                .trim()
                                .takeIf { it.isNotBlank() && it != current.target.title },
                            itemName =
                                current.target.displayName,
                            itemPoster = current.itemPoster,
                            clearLogoUrl = current.clearLogoUrl,
                            backdropUrl = current.backdropUrl,
                            overview = current.overview,
                            cast = current.cast.map { member ->
                                PlayerCastMember(
                                    id = member.id,
                                    name = member.name,
                                    character = member.character,
                                    profilePath =
                                        member.profilePath?.let {
                                            TmdbRepository.PROFILE_BASE + it
                                        }
                                )
                            },
                            startPositionMs =
                                current.target.resumePositionMs,
                            returnTo = stableBackDestination(current.returnTo),
                            sources = allSources,
                            totalEpisodesInSeason =
                                current.target.totalEpisodesInSeason,
                            runtimeMinutes =
                                current.target.runtimeMinutes,
                            drmLicenseUrl = stream.drm?.licenseUrl,
                            drmHeaders = stream.drm?.headers.orEmpty()
                        )
                    }
                },

                viewModel = streamsViewModel
            )
        }

        is Screen.Player -> {
            val context = LocalContext.current
            val playerResultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                // Check shared state first (more reliable than activity results).
                // consumePersisted falls back to the SharedPreferences copy the
                // player wrote before finishing, so the handoff also survives the
                // OS recreating this activity (and even process death) while the
                // player was up. Previously a recreate lost the in-memory handoff,
                // the callback fell through to the normal-exit branch, and the
                // user was dumped on the restored screen - which felt like
                // "Next Episode sends me Home". The stale Screen.Player in `screen`
                // here is scratch data the new navigation overwrites wholesale.
                val next = NextEpisodeResult.consumePersisted(context)
                if (next != null) {
                    val nextTarget = StreamsTarget(
                        contentType = current.parentType,
                        streamId = next.streamId,
                        title = next.title,
                        displayName = current.itemName,
                        season = next.season,
                        episode = next.episode,
                        resumePositionMs = 0L,
                        totalEpisodesInSeason = current.totalEpisodesInSeason,
                        runtimeMinutes = next.runtimeMinutes
                    )
                    val nextCast = current.cast.map { member ->
                        TmdbCastMember(
                            id = member.id,
                            name = member.name,
                            character = member.character,
                            profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                        )
                    }
                    if (AppPreferences.getAutoSelectStream(context)) {
                        pendingAutoPlay = PendingPlay(
                            target = nextTarget,
                            parentId = current.parentId,
                            parentType = current.parentType,
                            itemPoster = current.itemPoster,
                            backdropUrl = current.backdropUrl,
                            clearLogoUrl = current.clearLogoUrl,
                            overview = current.overview,
                            cast = nextCast,
                            returnTo = current.returnTo,
                            totalEpisodesInSeason = current.totalEpisodesInSeason
                        )
                    } else {
                        screen = Screen.Streams(
                            target = nextTarget,
                            parentId = current.parentId,
                            returnTo = stableBackDestination(current.returnTo),
                            parentType = current.parentType,
                            itemPoster = current.itemPoster,
                            backdropUrl = current.backdropUrl,
                            clearLogoUrl = current.clearLogoUrl,
                            overview = current.overview,
                            cast = nextCast
                        )
                    }
                } else {
                    val data = result.data
                    val action = data?.getStringExtra("player_result_action")
                    when (action) {
                        "next_episode" -> {
                            val nextEpisode = data.getIntExtra("next_episode", -1).takeIf { it >= 0 }
                            val nextSeason = data.getIntExtra("next_season", -1).takeIf { it >= 0 }
                            val nextTitle = data.getStringExtra("next_title")
                            val nextStreamId = data.getStringExtra("next_stream_id")
                            val nextTarget = StreamsTarget(
                                contentType = current.parentType,
                                streamId = nextStreamId.orEmpty(),
                                title = nextTitle.orEmpty(),
                                displayName = current.itemName,
                                season = nextSeason,
                                episode = nextEpisode,
                                resumePositionMs = 0L,
                                totalEpisodesInSeason = current.totalEpisodesInSeason
                            )
                            val nextCast = current.cast.map { member ->
                                TmdbCastMember(
                                    id = member.id,
                                    name = member.name,
                                    character = member.character,
                                    profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                                )
                            }
                            if (AppPreferences.getAutoSelectStream(context)) {
                                pendingAutoPlay = PendingPlay(
                                    target = nextTarget,
                                    parentId = current.parentId,
                                    parentType = current.parentType,
                                    itemPoster = current.itemPoster,
                                    backdropUrl = current.backdropUrl,
                                    clearLogoUrl = current.clearLogoUrl,
                                    overview = current.overview,
                                    cast = nextCast,
                                    returnTo = current.returnTo,
                                    totalEpisodesInSeason = current.totalEpisodesInSeason
                                )
                            } else {
                                screen = Screen.Streams(
                                    target = nextTarget,
                                    parentId = current.parentId,
                                    returnTo = stableBackDestination(current.returnTo),
                                    parentType = current.parentType,
                                    itemPoster = current.itemPoster,
                                    backdropUrl = current.backdropUrl,
                                    clearLogoUrl = current.clearLogoUrl,
                                    overview = current.overview,
                                    cast = nextCast
                                )
                            }
                        }
                    "navigate_actor" -> {
                        val personId = data.getIntExtra("actor_person_id", -1)
                        val resumePos = data.getLongExtra("actor_resume_position_ms", 0L)
                        if (personId > 0) {
                            screen = Screen.Actor(
                                personId = personId,
                                returnTo = current.copy(
                                    startPositionMs = resumePos,
                                    fromActorReturn = true
                                )
                            )
                        }
                    }
                    else -> {
                        // Normal BACK exit from player. Mark this target so the
                        // streams screen never re-auto-selects it.
                        val exitedKey = streamNavigationKey(
                            current.parentType,
                            current.episodeStreamId.orEmpty()
                        )
                        autoPlayedStreamKeys =
                            (autoPlayedStreamKeys + exitedKey).distinct()
                        if (current.parentType == "channel") {
                            screen = Screen.Guide
                        } else if (AppPreferences.getAutoSelectStream(context)) {
                            screen = stableBackDestination(current.returnTo)
                        } else {
                            // Auto-select is off: the user chose a source manually,
                            // so return to the picker for that target.
                            screen = Screen.Streams(
                                target = StreamsTarget(
                                    contentType = current.parentType,
                                    streamId = current.episodeStreamId.orEmpty(),
                                    title = restoredStreamTitle(
                                        displayName = current.itemName,
                                        season = current.season,
                                        episode = current.episode,
                                        episodeTitle = current.episodeTitle
                                    ),
                                    displayName = current.itemName,
                                    season = current.season,
                                    episode = current.episode,
                                    resumePositionMs = current.startPositionMs,
                                    totalEpisodesInSeason = current.totalEpisodesInSeason,
                                    runtimeMinutes = current.runtimeMinutes
                                ),
                            parentId = current.parentId,
                            returnTo = stableBackDestination(current.returnTo),
                            parentType = current.parentType,
                            itemPoster = current.itemPoster,
                            backdropUrl = current.backdropUrl,
                                clearLogoUrl = current.clearLogoUrl,
                                overview = current.overview,
                                cast = current.cast.map { member ->
                                    TmdbCastMember(
                                        id = member.id,
                                        name = member.name,
                                        character = member.character,
                                        profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            }

            LaunchedEffect(current.url) {
                val intent = Intent(context, NativePlayerActivity::class.java).apply {
                    putExtra("stream_url", current.url)
                    current.audioUrl?.let { putExtra("audio_url", it) }
                    putExtra("parent_id", current.parentId)
                    putExtra("parent_type", current.parentType)
                    putExtra("season", current.season ?: -1)
                    putExtra("episode", current.episode ?: -1)
                    current.episodeStreamId?.let { putExtra("episode_stream_id", it) }
                    current.episodeTitle?.let { putExtra("episode_title", it) }
                    putExtra("item_name", current.itemName)
                    putExtra("display_name", current.itemName)
                    current.itemPoster?.let { putExtra("item_poster", it) }
                    current.clearLogoUrl?.let { putExtra("clear_logo_url", it) }
                    current.backdropUrl?.let { putExtra("backdrop_url", it) }
                    current.overview?.let { putExtra("item_overview", it) }
                    putExtra("start_position_ms", current.startPositionMs)
                    putExtra("from_actor_return", current.fromActorReturn)
                    if (current.streamHeaders.isNotEmpty()) {
                        putExtra("stream_headers", current.streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    }
                    current.drmLicenseUrl?.let { putExtra("drm_license_url", it) }
                    if (current.drmHeaders.isNotEmpty()) {
                        putExtra("drm_headers", current.drmHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    }
                    // Pass sources as JSON array
                    val sourcesArray = JSONArray()
                    current.sources.forEach { stream ->
                        val obj = JSONObject().apply {
                            put("name", stream.name)
                            put("title", stream.title)
                            put("description", stream.description)
                            put("url", stream.url)
                            put("audioUrl", stream.audioUrl)
                            put("infoHash", stream.infoHash)
                            put("fileIdx", stream.fileIdx)
                            if (stream.badges.isNotEmpty()) {
                                val badgesArray = JSONArray()
                                stream.badges.forEach { badge ->
                                    badgesArray.put(
                                        JSONObject().apply {
                                            put("name", badge.name)
                                            put("imageURL", badge.imageURL)
                                            put("tagColor", badge.tagColor)
                                            put("tagStyle", badge.tagStyle)
                                            put("textColor", badge.textColor)
                                            put("borderColor", badge.borderColor)
                                        }
                                    )
                                }
                                put("badges", badgesArray)
                            }
                        }
                        sourcesArray.put(obj)
                    }
                    putExtra("sources_json", sourcesArray.toString())
                    // Pass cast as JSON array
                    val castArray = JSONArray()
                    current.cast.forEach { member ->
                        val obj = JSONObject().apply {
                            put("id", member.id)
                            put("name", member.name)
                            put("character", member.character)
                            put("profilePath", member.profilePath)
                        }
                        castArray.put(obj)
                    }
                    putExtra("cast_json", castArray.toString())
                    putExtra("season_episode_label", listOfNotNull(current.season?.let { "Season $it" }, current.episode?.let { "Episode $it" }).joinToString(" • "))
                    // Pass total episodes
                    current.totalEpisodesInSeason?.let { putExtra("total_episodes_in_season", it) }
                }
                playerResultLauncher.launch(intent)
            }
        }
    }

    // Loading splash while autoselect resolves sources in the background —
    // mirrors the player's first-load splash (backdrop + pulsing clearlogo) —
    // so the streams picker is never shown before playback.
    pendingAutoPlay?.let { pending ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            if (!pending.backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(pending.backdropUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = pending.target.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!pending.clearLogoUrl.isNullOrBlank()) {
                    PulsingClearLogo(
                        url = pending.clearLogoUrl,
                        contentDescription = pending.target.displayName
                    )
                } else {
                    Text(
                        text = pending.target.displayName,
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(
                    modifier = Modifier.height(14.dp)
                )

                Text(
                    text = "Finding sources…",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    }
}
