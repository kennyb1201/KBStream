package com.kennyb1201.kbstream.ui.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import coil3.load
import com.kennyb1201.kbstream.ui.components.loadLogo
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Everything the overlay's title block draws, gathered once by the host.
 *
 * The two activities already hold these facts; this is the shape they hand over
 * so the shared chrome never reaches back into the activity per field. Each is
 * optional and the overlay hides what is blank, exactly as both players did.
 */
data class ChromeTitleInfo(
    val clearLogoUrl: String? = null,
    val itemName: String? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val badges: List<StreamBadge> = emptyList(),
    val cast: List<PlayerCastMember> = emptyList()
)

/**
 * An optional companion to [PlayerChromeHost] for a host that wants cast-tile
 * presses to navigate. The required host interface draws the band; a host that
 * also implements this gets the press handed back (the main player's own
 * navigate_actor contract). Kept separate so a host with no actor screen - or a
 * test double - need not implement it.
 */
interface PlayerChromeCastHost {
    fun onChromeCastPressed(member: PlayerCastMember)
}

/**
 * The activity side of the shared overlay.
 *
 * One method per thing the overlay can ask of its player. This is a rename of
 * the calls the two activities already make, not new logic: `onChromePlayPause`
 * is the play/pause press that both already handle, `chromePositionMs` is the
 * playhead both already expose, and so on. Implementing it lets either engine
 * drive the same chrome with no overlay code of its own.
 */
interface PlayerChromeHost {
    // Transport
    fun chromeIsPlaying(): Boolean
    fun chromePositionMs(): Long
    fun chromeDurationMs(): Long
    fun onChromePlayPause()
    fun onChromeSeekTo(positionMs: Long)

    /**
     * A touch drag on the seek bar has begun. The Exo engine pauses for the drag
     * so the follow-seeks it makes have no playback deadline to miss; the default
     * does nothing, which leaves the MPV engine's own seek handling alone.
     */
    fun onChromeScrubStart() {}

    /**
     * The drag moved the thumb to [positionMs]. Called on every user progress
     * change, so an engine that wants the picture to follow must throttle this
     * itself; the default does nothing.
     */
    fun onChromeScrubProgress(positionMs: Long) {}

    /**
     * The drag ended (after [onChromeSeekTo], the exact landing). An engine that
     * paused in [onChromeScrubStart] restores its play state here; the default
     * does nothing.
     */
    fun onChromeScrubEnd() {}

    fun onChromeNext()

    // Pickers & panels
    fun onChromeOpenSourcePicker()

    /**
     * The Episodes button. Only ever called for a series, because the button is
     * hidden everywhere else (see [PlayerChrome.setEpisodesAvailable]) - the
     * default keeps a test double compiling.
     */
    fun onChromeOpenEpisodes() {}
    fun onChromeOpenAudioPicker()
    fun onChromeOpenSubtitlePicker()
    fun onChromeOpenSpeedPicker()
    fun onChromeOpenAspectPicker()
    fun onChromeOpenSettings()
    fun onChromeOpenInfo()
    fun onChromeSwitchPlayer()
    fun onChromeOpenExternal()
    fun onChromeSkipIntro()

    // Info the overlay draws
    fun chromeTitleInfo(): ChromeTitleInfo

    // Auto-hide, focus and scrub policy. Every one of these has a default that
    // reproduces the MPV engine's behaviour exactly, so only an engine that
    // needs to differ (the Exo engine, whose overlay answers to live TV and to
    // panels that can own the screen while the bar is up) overrides them.

    /**
     * Whether the bar may drop itself right now. The default is "yes"; an engine
     * whose panel owns the screen while the bar is up - or whose paused/live
     * session must keep it up - answers no here rather than running a second
     * auto-hide timer of its own.
     */
    fun chromeMayAutoHide(): Boolean = true

    /**
     * The auto-hide countdown fired. Returns true when the engine has taken the
     * bar down itself: the Exo engine's hideControls() ends a scrub and dismisses
     * panels as well, so it owns the whole response. The default (false) makes
     * the chrome hide itself, which is the MPV engine's whole answer.
     */
    fun onChromeAutoHide(): Boolean = false

    /**
     * Whether the bar may take the D-pad as it comes up. An engine with a panel
     * that was raised first returns false, so raising the overlay never steals
     * that panel's focus.
     */
    fun chromeMayTakeFocus(): Boolean = true

    /**
     * Whether the seek bar may become the bar's first stop. Live television has
     * no duration to scrub, so the Exo engine answers false there and lands on
     * its primary button instead.
     */
    fun chromeSeekBarScrubbable(): Boolean = true

    /**
     * Where UP goes from the control bar.
     *
     * The layout names the scrub bar, which is right for a session with a
     * timeline. A session that hides that row (live television) has to name
     * another stop, because focusing a GONE view is how a press gets lost:
     * the Exo engine answers with a control in the same row, since the live
     * program block above the bar is display-only and cannot take focus.
     */
    fun chromeFocusUpTargetId(): Int = R.id.chrome_seekbar

    /**
     * Parks the D-pad on the engine's primary control, for an engine whose first
     * stop is not play/pause (the Exo engine raises a live channel's CH up, or
     * the skip prompt while it is up). Returns true when it took focus; the
     * default returns false and leaves play/pause to the chrome.
     */
    fun chromeFocusPrimary(): Boolean = false
}

/**
 * Why a title slot is being repainted (spec 2026-11-24, F2/F4).
 *
 * The repaint is only allowed to re-issue a request that has NOT landed, and
 * only in the situations where a re-issue is a repair rather than a duplicate:
 *
 *  - [SETUP] is the setup paint, which runs twice back to back on both engines.
 *    It never re-issues a URL it already requested - that second cancel was the
 *    bug round 2 fixed.
 *  - [SWITCH] is a source switch. The splash goes back up and the title block
 *    is painted from the new source's facts, so an unlanded logo is re-fetched
 *    unconditionally: the second splash is exactly where the 2026-11-24 report
 *    saw no logo.
 *  - [FIRST_FRAME] means the callback arrived, so a load issued before it has
 *    had its chance.
 *
 * Both retrying repaints are also what bounds an unlanded request's life: see
 * [PlayerChrome.MAX_LOGO_ATTEMPTS].
 */
enum class LogoRepaint {
    SETUP,
    SWITCH,
    FIRST_FRAME
}

/**
 * When an unlanded logo request may be issued again (spec 2026-11-24, F2).
 *
 * Pure, so the matrix is pinned by a test rather than by reading three call
 * sites: the setup paint never retries (it runs back to back, and re-issuing
 * there is the cancel round 2 fixed), a source switch always does (the splash
 * is back up and the wordmark must be re-fetched), and anything else waits until
 * the first frame has arrived or the grace has passed since the request - the
 * two moments that say the in-flight load has had its chance.
 *
 * [MAX_ATTEMPTS] is the stop: three issues of one URL, then the trace says the
 * retries gave up. Silent death is what made the field report unreadable, not a
 * missing retry, so the cap is part of the answer, not a limitation of it.
 */
internal object LogoRetryPolicy {

    /** How long an unanswered request is given before a plain repaint may retry. */
    const val GRACE_MS = 10_000L

    /** Issues of one URL per surface, the first included. */
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(
        repaint: LogoRepaint,
        attempts: Int,
        requestedAtMs: Long,
        nowMs: Long,
        firstFrameSeen: Boolean
    ): Boolean {
        if (repaint == LogoRepaint.SETUP) return false
        if (attempts >= MAX_ATTEMPTS) return false
        if (repaint == LogoRepaint.SWITCH) return true
        return firstFrameSeen || nowMs - requestedAtMs >= GRACE_MS
    }
}

/**
 * The player overlay, once, for every engine.
 *
 * Both activities used to inflate their own copy of this chrome and wire it
 * themselves - the same views, the same order, wired twice and drifting apart.
 * This class owns the one layout ([R.layout.player_chrome], included by both
 * activities) and drives it entirely through a [PlayerChromeHost]: every button
 * calls the matching host method, [refreshProgress] paints the transport from the
 * host's playhead, [refreshTitle] paints everything [ChromeTitleInfo] carries,
 * and [setChapters] hands the marks to the shared chapter bar.
 *
 * Auto-hide timing and D-pad focus order live here too, unchanged from the two
 * activities' own: the bar comes up on [show] (focusing the bar unless a skip
 * prompt is up, exactly as both did), holds itself open while one of its controls
 * has focus, and drops again after [AUTO_HIDE_MS] without input.
 *
 * `root` is the included layer ([R.id.chrome_layer]): the overlay container is
 * found inside it, so an activity that includes the layout passes the layer and
 * nothing else. The engine-specific views (the Exo-only live block and channel
 * buttons, the MPV-only chapter strip) stay in the activity as siblings of the
 * include and are not touched here.
 */
class PlayerChrome(
    private val root: View,
    private val host: PlayerChromeHost,
    private val autoHideMs: Long = AUTO_HIDE_MS
) {

    private val overlay: View? = root.findViewById(R.id.chrome_root)
    private val clearLogo: ImageView? = root.findViewById(R.id.chrome_clear_logo)
    private val itemName: TextView? = root.findViewById(R.id.chrome_item_name)

    /**
     * The logo URLs this slot has painted and is still waiting on.
     *
     * A repaint is only cheap if it does not re-issue the load: Coil cancels the
     * request already on its way to an ImageView when a new `load` lands on it,
     * so three setup paints and a first-frame repaint used to throw away every
     * fetch but the last - which is how a carried logo vanished while the paste
     * showed no failure (spec 2026-11-24, F1/F3). A new URL simply supersedes
     * both, because issuing it cancels the old request; the old success can no
     * longer mark the new URL.
     */
    private var loadedLogoUrl: String? = null
    private var requestedLogoUrl: String? = null

    /**
     * When [requestedLogoUrl] was issued, and how many times this session has
     * tried it (spec 2026-11-24, F1/F2).
     *
     * The 2026-11-24 paste is a load that was issued and never called back: no
     * `loaded`, no `load-failed`, no `cancelled`. The dedup above made that one
     * silent death permanent for the whole session, because it skipped on
     * REQUESTED rather than on LANDED. The attempt count is what bounds the
     * retries instead of looping them.
     */
    private var requestedAtMs = 0L
    private var logoAttempts = 0
    private var retryExhaustedNoted = false
    private val episodeLabel: TextView? = root.findViewById(R.id.chrome_episode_label)
    private val episodeTitle: TextView? = root.findViewById(R.id.chrome_episode_title)
    private val overview: TextView? = root.findViewById(R.id.chrome_overview)
    private val infoBlock: View? = root.findViewById(R.id.chrome_info)
    private val clock: TextView? = root.findViewById(R.id.chrome_clock)
    private val endsAt: TextView? = root.findViewById(R.id.chrome_ends_at)
    private val seekRow: View? = root.findViewById(R.id.chrome_seekbar_row)

    /**
     * Whether this session has a timeline to scrub. Live television does not:
     * the row is GONE there (see [setSeekAvailable]) and the bar is not one of
     * the controls that hold the overlay open.
     */
    private var seekAvailable = true
    private val seekBar: ChapterSeekBar? = root.findViewById(R.id.chrome_seekbar)
    private val positionView: TextView? = root.findViewById(R.id.chrome_position)
    private val durationView: TextView? = root.findViewById(R.id.chrome_duration)
    private val badgeRow: LinearLayout? = root.findViewById(R.id.chrome_badge_row)
    private val castSection: View? = root.findViewById(R.id.chrome_cast_section)
    private val castRow: LinearLayout? = root.findViewById(R.id.chrome_cast_row)
    private val skipButton: TextView? = root.findViewById(R.id.chrome_skip_intro)
    private val playPauseButton: ImageView? = root.findViewById(R.id.chrome_btn_play_pause)
    private val nextButton: ImageView? = root.findViewById(R.id.chrome_btn_next)
    private val sourceButton: View? = root.findViewById(R.id.chrome_btn_source)
    private val episodesButton: View? = root.findViewById(R.id.chrome_btn_episodes)
    private val audioButton: View? = root.findViewById(R.id.chrome_btn_audio)
    private val subtitleButton: View? = root.findViewById(R.id.chrome_btn_subtitle)
    private val speedButton: TextView? = root.findViewById(R.id.chrome_btn_speed)
    private val aspectButton: TextView? = root.findViewById(R.id.chrome_btn_aspect)
    private val playerSwitchButton: View? = root.findViewById(R.id.chrome_btn_player_switch)
    private val externalButton: View? = root.findViewById(R.id.chrome_btn_player_external)
    private val infoButton: View? = root.findViewById(R.id.chrome_btn_info)
    private val settingsButton: View? = root.findViewById(R.id.chrome_btn_settings)

    /**
     * The D-pad's label, in the bar's own spacer (spec 2026-11-17, F3).
     *
     * A television has no hover, so the platform tooltip the phone gets (F2) is
     * unreachable there. On focus the focused button's own contentDescription is
     * shown here - the same words TalkBack reads (F7) - and empty text is the
     * hidden state, so nothing changes visibility and the bar never re-lays-out.
     */
    private val focusLabel: TextView? = root.findViewById(R.id.chrome_focus_label)

    /** The button whose label is showing, so a late blur cannot clear another's. */
    private var labelledButton: View? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The overlay's wall clock with the day and the date in front of it.
     *
     * Both engines draw this row (this is the shared chrome), so the dated
     * shape is decided in one place; the NativePlayerActivity's own writer keeps
     * the same two pairs. A bare `2:05 PM` was the one clock in the app that did
     * not say which evening it belonged to.
     */
    private val dayClock12Format by lazy { DateFormats.dayClock12h() }
    private val dayClock24Format by lazy { DateFormats.dayClock24h() }

    /** The bare-time pair, for the "Ends at" estimate. */
    private val clock12Format by lazy { DateFormats.clock12h() }
    private val clock24Format by lazy { DateFormats.clock24h() }

    /** Whether scrubbing is in progress, so a tick does not fight the drag. */
    private var scrubbing = false

    /**
     * The auto-hide countdown.
     *
     * An object expression, not a SAM lambda: the countdown REPOSTS ITSELF while
     * a panel holds the bar up (below), and inside a lambda `this` is the chrome,
     * not the runnable - so the repost would hand the handler the wrong object
     * (and does not compile). Here `this` IS the runnable being posted.
     */
    private val hideRunnable = object : Runnable {
        override fun run() {
            // A panel that owns the screen answers no here, and the bar stays up
            // until whatever raised the panel lowers it again - see
            // PlayerChromeHost.chromeMayAutoHide. The countdown REPOSTS rather than
            // dying: it used to run once, so a panel that outlived it (Settings
            // open for more than the auto-hide delay, which is ordinary) left the
            // overlay sitting over the picture until the next button press,
            // because nothing re-armed the timer when the panel closed - the panel
            // dismissals only hand focus back (audit U-F1, 2026-10-27). Reposted,
            // the bar drops on the first beat after whatever is holding it down
            // lets go.
            if (!host.chromeMayAutoHide()) {
                handler.postDelayed(this, autoHideMs)
                return
            }
            // An engine with its own teardown takes the bar down itself.
            if (!host.onChromeAutoHide()) hide()
        }
    }

    init {
        // A phone is the one window too short to hold the fixed title block: its
        // player runs landscape (see FormFactor.applyPlaybackOrientation), which
        // leaves ~390dp where the television has 540, and the cast band, the
        // badges and the controls alone take most of that. There the block keeps
        // the scroll it always had, so the episode line and the synopsis stay
        // reachable instead of being clipped. The television - the screen this
        // chrome is really for - gets the fixed layout, straight from the XML.
        if (!com.kennyb1201.kbstream.FormFactor.isTv) makeInfoBlockScrollable()
        wireButtons()
        wireLabels()
        wireSeekBar()
        wireFocusHold()
        seekBar?.max = SEEKBAR_MAX
    }

    /**
     * True while the bar is up.
     *
     * A bar that is fading out is NOT up: the fade changes only what is drawn,
     * and every press during it must behave exactly as it did when the bar left
     * in one frame (the caller has already moved focus off it). Without this
     * flag the 160ms of [hide] would report `true` and swallow the presses that
     * hide the bar.
     */
    private var hiding = false

    /** True while the overlay is on screen. */
    val isVisible: Boolean
        get() = !hiding && overlay?.visibility == View.VISIBLE

    /** True while the skip prompt is on screen. */
    val isSkipIntroVisible: Boolean
        get() = skipButton?.visibility == View.VISIBLE

    /** True while the seek bar is being dragged, so a tick leaves it alone. */
    val isScrubbing: Boolean
        get() = scrubbing

    /**
     * Brings the bar up, focuses it, and starts its auto-hide countdown.
     *
     * The views become visible in the same frame they always did - every
     * caller's `isVisible` and every focus request sees the old contract - and
     * then fade in over [com.kennyb1201.kbstream.ui.components.KB_CHROME_FADE_MS].
     * This is the app's most frequent transition: the bar rises and falls on
     * every D-pad press, on both engines, and it used to arrive as a cut.
     */
    fun show() {
        hiding = false
        KbViewMotion.fadeIn(overlay)
        // The seek row rides the overlay: it carries the bar both engines land
        // the D-pad on, so it is up exactly while the bar is.
        // ...unless the session has nothing to scrub: live television keeps the
        // row GONE, and a fade-in would bring it back (see setSeekAvailable).
        if (seekAvailable) KbViewMotion.fadeIn(seekRow) else seekRow?.visibility = View.GONE
        // The clock rides the overlay's visibility, the way both activities kept
        // it: shown and ticking exactly while the bar is, gone otherwise.
        KbViewMotion.fadeIn(clock)
        // The "Ends at" estimate is hidden by [refreshClock] when there is no
        // duration to count from (a live channel, a VOD that has not loaded).
        // Fading it in unconditionally would bring a stale line back for the
        // frame before the next tick hides it again.
        if (endsAt?.visibility != View.GONE) KbViewMotion.fadeIn(endsAt)
        // Which stop is above the bar can change between one raise and the next
        // (the live program block appears only once the channel has a lineup),
        // so the targets are re-applied on every show.
        applyFocusUpTarget()
        focusControls()
        keepVisible()
    }

    /**
     * Takes the bar down and stops the countdown.
     *
     * Focus has already been handed back by the callers (that is what
     * [focusControls]' inverse does in both activities), so the fade cannot
     * swallow a press: [isVisible] reads false from this line on, and the bar is
     * GONE at the end of it.
     */
    fun hide() {
        handler.removeCallbacks(hideRunnable)
        hiding = true
        // The label belongs to the bar, so it goes with it (F3/S4) - not with
        // the overlay's fade, which would leave a word floating over the video.
        hideFocusLabel()
        KbViewMotion.fadeOut(overlay) { hiding = false }
        KbViewMotion.fadeOut(seekRow)
        KbViewMotion.fadeOut(clock)
        KbViewMotion.fadeOut(endsAt)
    }

    /** Restarts the auto-hide countdown (a press that expects the bar to linger). */
    fun restartAutoHide() = keepVisible()

    /** Cancels the pending auto-hide; the bar stays up until [restartAutoHide]. */
    fun cancelAutoHide() {
        handler.removeCallbacks(hideRunnable)
    }

    /**
     * One progress tick: the position, the duration, the bar's own value, the
     * play/pause glyph, and the two clock readouts. Called from the activities'
     * existing tick, so the playhead stays the host's one source of truth.
     */
    fun refreshProgress() {
        val positionMs = host.chromePositionMs()
        val durationMs = host.chromeDurationMs()
        positionView?.text = formatMillis(positionMs)
        durationView?.text = formatDurationMillis(durationMs)
        if (!scrubbing) {
            seekBar?.progress = if (durationMs > 0L) {
                (positionMs * SEEKBAR_MAX / durationMs).toInt().coerceIn(0, SEEKBAR_MAX)
            } else {
                0
            }
        }
        playPauseButton?.setImageResource(
            if (host.chromeIsPlaying()) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
        // The glyph and the label are the same fact (F1/S3): "Pause" while
        // playing, "Play" while paused.
        syncPlayPauseLabel()
        refreshClock(positionMs, durationMs)
    }

    /**
     * Paints the playhead and the duration for a position the engine has scrubbed
     * to, without touching the clock or the play/pause glyph.
     *
     * The Exo engine's D-pad scrub owns a target that is AHEAD of the player - it
     * seeks opportunistically and only lands on release - so it draws the bar and
     * the readout itself through this, the same paint [refreshProgress] does minus
     * the parts that must follow the player's own position.
     */
    fun setScrubPosition(positionMs: Long, durationMs: Long) {
        positionView?.text = formatMillis(positionMs)
        if (durationMs <= 0L) return
        durationView?.text = formatDurationMillis(durationMs)
        if (!scrubbing) {
            seekBar?.progress =
                (positionMs * SEEKBAR_MAX / durationMs).toInt().coerceIn(0, SEEKBAR_MAX)
        }
    }

    /**
     * Puts [url] into the chrome's wordmark slot, or its name text when the
     * launch carries no logo.
     *
     * The one place the slot is loaded from - setup on both engines, a
     * first-frame repaint and a source switch all come through here - so the
     * chrome can tell a fresh URL from one it already painted or is already
     * fetching. The load is traced, and [onFailure] restores the name text
     * instead of leaving an empty slot over a hidden title (spec 2026-11-23,
     * F3).
     */
    internal fun showTitleLogo(
        url: String?,
        repaint: LogoRepaint = LogoRepaint.SETUP,
        onFailure: (() -> Unit)? = null
    ) {
        val logo = clearLogo
        if (logo == null || url.isNullOrBlank()) {
            // No art is the slot's reset: forget the URL so the same logo
            // returning later is loaded again instead of being "already
            // painted" into a slot this branch just emptied.
            loadedLogoUrl = null
            requestedLogoUrl = null
            requestedAtMs = 0L
            logoAttempts = 0
            retryExhaustedNoted = false
            logo?.setImageDrawable(null)
            logo?.visibility = View.GONE
            itemName?.visibility = View.VISIBLE
            return
        }
        if (url != loadedLogoUrl) {
            if (url != requestedLogoUrl) {
                issue(logo, url, onFailure)
            } else if (repaint != LogoRepaint.SETUP) {
                // Requested but not landed: the request may be silent (the
                // 2026-11-24 paste) or may still be on its way. Re-issuing
                // cancels it, and that cancellation is traced, so a retry can
                // never be invisible either. Capped so a load that never
                // answers cannot fetch forever.
                if (
                    LogoRetryPolicy.shouldRetry(
                        repaint = repaint,
                        attempts = logoAttempts,
                        requestedAtMs = requestedAtMs,
                        nowMs = SystemClock.elapsedRealtime(),
                        firstFrameSeen = repaint == LogoRepaint.FIRST_FRAME
                    )
                ) {
                    issue(logo, url, onFailure)
                } else if (logoAttempts >= LogoRetryPolicy.MAX_ATTEMPTS && !retryExhaustedNoted) {
                    retryExhaustedNoted = true
                    PlaybackSessionTrace.note("LOGO: retry-exhausted chrome")
                }
            }
        }
        logo.visibility = View.VISIBLE
        itemName?.visibility = View.GONE
    }

    /**
     * The one load call.
     *
     * It goes through [loadLogo], Coil's builder-block overload, rather than a
     * pre-built request (spec 2026-11-24, F2): the pre-built form is what went
     * silent on the player's setup load, and this call site runs during setup.
     * The `issued` line and the traced callbacks live in the block; `LOGO:
     * start` plus `issued` is what separates a call-site failure from Coil-level
     * silence.
     *
     * The issue is no longer wrapped in a swallowed `runCatching` (F4): a throw
     * is traced by name and clears the request, so a throwing load cannot leave
     * the dedup poisoned for the rest of the session.
     */
    private fun issue(logo: android.widget.ImageView, url: String, onFailure: (() -> Unit)?) {
        requestedLogoUrl = url
        requestedAtMs = SystemClock.elapsedRealtime()
        logoAttempts++
        try {
            logo.loadLogo(
                url = url,
                surface = "chrome",
                onLoaded = { loadedLogoUrl = url },
                onFailure = {
                    requestedLogoUrl = null
                    onFailure?.invoke()
                }
            )
        } catch (error: Exception) {
            // Not swallowed: named, and the dedup is cleared so the next
            // repaint is a fresh attempt instead of a permanent skip.
            requestedLogoUrl = null
            logoAttempts = 0
            PlaybackSessionTrace.note(
                "LOGO: issue-threw chrome ${error.javaClass.simpleName.ifBlank { "unknown" }}"
            )
        }
    }

    /**
     * Re-paints the title block from the host's current facts.
     *
     * The paint is [showTitleLogo], so a repaint whose logo already landed - or
     * is still on its way - issues no request at all (spec 2026-11-24, F3). That
     * is what makes the first-frame, source-switch and overlay-raise repaints
     * safe instead of each one cancelling the previous load.
     */
    fun refreshTitle(repaint: LogoRepaint = LogoRepaint.SETUP) {
        val info = host.chromeTitleInfo()

        showTitleLogo(info.clearLogoUrl, repaint = repaint) {
            // A failed load restores the text: the same fallback the no-logo
            // case gets, so the header still names the title.
            clearLogo?.setImageDrawable(null)
            clearLogo?.visibility = View.GONE
            itemName?.text = info.itemName
            itemName?.visibility = View.VISIBLE
        }
        itemName?.text = info.itemName

        episodeLabel?.text = info.episodeLabel
        episodeLabel?.visibility =
            if (!info.episodeLabel.isNullOrBlank()) View.VISIBLE else View.GONE
        episodeTitle?.text = info.episodeTitle
        episodeTitle?.visibility =
            if (!info.episodeTitle.isNullOrBlank()) View.VISIBLE else View.GONE
        overview?.text = info.overview
        overview?.visibility =
            if (!info.overview.isNullOrBlank()) View.VISIBLE else View.GONE
        // NEXT is an episode step, so it only appears for a session that knows
        // its episode - both activities showed it on the same fact.
        nextButton?.visibility =
            if (!info.episodeLabel.isNullOrBlank()) View.VISIBLE else View.GONE

        badgeRow?.let { PickerAdapter.bindBadgeRow(it, info.badges) }
        bindCast(info.cast)
    }

    /** The skip prompt, driven by the activity's own segment logic. */
    fun setSkipIntro(label: String?) {
        val button = skipButton ?: return
        if (label.isNullOrBlank()) {
            // A hidden view cannot keep focus: without a new target the next
            // D-pad press would go nowhere, so hand it back to the bar.
            // The hand-back stays synchronous; only the disappearance fades.
            val wasFocused = button.isFocused
            // GONE in this frame, not faded: `isSkipIntroVisible` and both
            // activities' key rules read `visibility`, and a prompt that is
            // still VISIBLE for the length of a fade would keep taking presses
            // for a segment that has ended. The entrance is the animation;
            // the exit is the contract.
            button.visibility = View.GONE
            // Nothing translates this pill any more (see the entrance below),
            // but a build that did could park it mid-slide if its animation was
            // cancelled by this hide - and the NEXT show would inherit the
            // offset. Clearing it here makes the plate's box its own.
            button.translationX = 0f
            if (wasFocused && isVisible) playPauseButton?.requestFocus()
        } else {
            button.text = label
            // A plain fade, in place - deliberately NOT the slide the panels
            // use. The prompt appears on nearly every television episode, and
            // it used to pop into a focused-looking button mid-frame; the slide
            // that replaced the pop put a sliver of the plate's left corner on
            // the wrong side of the view's own bounds (field, 2026-10-24: the
            // left corner drawn square, the right one round, only sometimes). A
            // translated view is composited from a render node whose bounds are
            // captured while the offset is still on it, so the left of the fill
            // is clipped by a couple of dp for a frame - never on the trailing
            // side, which is why the damage was one-sided. In place, the plate
            // and its node always agree, and the entrance still matches the
            // bar's own fade.
            KbViewMotion.fadeIn(button)
        }
    }

    /**
     * Hands the chapter marks to the shared bar. An empty list draws no ticks -
     * the bar then looks exactly like the stock SeekBar it replaces.
     */
    fun setChapters(marks: List<ChapterMark>, durationMs: Long) {
        seekBar?.setChapters(marks, durationMs)
    }

    // ── wiring ───────────────────────────────────────────────────────────

    /**
     * Wraps the info block in a [ScrollView], for the phone only.
     *
     * The block is fixed in the layout because a television must never scroll
     * its chrome (see [R.layout.player_chrome]). A phone's player is landscape
     * on a window barely 390dp tall, and this chrome is not the phone's own - it
     * is the television's, shared whole. There the fixed block is taller than
     * the room the controls leave, so it would be clipped; the scroll that was
     * removed for the television is put back here, around the block only, so the
     * clock, the badges, the cast band and the buttons stay where they are and
     * just the title and synopsis scroll.
     *
     * The block is moved with explicit layout params: its XML height is
     * `0dp`/`weight 1`, which only means "the leftover of a column" and would
     * collapse inside a ScrollView.
     */
    private fun makeInfoBlockScrollable() {
        val parent = overlay as? ViewGroup ?: return
        val block = infoBlock ?: return
        val index = parent.indexOfChild(block)
        if (index < 0) return
        parent.removeViewAt(index)
        val scroller = ScrollView(parent.context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                block,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        parent.addView(
            scroller,
            index,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun wireButtons() {
        playPauseButton?.setOnClickListener {
            host.onChromePlayPause()
            keepVisible()
        }
        nextButton?.setOnClickListener {
            host.onChromeNext()
            keepVisible()
        }
        sourceButton?.setOnClickListener {
            host.onChromeOpenSourcePicker()
            keepVisible()
        }
        episodesButton?.setOnClickListener {
            host.onChromeOpenEpisodes()
            keepVisible()
        }
        audioButton?.setOnClickListener {
            host.onChromeOpenAudioPicker()
            keepVisible()
        }
        subtitleButton?.setOnClickListener {
            host.onChromeOpenSubtitlePicker()
            keepVisible()
        }
        speedButton?.setOnClickListener {
            host.onChromeOpenSpeedPicker()
            keepVisible()
        }
        aspectButton?.setOnClickListener {
            host.onChromeOpenAspectPicker()
            keepVisible()
        }
        playerSwitchButton?.setOnClickListener {
            host.onChromeSwitchPlayer()
            keepVisible()
        }
        externalButton?.setOnClickListener {
            host.onChromeOpenExternal()
            keepVisible()
        }
        infoButton?.setOnClickListener {
            host.onChromeOpenInfo()
            keepVisible()
        }
        settingsButton?.setOnClickListener {
            host.onChromeOpenSettings()
        }
        skipButton?.setOnClickListener { host.onChromeSkipIntro() }
    }

    /**
     * The seekbar scrubs, it is not a readout. The bar paints the thumb's own
     * position for the whole drag, and the exact seek still happens on release -
     * but the engine is told about the drag so it can pause for it and follow the
     * thumb (see the scrub host hooks), which is how the picture comes with the
     * bar instead of waiting for the release.
     */
    private fun wireSeekBar() {
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val durationMs = host.chromeDurationMs()
                if (durationMs <= 0L) return
                val positionMs = durationMs * progress / SEEKBAR_MAX
                positionView?.text = formatMillis(positionMs)
                // The engine owns whether the picture follows and how often.
                host.onChromeScrubProgress(positionMs)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                scrubbing = true
                handler.removeCallbacks(hideRunnable)
                host.onChromeScrubStart()
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                scrubbing = false
                val durationMs = host.chromeDurationMs()
                if (durationMs > 0L) {
                    // The release is the exact landing, whatever the follow did.
                    host.onChromeSeekTo(durationMs * (bar?.progress ?: 0) / SEEKBAR_MAX)
                }
                host.onChromeScrubEnd()
                keepVisible()
            }
        })
    }

    /**
     * The same rule both activities used: a focused control holds the bar open,
     * and losing focus starts the countdown again.
     *
     * It also carries the focus label (spec 2026-11-17, F3): ONE focus listener
     * per control, so the hold's behaviour and the label cannot be wired twice
     * and drift - and so replacing a listener can never silently drop the hold.
     */
    private fun wireFocusHold() {
        controls().forEach { control ->
            control.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    handler.removeCallbacks(hideRunnable)
                    showFocusLabel(control)
                } else {
                    keepVisible()
                    clearFocusLabel(control)
                }
            }
        }
    }

    // ── labels (spec 2026-11-17) ─────────────────────────────────────────

    /**
     * The icon-only buttons that carry a label, in bar order (F1).
     *
     * The label IS the button's contentDescription: it is never written twice,
     * so the tooltip, the focus label and TalkBack cannot drift (F7). Speed and
     * Aspect gained theirs in player_chrome.xml. Skip Intro is deliberately
     * absent - it already draws its own text (F5).
     */
    private fun labelledButtons(): List<View> = listOfNotNull(
        playPauseButton,
        nextButton,
        sourceButton,
        episodesButton,
        audioButton,
        subtitleButton,
        speedButton,
        aspectButton,
        playerSwitchButton,
        externalButton,
        infoButton,
        settingsButton
    )

    /**
     * The platform tooltip, on hover and on long press (F2).
     *
     * `setTooltipText` is the whole mechanism: no custom popup, no new view and
     * no new timing. It is the string the button already announces, which is
     * also what the focus label shows (F7).
     */
    private fun wireLabels() {
        labelledButtons().forEach { button ->
            button.setTooltipText(button.contentDescription)
        }
        syncPlayPauseLabel()
    }

    /**
     * Play/Pause names the action its press performs (F1/S3): "Pause" while
     * playing, "Play" while paused - matching the glyph it is drawing.
     */
    private fun syncPlayPauseLabel() {
        val button = playPauseButton ?: return
        val label = if (host.chromeIsPlaying()) "Pause" else "Play"
        if (button.contentDescription?.toString() == label) return
        button.contentDescription = label
        button.setTooltipText(label)
        if (labelledButton === button) focusLabel?.text = label
    }

    /** Shows [button]'s label; a control with no label shows nothing. */
    private fun showFocusLabel(button: View) {
        val label = button.contentDescription?.toString().orEmpty()
        if (label.isBlank()) return
        focusLabel?.text = label
        labelledButton = button
    }

    /**
     * Hides the label IF it is [button]'s.
     *
     * Identity-checked because focus moves old -> new in one dispatch: a late
     * blur from the control being left must not wipe the label the new one has
     * just put up.
     */
    private fun clearFocusLabel(button: View) {
        if (labelledButton !== button) return
        hideFocusLabel()
    }

    /** Hides the label whatever it was showing: focus left the bar, or the bar did. */
    private fun hideFocusLabel() {
        focusLabel?.text = ""
        labelledButton = null
    }

    /**
     * The controls that hold the overlay open while focused.
     *
     * m9: the seek bar is one of them. It is what [focusControls] lands on
     * FIRST, so without it the bar hid underneath the control the D-pad had just
     * selected: sit idle for the auto-hide and the next Left/Right was spent
     * re-showing the chrome instead of scrubbing. The list carried every button
     * and this one surface, which is the one the rule was written for.
     */
    private fun controls(): List<View> = listOfNotNull(
        if (seekAvailable) seekBar else null,
        playPauseButton,
        nextButton,
        sourceButton,
        episodesButton,
        audioButton,
        subtitleButton,
        speedButton,
        aspectButton,
        playerSwitchButton,
        externalButton,
        infoButton,
        settingsButton
    )

    /**
     * Where the D-pad lands when the bar comes up: the scrub bar, unless a skip
     * prompt is up (it is the primary target then) or a session with no duration
     * has nothing on the bar to scrub - in which case play/pause. This is the
     * order both activities focused by.
     */
    private fun focusControls() {
        if (!host.chromeMayTakeFocus()) return
        val scrubbable = skipButton?.visibility != View.VISIBLE &&
            host.chromeSeekBarScrubbable() &&
            host.chromeDurationMs() > 0L
        if (scrubbable && seekBar?.requestFocus() == true) return
        if (host.chromeFocusPrimary()) return
        playPauseButton?.requestFocus()
    }

    /**
     * Shows or hides the Episodes button.
     *
     * A series gets it; a movie has no seasons and a live channel has no
     * episodes at all, so both keep the button out of the bar rather than
     * offering a panel that could only say "nothing here". The bar's focus
     * walk skips a GONE control on its own, so hiding it needs nothing else.
     */
    fun setEpisodesAvailable(available: Boolean) {
        val button = episodesButton ?: return
        val target = if (available) View.VISIBLE else View.GONE
        if (button.visibility != target) button.visibility = target
    }

    /**
     * Shows or hides the scrub row - the bar and its elapsed / total readouts.
     *
     * Live television has no timeline to scrub: the bar was drawn but could not
     * be dragged (the Exo engine answers false to
     * [PlayerChromeHost.chromeSeekBarScrubbable] on a live channel), its
     * "elapsed" readout was only time since tune-in and its "total" was blank -
     * and it took the D-pad's first stop away from the control the viewer
     * wanted (field report 2026-11-12). The live program block's own thin bar
     * stays: that one is a read-only "how far into this show" indicator, not a
     * scrub control.
     *
     * GONE rather than transparent, so it can neither take focus nor leave a
     * gap. The layout names this row as every control's UP target, so the
     * targets are re-pointed here ([applyFocusUpTarget]) - aiming UP at a GONE
     * view is how a press end up lost.
     */
    fun setSeekAvailable(available: Boolean) {
        seekAvailable = available
        val row = seekRow
        if (row != null) {
            if (available) {
                row.alpha = 1f
                if (row.visibility != View.VISIBLE) row.visibility = View.VISIBLE
            } else if (row.visibility != View.GONE) {
                row.visibility = View.GONE
            }
        }
        applyFocusUpTarget()
    }

    /**
     * Points every control-bar button's UP at the bar's own top stop, as the
     * host names it ([PlayerChromeHost.chromeFocusUpTargetId]).
     */
    private fun applyFocusUpTarget() {
        val target = host.chromeFocusUpTargetId()
        labelledButtons().forEach { button ->
            if (button.nextFocusUpId != target) button.nextFocusUpId = target
        }
    }

    /** Restarts the auto-hide countdown. */
    private fun keepVisible() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, autoHideMs)
    }

    private fun bindCast(cast: List<PlayerCastMember>) {
        val section = castSection ?: return
        val row = castRow ?: return
        if (cast.isEmpty()) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        row.removeAllViews()
        // A cast tile press goes back to the actor screen only where the host
        // asked for it (see PlayerChromeCastHost); the band still draws without.
        val castHost = host as? PlayerChromeCastHost
        cast.forEach { member ->
            val itemView = row.inflateCastTile()
            itemView.nextFocusDownId = R.id.chrome_seekbar
            itemView.findViewById<TextView>(R.id.cast_member_name).text = member.name
            itemView.findViewById<TextView>(R.id.cast_member_character).apply {
                val character = member.character
                if (character.isNullOrBlank()) {
                    visibility = View.GONE
                } else {
                    text = character
                    visibility = View.VISIBLE
                }
            }
            itemView.findViewById<ImageView>(R.id.cast_member_image).apply {
                val url = member.profileImageUrl()
                if (url.isNullOrBlank()) {
                    setImageResource(R.drawable.ic_cast_placeholder)
                } else {
                    runCatching { this.load(url) }
                        .onFailure { setImageResource(R.drawable.ic_cast_placeholder) }
                }
            }
            if (castHost != null) {
                itemView.setOnClickListener { castHost.onChromeCastPressed(member) }
            }
            row.addView(itemView)
        }
        // The tiles are inflated HERE, long after the activity has themed its own
        // tree, so each one comes up on @drawable/picker_item_bg as the XML
        // resolved it: #141A24 / #1D2530 with the brass focus ring. The MPV
        // player runs its chrome pass in onCreate, before this chrome even
        // exists, and nothing re-walked the band afterwards - so a pure-black
        // theme left the actor chips navy (field, 2026-11-24). Re-walk the band
        // as it is built, which is the call pattern the walk's own docstring
        // prescribes for late-inflated content; a source switch or a Next Up
        // repaint rebuilds the band through here and stays themed with it.
        //
        // Scoped to the row (a handful of tiles) rather than the whole overlay,
        // and a no-op on the default palette, where nothing has moved to re-tint.
        // The Exo engine's chrome pass happens after its first bind, so its
        // result is unchanged; this is the same themed tile, reached here.
        retintPlayerChrome(row, row.context)
    }

    private fun LinearLayout.inflateCastTile(): View =
        android.view.LayoutInflater.from(context)
            .inflate(R.layout.cast_member_item, this, false)

    /**
     * The wall clock + "Ends at" estimate, from the playhead and the device clock.
     *
     * The estimate needs a DURATION to exist, and live TV has none:
     * [PlayerChromeHost.chromeDurationMs] answers 0 when the engine's duration is
     * unset. The math below then worked out to zero remaining and printed the
     * current clock time back - "Ends at 9:41 PM" at 9:41 PM, as if the
     * channel's programme ended that instant. The seek bar already reads
     * `durationMs <= 0` as "no duration", and this is that same rule: the line
     * is hidden instead of being filled with a guess. It also covers a VOD whose
     * duration has not loaded yet; a VOD with a known duration is unchanged.
     *
     * The sleep suffix stays VISIBLE either way - it moves onto the clock line,
     * where it already reads beside the estimate. Otherwise the timer would
     * vanish exactly on the channels where the estimate is hidden, and the live
     * programme block already carries the programme's own end, so nothing is
     * lost by dropping the estimate there.
     */
    private fun refreshClock(positionMs: Long, durationMs: Long) {
        val clockView = clock ?: return
        val endsView = endsAt ?: return
        val use24Hour = AppPreferences.getUse24HourClock(root.context)
        val formatter = if (use24Hour) dayClock24Format else dayClock12Format
        val endsFormatter = if (use24Hour) clock24Format else clock12Format
        val suffix = playerSleepSuffix()
        if (durationMs <= 0L) {
            clockView.text = DateFormats.now(formatter) + suffix
            endsView.visibility = View.GONE
            return
        }
        clockView.text = DateFormats.now(formatter)
        endsView.visibility = View.VISIBLE
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val endsAtTime = DateFormats.time(System.currentTimeMillis() + remainingMs, endsFormatter)
        endsView.text = "Ends at $endsAtTime$suffix"
    }

    companion object {
        /**
         * How long the bar stays up without input before it hides itself. Both
         * activities used the same six seconds; this is the one place it is
         * written now.
         */
        const val AUTO_HIDE_MS = 6_000L

        /** The bar's own resolution; the existing bars used the same 1000. */
        private const val SEEKBAR_MAX = 1000


    }
}

/**
 * An armed sleep timer, on the bar beside the clock and the "Ends at" estimate.
 *
 * The countdown used to exist only inside the Settings panel's Sleep row, so a
 * viewer who armed "20 minutes" had no way to see it running - and no way to
 * tell a timer that was about to stop playback from one that had never been
 * armed. It rides the bar's own per-second clock but has no clock of its own:
 * it reads the shared [SleepTimer] state.
 *
 * Top level rather than a member because BOTH writers of the bar's clock text
 * need it: [PlayerChrome.refreshClock] and `NativePlayerActivity.updateClock`
 * paint the same two views, and whichever runs last must not drop the suffix.
 * It also has to survive the estimate being hidden (no duration to count from),
 * which is why the clock line carries it too.
 */
internal fun playerSleepSuffix(): String {
    val sleep = SleepTimer.state.value
    if (!sleep.isArmed) return ""
    // An end-of-item timer has no deadline to count down: it fires when the
    // episode does, so the bar names the moment rather than a number.
    if (sleep.stopsAtEndOfItem) return " \u00b7 Sleep at end"
    val remaining = sleep.deadlineMs?.minus(System.currentTimeMillis())
    if (remaining != null) return " \u00b7 Sleep in ${sleepCountdownText(remaining)}"
    return if (sleep.label.isNotBlank()) " \u00b7 Sleep: ${sleep.label}" else " \u00b7 Sleep on"
}
