package com.kennyb1201.kbstream

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
// Compose's KeyEvent under an alias: this file imports android.view.KeyEvent
// for the platform handlers, and the two cannot share a name. `key`/`type` are
// the compose extension properties on it, and extensions have to be imported.
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.domain.streamengine.BingeGroupResolver
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.update.AppUpdater
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.tmdbImageOriginal
import com.kennyb1201.kbstream.ui.actor.ActorScreen
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.collection.CollectionScreen
import com.kennyb1201.kbstream.ui.components.AutoPlayLoadSplash
import com.kennyb1201.kbstream.ui.components.KBFeedbackHost
import com.kennyb1201.kbstream.ui.components.KBHeroTransition
import com.kennyb1201.kbstream.ui.components.LocalKBHeroTransition
import com.kennyb1201.kbstream.ui.components.LocalKBFeedback
import com.kennyb1201.kbstream.ui.components.rememberKBFeedbackState
import com.kennyb1201.kbstream.ui.components.KBCard
// The touch twin, for the one dialog a phone draws with buttons: the TV card's
// clicks are focus/D-pad driven, so a finger cannot press it at all.
import com.kennyb1201.kbstream.ui.components.touch.KBCard as TouchKBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.LAUNCH_SPLASH_TIMEOUT_MS
import com.kennyb1201.kbstream.ui.LaunchSplashTarget
import com.kennyb1201.kbstream.ui.components.LaunchSplash
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.detail.DetailScreen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.CatalogGridScreen
import com.kennyb1201.kbstream.ui.mobile.CatalogGridScreenMobile
import com.kennyb1201.kbstream.ui.mobile.MobileBottomNav
import com.kennyb1201.kbstream.ui.mobile.MobileHomeTopRequest
import com.kennyb1201.kbstream.ui.mobile.MobileNavDestination
import com.kennyb1201.kbstream.ui.mobile.mobileNavSelection
import com.kennyb1201.kbstream.ui.mobile.OnboardingScreenMobile
import com.kennyb1201.kbstream.ui.profiles.ProfileEditScreen
import com.kennyb1201.kbstream.ui.profiles.ProfilePickerScreen
import com.kennyb1201.kbstream.ui.home.HomeScreen
import com.kennyb1201.kbstream.ui.home.HomeViewModel
import com.kennyb1201.kbstream.ui.home.upNextPlayerNameOrUnknown
import com.kennyb1201.kbstream.ui.launchSplashVisible
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.KidsLiveTvFilter
import com.kennyb1201.kbstream.data.iptv.PendingChannelTune
import com.kennyb1201.kbstream.data.notifications.NotificationCenter
import com.kennyb1201.kbstream.ui.iptv.GuideScreen
import com.kennyb1201.kbstream.ui.iptv.IptvViewModel
import com.kennyb1201.kbstream.ui.sports.SportsHubScreen
import com.kennyb1201.kbstream.ui.onboarding.OnboardingPrefs
import com.kennyb1201.kbstream.ui.onboarding.OnboardingScreen
import com.kennyb1201.kbstream.data.player.PlayedLinkCache
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.ui.player.ExternalPlayerActivity
import com.kennyb1201.kbstream.ui.player.MpvPlayerActivity
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.player.NextEpisodeResult
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.player.PlayerCastMember
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.kennyb1201.kbstream.ui.kb.KBFolderScreen
import com.kennyb1201.kbstream.ui.library.LibraryScreen
import com.kennyb1201.kbstream.ui.settings.SettingsScreen
import com.kennyb1201.kbstream.ui.search.SearchScreen
import com.kennyb1201.kbstream.ui.search.SearchSeed
import com.kennyb1201.kbstream.ui.search.SearchViewModel
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.trakt.TraktConnectScreen
import com.kennyb1201.kbstream.ui.player.addonsFor
import com.kennyb1201.kbstream.ui.player.sourceAddonsJson
import com.kennyb1201.kbstream.ui.streams.StreamsScreen
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.decade.DecadeScreen
import com.kennyb1201.kbstream.ui.studio.StudioScreen
import com.kennyb1201.kbstream.ui.tag.TagScreen
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.screenTransitionMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen-level log tag for this activity (startup lines use TAG_STARTUP). */
private const val TAG = "MAIN"

sealed class Screen {

    object Home : Screen()

    /**
     * Add-on management. [returnTo] is where Back lands, because this screen
     * is reached from three places: Settings (the app's normal route in),
     * onboarding's setup cards, and Settings' "Home / Collections" row, which
     * leaves [com.kennyb1201.kbstream.ui.home.HomeRailManagerRequest] behind so
     * the rail manager opens on arrival. The header's own BACK button is gone:
     * it used to send everyone Home while the hardware BACK sent everyone to
     * Settings — the same gesture landing on two different screens depending
     * on which BACK was pressed — so the remote's Back is now the only one, and
     * it follows where the user actually came from.
     */
    data class Addons(val returnTo: Screen = Home) : Screen()

    /**
     * The Catalog Builder: compose a rule-based catalog (media type, sort and
     * the full filter set) that Home then draws as its own rail. Reached from
     * Settings' "Collections & Catalogs" pane, which carries its own return
     * path, so Back lands on whichever screen opened it.
     */
    data class CatalogBuilder(val returnTo: Screen = Home) : Screen()

    object Search : Screen()

    /**
     * The Simkl connect page. Reached from Settings' tracker row AND from
     * onboarding's setup card, which are two different "back" destinations —
     * so it carries its own, exactly like [Addons].
     *
     * The header button used to be hardwired to Settings while the hardware
     * Back key went Home (the `else` arm of the back stack). On the onboarding
     * path that meant a button labelled "BACK TO HOME" dropping the user into
     * Settings, skipping the guide they were in the middle of. The two exits
     * now agree, and both follow where the page was opened from.
     */
    data class Simkl(val returnTo: Screen = Home) : Screen()

    /**
     * The Trakt connect page: a third tracker beside Simkl, reached from
     * Settings' tracker row. It carries its own return destination for the same
     * reason [Simkl] does - the page has more than one way in, and each one's
     * Back must land where the viewer came from.
     */
    data class Trakt(val returnTo: Screen = Home) : Screen()

    object Guide : Screen()

    /**
     * The sports hub: league tabs of live/upcoming/final games, reached from a
     * button in the guide's header. Back returns to Live TV, which is where it
     * is opened from - and the only place the playlist it resolves against is
     * configured.
     *
     * [detailGameId] / [detailTournamentId] name the detail sheet the hub reopens
     * on entry, and are set by the player launched FROM that sheet and nowhere
     * else (see the hub branch below). Back from a game therefore returns to the
     * sheet it was played from, rather than dropping the viewer on the bare
     * slate. At most one is ever non-null, a hub opened from the guide carries
     * neither, and neither survives process death - a restore opens the plain
     * hub (see decodeScreen in NavStateSerialization.kt).
     */
    data class Sports(
        val detailGameId: String? = null,
        val detailTournamentId: String? = null,
    ) : Screen()

    object Library : Screen()

    object Settings : Screen()

    /**
     * The viewing-stats page, opened from Settings' Data pane. A screen of its
     * own rather than a pane: its numbers are read from the profile's own
     * history and its list can be long, so it scrolls independently and Back
     * returns to Settings the same way Catalog Builder does.
     */
    object Stats : Screen()

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
        // Watch-provider id when this page is a streaming SERVICE (movies +
        // series rails via provider discover); null for plain network/company
        // pages.
        val providerId: Int? = null,
        // ORIGINALS rails: the brand's network/company id (what it made) and
        // whether that id is a company (company discover also covers movies).
        val networkOrCompanyId: Int? = null,
        val networkIsCompany: Boolean = false,
        // Optional extra originals rail from the brand's production company
        // (company discover: movies + TV) — catches titles that left the
        // service or were never tagged with the provider.
        val originalsCompanyId: Int? = null,
        val returnTo: Screen = Home
    ) : Screen()

    /** One decade's discover page (genre-style rails, no RECENT rails). */
    data class Decade(
        val decadeStart: Int,
        val name: String,
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

    /**
     * Profile picker. [returnTo] == null means the cold-launch entry picker
     * (Back must exit the app); a non-null returnTo means it was opened from
     * inside the app (Settings / the top bar), so Back returns there.
     */
    data class ProfilePicker(val returnTo: Screen? = null) : Screen()

    data class ProfileEdit(
        val editingProfileId: String? = null,
        val returnTo: Screen? = null
    ) : Screen()

    /** Whole addon catalog browsed as a poster grid (from Home long-press). */
    data class CatalogGrid(
        val title: String,
        val addonName: String,
        val returnTo: Screen = Home
    ) : Screen()

    /** One imported KB collection folder (its own layout mode). */
    data class KBFolder(
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
        /**
         * The launch explicitly asked for the beginning (Home's long-press
         * "Play from Beginning"). [startPositionMs] is 0 either way, so this
         * is what stops the player falling back to the saved history
         * position.
         */
        val startFromBeginning: Boolean = false,
        /**
         * Opened by the Random button: the player chains into random aired
         * episodes instead of the arithmetic next one.
         */
        val randomEpisodes: Boolean = false,
        val fromActorReturn: Boolean = false,
        /**
         * True when this player was launched by tapping a source in the streams
         * picker (manual pick), as opposed to auto-play. Back from such a
         * playback returns to the picker so the user can try another source.
         */
        val pickedFromStreams: Boolean = false,
        val returnTo: Screen = Home,
        val sources: List<Stream> = emptyList(),
        /**
         * The add-on behind each entry of [sources], in the same order - the
         * session-scoped demotion's map of whose link each row is (see
         * [com.kennyb1201.kbstream.ui.player.SourceAddonSession]). Empty, or
         * shorter than [sources], reads as "unknown add-on" for the missing
         * entries.
         */
        val sourceAddons: List<String?> = emptyList(),
        val streamHeaders: Map<String, String> = emptyMap(),
        val totalEpisodesInSeason: Int? = null,
        val runtimeMinutes: Int? = null,
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap(),
        /**
         * Non-null when this launch reused a cached debrid link; the key doubles
         * as the invalidation handle if the link turns out to be dead.
         */
        val linkCacheKey: String? = null
    ) : Screen()
}

// Screen persistence (rememberSaveable) — the saver, the JSON screen codec,
// and the stream navigation keys all live in NavStateSerialization.kt.


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
    val totalEpisodesInSeason: Int? = null,
    /** bingeGroup of the stream that just ended — empty for fresh starts. */
    val bingeGroup: String? = null,
    /** Best-effort addon identity of the stream that just ended. */
    val addonName: String? = null
) {
    val streamKey: String
        get() = streamNavigationKey(target.contentType, target.streamId)

    /**
     * True when this launch is the NEXT episode of what just finished, rather
     * than a fresh start. The two binge fields are set only by the handoff
     * paths (see launchNextEpisode in the players), so their presence is the
     * distinction the played-link cache uses to stay out of a binge's way.
     */
    val isNextEpisodeAdvance: Boolean
        get() = !bingeGroup.isNullOrBlank() || !addonName.isNullOrBlank()

    fun toPlayerScreen(
        stream: Stream,
        allSources: List<Stream>,
        sourceAddons: List<String?> = emptyList()
    ): Screen.Player {
        // A DRM stream cannot play on MPV (the engine's own handoff excludes
        // those too), so it must not inherit the anime route's engine choice.
        if (stream.drm?.licenseUrl != null) {
            PlayerEngine.clearLaunchAnime()
        }

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
            startFromBeginning = target.startFromBeginning,
            randomEpisodes = target.randomEpisodes,
            returnTo = returnTo,
            sources = allSources,
            sourceAddons = sourceAddons,
            totalEpisodesInSeason = totalEpisodesInSeason,
            runtimeMinutes = target.runtimeMinutes,
            // The addon's own HTTP headers for this link (Referer / Origin /
            // Cookie / User-Agent). They were modeled nowhere and passed
            // nowhere, so a gated direct source was fetched with a bare
            // Chrome UA and no Referer - the shape that makes a host answer
            // with a throttled variant. See Stream.requestHeaders.
            streamHeaders = stream.requestHeaders,
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

/**
 * The art a guide channel launch hands its load splashes: the channel's own
 * icon.
 *
 * A channel is the one launch shape with no widescreen backdrop and no TMDB
 * clearlogo, so both splashes - the pre-player cover in MainActivity, and the
 * player's own first load - had nothing to draw but the channel NAME as plain
 * text. The channel icon IS this launch's title graphic, so it is carried as
 * the clearlogo.
 *
 * Handing it over matters twice: the cover paints it pulsing (see
 * PulsingClearLogo), and the player keys its title-graphic policy off the same
 * extra - with no clearlogo it deletes the icon from the splash and prints the
 * name instead (see NativePlayerActivity.enforceTitleGraphicPolicy), so the
 * icon may not simply ride the poster it also fills.
 *
 * Blank counts as absent, so a playlist row whose logo is "" falls through to
 * the guide's icon rather than handing the image loader an empty URL.
 */
internal fun channelTitleGraphic(channelLogoUrl: String?, epgIconUrl: String?): String? =
    channelLogoUrl?.takeIf { it.isNotBlank() }
        ?: epgIconUrl?.takeIf { it.isNotBlank() }

/**
 * The TMDB id the player splash looks a chosen still up by, or null when this
 * launch has no TMDB title behind it.
 *
 * The splash is shared by on-demand playback and live channels, and this is the
 * one place that difference is answered: a channel launch carries its own id
 * namespace ("channel") whose id can be numeric, so it must never reach a TMDB
 * title's choice (see [ImageOverrides]). An add-on id TMDB does not know is
 * already rejected by [ImageOverrides.tmdbIdOf].
 */
private fun splashTmdbId(parentType: String, parentId: String): Int? =
    if (parentType == "channel") null else ImageOverrides.tmdbIdOf(parentId)

/**
 * The Player screen a live channel click produces, whichever screen asked.
 *
 * One function rather than two call sites because the guide and the sports hub
 * must launch a channel IDENTICALLY - same parent type, same stream id, same
 * title graphic, same direct source - and the zap registry the player reads
 * (CH+/CH−) keys off exactly those fields. Two copies of this list would
 * drift on the first edit to either, and the failure mode is a hub launch that
 * cannot zap.
 *
 * [backups] is the one thing the two callers do NOT share, and it is the only
 * reason this takes more than one channel: a game the playlist carries on two
 * feeds hands the player both, in the matcher's own order (the chosen feed
 * first), and the player's existing source ladder walks to the next one when a
 * feed will not open. A guide click has no such list and passes none - its
 * channel is the viewer's own choice, not a match to be second-guessed.
 */
internal fun liveChannelScreen(
    channel: IptvChannel,
    backups: List<IptvChannel> = emptyList(),
    epgIconUrl: String?,
    returnTo: Screen
): Screen.Player {
    val channelName = channel.displayName.ifBlank { "Live Channel" }
    // The channel icon is the only art a channel launch has, so it is also this
    // launch's title graphic. See channelTitleGraphic.
    val poster = channelTitleGraphic(channel.logoUrl, epgIconUrl)
    val directSource = Stream(name = channelName, title = channelName, url = channel.streamUrl)

    // Each backup carries its OWN name and headers: a second feed is often a
    // different URL on a different host, and a switch that dropped the provider's
    // headers would fail exactly like the feed it is replacing.
    val backupSources = backups.map { backup ->
        Stream(
            name = backup.displayName.ifBlank { backup.name },
            title = backup.displayName.ifBlank { backup.name },
            url = backup.streamUrl,
            headers = backup.headers.takeIf { it.isNotEmpty() }
        )
    }

    return Screen.Player(
        url = channel.streamUrl,
        audioUrl = directSource.audioUrl,
        parentId = channel.id.ifBlank { channel.streamUrl },
        parentType = "channel",
        season = null,
        episode = null,
        // The channel's own id travels here too: it is what the in-player guide
        // and the zap registry know the live channel by.
        episodeStreamId = channel.id,
        itemName = channelName,
        itemPoster = poster,
        // Also the splash's title graphic, which is what makes the loading
        // screen pulse the channel logo instead of printing the channel name.
        clearLogoUrl = poster,
        startPositionMs = 0L,
        sources = listOf(directSource) + backupSources,
        streamHeaders = channel.headers,
        returnTo = returnTo
    )
}

/**
 * True when a recovered next-episode handoff names an episode this profile has
 * ALREADY finished.
 *
 * A recovered handoff exists to resume the binge on the episode that had not
 * been watched yet. One that names a finished episode is stale - the pending
 * handoff is cleared with a write that can be lost to a process kill, so an
 * old one can survive - and auto-playing it is exactly the "it started the
 * first episode I began on" report. Everything here is best-effort: any doubt
 * answers false, which keeps the recovery doing what it did before.
 */
private suspend fun recoveredEpisodeAlreadyWatched(
    context: Context,
    showId: String,
    pending: NextEpisodeResult.PendingNext
): Boolean {
    val id = showId.trim()
    if (id.isBlank()) return false
    // The handoff's stream id carries the add-on's own show id; watch-history
    // rows are canonicalized to "tt..."/"tmdb:<n>", so both flavors are tried.
    val forms = linkedSetOf(id)
    id.removePrefix("tmdb:").toIntOrNull()?.let { forms += "tmdb:$it" }
    return try {
        WatchHistoryDatabase.getInstanceScoped(context)
            .watchHistoryDao()
            .getCompletedForParents(forms.toList())
            .any { row -> row.season == pending.season && row.episode == pending.episode }
    } catch (_: Exception) {
        false
    }
}class MainActivity : ComponentActivity() {


    // Kids-time accumulation is driven by KidsTimeGuard's own application-level
    // ActivityLifecycleCallbacks, NOT by this Activity's onStart/onStop. The
    // player is a separate Activity, so stopping here is what happens on every
    // playback start - reporting that as "backgrounded" froze the daily-limit
    // clock for the whole of playback. Do not reintroduce the calls.

    /// Latched by the exit dialog: while true, every key event is consumed
    /// here at the Activity level for a short settle window before
    /// finishAndRemoveTask() runs. Without this, stray key events from the
    /// EXIT press (IR remotes double-fire, held-key autorepeat) leak to the
    /// TV launcher after finish() and activate whatever icon is focused
    /// there — the "random app opens" bug. Activity-level dispatch covers
    /// all windows (including dialogs), unlike Compose modifier guards.
    @Volatile
    private var exitGuardLatched = false

    fun latchExitGuard() {
        exitGuardLatched = true
    }

    /** Latched by [observeFirstFrame]: the draw listener records once. */
    private var firstFrameRecorded = false

    /**
     * A launch intent that arrived while this Activity was already running.
     *
     * `android:launchMode="singleTop"` (AndroidManifest) keeps the running
     * instance and delivers the new intent to [onNewIntent] instead of
     * re-creating the activity — and the platform's [android.app.Activity.onNewIntent]
     * does nothing at all by default. Deep-link routing lives in the compose
     * tree, which reads `activity.intent` once, when it first composes, so every
     * WARM deep link (a TV-launcher Watch Next card, an OS global-search
     * suggestion, a spoken query, a live-TV reminder tap, a new-episode alert)
     * brought the app forward and then did nothing. [onNewIntent] publishes the
     * intent here, and the tree consumes it and routes it through the same
     * function as the cold launch (see [resolveLaunchIntentRoute]).
     */
    private var incomingIntent by mutableStateOf<Intent?>(null)

    /**
     * The one-shot runtime notification prompt (see
     * [com.kennyb1201.kbstream.data.notifications.NotificationPromptRules]).
     *
     * Registered as a field because an ActivityResult launcher has to exist
     * before the activity reaches STARTED, and the answer needs no handling:
     * both alert features already re-check `canPost()` themselves, and Android
     * 13 stops re-showing the dialog after two denials anyway.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Asks for `POST_NOTIFICATIONS` once, when there is something to ask about.
     *
     * Both alert features are on by default, so before this a fresh install on
     * Android 13+ had them enabled, ran its checks, and had every notification
     * dropped by the system with nothing on screen to explain it. The ask is
     * spent (flag set) before the dialog opens, so a denial does not re-ask on
     * every launch; the Settings toggles remain the place to grant it later.
     */
    private fun maybeAskForNotificationPermission() {
        val granted = Build.VERSION.SDK_INT <
            com.kennyb1201.kbstream.data.notifications.NotificationPromptRules
                .POST_NOTIFICATIONS_SDK ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val anyAlertEnabled =
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .getNewEpisodeNotifications(this) ||
                com.kennyb1201.kbstream.data.settings.AppPreferences
                    .getLiveReminderNotifications(this)
        val ask = com.kennyb1201.kbstream.data.notifications.NotificationPromptRules
            .shouldAsk(
                sdkInt = Build.VERSION.SDK_INT,
                granted = granted,
                alreadyAsked = com.kennyb1201.kbstream.data.settings.AppPreferences
                    .getNotificationPermissionAsked(this),
                anyAlertEnabled = anyAlertEnabled
            )
        if (!ask) return
        com.kennyb1201.kbstream.data.settings.AppPreferences
            .setNotificationPermissionAsked(this, true)
        runCatching {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep `intent` current too: the cold-launch path reads it, and the
        // activity is re-created (configuration change, the theme/profile
        // `recreate()`) after a warm launch — the extras have to survive that.
        setIntent(intent)
        incomingIntent = intent
    }

    /**
     * Hands the pending warm-launch intent to the compose tree, exactly once.
     *
     * Cleared on read rather than kept: a deep link is a handoff, not state, so
     * nothing lingers to re-fire if [AppRoot] re-enters composition (the
     * cold-launch effect runs once per activity; this one is keyed on the intent
     * it carries).
     */
    fun consumeIncomingIntent(): Intent? = incomingIntent.also { incomingIntent = null }

    // androidx.core marks ComponentActivity.dispatchKeyEvent @RestrictedApi
    // ("same library group"), which an app cannot satisfy however it calls it.
    // The call is `super`, from an override of the same method: the ordinary
    // way to see a key before the view tree does, and deliberate here (the
    // exit guard above has to catch keys aimed at a dialog's window too).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (exitGuardLatched) {
            // Consume everything: both DOWN and UP of any in-flight press.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        // The manifest's theme is the launch splash (@style/Theme.KBStream.Splash),
        // which is what the system paints between the launcher and this app's
        // first composed pixel: the void with the brass mark centred on it.
        // It is ONLY that frame - the window the activity actually runs in is
        // the ordinary theme, and everything from the first composition is the
        // Compose splash (LaunchSplash), which paints the same mark at the same
        // size on the same background. Swapped back here, before the
        // superclass builds the window, so the splash theme is never inherited
        // by a dialog, a popup or a screen.
        setTheme(R.style.Theme_KBStream)
        // A phone clears the manifest's landscape lock before the window exists;
        // on TV this returns without touching the activity. See FormFactor.
        FormFactor.applyOrientation(this)
        super.onCreate(savedInstanceState)
        // Application.onCreate → this activity's create: the part of the
        // launch the user actually waits on before the first frame. A
        // cumulative figure rather than a phase delta, so it is recorded here
        // instead of through recordStartupPhase — but it is still LOGGED, or
        // `adb logcat -s STARTUP` would show only three of the four phases.
        //
        // FIRST create only (recordLaunch), because the figure is cumulative:
        // this activity is re-created on a configuration change, on the
        // theme/profile `recreate()`, and whenever the user comes back to Home
        // from the player, and on every one of those a plain record() would
        // write the session's whole elapsed time down as a startup cost.
        val sinceAppStartMs =
            com.kennyb1201.kbstream.data.reporting.PerfTrace.sinceAppStartMs()
        if (
            sinceAppStartMs >= 0 &&
            com.kennyb1201.kbstream.data.reporting.PerfTrace
                .recordLaunch("startup.mainCreate", sinceAppStartMs)
        ) {
            Log.w(
                TAG_STARTUP,
                "startup.mainCreate=${sinceAppStartMs}ms " +
                    "sinceAppStart=${sinceAppStartMs}ms"
            )
        }

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        // Launch, rebuild - or a RETURN from another app? Read before anything
        // composes, because the app shell's saveable state has to know which
        // one it is restoring into (see AppRoot's `coldLaunch`, and
        // [isColdLaunch] for the decision itself).
        //
        // `savedInstanceState` is the phone's half: a create handed the kept
        // task's state is the system having killed this process while the app
        // was backgrounded, not the viewer starting it. Gating that restore to
        // Who's Watching is what made every app switch reopen the picker. A
        // television ignores the argument (see isColdLaunch), so its launch
        // gate is unchanged.
        val coldLaunch = isColdLaunch(
            shellStarted = shellStarted,
            isTv = FormFactor.isTv,
            hasSavedState = savedInstanceState != null
        )
        shellStarted = true

        // Establish the active profile BEFORE the first composition. The
        // theme seed inside setContent reads PROFILE-SCOPED prefs (AMOLED /
        // pure-black live under "<profileId>.kbstream_player_prefs"); with
        // init still pending, that read resolved the legacy global store
        // (toggle=false) and the app launched with the regular palette —
        // AMOLED only "kicked in" after visiting Settings, whose own read
        // then mirrored the correct scoped value into the live theme state.
        // Isolated: a corrupt prefs blob or unexpected throw here used to be
        // a guaranteed no-launch (black screen → launcher). With the guard
        // the app still starts (profiles may be empty → runs unsigned-in as
        // the legacy global store) instead of dying.
        val preCompositionStartedMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            com.kennyb1201.kbstream.data.sync.ProfileManager.init(applicationContext)
        }.onFailure {
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                it, mapOf("source" to "activity_create_profile_init")
            )
        }

        // Kids Mode time guard: owns daily-limit/bedtime tracking and the
        // lock overlay state. It registers its own application-level lifecycle
        // callbacks, so accumulation follows the PROCESS being foregrounded
        // (playback included) instead of this Activity alone.
        runCatching {
            com.kennyb1201.kbstream.data.sync.KidsTimeGuard.start(applicationContext)
        }.onFailure {
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                it, mapOf("source" to "activity_create_kids_guard")
            )
        }

        // The synchronous disk work that has to finish before anything can be
        // composed: the profile store plus the kids-time guard. Timed as one
        // phase because both sit between this activity's create and the first
        // composition, which is exactly the window a "the app is slow to come
        // back from the player" report cannot attribute.
        recordStartupPhase("startup.preComposition", preCompositionStartedMs)

        // The launch window's own background, before anything is composed.
        // The theme's static `colorBackground` cannot follow the AMOLED toggle,
        // so the window used to disagree with the first frame in BOTH
        // directions - pure black under an ordinary launch (a black window on
        // every screen, not just Home) and the ordinary void under an AMOLED
        // one. Match the window to the token the app shell paints.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                com.kennyb1201.kbstream.ui.theme.themeVoidWindowColor(this)
            )
        )

        maybeAskForNotificationPermission()

        val composeStartedMs = android.os.SystemClock.elapsedRealtime()
        setContent {
            // Sync the AMOLED toggle into the theme's live state BEFORE the
            // first composition so launch already paints the right palette.
            com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors(this)
            KBStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Safe area for a FINGER, not for a TV: the window is
                    // edge-to-edge (setDecorFitsSystemWindows(false)) because
                    // the TV shell wants the full panel, and a television has
                    // no status or navigation bar for content to slide under.
                    // A phone does: without this every phone screen drew its
                    // first row under the clock and its last row under the
                    // gesture bar. The padding is applied here, once, rather
                    // than in twenty screen files - and only on the phone, so
                    // the TV layout is byte-for-byte what it was.
                    //
                    // displayCutout is unioned in because systemBars does not
                    // reserve the notch area in landscape.
                    val mobileSafeArea = if (
                        com.kennyb1201.kbstream.FormFactor.isTv
                    ) {
                        Modifier
                    } else {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(mobileSafeArea)
                            // Insets-based IME handling app-wide: with
                            // adjustResize, every screen's fields (sign-in,
                            // API keys, addon URLs, profile names) stay above
                            // the keyboard via insets — no legacy window pan,
                            // which landed the scroll at the column bottom when
                            // the keyboard dismissed.
                            .imePadding()
                    ) {
                        // One transient-feedback channel for the whole shell.
                        // The host is the Box's LAST child so a message draws
                        // above whatever screen is showing — including
                        // onboarding, which AppRoot returns early for and which
                        // would otherwise skip a host placed inside AppRoot.
                        val kbFeedback = rememberKBFeedbackState()
                        CompositionLocalProvider(LocalKBFeedback provides kbFeedback) {
                            AppRoot(
                                incomingIntent = incomingIntent,
                                onIncomingIntentConsumed = { consumeIncomingIntent() },
                                coldLaunch = coldLaunch
                            )
                        }
                        KBFeedbackHost(state = kbFeedback)
                    }
                }
            }
        }
        recordStartupPhase("startup.setContent", composeStartedMs)
        observeFirstFrame()
    }

    /**
     * Foreground trigger for the add-on manifest auto-update.
     *
     * The automatic path used to have exactly two drivers: the launch hook in
     * Application.onCreate and a daily WorkManager job. On a TV box neither is
     * dependable on its own - the process is NOT recreated between sessions
     * (the launcher can sit in front of a warm process for weeks, so the launch
     * hook never fires again), and periodic work is subject to Doze / App
     * Standby, which on Fire OS can stretch a 24 h request into days. The
     * result was the report this fixes: "my addons never update automatically,
     * but pressing Refresh addons finds the new catalogs straight away".
     *
     * Returning to the app is the reliable cadence, because it is the one event
     * that happens exactly when a user is about to look at those catalogs. The
     * pass is throttled inside the manager (6 h, shared with the launch hook and
     * the worker), so a viewer flipping in and out of the app pays one prefs
     * read rather than a manifest fetch.
     */
    override fun onStart() {
        super.onStart()
        runCatching {
            com.kennyb1201.kbstream.data.addon.AddonManager
                .getInstance(applicationContext)
                .refreshInstalledAddonsSoon()
        }.onFailure { error ->
            // Never let a refresh trigger take the Activity down: this runs on
            // every return from the launcher.
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                error,
                mapOf("source" to "activity_start_addon_refresh")
            )
        }
    }

    /**
     * The first frame is when the launch is over for the user — and it is the
     * one number the frame-timing warnings in logcat ("Skipped 39 frames!",
     * "Davey! duration=923ms") cannot attribute to anything. Recorded once,
     * from the decor view's first draw, so a single capture reads:
     *
     *   STARTUP preComposition=… setContent=… firstFrame=… sinceAppStart=…
     *
     * A slow preComposition means prefs/store work (make it lazy), a slow
     * setContent means the tree being built on the main thread, and a slow
     * firstFrame with a fast setContent means layout/draw.
     */
    private fun observeFirstFrame() {
        val decor = window.decorView
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val observer = decor.viewTreeObserver
        observer.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (firstFrameRecorded) return
                firstFrameRecorded = true
                // Detach from the next message: removing a listener from
                // inside its own callback mutates the list being dispatched.
                //
                // Guarded because the captured observer can DIE before this
                // posted runnable runs: detaching/re-attaching the decor view
                // (a configuration change, a window re-add) swaps in a fresh
                // ViewTreeObserver, and calling removeOnDrawListener on the
                // old, dead one throws
                // IllegalStateException("This ViewTreeObserver is not alive,
                // call getViewTreeObserver() again"). That is fatal and took
                // the process down on the first frame of launch (Sentry
                // ANDROID-B, release 0.2.4257). A dead observer's listener
                // list is gone with it, so there is nothing left to remove
                // when it is not alive.
                decor.post {
                    runCatching {
                        if (observer.isAlive) {
                            observer.removeOnDrawListener(this)
                        }
                    }
                }
                recordStartupPhase("startup.firstFrame", startedMs)
            }
        })
    }

    /**
     * One startup phase, into both the diagnostics perf ring and a logcat
     * line, so a plain `adb logcat --pid=$(pidof …)` capture is enough to see
     * where a launch spends its time.
     */
    private fun recordStartupPhase(label: String, startedMs: Long) {
        val tookMs = android.os.SystemClock.elapsedRealtime() - startedMs
        com.kennyb1201.kbstream.data.reporting.PerfTrace.record(label, tookMs)
        Log.w(
            TAG_STARTUP,
            "$label=${tookMs}ms sinceAppStart=" +
                "${com.kennyb1201.kbstream.data.reporting.PerfTrace.sinceAppStartMs()}ms"
        )
    }

    /** Startup phase lines, deliberately separate from the screen tags. */
    private companion object {
        const val TAG_STARTUP = "STARTUP"

        /**
         * Whether an activity in THIS process has already built the app shell.
         *
         * The launch gate opens on Who's Watching whenever the device holds
         * profiles, and it does that from the screen state's own restore path —
         * which cannot tell a launch from a rebuild. A configuration change
         * (a phone rotating, a TV switching display mode, the theme/profile
         * `recreate()`) re-creates this activity in the same process and
         * restores that state, so every rotation reopened the picker over
         * whatever the viewer was doing. The first create of the process is the
         * launch; later ones keep the screen they saved. Process death clears
         * this with the rest of the process, so a restored app still starts
         * under a chosen identity.
         */
        @Volatile
        private var shellStarted = false
    }

@OptIn(ExperimentalSharedTransitionApi::class)
/**
 * Mounts the shared-element registry only while the hero flight can run.
 *
 * `SharedTransitionLayout` installs a lookahead scope, which costs an extra
 * measure pass over everything inside it — here the whole app shell, because
 * the layout has to enclose the screen switch for a rail poster and the Detail
 * hero to share one registry. Under reduced motion the flight is already inert
 * (the composition local below is null, so every hero modifier is a no-op), and
 * paying for a lookahead scope while nothing can fly is pure cost on these
 * low-memory boxes. So that path skips the layout outright and hands its
 * content a null scope.
 *
 * A wrapper rather than an inline `if` in AppRoot: the content it wraps is the
 * entire screen switch, and taking the scope as a parameter keeps that block
 * where it was instead of re-indenting it.
 */
@Composable
private fun HeroTransitionHost(
    enabled: Boolean,
    content: @Composable (SharedTransitionScope?) -> Unit
) {
    if (enabled) {
        SharedTransitionLayout {
            content(this)
        }
    } else {
        content(null)
    }
}

@Composable
fun AppRoot(
    /**
     * A deep link delivered while this activity was already running (see
     * [MainActivity.onNewIntent]); null on a cold launch, where the same extras
     * come off `activity.intent` below.
     */
    incomingIntent: Intent?,
    /** Called once [incomingIntent] has been routed, so it cannot re-fire. */
    onIncomingIntentConsumed: () -> Unit,
    /**
     * True only for the first activity create in this process (see
     * `MainActivity.shellStarted`). A re-create that keeps the same session -
     * a density or uiMode change, a theme/profile `recreate()` - restores the
     * shell's screen, and that restore is not a launch: gating it back to
     * Who's Watching is what made a phone rotation leave the screen the viewer
     * was on. (A rotation no longer re-creates this activity - orientation is in
     * its manifest `configChanges`, so the splash can be held sideways without
     * losing its in-flight autoplay.)
     */
    coldLaunch: Boolean = true
) {

    // Persisted across process death: Android can kill a backgrounded TV app,
    // and without a Saver the app would come back on Home instead of the
    // detail/streams screen the user was on. The Saver encodes the current
    // Screen as JSON; anything unparseable falls back to Home.
    //
    // Profile gating rides in that same saver, so the entry screen is decided
    // BEFORE anything composes: with profiles on the device the launch opens on
    // Who's Watching (each session starts under a chosen identity), and only a
    // profile-less install opens on Home. A rebuild inside the same process (a
    // phone rotating, a theme/profile recreate) restores the saved screen
    // instead - see `coldLaunch`. init() already ran in onCreate; this
    // is just the "which screen do we open on" read. It used to be a
    // LaunchedEffect beside this state, which runs a frame AFTER the first
    // composition — so Home composed, its rail loaders started, and its empty
    // placeholder rails flashed underneath the picker that then replaced it.
    val profilesExist = remember {
        com.kennyb1201.kbstream.data.sync.ProfileManager.hasProfiles(applicationContext)
    }
    // Whether a launch opens on Who's Watching, or goes straight into the last
    // used profile (Settings → Profiles → "Who's Watching on launch").
    // Device-local, read once: this is a launch-time decision, and flipping the
    // row from Settings re-creates nothing by itself.
    //
    // A PIN still forces the picker: the code is entered THERE (see
    // ProfilePickerScreen), so skipping it would silently unlock a protected
    // profile. The toggle only decides the no-PIN case.
    val launchProfileHasPin = remember {
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
            ?.let { com.kennyb1201.kbstream.data.sync.ProfileManager.hasPin(it) } == true
    }
    val showProfilePicker = remember(launchProfileHasPin) {
        launchProfileHasPin ||
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .getShowProfilePicker(applicationContext)
    }
    val entrySaver = remember(profilesExist, coldLaunch, showProfilePicker) {
        launchScreenSaver(profilesExist, coldLaunch, showProfilePicker)
    }
    var screen by rememberSaveable(stateSaver = entrySaver) {
        mutableStateOf<Screen>(launchScreen(profilesExist, showProfilePicker))
    }

    // Re-mirror the theme toggles on every active-profile change: AMOLED /
    // pure-black are profile-scoped prefs backing live theme state, and the
    // switch path (picker / profile editor) never touched the mirrors — the
    // new profile's palette only appeared after opening Settings.
    val themeMirrorContext = LocalContext.current
    LaunchedEffect(Unit) {
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.collect {
            com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors(themeMirrorContext)
        }
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

    // Collapses the screen transitions below to an instant cut when the user
    // has asked the platform to reduce motion.
    val transitionMs = screenTransitionMs(rememberReducedMotion())

    val context = LocalContext.current

    // First-run onboarding: shown over the app until the user taps
    // "Start Browsing". The flag lives in SharedPreferences so it survives
    // restarts and process death (and restoring a saved screen can't skip it).
    var onboardingComplete by rememberSaveable {
        mutableStateOf(OnboardingPrefs.isComplete(context))
    }

    // ----------------------------------------------------------------- launch
    // splash (see LaunchSplash / launchSplashVisible)
    //
    // The branded frame that covers the two beats that used to show raw
    // loading: the void window before the first composition (the launch theme
    // paints that one) and the placeholder rails while Home's catalogs load.
    //
    // Armed for the launch and LATCHED off the moment the content it stands in
    // front of is ready - never dismissed on a timer, because "quick" means it
    // does not hold the viewer. A profile pick re-arms it, since choosing an
    // identity is an entry into Home.
    var splashArmed by rememberSaveable { mutableStateOf(true) }
    // Deliberately NOT saveable: `splashArmed` is the latch, and it is restored
    // across a re-create, so a restored target could never show anything. It is
    // re-derived from the launch gate below and set outright by a profile pick.
    var splashTarget by remember(coldLaunch, showProfilePicker) {
        mutableStateOf(
            if (profilesExist && coldLaunch && showProfilePicker) {
                LaunchSplashTarget.PROFILE_PICKER
            } else {
                LaunchSplashTarget.HOME
            }
        )
    }

    // Bumped by every re-arm, so the failsafe below starts over with the
    // splash instead of inheriting the launch's remaining budget.
    var splashArmKey by rememberSaveable { mutableStateOf(0) }

    // The failsafe's deadline. Elapsed time is the one input that changes with
    // no state to observe - a load that never lands is exactly the case that
    // never recomposes - so one timer marks the deadline and the rule is handed
    // that value rather than a ticker recomposing the shell every frame.
    var splashDeadlinePassed by remember(splashArmKey) { mutableStateOf(false) }
    LaunchedEffect(splashArmKey) {
        delay(LAUNCH_SPLASH_TIMEOUT_MS)
        splashDeadlinePassed = true
    }
    val splashElapsedMs =
        if (splashDeadlinePassed) LAUNCH_SPLASH_TIMEOUT_MS else 0L

    // Collected, not read once from `profiles.value`: the store is populated
    // synchronously in onCreate, so this beat is the handoff alone in practice,
    // but a late emission must still dismiss the splash rather than sit it out
    // until the failsafe. Only while the picker is the beat it is covering.
    val profilesEmitted =
        if (splashArmed && splashTarget == LaunchSplashTarget.PROFILE_PICKER) {
            com.kennyb1201.kbstream.data.sync.ProfileManager.profiles
                .collectAsStateWithLifecycle().value.isNotEmpty()
        } else {
            true
        }

    // Home's readiness, read from the SAME instance HomeScreen composes: both
    // resolve `viewModel()` against this activity's ViewModelStore, and
    // HomeScreen's own parameter defaults to the same call (nothing
    // nav-scoped or factory-bound is involved). Resolved only while the splash
    // is waiting on Home - constructing it during the picker beat would kick
    // off a rail load before a profile has been chosen.
    val splashHomeViewModel =
        if (
            splashArmed &&
            splashTarget == LaunchSplashTarget.HOME &&
            onboardingComplete &&
            screen is Screen.Home
        ) {
            viewModel<HomeViewModel>()
        } else {
            null
        }
    val splashHomeLoading: Boolean
    val splashRailsPresent: Boolean
    val splashRailsStale: Boolean
    if (splashHomeViewModel != null) {
        splashHomeLoading =
            splashHomeViewModel.isLoading.collectAsStateWithLifecycle().value
        splashRailsPresent =
            splashHomeViewModel.rails.collectAsStateWithLifecycle().value.isNotEmpty()
        // Which profile the on-screen rails were BUILT for, against the one
        // that is active now. A pick re-arms this splash while the old
        // profile's rails are still composed (HomeViewModel clears them from
        // its own collector, a beat later), so for that first frame nothing is
        // loading and rails are present - the shape the rule reads as "ready".
        // Those rows are not content the viewer asked to see; they belong to
        // the identity just left, so they keep the splash up until the new
        // profile's rails land.
        //
        // Ordering: `railsProfileId` only moves when a build PUBLISHES (see
        // HomeViewModel.loadRailsInternal), so it holds the old id for the
        // whole switch, and `ProfileManager.setActive` runs before the pick
        // handler navigates - there is no frame between the two where this is
        // false while Home is also settled. Null means no profile yet (legacy
        // scope) and is deliberately never stale.
        val railsProfileId =
            splashHomeViewModel.railsProfileId.collectAsStateWithLifecycle().value
        val activeProfileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collectAsStateWithLifecycle().value?.id
        splashRailsStale = railsProfileId != null && railsProfileId != activeProfileId
    } else {
        // Not waiting on Home: none of these signals are what the splash
        // needs, and a ViewModel that does not exist cannot be loading
        // anything.
        splashHomeLoading = false
        splashRailsPresent = false
        splashRailsStale = false
    }

    val splashVisible = splashArmed && launchSplashVisible(
        target = splashTarget,
        profilesEmitted = profilesEmitted,
        homeLoading = splashHomeLoading,
        railsPresent = splashRailsPresent,
        elapsedMs = splashElapsedMs,
        railsStale = splashRailsStale
    )

    // Latched, not derived: once it has handed over, coming back to Home from
    // the player, a setting or a deep link must not replay it. Only a profile
    // pick re-arms it, which is why the flag is separate from the rule.
    //
    // Keyed on the arm as well as the verdict: a re-arm whose Home is ready
    // instantly leaves `splashVisible` false throughout, so a key of just the
    // verdict would never run again - and the splash would still be armed when
    // some later rail refresh emptied Home.
    LaunchedEffect(splashVisible, splashArmKey) {
        if (!splashVisible) splashArmed = false
    }

    // Deliberately NOT constructing TmdbRepository here. This composable used to
    // call getInstance during the first composition — building Retrofit and
    // Moshi on the main thread — for a value nothing in this file ever read.
    // Every consumer calls getInstance itself, and the repository now builds its
    // reflection-heavy half on IO (see TmdbRepository.warmUpReflectionStack).
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

    // Clearlogos DetailScreen has resolved, keyed by "type:id".
    //
    // The pre-playback cover splash is painted BEFORE Detail has its art (a
    // Continue Watching / Up Next deep link carries only the history row's meta,
    // whose logo is usually absent), so it fell back to the plain title while the
    // NEXT splash - fed the logo Detail had resolved - showed the pulsing
    // clearlogo. This lets the cover splash show the same art the moment it
    // lands, so the first splash is never the logoless one.
    val resolvedDetailClearLogos = remember { mutableStateMapOf<String, String>() }
    // The backdrop half of the same hand-off: DetailScreen reports the still it
    // resolved (onBackdropResolved), and every surface that covers or replaces
    // the screen reads it before the navigation meta's own.
    //
    // Reported: a Continue Watching resume showed the "Finding sources" splash
    // with no picture while the player's own splash - fed the backdrop by the
    // time Screen.Player existed - had one. The cover is painted from the deep
    // link's meta the moment the screen appears, and a CW / Up Next history row
    // carries a poster, not a backdrop, so that value was empty and stayed
    // empty: the resolved still had nowhere to go. Same map shape as the logos
    // above, and read by the same three surfaces (cover splash, cover splash's
    // pending branch, picker header).
    val resolvedDetailBackdrops = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(pendingAutoPlay) {
        val pending = pendingAutoPlay ?: return@LaunchedEffect
        // Mark the target as auto-played up front so the picker can never
        // auto-select it again if we fall through to it (no playable source).
        autoPlayedStreamKeys =
            (autoPlayedStreamKeys + pending.streamKey).distinct()
        // Replay inside the link's lifetime: hand the player the link that
        // played last time instead of paying the addon round-trip again. A miss
        // (nothing cached, expired, or a different episode) falls straight
        // through to the resolve below, unchanged.
        //
        // A NEXT-EPISODE advance is deliberately excluded (see
        // PendingPlay.isNextEpisodeAdvance): re-binging an episode inside the
        // link's lifetime would otherwise replay the PREVIOUS episode's
        // ordering and skip BingeGroupResolver.orderedForNextEpisode entirely,
        // which is the continuity a binge is supposed to inherit.
        val cached =
            if (pending.isNextEpisodeAdvance) null
            else PlayedLinkCache.get(context, pending.streamKey)
        if (cached != null) {
            // The replay answers to the viewer's ceiling too. A link remembered
            // before the ceiling was set (or before it was lowered) would
            // otherwise be the one way a 4K starts under a 1080p cap: this
            // branch never reaches the capped pick below, because it never
            // resolves anything.
            val replay = AutoPlayQuality.cappedPick(
                pick = cached.played,
                candidates = cached.sources,
                season = pending.target.season,
                episode = pending.target.episode,
                runtimeMinutes = pending.target.runtimeMinutes,
                cap = AppPreferences.getMaxAutoPlayQuality(context)
            )
            if (replay != null) {
                Log.i(TAG, "played-link cache hit for ${pending.streamKey}; skipping resolve")
                pendingAutoPlay = null
                // The link cache stores sources, not their add-ons, so a replay
                // hands the player an unknown-add-on list: nothing is skipped
                // and nothing is demoted off a stale name, which is the safe
                // default (see SourceAddonSession).
                screen = pending.toPlayerScreen(replay, cached.sources, emptyList())
                    .copy(linkCacheKey = pending.streamKey)
                return@LaunchedEffect
            }
            // Every cached source is taller than the ceiling: resolve afresh,
            // where the whole ranked list - beyond the ten the cache keeps - is
            // in front of the cap.
            Log.i(TAG, "played-link replay is above the auto-play ceiling; resolving")
        }
        val resolved = streamsViewModel.resolve(
            pending.target.contentType,
            pending.target.streamId,
            // The episode's own length goes with the request: it is what lets
            // the ranker score a file's size as a density rather than as bulk
            // (see StreamRanker), which is the difference between a healthy
            // 45-minute encode and a bloated one.
            pending.target.runtimeMinutes
        )
        // Binge continuation: when this auto-play is the next episode of a
        // show whose previous stream carried a Stremio bingeGroup, reorder
        // the sources so group/addon matches win (see BingeGroupResolver).
        // Fallback OFF additionally collapses the list to nothing so the
        // picker is shown instead of silently playing a different provider.
        // Either half of the handoff is context enough: the group is what a
        // tagging provider continues with, and the addon is the fallback for
        // one that stopped tagging consecutive files - the case where the
        // viewer sees a different, working addon being forgotten every
        // episode. Requiring a group skipped the resolver entirely there.
        val streams = resolved.streams
        val ordered =
            if (pending.bingeGroup.isNullOrBlank() && pending.addonName.isNullOrBlank()) {
                streams
            } else {
                BingeGroupResolver.orderedForNextEpisode(
                    context = context,
                    streams = streams,
                    previousBingeGroup = pending.bingeGroup,
                    previousAddonName = pending.addonName
                )
            }
        // The add-on for each source, in the same order the advance will walk:
        // the binge resolver above may have re-ordered the list, so the names
        // are resolved by stream identity rather than by their old position.
        val orderedAddons = addonsFor(ordered, resolved.streams, resolved.addons)
        // Auto-play takes the episode this request is for, then a source that
        // says nothing about the episode - and never one whose own name
        // declares another episode (see EpisodeMatch). Where that leaves no
        // source at all, the picker below opens with each file's own
        // declaration on its card, instead of the wrong episode starting by
        // itself: "Paw Patrol is playing the wrong episodes" was a head of the
        // list the app could see was not the episode and started anyway.
        val top = AutoPlayQuality.cappedPick(
            pick = EpisodeMatch.autoplayPick(
                ordered,
                pending.target.season,
                pending.target.episode,
                pending.target.runtimeMinutes
            ),
            candidates = ordered,
            season = pending.target.season,
            episode = pending.target.episode,
            runtimeMinutes = pending.target.runtimeMinutes,
            cap = AppPreferences.getMaxAutoPlayQuality(context)
        )
        // Recorded, not inferred from the head of the list: this is the
        // episode-handoff path a wrong episode is reported from, and the rank
        // block cannot say whether auto-play started anything at all (see
        // StreamRankReport.noteAutoPlay).
        com.kennyb1201.kbstream.data.reporting.StreamRankReport.noteAutoPlay(
            top?.let { "started · ${StreamRanker.labelOf(it)}" }
                ?: "none - the picker is showing instead " +
                "(episode length ${
                    pending.target.runtimeMinutes?.let { "$it min" } ?: "unknown"
                })"
        )
        pendingAutoPlay = null
        screen = if (top != null) {
            if (pending.isNextEpisodeAdvance) {
                // Not remembered either: caching the advance's ordering would
                // hand the NEXT replay of this episode the same stale ordering
                // the get above just refused. A fresh start (no binge identity)
                // is the case the cache is for.
                pending.toPlayerScreen(top, ordered, orderedAddons)
            } else {
                // Remember what actually started, so a replay within the link's
                // lifetime can skip this resolve entirely.
                PlayedLinkCache.remember(context, pending.streamKey, top, ordered)
                // ...and stamp the key on the player screen as well, exactly as
                // the cache-hit path above does: the key is the invalidation
                // handle the player uses when this freshly-resolved link turns
                // out to be dead before its first frame. Without it only a
                // REPLAY (a cache hit) could heal the entry, so one more launch
                // replayed the corpse.
                pending.toPlayerScreen(top, ordered, orderedAddons)
                    .copy(linkCacheKey = pending.streamKey)
            }
        } else {
            pending.toStreamsScreen()
        }
    }

    // One routing table for launch-intent extras, shared by the cold launch
    // below and by a warm one ([incomingIntent], see MainActivity.onNewIntent).
    // Living in one place is the fix: the extras used to be read inline in a
    // one-shot effect, so an intent delivered to an already-running activity was
    // dropped on the floor.
    //
    // Returns true when the intent named a launcher card or a reminder - a
    // destination the user just asked for, which is what makes the startup-only
    // dropped-handoff recovery below stand down. A spoken query deliberately
    // does not (it never did: the recovery could override it).
    fun applyLaunchIntentExtras(intent: Intent?): Boolean {
        val launcherType = intent?.getStringExtra(TvLauncherPublisher.EXTRA_TYPE)
        val launcherId = intent?.getStringExtra(TvLauncherPublisher.EXTRA_ID)
        val reminderChannelId =
            intent?.getStringExtra(NotificationCenter.EXTRA_REMINDER_CHANNEL_ID)
        val spokenQuery = intent?.getStringExtra(SearchSeed.EXTRA_QUERY)
        // A followed-team game reminder: open the hub. The hub is its own
        // Screen, reached from the guide's header, and this is the same
        // destination - the reminder deep link adds no new entry point.
        if (intent?.getBooleanExtra(NotificationCenter.EXTRA_OPEN_SPORTS, false) == true) {
            screen = Screen.Sports()
            return true
        }
        when (
            val route = resolveLaunchIntentRoute(
                launcherType = launcherType,
                launcherId = launcherId,
                reminderChannelId = reminderChannelId,
                spokenQuery = spokenQuery
            )
        ) {
            is LaunchIntentRoute.Channel -> {
                // A live-TV reminder tap: the reminder stores the channel but
                // not its stream URL, so hand the id to the guide, which
                // resolves it against the playlist it loads and plays it (see
                // PendingChannelTune).
                PendingChannelTune.set(route.channelId)
                screen = Screen.Guide
            }

            is LaunchIntentRoute.Title -> {
                // A launcher Watch Next card, or one of the suggestions our own
                // global-search provider contributes.
                screen = Screen.Detail(route.type, route.id, returnTo = Screen.Home)
            }

            is LaunchIntentRoute.Query -> {
                // Voice / system search (see VoiceSearchActivity): land on
                // Search with the spoken query already submitted instead of an
                // empty field. The seed is also read by the Search screen
                // itself, which covers the warm case where the view model
                // already exists.
                SearchSeed.set(route.query)
                screen = Screen.Search
            }

            null -> Unit
        }
        return !reminderChannelId.isNullOrBlank() ||
            !launcherType.isNullOrBlank() ||
            !launcherId.isNullOrBlank()
    }

    // Warm deep links: an intent delivered to the already-running activity.
    // `singleTop` means the user never sees a re-created activity here, and the
    // compose tree never re-reads `activity.intent`, so without this the
    // launcher card / suggestion / voice query / reminder tap only brought the
    // app forward.
    LaunchedEffect(incomingIntent) {
        val intent = incomingIntent ?: return@LaunchedEffect
        applyLaunchIntentExtras(intent)
        // Cleared through the activity, so the same intent cannot be routed
        // again if this shell re-enters composition.
        onIncomingIntentConsumed()
    }

    // Cold launch: the same extras off the activity's own intent, plus the
    // startup-only work - recovering a handoff dropped to process death, and
    // reconciling the TV launcher's Continue Watching rail with the in-app
    // watch history (self-healing, cheap).
    LaunchedEffect(Unit) {
        val handledByExtras =
            applyLaunchIntentExtras((context as? android.app.Activity)?.intent)

        // Safety net: if the app was killed while a next-episode handoff was
        // pending (process death between the player finishing and this
        // activity's result callback ever running), resurface the next episode
        // instead of silently landing on Home. The pending Detail.target routes
        // through the same one-shot Continue Watching autoplay path. Skipped
        // when a launcher deep link is present - that launch intent wins.
        if (!handledByExtras) {
            NextEpisodeResult.restoreIfDropped(context)?.let { pending ->
                // PendingNext carries the Stremio stream id ("tt123:S:E"), so
                // the show id is its double-colon prefix; next episodes only
                // exist for series.
                val showId = pending.streamId
                    .substringBeforeLast(':')
                    .substringBeforeLast(':')
                if (recoveredEpisodeAlreadyWatched(context, showId, pending)) {
                    // A stale handoff naming an already-watched episode: open the
                    // show instead of replaying it. Auto-playing an episode the
                    // viewer has finished is the "the binge restarted from the
                    // episode I started on" report.
                    screen = Screen.Detail(
                        type = "series",
                        id = showId,
                        returnTo = Screen.Home
                    )
                    return@LaunchedEffect
                }
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
                        resumePositionMs = 0L,
                        randomEpisodes = pending.randomEpisodes
                    ),
                    returnTo = Screen.Home
                )
                return@LaunchedEffect
            }
        }

        val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()
        val entries = runCatchingCancellable { dao.getAll() }.getOrDefault(emptyList())
        TvLauncherPublisher.sync(context, entries)
    }

    // "Back to exit" confirmation: on Home, Back opens an exit prompt instead
    // of finishing the Activity immediately, so an accidental press can't drop
    // the user out of the app. The profile picker gets the same treatment —
    // it is the app's entry screen, so Back there must EXIT, not push the
    // user into a profile. Canceling an in-flight auto-play keeps priority
    // over the prompt.
    var confirmExit by remember { mutableStateOf(false) }
    // Only the ENTRY picker (no returnTo) treats Back as exit; a picker opened
    // from inside the app belongs to its returnTo screen.
    // `screen` is a delegated property, so it cannot be smart-cast: read the
    // picker into a local once and test that. The previous form ANDed a
    // safe-cast against a second `is` check, which read as if one of them were
    // redundant when neither could be dropped.
    val profilePicker = screen as? Screen.ProfilePicker
    val interceptBack =
        (screen == Screen.Home ||
            (profilePicker != null && profilePicker.returnTo == null)) &&
            pendingAutoPlay == null

    BackHandler {
        if (interceptBack) {
            confirmExit = true
            return@BackHandler
        }
        // Backing out while "Finding sources" is up cancels the in-flight
        // resolution (the LaunchedEffect above is keyed on pendingAutoPlay) and
        // navigates back from the screen underneath.
        if (pendingAutoPlay != null) {
            pendingAutoPlay = null
        }
        screen = when (val current = screen) {

            is Screen.Addons ->
                stableBackDestination(current.returnTo)

            // A channel player backs out to whatever launched it, which every
            // launch states: the guide's own (live, catch-up and DVR alike) set
            // returnTo = Screen.Guide, and the hub's sets Screen.Sports with the
            // sheet that raised the launch. Both are explicit at their call
            // sites (see liveChannelScreen's callers), so "channel" no longer has
            // to mean "Live TV" here - which is exactly what used to send a game
            // launched from the hub to the guide with its sheet gone.
            is Screen.Player ->
                stableBackDestination(current.returnTo)

            // The channel's source picker is the same launch, and states the
            // same returnTo the player does, so it is read rather than assumed:
            // hardcoding the guide here is the other half of "backing out of a
            // game from the sports hub still sends me to the guide".
            is Screen.Streams ->
                stableBackDestination(current.returnTo)

            // Every drill-down goes through stableBackDestination for the
            // same reason Detail/Streams/Player do: it strips a one-shot
            // pending target from a Detail destination, so Back can never
            // re-fire that Detail screen's auto-play.
            is Screen.Actor ->
                stableBackDestination(current.returnTo)

            is Screen.Studio ->
                stableBackDestination(current.returnTo)

            is Screen.Decade ->
                stableBackDestination(current.returnTo)

            is Screen.Tag ->
                stableBackDestination(current.returnTo)

            // The hub is opened from the guide's own header, so Back returns
            // there rather than to Home. A game played FROM the hub is a Player
            // screen of its own and backs out to Screen.Sports with its sheet
            // (see the Player branch above), so this branch is only ever the hub
            // being left for the guide.
            is Screen.Sports ->
                Screen.Guide

            is Screen.Collection ->
                stableBackDestination(current.returnTo)

            is Screen.CatalogGrid ->
                stableBackDestination(current.returnTo)

            is Screen.KBFolder ->
                stableBackDestination(current.returnTo)

            is Screen.ProfileEdit ->
                if (current.returnTo != null) {
                    stableBackDestination(current.returnTo)
                } else {
                    // Opened from the picker's Manage tile (or restored state
                    // without a returnTo): Back belongs to the picker.
                    Screen.ProfilePicker()
                }

            // Unreachable (interceptBack routes the picker to the exit
            // prompt), but the entry screen must never navigate INTO Home —
            // kept explicit so a future else-branch reshuffle can't regress
            // "Back on Who's Watching exits the app".
            is Screen.ProfilePicker ->
                // Opened from Settings/top bar: Back belongs to that screen.
                // Entry picker: unreachable (interceptBack routes it to the
                // exit prompt) — kept explicit so it can never regress into
                // "Back on Who's Watching enters Home".
                current.returnTo ?: Screen.ProfilePicker()

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

    // Kids Mode time lock: a full-screen overlay that covers every screen
    // (home, player, settings) while the active kids profile's daily limit
    // is spent or the bedtime window is active. A parent can enter the
    // profile's PIN to unlock for this session.
    KidsTimeLockOverlay()

    // m5 (audit): nothing else may be composable as its own WINDOW while the
    // time lock is up. A `Dialog` is a separate window from the veil's, so the
    // update offer and the exit prompt sat freely above a locked screen -
    // touchable and D-pad-drivable, with their own buttons, over a veil that had
    // just swallowed every press aimed at the app behind it. The veil wins.
    val timeLocked by com.kennyb1201.kbstream.data.sync.KidsTimeGuard.state
        .collectAsStateWithLifecycle()

    // Update popup on every screen except Settings (Settings has the full
    // updater row; everywhere else the user would otherwise never know a new
    // build exists until they happen to visit Settings). Shown when the
    // launch check found a newer release that hasn't been dismissed for this
    // version. Covers Home/Search/Detail/Player/Guide/Library/Profiles.
    if (screen !is Screen.Settings && !timeLocked.locked) {
        UpdateAvailablePopup(isPlaying = screen is Screen.Player)
    }

    // The exit prompt sits before the onboarding early-return so Back is also
    // confirmed while the onboarding guide stands in for Home - but not over a
    // lock (m5): the veil already swallows Back.
    if (confirmExit && !timeLocked.locked) {
        ExitConfirmDialog(
            onDismiss = { confirmExit = false },
            onConfirm = {
                confirmExit = false
                // Latch the key guard, then finish after a settle window so
                // any in-flight/duplicate key events die inside our window
                // instead of reaching the launcher.
                val activity = context as? MainActivity
                activity?.latchExitGuard()
                searchListScope.launch {
                    delay(350)
                    activity?.finishAndRemoveTask()
                }
            }
        )
    }

    // Onboarding stands in for Home until the user finishes it — other
    // screens (Add-ons, Live TV, Simkl) render normally so the setup cards
    // genuinely hand off, and backing out to Home brings the guide back
    // until "Start Browsing" is tapped.
    if (!onboardingComplete && screen is Screen.Home) {
        // The phone gets its own onboarding. The TV page is a two-column Row
        // whose fixed 392dp account pane takes a phone's whole width (the
        // source rows were laid out past the edge with nothing to scroll to
        // them), and its fields are the D-pad KBTextField, which a tap cannot
        // get a soft keyboard out of - so onboarding was a dead end on touch.
        // The branch is here rather than inside OnboardingScreen so that file
        // stays untouched; both halves hand their completion to the same
        // OnboardingPrefs, so finishing on either form factor satisfies the
        // other.
        val finishOnboarding = {
            OnboardingPrefs.setComplete(context, true)
            onboardingComplete = true
            screen = Screen.Home
        }
        if (!FormFactor.isTv) {
            OnboardingScreenMobile(
                onOpenAddons = { screen = Screen.Addons(returnTo = Screen.Home) },
                onOpenSimkl = { screen = Screen.Simkl(returnTo = Screen.Home) },
                onOpenGuide = { screen = Screen.Guide },
                onFinish = finishOnboarding
            )
            return
        }
        OnboardingScreen(
            onOpenAddons = { screen = Screen.Addons(returnTo = Screen.Home) },
            onOpenSimkl = { screen = Screen.Simkl(returnTo = Screen.Home) },
            onOpenGuide = { screen = Screen.Guide },
            onFinish = finishOnboarding
        )
        return
    }

    // ------------------------------------------------------------------ phone nav
    //
    // The phone's five top-level destinations live in ONE persistent bottom bar,
    // hosted here rather than by any single screen. That is the whole point: a
    // bar owned by Home disappears the moment it is used, which is what the four
    // header icons did before it. The actions are the same callbacks those icons
    // called - the destinations themselves are untouched, only the way in moved.
    val navToSearch: () -> Unit = { screen = Screen.Search }
    val navToGuide: () -> Unit = { screen = Screen.Guide }
    val navToLibrary: () -> Unit = { screen = Screen.Library }
    val navToSettings: () -> Unit = { screen = Screen.Settings }
    val onMobileNavSelect: (MobileNavDestination) -> Unit = { destination ->
        when (destination) {
            // Re-tapping the destination you are already on is the standard
            // "take me back to the top", not a no-op navigation.
            MobileNavDestination.Home ->
                if (screen is Screen.Home) {
                    MobileHomeTopRequest.request()
                } else {
                    screen = Screen.Home
                }

            MobileNavDestination.Search -> navToSearch()
            MobileNavDestination.LiveTv -> navToGuide()
            MobileNavDestination.Library -> navToLibrary()
            MobileNavDestination.Settings -> navToSettings()
        }
    }
    // The bar's profile door. Same picker Home's top-bar chip opened and the
    // same PIN/exit gates; only the button moved. `returnTo` is where BACK goes
    // (the bar only draws on the five top-level screens, so it is always one of
    // them), while CHOOSING a profile always lands on Home - see the picker's
    // onSelect below, which is the single place that rule lives for every door.
    val onMobileSwitchProfile: () -> Unit = {
        screen = Screen.ProfilePicker(returnTo = screen)
    }
    // Which item is lit - and whether the bar exists at all. Derived from the
    // screen being drawn, so it cannot disagree with what is on screen (Back
    // from Search lands on Home with Home lit), and it is null on a television
    // and on every pushed screen, which is how the bar stays out of Detail, the
    // browse drill-downs, the player and the connect pages.
    val mobileNavDestination = if (FormFactor.isTv) null else mobileNavSelection(screen)

    // Screen transitions. Every navigation used to be an instant cut, which is
    // the one thing that most reliably reads as "not a finished app" on a TV —
    // the user triggers it constantly.
    //
    // Keying on the screen KIND rather than its value means navigating between
    // two Detail pages, or re-targeting one, does not replay a full-screen
    // transition; only an actual screen change does.
    // HeroTransitionHost owns the shared-element registry the poster -> Detail
    // hero flight is built on; the AnimatedContent below supplies the
    // per-screen visibility scope. Both are needed, which is why the hero
    // transition can only be constructed inside the content lambda. The host
    // skips the registry entirely under reduced motion — the flight is inert
    // there, and a lookahead scope is not free on these boxes.
    HeroTransitionHost(
        enabled = transitionMs != 0
    ) { heroScope ->
    // The phone's screens sit ABOVE the bottom bar rather than behind it: the bar
    // measures itself and the screen switch is handed the space that is left, so
    // the last rail of every top-level screen ends where the bar begins and no
    // screen needed a bottom inset of its own. (A bar drawn as an overlay is the
    // arrangement that would have needed every screen edited.) On a television
    // the bar is null and this column holds the one child it always had.
    Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
    AnimatedContent(
        targetState = screen,
        contentKey = { it.typeName() },
        transitionSpec = {
            when {
                // The player is a separate Activity that covers this one the
                // moment it starts, so there is nothing to animate — and
                // animating anyway would only keep the outgoing screen
                // composed for another 220ms. Leave that path byte-identical
                // to how it behaved before transitions existed.
                initialState is Screen.Player || targetState is Screen.Player ->
                    EnterTransition.None togetherWith ExitTransition.None

                // Forward (deeper): the new screen drifts in from the trailing
                // edge while the old one fades.
                targetState.navDepth >= initialState.navDepth ->
                    (slideInHorizontally { width -> width / 6 } + fadeIn(tween(transitionMs)))
                        .togetherWith(fadeOut(tween(transitionMs)))

                // Back: only the outgoing screen moves, so the two never look
                // like they are fighting for the same pixels.
                else ->
                    fadeIn(tween(transitionMs))
                        .togetherWith(
                            slideOutHorizontally { width -> width / 6 } +
                                fadeOut(tween(transitionMs))
                        )
            }
        },
        label = "screen"
    ) { current ->
    // See KBHeroTransition: this lambda is the only place where the shared
    // registry and the animated-visibility scope are both in hand — and it is
    // also the per-screen boundary, so the scope is published here as an
    // ambient value. A card that can open a Detail page opts in with a single
    // Modifier.heroSourceElement(type, id), the Detail hero with
    // Modifier.heroSharedElement(type, id); no screen has to grow a parameter
    // to join the flight.
    CompositionLocalProvider(
        // Null under reduced motion: HeroTransitionHost skipped the registry
        // above, so there is nothing for a call site to join.
        LocalKBHeroTransition provides heroScope?.let { scope ->
            KBHeroTransition(
                sharedScope = scope,
                visibilityScope = this,
                durationMs = transitionMs
            )
        }
    ) {
    when (current) {

        is Screen.ProfilePicker -> {
            ProfilePickerScreen(
                onSelect = {
                    // Choosing a profile ALWAYS starts it on Home, whichever
                    // door the picker was opened from - the bottom bar's
                    // avatar, Home's top-bar chip, Settings' Profiles row or
                    // the cold-launch picker. It used to hand back the screen
                    // the picker was opened on, so a switch made from Search
                    // (or Library, Live TV or Settings) dropped the viewer into
                    // the PREVIOUS profile's screen with the new profile's
                    // rails still loading under it. `returnTo` is still what
                    // Back leaves for - not choosing a profile is not a
                    // switch. HomeViewModel's observeProfileSwitches reloads
                    // all rails for the new identity.
                    screen = Screen.Home
                    // Entering Home is the same loading beat as a cold
                    // launch's - every rail is being reloaded for the new
                    // identity - so put the splash back until the rails land
                    // rather than showing a profile the viewer just chose the
                    // skeleton stack.
                    splashTarget = LaunchSplashTarget.HOME
                    splashArmed = true
                    splashArmKey += 1
                },
                onManage = {
                    screen = Screen.ProfileEdit(
                        returnTo = current.returnTo ?: Screen.ProfilePicker()
                    )
                }
            )
        }

        is Screen.ProfileEdit -> {
            ProfileEditScreen(
                editingProfileId = current.editingProfileId,
                // The phone's header arrow is a CANCEL, so it runs the same
                // branch the system back button does: back to whatever opened
                // this editor, else (the picker's Manage tile) the picker.
                onBack = {
                    screen = if (current.returnTo != null) {
                        stableBackDestination(current.returnTo)
                    } else {
                        Screen.ProfilePicker()
                    }
                },
                onDone = {
                    screen = if (current.returnTo != null) {
                        current.returnTo
                    } else {
                        Screen.ProfilePicker()
                    }
                }
            )
        }

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
                    // The key has to be the same normalized one StreamsScreen
                    // builds, or the suppression silently misses for a
                    // "tv"/"show" type and the picker auto-selects anyway.
                    val manualKey = streamNavigationKey(meta.type, target.streamId)
                    autoPlayedStreamKeys =
                        (autoPlayedStreamKeys + manualKey).distinct()

                    // Home's manual action already opens the picker directly;
                    // consume any shared marker so it cannot affect a later
                    // detail-screen Play request.
                    ManualSourceSelection.consume()

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

                // The same four actions the bottom bar's items call: a TV still
                // reaches them through its top bar, and the phone through the
                // bar, but there is one definition of where each one lands.
                onSearch = navToSearch,

                onOpenGuide = navToGuide,

                onOpenLibrary = navToLibrary,

                onOpenSettings = navToSettings,
                onSwitchProfile = {
                    // Quick switch: top-bar profile button -> picker, then
                    // back to Home with the new profile's rails loaded.
                    screen = Screen.ProfilePicker(returnTo = Screen.Home)
                },

                onOpenKBFolder = { folderId ->
                    screen = Screen.KBFolder(
                        folderId = folderId,
                        returnTo = Screen.Home
                    )
                },

                // A Browse tile on Home opens exactly what its chip opens in
                // Search, so the mirrored chip and the chip cannot drift.
                onOpenBrowseShortcut = { shortcut ->
                    browseShortcutScreen(shortcut)?.let { destination ->
                        screen = destination
                    }
                },

                onOpenCatalogGrid = { rail ->
                    screen = Screen.CatalogGrid(
                        title = rail.catalogName,
                        addonName = rail.addonName,
                        returnTo = Screen.Home
                    )
                },

                // "Manage rails" on Home's all-hidden card (HD-P2-5): the
                // manager is the Add-ons screen's dialog, so navigate there and
                // leave the one-shot request for it to pick up on arrival.
                onManageRails = {
                    com.kennyb1201.kbstream.ui.home.HomeRailManagerRequest.request()
                    screen = Screen.Addons(returnTo = Screen.Home)
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                onBack = { screen = Screen.Home },
                onOpenAddons = {
                    // Kids Mode "Lock add-ons": a kids profile with the
                    // lock on cannot reach addon management (defense in
                    // depth alongside the hidden Settings row).
                    val active =
                        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
                    if (active?.kidsMaxAge != null && active.kidsHideAddons) {
                        screen = Screen.Home
                    } else {
                        screen = Screen.Addons(returnTo = Screen.Settings)
                    }
                },
                onOpenSimkl = { screen = Screen.Simkl(returnTo = Screen.Settings) },
                onOpenTrakt = { screen = Screen.Trakt(returnTo = Screen.Settings) },
                onOpenProfiles = {
                    // Real picker so the profile can be SWITCHED here — the
                    // old wiring jumped straight into the editor, which can
                    // only rename/create profiles, never switch.
                    screen = Screen.ProfilePicker(returnTo = Screen.Settings)
                },
                // Both rail doors moved off the Add-ons header into Settings'
                // own "Collections & Catalogs" pane. The manager is still
                // Add-ons' dialog (it is wired to that screen's view model), so
                // this leaves the same one-shot request Home's "Manage rails"
                // card does and navigates to the screen that hosts it; the
                // builder is a screen of its own and opens directly.
                // M5 (audit): both of these are doors to add-on management -
                // the Collections pane handed them their own entries when the
                // rail doors moved here, and they were the two that forgot the
                // gate `onOpenAddons` enforces. On a profile with "Lock
                // add-ons" on, the Add-ons screen (and the catalogs builder
                // beside it) is exactly what must not open, or the lock is a
                // label rather than a lock. Same check, one place, both doors.
                onOpenCatalogManager = {
                    val active =
                        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
                    if (active?.kidsMaxAge != null && active.kidsHideAddons) {
                        screen = Screen.Home
                    } else {
                        com.kennyb1201.kbstream.ui.home.HomeRailManagerRequest.request()
                        screen = Screen.Addons(returnTo = Screen.Settings)
                    }
                },
                onOpenCatalogBuilder = {
                    val active =
                        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
                    if (active?.kidsMaxAge != null && active.kidsHideAddons) {
                        screen = Screen.Home
                    } else {
                        screen = Screen.CatalogBuilder(returnTo = Screen.Settings)
                    }
                },
                onOpenStats = { screen = Screen.Stats }
            )
        }

        is Screen.Stats -> {
            com.kennyb1201.kbstream.ui.settings.StatsScreen(
                onBack = { screen = Screen.Settings }
            )
        }

        is Screen.CatalogGrid -> {
            // The TV grid is a D-pad grid of focusable cards with no touch
            // scrolling; the phone gets the same pager, ViewModel and menu over
            // a finger-scrolled touch grid. Same branch rule as onboarding:
            // here, not inside CatalogGridScreen, so the TV file is untouched.
            val openGridItem: (MetaPreview) -> Unit = { meta ->
                screen = Screen.Detail(
                    meta.type,
                    meta.id,
                    returnTo = current
                )
            }
            val closeGrid = {
                screen = stableBackDestination(current.returnTo)
            }
            if (!FormFactor.isTv) {
                CatalogGridScreenMobile(
                    title = current.title,
                    addonName = current.addonName,
                    onItemClick = openGridItem,
                    onBack = closeGrid
                )
            } else {
                CatalogGridScreen(
                    title = current.title,
                    addonName = current.addonName,
                    onItemClick = openGridItem,
                    onBack = closeGrid
                )
            }
        }

        is Screen.KBFolder -> {
            KBFolderScreen(
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

            // Add-on management only: the TV header draws no back control (the
            // remote's Back is the single way out, and it follows returnTo; the
            // phone draws the shared header arrow instead), and HOME /
            // COLLECTIONS + CATALOGS are rows in Settings now.
            AddonsScreen(
                // Same pop as system back on this screen (see the BackHandler's
                // Screen.Addons branch): the add-ons page's own returnTo.
                onBack = { screen = stableBackDestination(current.returnTo) }
            )
        }

        is Screen.CatalogBuilder -> {

            com.kennyb1201.kbstream.ui.addons.CatalogBuilderScreen(
                // Opened from Settings' "Collections & Catalogs" pane, whose
                // returnTo this carries, so Back lands on whatever opened it.
                onBack = { screen = stableBackDestination(current.returnTo) }
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
                        null,
                        returnTo = Screen.Search
                    )
                },

                onCollectionClick = { collection ->
                    screen = Screen.Collection(
                        collection.id,
                        collection.name,
                        Screen.Search
                    )
                },

                onOpenTagScreen = { id, name, isKeyword, mediaType ->
                    screen = Screen.Tag(id, name, isKeyword, mediaType, Screen.Search)
                },
                onOpenStudioScreen = { id, name, isNetwork, providerId, networkOrCompanyId, networkIsCompany, originalsCompanyId ->
                    screen = Screen.Studio(
                        id,
                        name,
                        isNetwork,
                        providerId,
                        networkOrCompanyId,
                        networkIsCompany,
                        originalsCompanyId,
                        Screen.Search
                    )
                },
                onOpenCollectionScreen = { id, name ->
                    screen = Screen.Collection(id, name, Screen.Search)
                },
                onOpenDecadeScreen = { decadeStart, name ->
                    screen = Screen.Decade(decadeStart, name, Screen.Search)
                }
            )
        }

        is Screen.Trakt -> {
            TraktConnectScreen(
                onBack = { screen = stableBackDestination(current.returnTo) }
            )
        }

        is Screen.Simkl -> {

            SimklConnectScreen(
                // Same rule as the Add-ons page: the remote's Back already
                // lands on [Screen.Simkl.returnTo], and the header button now
                // lands there too instead of always jumping to Settings.
                onBack = {
                    screen = stableBackDestination(current.returnTo)
                }
            )
        }

        is Screen.Library -> {
            LibraryScreen(
                onItemClick = { mediaType, id ->
                    screen = Screen.Detail(
                        mediaType,
                        id,
                        returnTo = Screen.Library
                    )
                },
            )
        }

        is Screen.Guide -> {

            // Created on demand, like the streams picker below: the IPTV
            // ViewModel's init resolves the cached playlist (a paged read of
            // every cached channel, then fingerprint passes over the whole
            // list) and starts the guide clock. Constructing it at the app
            // root paid all of that on every launch — including every return
            // from the player — for a screen most sessions never open. The
            // activity-scoped store still hands back the same instance once
            // it exists, so Guide -> Player -> Guide keeps its state.
            val iptvViewModel: IptvViewModel = viewModel()

            GuideScreen(
                viewModel = iptvViewModel,
                // Back on the guide (including its playlist-setup form, which
                // is the whole screen before a playlist exists) returns Home.
                // The guide handles its own Back so this is reached even while
                // a field's IME has been open.
                onBack = { screen = Screen.Home },
                defaultPlaylistUrl = "",
                defaultEpgUrl = "",
                defaultPlaylistName = "Live TV",

                // The live launch itself lives in liveChannelScreen(), shared
                // with the sports hub. The guide's job here is only to hand it
                // the channel and where Back should land. (The zap lineup the
                // player reads for CH+/CH− is published by the guide itself,
                // from the group being browsed — see GuideScreen — because
                // publishing the whole visible list here is what used to make
                // UP/DOWN jump out of the group you were in.)
                onPlayChannel = { channelWithEpg ->
                    screen = liveChannelScreen(
                        channel = channelWithEpg.channel,
                        epgIconUrl = channelWithEpg.epgChannel?.iconUrl,
                        returnTo = Screen.Guide
                    )
                },

                // The sports hub: a row of leagues over the same playlist the
                // guide is showing. Back returns here, which is why the hub is
                // a sibling of the guide rather than a pane inside it.
                onOpenSports = {
                    screen = Screen.Sports()
                },

                // Catch-up (DVR): launch the recorded broadcast URL directly.
                // Not registered in the zap registry — CH+/CH− flips live
                // channels, and a DVR recording is not one of them. Back
                // returns to the guide like a live session.
                onPlayCatchup = { channelWithEpg, program ->
                    val channel = channelWithEpg.channel
                    val channelName = channel.displayName.ifBlank { "Live Channel" }
                    val programName = program.title.ifBlank { "Catch-up" }
                    // The channel icon, exactly as the live launch carries it:
                    // a DVR recording is the same channel, and the same splash
                    // loads it. See channelTitleGraphic.
                    val poster =
                        channelTitleGraphic(
                            channel.logoUrl,
                            channelWithEpg.epgChannel?.iconUrl
                        )

                    screen = Screen.Player(
                        url = program.url,
                        audioUrl = null,
                        parentId = channel.id.ifBlank { channel.streamUrl },
                        parentType = "channel",
                        season = null,
                        episode = null,
                        episodeStreamId = channel.id,
                        itemName = "$channelName — $programName",
                        itemPoster = poster,
                        clearLogoUrl = poster,
                        startPositionMs = 0L,
                        sources = listOf(
                            Stream(
                                name = programName,
                                title = programName,
                                url = program.url
                            )
                        ),
                        streamHeaders = channel.headers,
                        returnTo = Screen.Guide
                    )
                }
            )
        }

        is Screen.Sports -> {

            // The hub's own ViewModel: it owns the ESPN fetch, the 30-second
            // live tick and the playlist match, none of which the guide needs.
            // Created on demand for the same reason the guide's is - most
            // sessions never open it, and the hub's first act is a scoreboard
            // fetch plus a paged read of the cached lineup.
            SportsHubScreen(
                // The phone's header arrow lands where system back does for the
                // hub: the guide it was opened from.
                onBack = { screen = Screen.Guide },
                // Playing a game is the guide's own launch, with the hub as the
                // Back destination. There is no second player and no second
                // channel route - a card that matched a channel hands it to the
                // SAME liveChannelScreen the guide uses.
                onPlayChannels = { gameId, tournamentId, channels ->
                    // Defense in depth for kids-proof C7: the hub already matches
                    // games against the profile's VISIBLE channels, but a launch
                    // is the moment that matters, so the filter is re-applied at
                    // the one place the sports hub can start a live channel
                    // rather than trusted from the caller. A channel the guide
                    // hides cannot be started from here at all.
                    val playable = KidsLiveTvFilter.visible(channels)
                    // The head is the feed the hub's matcher chose and the rest
                    // are the backups the sheet listed; the player walks them in
                    // this order if one will not open.
                    playable.firstOrNull()?.let { primary ->
                        screen = liveChannelScreen(
                            channel = primary,
                            backups = playable.drop(1),
                            epgIconUrl = null,
                            // One of these two is the id of the sheet WATCH was
                            // pressed in; Back from the game returns here and
                            // the hub raises that sheet again.
                            returnTo = Screen.Sports(
                                detailGameId = gameId,
                                detailTournamentId = tournamentId
                            )
                        )
                    }
                },

                // The same two ids, read back on entry: a hub reached by a
                // player's Back opens on the sheet it came from. Null for every
                // other way in, which is every other hub.
                initialDetailGameId = current.detailGameId,
                initialDetailTournamentId = current.detailTournamentId,
            )
        }

        is Screen.Detail -> {

            // A pending target is a one-shot Continue Watching / up-next
            // request, so it must never be stored as a Back destination:
            // returning to that Detail screen fires its LaunchedEffect again,
            // which reopens the player and makes Back look like it did
            // nothing. Every drill-down opened from this page (actor, studio,
            // genre, streams) returns here, and keeping the poster / backdrop
            // / logo / overview is what lets it repaint instantly instead of
            // re-resolving meta.
            val stableReturnTo = current.copy(pendingTarget = null)

            DetailScreen(
                type = current.type,
                id = current.id,
                initialTarget = current.pendingTarget,
                initialPoster = current.itemPoster,
                initialBackdrop = current.itemBackdrop,
                initialClearLogo = current.itemClearLogo,
                initialOverview = current.itemOverview,

                onClearLogoResolved = { logo ->
                    resolvedDetailClearLogos["${current.type}:${current.id}"] = logo
                },

                onBackdropResolved = { backdrop ->
                    resolvedDetailBackdrops["${current.type}:${current.id}"] = backdrop
                },

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                },

                onNavigateActor = { personId ->

                    screen = Screen.Actor(personId, stableReturnTo)
                },

                onNavigateStudio = {
                        id,
                        name,
                        isNetwork ->

                    screen = Screen.Studio(
                        id,
                        name,
                        isNetwork,
                        null,
                        returnTo = stableReturnTo
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
                        stableReturnTo
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
                        ManualSourceSelection.consume()

                    // A manual pick must never auto-play, no matter how long
                    // the source fetch takes: the picker's own suppression
                    // (ManualSourceSelection.isPendingPickFor) is time-based
                    // (12s) while addon fetches are unbounded, so a slow
                    // addon's late result used to flip the picker into
                    // auto-play after it had already opened. Marking the key
                    // here gives this route the same permanent, key-based
                    // suppression the Home manual route already has (see the
                    // manualKey block above); the picker reads it as
                    // suppressAutoSelect.
                    if (manualSourceSelection) {
                        autoPlayedStreamKeys =
                            (autoPlayedStreamKeys + streamNavigationKey(
                                target.contentType,
                                target.streamId
                            )).distinct()
                    }

                    if (
                        !manualSourceSelection &&
                        AppPreferences.getAutoSelectStream(context)
                    ) {
                        // Autoselect is on: resolve sources in the background
                        // (this screen stays visible under a brief "Finding
                        // sources" overlay) and jump straight into the player —
                        // the streams picker only appears if nothing playable
                        // resolves.
                        // Fresh user-initiated play: the still-there binge
                        // watchdog tracks consecutive AUTO-advanced episodes,
                        // so a manual play restarts the count.
                        AppPreferences.resetConsecutiveAutoplays(context)
                        pendingAutoPlay = PendingPlay(
                            target = target,
                            parentId = parentId,
                            parentType = parentType,
                            itemPoster = poster,
                            backdropUrl = backdropUrl,
                            clearLogoUrl = clearLogoUrl,
                            overview = overview,
                            cast = cast,
                            // The detail route carries the season's episode
                            // count on its target; forward it so the player's
                            // own season-boundary arithmetic (and the TMDB
                            // tail clamp) works here too, not just on the
                            // routes that already set it.
                            totalEpisodesInSeason = target.totalEpisodesInSeason,
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
                // The header arrow performs the BackHandler's own pop for this
                // screen, so the browse return record behaves identically.
                onBack = { screen = stableBackDestination(current.returnTo) },

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
                current.providerId,
                current.networkOrCompanyId,
                current.networkIsCompany,
                current.originalsCompanyId,

                onBack = { screen = stableBackDestination(current.returnTo) },

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

        is Screen.Decade -> {

            DecadeScreen(
                current.decadeStart,
                current.name,

                onBack = { screen = stableBackDestination(current.returnTo) },

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

                onBack = { screen = stableBackDestination(current.returnTo) },

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

                onBack = { screen = stableBackDestination(current.returnTo) },

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
                        current.target.streamId,
                        current.target.runtimeMinutes
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
                backdropUrl = resolvedDetailBackdrops["${current.parentType}:${current.parentId}"]
                    ?: current.backdropUrl,
                // The title's TMDB id, so the picker can draw the still the
                // viewer chose for it on the Media row (see ImageOverrides).
                // The route's parent id is one of the app's own spellings
                // ("tmdb:603", a bare number), and the choices are filed under
                // the number.
                tmdbId = ImageOverrides.tmdbIdOf(current.parentId),
                // The clearlogo resolved for this title, so the picker header
                // shows the art even when the Continue Watching row had none.
                clearLogoUrl = resolvedDetailClearLogos["${current.parentType}:${current.parentId}"]
                    ?: current.clearLogoUrl,
                suppressAutoSelect =
                    streamKey in autoPlayedStreamKeys,

                onStreamSelected = {
                        stream,
                        allSources,
                        sourceAddons ->

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
                            startFromBeginning = current.target.startFromBeginning,
                            randomEpisodes = current.target.randomEpisodes,
                            // A tap in this picker is a manual pick, whatever
                            // the auto-select setting says: backing out of the
                            // playback has to bring the picker back so another
                            // source can be tried. Without the flag the
                            // auto-select rule below claims the back and drops
                            // the user on the screen behind the picker.
                            pickedFromStreams = true,
                            returnTo = stableBackDestination(current.returnTo),
                            sources = allSources,
                            sourceAddons = sourceAddons,
                            totalEpisodesInSeason =
                                current.target.totalEpisodesInSeason,
                            runtimeMinutes =
                                current.target.runtimeMinutes,
                            streamHeaders = stream.requestHeaders,
                            drmLicenseUrl = stream.drm?.licenseUrl,
                            drmHeaders = stream.drm?.headers.orEmpty()
                        )
                            // The manual pick is cached too, so carry the same
                            // invalidation handle the auto-play paths set: a
                            // hand-picked link that dies before its first frame
                            // must be able to heal itself rather than be served
                            // once more from the cache.
                            .copy(linkCacheKey = streamKey)
                        // Remember the manual pick too, so a replay of this
                        // episode within the link's lifetime skips the resolve.
                        PlayedLinkCache.remember(context, streamKey, stream, allSources)
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
                        // Belt-and-braces on the handoff: the session's own name
                        // can be an internal id when its card had none (see
                        // upNextPlayerDisplayName on the sending side). Copying
                        // it raw is what carried the id into every episode
                        // after this one.
                        displayName = upNextPlayerNameOrUnknown(
                            current.itemName,
                            !(current.backdropUrl ?: current.itemPoster).isNullOrBlank()
                        ),
                        season = next.season,
                        episode = next.episode,
                        resumePositionMs = 0L,
                        totalEpisodesInSeason = current.totalEpisodesInSeason,
                        runtimeMinutes = next.runtimeMinutes,
                        randomEpisodes = next.randomEpisodes
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
                            // The episode being handed TO, not the finished
                            // one: NextEpisodeResult carries the overview the
                            // Up Next panel resolved, and current.overview is
                            // only the fallback for a handoff that has none
                            // (panel dismissed early, TMDB miss, an older
                            // persisted string, the external engine).
                            overview = next.overview ?: current.overview,
                            cast = nextCast,
                            returnTo = current.returnTo,
                            totalEpisodesInSeason = current.totalEpisodesInSeason,
                            bingeGroup = next.bingeGroup,
                            addonName = next.addonName
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
                            overview = next.overview ?: current.overview,
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
                            val nextBingeGroup = data.getStringExtra("next_binge_group")
                            val nextAddonName = data.getStringExtra("next_addon_name")
                            val nextOverview = data.getStringExtra("next_overview")
                            val nextTarget = StreamsTarget(
                                contentType = current.parentType,
                                streamId = nextStreamId.orEmpty(),
                                title = nextTitle.orEmpty(),
                                // Same handoff guard as the persisted-next path
                                // above.
                                displayName = upNextPlayerNameOrUnknown(
                                    current.itemName,
                                    !(current.backdropUrl ?: current.itemPoster).isNullOrBlank()
                                ),
                                season = nextSeason,
                                episode = nextEpisode,
                                resumePositionMs = 0L,
                                totalEpisodesInSeason = current.totalEpisodesInSeason,
                                randomEpisodes = data.getBooleanExtra("next_random", false)
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
                                    // See the persisted branch above: the
                                    // handoff's own overview wins, the finished
                                    // episode's is only the fallback.
                                    overview = nextOverview ?: current.overview,
                                    cast = nextCast,
                                    returnTo = current.returnTo,
                                    totalEpisodesInSeason = current.totalEpisodesInSeason,
                                    bingeGroup = nextBingeGroup,
                                    addonName = nextAddonName
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
                                    overview = nextOverview ?: current.overview,
                                    cast = nextCast
                                )
                            }
                        }
                        "play_now" -> {
                            // PLAY on a recommendation follows the same rule as
                            // PLAY anywhere else (the detail page, the Up Next
                            // card): auto-select on resolves and plays the best
                            // source, off opens the source list. It used to hand
                            // over the one stream the panel had already
                            // resolved whatever the setting said - and with
                            // auto-select off there was no way to reach the
                            // other sources at all.
                            val bywType = data.getStringExtra("byw_type") ?: "movie"
                            val bywId = data.getStringExtra("byw_id").orEmpty()
                            val bywName = data.getStringExtra("byw_name").orEmpty()
                            val bywPoster = data.getStringExtra("byw_poster")
                            val bywBackdrop = data.getStringExtra("byw_backdrop")
                            // The wordmark the popup's featured strip showed for
                            // this pick. Null when the pick had no TMDB logo,
                            // which the launch below already treats as "no logo".
                            val bywClearLogo = data.getStringExtra("byw_clearlogo")
                            // A recommendation card names a SHOW, never one of
                            // its episodes, so a series pick carries no
                            // season/episode. Building a season-less stream
                            // target here asked the resolver to play an
                            // unspecified episode. A series has no single
                            // playable target: open its Detail page, which is
                            // exactly where PLAY resolves the next-unwatched or
                            // resume episode. Movies are self-contained and
                            // still follow the auto-select rule below.
                            val bywIsSeries =
                                bywType.equals("series", ignoreCase = true) ||
                                    bywType.equals("show", ignoreCase = true) ||
                                    bywType.equals("tv", ignoreCase = true)
                            if (bywIsSeries) {
                                screen = Screen.Detail(
                                    bywType,
                                    bywId,
                                    itemPoster = bywPoster,
                                    itemBackdrop = bywBackdrop,
                                    itemOverview = null,
                                    returnTo = stableBackDestination(current.returnTo)
                                )
                            } else {
                                val bywTarget = StreamsTarget(
                                    contentType = bywType,
                                    streamId = bywId,
                                    title = bywName,
                                    displayName = bywName.ifBlank { bywId },
                                    season = null,
                                    episode = null,
                                    resumePositionMs = 0L
                                )
                                if (AppPreferences.getAutoSelectStream(context)) {
                                    pendingAutoPlay = PendingPlay(
                                        target = bywTarget,
                                        parentId = bywId,
                                        parentType = bywType,
                                        itemPoster = bywPoster,
                                        backdropUrl = bywBackdrop,
                                        // Was hardcoded null, so a pick whose
                                        // strip showed a clearlogo still reached
                                        // the player with none.
                                        clearLogoUrl = bywClearLogo,
                                        overview = null,
                                        cast = emptyList(),
                                        returnTo = stableBackDestination(current.returnTo)
                                    )
                                } else {
                                    // With auto-select off the pick goes on to
                                    // the source list, so the clearlogo rides
                                    // along and both the list's header and the
                                    // player the list launches show what the
                                    // strip showed - the same way the picker is
                                    // opened from every other screen.
                                    screen = Screen.Streams(
                                        target = bywTarget,
                                        parentId = bywId,
                                        returnTo = stableBackDestination(current.returnTo),
                                        parentType = bywType,
                                        itemPoster = bywPoster,
                                        backdropUrl = bywBackdrop,
                                        clearLogoUrl = bywClearLogo
                                    )
                                }
                            }
                        }

                        "go_details" -> {
                            val bywType = data.getStringExtra("byw_type") ?: "movie"
                            val bywId = data.getStringExtra("byw_id").orEmpty()
                            val bywName = data.getStringExtra("byw_name").orEmpty()
                            val bywPoster = data.getStringExtra("byw_poster")
                            val bywBackdrop = data.getStringExtra("byw_backdrop")
                            screen = Screen.Detail(
                                bywType,
                                bywId,
                                itemPoster = bywPoster,
                                itemBackdrop = bywBackdrop,
                                itemOverview = null,
                                returnTo = stableBackDestination(current.returnTo)
                            )
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
                        // A channel always backs out to its launch's own
                        // destination, because every channel launch states one:
                        // the guide's sets Screen.Guide, the hub's sets
                        // Screen.Sports with the sheet that raised it. Assuming
                        // the guide for anything typed "channel" is what used to
                        // drop a game's sheet on the way back; see
                        // liveChannelScreen's callers. Everything else keeps the
                        // auto-select rule below it.
                        // Auto-select only claims the back for a playback this
                        // app started on its own. A pick made by hand in the
                        // streams picker (pickedFromStreams) keeps the picker in
                        // the chain even with auto-select on, so the next source
                        // is one tap away instead of a re-navigation from Home.
                        // The picker's own onStreamSelected is the only launch
                        // that sets the flag.
                        if (
                            current.parentType == "channel" ||
                            (AppPreferences.getAutoSelectStream(context) &&
                                !current.pickedFromStreams)
                        ) {
                            screen = stableBackDestination(current.returnTo)
                        } else {
                            // Auto-select is off, or the source was picked by
                            // hand in the picker: the user is choosing sources,
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
                                    // The picker restore carries the session's
                                    // name back, so it needs the same guard: a
                                    // name that arrived here as an id must not
                                    // ride out again.
                                    displayName = upNextPlayerNameOrUnknown(
                                        current.itemName,
                                        !(current.backdropUrl ?: current.itemPoster).isNullOrBlank()
                                    ),
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
                // Settings → Playback engine. "MPV" opens the backup player
                // directly; the default keeps ExoPlayer here and lets it fall
                // over to MPV by itself when a stream is unplayable (see
                // NativePlayerActivity.handOffToMpv). Both take the same
                // extras, so nothing below has to know which one it got.
                // "External player" hands the stream to an installed video app
                // while THIS app keeps the session (see ExternalPlayerActivity).
                // DRM stays in-app whatever the setting says: the license is
                // ours to request, so another app handed the URL alone could
                // not play it. The external wrapper says the same thing on a
                // card; deciding it here just skips the detour.
                val playerActivity = when {
                    PlayerEngine.prefersExternal(context) && current.drmLicenseUrl == null ->
                        ExternalPlayerActivity::class.java

                    PlayerEngine.prefersMpv(context) ->
                        MpvPlayerActivity::class.java

                    else -> NativePlayerActivity::class.java
                }
                val intent = Intent(context, playerActivity).apply {
                    putExtra("stream_url", current.url)
                    current.audioUrl?.let { putExtra("audio_url", it) }
                    putExtra("parent_id", current.parentId)
                    putExtra("parent_type", current.parentType)
                    // The profile this session belongs to, pinned at launch: a
                    // profile switch during playback (PiP makes that routine)
                    // must not re-file this session's progress under the new
                    // profile's Continue Watching. See PlaybackHistoryWriter.
                    PlaybackHistoryWriter.profileIdForNewSession(context)?.let {
                        putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, it)
                    }
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
                    putExtra("from_beginning", current.startFromBeginning)
                    current.linkCacheKey?.let { putExtra("played_link_key", it) }
                    putExtra("random_episodes", current.randomEpisodes)
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
                            // Per-source HTTP headers, which the payload used to
                            // drop on the floor: a source that needs a Referer
                            // or a token lost it the moment it was anything but
                            // the one playing (see Stream.requestHeaders).
                            stream.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
                                put("headers", JSONObject(headers as Map<*, *>))
                            }
                            stream.bingeGroup?.let { put("bingeGroup", it) }
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
                    // The add-on behind each source, parallel to sources_json:
                    // the players skip an add-on whose links are dead for the
                    // session when the viewer (or the open-failure ladder) asks
                    // for the next source (see SourceAddonSession).
                    putExtra("source_addons", sourceAddonsJson(current.sourceAddons))
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
                    // The runtime, for the external engine: it cannot see a
                    // playhead it does not own, so the TMDB runtime is the only
                    // duration it has when the other app reports none - and a
                    // duration is what makes a saved position mean anything to
                    // Continue Watching. Ignored by the two in-app engines.
                    current.runtimeMinutes?.let { putExtra("runtime_minutes", it) }
                }
                playerResultLauncher.launch(intent)
            }
        }
    }
    } // closes CompositionLocalProvider( LocalKBHeroTransition provides … )

    // Resolve a clearlogo AND the still for the title on screen when the
    // navigation meta carried neither. DetailScreen reports its own resolved art
    // (onClearLogoResolved / onBackdropResolved above); this covers the route
    // that never opens Detail - the Continue Watching picker (autoplay OFF /
    // "Play Manually") - whose history row usually has no logo and no backdrop,
    // so its header showed the plain name and the player it launched showed a
    // bare splash while every other surface had the art. One fetch answers
    // both: the same TMDB record carries the wordmark and the still. Stored in
    // the same maps the cover splash reads.
    LaunchedEffect(current) {
        val streamsScreen = current as? Screen.Streams ?: return@LaunchedEffect
        val key = "${streamsScreen.parentType}:${streamsScreen.parentId}"
        if (streamsScreen.parentId.isBlank()) return@LaunchedEffect
        // Each half is asked for separately, so a route that has one already
        // still gets the other - and so this never re-fetches a title that has
        // both.
        val needsLogo = !resolvedDetailClearLogos.containsKey(key)
        val needsBackdrop = !resolvedDetailBackdrops.containsKey(key)
        if (!needsLogo && !needsBackdrop) return@LaunchedEffect
        val detail =
            runCatchingCancellable {
                TmdbRepository.getInstance(context)
                    .fetchEnrichedMetaCached(
                        imdbId = streamsScreen.parentId,
                        type = streamsScreen.parentType.ifBlank { "movie" }
                    )
            }.getOrNull() ?: return@LaunchedEffect
        if (needsLogo) {
            detail.bestLogoPath()?.let(::tmdbImageOriginal)?.let {
                resolvedDetailClearLogos[key] = it
            }
        }
        if (needsBackdrop) {
            detail.backdropPath
                ?.let { TmdbRepository.fullResBackdropUrl(TmdbRepository.BACKDROP_BASE + it) }
                ?.let { resolvedDetailBackdrops[key] = it }
        }
    }

    // Loading splash while a pre-playback hand-off is in flight.
    //
    // Two shapes, ONE screen:
    //  - a Continue Watching / Up Next Detail deep link, which exists only to
    //    hand the player its art. It is covered from the moment the screen
    //    appears, not just while its metadata loads, so the resolved detail
    //    page never paints for a frame between the splash and playback — that
    //    frame is exactly the flash this removes.
    //  - the autoselect resolution over whatever screen is already showing
    //    (the detail page's own Play press, an autoplay next episode).
    //
    // Both render the same AutoPlayLoadSplash, so the tap reads as one
    // unbroken tap -> splash -> playback.
    val detailAutoPlay = (current as? Screen.Detail)?.takeIf { detail ->
        val pending = detail.pendingTarget
        pending != null &&
            AppPreferences.getAutoSelectStream(context) &&
            (
                detail.type == "series" ||
                    pending.startFromBeginning ||
                    pending.resumePositionMs > 0L
                )
    }
    val pending = pendingAutoPlay
    val playerSplash = current as? Screen.Player

    // The phone holds the playback orientation for exactly the lifetime of the
    // pre-playback splash above.
    //
    // This is the app's OWN window, not a player's: a phone's MainActivity
    // follows the device, so held upright it is portrait and the splash used to
    // come up vertical - only rotating when the player activity, which asks for
    // landscape before its window exists, arrived on top and covered it. Source
    // resolution takes seconds, so that was a visible flip mid-load.
    //
    // Keyed on the splash's own visibility, so it is the SAME state change that
    // paints the splash and no tap can strand a request: a successful hand-off
    // (the player sets its own request, so the screen is already landscape and
    // there is no portrait bounce), a back/cancel out of the splash, and a
    // resolve that gives up all drop the hold the moment the splash goes. On TV
    // both helpers return before touching the activity, so the TV flow is the
    // one it has always been.
    val splashShowing = detailAutoPlay != null || pending != null || playerSplash != null
    val splashActivity = LocalContext.current as? Activity
    DisposableEffect(splashActivity, splashShowing) {
        if (splashActivity != null && splashShowing) {
            FormFactor.applyPlaybackOrientation(splashActivity)
        }
        onDispose {
            if (splashActivity != null && splashShowing) {
                FormFactor.releasePlaybackOrientation(splashActivity)
            }
        }
    }

    when {
        detailAutoPlay != null -> AutoPlayLoadSplash(
            // Detail's own resolved still first, the deep link's own (usually
            // absent) one second. This is the branch a Continue Watching resume
            // paints, and the one that used to come up empty: its backdrop came
            // only from the history row's meta, which carries a poster.
            backdropUrl = resolvedDetailBackdrops["${detailAutoPlay.type}:${detailAutoPlay.id}"]
                ?: detailAutoPlay.itemBackdrop,
            // The logo Detail has resolved for this title, so the cover splash
            // shows the pulsing clearlogo too - not just the plain name it had
            // when the deep link carried no logo of its own.
            clearLogoUrl = resolvedDetailClearLogos["${detailAutoPlay.type}:${detailAutoPlay.id}"]
                ?: detailAutoPlay.itemClearLogo,
            title = detailAutoPlay.pendingTarget?.displayName.orEmpty()
                .ifBlank { detailAutoPlay.id },
            subtitle = "Finding sources…",
            tmdbId = splashTmdbId(detailAutoPlay.type, detailAutoPlay.id)
        )

        pending != null -> AutoPlayLoadSplash(
            // The hand-off's own still, else the one Detail resolved for this
            // title - the same map the cover above reads, so a launch that
            // reached here without Detail's art still shows it.
            backdropUrl = pending.backdropUrl
                ?: resolvedDetailBackdrops["${pending.parentType}:${pending.parentId}"],
            clearLogoUrl = pending.clearLogoUrl,
            title = pending.target.displayName,
            subtitle = "Finding sources…",
            tmdbId = splashTmdbId(pending.parentType, pending.parentId)
        )

        // The PLAYER screen composes nothing visible of its own (it only
        // starts the player Activity). Left bare, the cover dropped here and
        // the window showed its own background for a frame - the black flash
        // between the "Finding sources" overlay and the player's own first
        // load, which is what made it read as two splashes. Painting the SAME
        // cover from the player screen's own art/logo keeps it continuous
        // until the player Activity is on top. No "Finding sources..." line:
        // the sources ARE found by the time we hand off, and this matches the
        // player's own splash, which never carries one.
        playerSplash != null -> AutoPlayLoadSplash(
            backdropUrl = playerSplash.backdropUrl,
            clearLogoUrl = playerSplash.clearLogoUrl,
            title = playerSplash.itemName,
            subtitle = null,
            tmdbId = splashTmdbId(playerSplash.parentType, playerSplash.parentId)
        )
    } // closes the splash picker's `when`
    } // closes AnimatedContent( targetState = screen ) { current -> ... }

    // Emitted LAST - after the screen-transition container, so it is the
    // topmost child of the shell's Box and a screen can never be seen
    // composing underneath it. Purely visual: no focus, no D-pad keys and no
    // Back interception (see LaunchSplash), so the screen below keeps the
    // input and every press survives the handover.
    if (splashVisible) {
        LaunchSplash()
    }
    // The Box closes AFTER the splash, because the splash is its last child.
    // Closing it a line early - which is what this used to do - left the
    // splash and the bottom bar as children of the column's PARENT instead:
    // the shell is a stacking host there, so the bar drew across the top of
    // the window rather than at the bottom of it, and the launch splash took
    // a layout slot instead of covering the screen.
    } // closes the screen Box above the bottom bar

    // The bar, on the phone's five top-level destinations and nowhere else.
    // It is a CHILD of this column and the column's last one: the screen above
    // holds `weight(1f)`, so the bar takes its own height off the bottom of
    // the window instead of being stacked over the screen. Emitting it outside
    // this column is what drew the five items along the top of the display.
    mobileNavDestination?.let { destination ->
        MobileBottomNav(
            selected = destination,
            onSelect = onMobileNavSelect,
            onSwitchProfile = onMobileSwitchProfile
        )
    }
    } // closes the shell Column - after the bar, so the bar is inside it
    } // closes HeroTransitionHost
    } // closes AppRoot
}

@Composable
private fun KidsTimeLockOverlay() {
    val guard = com.kennyb1201.kbstream.data.sync.KidsTimeGuard
    val lockState by guard.state.collectAsStateWithLifecycle()

    val profile = com.kennyb1201.kbstream.data.sync.ProfileManager.profiles
        .collectAsStateWithLifecycle().value
        .firstOrNull { it.id == lockState.profileId }

    var pin by remember(lockState.profileId) { mutableStateOf("") }
    var pinError by remember(lockState.profileId) { mutableStateOf(false) }

    if (!lockState.locked) return

    LaunchedEffect(pin, profile) {
        if (pin.length == 4 && profile != null) {
            if (guard.overrideForSession(profile, pin)) {
                pin = ""
                pinError = false
            } else {
                pinError = true
                pin = ""
            }
        }
    }

    // The lock has to TAKE the input, not just paint over it. It used to be a
    // plain `Box` with a background and nothing else, so when the lock engaged
    // the previously focused control UNDERNEATH kept focus: D-pad presses went
    // on driving the covered screen (blind navigation, opening screens and
    // settings) and taps passed straight through. The only focusable node the
    // overlay had was the parent-PIN field, which nothing ever requested — so a
    // parent picking up the remote had to D-pad-hunt from a hidden node to
    // reach it. Playback itself is still ended by `KidsTimeGuard.enforceLock`
    // in the player Activities; this is the shell's half.
    // A field whenever ANY credential can answer it: the locked profile's own
    // PIN, or the app-level parent PIN (see KidsTimeGuard.setParentPin). The
    // copy below is the same for both on purpose - which one answers is the
    // parent's business, not something the veil should announce to the child
    // holding the remote.
    val hasPinField = profile != null &&
        (
            com.kennyb1201.kbstream.data.sync.ProfileManager.hasPin(profile) ||
                guard.hasParentPin()
            )
    val lockRoot = remember { FocusRequester() }
    val pinField = remember { FocusRequester() }
    // Whether the OVERLAY ITSELF holds focus (the no-PIN case), which is what
    // decides if the key handler below swallows the press. It must not swallow
    // while the PIN field has focus, or the digits would never reach it.
    var lockRootFocused by remember { mutableStateOf(false) }

    // The four D-pad directions are swallowed in the PREVIEW phase, before the
    // focused node can act on them.
    //
    // Consuming them in the bubble phase (below) was not enough: Compose's
    // default directional navigation runs on KeyDown as the key travels to the
    // focused node, and a focus move CONSUMES the event - so the bubble-phase
    // handler never saw the press it was meant to stop. Same story with a PIN
    // on screen: KBTextField moves focus Down/Up in ITS preview handler, which
    // is inner to this one, so it ran first too. Nothing scopes this overlay
    // (no focusGroup, no trap) and focus search is purely geometric - it knows
    // nothing about the veil - so the press landed on a Home or Settings tile
    // composed UNDERNEATH, and the kid could then drive the covered screen
    // blind to the profile picker, switch profiles, and so unlock the very
    // profile the veil exists to hold. The preview handler runs before every
    // other node on the path, including the field's own, so the move can no
    // longer happen at all. Focus stays where the overlay put it (the field, or
    // the lock root) until the lock ends.
    val isVeilDirectionKey: (Key) -> Boolean = { key ->
        key == Key.DirectionUp || key == Key.DirectionDown ||
            key == Key.DirectionLeft || key == Key.DirectionRight
    }
    // The parameter type is named in full: this file also imports
    // android.view.KeyEvent for the platform handlers, and an inferred
    // parameter binds to that one.
    val veilKeys = Modifier.onPreviewKeyEvent { event: ComposeKeyEvent ->
        event.type == KeyEventType.KeyDown && isVeilDirectionKey(event.key)
    }
    LaunchedEffect(lockState.locked, hasPinField) {
        // Verified retry: `requestFocus()` returns false for a node that has not
        // been laid out yet, and this effect can run before the overlay's first
        // layout pass.
        landFocusOn(if (hasPinField) pinField else lockRoot)
    }

    // Back must not walk out from under the veil either (the screen underneath
    // would pop to Home). The only ways out are the PIN or the lock expiring.
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.97f))
            // The trap: see [veilKeys]. Outer-most on the chain, so its preview
            // pass is the first thing a D-pad press meets.
            .then(veilKeys)
            // Focus lands here when the profile has no PIN (nothing else in the
            // overlay can take it), and the key handler swallows whatever the
            // focused child did not consume, so nothing behind the veil can be
            // driven. The tap handler does the same for touch, which used to
            // pass through the non-consuming Box. Directional keys never reach
            // here: they are swallowed in the preview phase above, which is what
            // actually keeps focus inside the veil.
            //
            // M11 (audit): a locked kids profile with NO pin used to have no way
            // out at all - Back is swallowed, everything is covered, and the
            // only exits were the PIN override or the window expiring. Kenny's
            // call (2026-10-18) was one app-level PARENT PIN, set once and asked
            // only here, so a lock on a PIN-less profile now has a door a kid
            // cannot open (see KidsTimeGuard.setParentPin and overrideForSession)
            // while profile switching stays PIN-free for daily use. A profile
            // with neither credential is still deliberately without an exit -
            // any escape hatch for a profile with no credential is a bypass a kid
            // can use - and the editor now refuses to create that state (see the
            // save gate in ProfileEditScreen).
            .focusRequester(lockRoot)
            .focusable()
            .onFocusChanged { state ->
                lockRootFocused = state.isFocused
                // With a PIN on screen the field is the only place input can
                // go; the root must not become a dead end a D-pad press can
                // fall into (it would then swallow the digits).
                if (state.isFocused && hasPinField) {
                    runCatching { pinField.requestFocus() }
                }
            }
            .onKeyEvent { lockRootFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (lockState.reason) {
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.BEDTIME ->
                        "TIME FOR BED"
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.CLOCK ->
                        "CLOCK CHANGED"
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.UNKNOWN ->
                        "LOCKED"
                    else -> "WATCH TIME IS DONE"
                },
                color = KBAccent,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (lockState.reason) {
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.BEDTIME ->
                        "${lockState.profileName ?: "This profile"} is resting until 4:00 AM."
                    // M3: the device clock moved without time passing, so the
                    // limits cannot be trusted; the lock holds until it is put
                    // back (and lifts with the next day).
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.CLOCK ->
                        "${lockState.profileName ?: "This profile"} is locked while " +
                            "this device's clock has been changed. Put the time back " +
                            "to use it."
                    // m13: the guard failed closed rather than leaving the
                    // profile open, so the copy must not claim a limit was hit.
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.UNKNOWN ->
                        "${lockState.profileName ?: "This profile"} is locked while " +
                            "its time limit is checked. Ask a parent to open Settings."
                    else ->
                        "${lockState.profileName ?: "This profile"} used its " +
                            "${lockState.dailyLimitMinutes} min today. Come back tomorrow!"
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp)
            )

            if (profile != null && hasPinField) {
                Text(
                    text = "Parent PIN to unlock",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 26.dp)
                )
                KBTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(4)
                        pinError = false
                    },
                    placeholder = "••••",
                    focusRequester = pinField,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keepFocusOnDone = true
                )
                if (pinError) {
                    Text(
                        text = "Wrong PIN — try again",
                        color = KBDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            } else {
                Text(
                    text = "A parent can remove the limit in profile settings.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 26.dp)
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailablePopup(isPlaying: Boolean) {
    val context = LocalContext.current
    val updateState by AppUpdater.state.collectAsStateWithLifecycle()

    // Never interrupt playback (movies, series, IPTV catch-up all run through
    // the fullscreen player activity). While something plays the popup stays
    // down - and for 10s after playback ends, so backing out of a video
    // doesn't land you face-first in an update dialog either.
    var everPlayed by remember { mutableStateOf(false) }
    var suppressForPlayback by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            everPlayed = true
            suppressForPlayback = true
        } else if (everPlayed) {
            @Suppress("OPT_IN_USAGE")
            delay(10_000)
            suppressForPlayback = false
        }
    }

    // Fire the launch check once per composition entry (state stays Idle when
    // Application.onCreate's 12h throttle skipped - a new process still gets
    // one check, so reopening the app surfaces fresh releases).
    LaunchedEffect(Unit) {
        AppUpdater.checkOnLaunch(context)
    }

    // "Later" hides the dialog immediately and (via prefs) for this specific
    // version, so it doesn't re-nag on every launch. A NEW versionCode (a
    // release published while the app is open, or a late-landing check)
    // re-arms the popup.
    val available = updateState as? AppUpdater.UpdateState.Available
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(available?.versionCode) {
        if (available != null) {
            hidden = !AppUpdater.isPopupEligible(context)
        }
    }

    // Once the user has said yes, every later phase has to keep reporting: the
    // download percentage and the installer handoff are the only answer to
    // "is it doing anything?", and an install that lands is announced on the
    // launch AFTER it (AppUpdater.confirmInstallOnLaunch), so this dialog is
    // often the first thing the relaunched app draws. The dismiss gate and the
    // playback guard exist to soften an *uninvited* offer, so neither applies
    // past the tap.
    var installStarted by remember { mutableStateOf(false) }
    LaunchedEffect(updateState) {
        when (updateState) {
            is AppUpdater.UpdateState.Downloading,
            is AppUpdater.UpdateState.ReadyToInstall -> installStarted = true
            else -> Unit
        }
    }

    val downloading = updateState as? AppUpdater.UpdateState.Downloading
    val installing = updateState as? AppUpdater.UpdateState.ReadyToInstall
    val installed = updateState as? AppUpdater.UpdateState.Updated
    val failed = updateState as? AppUpdater.UpdateState.Failed
    // A failure only reaches the viewer when it is one they caused: a
    // launch-time check that cannot reach GitHub is reported in Settings, in
    // the row it belongs to. Throwing a dialog at someone who pressed nothing
    // is what this filter is for.
    val phase = when {
        downloading != null -> UpdatePhase.Downloading
        installing != null -> UpdatePhase.Installing
        installed != null -> UpdatePhase.Installed
        installStarted && failed != null -> UpdatePhase.Failed
        available != null && !hidden && !suppressForPlayback -> UpdatePhase.Offer
        else -> null
    } ?: return

    // A phone draws this dialog with a finger, a television with a D-pad. The
    // report this branch answers: INSTALL/LATER could not be TAPPED (they were
    // built from the TV card, whose clicks are focus-driven) and Back did
    // nothing (`onDismissRequest = { }` swallowed it), so the popup was a dead
    // end that also re-armed on the next launch. TV keeps every bit of the old
    // behaviour, including the barrier.
    val mobile = !FormFactor.isTv

    // Back and a scrim tap mean "not now", never "abandon a download". Each
    // phase answers with the button it already has, so a dismissal can never
    // do something the UI does not offer:
    //  - Offer: exactly LATER, i.e. a per-version dismissal that survives a
    //    relaunch (the reason force-stopping did not help before was that the
    //    offer was never dismissed, only escaped from).
    //  - Installed / Failed: acknowledge(), the DONE and CLOSE actions.
    //  - Downloading / Installing: a no-op - the download must not be
    //    orphaned, and Installing still has its HIDE button.
    // On TV the dialog stays a barrier: its buttons work and Back does nothing.
    val onDismiss: () -> Unit = if (mobile) {
        {
            when (phase) {
                UpdatePhase.Offer -> available?.let { offer ->
                    hidden = true
                    AppUpdater.dismissPopup(context, offer)
                }
                UpdatePhase.Installed, UpdatePhase.Failed -> AppUpdater.acknowledge()
                UpdatePhase.Downloading, UpdatePhase.Installing -> Unit
            }
        }
    } else {
        {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                // A portrait phone is narrower than the television panel, so the
                // 560dp is a CAP here rather than a width. `widthIn` comes first
                // on purpose: `fillMaxWidth` would otherwise claim the screen's
                // full width and the cap could never bite. The 16dp is the outer
                // gutter, so it sits before the background.
                .then(
                    if (mobile) {
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    } else {
                        Modifier.width(560.dp)
                    }
                )
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            when (phase) {
                UpdatePhase.Offer -> available?.let { offer ->
                    UpdateDialogTitle("UPDATE AVAILABLE")
                    UpdateDialogBody(
                        "KBStream ${offer.versionName} (build ${offer.versionCode}) " +
                            "is ready to install."
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        DialogPlate(mobile = mobile, onClick = {
                            // No dismissPopup here: if the download/install
                            // fails the popup must re-offer this version on
                            // the next launch.
                            hidden = true
                            AppUpdater.downloadAndInstall(context, offer)
                        }) {
                            UpdateDialogButton("INSTALL NOW", KBAccent, bold = true)
                        }
                        DialogPlate(mobile = mobile, onClick = {
                            hidden = true
                            AppUpdater.dismissPopup(context, offer)
                        }) {
                            UpdateDialogButton("LATER", KBTextHi)
                        }
                    }
                    UpdateDialogFootnote(
                        "Downloads in the background - the progress stays on this " +
                            "screen, and Android's installer opens when it's ready."
                    )
                }

                UpdatePhase.Downloading -> downloading?.let { s ->
                    UpdateDialogTitle("DOWNLOADING UPDATE")
                    UpdateDialogBody(
                        "KBStream ${s.versionName} (build ${s.versionCode}) - ${s.percent}%"
                    )
                    UpdateProgressBar(s.percent / 100f)
                    Text(
                        text = updateBytesLabel(s),
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    UpdateDialogFootnote(
                        "Leave KBStream open. Android's installer opens by itself " +
                            "when the download finishes."
                    )
                }

                UpdatePhase.Installing -> installing?.let { s ->
                    UpdateDialogTitle("INSTALLING UPDATE")
                    UpdateDialogBody(
                        "Android is replacing KBStream with " +
                            "${s.versionName} (build ${s.versionCode})."
                    )
                    UpdateProgressBar(1f)
                    UpdateDialogFootnote(
                        "Confirm Android's prompt if it appears. KBStream restarts " +
                            "itself when the install finishes."
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        DialogPlate(mobile = mobile, onClick = { AppUpdater.acknowledge() }) {
                            UpdateDialogButton("HIDE", KBTextHi)
                        }
                    }
                }

                UpdatePhase.Installed -> installed?.let { s ->
                    UpdateDialogTitle("UPDATE INSTALLED")
                    UpdateDialogBody(
                        "KBStream ${s.versionName} (build ${s.versionCode}) is " +
                            "installed and running."
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        DialogPlate(mobile = mobile, onClick = { AppUpdater.acknowledge() }) {
                            UpdateDialogButton("DONE", KBAccent, bold = true)
                        }
                    }
                }

                UpdatePhase.Failed -> failed?.let { s ->
                    UpdateDialogTitle("UPDATE FAILED")
                    UpdateDialogBody(s.message)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        DialogPlate(mobile = mobile, onClick = {
                            // Un-hide first: the dismissed flag is keyed to a
                            // versionCode that has not changed, so the retry's
                            // fresh offer would otherwise stay hidden.
                            hidden = false
                            AppUpdater.acknowledge()
                            AppUpdater.checkForUpdate(context)
                        }) {
                            UpdateDialogButton("TRY AGAIN", KBAccent, bold = true)
                        }
                        DialogPlate(mobile = mobile, onClick = { AppUpdater.acknowledge() }) {
                            UpdateDialogButton("CLOSE", KBTextHi)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The destination a Home Browse tile opens.
 *
 * Deliberately one function for both callers: the Search screen's browse
 * browser routes its own chips through the equivalent `when` (see
 * SearchViewModel.onBrowseEntryClicked), and a mirrored chip has to land on
 * the same screen or the whole point of the shelf - reaching the browser's
 * doors without walking the browser - is lost to a copy that drifts.
 *
 * Return is Home, since that is where the tile was pressed. A category with no
 * screen of its own yields null and the press does nothing, rather than
 * navigating somewhere arbitrary.
 */
private fun browseShortcutScreen(
    shortcut: com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
): Screen? = when (shortcut.categoryKey) {
    "genres" -> Screen.Tag(shortcut.id, shortcut.name, false, "movie", Screen.Home)
    "keywords" -> Screen.Tag(shortcut.id, shortcut.name, true, "movie", Screen.Home)
    // Services carry their watch-provider id so their screen runs movies +
    // series rails; a plain network entry has none, so it runs the network
    // page. isNetwork follows the id space, as it does in Browse.
    "services" -> Screen.Studio(
        shortcut.id,
        shortcut.name,
        !shortcut.networkIsCompany,
        shortcut.providerId,
        shortcut.networkOrCompanyId,
        shortcut.networkIsCompany,
        shortcut.originalsCompanyId,
        Screen.Home
    )
    "studios" -> Screen.Studio(
        shortcut.id,
        shortcut.name,
        false,
        null,
        null,
        false,
        null,
        Screen.Home
    )
    "collections" -> Screen.Collection(shortcut.id, shortcut.name, Screen.Home)
    "decades" -> Screen.Decade(shortcut.id, shortcut.name, Screen.Home)
    else -> null
}

/** Which face the update dialog is showing. */
private enum class UpdatePhase { Offer, Downloading, Installing, Installed, Failed }

@Composable
private fun UpdateDialogTitle(text: String) {
    Text(
        text = text,
        color = KBAccent,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun UpdateDialogBody(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun UpdateDialogFootnote(text: String) {
    Text(
        text = text,
        color = KBTextLo.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 14.dp)
    )
}

/**
 * One button plate in the app's two shared dialogs (the update prompt and the
 * exit prompt).
 *
 * Both dialogs are shared code, so their buttons used to be the TV [KBCard] on
 * every device: a focus surface that takes Select/Enter from a remote and
 * nothing at all from a finger, which is why INSTALL, LATER, STAY and EXIT could
 * not be tapped on a phone. Mobile gets [TouchKBCard] - same plate, same labels,
 * same clicks - and a television keeps exactly what it had.
 */
@Composable
private fun DialogPlate(
    mobile: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    if (mobile) {
        TouchKBCard(onClick = onClick) { content() }
    } else {
        KBCard(onClick = onClick) { content() }
    }
}

@Composable
private fun UpdateDialogButton(text: String, color: Color, bold: Boolean = false) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (bold) FontWeight.Bold else null,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/**
 * Download progress bar. Hand-rolled rather than Material's: a download is the
 * only countable operation the app shows, and the bar has to take the TV
 * theme's own accent and surface instead of a phone-shaped component's.
 */
@Composable
private fun UpdateProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(6.dp)
            .background(KBSurfaceRaised, KBShapePill)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtMost(1f))
                    .fillMaxHeight()
                    .background(KBAccent, KBShapePill)
            )
        }
    }
}

/**
 * "53.2 MB of 126.4 MB", or just the running total when the server sent no
 * Content-Length and there is no denominator to quote.
 */
private fun updateBytesLabel(s: AppUpdater.UpdateState.Downloading): String =
    if (s.totalBytes > 0) {
        "${updateMb(s.downloadedBytes)} of ${updateMb(s.totalBytes)}"
    } else {
        updateMb(s.downloadedBytes)
    }

private fun updateMb(bytes: Long): String =
    if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0)
    else "${bytes / 1024} KB"

@Composable
private fun ExitConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // The second shared dialog with the same problem the update prompt had: a
    // TV card is a focus surface, so STAY and EXIT could not be TAPPED on a
    // phone. Back already works here (onDismissRequest is wired), which is why
    // this one was annoying rather than a trap - the fix still belongs with the
    // other one. The panel width gets the same phone treatment: 620dp is a cap
    // on a phone and a fixed width on a television.
    val mobile = !FormFactor.isTv

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .then(
                    if (mobile) {
                        Modifier
                            .widthIn(max = 620.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    } else {
                        Modifier.width(620.dp)
                    }
                )
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "EXIT KBSTREAM?",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Are you sure you want to exit?",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                ExitDialogButton(label = "STAY", mobile = mobile, onClick = onDismiss)
                ExitDialogButton(label = "EXIT", mobile = mobile, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun ExitDialogButton(
    label: String,
    mobile: Boolean,
    onClick: () -> Unit
) {
    DialogPlate(mobile = mobile, onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
