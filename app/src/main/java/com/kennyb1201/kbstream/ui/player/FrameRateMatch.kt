package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.kennyb1201.kbstream.data.reporting.AfrTrace
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Panel-side frame-rate matching, behind Settings → Playback → Match Content
 * Frame Rate. Off by default: it changes what the whole screen does, and the
 * choice is opted into rather than assumed.
 *
 * Why it exists: a TV panel shows one fixed rate — overwhelmingly 60 Hz — while
 * film is 23.976 fps. 24 frames into 60 slots is not a whole number, so the
 * panel repeats some frames and drops others, which is the judder people
 * describe when a slow pan "stutters" or a film looks subtly wrong. Asking the
 * panel for a rate that is an exact multiple of the content rate (24, 48 or
 * 50 Hz) removes it.
 *
 * Every decision lives in [FrameRateMatch] as pure arithmetic over
 * [DisplayModeInfo] and pure text, so both are unit tested without a device,
 * and [FrameRateMatcher] is the thin Android shell
 * that reads the panel and makes one or two requests.
 *
 * Two deliberate limits, both chosen to fail quiet:
 *  - A rate only counts as content when it is a real frame rate and a mode only
 *    counts as a match when it is a near-exact multiple of it. 23.976 fps
 *    content on a 60 Hz-only panel has no integer between them, so nothing is
 *    forced onto the panel: it is left exactly as it was rather than flashed for
 *    no gain. Many boxes report only the mode they are already in, which lands
 *    in the same place.
 *  - Whatever is changed is changed back when the player exits. A panel left at
 *    24 Hz would make the whole TV interface — menus, launcher, everything —
 *    look wrong, which is far worse than the judder this fixes.
 */
internal data class DisplayModeInfo(
    val id: Int,
    val refreshRate: Double,
    /**
     * The mode's own pixel size, or 0 when the platform would not say.
     *
     * Carried because a mode list is not sorted by anything useful: a panel can
     * list 1080p@23.976 and 2160p@23.976, and until this was read the winner at
     * the target rate was whichever had the lower mode id - the platform's
     * listing order, not the picture. A 2160p film dropped to a 1080p mode is a
     * real loss and one the diagnostics row could not even show (audit AFR C3).
     * Zero means "unknown", and an unknown size never disqualifies a mode.
     */
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Which half of the player supplied a content rate: the player's own track
 * report ([PLAYER]), [FrameRateProbe] reading the stream's head before
 * playback ([PROBE]), that same probe run a second time after its first attempt
 * lost to a slow host ([PROBE_RETRY]), or the rate this session already knew,
 * re-offered on the way back into a player that never measures the same stream
 * twice ([RESUME]). Only the diagnostics row cares, but it is the whole
 * difference between "the player never knew the rate" and "the probe could not
 * read it" - and between either of those and a panel re-armed from a rate
 * measured earlier, so it is stated rather than inferred.
 *
 * [PROBE_RETRY] is a separate value from [PROBE] on purpose: the field question
 * that mattered on 2026-10-16 was whether the second attempt was made at all,
 * and the log has to answer it without anyone reading the probe's own lines.
 */
internal enum class FrameRateSource { PLAYER, PROBE, PROBE_RETRY, RESUME }

/**
 * What to hand `Surface.setFrameRate`: the HINT path, used only when the panel's
 * own mode list offers nothing better than the mode it is already in.
 *
 * It asks the platform's frame-rate scheduler rather than naming a mode, and on
 * some firmware that scheduler never acts on it. The field capture of
 * 2026-10-17 on an Onn 4K Plus is that case: the call was logged, the panel was
 * unmoved 1.5 s later, and `preferredDisplayModeId` reached 23.976 Hz on the
 * same box in the same session. So this is not the request that reaches film
 * rates - the mode request is - and in the shipped order it is made second,
 * never first.
 *
 * [changeFrameRateStrategy] is the non-seamless opt-in, the one that asks for a
 * switch the panel cannot make without blanking the screen for a moment. The
 * platform will not make such a switch unless both the app asks for it and the
 * user has allowed it in the TV's own display settings, so it is a request, not
 * an instruction. It is null on an Android version that has no such flag, where
 * only a switch the panel can do without blanking is possible.
 */
internal data class SurfaceRateRequest(
    val frameRate: Float,
    val changeFrameRateStrategy: Int?
)

internal object FrameRateMatch {

    /** Below this a "rate" is a misparse or a telecine artifact, not content. */
    private const val MIN_CONTENT_FPS = 10.0

    /** Above this nothing real is being described. */
    private const val MAX_CONTENT_FPS = 121.0

    /**
     * Standard rates, used to settle the noise in what the player reports.
     * Extractors disagree about the same film — 23.976, 23.98 and 23.976023
     * all appear for one file — and the arithmetic below is much easier to
     * reason about against a fixed set.
     */
    private val STANDARD_RATES = doubleArrayOf(
        23.976, 24.0, 25.0, 29.97, 30.0,
        47.952, 48.0, 50.0, 59.94, 60.0,
        100.0, 119.88, 120.0
    )

    /**
     * How far a panel mode may sit from an exact multiple and still be worth
     * switching to, as a fraction of that multiple.
     *
     * 0.5% accepts a true 24.000 mode for 23.976 content — 0.1% off, and the
     * standard answer: the 0.024 fps drift is one frame per 41 seconds, which
     * the panel's own pulldown absorbs invisibly — and rejects 60 Hz for film
     * content, which is 150% off.
     */
    private const val TOLERANCE = 0.005

    /**
     * How far a mode may sit from an exact double or a 2.5x pulldown and still
     * be taken as that fallback.
     *
     * Looser than [TOLERANCE] because these are not whole multiples of the
     * content: a 60 Hz mode against 23.976 fps is 2.5x to within 0.1%, which is
     * the pulldown every TV does, and a 50 Hz mode against 23.976 is 4.3% off
     * its nearest ratio and rejected here on purpose. 2% takes the pulldown and
     * leaves the near-miss.
     */
    private const val FALLBACK_TOLERANCE = 0.02

    /**
     * The last-resort cadence search: how far the closest mode may be from the
     * nearest ratio before the panel is left alone. Beyond this every mode
     * judders as much as the one the panel is already in, and a blank-screen
     * switch buys nothing.
     */
    private const val CATCH_ALL_MAX_ERROR = 0.06

    /**
     * Added to a PAL mode's cadence error when the content is film/cinema, so
     * that NTSC film only lands on 25/50 Hz when nothing else is offered — film
     * does not belong on PAL rates.
     */
    private const val PAL_PENALTY = 0.03

    /** The ratios a panel can drive from the content rate, in useful order. */
    private val PULLDOWN_RATIOS = doubleArrayOf(1.0, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0)

    /** Rates whose content is film/cinema, which the PAL penalty protects. */
    private val CINEMA_RATES = doubleArrayOf(23.976, 24.0, 29.97, 30.0)

    /** How many panel modes the diagnostic line names before it starts counting. */
    private const val MAX_LISTED_MODES = 8

    /**
     * [contentFps] snapped onto a standard rate when it is close enough to one,
     * or null when it does not describe content at all.
     */
    fun normalise(contentFps: Double): Double? {
        if (!contentFps.isFinite()) return null
        if (contentFps < MIN_CONTENT_FPS || contentFps > MAX_CONTENT_FPS) return null
        val nearest = STANDARD_RATES.minByOrNull { abs(it - contentFps) } ?: return contentFps
        return if (abs(nearest - contentFps) / nearest <= 0.01) nearest else contentFps
    }

    /**
     * The rate a RESUMED player should offer the panel again, or null when
     * there is nothing to re-arm with.
     *
     * [lastContentFps] is whatever this session was last told - by the probe, or
     * by the player's own track report. It goes back out through the same
     * [normalise] every other rate does, so a stored `NO_VALUE` (-1.0) or a
     * misparse can never become a request: a rate that describes no content
     * leaves the panel alone when it is re-offered, exactly as it did the first
     * time it arrived.
     *
     * Pure, and separate from the Android shell, because this is the whole rule
     * a resume depends on: it is the part that decides whether anything is asked
     * for at all, and it is testable without a device.
     */
    fun resumeRate(lastContentFps: Double?): Double? =
        lastContentFps?.let { normalise(it) }

    /**
     * How long into a session the panel may still be moved.
     *
     * A mode request is an HDMI renegotiation, and renegotiating the panel under
     * a film that has already started is a hitch the viewer sees as judder - the
     * "it gets juddery partway through" report. Worse than a rate being a frame
     * or two off, because the film never recovers the moment.
     *
     * Every legitimate request happens while a title is opening: the player's
     * own track report, the probe's reading of the stream head, the one retry
     * that follows it, and the confirm ladder that finishes within ~3.5 s all
     * land in the first seconds. A rate offered later is therefore not the
     * opening - it is a rebuilt player re-reporting a track after a source
     * switch, a probe that only now finished on a slow host, or a track group
     * that appeared mid-file - and the panel is left exactly where it is. The
     * diagnostics row says so, so the next field report can name the late claim.
     *
     * Two exceptions, both decided rather than inferred (audit AFR C7):
     *
     *  - [FrameRateSource.RESUME]'s whole job is repairing a panel the platform
     *    put back while the player was away, and that repair can be hours into a
     *    session.
     *  - [FrameRateSource.PROBE_RETRY] is definitionally the FIRST usable rate
     *    for this title, arriving late because the first attempt lost to a slow
     *    host. Refusing it would leave a title unmatched for no reason the
     *    window was written for - the rule exists to stop a mid-film mode
     *    change, and no mode has been engaged at that point.
     *
     * A first [FrameRateSource.PROBE] result that somehow lands this late is
     * still refused when the source has already offered a rate: the probe is
     * bounded by 6 s, so a late one means the player was rebuilt, which is
     * exactly what the window is about.
     *
     * What the age is measured FROM is [engageAgeMs]: this source's first usable
     * offer, so a slow host whose opening only reports after 90 s is not refused
     * for the whole title (audit AFR S9).
     */
    const val ENGAGE_WINDOW_MS = 90_000L

    /**
     * Whether a session this old may still move the panel, given who offered
     * the rate. Pure, so the rule is unit-tested rather than only observed.
     */
    fun engageWindowAllows(ageMs: Long, source: FrameRateSource): Boolean =
        source == FrameRateSource.RESUME ||
            source == FrameRateSource.PROBE_RETRY ||
            ageMs <= ENGAGE_WINDOW_MS

    /**
     * The age an offer is measured against: from this SOURCE's first usable
     * offer once it has made one, otherwise from the moment the source opened
     * (audit AFR S9).
     *
     * [ENGAGE_WINDOW_MS] used to be measured from the matcher's construction,
     * and construction happens inside `createPlayer` - so a slow host whose first
     * track report (or first probe result) only landed after 90 s lost frame-rate
     * matching for the whole title, even though the picture had only just
     * started. The window exists to stop a mode change under a film that is
     * already playing, and "already playing" is measured from the first rate this
     * source offered, not from the moment the player object was built.
     *
     * A REBUILT player does not re-anchor it: a rebuild is not a new source
     * (only [FrameRateMatcher.beginSource] is, for a source switch), so a track
     * re-reported thirty minutes into a film is still refused as late - which is
     * the whole point of the rule.
     */
    fun engageAgeMs(nowMs: Long, openedAtMs: Long, firstOfferAtMs: Long?): Long =
        nowMs - (firstOfferAtMs ?: openedAtMs)

    /**
     * [modes] with every mode SMALLER than [current] removed, so an AFR switch
     * can never trade resolution for refresh rate (audit AFR C3).
     *
     * The ladder's whole vocabulary is refresh rate, so on a panel that offers
     * the target rate at two resolutions the old tie-break (lowest mode id) was
     * a coin toss - and losing it meant a 4K film played at 1080p with the
     * diagnostics row still saying `id@23.976`, which reads as success. Modes
     * whose size the platform did not report are kept: unknown is not smaller.
     *
     * The fallback matters: a panel that lists only scaled modes (or reports the
     * current one at a size no other mode reaches) would otherwise be left with
     * an empty list and lose frame-rate matching entirely, which is worse than
     * the downgrade. Nothing is filtered when the current size is unknown.
     */
    fun atLeastResolution(
        modes: List<DisplayModeInfo>,
        current: DisplayModeInfo?
    ): List<DisplayModeInfo> {
        val width = current?.width ?: 0
        val height = current?.height ?: 0
        if (width <= 0 || height <= 0) return modes
        val bigEnough = modes.filter { mode ->
            mode.width <= 0 || mode.height <= 0 || (mode.width >= width && mode.height >= height)
        }
        return bigEnough.ifEmpty { modes }
    }

    /**
     * The diagnostics line for a panel that left the mode this session engaged,
     * and was asked for it once more.
     */
    fun describeReasserted(engagedModeId: Int, currentModeId: Int): String =
        "panel left the engaged mode $engagedModeId for $currentModeId; asked for " +
            "$engagedModeId again (the film's rate is the one this session chose)"

    /**
     * The diagnostics line for a held mode the panel no longer offers at all -
     * an input or sink change that took the engaged mode off the list (audit AFR
     * S7). Re-asking would be a request that cannot land.
     */
    fun describeModeGone(engagedModeId: Int, currentModeId: Int): String =
        "panel left the engaged mode $engagedModeId for $currentModeId and no longer " +
            "offers $engagedModeId; nothing re-asked (a different display is attached)"

    /**
     * ` (1920x1080)` when the platform named the mode's size, otherwise
     * nothing.
     *
     * The size is what makes the resolution rule in [atLeastResolution]
     * checkable from a report (audit AFR C3): without it a line reading
     * `2@23.976` cannot tell a 4K mode from a 1080p one, and dropping to the
     * smaller mode for the right rate looked exactly like success.
     */
    internal fun sizeSuffix(mode: DisplayModeInfo): String =
        if (mode.width > 0 && mode.height > 0) " (${mode.width}x${mode.height})" else ""

    /** The diagnostics line for a rate offered after [ENGAGE_WINDOW_MS]. */
    fun describeLateOffer(contentFps: Double?, ageMs: Long): String {
        val label = contentFps?.let { "${rate(it)} fps" } ?: "a rate"
        return "$label offered ${ageMs / 1_000}s into playback; panel left alone " +
            "(a mode switch mid-title judders the picture)"
    }

    /**
     * The panel mode to switch to, or null when the panel must be left alone.
     *
     * A ladder, each step tried only when the one before it found nothing:
     *
     *  1. An exact multiple within [TOLERANCE] — 50 Hz for 25 fps content, 60 Hz
     *     for 29.97 — where the smallest such multiple wins, since it repeats
     *     the fewest frames. This is the behaviour that always existed.
     *  2. A mode within [FALLBACK_TOLERANCE] of exactly double the content.
     *  3. A mode within [FALLBACK_TOLERANCE] of a 2.5x pulldown — how a 60 Hz
     *     panel shows 23.976 film, and how that panel already runs it.
     *  4. The closest mode by cadence error over [PULLDOWN_RATIOS], with the PAL
     *     penalty keeping cinema content off 25/50 Hz when alternatives exist.
     *
     * The important property is what the fallbacks do NOT do: each step's winner
     * is compared against [currentModeId], and when the panel is already in it
     * the answer is null — no request, no blank screen. Film on a single-60 Hz
     * panel still resolves to 60 Hz itself, which is "already there", so the
     * no-op the old code guaranteed is preserved; the fallback only ever
     * switches where a genuinely closer mode exists. Ties break on the smaller
     * rate difference, then the lower mode id, so the answer does not depend on
     * the order the box reports its modes in.
     */
    fun chooseMode(
        contentFps: Double,
        modes: List<DisplayModeInfo>,
        currentModeId: Int
    ): DisplayModeInfo? = bestMode(contentFps, modes)?.takeIf { it.id != currentModeId }

    /**
     * The same ladder as [chooseMode] without the "already there" exclusion: the
     * best mode for this content whether or not the panel is in it now.
     *
     * It exists because the two reasons [chooseMode] can answer null are not the
     * same thing, and the caller has to tell them apart: the panel may have no
     * better mode at all (the single-rate case, where the surface hint is the
     * whole request), or it may ALREADY be in the best mode (where a mode
     * request is a no-op and the surface hint is not — see [FrameRateMatcher]).
     *
     * [chooseMode] is defined in terms of this, so the exclusion-free answer
     * being the same mode [chooseMode] refuses cannot drift from it.
     */
    fun bestMode(contentFps: Double, modes: List<DisplayModeInfo>): DisplayModeInfo? {
        val content = normalise(contentFps) ?: return null
        val usable = modes.filter { it.refreshRate.isFinite() && it.refreshRate > 0.0 }

        exactMultipleMode(content, usable)?.let { return it }
        ratioMode(content, usable, ratio = 2.0)?.let { return it }
        ratioMode(content, usable, ratio = 2.5)?.let { return it }
        return cadenceMode(content, usable)
    }

    /**
     * The better of two modes the ladder has already judged equal on rate: the
     * larger picture first, then the lower id.
     *
     * A panel can list the target rate at more than one resolution (1080p and
     * 2160p at 23.976 is the common pair), and the old tie-break was the mode
     * id - the platform's listing order, not the picture (audit AFR C3).
     * [atLeastResolution] already refuses a mode SMALLER than the panel's
     * current one; this is what picks the bigger of two modes that both pass.
     * Unknown sizes compare equal, so those ties still fall back to the id.
     */
    private fun prefersOver(candidate: DisplayModeInfo, current: DisplayModeInfo): Boolean {
        val candidatePixels = candidate.width.toLong() * candidate.height
        val currentPixels = current.width.toLong() * current.height
        return candidatePixels > currentPixels ||
            (candidatePixels == currentPixels && candidate.id < current.id)
    }

    /**
     * Step 1: the closest mode that is an exact integer multiple of the content
     * within [TOLERANCE], small multiple first. Null when the panel offers none.
     */
    private fun exactMultipleMode(
        content: Double,
        modes: List<DisplayModeInfo>
    ): DisplayModeInfo? {
        var best: DisplayModeInfo? = null
        var bestMultiple = 0
        var bestDiff = 0.0

        modes.forEach { mode ->
            // An integer multiple, never a fraction: a mode SLOWER than the
            // content cannot show every frame and is not a match.
            val multiple = round(mode.refreshRate / content).toInt().coerceAtLeast(1)
            val ideal = content * multiple
            val diff = abs(mode.refreshRate - ideal)
            if (diff / ideal > TOLERANCE) return@forEach

            // Captured so the non-null branch below reads a smart-cast value:
            // `best` is a captured var and the compiler does not narrow it
            // across the branches.
            val currentBest = best
            val better = when {
                currentBest == null -> true
                multiple != bestMultiple -> multiple < bestMultiple
                else -> diff < bestDiff - 1e-9 ||
                    (diff <= bestDiff + 1e-9 && prefersOver(mode, currentBest))
            }
            if (better) {
                best = mode
                bestMultiple = multiple
                bestDiff = diff
            }
        }

        return best
    }

    /**
     * Step 2/3: the mode closest to [ratio] times the content, within
     * [FALLBACK_TOLERANCE]. Null when no mode is near that ratio.
     */
    private fun ratioMode(
        content: Double,
        modes: List<DisplayModeInfo>,
        ratio: Double
    ): DisplayModeInfo? {
        val ideal = content * ratio
        var best: DisplayModeInfo? = null
        var bestDiff = 0.0
        modes.forEach { mode ->
            val diff = abs(mode.refreshRate - ideal)
            if (diff / ideal > FALLBACK_TOLERANCE) return@forEach
            val currentBest = best
            val better = when {
                currentBest == null -> true
                diff < bestDiff - 1e-9 -> true
                diff <= bestDiff + 1e-9 && prefersOver(mode, currentBest) -> true
                else -> false
            }
            if (better) {
                best = mode
                bestDiff = diff
            }
        }
        return best
    }

    /**
     * Step 4: the mode with the smallest cadence error against the nearest
     * [PULLDOWN_RATIOS] target, capped at [CATCH_ALL_MAX_ERROR]. Modes slower
     * than the content are skipped — they cannot show every frame — and cinema
     * content pays [PAL_PENALTY] against a 25/50 Hz mode so film only lands
     * there when nothing else can drive it.
     */
    private fun cadenceMode(
        content: Double,
        modes: List<DisplayModeInfo>
    ): DisplayModeInfo? {
        val cinema = CINEMA_RATES.any { abs(it - content) / it <= 0.01 }
        var best: DisplayModeInfo? = null
        var bestError = Double.MAX_VALUE
        modes.forEach { mode ->
            if (mode.refreshRate < content - 1e-9) return@forEach
            var error = Double.MAX_VALUE
            PULLDOWN_RATIOS.forEach { ratio ->
                val target = content * ratio
                error = minOf(error, abs(mode.refreshRate - target) / target)
            }
            if (cinema && isPalRate(mode.refreshRate)) error += PAL_PENALTY
            val currentBest = best
            val better = when {
                currentBest == null -> true
                error < bestError - 1e-9 -> true
                error <= bestError + 1e-9 && prefersOver(mode, currentBest) -> true
                else -> false
            }
            if (better) {
                best = mode
                bestError = error
            }
        }
        return best?.takeIf { bestError <= CATCH_ALL_MAX_ERROR }
    }

    /** A mode whose rate is a whole multiple of 25 Hz within [TOLERANCE]. */
    private fun isPalRate(rate: Double): Boolean {
        val multiple = round(rate / 25.0).toInt().coerceAtLeast(1)
        return abs(rate - 25.0 * multiple) / (25.0 * multiple) <= TOLERANCE
    }

    // ── Asking the platform ─────────────────────────────────────────────────
    //
    // Two ways to ask, and the newer one is the one Android documents for video
    // apps: state the content's rate on the surface the picture goes to and let
    // the platform's scheduler choose the mode. It is easier (no mode list to
    // search), it lets the platform satisfy several surfaces at once, and it
    // respects whatever the TV's own display settings allow. The older way —
    // naming a specific mode in the window's attributes — is stronger against a
    // firmware that ignores the scheduler, which is why it is kept as the
    // backstop rather than deleted.

    /**
     * What to ask `Surface.setFrameRate` for, or null when this Android version
     * has no such API (before API 30) or the rate does not describe content.
     *
     * The non-seamless opt-in only exists from Android 12 ([Build.VERSION_CODES.S]);
     * on 11 the request exists but can only ever produce a switch the panel does
     * without blanking the screen.
     */
    fun surfaceRateRequest(contentFps: Double, sdkInt: Int): SurfaceRateRequest? {
        if (sdkInt < Build.VERSION_CODES.R) return null
        val rate = normalise(contentFps) ?: return null
        return SurfaceRateRequest(
            frameRate = rate.toFloat(),
            changeFrameRateStrategy =
                if (sdkInt >= Build.VERSION_CODES.S) Surface.CHANGE_FRAME_RATE_ALWAYS else null
        )
    }

    // ── Diagnostic lines ───────────────────────────────────────────────────
    //
    // Everything below is text for the Settings row. It is pure so the wording
    // is pinned by tests: these lines are the only thing that separates "the TV
    // refused" from "there was nothing to ask for", and a line that quietly
    // changes meaning is worse than none.

    /**
     * The panel as one line: what it is showing now and everything it says it
     * can show. This is the answer to "does my TV even offer 24 Hz", which is
     * the first thing to know about a panel that never switches.
     */
    fun describeModes(modes: List<DisplayModeInfo>, currentModeId: Int): String {
        val now = modes.firstOrNull { it.id == currentModeId }
        val showing = if (now != null) {
            "mode ${now.id} at ${rate(now.refreshRate)} Hz${sizeSuffix(now)}"
        } else {
            "mode $currentModeId (not in the panel's list)"
        }
        if (modes.isEmpty()) return "$showing; the panel reports no modes at all"
        val listed = modes.take(MAX_LISTED_MODES)
            .joinToString(", ") { "${it.id}@${rate(it.refreshRate)}${sizeSuffix(it)}" }
        val more = modes.size - MAX_LISTED_MODES
        val rest = if (more > 0) ", +$more more" else ""
        val count = if (modes.size == 1) "1 mode" else "${modes.size} modes"
        return "$showing; $count: $listed$rest"
    }

    /** Why a content rate resolved to no request at all. */
    fun describeNoMatch(contentFps: Double, modes: List<DisplayModeInfo>): String {
        val content = normalise(contentFps) ?: return "no usable frame rate was reported"
        if (modes.isEmpty()) return "the panel reports no modes to choose from"
        val what = if (modes.size == 1) "the panel's only mode" else "any of the ${modes.size} modes"
        return "no whole multiple of ${rate(content)} fps in $what"
    }

    /** A frame rate put on the player's surface, named with what it opted into. */
    fun describeSurfaceRequest(request: SurfaceRateRequest, allowNonSeamless: Boolean): String =
        "asked the platform for ${rate(request.frameRate.toDouble())} fps (" +
            if (allowNonSeamless) "a blank-screen switch is allowed)" else "no blank-screen switch)"

    /**
     * Where a content rate came from — the head the probe read, or the player's
     * own report. The first line of the request sequence, so a report says
     * which half of the app supplied the number.
     */
    fun describeSource(contentFps: Double, source: FrameRateSource): String {
        val content = normalise(contentFps) ?: contentFps
        return when (source) {
            FrameRateSource.PROBE -> "probed ${rate(content)} fps from the stream head"
            FrameRateSource.PROBE_RETRY ->
                "re-probed ${rate(content)} fps from the stream head after a slow first attempt"
            FrameRateSource.PLAYER -> "the player reported ${rate(content)} fps"
            FrameRateSource.RESUME ->
                "re-offered the ${rate(content)} fps measured earlier in this session"
        }
    }

    /** A specific mode forced through the window's display-mode attribute. */
    fun describeModeSwitch(contentFps: Double, chosen: DisplayModeInfo, wasModeId: Int): String {
        val content = normalise(contentFps) ?: contentFps
        return "asked for mode ${chosen.id} at ${rate(chosen.refreshRate)} Hz for " +
            "${rate(content)} fps (was mode $wasModeId)"
    }

    /**
     * A mode was asked for twice and the panel had still not moved at the second
     * look: the panel refused it, and that is what has to be read off the row —
     * not "the request was not taken" (which implies the ladder had something
     * else to try) and not anything about presentation. Once a panel has a mode
     * answer there is no surface request to fall back to.
     */
    fun describeModeRefused(
        contentFps: Double,
        chosen: DisplayModeInfo,
        wasModeId: Int
    ): String {
        val content = normalise(contentFps) ?: contentFps
        return "the panel refused mode ${chosen.id} at ${rate(chosen.refreshRate)} Hz for " +
            "${rate(content)} fps (still mode $wasModeId); re-asked once, no surface request made"
    }

    /**
     * Nothing was asked, because the panel is already in the best mode for this
     * content. Deliberately not [describeOutcome], which says "the request was
     * not taken" — there was no request, and a field capture must not read a
     * no-op as a refusal.
     */
    fun describeAlreadyInBestMode(contentFps: Double, mode: DisplayModeInfo): String {
        val content = normalise(contentFps) ?: contentFps
        return "left the panel in mode ${mode.id} at ${rate(mode.refreshRate)} Hz — it is " +
            "already the best mode for ${rate(content)} fps; no request made"
    }

    /**
     * What the panel did about a request, measured a moment after it was made.
     * The platform is allowed to say no, and this is what says whether it did.
     */
    fun describeOutcome(beforeModeId: Int, afterModeId: Int, afterRate: Double): String =
        if (afterModeId == beforeModeId) {
            "the panel is still in mode $beforeModeId — the request was not taken"
        } else {
            "the panel moved to mode $afterModeId at ${rate(afterRate)} Hz"
        }

    /**
     * A resume found the panel already right for this content, so nothing was
     * asked. Distinct from a refused request for the same reason
     * [describeAlreadyInBestMode] is: a field capture must not read a no-op as
     * a refusal.
     */
    fun describeResumeNoRequest(contentFps: Double, mode: DisplayModeInfo?): String {
        val content = normalise(contentFps) ?: contentFps
        return if (mode != null) {
            "resumed; the panel is still in mode ${mode.id} at ${rate(mode.refreshRate)} Hz " +
                "for ${rate(content)} fps — no request made"
        } else {
            "resumed; the surface already carries ${rate(content)} fps and the panel " +
                "offers no better mode — no request made"
        }
    }

    /**
     * The surface already carries this rate, so asking again was refused before
     * it reached the platform.
     */
    fun describeSurfaceAlreadyAt(contentFps: Double): String {
        val content = normalise(contentFps) ?: contentFps
        return "left the surface at ${rate(content)} fps — it already carries that rate; " +
            "no request made"
    }

    /** Which way this Android version asks, for the diagnostics row. */
    fun describePath(sdkInt: Int): String = when {
        sdkInt >= Build.VERSION_CODES.S ->
            "Android 12+: the platform's frame-rate API, blank-screen switch allowed"
        sdkInt >= Build.VERSION_CODES.R ->
            "Android 11: the platform's frame-rate API, no blank-screen switch"
        else ->
            "Android 10 and below: asks for a display mode directly"
    }
}

/**
 * A refresh rate or frame rate for a human to read. Fixed to the US locale on
 * purpose: a diagnostic that says "60,000 Hz" on one TV and "60.000 Hz" on
 * another is harder to compare in a bug report, and video rates are decimal
 * points everywhere they are quoted.
 */
private fun rate(value: Double): String = String.format(Locale.US, "%.3f", value)

/**
 * The Android half: reads the panel, then asks it for the content's rate.
 *
 * The request ladder, in order:
 *
 *  1. The window's `preferredDisplayModeId`, when the panel's own mode list
 *     names a better rate than the mode it is already in. This is the request
 *     the field can verify - `display.mode.modeId` either changed or it did not
 *     - and the one that demonstrably moved the panel on the Onn 4K Plus
 *     (2026-10-17), where `Surface.setFrameRate` was logged and ignored. It
 *     needs no permission check of its own, but it cannot ask for a mode the
 *     panel does not have.
 *  2. A second look 1.5 seconds later, and a third at ~3.5 s, both at the MODE.
 *     HDMI renegotiation is slower than a surface write, and the field captures
 *     of 2026-10-22 show a 60 -> 24 switch still landing after the first look
 *     had fired. The panel is where the answer is read from - not the window
 *     attribute, which a box that ignores the request leaves sitting there
 *     looking honoured - and a mode still not there at the second look is
 *     re-asked and recorded as a refused mode.
 *  3. `Surface.setFrameRate` on the player's own surface, made only when the
 *     panel offers no mode answer at all (single-60 Hz panels), where it is the
 *     whole request. It is never made on top of a mode answer: a FIXED_SOURCE
 *     rate on a live surface under a panel force-switched to the same rate is
 *     the stutter the 2026-10-22 bisect convicted.
 *
 * The order is behind [ENGAGE_MODE_FIRST] so a field capture can bisect it:
 * off, the surface hint goes first and the mode request is its backstop, which
 * is how this shipped before the 08:47 report.
 *
 * One instance per player session, created before the player can report a video
 * track, because the value step 3 restores is captured at construction — the
 * window attribute as it was before this class ever touched it. A second
 * instance created after a switch would capture the switched value and restore
 * the panel to it, leaving the UI at film rate after the title ended.
 */
internal class FrameRateMatcher(
    private val activity: Activity,
    /**
     * The surface the player is drawing into, resolved on every ask rather than
     * captured once: the native player builds its SurfaceView during layout and
     * can swap it for a TextureView mid-session, so a captured reference would
     * be wrong exactly when it is needed. Null means "no surface to hand".
     */
    private val videoSurface: () -> Surface? = { null },
    /**
     * Whether the picture is actually moving, asked only before a pending
     * backstop fires.
     *
     * The ladder's second step is posted 1.5 s after the first, and a viewer who
     * pauses inside that window would otherwise get it against a stopped
     * picture: on a box that ignores `Surface.setFrameRate`, the request can
     * still disturb the presentation pipeline, and the disturbance shows up as
     * the stutter the viewer then resumes into. A paused session has nothing to
     * repair - on the way back the resume re-offer re-runs the ladder from the
     * top (see [apply]).
     */
    private val isPlaybackActive: () -> Boolean = { true }
) {

    private val originalPreferredModeId = activity.window.attributes.preferredDisplayModeId
    private val handler = Handler(Looper.getMainLooper())

    /**
     * When the CURRENT SOURCE opened, for [FrameRateMatch.engageWindowAllows].
     *
     * Per source, not per activity (audit AFR C1). The matcher is created once
     * per activity - the Native player guards on `frameRateMatcher == null`, MPV
     * keeps it until `onDestroy` - so a field measured from construction
     * refused every offer after the first 90 s of the ACTIVITY, which is why an
     * in-player source switch or a live zap in a long session got no mode switch
     * at all. [beginSource] restarts it; the comments' "no HDMI renegotiation
     * under a playing film" rationale still holds, because the window that
     * matters is the one measured from the source's own opening.
     */
    private var openedAtMs = SystemClock.elapsedRealtime()

    /**
     * The mode this session holds the panel to: asked for by id, or taken over
     * by finding the panel already in it. There is one field rather than a
     * "requested" one plus a "held" one (audit AFR C5): a takeover makes no
     * request, so a session keyed on the requested id alone had nothing for
     * [verifyEngagedMode] to hold even though it owns the restore.
     */
    private var heldModeId = UNSET

    /**
     * Whether leaving still owes the panel a restore.
     *
     * True once this session asked for a mode, and also when it found the panel
     * already in the best mode and took that state over without asking: after an
     * engine switch the successor is the session that is left holding the panel,
     * and it has to be the one that puts it back — the engine that engaged it has
     * stopped (not finished) and its own window is long gone by then.
     */
    private var restoreOnRelease = false

    /** Whether a rate has been put on the player's surface. */
    private var surfaceRateApplied = false

    /**
     * The surface this session last handed a rate to, and that rate.
     *
     * Together they make the hint idempotent. Re-applying the same rate to the
     * same live surface is not a no-op on every box: the field capture of
     * 2026-10-18 saw choppy playback and "no present fence" from exactly that,
     * when a resume re-offered a rate the surface already carried. A surface the
     * player has swapped (a TextureView replacing a SurfaceView) is a different
     * object, so it is asked again - which is the case the request exists for.
     */
    private var surfaceRateSurface: Surface? = null

    /** The rate [surfaceRateSurface] was asked for. */
    private var surfaceRateValue = 0f

    /** Bumped per request, so only the newest second look runs. */
    private var askSerial = 0

    /**
     * The last rate and source offered, and whether that offer did anything.
     *
     * [apply] runs the whole ladder - display read, mode list, panel string, a
     * diagnostics rewrite and possibly a fresh confirm that drops the previous
     * one's second look - and the player is allowed to call it as often as it
     * likes. media3 can report several video formats per group, so a
     * multi-format track list re-ran all of that per format, mid-opening (audit
     * AFR C10). A repeat of the same rate from the same half of the app, after
     * something has already been engaged, is a no-op by definition.
     */
    private var lastOfferRate: Double? = null
    private var lastOfferSource: FrameRateSource? = null

    /**
     * When this SOURCE first offered a usable rate, or null before it has (audit
     * AFR S9).
     *
     * The engage window is measured from here rather than from construction, so a
     * slow host's late first reading is still the opening of its own picture.
     * Reset by [beginSource], because a switched source opens its window again.
     */
    private var firstOfferAtMs: Long? = null

    /** Set by [release]; a second look that lands after it does nothing. */
    private var released = false

    /** Set once [verifyEngagedMode] has put the panel back after it moved. */
    private var reassertedMode = false

    /** Last look at the panel from [verifyEngagedMode]. */
    private var lastVerifyMs = 0L

    /**
     * Offers one content frame rate. Safe to call as often as the player likes:
     * a rate that needs no switch, a panel that cannot do it, and a repeat of a
     * request already made all resolve to doing nothing.
     */
    fun onContentFrameRate(
        contentFps: Double,
        source: FrameRateSource = FrameRateSource.PLAYER
    ) {
        released = false
        runCatching { apply(contentFps, source) }
            .onFailure { Log.w(TAG, "frame-rate match failed", it) }
    }

    /**
     * Whether this session has already taken the panel's rate as its own — a
     * mode it asked for by id, a state it took over, or a rate on the surface.
     * What a resume re-arms against: once the answer is yes, re-offering the same
     * rate is a no-op (see [apply]), and a setting switched off while the player
     * was away has something to undo ([release]).
     */
    val hasEngaged: Boolean
        get() = restoreOnRelease || surfaceRateApplied

    /**
     * Drops a confirm still waiting to look at the panel, and nothing else: the
     * request it was posted for, the mode this session asked for and the rate
     * already on the surface all stay.
     *
     * For a stop that is not an exit — a pause, the screen going off, the engine
     * switch — where a confirm posted at open would otherwise fire its backstop
     * at a panel the player is not drawing to, and the panel itself must stay at
     * the film rate for the resume.
     */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Starts a new SOURCE inside this activity (audit AFR C1).
     *
     * The matcher is built once per activity while a source switch rebuilds the
     * player, so everything measured from construction was measured from the
     * previous title: the engage window expired after 90 s and the next source
     * was refused as a "late" offer, and a take-over from the previous source
     * kept [heldModeId] pointing at a mode this source never asked for. Both are
     * per-source facts.
     *
     * [restoreOnRelease] and [originalPreferredModeId] are deliberately NOT
     * reset: the pre-title mode is still what the way out owes, and a new
     * "original" captured now would be the film rate this session itself set.
     */
    fun beginSource() {
        cancelPending()
        openedAtMs = SystemClock.elapsedRealtime()
        heldModeId = UNSET
        reassertedMode = false
        lastVerifyMs = 0L
        // A new source's first offer is news even when it happens to repeat
        // the previous source's rate (audit AFR C10's dedupe is per source).
        lastOfferRate = null
        lastOfferSource = null
        // Its window starts with its own first offer (audit AFR S9).
        firstOfferAtMs = null
        Log.i(TAG, "new source: engage window restarted")
    }

    /**
     * Holds the panel to the mode this session engaged.
     *
     * The engage ladder runs once, in the opening seconds, and then never looks
     * at the panel again - so a panel that leaves the chosen mode later (the
     * TV's own display setting, an HDMI renegotiation, another app's mode being
     * restored underneath) stays wrong for the rest of the film, and the judder
     * this feature exists to remove comes back partway through with nothing
     * having asked again. That is the "AFR on and it still judders halfway
     * through" shape.
     *
     * Called from the player's one-second tick, but throttled to
     * [VERIFY_INTERVAL_MS] and only while the picture is moving. **One repair
     * per session**: a box that keeps dropping the mode would otherwise be
     * renegotiating HDMI in a loop, and each renegotiation is itself a hitch.
     * The repair re-asks the mode this session already chose - [apply]'s engage
     * window still refuses to pick a NEW mode mid-film.
     *
     * Held is [heldModeId], which a request by id sets and a TOOK-OVER panel
     * sets too: a session that found the panel already at the film rate asked
     * for nothing, but it is still the session that owes the restore, and it was
     * precisely the one that used to stop holding (audit AFR C5). Nothing
     * happens for a session that never engaged at all.
     */
    fun verifyEngagedMode() {
        if (released || heldModeId == UNSET || reassertedMode) return
        if (!isPlaybackActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVerifyMs < VERIFY_INTERVAL_MS) return
        lastVerifyMs = now
        val display = displayOf() ?: return
        val currentModeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        if (currentModeId == heldModeId) return
        // A sink change can take the engaged mode off the panel's own list
        // (audit AFR S7): re-asserting an id the new sink does not offer is a
        // request that cannot land, so check the list before asking. An empty
        // list means the platform would not say, and the id came from this panel
        // minutes earlier - ask anyway, the platform is allowed to refuse.
        val modes = supportedModes(display)
        if (modes.isNotEmpty() && modes.none { it.id == heldModeId }) {
            reassertedMode = true
            FrameRateDiagnostics.setOutcome(
                FrameRateMatch.describeModeGone(heldModeId, currentModeId)
            )
            Log.i(
                TAG,
                "panel left the engaged mode $heldModeId and no longer offers it " +
                    "(in $currentModeId); not re-asked"
            )
            return
        }
        reassertedMode = true
        setPreferredModeId(heldModeId)
        FrameRateDiagnostics.setOutcome(
            FrameRateMatch.describeReasserted(heldModeId, currentModeId)
        )
        PlaybackSessionTrace.note(
            "afr re-asked the engaged mode: mode=$heldModeId was $currentModeId"
        )
        Log.i(TAG, "panel left the engaged mode $heldModeId for $currentModeId; re-asked")
    }

    /** Puts the panel back. Called from the player's teardown. */
    fun release() {
        released = true
        cancelPending()
        runCatching { clearSurfaceRate() }
            .onFailure { Log.w(TAG, "could not clear the surface frame rate", it) }
        if (!restoreOnRelease) return
        restoreOnRelease = false
        heldModeId = UNSET
        runCatching {
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = originalPreferredModeId
            activity.window.attributes = attributes
            Log.i(TAG, "restored display mode preference $originalPreferredModeId")
        }.onFailure { Log.w(TAG, "could not restore the display mode", it) }
    }

    private fun apply(contentFps: Double, source: FrameRateSource) {
        val offeredRate = FrameRateMatch.normalise(contentFps)
        // A repeat of a rate this session has already acted on, from the same
        // half of the app, asks nothing and says nothing (audit AFR C10). Only
        // suppressed once something is engaged: before that a repeat is still
        // the first real attempt, and a probe that failed silently must not be
        // locked out by the player's own (unusable) report of the same rate.
        if (offeredRate != null &&
            offeredRate == lastOfferRate &&
            source == lastOfferSource &&
            (heldModeId != UNSET || surfaceRateApplied)
        ) {
            Log.i(TAG, "repeat ${rate(offeredRate)} fps offer from $source; nothing to do")
            return
        }
        offeredRate?.let {
            lastOfferRate = it
            lastOfferSource = source
        }
        val display = displayOf() ?: return
        val modeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        val allModes = supportedModes(display)
        // A switch may not trade resolution for refresh rate (audit AFR C3), so
        // every candidate list below is the panel's list minus anything smaller
        // than the mode it is currently in. The panel LINE still lists them all:
        // it answers "does this TV offer 24 Hz?", which is a different question.
        val modes = FrameRateMatch.atLeastResolution(
            allModes,
            allModes.firstOrNull { it.id == modeId }
        )
        val panel = FrameRateMatch.describeModes(allModes, modeId)
        // Cleared first, then written: the clear drops the previous title's
        // request AND outcome AND panel, so the row can never describe a
        // playback that already ended (audit AFR C8's staleness half).
        FrameRateDiagnostics.clearRequest()
        FrameRateDiagnostics.setPanel(panel)
        // Which half of the app supplied this number, first in the sequence.
        // Nothing is written for a rate that does not describe content, so a
        // track the player could not measure adds no line, exactly as before.
        val normalised = offeredRate
        normalised?.let {
            FrameRateDiagnostics.addRequest(FrameRateMatch.describeSource(it, source))
        }
        Log.i(TAG, "panel: $panel")

        // The panel is only moved while a title is opening (see
        // [FrameRateMatch.ENGAGE_WINDOW_MS]). A late offer is a rebuilt player
        // re-reporting a track, a source switched mid-film, or a probe that
        // finished long after the picture did - and switching the mode for it
        // renegotiates HDMI under a playing film, which is the judder that
        // starts partway through. Recorded rather than silently dropped, so a
        // field capture can name what was still asking.
        //
        // Measured from this source's first usable offer, not from the player
        // build (audit AFR S9): a slow host's first rate can land well after
        // construction while the picture has only just started, and refusing it
        // left the whole title unmatched.
        val nowMs = SystemClock.elapsedRealtime()
        if (normalised != null && firstOfferAtMs == null) firstOfferAtMs = nowMs
        val ageMs = FrameRateMatch.engageAgeMs(nowMs, openedAtMs, firstOfferAtMs)
        if (!FrameRateMatch.engageWindowAllows(ageMs, source)) {
            FrameRateDiagnostics.setOutcome(FrameRateMatch.describeLateOffer(normalised, ageMs))
            Log.i(
                TAG,
                "ignored a ${rate(contentFps)} fps offer ${ageMs}ms into playback; " +
                    "the panel stays in mode $modeId"
            )
            return
        }

        // A resume can only repair the MODE, never the surface.
        //
        // Re-offering the rate on the way back into a session used to fall
        // through to the surface hint, which is a request against the LIVE
        // surface mid-playback - and that is the one the field capture of
        // 2026-10-18 links to choppy playback and "no present fence" after a
        // pause and resume. The surface was asked at open, before decode, which
        // is the smooth point for it; the panel is either still in the mode
        // this session asked for (a true no-op) or the system put it back while
        // the player was away, and then the mode request is the right repair.
        // The only surface case left is a surface the player swapped while the
        // viewer was away, which the idempotence guard in [askSurface] allows.
        if (source == FrameRateSource.RESUME) {
            val best = FrameRateMatch.bestMode(contentFps, modes)
            when {
                best != null && best.id != modeId -> requestMode(best, contentFps, modeId)
                best != null -> {
                    FrameRateDiagnostics.setOutcome(
                        FrameRateMatch.describeResumeNoRequest(contentFps, best)
                    )
                    Log.i(TAG, "resume: panel already right for ${rate(contentFps)} fps; no request")
                }
                // No better mode than the one the panel is in: only a surface
                // that does not already carry the rate is asked.
                else -> askSurfaceIfPossible(contentFps, modeId)
            }
            return
        }

        if (ENGAGE_MODE_FIRST) {
            // Mode id first: it is the request the field can verify
            // (display.mode.modeId changes) and the one that demonstrably moved
            // the panel on the Onn 4K Plus, where Surface.setFrameRate was
            // logged and ignored. Asking this way also means that call is never
            // made at all on a box where the mode request works.
            //
            // The search is the exclusion-free one, because the two reasons the
            // old search answered null are not the same thing:
            //
            //  - the panel has no better mode at all (a single-rate panel,
            //    typically). The surface hint is its whole request, as it always
            //    was.
            //  - the panel is ALREADY in the best mode, whether or not this
            //    session is what put it there. A mode request would be a no-op,
            //    but falling through to the surface hint is not: re-applying a
            //    rate to the live surface breaks presentation on the Onn 4K Plus
            //    — choppy playback plus "no present fence" from the resume
            //    re-arm (field, 2026-10-18), and the same write under a panel
            //    the platform had already switched is the 2026-10-22 stutter.
            //    Ask for nothing, say so on the diagnostics row, and keep the
            //    restore debt.
            val best = FrameRateMatch.bestMode(contentFps, modes)
            when {
                best == null -> askSurfaceIfPossible(contentFps, modeId)
                best.id != modeId -> requestMode(best, contentFps, modeId)
                else -> {
                    // Already in the best mode, whether or not this session is
                    // what put the panel there (2026-10-22, Door 1). A mode
                    // request would be a no-op, and the surface hint is not one:
                    // re-applying a rate to the live surface under a panel
                    // already forced to that rate is the combination the
                    // 2026-10-18 and 2026-10-22 captures convict. Ask for
                    // nothing, and take the panel's state over so it is still
                    // put back on the way out (an engine switch leaves this
                    // session as the only one holding it).
                    restoreOnRelease = true
                    // Taken over, so held: this session owes the restore, and a
                    // panel that leaves the mode mid-film has to be asked back
                    // by the session that took it on (audit AFR C5).
                    heldModeId = best.id
                    FrameRateDiagnostics.setOutcome(
                        FrameRateMatch.describeAlreadyInBestMode(contentFps, best)
                    )
                    PlaybackSessionTrace.note(
                        "afr took over an already-matched panel: mode=${best.id} " +
                            "${rate(contentFps)} fps"
                    )
                    Log.i(
                        TAG,
                        "already in the best mode for ${rate(contentFps)} fps; no request made"
                    )
                }
            }
            return
        }
        // Pre-fix ladder, for the field bisect: the surface hint first, and the
        // mode request only as its backstop.
        if (askSurfaceIfPossible(contentFps, modeId)) return
        forceMode(contentFps, modes, modeId)
    }

    /**
     * The surface hint, when this Android version and this session's surface
     * allow it. False means there is nothing to ask: either the platform has no
     * frame-rate API, or the player has no valid surface yet (the native
     * player's layout may not have built one). The caller decides what the
     * absence leaves it with.
     *
     * Reachable only from the `best == null` arm of [apply]'s mode-first block
     * and from the pre-fix (bisect) ladder: a panel that has a mode answer is
     * never handed a rate on its live surface.
     */
    private fun askSurfaceIfPossible(contentFps: Double, modeId: Int): Boolean {
        val request = FrameRateMatch.surfaceRateRequest(contentFps, Build.VERSION.SDK_INT)
            ?: return false
        val surface = videoSurface() ?: return false
        if (!surface.isValid) return false
        return askSurface(surface, request, modeId, contentFps)
    }

    /** True when the surface was actually asked (see the idempotence guard). */
    private fun askSurface(
        surface: Surface,
        request: SurfaceRateRequest,
        modeId: Int,
        contentFps: Double
    ): Boolean {
        // The same rate on the same surface is not a request. This is what
        // keeps a resume - or a second offer of the same rate from the probe and
        // the player - from re-applying the hint to a live surface, which
        // breaks presentation on the boxes that ignore it.
        if (surface === surfaceRateSurface && request.frameRate == surfaceRateValue) {
            FrameRateDiagnostics.setOutcome(
                FrameRateMatch.describeSurfaceAlreadyAt(contentFps)
            )
            Log.i(TAG, "surface already at ${request.frameRate} fps; no request made")
            return false
        }
        val strategy = request.changeFrameRateStrategy
        if (strategy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
                request.frameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                strategy
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(request.frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } else {
            return false
        }
        surfaceRateApplied = true
        surfaceRateSurface = surface
        surfaceRateValue = request.frameRate
        FrameRateDiagnostics.addRequest(
            FrameRateMatch.describeSurfaceRequest(request, strategy != null)
        )
        PlaybackSessionTrace.note(
            "afr asked the surface: ${request.frameRate} fps mode=$modeId " +
                "nonSeamless=${strategy != null}"
        )
        Log.i(TAG, "surface asked for ${request.frameRate} fps (non-seamless=${strategy != null})")
        val serial = ++askSerial
        handler.postDelayed(
            { confirm(serial, modeId, contentFps, EngageStep.SURFACE) },
            CONFIRM_DELAY_MS
        )
        return true
    }

    /**
     * Asks for a mode by id, then has it confirmed like every other request.
     * Safe to call with any mode the panel reports: the platform is allowed to
     * refuse, and the confirm is what says whether it did.
     */
    private fun requestMode(chosen: DisplayModeInfo, contentFps: Double, currentModeId: Int) {
        setPreferredModeId(chosen.id)
        // The mode this session holds, and the one the restore debt rides on.
        heldModeId = chosen.id
        restoreOnRelease = true
        FrameRateDiagnostics.addRequest(
            FrameRateMatch.describeModeSwitch(contentFps, chosen, currentModeId)
        )
        PlaybackSessionTrace.note(
            "afr asked for a mode: mode=${chosen.id} ${chosen.refreshRate} Hz for " +
                "${rate(contentFps)} fps (was $currentModeId)"
        )
        Log.i(
            TAG,
            "forced mode ${chosen.id} at ${chosen.refreshRate} Hz for " +
                "${rate(contentFps)} fps (was $currentModeId)"
        )
        val serial = ++askSerial
        handler.postDelayed(
            { confirm(serial, currentModeId, contentFps, EngageStep.MODE, chosen) },
            CONFIRM_DELAY_MS
        )
    }

    /** Writes the one attribute a mode request is: the window's preferred mode id. */
    private fun setPreferredModeId(modeId: Int) {
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = modeId
        activity.window.attributes = attributes
    }

    /**
     * The mode search, used by the pre-fix order only (the bisect switch): from
     * [apply] when there is no surface to hint at, and from the surface
     * confirm as its backstop. A panel with no better mode than the one it is
     * in records that, so the diagnostics row can say why nothing was switched.
     */
    private fun forceMode(contentFps: Double, modes: List<DisplayModeInfo>, currentModeId: Int) {
        val chosen = FrameRateMatch.chooseMode(contentFps, modes, currentModeId)
        if (chosen == null) {
            FrameRateDiagnostics.addRequest(FrameRateMatch.describeNoMatch(contentFps, modes))
            return
        }
        requestMode(chosen, contentFps, currentModeId)
    }

    /**
     * Looks at the panel a moment after a request, and — for a mode — again
     * later. A TV whose own display settings forbid a blank-screen switch will
     * simply not have moved, and the difference between that and a switch is
     * what the diagnostics row reports.
     *
     * The first look at 1.5 s suits the surface hint, whose answer is immediate.
     * A mode request needs longer: HDMI renegotiation is slower than that, and
     * the 2026-10-22 capture caught the mode landing only after the first look
     * had fallen through. So a mode that has not moved gets a SECOND look at
     * ~3.5 s — and it is still a look at the mode. Once the panel has a mode
     * answer the surface is never asked (2026-10-22, Door 2): that write on a
     * live surface under a panel being force-switched to the same rate is the
     * combination the field captures convict.
     */
    private fun confirm(
        serial: Int,
        beforeModeId: Int,
        contentFps: Double,
        step: EngageStep,
        requestedMode: DisplayModeInfo? = null
    ) {
        if (released || serial != askSerial) return
        // Nothing to repair while the picture is stopped, and a backstop that
        // fires here is exactly the mid-session surface request this ladder is
        // trying to avoid. The resume re-offer starts the ladder again.
        if (!isPlaybackActive()) return
        val display = displayOf() ?: return
        val afterModeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        val afterRate = runCatching { display.mode.refreshRate.toDouble() }.getOrDefault(0.0)
        FrameRateDiagnostics.setOutcome(
            FrameRateMatch.describeOutcome(beforeModeId, afterModeId, afterRate)
        )
        // One trace line per outcome, not only per request (audit AFR C8 / I1):
        // a session ring that records what was asked but never what came of it
        // cannot corroborate an AFR session at all.
        PlaybackSessionTrace.note(
            "afr ${if (afterModeId != beforeModeId) "panel moved" else "panel unmoved"}: " +
                "mode=$beforeModeId->$afterModeId at $afterRate Hz step=$step"
        )
        when (step) {
            // Both looks are spent. Still not moved means the panel refused a
            // mode, and that is what the row has to say.
            EngageStep.MODE_SECOND_LOOK -> settleMode(contentFps, requestedMode, afterModeId)
            EngageStep.MODE -> {
                if (afterModeId != beforeModeId) return
                val mode = requestedMode ?: return
                // Mode-first: the next step is the SAME mode, later. Never the
                // surface hint — a mode answer already exists.
                if (ENGAGE_MODE_FIRST) {
                    scheduleSecondLook(serial, beforeModeId, contentFps, mode)
                }
                // Pre-fix order: the mode request was itself the backstop for
                // the surface hint, so it is the end of that ladder.
            }
            EngageStep.SURFACE -> {
                if (afterModeId != beforeModeId) return
                // Only the pre-fix order has a step after the surface hint: the
                // mode search needs the panel's own list, and a TV that reports
                // one mode ends here.
                if (!ENGAGE_MODE_FIRST) {
                    forceMode(contentFps, supportedModes(display), afterModeId)
                }
            }
        }
    }

    /**
     * The decision at the second look: a mode that still has not landed was
     * refused. It is re-asked by attribute one last time — never a surface vote
     * on top of it — and the row says "refused" rather than leaving the first
     * look's "not taken" line to imply the ladder had something else to try.
     */
    private fun settleMode(contentFps: Double, requested: DisplayModeInfo?, afterModeId: Int) {
        if (requested == null || afterModeId == requested.id) return
        setPreferredModeId(requested.id)
        FrameRateDiagnostics.setOutcome(
            FrameRateMatch.describeModeRefused(contentFps, requested, afterModeId)
        )
        PlaybackSessionTrace.note(
            "afr mode refused: asked mode=${requested.id} at " +
                "${rate(requested.refreshRate)} Hz for ${rate(contentFps)} fps, " +
                "panel in $afterModeId; re-asked once"
        )
        Log.i(TAG, "mode ${requested.id} still not taken; re-asked the mode, no surface request")
    }

    /** The later look at a mode that has not moved yet — the same mode, checked again. */
    private fun scheduleSecondLook(
        serial: Int,
        beforeModeId: Int,
        contentFps: Double,
        mode: DisplayModeInfo
    ) {
        handler.postDelayed(
            { confirm(serial, beforeModeId, contentFps, EngageStep.MODE_SECOND_LOOK, mode) },
            SECOND_CONFIRM_DELAY_MS
        )
    }

    /**
     * Hands the surface back its default rate. The platform documents a 0 rate
     * as how a request is cleared once the picture stops, and it is what puts
     * the panel back to the rate the rest of the TV interface expects.
     */
    private fun clearSurfaceRate() {
        if (!surfaceRateApplied) return
        // The vote was cast on THIS surface, so it is this surface that has to
        // take it back (audit AFR C6). Re-resolving `videoSurface()` here asked
        // whichever surface the player happens to hold at release time: after a
        // SurfaceView -> TextureView fallback (or with the P5 GLES view in
        // front) the voted surface was never cleared, and the flags said it had
        // been. The current surface is only a fallback for a stored one the
        // process no longer has.
        val voted = surfaceRateSurface
        val surface = voted?.takeIf { it.isValid } ?: videoSurface()?.takeIf { it.isValid }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface == null) {
            // Nothing could be written. Keep the state so a later release (the
            // activity's own onDestroy after a transient stop) tries again,
            // rather than reporting a clear that never happened.
            Log.i(TAG, "surface frame rate left in place: no usable surface to clear")
            return
        }
        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        surfaceRateApplied = false
        surfaceRateSurface = null
        surfaceRateValue = 0f
        Log.i(TAG, "cleared the surface frame rate")
    }

    private fun supportedModes(display: Display): List<DisplayModeInfo> =
        runCatching {
            display.supportedModes.map {
                DisplayModeInfo(
                    id = it.modeId,
                    refreshRate = it.refreshRate.toDouble(),
                    // physical* are the mode's own pixel size - what the switch
                    // would actually put on screen (audit AFR C3).
                    width = it.physicalWidth,
                    height = it.physicalHeight
                )
            }
        }.getOrDefault(emptyList())

    private fun displayOf(): Display? = displayFor(activity)

    /** Which ladder step a confirm is looking at the panel for. */
    private enum class EngageStep { MODE, MODE_SECOND_LOOK, SURFACE }

    private companion object {
        const val TAG = "PLAYER_AFR"

        /** No mode has been asked for by id. */
        const val UNSET = -1

        /**
         * Engage order. false restores the pre-fix ladder - the surface hint
         * first, the mode request as its backstop - for a field bisect.
         */
        const val ENGAGE_MODE_FIRST = true

        /**
         * How long the platform is given to pick a mode before the panel is
         * asked the direct way. Long enough for a blank-screen switch (a second
         * or two) to have landed, short enough to stay inside the opening of
         * the title.
         */
        const val CONFIRM_DELAY_MS = 1500L

        /**
         * How long after the first look the second one lands: 2 s on top of the
         * 1.5 s above, so ~3.5 s from the mode request. Long enough for a slow
         * HDMI renegotiation to finish, and still inside the opening minute of
         * the title.
         */
        const val SECOND_CONFIRM_DELAY_MS = 2000L

        /**
         * How often [verifyEngagedMode] may look at the panel. A mode read is
         * free; the repair it may lead to is not, so the look is cheap and the
         * repair is once per session.
         */
        const val VERIFY_INTERVAL_MS = 20_000L
    }
}

/**
 * What the last playback asked the panel for, for the diagnostics row in
 * Settings and for a logcat that a report can quote. The whole failure mode of
 * frame-rate matching is silence — a TV that refuses the request and a panel
 * that had nothing better to offer both look like nothing happening — so the
 * lines that tell them apart are kept where a person on a couch can read them.
 *
 * A [StateFlow] rather than plain fields because the reader is a settings
 * screen: it is composed once and stopped while the player runs, and a value
 * that only updates on recomposition would show the state from before the very
 * playback it is meant to explain.
 */
internal data class FrameRateReport(
    val panel: String? = null,
    val request: String? = null,
    val outcome: String? = null
)

internal object FrameRateDiagnostics {

    private val _report = MutableStateFlow(FrameRateReport())

    val report: StateFlow<FrameRateReport> = _report.asStateFlow()

    fun setPanel(line: String) {
        _report.value = _report.value.copy(panel = line)
        publish()
    }

    /**
     * Mirrors the row into [AfrTrace], which is what COPY DIAGNOSTICS prints
     * (audit AFR C8 / I1). Called from every mutator rather than from the
     * reader, because the report is read after the player has gone.
     */
    private fun publish() {
        val value = _report.value
        AfrTrace.set(value.panel, value.request, value.outcome)
    }

    /**
     * Clears the request sequence, the outcome and the PANEL line.
     *
     * The panel line used to survive, so Settings could show the previous
     * title's panel next to the new title's request - a row describing two
     * different playbacks (audit AFR C8). [FrameRateMatcher.apply] writes the
     * panel line back immediately after this call, so nothing is lost for the
     * session it belongs to.
     */
    fun clearRequest() {
        _report.value = FrameRateReport()
        publish()
    }

    /** Records one request. More than one per session is shown as a sequence. */
    fun addRequest(line: String) {
        val current = _report.value
        _report.value = current.copy(
            request = listOfNotNull(current.request, line).joinToString(" · ")
        )
        publish()
    }

    fun setOutcome(line: String) {
        _report.value = _report.value.copy(outcome = line)
        publish()
    }
}

/**
 * The display this context draws on, by the only route each API level has.
 * Null when the context is not attached to one (a non-visual context), which
 * is why every caller treats a null as "cannot say" rather than an error.
 */
@Suppress("DEPRECATION")
private fun displayFor(context: Context): Display? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return runCatching { context.display }.getOrNull()
    }
    val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    return runCatching { manager?.defaultDisplay }.getOrNull()
}

/**
 * The panel as a diagnostic line — what it is showing and everything it reports
 * it can show — for the Settings row. Read live, so it answers "does this TV
 * even offer 24 Hz?" before anything has been played.
 */
internal fun displayReport(context: Context): String? {
    val display = displayFor(context) ?: return null
    val current = runCatching { display.mode.modeId }.getOrNull() ?: return null
    val modes = runCatching {
        display.supportedModes.map {
            DisplayModeInfo(
                it.modeId,
                it.refreshRate.toDouble(),
                it.physicalWidth,
                it.physicalHeight
            )
        }
    }.getOrDefault(emptyList())
    return FrameRateMatch.describeModes(modes, current)
}
