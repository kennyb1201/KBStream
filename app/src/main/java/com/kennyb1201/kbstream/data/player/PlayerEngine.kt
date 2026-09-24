package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * Which engine plays a title, and whether the backup engine may take over.
 *
 * ExoPlayer is the primary engine: it is the one wired into the media session,
 * the Dolby Vision layers, the track memory and the whole player panel. MPV is
 * the *backup* — it exists for the cases ExoPlayer cannot solve on the device
 * at all, in particular:
 *
 *  - "no decoder resources available" (OMX_ErrorInsufficientResources,
 *    0x80001000). Once one video decoder on the process fails that way the box
 *    returns it for every later MediaCodec decoder too, and ExoPlayer has no
 *    way back; mpv can decode the file in software instead.
 *  - files MediaCodec has no decoder for (exotic codecs, some 10-bit/4:4:4
 *    profiles), which mpv's bundled FFmpeg handles.
 *  - fansub ASS/SSA typesetting, which mpv renders through libass.
 *
 * Two places use this:
 *
 *  1. A launch: MainActivity asks [prefersMpv] to decide whether to open MPV
 *     straight away (the "MPV" setting, plus the "Play anime in MPV" setting
 *     for a title [AnimeDetect] recognises as anime - the fansub typesetting
 *     and 10-bit releases above are the common case on that side).
 *  2. A failure: NativePlayerActivity asks [mpvFallbackEnabled] before handing
 *     its own launch intent to the MPV player (the default setting).
 *
 * A third choice, "External player", sits beside those two: the title is handed
 * to an installed video app. It is handled by [prefersExternal] and never by the
 * MPV rules - a profile that asked for an external player is not also asking to
 * be redirected to libmpv because the title happens to be anime.
 */
object PlayerEngine {

    /**
     * libmpv's own floor. The bundled artifact declares minSdk 26 and the app
     * declares 23, so the manifest overrides the library's floor for the
     * merger; this constant is what actually keeps the native libraries off a
     * device they were not built for. Nothing may call into MPV without
     * checking this first.
     */
    const val MIN_SDK = Build.VERSION_CODES.O

    /** Whether this device can run the MPV engine at all. */
    fun isMpvAvailable(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * The stored choice, coerced to something this device can actually run: a
     * Fire OS 6 box (API 25) has no libmpv, so a stored "MPV" reads back as
     * ExoPlayer there instead of being honoured at playback time and dying on
     * a missing native library.
     */
    fun selected(context: Context): Int {
        val chosen = AppPreferences.getPlayerEngine(context)
        return when {
            chosen == AppPreferences.PLAYER_ENGINE_MPV && !isMpvAvailable() ->
                AppPreferences.PLAYER_ENGINE_EXO

            // Same reasoning for the External engine: a box with no video app
            // installed has nothing to hand a title TO, so the stored choice
            // reads back as ExoPlayer rather than launching into a chooser with
            // an empty list.
            chosen == AppPreferences.PLAYER_ENGINE_EXTERNAL &&
                !ExternalPlayer.isAvailable(context) ->
                AppPreferences.PLAYER_ENGINE_EXO

            else -> chosen
        }
    }

    /** Whether this box has an installed app the External engine could use. */
    fun externalAvailable(context: Context): Boolean =
        ExternalPlayer.isAvailable(context)

    /**
     * True when a launch should open the external-player wrapper instead of
     * either in-app engine. The stored choice alone decides: unlike MPV, there
     * is no per-title rule that overrides it.
     */
    fun prefersExternal(context: Context): Boolean =
        externalAvailable(context) &&
            AppPreferences.getPlayerEngine(context) == AppPreferences.PLAYER_ENGINE_EXTERNAL

    /**
     * The launch decision itself, free of prefs and device state so it can be
     * unit-tested: the stored engine, plus the anime rule.
     *
     * The anime setting deliberately outranks "ExoPlayer only". That choice
     * is about not changing engine MID-TITLE (see [mpvFallbackEnabled]); the
     * anime setting is an explicit statement about which engine should OPEN
     * anime, so a profile that turned it on gets it even though it also asked
     * for no surprise engine changes elsewhere.
     */
    fun launchPrefersMpv(
        chosenEngine: Int,
        mpvAvailable: Boolean,
        mpvForAnime: Boolean,
        isAnime: Boolean
    ): Boolean =
        mpvAvailable &&
            chosenEngine != AppPreferences.PLAYER_ENGINE_EXTERNAL &&
            (
                chosenEngine == AppPreferences.PLAYER_ENGINE_MPV ||
                    (mpvForAnime && isAnime)
                )

    /**
     * True when a launch should open the MPV player instead of ExoPlayer.
     *
     * This is the launch site's entry point, and it takes a Context alone:
     * the anime verdict comes from [publishLaunchAnime], consumed here.
     */
    fun prefersMpv(context: Context): Boolean =
        prefersMpv(context, isAnime = consumeLaunchAnime())

    /** [prefersMpv] with the anime verdict supplied by the caller. */
    fun prefersMpv(context: Context, isAnime: Boolean): Boolean =
        launchPrefersMpv(
            chosenEngine = AppPreferences.getPlayerEngine(context),
            mpvAvailable = isMpvAvailable(),
            mpvForAnime = AppPreferences.getMpvForAnime(context),
            isAnime = isAnime
        )

    /**
     * Whether the "Play anime in MPV" setting can have any effect here - false
     * on a device without libmpv, so a caller does not spend its anime lookup
     * on a launch that was never going to MPV.
     */
    fun mpvForAnimeEnabled(context: Context): Boolean =
        isMpvAvailable() && AppPreferences.getMpvForAnime(context)

    /**
     * How long a published anime verdict stays usable. Long enough for a user
     * who studies the source list before picking one, short enough that the
     * verdict cannot leak onto a launch a moment later.
     */
    private const val LAUNCH_ANIME_TTL_MS = 60_000L

    private data class LaunchAnime(
        val isAnime: Boolean,
        val at: Long
    )

    /**
     * The anime verdict for the play request that is about to be launched.
     *
     * The launch site asks [prefersMpv] with nothing but a Context, so the one
     * fact that decision needs about the title has to be published by whoever
     * resolved the play request: StreamsViewModel (ui/streams) sees both the
     * request's id and its media type and calls [publishLaunchAnime] on its way
     * to the player screen.
     *
     * One-shot, and only honoured while fresh - see [LAUNCH_ANIME_TTL_MS].
     * Without that, a verdict would outlive its launch and reach the next one
     * through a path that publishes nothing at all (a live channel, a
     * because-you-watched card, a chained next episode) and open it in MPV.
     */
    @Volatile
    private var launchAnime: LaunchAnime? = null

    /**
     * Records whether the play request being resolved right now is anime.
     *
     * Costs nothing unless the setting is on and this device can run MPV: on a
     * box without libmpv there is no point asking TMDB about a title whose
     * launch was never going to change engine.
     */
    suspend fun publishLaunchAnime(
        context: Context,
        streamId: String,
        contentType: String
    ) {
        if (!mpvForAnimeEnabled(context)) {
            return
        }

        launchAnime =
            LaunchAnime(
                isAnime =
                    AnimeDetect.isAnimeForLaunch(
                        repository = TmdbRepository.getInstance(context),
                        parentId = AnimeDetect.titleIdOf(streamId),
                        parentType = contentType
                    ),
                at = SystemClock.elapsedRealtime()
            )
    }

    /**
     * Drops the published verdict: the source about to play cannot use the MPV
     * engine at all (a DRM stream - NativePlayerActivity's own handoff excludes
     * those for the same reason), so this launch must stay on the stored
     * choice no matter what the anime rule said about the title.
     */
    fun clearLaunchAnime() {
        launchAnime = null
    }

    /** The published verdict, consumed: stale or already-read reads as false. */
    private fun consumeLaunchAnime(): Boolean {
        val published = launchAnime ?: return false
        launchAnime = null
        return published.isAnime &&
            SystemClock.elapsedRealtime() - published.at <= LAUNCH_ANIME_TTL_MS
    }

    /**
     * True when ExoPlayer may hand a stream it cannot play over to MPV.
     *
     * This is the default: the fallback is the point of the setting, and the
     * one choice that disables it (ExoPlayer only) exists because a mid-title
     * engine change is a visible thing to have happen without asking.
     */
    fun mpvFallbackEnabled(context: Context): Boolean =
        isMpvAvailable() && selected(context) != AppPreferences.PLAYER_ENGINE_EXO_ONLY

    /**
     * Human-readable name of the engine the STORED choice opens - the anime
     * setting can still send one particular title to MPV.
     */
    fun displayName(context: Context): String = when (selected(context)) {
        AppPreferences.PLAYER_ENGINE_MPV -> "MPV"
        AppPreferences.PLAYER_ENGINE_EXTERNAL ->
            ExternalPlayer.target(context)?.label ?: "External player"

        else -> "ExoPlayer"
    }
}
