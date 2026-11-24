package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.player.FrameRateMatch
import com.kennyb1201.kbstream.ui.player.FrameRateProbe
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull

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
 *  1. A launch: MainActivity asks [prefersMpvForLaunch] to decide whether to
 *     open MPV straight away (the "MPV" setting, plus the "Play anime in MPV"
 *     setting for a title [AnimeDetect] recognizes as anime - the fansub
 *     typesetting and 10-bit releases above are the common case on that side -
 *     plus the "Play 24 fps titles in MPV" setting, a property of the FILE
 *     rather than the title; see [routesFilmCadenceToMpv]).
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
     * The app's own floor, and the API level MPV is selected from. It used to
     * coincide with libmpv's (both declared 26); the mpvlibAndroid AAR now
     * declares minSdk 24 - below this - so this is purely the app's own choice,
     * kept as the single explicit gate MPV is selected through and so the floor
     * cannot drift back below the native library without someone noticing this
     * line.
     */
    const val MIN_SDK = Build.VERSION_CODES.O

    /** Whether this device can run the MPV engine at all. */
    fun isMpvAvailable(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * The stored choice, coerced to something this device can actually run.
     * The app's floor (26) sits above the AAR's declared minSdk (24), so
     * [isMpvAvailable] is unconditionally true and the API-25 case this used to
     * guard against is gone; the coercion stays as the single place a stored
     * choice meets the runtime capability, which still covers native libraries
     * that fail to load for a reason other than the API level.
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
            //
            // And a kids profile never gets it either (audit C8), however the
            // pref got set: see [externalAllowedForProfile].
            chosen == AppPreferences.PLAYER_ENGINE_EXTERNAL &&
                (!ExternalPlayer.isAvailable(context) || !externalAllowedForProfile()) ->
                AppPreferences.PLAYER_ENGINE_EXO

            else -> chosen
        }
    }

    /**
     * Whether the External engine can be USED right now: this box has an app to
     * hand a stream to, AND the active profile may hand one over.
     *
     * The profile half is folded in here on purpose (audit C8): every reader of
     * this predicate is deciding whether to OFFER the external path - the
     * launcher, both in-app players' "play in another app" buttons and the
     * MPV -> external hand-off - so one rule here is the difference between a
     * kids profile that cannot reach that path and four call sites that each
     * have to remember. A box that HAS a video app is what
     * [ExternalPlayer.isAvailable] answers.
     */
    fun externalAvailable(context: Context): Boolean =
        externalAllowedForProfile() && ExternalPlayer.isAvailable(context)

    /**
     * Whether the External engine may be used on the ACTIVE profile at all.
     *
     * False on a kids profile, and that is a lock rather than a preference
     * (audit C8): the time locks are enforced from the app's own player
     * Activities (`KidsTimeGuard.enforceLock`), so once a title is handed to
     * VLC/MX those collectors stop running - bedtime and the daily limit never
     * fire mid-playback, and the budget does not burn while the other app owns
     * the screen. An engine that silently disables both limits is not something
     * a kids profile may choose (nor keep, if it was chosen before the profile
     * became restricted), so the stored pref is coerced at [selected] and this
     * is the one predicate both the launch and the settings row ask.
     */
    fun externalAllowedForProfile(): Boolean =
        ProfileManager.activeProfile.value?.kidsMaxAge == null

    /**
     * True when a launch should open the external-player wrapper instead of
     * either in-app engine. The stored choice alone decides: unlike MPV, there
     * is no per-title rule that overrides it - except the profile rule above,
     * which a kids profile cannot opt out of.
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
        isAnime: Boolean,
        mpvFor24Fps1080: Boolean = false,
        fps: Double? = null,
        height: Int? = null,
        isDolbyVision: Boolean = false
    ): Boolean =
        mpvAvailable &&
            chosenEngine != AppPreferences.PLAYER_ENGINE_EXTERNAL &&
            (
                chosenEngine == AppPreferences.PLAYER_ENGINE_MPV ||
                    (mpvForAnime && isAnime) ||
                    (mpvFor24Fps1080 && routesFilmCadenceToMpv(fps, height, isDolbyVision))
                )

    /**
     * The tallest picture the 24 fps rule may hand to MPV. 1080p is the whole
     * carve-out: any 4K on the TCL makes mpv's default `vo=gpu-next` run every
     * frame through libplacebo on a GPU that box cannot spare (its `profile=fast`
     * already concedes the same point), and the `hwdec` chain may end up in
     * copy-back or FFmpeg software decode.
     */
    private const val MPV_FILM_CADENCE_MAX_HEIGHT = 1080

    /**
     * Whether [fps], after [FrameRateMatch.normalise], is film cadence: 23.976
     * or 24.0.
     *
     * Only those two. 25 fps is a different standard rate, not a sloppier 24 —
     * PAL titles are not what 3:2 pulldown judder is about, so folding them in
     * here would redirect a whole library the rule was never checked against.
     */
    fun isFilmCadence(fps: Double?): Boolean {
        val rate = fps?.let { FrameRateMatch.normalise(it) } ?: return false
        return rate == 23.976 || rate == 24.0
    }

    /**
     * The 24 fps rule itself, free of prefs, probes and device state so its
     * matrix is unit-tested: MPV only for film cadence whose measured height is
     * 1080 or less, and never for Dolby Vision.
     *
     * Every unknown fails closed. An absent rate or height is "the probe could
     * not say", not a guess: release names lie about resolution (StreamRanker
     * documents a "2160p" tag on a file that cannot carry 2160p), so nothing
     * here may read one. DV is excluded because mpv has no DV path at all — the
     * passthrough, the relabel and the P5→8.1 conversion live in ExoPlayer's
     * extractors and NativePlayerActivity.
     */
    fun routesFilmCadenceToMpv(
        fps: Double?,
        height: Int?,
        isDolbyVision: Boolean
    ): Boolean = filmCadenceRefusal(fps, height, isDolbyVision) == null

    /**
     * Why [routesFilmCadenceToMpv] refused, or null when it did not.
     *
     * One rule, two readers: the predicate above and the diagnostic line below, so
     * a field log can never name a different reason from the one the launch
     * acted on.
     */
    fun filmCadenceRefusal(
        fps: Double?,
        height: Int?,
        isDolbyVision: Boolean
    ): String? = when {
        isDolbyVision -> "Dolby Vision"
        fps == null -> "no rate"
        !isFilmCadence(fps) -> "${formatRate(fps)} fps"
        height == null -> "no height"
        height > MPV_FILM_CADENCE_MAX_HEIGHT -> "${height}p"
        else -> null
    }

    /** A rate as the log line writes it: three decimals, always a dot. */
    private fun formatRate(fps: Double): String = String.format(Locale.US, "%.3f", fps)

    /**
     * True when a launch should open the MPV player instead of ExoPlayer.
     *
     * This is the launch site's entry point (MainActivity's player route). The
     * anime verdict is answered from this session's resolved verdicts first and
     * RESOLVED HERE when there is none - it is not "whatever the streams fetch
     * happened to publish".
     *
     * That distinction is the whole bug this entry point exists to close. The
     * verdict used to be a single slot that only [publishLaunchAnime] filled,
     * from StreamsViewModel's fetch, and the launch read it one-shot:
     *
     *  - The picker presses a row AFTER its list is loaded, so the fetch behind
     *    that press may be minutes old (the verdict went stale at 60 s) or may
     *    not have run at all - the picker skips its fetch when it already holds
     *    this target's list. Those launches read nothing and opened ExoPlayer.
     *  - The slot was consumed by the first read, so backing out and picking a
     *    second source for the same title - or a relaunch on rotation - read
     *    nothing either.
     *  - Any fetch for ANOTHER title replaced the slot, so a prefetch racing a
     *    play erased the verdict for the title being launched.
     *
     * Hence "anime opens in MPV sometimes": it worked exactly when a fetch for
     * this title landed within a minute of the launch. A verdict is a fact
     * about the title, so it is now cached BY TITLE, survives reads, and is
     * resolved on demand when missing (see [resolveLaunchAnime] for the one cost
     * that carries).
     */
    suspend fun prefersMpvForLaunch(
        context: Context,
        parentId: String?,
        parentType: String?,
        streamUrl: String? = null,
        streamHeaders: Map<String, String> = emptyMap()
    ): Boolean {
        // Already MPV by the stored choice: no verdict needed and nothing to
        // wait for.
        if (prefersMpv(context, isAnime = false)) {
            return true
        }
        val animeEnabled = mpvForAnimeEnabled(context)
        val filmCadenceEnabled = mpvFor24Fps1080Enabled(context)
        if (!animeEnabled && !filmCadenceEnabled) {
            return false
        }

        // The title's own arm comes first, because it is the one that is usually
        // already answered from this session's cache.
        if (animeEnabled) {
            val titleId = launchTitleIdOf(parentId, parentType)
            if (titleId != null) {
                val isAnime = cachedLaunchAnime(titleId)
                    ?: resolveLaunchAnime(context, titleId, parentType.orEmpty().trim())
                if (prefersMpv(context, isAnime = isAnime)) {
                    return true
                }
            }
        }
        if (!filmCadenceEnabled) {
            return false
        }
        return prefersMpvForFilmCadence(context, parentType, streamUrl, streamHeaders)
    }

    /**
     * The 24 fps arm of a launch: read the file's own head, then let
     * [routesFilmCadenceToMpv] decide.
     *
     * The probe runs here and nowhere earlier. The players already probe for
     * their own AFR matches, but both do it after the engine is chosen, so that
     * reading cannot drive this rule; this one WARMS the probe cache, so the
     * player's later AFR probe for the same URL logs a cache hit instead of
     * fetching the head again.
     *
     * Bounded by [LAUNCH_ANIME_BUDGET_MS] for the same reason the anime verdict
     * is: this wait sits between the tap and the first frame. The probe's own
     * 6 s default is deliberately not used here. A timeout, an unreadable head,
     * or a manifest this probe refuses all fail closed to the stored engine, and
     * the decision line says which.
     *
     * Two exclusions are the caller's: a DRM stream never reaches this function
     * (MainActivity guards the whole MPV branch), and an External engine is
     * refused below before anything is fetched.
     *
     * Kids profiles are deliberately NOT excluded. MPV is in-app and both
     * players run the time guards; only the External engine is kids-locked
     * (audit C8). Do not "fix" this by adding a profile check.
     */
    private suspend fun prefersMpvForFilmCadence(
        context: Context,
        parentType: String?,
        streamUrl: String?,
        streamHeaders: Map<String, String>
    ): Boolean {
        // An external choice is never redirected (the same rule the anime arm
        // obeys through [launchPrefersMpv]) - and there is no point paying for a
        // head fetch to learn that.
        if (AppPreferences.getPlayerEngine(context) == AppPreferences.PLAYER_ENGINE_EXTERNAL) {
            Log.i(TAG, "24fps rule: fps=? h=? dv=false -> Exo (external engine)")
            return false
        }
        // A live channel has no file to probe; the anime arm excludes it the same
        // way (see [launchTitleIdOf]).
        if (parentType?.trim().equals("channel", ignoreCase = true)) {
            Log.i(TAG, "24fps rule: fps=? h=? dv=false -> Exo (live channel)")
            return false
        }
        val url = streamUrl?.trim().orEmpty()
        if (url.isEmpty() || !FrameRateProbe.isProbeable(url)) {
            Log.i(TAG, "24fps rule: fps=? h=? dv=false -> Exo (probe: no reading)")
            return false
        }
        val reading = FrameRateProbe.read(
            url = url,
            headers = streamHeaders,
            // The head is written to a temp file, and the app's cache directory
            // is the one this process is guaranteed to be able to write.
            cacheDir = context.cacheDir,
            budgetMs = LAUNCH_ANIME_BUDGET_MS
        )
        val fps = reading?.fps
        val height = reading?.height
        val isDolbyVision = reading?.isDolbyVision == true
        val refusal = if (fps == null) "probe: no reading" else filmCadenceRefusal(
            fps = fps,
            height = height,
            isDolbyVision = isDolbyVision
        )
        Log.i(
            TAG,
            "24fps rule: fps=${fps?.let { formatRate(it) } ?: "?"} " +
                "h=${height ?: "?"} dv=$isDolbyVision -> ${if (refusal == null) "MPV" else "Exo ($refusal)"}"
        )
        return refusal == null
    }

    /** [prefersMpv] with the anime verdict supplied by the caller. */
    fun prefersMpv(context: Context, isAnime: Boolean): Boolean =
        launchPrefersMpv(
            chosenEngine = AppPreferences.getPlayerEngine(context),
            mpvAvailable = isMpvAvailable(),
            mpvForAnime = AppPreferences.getMpvForAnime(context),
            isAnime = isAnime
        )

    /**
     * Whether a persisted next-episode handoff still names the show the live
     * session is playing.
     *
     * The handoff is built by the player that just ended (one persisted by
     * [com.kennyb1201.kbstream.ui.player.NextEpisodeResult]), and the shell builds
     * the next launch from the play request it currently holds. In a normal binge
     * those are the same show, so every field of the launch is right by
     * construction. When the handoff is delivered LATE - the viewer left the
     * player, browsed and started something else before the result callback ran -
     * the live request belongs to another title, and building from it adopts that
     * title's identity while playing the handed-off episode: the 2026-11-23 field
     * report, where Holy Marvels' E5 opened carrying Man on the Inside's id,
     * poster and cast.
     *
     * Pure on purpose (the [launchPrefersMpv] model): ids in, verdict out, so
     * the matrix is pinned by a unit test rather than by reading the branch. Both
     * sides are normalized with [AnimeDetect.titleIdOf], which is the same strip
     * the startup safety net
     * ([com.kennyb1201.kbstream.ui.player.NextEpisodeResult.restoreIfDropped]'s
     * caller) applies to the handoff, so `tt…:S:E` meets `tt…` and
     * `tmdb:…:S:E` meets `tmdb:…`.
     *
     * Two answers are conservative on purpose:
     *
     *  - An id space that cannot be compared (a `tt…` live show against a
     *    `tmdb:…` handoff) is a MATCH. Only dropping what is provably another
     *    show is worth risking an ordinary binge for: the id spaces are resolved
     *    elsewhere, and a caller here has no lookup to spend.
     *  - A handoff that names no show, or a live session with no parent, is a
     *    MISMATCH. Neither can be verified, and the cost of guessing wrong is the
     *    hijack this exists to stop.
     */
    fun nextEpisodeHandoffMatches(pendingStreamId: String, liveParentId: String?): Boolean {
        val pendingShow = AnimeDetect.titleIdOf(pendingStreamId)
        val liveShow = liveParentId?.let { AnimeDetect.titleIdOf(it) }
        if (pendingShow.isBlank() || liveShow.isNullOrBlank()) return false
        if (idSpaceOf(pendingShow) != idSpaceOf(liveShow)) return true
        return pendingShow.equals(liveShow, ignoreCase = true)
    }

    /**
     * The id space a show id belongs to: `tt…` ids are IMDb, everything else is
     * named by its own prefix (`tmdb:`, `kitsu:`, a catalog id).
     *
     * Only used to decide whether two ids may be compared at all - see
     * [nextEpisodeHandoffMatches].
     */
    private fun idSpaceOf(showId: String): String =
        if (showId.startsWith("tt", ignoreCase = true)) "imdb" else showId.substringBefore(':')

    /**
     * Whether the "Play anime in MPV" setting can have any effect here - false
     * on a device without libmpv, so a caller does not spend its anime lookup
     * on a launch that was never going to MPV.
     */
    fun mpvForAnimeEnabled(context: Context): Boolean =
        isMpvAvailable() && AppPreferences.getMpvForAnime(context)

    /**
     * Whether the "Play 24 fps titles in MPV" setting can have any effect here -
     * false on a device without libmpv, so a launch does not spend its head
     * fetch on an engine this box cannot run.
     */
    fun mpvFor24Fps1080Enabled(context: Context): Boolean =
        isMpvAvailable() && AppPreferences.getMpvFor24Fps1080(context)

    private const val TAG = "PlayerEngine"

    /**
     * How long a resolved verdict stays usable.
     *
     * This is NOT the guard against a verdict reaching the wrong launch - the
     * title id it is filed under is (see [launchAnime]), and this used to be a
     * 60 s one-shot because there was no such key. It only bounds how long an
     * answer may sit in memory; a title's anime-ness does not change, so the
     * window is generous enough that a picker left open while the viewer reads
     * the source list still gets the answer the row deserves.
     */
    private const val LAUNCH_ANIME_TTL_MS = 12 * 60 * 60 * 1000L

    /** How many titles' verdicts are remembered at once. */
    private const val LAUNCH_ANIME_MAX_TITLES = 8

    /**
     * How long a launch may wait for a verdict it does not already hold.
     *
     * Short on purpose: this wait sits between the tap and the first frame. A
     * verdict that does not arrive in this window is not worth stalling the
     * player for - the launch proceeds on the stored engine, and the stream's
     * own failure fallback can still hand an unplayable one to MPV.
     */
    private const val LAUNCH_ANIME_BUDGET_MS = 2_500L

    /** One resolved verdict, and when it was resolved. */
    private data class LaunchAnime(
        val isAnime: Boolean,
        val at: Long
    )

    /**
     * The anime verdicts this session has resolved, BY TITLE ID.
     *
     * [publishLaunchAnime] warms this from StreamsViewModel's fetch - the one
     * place that sees a play request's id and media type together before the
     * player screen opens - and [prefersMpvForLaunch] reads it and, when there
     * is nothing filed for the title it is launching, resolves and files one
     * itself.
     *
     * Keyed rather than a single slot for a reason that shows up in the field:
     * one fetch for one title used to overwrite the answer for another, so a
     * background resolve (a prefetch, an autoplay advance, a second picker) in
     * the seconds before a launch erased the verdict that launch needed. The
     * id is also what the launch is looked up by, so a verdict can never be
     * applied to a title it was not computed for - not even when the setting
     * was just turned on and the cache is empty.
     */
    private val launchAnime = ConcurrentHashMap<String, LaunchAnime>()

    /**
     * Files the anime verdict for the play request being resolved right now.
     *
     * A WARM-UP, not the launch's only source: it saves the launch its lookup on
     * the path every play takes, and [prefersMpvForLaunch] still answers a
     * launch this never ran for (a picker reusing a list it already had, a
     * restored session) instead of falling back to ExoPlayer.
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

        val titleId = AnimeDetect.titleIdOf(streamId)
        if (titleId.isBlank()) return
        launchAnime[titleId] = LaunchAnime(
            isAnime =
                AnimeDetect.isAnimeForLaunch(
                    repository = TmdbRepository.getInstance(context),
                    parentId = titleId,
                    parentType = contentType
                ),
            at = SystemClock.elapsedRealtime()
        )
        pruneLaunchAnime()
    }

    /**
     * Drops a verdict: the source about to play cannot use the MPV engine at
     * all (a DRM stream - NativePlayerActivity's own handoff excludes those for
     * the same reason), so this launch must stay on the stored choice no matter
     * what the anime rule said about the title.
     *
     * [titleId] limits it to that title's verdict; null drops them all. The DRM
     * pick is about one title, so it has no business deleting the answer this
     * session resolved for another.
     */
    fun clearLaunchAnime(titleId: String? = null) {
        val key = titleId?.let { AnimeDetect.titleIdOf(it) }
        if (key.isNullOrBlank()) {
            launchAnime.clear()
        } else {
            launchAnime.remove(key)
        }
    }

    /**
     * The verdict filed for [titleId], or null when this session has not
     * resolved one (or the one it holds has aged out).
     *
     * A read, not a take. The slot this replaced was consumed on first use, so
     * the second launch of a title - backing out of a bad source and picking
     * the next one in the same picker, a rotate, an engine relaunch - read
     * nothing and opened ExoPlayer. The verdict is a fact about the title, so
     * it stays.
     */
    private fun cachedLaunchAnime(titleId: String): Boolean? {
        val verdict = launchAnime[titleId] ?: return null
        if (SystemClock.elapsedRealtime() - verdict.at > LAUNCH_ANIME_TTL_MS) {
            launchAnime.remove(titleId, verdict)
            return null
        }
        return verdict.isAnime
    }

    /**
     * Asks the metadata for a verdict this session does not hold, and files the
     * answer for the rest of the session.
     *
     * Bounded by [LAUNCH_ANIME_BUDGET_MS]: [AnimeDetect.isAnimeForLaunch] reads
     * the cached TMDB detail, so the common case is an in-memory hit and free -
     * but a title nothing has opened yet is a network round-trip, and a launch
     * must not wait on one indefinitely. On timeout the launch goes ahead on the
     * stored engine and says so in the log.
     */
    private suspend fun resolveLaunchAnime(
        context: Context,
        titleId: String,
        parentType: String
    ): Boolean {
        val verdict = withTimeoutOrNull(LAUNCH_ANIME_BUDGET_MS) {
            AnimeDetect.isAnimeForLaunch(
                repository = TmdbRepository.getInstance(context),
                parentId = titleId,
                parentType = parentType
            )
        }
        if (verdict == null) {
            Log.w(
                TAG,
                "anime verdict for $titleId did not arrive within " +
                    "${LAUNCH_ANIME_BUDGET_MS}ms; launching on the stored engine"
            )
            return false
        }
        launchAnime[titleId] = LaunchAnime(verdict, SystemClock.elapsedRealtime())
        pruneLaunchAnime()
        return verdict
    }

    /**
     * The title id a launch's verdict is filed under, or null when this launch
     * has no title to ask about (a live channel: a live stream is not a title).
     */
    private fun launchTitleIdOf(parentId: String?, parentType: String?): String? {
        val type = parentType?.trim().orEmpty()
        if (type.isEmpty() || type.equals("channel", ignoreCase = true)) return null
        val id = parentId?.let { AnimeDetect.titleIdOf(it) }?.takeIf { it.isNotBlank() }
        return id
    }

    /** Keeps [launchAnime] small: aged-out entries first, then the oldest. */
    private fun pruneLaunchAnime() {
        if (launchAnime.size <= LAUNCH_ANIME_MAX_TITLES) return
        val now = SystemClock.elapsedRealtime()
        for (entry in launchAnime.entries) {
            if (now - entry.value.at > LAUNCH_ANIME_TTL_MS) {
                launchAnime.remove(entry.key, entry.value)
            }
        }
        val overflow = launchAnime.size - LAUNCH_ANIME_MAX_TITLES
        if (overflow <= 0) return
        launchAnime.entries
            .sortedBy { it.value.at }
            .take(overflow)
            .forEach { launchAnime.remove(it.key, it.value) }
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

}
