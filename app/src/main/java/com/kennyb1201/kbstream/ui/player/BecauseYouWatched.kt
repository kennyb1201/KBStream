package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.BYW_CERT_TIMEOUT_MS
import com.kennyb1201.kbstream.data.tmdb.BYW_PICK_COUNT
import com.kennyb1201.kbstream.data.tmdb.BlendParams
import com.kennyb1201.kbstream.data.tmdb.BlendPick
import com.kennyb1201.kbstream.data.tmdb.BlendWatchedMode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.buildRecommendationBlend
import com.kennyb1201.kbstream.data.tmdb.displayCardMeta
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import com.kennyb1201.kbstream.data.tmdb.displayMetaLine
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The end-credits "Because you watched" recommendations, shared by both players.
 *
 * The main player grew this panel first; the MPV backup engine then needed the
 * same row so an end of playback looks and behaves the same whichever engine
 * played it. Rather than a second copy of the pick engine and the card builder -
 * two places for the ranking tiers, the doubled-logo fix and the focus rules to
 * drift apart - the recommendation logic and the row's view construction live
 * here, and each activity supplies only what is genuinely its own: the panel
 * views from its own layout, its theme colors, its pill styling, and where a
 * PLAY / DETAILS press should go.
 */

/**
 * One recommendation card inside the because-you-watched row: poster + name,
 * with the ids the result handoff needs. Built as views (not Compose) because
 * the panel lives in the player's view hierarchy.
 */
internal data class BywPick(
    val tmdbId: Int,
    val type: String,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val overview: String?,
    /**
     * Why this pick is here, one short line ("Directed by ...", "Same
     * series", "Similar theme: heist"). Filled by the shared blend; the card
     * renders it under the name.
     */
    val reason: String? = null,
    var imdbId: String? = null
)

/** The parent type the player carries, as TMDB spells it. */
internal fun bywMediaType(parentType: String): String = when (parentType.lowercase()) {
    "series", "show", "tv" -> "series"
    else -> "movie"
}

/**
 * The credits row's picks: the shared recommendation blend, with the player's
 * own parameters.
 *
 * The tiers, the scoring, the ranking, the reason labels and the kids ceiling
 * all live in [buildRecommendationBlend] - one engine, shared with Detail's
 * "More Like This" rail, so the two surfaces cannot drift apart. This function
 * is the player's own wrapper around it and supplies only what is genuinely the
 * panel's:
 *
 *  - the finished title's detail as the seed;
 *  - the SAME FRANCHISE tier, which belongs to this surface only (Detail's
 *    Collections rail already carries the saga);
 *  - seven picks;
 *  - the DROP watched rule: this panel suggests something NEW to watch, so a
 *    title this profile has already seen is gone rather than pushed down;
 *  - hidden titles dropped, the same rule Detail's rail uses;
 *  - the profile's own Kids Mode ceiling, certified through the repository's
 *    cached lookup with the player's bounded timeout.
 *
 * A null [kidsMaxAge] is an ordinary profile: the ceiling never runs and no
 * certification fetch is made.
 */
internal suspend fun buildBecauseYouWatchedPicks(
    ctx: Context,
    tmdbId: Int,
    mediaType: String,
    kidsMaxAge: Int? = null
): List<BywPick> {
    val repo = TmdbRepository.getInstance(ctx)
    val detail = runCatchingCancellable {
        repo.getDetailByTmdbId(tmdbId, mediaType)
    }.getOrNull() ?: return emptyList()

    return runCatchingCancellable {
        buildRecommendationBlend(
            BlendParams(
                context = ctx,
                repo = repo,
                detail = detail,
                mediaType = bywMediaType(mediaType),
                seedName = detail.title ?: detail.name.orEmpty(),
                pickCount = BYW_PICK_COUNT,
                includeFranchise = true,
                watchedMode = BlendWatchedMode.DROP,
                kidsMaxAge = kidsMaxAge,
                dropHidden = true,
                // The profile's taste signals, so a title this viewer thumbed
                // down is never suggested to them here either (the same rule
                // More Like This applies). Local and profile-scoped.
                taste = TasteSignals.snapshot(ctx),
                certify = { candidate ->
                    repo.usCertification(
                        tmdbId = candidate.pick.tmdbId,
                        mediaType = candidate.pick.type,
                        timeoutMs = BYW_CERT_TIMEOUT_MS
                    )
                }
            )
        )
    }.getOrNull().orEmpty().map { it.toBywPick() }
}

/** The blend's pick, as the panel's own card shape (paths -> the URLs it loads). */
private fun BlendPick.toBywPick(): BywPick = BywPick(
    tmdbId = tmdbId,
    type = type,
    name = name,
    posterUrl = posterPath?.let { TmdbRepository.POSTER_BASE + it },
    backdropUrl = backdropPath?.let { TmdbRepository.BACKDROP_BASE + it },
    logoUrl = null,
    overview = overview,
    reason = reason,
    imdbId = imdbId
)



/**
 * The active profile's Kids Mode ceiling, or null for an ordinary profile.
 *
 * The players hand this to [buildBecauseYouWatchedPicks], so the credits row is
 * gated by the same per-profile setting every other surface uses (see
 * TmdbRepository.kidsMaxAge, which reads it for the rails).
 */
internal fun activeKidsMaxAge(): Int? = ProfileManager.activeProfile.value?.kidsMaxAge

/** AMOLED-aware stand-in for @color/kb_surface (card / artwork fills). */
internal fun playerPanelSurfaceColor(context: Context): Int = when {
    AppPreferences.getAmoledBlack(context) &&
        AppPreferences.getPureBlackSurface(context) -> 0xFF000000.toInt()

    AppPreferences.getAmoledBlack(context) -> 0xFF06080B.toInt()
    else -> ContextCompat.getColor(context, R.color.kb_surface)
}

/** AMOLED-aware stand-in for @color/kb_surface_raised (panel fills). */
internal fun playerPanelRaisedColor(context: Context): Int = when {
    AppPreferences.getAmoledBlack(context) &&
        AppPreferences.getPureBlackSurface(context) -> 0xFF050505.toInt()

    AppPreferences.getAmoledBlack(context) -> 0xFF0D1117.toInt()
    else -> ContextCompat.getColor(context, R.color.kb_surface_raised)
}

/**
 * Re-tints one XML chrome view to the current accent.
 *
 * The player and guide layouts resolve @color/kb_accent at inflation, so a
 * non-default global accent left every accent TextView, progress / seek tint
 * and ImageView tint on the old brass. Views are matched by the colour they
 * already carry rather than by id, so a new accent control is covered without a
 * hand-kept list, and a view that is not accent-coloured is left untouched.
 * Only called when the accent is actually non-default.
 */
internal fun retintAccentView(view: View, accent: Int, xmlAccent: Int) {
    when (view) {
        is TextView -> if (view.currentTextColor == xmlAccent) view.setTextColor(accent)
        is SeekBar -> {
            if (view.progressTintList?.defaultColor == xmlAccent) {
                view.progressTintList = ColorStateList.valueOf(accent)
            }
            if (view.indeterminateTintList?.defaultColor == xmlAccent) {
                view.indeterminateTintList = ColorStateList.valueOf(accent)
            }
            if (view.thumbTintList?.defaultColor == xmlAccent) {
                view.thumbTintList = ColorStateList.valueOf(accent)
            }
        }
        is ProgressBar -> {
            if (view.progressTintList?.defaultColor == xmlAccent) {
                view.progressTintList = ColorStateList.valueOf(accent)
            }
            if (view.indeterminateTintList?.defaultColor == xmlAccent) {
                view.indeterminateTintList = ColorStateList.valueOf(accent)
            }
        }
        is ImageView -> if (view.imageTintList?.defaultColor == xmlAccent) {
            view.imageTintList = ColorStateList.valueOf(accent)
        }
    }
}

/**
 * [retintAccentView] over a whole view tree, for screens the player's own
 * chrome walk does not already traverse (the guide rows, the external-player
 * panel). A no-op when the accent is still the default, so callers can run it
 * unconditionally.
 */
internal fun retintAccentChrome(root: View, context: Context) {
    val xmlAccent = ContextCompat.getColor(context, R.color.kb_accent)
    val accent = themeAccentColor(context)
    if (accent == xmlAccent) return
    retintAccentView(root, accent, xmlAccent)
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            retintAccentChrome(root.getChildAt(index), context)
        }
    }
}

/**
 * Tints the play/pause mark to the current global accent.
 *
 * @drawable/ic_player_play and ic_player_pause carry a fixed brass fill, so
 * the one control a viewer presses most kept the default brass under every
 * other accent: [retintAccentView] only retints an ImageView whose
 * imageTintList already equals the XML accent, and these marks carry no tint
 * list at all. Both players route the button's image through here so the mark
 * tracks the theme - at the default accent this is a no-op (the tint equals
 * the fill), and a flat tint still leaves the glyph's silhouette intact.
 */
internal fun tintPlayPauseIcon(view: ImageView, context: Context) {
    view.imageTintList = ColorStateList.valueOf(themeAccentColor(context))
}

/** Rounded rectangle standing in for the XML shape drawables. */
internal fun roundedPanelDrawable(
    context: Context,
    color: Int,
    radiusDp: Float
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = radiusDp * context.resources.displayMetrics.density
}

/**
 * The in-player guide row's background, built in code so the focus outline
 * follows a non-default global accent.
 *
 * [retintAccentView] retints colours and tint lists, but the row's outline is a
 * stroke inside @drawable/channel_guide_item_bg, and a GradientDrawable's stroke
 * colour cannot be read back to patch it in place. Mirroring the selector here
 * is the reliable way: the focused and selected states keep the XML's accent
 * stroke (2dp / 1dp) at the chosen accent, and every fill goes through the same
 * AMOLED-aware panel colours the player panels use, so the outline and the row
 * body track the theme instead of the fixed brass they inflated with.
 */
internal fun themedGuideRowBackground(context: Context, accent: Int): Drawable {
    val density = context.resources.displayMetrics.density

    fun row(fill: Int, strokeWidthDp: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), stroke)
            cornerRadius = 10f * density
        }

    val raised = playerPanelRaisedColor(context)
    val surface = playerPanelSurfaceColor(context)
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), row(raised, 2, accent))
        addState(intArrayOf(android.R.attr.state_selected), row(raised, 1, accent))
        addState(
            intArrayOf(),
            row(surface, 1, ContextCompat.getColor(context, R.color.kb_overlay_gradient_mid))
        )
    }
}

/**
 * The accent button's background (the player's SKIP INTRO / recap control):
 * the accent fill with an optional focus ring, resolved from the CURRENT
 * theme.
 *
 * @drawable/button_accent_bg is a ripple whose content is an accent
 * GradientDrawable, so the chrome walk ([retintPlayerChromeView]) re-tints it -
 * but its FOCUSED twin was a LAYER-LIST, which the walk (matching a
 * GradientDrawable's own fill) cannot rebuild. A focused SKIP INTRO therefore
 * kept the default brass under every other accent, exactly like the pill
 * chips - and that twin has since been deleted, because this function is the
 * only thing that ever drew it. Built here, the fill and ring follow
 * [themeAccentColor], and the unfocused state keeps the XML's press flash.
 */
internal fun accentButtonBackground(context: Context, focused: Boolean): Drawable {
    val density = context.resources.displayMetrics.density
    val body = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(themeAccentColor(context))
        cornerRadius = 8f * density
        if (focused) {
            setStroke(
                (2f * density).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, R.color.kb_text_hi)
            )
        }
    }
    if (focused) return body
    // The XML's press flash (@color/kb_void), kept on the unfocused state.
    val mask = roundedPanelDrawable(context, 0xFF000000.toInt(), 8f)
    return RippleDrawable(
        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.kb_void)),
        body,
        mask
    )
}

/**
 * The background of the screen the player's INFO button brings up: the same
 * accent hairline and 16dp corners the XML shape carried, but with an OPAQUE,
 * theme-aware fill.
 *
 * The old @drawable/info_panel_bg was translucent (#CC10141B), which is wrong
 * on the one surface that is meant to be read: it sits over the player's own
 * translucent overlay gradient, so the two washed into each other and the rails
 * behind them showed through the codec lines. Both players use this - the main
 * player's info screen and the MPV engine's own readout panel, which takes this
 * fill in its own activity the same way - so the two engines' info looks the
 * same and both follow the AMOLED / pure-black toggles instead of a fixed panel
 * color.
 */
internal fun infoPanelDrawable(context: Context): GradientDrawable =
    roundedPanelDrawable(
        context,
        playerPanelRaisedColor(context),
        16f
    ).apply {
        val accent = themeAccentColor(context)
        // Quarter-strength accent: the hairline the XML drawable had, kept so
        // the panel still reads as a panel rather than a black rectangle.
        setStroke(
            context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
            (accent and 0x00FFFFFF) or (0x40 shl 24)
        )
    }

/**
 * The credits panel's fill with its top-right corner cut away.
 *
 * The end-credits arrangement shrinks the video (the credits themselves) into
 * the top-right corner and spreads the recommendations across the screen. That
 * video is a SurfaceView - a separate surface UNDER the activity's own views -
 * so anything the panel paints over its corner hides the credits outright. The
 * panel therefore cannot simply be drawn full width with the video sitting on
 * top of it; cutting the notch out of the fill is what lets the two share the
 * top of the screen. The header stops short of the notch, and the pick row
 * below it still gets the whole width - which is the point of moving the video
 * out of the bottom-right, where it used to sit under the row.
 *
 * One path, so the panel keeps its rounded corners everywhere except where the
 * notch meets the top edge.
 */
private class NotchedPanelDrawable(
    fillColor: Int,
    private val radiusPx: Float,
    private val notchWidthPx: Int,
    private val notchHeightPx: Int
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val silhouette = Path()
    private val corner = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || notchWidthPx <= 0 || notchHeightPx <= 0) return
        rect.set(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat()
        )
        silhouette.reset()
        silhouette.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        rect.set(
            (bounds.right - notchWidthPx).toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), (bounds.top + notchHeightPx).toFloat()
        )
        corner.reset()
        corner.addRect(rect, Path.Direction.CW)
        silhouette.op(corner, Path.Op.DIFFERENCE)
        canvas.drawPath(silhouette, fill)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(0, 0, bounds.width(), bounds.height(), radiusPx)
    }
}

/** The credits panel's fill, notched for the shrunk video's corner. */
internal fun creditsPanelDrawable(
    context: Context,
    color: Int,
    notchWidthPx: Int,
    notchHeightPx: Int
): Drawable = NotchedPanelDrawable(
    fillColor = color,
    radiusPx = 16f * context.resources.displayMetrics.density,
    notchWidthPx = notchWidthPx,
    notchHeightPx = notchHeightPx
)

/**
 * The credits recommendation panel: the pick row, the featured strip under it,
 * and the focus rules that tie the two together.
 *
 * Focus deliberately lives on the PLAY / DETAILS pills and never on the card
 * itself. A focusable card swallowed the D-pad: stepping through the row landed
 * on whole posters (which lit the poster up and read as "the card is the
 * button"), the featured strip never changed because only the pills report
 * focus, and OK on a card opened DETAILS no matter which half of the card the
 * user thought they were on. Only the two pills take focus now, and both of them
 * drive the strip.
 */
internal class BecauseYouWatchedUi(
    private val host: ComponentActivity,
    private val panel: LinearLayout,
    private val title: TextView,
    private val row: LinearLayout,
    private val surfaceColor: () -> Int,
    private val raisedColor: () -> Int,
    private val applyPill: (TextView, Boolean, Boolean) -> Unit,
    private val scope: () -> CoroutineScope?,
    private val onPlay: (BywPick, String) -> Unit,
    private val onDetails: (BywPick, String) -> Unit
) {

    private val density = host.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Live view refs + mutable imdb id for one card in the row. */
    private class CardRefs(
        val cardView: View,
        val metaView: TextView?,
        var imdbId: String?
    )

    /**
     * Per-pick metadata fetched alongside the logo pass: the featured strip's
     * meta line, the cards' compact line, and the description / backdrop the
     * ranking tiers could not supply (franchise and credits candidates carry
     * neither of their own).
     */
    private class PickMeta(
        val metaLine: String?,
        val cardLine: String?,
        val overview: String?,
        val backdropUrl: String?
    )

    private val cards = mutableMapOf<Int, CardRefs>()
    private val metaCache = mutableMapOf<Int, PickMeta>()
    private val logoCache = mutableMapOf<Int, String>()
    private val pills = mutableListOf<Pair<TextView, Boolean>>()
    private var featured: Int? = null

    /**
     * The end-credits arrangement, from [setCreditsLayout]: how far the header
     * stops short of the shrunk video's corner, how tall that corner is, and the
     * row's own top margin before the clearance pass below touched it.
     */
    private var creditsInsetPx = 0
    private var creditsNotchPx = 0
    private var creditsRowBaseMargin: Int? = null

    init {
        // The row is rebuilt on every show, so its artwork is polished from a
        // layout pass instead of once at build time - which is also what runs
        // after the theme has re-tinted those very views. The same pass keeps
        // the row clear of the video's notch.
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isVisible) polish()
            keepRowClearOfNotch()
        }
        panel.addOnLayoutChangeListener(onLayout)
        row.addOnLayoutChangeListener(onLayout)
    }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    fun hasFocus(): Boolean = panel.hasFocus()

    fun show(titleText: String?) {
        title.text = titleText?.takeIf { it.isNotBlank() } ?: "This title"
        applyTheme()
        panel.visibility = View.VISIBLE
    }

    fun hide() {
        panel.visibility = View.GONE
    }

    /**
     * The clear logo the featured strip is showing for [pick], for the result
     * handoff: the wordmark the per-pick fetch landed, or null when it landed
     * none (the strip then draws nothing, and so does the player's splash).
     */
    fun clearLogoFor(pick: BywPick): String? = logoCache[pick.tmdbId]

    /**
     * The backdrop the featured strip is showing for [pick], for the result
     * handoff.
     *
     * The fetched detail's backdrop first, then the pick's own - the strip's
     * chain. It stops there on purpose: the strip's last-resort poster is not a
     * backdrop, and the player already falls back to the item poster when none
     * arrives, so handing one over as a backdrop would only get it cropped as
     * one.
     */
    fun backdropFor(pick: BywPick): String? =
        metaCache[pick.tmdbId]?.backdropUrl ?: pick.backdropUrl

    /**
     * Parks focus on the first pick in the credits row. The panel owns LEFT/RIGHT
     * while it is up, but focus is not always inside it by then: the row is built
     * a beat after the panel opens, and a skip prompt hands focus back to the
     * video surface when it hides. The activity calls this on the first
     * horizontal press so the pick pills can actually be reached. False when
     * there is nothing focusable yet, which leaves the press alone.
     */
    fun focusFirst(): Boolean {
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view !== row && view.isFocusable && view.visibility == View.VISIBLE) {
                return view.requestFocus()
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return false
    }

    /**
     * Switches the panel between its plain box and the end-credits arrangement,
     * where the shrunk video owns the top-right corner.
     *
     * [insetPx] is the width of that corner (the video plus the gap the panel
     * used to leave to the screen edge): the panel's fill is notched for it and
     * the header - kicker, title, featured strip - stops short of it, which is
     * what keeps the header the width it has always been. The pick row and the
     * hint under it are untouched, so they run the full width of the screen
     * instead of being cut off at the header's edge.
     *
     * Zero puts the plain box back.
     */
    fun setCreditsLayout(insetPx: Int, notchPx: Int) {
        creditsInsetPx = insetPx
        creditsNotchPx = notchPx
        if (insetPx <= 0) {
            clearRowClearance()
            creditsRowBaseMargin = null
        }
        applyPanelBackground()
        applyHeaderInset()
        keepRowClearOfNotch()
    }

    /**
     * The panel's fill: notched for the credits video's corner while the end
     * panel is up, the plain rounded box otherwise.
     */
    private fun applyPanelBackground() {
        panel.background = if (creditsInsetPx > 0 && creditsNotchPx > 0) {
            creditsPanelDrawable(host, raisedColor(), creditsInsetPx, creditsNotchPx)
        } else {
            roundedPanelDrawable(host, raisedColor(), 16f)
        }
    }

    /**
     * Holds the header - kicker, title and the featured strip - clear of the
     * shrunk video. The strip is built on the first pick, so [feature] applies
     * this too.
     */
    private fun applyHeaderInset() {
        val header = listOfNotNull(
            panel.getChildAt(0),
            title,
            panel.findViewWithTag<View>(TAG_FEATURED_STRIP)
        )
        header.forEach { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if (params.marginEnd == creditsInsetPx) return@forEach
            params.marginEnd = creditsInsetPx
            view.requestLayout()
        }
    }

    /** The scroll view the pick row lives in - the box the clearance moves. */
    private fun rowScroll(): View? = row.parent as? View

    /**
     * Pushes the pick row below the notch when the header is shorter than the
     * shrunk video. Both scale with the screen, but not identically: a box that
     * reports a 4K surface at a 1080p density gets a video nearly twice the
     * header's height, and the row's right end would then sit under the credits
     * instead of under the panel's fill. The margin comes from the header's own
     * height (which excludes it), so the pass settles after one layout.
     */
    private fun keepRowClearOfNotch() {
        if (creditsInsetPx <= 0 || creditsNotchPx <= 0) return
        val scroll = rowScroll() ?: return
        // Before the first layout the header has no measured height at all, and
        // taking that for a header would shove the row down for a frame. The
        // layout pass that follows calls this again with real numbers.
        if (scroll.top <= 0) return
        val params = scroll.layoutParams as? LinearLayout.LayoutParams ?: return
        val base = creditsRowBaseMargin ?: params.topMargin.also { creditsRowBaseMargin = it }
        val header = scroll.top - panel.paddingTop - params.topMargin
        val margin = base + (creditsNotchPx - header).coerceAtLeast(0)
        if (params.topMargin != margin) {
            params.topMargin = margin
            scroll.requestLayout()
        }
    }

    /** Puts the row's own top margin back, on leaving the credits arrangement. */
    private fun clearRowClearance() {
        val base = creditsRowBaseMargin ?: return
        val scroll = rowScroll() ?: return
        val params = scroll.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.topMargin != base) {
            params.topMargin = base
            scroll.requestLayout()
        }
    }

    /** Re-tints everything the panel owns, after an AMOLED / theme change. */
    fun applyTheme() {
        applyPanelBackground()
        // Every piece of artwork sits in its own frame: the poster's frame and
        // the featured backdrop. Left alone they keep the XML's fixed
        // @color/kb_surface, which is what made the popup ignore the AMOLED /
        // pure-black toggles.
        cards.values.forEach { refs ->
            (refs.cardView as? ViewGroup)?.getChildAt(0)?.setBackgroundColor(surfaceColor())
        }
        panel.findViewWithTag<View>(TAG_FEATURED_BACKDROP)
            ?.setBackgroundColor(surfaceColor())
        pills.forEach { (pill, selected) -> applyPill(pill, selected, pill.isFocused) }
    }

    /** Renders the pick cards: poster, name, meta line, PLAY + DETAILS. */
    fun build(picks: List<BywPick>) {
        row.removeAllViews()
        cards.clear()
        pills.clear()
        metaCache.clear()
        logoCache.clear()
        featured = null

        picks.forEachIndexed { index, pick ->
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(4), dp(6), dp(8))
            }

            // Poster only: TMDB poster art already carries the title, so the
            // clear logo the row used to overlay on each card drew a second title
            // over the first - the doubled logo. The featured backdrop is a 16:9
            // still with no title on it, so its logo stays.
            val posterFrame = FrameLayout(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(108), dp(162))
                clipToOutline = true
                setBackgroundColor(surfaceColor())
                roundArtwork(this)
            }
            val poster = ImageView(host).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                pick.posterUrl?.let { load(it) }
            }
            posterFrame.addView(poster)
            card.addView(posterFrame)

            val name = TextView(host).apply {
                text = pick.name
                textSize = 11f
                setTextColor(ContextCompat.getColor(host, R.color.kb_text_hi))
                maxWidth = dp(108)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    host, R.font.oswald_medium
                )
            }
            card.addView(name)

            // WHY this pick is here: one short line from the shared blend,
            // "Directed by ...", "Same series", "Similar theme: heist". A
            // second caption line rather than a replacement for the year and
            // rating below it - "why" and "should I bother" are different
            // questions, and the row is wide enough for both. Accent-tinted so
            // the reason reads as the panel talking rather than as metadata,
            // and held to the poster's width so every card keeps one pitch.
            pick.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                card.addView(
                    TextView(host).apply {
                        text = reason
                        textSize = 9f
                        setTextColor(themeAccentColor(host))
                        maxWidth = dp(108)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(3), 0, 0)
                        typeface = androidx.core.content.res.ResourcesCompat.getFont(
                            host, R.font.oswald_medium
                        )
                    }
                )
            }

            // Year + length under the name: enough to compare picks without
            // focusing each one. Filled by the metadata pass.
            val cardMeta = TextView(host).apply {
                textSize = 9f
                setTextColor(ContextCompat.getColor(host, R.color.kb_text_lo))
                maxWidth = dp(108)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    host, R.font.oswald_medium
                )
            }
            card.addView(cardMeta)

            val buttons = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val play = TextView(host).apply {
                text = "PLAY"
                textSize = 10f
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setTextColor(ContextCompat.getColor(host, R.color.kb_void))
            }
            val details = TextView(host).apply {
                text = "DETAILS"
                textSize = 10f
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setTextColor(ContextCompat.getColor(host, R.color.kb_text_hi))
            }
            applyPill(play, true, false)
            applyPill(details, false, false)
            buttons.addView(play)
            buttons.addView(details)
            card.addView(buttons)

            cards[pick.tmdbId] = CardRefs(
                cardView = card,
                metaView = cardMeta,
                imdbId = pick.imdbId
            )
            pills.add(play to true)
            pills.add(details to false)

            // Only the two pills do anything: the card itself is not focusable
            // and has no click handler of its own.
            play.setOnClickListener { playPick(pick) }
            details.setOnClickListener { onDetails(pick, imdbFor(pick)) }

            play.setOnFocusChangeListener { v, hasFocus ->
                applyPill(v as TextView, true, hasFocus)
                if (hasFocus) feature(pick)
            }
            details.setOnFocusChangeListener { v, hasFocus ->
                applyPill(v as TextView, false, hasFocus)
                if (hasFocus) feature(pick)
            }

            row.addView(card)

            if (index == 0) {
                card.post {
                    play.requestFocus()
                    feature(pick)
                }
            }
        }

        // Clear-logo + description pass: fetch images/metadata per pick and fill
        // the featured strip as answers land (cards render instantly with
        // posters; logos/descriptions stream in).
        scope()?.launch {
            picks.forEach { pick ->
                val detail = withContext(Dispatchers.IO) {
                    runCatchingCancellable {
                        TmdbRepository.getInstance(host)
                            .getDetailByTmdbId(pick.tmdbId, pick.type)
                    }.getOrNull()
                } ?: return@forEach
                // The shared English-only pick (see [bestLogoPath]): a `null`
                // logo is an unset-language wordmark, often the title's
                // original-language mark, so the local copy that preferred
                // English and otherwise took TMDB's first entry could still
                // draw a foreign clearlogo here.
                val logo = detail.bestLogoPath()
                val isMovie = pick.type != "series"
                val meta = PickMeta(
                    // One genre: the featured strip is a single line wide.
                    metaLine = detail.displayMetaLine(isMovie, genreLimit = 1),
                    cardLine = detail.displayCardMeta(isMovie),
                    overview = pick.overview ?: detail.displayDescription(),
                    // Franchise and credits candidates ship a poster only; the
                    // fetched detail is what gives the strip a backdrop.
                    backdropUrl = pick.backdropUrl
                        ?: detail.backdropPath?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.BACKDROP_BASE + it }
                )
                val imdb = withContext(Dispatchers.IO) {
                    runCatchingCancellable {
                        TmdbRepository.getInstance(host).resolveImdbId(pick.tmdbId, pick.type)
                    }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    val refs = cards[pick.tmdbId] ?: return@withContext
                    if (!logo.isNullOrBlank()) {
                        logoCache[pick.tmdbId] = TmdbRepository.LOGO_BASE + logo
                    }
                    metaCache[pick.tmdbId] = meta
                    refs.metaView?.text = meta.cardLine.orEmpty()
                    imdb?.let { refs.imdbId = it }
                    if (featured == pick.tmdbId) feature(pick)
                }
            }
        }
    }

    /** The imdb id PLAY / DETAILS hand back for a pick. */
    private fun imdbFor(pick: BywPick): String =
        pick.imdbId ?: cards[pick.tmdbId]?.imdbId ?: "tmdb:${pick.tmdbId}"

    /**
     * PLAY for one card: hand the pick straight over, unresolved.
     *
     * Which of the two things happens next is not this panel's decision: PLAY
     * follows one rule everywhere in the app - auto-select resolves and plays
     * the best source, and with it off the source list opens (see MainActivity).
     * The panel used to resolve the sources here and hand over the single top
     * stream whatever the setting said, so with auto-select off there was no way
     * to reach the other sources, and a pick whose addons answered nothing
     * turned the press into DETAILS instead. What the press looks like while it
     * runs is the hand-off splash over the screen behind, not a hint line.
     */
    private fun playPick(pick: BywPick) {
        onPlay(pick, imdbFor(pick))
    }

    /**
     * Featured strip under the row: the focused pick's backdrop + clear logo +
     * description. Built once, from the panel's own children, so no extra layout
     * resource is needed.
     */
    private fun feature(pick: BywPick) {
        featured = pick.tmdbId
        // The panel is: kicker, title, [featured strip], row scroll, hint - the
        // strip is inserted programmatically at index 2 when missing.
        var strip = panel.findViewWithTag<LinearLayout>(TAG_FEATURED_STRIP)
        if (strip == null) {
            strip = LinearLayout(host).apply {
                tag = TAG_FEATURED_STRIP
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(4))
            }
            val backdrop = ImageView(host).apply {
                tag = TAG_FEATURED_BACKDROP
                layoutParams = LinearLayout.LayoutParams(dp(192), dp(108)).also {
                    it.marginEnd = dp(14)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surfaceColor())
                roundArtwork(this)
            }
            strip.addView(backdrop)
            val textCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
            }
            val logo = ImageView(host).apply {
                tag = TAG_FEATURED_LOGO
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
                )
                scaleType = ImageView.ScaleType.FIT_START
            }
            textCol.addView(logo)
            // Full metadata (certification, year, length, genres, rating) between
            // the logo and the synopsis. Two lines max so a series' longer scope
            // string cannot push the description out of view.
            val meta = TextView(host).apply {
                tag = TAG_FEATURED_META
                textSize = 11f
                setTextColor(themeAccentColor(host))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    host, R.font.oswald_medium
                )
                visibility = View.GONE
            }
            textCol.addView(meta)
            val desc = TextView(host).apply {
                tag = TAG_FEATURED_DESC
                textSize = 12f
                setTextColor(ContextCompat.getColor(host, R.color.kb_text_lo))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            textCol.addView(desc)
            strip.addView(
                textCol,
                LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            panel.addView(
                strip,
                FEATURED_STRIP_INDEX,
                // Explicit MATCH_PARENT: the header inset is a margin, and a
                // wrap_content strip would simply refuse to narrow.
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            applyHeaderInset()
        }
        val backdrop = strip.findViewWithTag<ImageView>(TAG_FEATURED_BACKDROP)
        val logo = strip.findViewWithTag<ImageView>(TAG_FEATURED_LOGO)
        val desc = strip.findViewWithTag<TextView>(TAG_FEATURED_DESC)
        val meta = strip.findViewWithTag<TextView>(TAG_FEATURED_META)
        // Metadata arrives with the same per-pick fetch as the logo, so the strip
        // shows what it has and fills in when the answer lands (feature is called
        // again for the still-focused card).
        val fetched = metaCache[pick.tmdbId]
        meta.text = fetched?.metaLine.orEmpty()
        meta.visibility =
            if (fetched?.metaLine.isNullOrBlank()) View.GONE else View.VISIBLE
        (fetched?.backdropUrl ?: pick.backdropUrl ?: pick.posterUrl)
            ?.let { backdrop.load(it) }
        desc.text = (fetched?.overview ?: pick.overview).orEmpty()
        // Logo: from the per-pick logo pass if it landed already.
        logoCache[pick.tmdbId]?.let { logo.load(it) } ?: run { logo.setImageDrawable(null) }
    }

    /** Clips the pick posters and the featured backdrop to rounded corners. */
    private fun polish() {
        cards.values.forEach { refs ->
            ((refs.cardView as? ViewGroup)?.getChildAt(0) as? View)
                ?.let { roundArtwork(it) }
        }
        panel.findViewWithTag<View>(TAG_FEATURED_BACKDROP)
            ?.let { roundArtwork(it) }
    }

    /**
     * Clips one piece of artwork - poster or backdrop - to rounded corners.
     *
     * A rounded outline rather than a rounded background: the theme pass re-tints
     * these views' backgrounds, which would wipe a background-carried radius, and
     * the clip is what makes the corners round in the first place.
     */
    private fun roundArtwork(view: View) {
        if (view.outlineProvider !is RoundedArtworkOutline) {
            view.outlineProvider = RoundedArtworkOutline(12f * density)
        }
        view.clipToOutline = true
    }

    private class RoundedArtworkOutline(private val radiusPx: Float) :
        android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    private companion object {
        const val TAG_FEATURED_STRIP = "byw_featured_strip"
        const val TAG_FEATURED_BACKDROP = "byw_featured_backdrop"
        const val TAG_FEATURED_LOGO = "byw_featured_logo"
        const val TAG_FEATURED_META = "byw_featured_meta"
        const val TAG_FEATURED_DESC = "byw_featured_desc"

        /** Kicker, title, [strip], row scroll, hint. */
        const val FEATURED_STRIP_INDEX = 2
    }
}
