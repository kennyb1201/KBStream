package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
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
internal data class DisplayModeInfo(val id: Int, val refreshRate: Double)

/**
 * What to hand `Surface.setFrameRate`.
 *
 * [changeFrameRateStrategy] is the non-seamless opt-in — the switch that blanks
 * the screen for a moment, which is the only kind that reaches 24 Hz for film
 * from a 60 Hz panel. The platform will not make such a switch unless both the
 * app asks for it and the user has allowed it in the TV's own display settings,
 * so this is a request, not an instruction. It is null on an Android version
 * that has no such flag, where only a switch the panel can do without blanking
 * is possible.
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
     * The panel mode to switch to, or null when the panel must be left alone.
     *
     * A mode qualifies when its rate is an exact multiple of the content rate
     * within [TOLERANCE] — 50 Hz for 25 fps content, 60 Hz for 29.97 — and the
     * smallest such multiple wins, because that is the one with the fewest
     * repeated frames. Ties break on the smaller rate difference, then on the
     * lower mode id so the answer does not depend on the order the box reports
     * its modes in. Null also covers "already there": [currentModeId] matching
     * the winner means there is nothing to do.
     */
    fun chooseMode(
        contentFps: Double,
        modes: List<DisplayModeInfo>,
        currentModeId: Int
    ): DisplayModeInfo? {
        val content = normalise(contentFps) ?: return null

        var best: DisplayModeInfo? = null
        var bestMultiple = 0
        var bestDiff = 0.0

        modes.forEach { mode ->
            if (!mode.refreshRate.isFinite() || mode.refreshRate <= 0.0) return@forEach
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
                    (diff <= bestDiff + 1e-9 && mode.id < currentBest.id)
            }
            if (better) {
                best = mode
                bestMultiple = multiple
                bestDiff = diff
            }
        }

        return best?.takeIf { it.id != currentModeId }
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
            "mode ${now.id} at ${rate(now.refreshRate)} Hz"
        } else {
            "mode $currentModeId (not in the panel's list)"
        }
        if (modes.isEmpty()) return "$showing; the panel reports no modes at all"
        val listed = modes.take(MAX_LISTED_MODES)
            .joinToString(", ") { "${it.id}@${rate(it.refreshRate)}" }
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

    /** A specific mode forced through the window's display-mode attribute. */
    fun describeModeSwitch(contentFps: Double, chosen: DisplayModeInfo, wasModeId: Int): String {
        val content = normalise(contentFps) ?: contentFps
        return "asked for mode ${chosen.id} at ${rate(chosen.refreshRate)} Hz for " +
            "${rate(content)} fps (was mode $wasModeId)"
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
 *  1. `Surface.setFrameRate` on the player's own surface, which is the API
 *     Android documents for this. From API 31 it carries the non-seamless
 *     opt-in, so it is the only request that can reach 24 Hz from a 60 Hz panel.
 *  2. A second look 1.5 seconds later. A TV whose own display settings forbid
 *     the switch will have refused by then, and the panel is where the answer
 *     is read from — not from the window attribute, which a box that ignores
 *     the request leaves sitting there looking honoured.
 *  3. Only then, and only when the panel's own mode list names a rate that is an
 *     exact multiple of the content, the window's `preferredDisplayModeId`. That
 *     is the request this shipped with first: stronger against a firmware that
 *     ignores the platform's scheduler, and useless against one that reports a
 *     single mode. Asking for it needs no permission check of its own, but it
 *     cannot ask for a mode the panel does not have.
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
    private val videoSurface: () -> Surface? = { null }
) {

    private val originalPreferredModeId = activity.window.attributes.preferredDisplayModeId
    private val handler = Handler(Looper.getMainLooper())

    /** The mode this session asked for by id, or [UNSET] when it has asked for none. */
    private var appliedModeId = UNSET

    /** Whether a rate has been put on the player's surface. */
    private var surfaceRateApplied = false

    /** Bumped per request, so only the newest second look runs. */
    private var askSerial = 0

    /** Set by [release]; a second look that lands after it does nothing. */
    private var released = false

    /**
     * Offers one content frame rate. Safe to call as often as the player likes:
     * a rate that needs no switch, a panel that cannot do it, and a repeat of a
     * request already made all resolve to doing nothing.
     */
    fun onContentFrameRate(contentFps: Double) {
        released = false
        runCatching { apply(contentFps) }
            .onFailure { Log.w(TAG, "frame-rate match failed", it) }
    }

    /** Puts the panel back. Called from the player's teardown. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching { clearSurfaceRate() }
            .onFailure { Log.w(TAG, "could not clear the surface frame rate", it) }
        if (appliedModeId == UNSET) return
        appliedModeId = UNSET
        runCatching {
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = originalPreferredModeId
            activity.window.attributes = attributes
            Log.i(TAG, "restored display mode preference $originalPreferredModeId")
        }.onFailure { Log.w(TAG, "could not restore the display mode", it) }
    }

    private fun apply(contentFps: Double) {
        val display = displayOf() ?: return
        val modeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        val modes = supportedModes(display)
        val panel = FrameRateMatch.describeModes(modes, modeId)
        FrameRateDiagnostics.setPanel(panel)
        FrameRateDiagnostics.clearRequest()
        Log.i(TAG, "panel: $panel")

        val request = FrameRateMatch.surfaceRateRequest(contentFps, Build.VERSION.SDK_INT)
        val surface = videoSurface()
        if (request != null && surface != null && surface.isValid) {
            askSurface(surface, request, modeId, contentFps)
            return
        }
        // Either this Android version has no frame-rate API, or the player has
        // no surface to put it on yet — the native player's layout may not have
        // built one. The mode search needs neither, so it goes first here.
        forceMode(contentFps, modes, modeId)
    }

    private fun askSurface(
        surface: Surface,
        request: SurfaceRateRequest,
        modeId: Int,
        contentFps: Double
    ) {
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
            return
        }
        surfaceRateApplied = true
        FrameRateDiagnostics.addRequest(
            FrameRateMatch.describeSurfaceRequest(request, strategy != null)
        )
        Log.i(TAG, "surface asked for ${request.frameRate} fps (non-seamless=${strategy != null})")
        val serial = ++askSerial
        handler.postDelayed({ confirm(serial, modeId, contentFps) }, CONFIRM_DELAY_MS)
    }

    /**
     * Looks at the panel a moment after the request. A TV whose own display
     * settings forbid a blank-screen switch will simply not have moved, and the
     * difference between that and a switch is what the diagnostics row reports.
     */
    private fun confirm(serial: Int, beforeModeId: Int, contentFps: Double) {
        if (released || serial != askSerial) return
        val display = displayOf() ?: return
        val afterModeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        val afterRate = runCatching { display.mode.refreshRate.toDouble() }.getOrDefault(0.0)
        FrameRateDiagnostics.setOutcome(
            FrameRateMatch.describeOutcome(beforeModeId, afterModeId, afterRate)
        )
        if (afterModeId != beforeModeId) return
        // Refused, so the backstop gets its turn — but only the panel's own mode
        // list can say whether there is a mode worth asking for. On a TV that
        // reports one mode there is not, and that is where this ends.
        forceMode(contentFps, supportedModes(display), afterModeId)
    }

    private fun forceMode(contentFps: Double, modes: List<DisplayModeInfo>, currentModeId: Int) {
        val chosen = FrameRateMatch.chooseMode(contentFps, modes, currentModeId)
        if (chosen == null) {
            FrameRateDiagnostics.addRequest(FrameRateMatch.describeNoMatch(contentFps, modes))
            return
        }
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = chosen.id
        activity.window.attributes = attributes
        appliedModeId = chosen.id
        FrameRateDiagnostics.addRequest(
            FrameRateMatch.describeModeSwitch(contentFps, chosen, currentModeId)
        )
        Log.i(
            TAG,
            "forced mode ${chosen.id} at ${chosen.refreshRate} Hz for " +
                "${rate(contentFps)} fps (was $currentModeId)"
        )
    }

    /**
     * Hands the surface back its default rate. The platform documents a 0 rate
     * as how a request is cleared once the picture stops, and it is what puts
     * the panel back to the rate the rest of the TV interface expects.
     */
    private fun clearSurfaceRate() {
        if (!surfaceRateApplied) return
        surfaceRateApplied = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = videoSurface() ?: return
        if (!surface.isValid) return
        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        Log.i(TAG, "cleared the surface frame rate")
    }

    private fun supportedModes(display: Display): List<DisplayModeInfo> =
        runCatching {
            display.supportedModes.map { DisplayModeInfo(it.modeId, it.refreshRate.toDouble()) }
        }.getOrDefault(emptyList())

    private fun displayOf(): Display? = displayFor(activity)

    private companion object {
        const val TAG = "PLAYER_AFR"

        /** No mode has been asked for by id. */
        const val UNSET = -1

        /**
         * How long the platform is given to pick a mode before the panel is
         * asked the direct way. Long enough for a blank-screen switch (a second
         * or two) to have landed, short enough to stay inside the opening of
         * the title.
         */
        const val CONFIRM_DELAY_MS = 1500L
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
    }

    fun clearRequest() {
        _report.value = _report.value.copy(request = null, outcome = null)
    }

    /** Records one request. More than one per session is shown as a sequence. */
    fun addRequest(line: String) {
        val current = _report.value
        _report.value = current.copy(
            request = listOfNotNull(current.request, line).joinToString(" · ")
        )
    }

    fun setOutcome(line: String) {
        _report.value = _report.value.copy(outcome = line)
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
        display.supportedModes.map { DisplayModeInfo(it.modeId, it.refreshRate.toDouble()) }
    }.getOrDefault(emptyList())
    return FrameRateMatch.describeModes(modes, current)
}
