package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.reporting.SpotlightDiagTrace
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.data.youtube.HeroTrailerResumeVerdict
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.PoTokenTrace
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.youtube.deathKind
import com.kennyb1201.kbstream.data.youtube.googlevideoBytes
import com.kennyb1201.kbstream.data.youtube.googlevideoItag
import com.kennyb1201.kbstream.data.youtube.heroTrailerRetryPlan
import com.kennyb1201.kbstream.data.youtube.signedUrl
import kotlinx.coroutines.delay

/**
 * The expanded card's trailer, with the hero's own ladder.
 *
 * The panel is a second SURFACE, not a second player: it mounts the same pooled
 * player through [HeroInlineTrailerPlayer], resolves through the same
 * [TrailerPlayerLauncher], and answers a death with the same
 * [heroTrailerRetryPlan] the TV hero and the phone's Detail hero use - a dead
 * client class is never offered twice, a replacement resumes at the death, an
 * identical replay resumes past it, and the plan's caps decide when the trailer
 * is spent. What it does NOT do is invent a resume: the plan's `resumeAtMs` is
 * the start position, exactly as the hero uses it.
 *
 * The still is what stays on screen, and it stays until the player's FIRST
 * FRAME - the same rule the hero keeps with its backdrop. Mounting a trailer
 * over a bare SurfaceView is an unpainted black rectangle for as long as the
 * first frame takes, so the still is drawn over the surface until
 * [HeroInlineTrailerPlayer] says it has painted.
 *
 * THAT COVER IS BOUNDED (2026-11-12, F1/F2). A still held until a frame is a
 * cover for a brief gap; held until an event that never arrives, it is a mask
 * over a playing trailer - which is the field report: the trailer mounted,
 * resolved, played (audio audible) and the panel never showed it. So the player
 * also reports a PLAYING trailer with no picture
 * ([HeroInlineTrailerPlayer]'s `onNoVideoFrame`), and that report lifts the
 * still: the surface is shown, because if the video is there the event is what
 * was missing. If no frame follows inside [PANEL_AUDIO_ONLY_GRACE_MS] the
 * playback is audio-only, which is not a trailer worth watching - it is
 * stopped, the still comes back, and the trailer line says which of the two
 * happened.
 *
 * A deliberate CUT is not a death: the caller removes this composable (a
 * collapsed card, a swept page) and the dispose below releases the pooled player
 * for reuse without recording anything, so a healthy source is never poisoned
 * by the handoff. A death while the panel owns the player is a real death and
 * goes through the ladder.
 */
@Composable
internal fun SpotlightPanelTrailer(
    trailerKey: String?,
    /** The title's own ids, so the ladder's IMDb leg can fire on a YouTube death. */
    imdbId: String?,
    tmdbId: Int?,
    type: String?,
    muted: Boolean,
    mode: BillboardTrailerMode,
    heroTrailerAutoplay: Boolean,
    /** The viewer's hero-trailer delay, counted from the EXPANSION (see F7). */
    delayMs: Long,
    /** What the panel shows when there is no trailer on screen. */
    stillUrl: String?,
    /**
     * The size the still is DRAWN at, in pixels (F2, 2026-11-13).
     *
     * The request carries it explicitly. Left to the layout, a load started
     * while the panel was still growing out of the card's bounds could decode at
     * card size and then be stretched to panel size - soft for exactly the reason
     * a small still is, even when the URL is the w1280 backdrop. The caller
     * computes it from the SETTLED panel box, so the decode never depends on when
     * the load happened to start.
     */
    stillDecodeSize: IntSize,
    title: String?,
    onPlayingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val key = trailerKey?.takeIf { it.isNotBlank() }
    var source by remember(key) { mutableStateOf<PlayableSource?>(null) }
    var mountedSource by remember(key) { mutableStateOf<PlayableSource?>(null) }
    var firstFrame by remember(key) { mutableStateOf(false) }
    // The player is playing with nothing painted (see the header): the still
    // lifts on it, and a frame that never follows stops the playback.
    var frameMissing by remember(key) { mutableStateOf(false) }
    // The resolve a press or a death asks for, through a COUNTER: writing any
    // state the resolve effect itself keys on would cancel it mid-flight.
    var request by remember(key) { mutableIntStateOf(0) }
    var resumeAtMs by remember(key) { mutableLongStateOf(0L) }
    var durationMs by remember(key) { mutableLongStateOf(0L) }
    // The ladder, per title - the same three numbers the hero keeps.
    var excludedClients by remember(key) { mutableStateOf<Set<String>>(emptySet()) }
    var identicalReplays by remember(key) { mutableIntStateOf(0) }
    var resumeAttempts by remember(key) { mutableIntStateOf(0) }

    /** This trailer's own state, for the diagnostics line (F4). */
    fun report(status: String) {
        SpotlightDiagTrace.spotlightPanelTrailer(status)
    }

    // Autoplay waits the VIEWER'S delay (F7), counted from the expansion - the
    // panel's own composition - and never from focus: the expand delay and the
    // trailer delay do not stack into one timer.
    //
    // The gate's own answer is REPORTED (spec 2026-11-12, F4), because "the
    // trailer did not start" has four indistinguishable causes on screen: no
    // trailer key for the card, the mode/autoplay gate, a resolve that failed,
    // and a player that never painted. The paste now names the one it is.
    LaunchedEffect(key, mode, heroTrailerAutoplay, delayMs) {
        if (key == null) {
            report("none (no trailer key)")
            return@LaunchedEffect
        }
        if (!SpotlightTrailerRule.autoplaysInCard(mode, heroTrailerAutoplay)) {
            report(
                "none (gate: mode=$mode autoplay=" +
                    (if (heroTrailerAutoplay) "on" else "off") + ")"
            )
            return@LaunchedEffect
        }
        if (request > 0) return@LaunchedEffect
        report("waiting ${delayMs.coerceAtLeast(0L)}ms")
        delay(delayMs.coerceAtLeast(0L))
        request += 1
    }

    // One resolve path, the launcher's own result (it handles its cancellation
    // and its refusals): a null answer leaves the still showing.
    LaunchedEffect(request, key) {
        if (request == 0 || key == null) return@LaunchedEffect
        report("resolving")
        val result = TrailerPlayerLauncher.resolvePlayableUrl(
            key,
            excludeClients = excludedClients,
            imdbId = imdbId,
            tmdbId = tmdbId,
            type = type
        )
        result.onSuccess { resolved ->
            mountedSource = resolved
            firstFrame = false
            frameMissing = false
            source = resolved
            report("mounted")
        }
        result.onFailure { error ->
            report("none (resolve failed: ${error.javaClass.simpleName})")
        }
    }

    // The panel's own release: this composable leaving (collapse, layout switch,
    // leaving Home) is a CUT, so the pooled player goes back without a death
    // line, and the playing flag falls with it (F2).
    DisposableEffect(key) {
        onDispose {
            onPlayingChange(false)
            TrailerPlayerPool.releaseForReuse()
        }
    }
    LaunchedEffect(source != null) {
        onPlayingChange(source != null)
    }

    // A playing trailer that paints nothing must not keep the still (F1), and
    // must not play on unheard-of behind it either (F2). The player's frame
    // watch is what says so; this is what the panel does about it.
    LaunchedEffect(source, frameMissing, firstFrame) {
        if (source == null || !frameMissing || firstFrame) return@LaunchedEffect
        report("playing, no picture (surface shown)")
        delay(PANEL_AUDIO_ONLY_GRACE_MS)
        if (frameMissing && !firstFrame) {
            report("no video: stopped the audio-only trailer")
            // Still FIRST, so the cover is back before the surface goes: the
            // player leaves, the still is what the panel shows.
            frameMissing = false
            source = null
            TrailerPlayerPool.releaseForReuse()
        }
    }

    /** Plans the replacement a death is owed, or declines it. */
    fun requestRetry(positionMs: Long, reportedDurationMs: Long): Boolean {
        val trailerKeyOrNull = key ?: return false
        val length = if (reportedDurationMs > 0L) reportedDurationMs else durationMs
        val dead = TrailerPlayerLauncher.signingClient(trailerKeyOrNull)
        val chunk = PoTokenTrace.chunkDeathFor(trailerKeyOrNull)
        val deadSource = mountedSource
        val signedUrl = deadSource?.signedUrl()
        val streamBytes = chunk?.totalBytes ?: signedUrl?.let { googlevideoBytes(it) }
        val plan = heroTrailerRetryPlan(
            positionMs = positionMs,
            durationMs = length,
            deadClient = TrailerPlayerLauncher.cachedSigningClient(trailerKeyOrNull),
            excludedClients = excludedClients,
            identicalReplays = identicalReplays,
            resumeAttempts = resumeAttempts,
            totalBytes = streamBytes
        )
        val attempt = resumeAttempts
        val change = when {
            attempt == 0 -> "first"
            plan.changedClass ->
                "class:" + (plan.excludedClients - excludedClients).joinToString(",")

            else -> "same"
        }
        PoTokenTrace.recordTrailerDeath(
            videoId = trailerKeyOrNull,
            client = dead,
            kind = deadSource?.deathKind(),
            positionMs = positionMs,
            durationMs = length,
            resume = if (plan.verdict == HeroTrailerResumeVerdict.RESUME) {
                "pending"
            } else {
                "none: retries spent"
            },
            itag = signedUrl?.let { googlevideoItag(it) },
            attempt = attempt,
            change = change,
            chunkOffsetBytes = chunk?.offsetBytes,
            chunkTotalBytes = streamBytes
        )
        if (plan.verdict != HeroTrailerResumeVerdict.RESUME) {
            Log.w(
                TAG,
                "Spotlight trailer key=$trailerKeyOrNull: no untried client left after " +
                    "${attempt + 1} attempt(s) (excluded=${plan.excludedClients.sorted()}); " +
                    "leaving the still up"
            )
            source = null
            return false
        }
        excludedClients = plan.excludedClients
        resumeAttempts = attempt + 1
        if (!plan.changedClass) identicalReplays += 1
        resumeAtMs = plan.resumeAtMs
        // The cached URL is the one that just died (or the one about to be
        // replaced): drop it so the resolve really re-signs.
        TrailerPlayerLauncher.invalidate(trailerKeyOrNull)
        Log.w(
            TAG,
            "Spotlight trailer key=$trailerKeyOrNull died at ${positionMs}ms; retry " +
                "${attempt + 1} ($change) resuming at ${plan.resumeAtMs}ms"
        )
        source = null
        request += 1
        return true
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The still, over the surface until the first frame: mounting a trailer
        // is an unpainted black rectangle until then.
        val still = stillUrl?.takeIf { it.isNotBlank() }
        if (still != null && !firstFrame && !frameMissing) {
            val context = LocalContext.current
            // The explicit size is the fix, not a hint: Coil decodes to fit the
            // requested size, so the picture is already at panel resolution the
            // first time it paints and a mid-expand size can never be captured.
            val request = remember(still, stillDecodeSize) {
                ImageRequest.Builder(context)
                    .data(still)
                    .size(stillDecodeSize.width, stillDecodeSize.height)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        val playing = source
        if (playing != null) {
            HeroInlineTrailerPlayer(
                source = playing,
                muted = muted,
                startPositionMs = resumeAtMs,
                modifier = Modifier.fillMaxSize(),
                videoId = key,
                onFirstFrame = {
                    firstFrame = true
                    frameMissing = false
                    report("first-frame")
                },
                // Playing, nothing painted: lift the still and show the
                // surface. The escalation above decides whether it stays.
                onNoVideoFrame = {
                    frameMissing = true
                },
                // A natural end is not a death: the player unmounts and the
                // still returns, which is the rule the hero keeps too.
                onEnded = {
                    source = null
                    firstFrame = false
                    frameMissing = false
                    report("ended")
                },
                onFailed = { positionMs -> requestRetry(positionMs, 0L) },
                onStalled = { positionMs, stalledDuration -> requestRetry(positionMs, stalledDuration) },
                onIdentityBlocked = { positionMs, blockedDuration ->
                    requestRetry(positionMs, blockedDuration)
                },
                onProgress = { _, positionDuration ->
                    if (positionDuration > 0L) durationMs = positionDuration
                }
            )
        }
    }
}

/**
 * How long the panel shows a surface it has been told is picture-less before
 * deciding the playback is audio-only and stopping it.
 *
 * Long enough for a slow first frame to land after the still has lifted, short
 * enough that nobody listens to a trailer they cannot see. See the header.
 */
private const val PANEL_AUDIO_ONLY_GRACE_MS = 2_500L

private const val TAG = "SPOTLIGHT_CARD"
