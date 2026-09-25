package com.kennyb1201.kbstream

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.domain.streamengine.BingeGroupResolver
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.update.AppUpdater
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.actor.ActorScreen
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.collection.CollectionScreen
import com.kennyb1201.kbstream.ui.components.AutoPlayLoadSplash
import com.kennyb1201.kbstream.ui.components.KBFeedbackHost
import com.kennyb1201.kbstream.ui.components.KBHeroTransition
import com.kennyb1201.kbstream.ui.components.LocalKBHeroTransition
import com.kennyb1201.kbstream.ui.components.LocalKBFeedback
import com.kennyb1201.kbstream.ui.components.rememberKBFeedbackState
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.detail.DetailScreen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.CatalogGridScreen
import com.kennyb1201.kbstream.ui.profiles.ProfileEditScreen
import com.kennyb1201.kbstream.ui.profiles.ProfilePickerScreen
import com.kennyb1201.kbstream.ui.home.HomeScreen
import com.kennyb1201.kbstream.data.iptv.PendingChannelTune
import com.kennyb1201.kbstream.data.notifications.NotificationCenter
import com.kennyb1201.kbstream.ui.iptv.GuideScreen
import com.kennyb1201.kbstream.ui.iptv.IptvViewModel
import com.kennyb1201.kbstream.ui.onboarding.OnboardingPrefs
import com.kennyb1201.kbstream.ui.onboarding.OnboardingScreen
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.ui.player.ExternalPlayerActivity
import com.kennyb1201.kbstream.ui.player.MpvPlayerActivity
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.player.NextEpisodeResult
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.player.PlayerCastMember
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.kennyb1201.kbstream.ui.kb.KBFolderScreen
import com.kennyb1201.kbstream.ui.library.LibraryScreen
import com.kennyb1201.kbstream.ui.settings.SettingsScreen
import com.kennyb1201.kbstream.ui.search.SearchScreen
import com.kennyb1201.kbstream.ui.search.SearchViewModel
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.streams.StreamsScreen
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.decade.DecadeScreen
import com.kennyb1201.kbstream.ui.studio.StudioScreen
import com.kennyb1201.kbstream.ui.tag.TagScreen
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.screenTransitionMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen {

    object Home : Screen()

    /**
     * Add-on management. [returnTo] is where Back lands, because this screen
     * is reached from two different places: Settings (the app's normal route
     * in) and onboarding's setup cards. Its own on-screen BACK button used to
     * send everyone Home while the hardware BACK sent everyone to Settings —
     * the same gesture landing on two different screens depending on which
     * BACK the user pressed. Both now follow where the user actually came
     * from.
     */
    data class Addons(val returnTo: Screen = Home) : Screen()

    object Search : Screen()

    object Simkl : Screen()

    object Guide : Screen()

    object Library : Screen()

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
        val returnTo: Screen = Home,
        val sources: List<Stream> = emptyList(),
        val streamHeaders: Map<String, String> = emptyMap(),
        val totalEpisodesInSeason: Int? = null,
        val runtimeMinutes: Int? = null,
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap()
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

    fun toPlayerScreen(stream: Stream, allSources: List<Stream>): Screen.Player {
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

class MainActivity : ComponentActivity() {

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
        super.onCreate(savedInstanceState)
        // Application.onCreate → this activity's create: the part of the
        // launch the user actually waits on before the first frame. A
        // cumulative figure rather than a phase delta, so it is recorded here
        // instead of through recordStartupPhase — but it is still LOGGED, or
        // `adb logcat -s STARTUP` would show only three of the four phases.
        val sinceAppStartMs =
            com.kennyb1201.kbstream.data.reporting.PerfTrace.sinceAppStartMs()
        if (sinceAppStartMs >= 0) {
            com.kennyb1201.kbstream.data.reporting.PerfTrace
                .record("startup.mainCreate", sinceAppStartMs)
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

        val composeStartedMs = android.os.SystemClock.elapsedRealtime()
        setContent {
            // Sync the AMOLED toggle into the theme's live state BEFORE the
            // first composition so launch already paints the right palette.
            AppPreferences.getAmoledBlack(this)
            AppPreferences.getPureBlackSurface(this)
            KBStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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
                            AppRoot()
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
    }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot() {

    // Persisted across process death: Android can kill a backgrounded TV app,
    // and without a Saver the app would come back on Home instead of the
    // detail/streams screen the user was on. The Saver encodes the current
    // Screen as JSON; anything unparseable falls back to Home.
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }

    // Profile gating: with profiles set up, launch into the picker so each
    // session starts under the right identity. Fresh installs (no profiles)
    // skip it and stay optional-profile.
    // init() already ran in onCreate (before first composition); here we
    // only gate the entry screen on whether profiles exist.
    LaunchedEffect(Unit) {
        if (com.kennyb1201.kbstream.data.sync.ProfileManager.hasProfiles(applicationContext)) {
            screen = Screen.ProfilePicker()
        }
    }

    // Re-mirror the theme toggles on every active-profile change: AMOLED /
    // pure-black are profile-scoped prefs backing live theme state, and the
    // switch path (picker / profile editor) never touched the mirrors — the
    // new profile's palette only appeared after opening Settings.
    val themeMirrorContext = LocalContext.current
    LaunchedEffect(Unit) {
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.collect {
            AppPreferences.getAmoledBlack(themeMirrorContext)
            AppPreferences.getPureBlackSurface(themeMirrorContext)
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
        // Binge continuation: when this auto-play is the next episode of a
        // show whose previous stream carried a Stremio bingeGroup, reorder
        // the sources so group/addon matches win (see BingeGroupResolver).
        // Fallback OFF additionally collapses the list to nothing so the
        // picker is shown instead of silently playing a different provider.
        val ordered =
            if (pending.bingeGroup.isNullOrBlank()) {
                streams
            } else {
                BingeGroupResolver.orderedForNextEpisode(
                    context = context,
                    streams = streams,
                    previousBingeGroup = pending.bingeGroup,
                    previousAddonName = pending.addonName
                )
            }
        val top = ordered.firstOrNull { !it.url.isNullOrBlank() }
        pendingAutoPlay = null
        screen = if (top != null) {
            pending.toPlayerScreen(top, ordered)
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
        // A live-TV reminder tap: the reminder stores the channel but not its
        // stream URL, so hand the id to the guide, which resolves it against
        // the playlist it loads and plays it (see PendingChannelTune).
        val reminderChannelId =
            intent?.getStringExtra(NotificationCenter.EXTRA_REMINDER_CHANNEL_ID)
        val reminderPending = !reminderChannelId.isNullOrBlank()
        if (reminderPending) {
            PendingChannelTune.set(reminderChannelId)
            screen = Screen.Guide
        }
        if (!launcherType.isNullOrBlank() && !launcherId.isNullOrBlank()) {
            screen = Screen.Detail(
                if (launcherType == "tv") "series" else launcherType,
                launcherId,
                returnTo = Screen.Home
            )
        }

        // Voice / system search (see VoiceSearchActivity): land on Search with
        // the spoken query already submitted instead of an empty field. The
        // seed is also read by the Search screen itself, which covers the warm
        // case where the view model already exists.
        val spokenQuery =
            intent?.getStringExtra(com.kennyb1201.kbstream.ui.search.SearchSeed.EXTRA_QUERY)
        if (!spokenQuery.isNullOrBlank()) {
            com.kennyb1201.kbstream.ui.search.SearchSeed.set(spokenQuery)
            screen = Screen.Search
        }

        // Safety net: if the app was killed while a next-episode handoff was
        // pending (process death between the player finishing and this
        // activity's result callback ever running), resurface the next episode
        // instead of silently landing on Home. The pending Detail.target routes
        // through the same one-shot Continue Watching autoplay path. Skipped
        // when a launcher deep link is present - that launch intent wins.
        if (launcherType.isNullOrBlank() && launcherId.isNullOrBlank() && !reminderPending) {
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
                        resumePositionMs = 0L,
                        randomEpisodes = pending.randomEpisodes
                    ),
                    returnTo = Screen.Home
                )
                return@LaunchedEffect
            }
        }

        val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()
        val entries = runCatching { dao.getAll() }.getOrDefault(emptyList())
        TvLauncherPublisher.sync(context, entries)
    }

    // "Back to exit" confirmation: on Home, Back opens an exit prompt instead
    // of finishing the Activity immediately, so an accidental press can't drop
    // the user out of the app. The profile picker gets the same treatment —
    // it is the app's entry screen, so Back there must EXIT, not push the
    // user into a profile. Cancelling an in-flight auto-play keeps priority
    // over the prompt.
    var confirmExit by remember { mutableStateOf(false) }
    // Only the ENTRY picker (no returnTo) treats Back as exit; a picker opened
    // from inside the app belongs to its returnTo screen.
    val interceptBack =
        (screen == Screen.Home ||
            ((screen as? Screen.ProfilePicker)?.returnTo == null &&
                screen is Screen.ProfilePicker)) &&
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

    // Update popup on every screen except Settings (Settings has the full
    // updater row; everywhere else the user would otherwise never know a new
    // build exists until they happen to visit Settings). Shown when the
    // launch check found a newer release that hasn't been dismissed for this
    // version. Covers Home/Search/Detail/Player/Guide/Library/Profiles.
    if (screen !is Screen.Settings) {
        UpdateAvailablePopup(isPlaying = screen is Screen.Player)
    }

    // The exit prompt sits before the onboarding early-return so Back is also
    // confirmed while the onboarding guide stands in for Home.
    if (confirmExit) {
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
        OnboardingScreen(
            onOpenAddons = { screen = Screen.Addons(returnTo = Screen.Home) },
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

    // Screen transitions. Every navigation used to be an instant cut, which is
    // the one thing that most reliably reads as "not a finished app" on a TV —
    // the user triggers it constantly.
    //
    // Keying on the screen KIND rather than its value means navigating between
    // two Detail pages, or re-targeting one, does not replay a full-screen
    // transition; only an actual screen change does.
    // SharedTransitionLayout owns the shared-element registry the poster ->
    // Detail hero flight is built on; the AnimatedContent below supplies the
    // per-screen visibility scope. Both are needed, which is why the hero
    // transition can only be constructed inside the content lambda.
    SharedTransitionLayout {
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
    // ambient value. Every poster that can open a Detail page opts in with a
    // single Modifier.heroSharedElement(type, id); no screen has to grow a
    // parameter to join the flight.
    CompositionLocalProvider(
        LocalKBHeroTransition provides if (transitionMs == 0) {
            // Reduced motion: the screen change is already a hard cut, so a
            // poster that flew across it would be the one thing still moving.
            null
        } else {
            KBHeroTransition(
                sharedScope = this@SharedTransitionLayout,
                visibilityScope = this,
                durationMs = transitionMs
            )
        }
    ) {
    when (current) {

        is Screen.ProfilePicker -> {
            ProfilePickerScreen(
                onSelect = {
                    // Switching profiles: return to where the picker was
                    // opened from (Settings/top bar path), or Home for the
                    // cold-launch entry picker. HomeViewModel's
                    // observeProfileSwitches reloads all rails for the new
                    // profile.
                    screen = current.returnTo ?: Screen.Home
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
                    val manualKey = "${meta.type}:${target.streamId}"
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

                onSearch = {
                    screen = Screen.Search
                },

                onOpenGuide = {
                    screen = Screen.Guide
                },

                onOpenLibrary = {
                    screen = Screen.Library
                },

                onOpenSettings = {
                    screen = Screen.Settings
                },
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

                onOpenCatalogGrid = { rail ->
                    screen = Screen.CatalogGrid(
                        title = rail.catalogName,
                        addonName = rail.addonName,
                        returnTo = Screen.Home
                    )
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
                onOpenSimkl = { screen = Screen.Simkl },
                onOpenProfiles = {
                    // Real picker so the profile can be SWITCHED here — the
                    // old wiring jumped straight into the editor, which can
                    // only rename/create profiles, never switch.
                    screen = Screen.ProfilePicker(returnTo = Screen.Settings)
                }
            )
        }

        is Screen.CatalogGrid -> {
            CatalogGridScreen(
                title = current.title,
                addonName = current.addonName,
                onItemClick = { meta ->
                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        returnTo = current
                    )
                },
                onBack = {
                    screen = stableBackDestination(current.returnTo)
                }
            )
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

            AddonsScreen(
                onBack = {
                    screen = stableBackDestination(current.returnTo)
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

        is Screen.Simkl -> {

            SimklConnectScreen(
                onBackToHome = {
                    screen = Screen.Settings
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
                onBack = { screen = Screen.Home }
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

                    // Publish the guide's filtered/ordered lineup so the
                    // player can zap between live channels with CH+/CH− and
                    // show an EPG info banner. Kept in lockstep with every
                    // launch so edits in the guide are reflected next time.
                    // The zap lineup is published by the guide itself, from
                    // the group being browsed (see GuideScreen) — publishing
                    // the whole visible list here is what used to make UP/DOWN
                    // jump out of the group you were in.

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
                },

                // Catch-up (DVR): launch the recorded broadcast URL directly.
                // Not registered in the zap registry — CH+/CH− flips live
                // channels, and a DVR recording is not one of them. Back
                // returns to the guide like a live session.
                onPlayCatchup = { channelWithEpg, program ->
                    val channel = channelWithEpg.channel
                    val channelName = channel.displayName.ifBlank { "Live Channel" }
                    val programName = program.title.ifBlank { "Catch-up" }

                    screen = Screen.Player(
                        url = program.url,
                        audioUrl = null,
                        parentId = channel.id.ifBlank { channel.streamUrl },
                        parentType = "channel",
                        season = null,
                        episode = null,
                        episodeStreamId = channel.id,
                        itemName = "$channelName — $programName",
                        itemPoster = channel.logoUrl
                            ?: channelWithEpg.epgChannel?.iconUrl,
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
                current.providerId,
                current.networkOrCompanyId,
                current.networkIsCompany,
                current.originalsCompanyId,

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
                            startFromBeginning = current.target.startFromBeginning,
                            randomEpisodes = current.target.randomEpisodes,
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
                            overview = current.overview,
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
                            val nextBingeGroup = data.getStringExtra("next_binge_group")
                            val nextAddonName = data.getStringExtra("next_addon_name")
                            val nextTarget = StreamsTarget(
                                contentType = current.parentType,
                                streamId = nextStreamId.orEmpty(),
                                title = nextTitle.orEmpty(),
                                displayName = current.itemName,
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
                                    overview = current.overview,
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
                                    overview = current.overview,
                                    cast = nextCast
                                )
                            }
                        }
                                            "play_now" -> {
                            val bywUrl = data.getStringExtra("byw_stream_url")
                            val bywType = data.getStringExtra("byw_type") ?: "movie"
                            val bywId = data.getStringExtra("byw_id").orEmpty()
                            val bywName = data.getStringExtra("byw_name").orEmpty()
                            val bywPoster = data.getStringExtra("byw_poster")
                            val bywBackdrop = data.getStringExtra("byw_backdrop")
                            if (!bywUrl.isNullOrBlank()) {
                                screen = Screen.Player(
                                    url = bywUrl,
                                    audioUrl = null,
                                    parentId = bywId,
                                    parentType = bywType,
                                    season = null,
                                    episode = null,
                                    episodeStreamId = bywId,
                                    itemName = bywName,
                                    itemPoster = bywPoster,
                                    backdropUrl = bywBackdrop,
                                    startPositionMs = 0L,
                                    sources = listOf(
                                        Stream(
                                            name = data.getStringExtra("byw_stream_name"),
                                            title = data.getStringExtra("byw_stream_name"),
                                            url = bywUrl
                                        )
                                    ),
                                    returnTo = stableBackDestination(current.returnTo)
                                )
                            } else {
                                screen = Screen.Detail(
                                    bywType,
                                    bywId,
                                    itemPoster = bywPoster,
                                    itemBackdrop = bywBackdrop,
                                    returnTo = stableBackDestination(current.returnTo)
                                )
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
                // Settings → Playback engine. "MPV" opens the backup player
                // directly; the default keeps ExoPlayer here and lets it fall
                // over to MPV by itself when a stream is unplayable (see
                // NativePlayerActivity.handOffToMpv). Both take the same
                // extras, so nothing below has to know which one it got.
                // "External player" hands the stream to an installed video app
                // while THIS app keeps the session (see ExternalPlayerActivity).
                // DRM stays in-app whatever the setting says: the licence is
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

    // Loading splash while autoselect resolves sources in the background —
    // mirrors the player's first-load splash (backdrop + pulsing clearlogo) —
    // so the streams picker is never shown before playback.
    pendingAutoPlay?.let { pending ->
        AutoPlayLoadSplash(
            backdropUrl = pending.backdropUrl,
            clearLogoUrl = pending.clearLogoUrl,
            title = pending.target.displayName,
            subtitle = "Finding sources…"
        )
    }
    }
    } // closes AnimatedContent( targetState = screen ) { current -> ... }
    } // closes SharedTransitionLayout
}

@Composable
private fun KidsTimeLockOverlay() {
    val guard = com.kennyb1201.kbstream.data.sync.KidsTimeGuard
    val lockState by guard.state.collectAsState()

    val profile = com.kennyb1201.kbstream.data.sync.ProfileManager.profiles
        .collectAsState().value
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.97f))
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (lockState.reason) {
                    com.kennyb1201.kbstream.data.sync.KidsTimeGuard.LockState.Reason.BEDTIME ->
                        "TIME FOR BED"
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
                    else ->
                        "${lockState.profileName ?: "This profile"} used its " +
                            "${lockState.dailyLimitMinutes} min today. Come back tomorrow!"
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp)
            )

            if (profile != null &&
                com.kennyb1201.kbstream.data.sync.ProfileManager.hasPin(profile)
            ) {
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
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
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
    val updateState by AppUpdater.state.collectAsState()

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

    if (available == null || hidden || suppressForPlayback) return

    Dialog(onDismissRequest = { }) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "UPDATE AVAILABLE",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "KBStream ${available.versionName} (build ${available.versionCode}) " +
                    "is ready to install.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                KBCard(onClick = {
                    // No dismissPopup here: if the download/install fails the
                    // popup must re-offer this version on the next launch.
                    hidden = true
                    AppUpdater.downloadAndInstall(context, available)
                }) {
                    Text(
                        text = "INSTALL NOW",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                KBCard(onClick = {
                    hidden = true
                    AppUpdater.dismissPopup(context, available)
                }) {
                    Text(
                        text = "LATER",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            Text(
                text = "Downloads in the background - the app relaunches when done.",
                color = KBTextLo.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun ExitConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
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
                ExitDialogButton(label = "STAY", onClick = onDismiss)
                ExitDialogButton(label = "EXIT", onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun ExitDialogButton(
    label: String,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick) {
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
