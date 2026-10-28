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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.library.TasteProfileStore
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
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    // The profile's affinities, gathered lazily (one capped history read, and
    // the evidence reads only when something changed) so the panel's own
    // scoring sees them. Null/empty profiles rank exactly as before.
    val profile = runCatchingCancellable { TasteProfileStore.current(ctx, repo) }.getOrNull()

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
                profile = profile,
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

/**
 * The identity the credits thumbs write with: the title the viewer has just
 * finished, as the taste store keys it.
 *
 * Built PER RAISE, from the session's own parent id and type - never cached
 * across sessions, never read from the previous panel's state, and never derived
 * from a pick (a pick is what the row SUGGESTS, not what was watched). [keys]
 * carries every identity spelling the watched-key rule emits for this title, so
 * a thumb pressed in the panel and a thumb pressed on the same title's poster in
 * a rail are ONE signal, whichever id namespace the surface that raised the
 * player happened to know.
 *
 * There is deliberately nothing per-episode here: a series thumb is a thumb for
 * the SHOW the finished episode belongs to, which is why the shape has no season
 * or episode member to fill in.
 */
internal data class FinishedTitle(
    /** `type::id` spellings from [WatchedStatusKeys], the IMDB flavor first. */
    val keys: List<String>,
    /** The name the panel was raised with, for the store entry and the Toast. */
    val title: String,
    /** `series` | `movie`, already normalized. */
    val mediaType: String,
    /**
     * Always null from the panel: it supplies no artwork (the store entry's
     * poster is diagnostics-only), and inventing one here would be a fetch the
     * panel does not need.
     */
    val posterUrl: String? = null
) {

    /**
     * The id flavors the taste store takes, read back out of [keys].
     *
     * [WatchedStatusKeys.key] spells a key `type::id`, and the store keys an id
     * itself (`HiddenTitles.keyFor`: `series::tmdb:603` for a TMDB flavor,
     * `id::tt0133093` for anything else). Handing it the flavors - both of them,
     * the IMDB one first - is what makes one signal answer under either
     * spelling; the media type travels beside them.
     */
    val storeIds: List<String>
        get() = keys
            .map { it.substringAfter("::") }
            .filter { it.isNotBlank() }
            .distinct()
}

/**
 * The identity spellings for one finished title: the resolved IMDB flavor and
 * the TMDB one, in the watched-key order (see [WatchedStatusKeys]).
 *
 * Pure, so the both-flavor rule and the `tv`/`show` -> `series` mapping are
 * pinned by a plain JVM test instead of through three activities. A null
 * [imdbId] - an unresolved twin, or a title TMDB holds no IMDB id for - leaves
 * the TMDB flavor alone, which is exactly what the store's own rule does.
 */
internal fun finishedTitleKeys(mediaType: String, tmdbId: Int, imdbId: String?): List<String> =
    listOfNotNull(
        imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { WatchedStatusKeys.key(it, mediaType) },
        WatchedStatusKeys.key(WatchedStatusKeys.tmdbKeyId(tmdbId), mediaType)
    ).distinct()

/** How long the panel waits for the finished title's IMDB flavor. */
internal const val BYW_FINISHED_IMDB_TIMEOUT_MS = 2_500L

/**
 * The finished title's identity, resolved where the picks are.
 *
 * The TMDB flavor needs nothing (the panel is only ever raised with a resolved
 * TMDB id), so the IMDB twin is the one lookup this adds - and it is BOUNDED,
 * because a slow TMDB must never delay the raise: on null, failure or timeout
 * the keys are TMDB-flavor only and the panel opens on time. The store matches
 * by whichever spelling a reading surface has, so an unresolved twin costs
 * nothing but the cross-namespace shortcut until the next write.
 */
internal suspend fun finishedTitleFor(
    context: Context,
    tmdbId: Int,
    mediaType: String,
    title: String?
): FinishedTitle {
    val imdb = withTimeoutOrNull(BYW_FINISHED_IMDB_TIMEOUT_MS) {
        runCatchingCancellable {
            TmdbRepository.getInstance(context).resolveImdbId(tmdbId, mediaType)
        }.getOrNull()
    }
    return FinishedTitle(
        keys = finishedTitleKeys(mediaType, tmdbId, imdb),
        title = title.orEmpty(),
        mediaType = mediaType
    )
}

/**
 * What one panel raise needs: the finished title's identity and its picks.
 *
 * One value rather than two independent locals, because both are resolved in the
 * same IO block before the raise: the thumbs write for [finished], the row
 * renders [picks], and a pair also keeps a `var` written on IO and read on Main
 * out of the three call sites.
 *
 * [finished] is null only when the session has no TMDB id at all - and in that
 * case [picks] is empty too, so no panel is raised and the thumbs row has no
 * identity to exist without.
 */
internal data class BywPanelPlan(
    val finished: FinishedTitle?,
    val picks: List<BywPick>
)

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
 *
 * The header carries the second thing a viewer can press: the Like / Dislike pair
 * for the JUST-FINISHED title (see [FinishedTitle]), directly under the title and
 * above the pick row. Thumbs on the picks were rejected - a thumb on a title the
 * viewer has never played is a pre-judgment, not taste, and the blend consumes a
 * like as evidence about a title that was watched - so the pair belongs to the
 * one title in the room that just finished. The thumbs are focusable stops of
 * their own and never change the pills-only rule: no card gains a focusable, and
 * neither thumb drives the featured strip (the strip is about the focused PICK).
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

    /** The finished title THIS raise writes for; null while the panel is down. */
    private var finishedTitle: FinishedTitle? = null

    /** The signal the thumbs are painting, as the store last answered. */
    private var tasteSignal: String? = null

    /** The taste row and its two thumbs; built once, kept for the panel's life. */
    private var tasteRow: LinearLayout? = null
    private var likeThumb: ImageView? = null
    private var dislikeThumb: ImageView? = null

    /**
     * The collector that repaints the thumbs whenever the store changes - a press
     * here, a press on the title's Detail page, a row in a poster menu. One per
     * raise, cancelled in [hide].
     */
    private var tasteJob: Job? = null

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

    /**
     * Raises the panel for [titleText], with [finished] as the title the thumbs
     * write for.
     *
     * [finished] is the identity resolved alongside the picks (see
     * [BywPanelPlan]); null means the session had no TMDB id, in which case no
     * panel is raised at all and this is only ever reached with an identity. The
     * row is inserted BEFORE the featured strip can be, because the strip is
     * built on the first pick and lands at its own index - a row added later
     * would sit under the strip instead of under the title.
     */
    fun show(titleText: String?, finished: FinishedTitle? = null) {
        finishedTitle = finished
        title.text = titleText?.takeIf { it.isNotBlank() } ?: "This title"
        ensureTasteRow()
        applyTheme()
        panel.visibility = View.VISIBLE
        observeTaste()
    }

    fun hide() {
        // Nothing may paint or write for a title the panel is no longer showing:
        // the identity and the collector go with the raise, so a stale collector
        // can never repaint (or a press write) for the previous session's title.
        tasteJob?.cancel()
        tasteJob = null
        finishedTitle = null
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

    // --- the thumbs for the finished title ---------------------------------

    /**
     * Inserts the taste row - the Like / Dislike pair for the finished title -
     * into the header, directly under the title and above the pick row.
     *
     * Idempotent, and built once for the panel's lifetime the way the featured
     * strip is: [build] rebuilds the PICKS and must never touch this row, whose
     * thumbs belong to no pick. THE STRIP'S INDEX MOVES WITH IT (see
     * [FEATURED_STRIP_INDEX]) - a row added at index 2 while the strip also
     * inserts at 2 puts the strip above the thumbs and breaks the header order.
     *
     * The row is MATCH_PARENT with a top margin, so [applyHeaderInset] can hold
     * it clear of the shrunk credits video exactly like the title and the strip.
     */
    private fun ensureTasteRow() {
        val rowVisibility = if (finishedTitle != null) View.VISIBLE else View.GONE
        tasteRow?.let { row ->
            row.visibility = rowVisibility
            return
        }
        panel.findViewWithTag<View>(TAG_TASTE_ROW)?.let { existing ->
            tasteRow = existing as? LinearLayout
            likeThumb = existing.findViewWithTag(TAG_TASTE_LIKE)
            dislikeThumb = existing.findViewWithTag(TAG_TASTE_DISLIKE)
            existing.visibility = rowVisibility
            return
        }

        val like = thumbView(up = true)
        val dislike = thumbView(up = false)
        // LEFT/RIGHT is pinned by id instead of left to the geometric focus
        // search across the scroll view boundary, the way PillPanelUi pins its
        // stepper pads. DOWN is wired in [build], where the first card's pills
        // exist, and UP from a pill is wired there too.
        like.nextFocusRightId = dislike.id
        dislike.nextFocusLeftId = like.id

        val row = LinearLayout(host).apply {
            tag = TAG_TASTE_ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(like)
            addView(dislike)
        }
        row.visibility = rowVisibility
        panel.addView(
            row,
            TASTE_ROW_INDEX,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        tasteRow = row
        likeThumb = like
        dislikeThumb = dislike
    }

    /**
     * One thumb: a 36x28 plate with an 18dp glyph (dp(9) / dp(5) padding),
     * focusable and touch-focusable exactly like the card pills.
     *
     * The meaning is in the content description as well as the glyph - the mark
     * alone never says which thumb it is - and the press writes through the store
     * (see [pressThumb]). Nothing here requests focus: the panel's initial focus
     * stays on the first card's PLAY pill, and [focusFirst] walks the pick row
     * only, so the header is invisible to it.
     */
    private fun thumbView(up: Boolean): ImageView = ImageView(host).apply {
        tag = if (up) TAG_TASTE_LIKE else TAG_TASTE_DISLIKE
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(28)).apply {
            if (up) marginEnd = dp(8)
        }
        setPadding(dp(9), dp(5), dp(9), dp(5))
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        if (up) {
            setImageResource(R.drawable.ic_detail_thumb_up_outline)
            contentDescription = LIKE_DESCRIPTION
        } else {
            setImageResource(R.drawable.ic_detail_thumb_down_outline)
            contentDescription = DISLIKE_DESCRIPTION
        }
        setOnClickListener { pressThumb(if (up) TasteSignals.LIKE else TasteSignals.DISLIKE) }
        setOnFocusChangeListener { view, hasFocus ->
            applyThumbLook(host, view as ImageView, active = isThumbActive(up), focused = hasFocus)
        }
    }

    /**
     * One thumb's look: the panel's one pill background for its state, and the
     * glyph that state owns.
     *
     * The BACKGROUND is the state - accent fill when the signal is this thumb's,
     * the raised fill plus accent ring when focused, the surface fill otherwise
     * ([pillChipBackground] is the same function the PLAY / DETAILS pills wear) -
     * and there is deliberately no image tint, because the drawables carry their
     * own baked fill (the Detail row's rule). Which thumb this is comes from the
     * view's own tag, so both thumbs take the same call.
     */
    private fun applyThumbLook(
        context: Context,
        view: ImageView,
        active: Boolean,
        focused: Boolean
    ) {
        view.background = pillChipBackground(context, selected = active, focused = focused)
        val up = view.tag == TAG_TASTE_LIKE
        view.setImageResource(
            when {
                up && active -> R.drawable.ic_detail_thumb_up_filled
                up -> R.drawable.ic_detail_thumb_up_outline
                active -> R.drawable.ic_detail_thumb_down_filled
                else -> R.drawable.ic_detail_thumb_down_outline
            }
        )
    }

    /** True when [up]'s thumb is the one the painted signal belongs to. */
    private fun isThumbActive(up: Boolean): Boolean =
        tasteSignal == if (up) TasteSignals.LIKE else TasteSignals.DISLIKE

    /**
     * Paints both thumbs from the store's answer (null = this title carries no
     * signal).
     *
     * Called on show, on every store version change, after a press, and from
     * [applyTheme] - never from a press's own intent, so a write that did not
     * persist is never painted as one that did.
     */
    private fun paintThumbs(signal: String?) {
        tasteSignal = signal
        val like = likeThumb ?: return
        val dislike = dislikeThumb ?: return
        applyThumbLook(host, like, active = signal == TasteSignals.LIKE, focused = like.isFocused)
        applyThumbLook(
            host,
            dislike,
            active = signal == TasteSignals.DISLIKE,
            focused = dislike.isFocused
        )
        like.contentDescription =
            if (signal == TasteSignals.LIKE) LIKED_DESCRIPTION else LIKE_DESCRIPTION
        dislike.contentDescription =
            if (signal == TasteSignals.DISLIKE) DISLIKED_DESCRIPTION else DISLIKE_DESCRIPTION
    }

    /**
     * Starts this raise's live repaint: one read now, so the panel opens already
     * painted, then the store's own version flow - a thumb pressed on the title's
     * Detail page, or in a poster menu, flips these thumbs without a revisit.
     *
     * The read happens off the main thread (the store has a blob to parse), and
     * the job dies with the raise, so no collector outlives the panel it belongs
     * to.
     */
    private fun observeTaste() {
        tasteJob?.cancel()
        tasteJob = null
        val finished = finishedTitle ?: return
        tasteJob = scope()?.launch {
            refreshThumbs(finished)
            TasteSignals.version.collectLatest { refreshThumbs(finished) }
        }
    }

    /** One read of the store, then a repaint on the main thread. */
    private suspend fun refreshThumbs(finished: FinishedTitle) {
        val signal = withContext(Dispatchers.IO) { readSignal(finished) }
        withContext(Dispatchers.Main) { paintThumbs(signal) }
    }

    /**
     * The store's answer for the finished title.
     *
     * No year: the identity is the id pair (both flavors), and the store's name
     * fallback exists for surfaces that have no id at all.
     */
    private fun readSignal(finished: FinishedTitle): String? = TasteSignals.signalFor(
        context = host,
        mediaType = finished.mediaType,
        title = finished.title,
        year = null,
        ids = finished.storeIds
    )

    /**
     * A thumb press: the pressed thumb decides the next signal (the active one
     * clears it, the other flips it), the store answers, and the thumbs repaint
     * from ITS answer - the bookmark toggle's rule, so a press can never claim a
     * signal that did not persist.
     *
     * A press is INERT to everything else in the player, and that is pinned by
     * omission here: no countdown field, handler or runnable is touched (the Up
     * Next countdown belongs to a different panel, and none is running under this
     * one), no dismiss flag is set, the session is neither finished nor handed
     * back, playback is not paused or seeked, credits mode is not entered or left,
     * the picks are not rebuilt, the featured strip does not move, and focus stays
     * on the pressed thumb (the view is never removed, so there is nothing to hand
     * focus back to).
     */
    private fun pressThumb(pressed: String) {
        val finished = finishedTitle ?: return
        // Named apart from the scope accessor this reads: a local called `scope`
        // would shadow it for everything below.
        val hostScope = scope() ?: return
        hostScope.launch {
            val current = withContext(Dispatchers.IO) { readSignal(finished) }
            val next = TasteSignals.nextSignal(current, pressed)
            val stored = withContext(Dispatchers.IO) {
                if (next == null) {
                    TasteSignals.clearSignal(
                        context = host,
                        mediaType = finished.mediaType,
                        title = finished.title,
                        year = null,
                        ids = finished.storeIds
                    )
                } else {
                    TasteSignals.setSignal(
                        context = host,
                        mediaType = finished.mediaType,
                        title = finished.title,
                        posterUrl = finished.posterUrl,
                        year = null,
                        ids = finished.storeIds,
                        signal = next
                    )
                }
            }
            withContext(Dispatchers.Main) {
                // A failed write repaints what the store still holds and reports
                // the failure; a stored one paints the new signal.
                paintThumbs(if (stored) next else current)
                Toast.makeText(
                    host,
                    when {
                        !stored -> "Couldn't save your rating"
                        next == null -> "Rating removed: ${finished.title}"
                        next == TasteSignals.LIKE -> "Liked: ${finished.title}"
                        else -> "Disliked: ${finished.title}"
                    },
                    // The players report real problems for longer, as they do
                    // for every other failure toast.
                    if (stored) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Switches the panel between its plain box and the end-credits arrangement,
     * where the shrunk video owns the top-right corner.
     *
     * [insetPx] is the width of that corner (the video plus the gap the panel
     * used to leave to the screen edge): the panel's fill is notched for it and
     * the header - kicker, title, the thumbs and the featured strip - stops short
     * of it, which is what keeps the header the width it has always been. The
     * pick row and the
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
     * Holds the header - kicker, title, the thumbs and the featured strip - clear
     * of the shrunk video. The strip is built on the first pick, so [feature]
     * applies this too; the taste row is in the list for the same reason, or it
     * would run under the notch while everything around it stops short.
     */
    private fun applyHeaderInset() {
        val header = listOfNotNull(
            panel.getChildAt(0),
            title,
            panel.findViewWithTag<View>(TAG_TASTE_ROW),
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
        // The thumbs wear the same theme-resolved chips, so an AMOLED / accent
        // change re-tints them with the pills beside them rather than leaving the
        // default brass behind.
        paintThumbs(tasteSignal)
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
                // An id of its own, so the thumbs' DOWN wiring can name this pill
                // (see below); nothing else reads it.
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setTextColor(ContextCompat.getColor(host, R.color.kb_void))
            }
            val details = TextView(host).apply {
                text = "DETAILS"
                textSize = 10f
                id = View.generateViewId()
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

            // UP from either pill reaches the thumbs row above the pick row, and
            // DOWN from a thumb lands on the FIRST card's own pills - named by id
            // rather than left to the geometric search across the scroll view
            // boundary, the PillPanelUi precedent. Rebuilt with the pills on every
            // build, never cached across one.
            play.nextFocusUpId = likeThumb?.id ?: View.NO_ID
            details.nextFocusUpId = dislikeThumb?.id ?: View.NO_ID
            if (index == 0) {
                likeThumb?.nextFocusDownId = play.id
                dislikeThumb?.nextFocusDownId = details.id
            }

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
        // The panel is: kicker, title, taste row, [featured strip], row scroll,
        // hint - the strip is inserted programmatically at its own index when
        // missing, one slot BELOW the thumbs row the header already carries.
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

        /** The thumbs row and its two marks (see [ensureTasteRow]). */
        const val TAG_TASTE_ROW = "byw_taste_row"
        const val TAG_TASTE_LIKE = "byw_taste_like"
        const val TAG_TASTE_DISLIKE = "byw_taste_dislike"

        /** What the two thumbs say, in both states (the Detail row's wording). */
        const val LIKE_DESCRIPTION = "Like this title"
        const val LIKED_DESCRIPTION = "Liked — tap to remove your like"
        const val DISLIKE_DESCRIPTION = "Dislike this title"
        const val DISLIKED_DESCRIPTION = "Disliked — tap to remove your dislike"

        /**
         * Kicker, title, taste row, [strip], row scroll, hint.
         *
         * The strip's index MOVED when the thumbs row landed, and the two are one
         * decision: the row inserts at [TASTE_ROW_INDEX] on show, and a strip still
         * inserting at 2 would land ABOVE it and break the header order.
         */
        const val FEATURED_STRIP_INDEX = 3

        /** The thumbs row's own slot: after the kicker and title, before the strip. */
        const val TASTE_ROW_INDEX = 2
    }
}
