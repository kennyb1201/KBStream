package com.kennyb1201.kbstream.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.badges.StreamBadge
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
     * Parks the D-pad on the engine's primary control, for an engine whose first
     * stop is not play/pause (the Exo engine raises a live channel's CH up, or
     * the skip prompt while it is up). Returns true when it took focus; the
     * default returns false and leaves play/pause to the chrome.
     */
    fun chromeFocusPrimary(): Boolean = false
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
    private val episodeLabel: TextView? = root.findViewById(R.id.chrome_episode_label)
    private val episodeTitle: TextView? = root.findViewById(R.id.chrome_episode_title)
    private val overview: TextView? = root.findViewById(R.id.chrome_overview)
    private val clock: TextView? = root.findViewById(R.id.chrome_clock)
    private val endsAt: TextView? = root.findViewById(R.id.chrome_ends_at)
    private val seekRow: View? = root.findViewById(R.id.chrome_seekbar_row)
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
    private val audioButton: View? = root.findViewById(R.id.chrome_btn_audio)
    private val subtitleButton: View? = root.findViewById(R.id.chrome_btn_subtitle)
    private val speedButton: TextView? = root.findViewById(R.id.chrome_btn_speed)
    private val aspectButton: TextView? = root.findViewById(R.id.chrome_btn_aspect)
    private val playerSwitchButton: View? = root.findViewById(R.id.chrome_btn_player_switch)
    private val externalButton: View? = root.findViewById(R.id.chrome_btn_player_external)
    private val infoButton: View? = root.findViewById(R.id.chrome_btn_info)
    private val settingsButton: View? = root.findViewById(R.id.chrome_btn_settings)

    private val handler = Handler(Looper.getMainLooper())

    private val clock12Format by lazy { DateFormats.clock12h() }
    private val clock24Format by lazy { DateFormats.clock24h() }

    /** Whether scrubbing is in progress, so a tick does not fight the drag. */
    private var scrubbing = false

    private val hideRunnable = Runnable {
        // A panel that owns the screen answers no here, and the bar stays up
        // until whatever raised the panel lowers it again - see
        // PlayerChromeHost.chromeMayAutoHide.
        if (!host.chromeMayAutoHide()) return@Runnable
        // An engine with its own teardown takes the bar down itself.
        if (!host.onChromeAutoHide()) hide()
    }

    init {
        wireButtons()
        wireSeekBar()
        wireFocusHold()
        seekBar?.max = SEEKBAR_MAX
    }

    /** True while the overlay is on screen. */
    val isVisible: Boolean
        get() = overlay?.visibility == View.VISIBLE

    /** True while the skip prompt is on screen. */
    val isSkipIntroVisible: Boolean
        get() = skipButton?.visibility == View.VISIBLE

    /** True while the seek bar is being dragged, so a tick leaves it alone. */
    val isScrubbing: Boolean
        get() = scrubbing

    /** Brings the bar up, focuses it, and starts its auto-hide countdown. */
    fun show() {
        overlay?.visibility = View.VISIBLE
        // The seek row rides the overlay: it carries the bar both engines land
        // the D-pad on, so it is up exactly while the bar is.
        seekRow?.visibility = View.VISIBLE
        // The clock rides the overlay's visibility, the way both activities kept
        // it: shown and ticking exactly while the bar is, gone otherwise.
        clock?.visibility = View.VISIBLE
        endsAt?.visibility = View.VISIBLE
        focusControls()
        keepVisible()
    }

    /** Takes the bar down and stops the countdown. */
    fun hide() {
        handler.removeCallbacks(hideRunnable)
        overlay?.visibility = View.GONE
        seekRow?.visibility = View.GONE
        clock?.visibility = View.GONE
        endsAt?.visibility = View.GONE
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

    /** Re-paints the title block from the host's current facts. */
    fun refreshTitle() {
        val info = host.chromeTitleInfo()

        val logoUrl = info.clearLogoUrl
        val logo = clearLogo
        if (logo != null && !logoUrl.isNullOrBlank()) {
            runCatching { logo.load(logoUrl) }
            logo.visibility = View.VISIBLE
            itemName?.visibility = View.GONE
        } else {
            logo?.setImageDrawable(null)
            logo?.visibility = View.GONE
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
            val wasFocused = button.isFocused
            button.visibility = View.GONE
            if (wasFocused && isVisible) playPauseButton?.requestFocus()
        } else {
            button.text = label
            button.visibility = View.VISIBLE
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
     */
    private fun wireFocusHold() {
        controls().forEach { control ->
            control.setOnFocusChangeListener { _, focused ->
                if (focused) handler.removeCallbacks(hideRunnable) else keepVisible()
            }
        }
    }

    /** The controls that hold the overlay open while focused. */
    private fun controls(): List<View> = listOfNotNull(
        playPauseButton,
        nextButton,
        sourceButton,
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
    }

    private fun LinearLayout.inflateCastTile(): View =
        android.view.LayoutInflater.from(context)
            .inflate(R.layout.cast_member_item, this, false)

    /** The wall clock + "Ends at" estimate, from the playhead and the device clock. */
    private fun refreshClock(positionMs: Long, durationMs: Long) {
        val clockView = clock ?: return
        val endsView = endsAt ?: return
        val formatter =
            if (AppPreferences.getUse24HourClock(root.context)) clock24Format else clock12Format
        clockView.text = DateFormats.now(formatter)
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val endsAtTime = DateFormats.time(System.currentTimeMillis() + remainingMs, formatter)
        endsView.text = "Ends at $endsAtTime"
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
