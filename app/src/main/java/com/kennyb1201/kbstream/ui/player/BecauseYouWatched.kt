package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.UNSCRIPTED_TV_GENRES
import com.kennyb1201.kbstream.data.tmdb.displayCardMeta
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import com.kennyb1201.kbstream.data.tmdb.displayMetaLine
import com.kennyb1201.kbstream.data.tmdb.keepRecommendedGenre
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * views from its own layout, its theme colours, its pill styling, and where a
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
    var imdbId: String? = null
)

/** The parent type the player carries, as TMDB spells it. */
internal fun bywMediaType(parentType: String): String = when (parentType.lowercase()) {
    "series", "show", "tv" -> "series"
    else -> "movie"
}

/**
 * How many picks the credits row fills in.
 *
 * Seven, not six: the row is built from a wrap-content set of cards packed to
 * the left, so the six it used to stop at left a visible gap at the end of the
 * line while the end-credits arrangement has the whole screen's width to fill.
 * One more card closes that gap without pushing the row into its scroll.
 */
private const val BYW_PICK_COUNT = 7

/**
 * Weighted-rating rank (IMDB-style) for one credit of a person: the rating
 * blended toward a 6.5 prior worth 200 votes. The cast tier used to sort by
 * `popularity`, which is why "Because you watched Ted Lasso" filled up with talk
 * shows — a guest spot on a nightly show is very popular but rates ~5-6, so it
 * outranked the scripted work the actor is actually known for. This ranks a
 * well-reviewed credit of theirs first and makes a single 9.5 from a dozen
 * voters unable to jump the queue.
 */
private fun personCreditRank(credit: TmdbPersonCredit): Double {
    val votes = (credit.voteCount ?: 0).coerceAtLeast(0).toDouble()
    val average = credit.voteAverage ?: 0.0
    return (average * votes + 6.5 * 200.0) / (votes + 200.0)
}

/**
 * True when a credit is the person appearing as themselves rather than playing a
 * role: talk and award shows, documentaries, archive-footage cameos. TMDB
 * credits these as "Himself" / "Herself" / "Self - Guest" / "(1998) (archive
 * footage)". Without this the cast tier spends one of its four slots per person
 * on a guest appearance — a suggestion for a show rather than for the actor's
 * work.
 */
private fun isSelfAppearance(character: String?): Boolean {
    val role = character?.lowercase()?.trim().orEmpty()
    if (role.isEmpty()) return false
    if (role.contains("archive footage")) return true
    return role.startsWith("self") ||
        role.startsWith("himself") ||
        role.startsWith("herself") ||
        role.startsWith("themselves")
}

/**
 * Builds the because-you-watched lineup as a weighted blend of four signals, then
 * de-dupes against what this profile already watched:
 *
 *  1. SAME FRANCHISE (weight 100) — the TMDB collection the finished title
 *     belongs to, minus its own entry. "You finished Fast Five -> here's Fast &
 *     Furious 6" is the single most-wanted next watch.
 *  2. SAME KEY CREATIVES (weight 60) — other works by the director(s) and
 *     top-billed cast via combined credits, ranked by rating weight
 *     (personCreditRank) rather than popularity. People are the strongest taste
 *     signal in the data.
 *  3. TMDB RECOMMENDATIONS (weight 30) — the content engine; good genre-adjacent
 *     fill but generic on its own.
 *  4. SAME KEYWORDS (weight 25) — TMDB keywords ("heist", "space western")
 *     sharpen the theme match when they exist.
 *
 * Earlier tiers win ties; within a tier the source order stands (TMDB sorts by
 * its own relevance). Already-watched titles, the finished title itself, and
 * unposter-ed entries are dropped.
 */
internal suspend fun buildBecauseYouWatchedPicks(
    ctx: Context,
    tmdbId: Int,
    mediaType: String
): List<BywPick> {
    val repo = TmdbRepository.getInstance(ctx)
    val detail = runCatching {
        repo.getDetailByTmdbId(tmdbId, mediaType)
    }.getOrNull() ?: return emptyList()

    // "Because you watched Ted Lasso" came back all talk shows: the cast tier
    // takes each person's top works by popularity, and a guest spot on a nightly
    // talk show out-popularises every scripted credit they have. Same for TMDB's
    // own recommendation blob now and then. Drop the unscripted formats - unless
    // the title being watched IS one, in which case they are exactly the right
    // suggestion.
    val parentIsUnscripted = detail.genres.any { it.id in UNSCRIPTED_TV_GENRES }
    val keepScripted: (List<Int>?) -> Boolean = { genreIds ->
        keepRecommendedGenre(genreIds, parentIsUnscripted)
    }

    // What this profile has already watched (any parent id, completed or
    // started): the ids come back as imdb ids / raw stream ids, so the filter
    // below normalizes through the same tmdb->imdb resolution.
    val watchedParentIds = runCatching {
        WatchHistoryDatabase.getInstanceScoped(ctx)
            .watchHistoryDao()
            .getAll()
            .map { it.parentId }
            .toHashSet()
    }.getOrNull() ?: HashSet()

    data class Candidate(
        val pick: BywPick,
        val score: Int,
        val order: Int
    )

    val candidates = LinkedHashMap<Int, Candidate>()
    var order = 0

    fun addCandidate(
        candidateTmdbId: Int,
        type: String,
        name: String,
        poster: String?,
        backdrop: String?,
        overview: String?,
        score: Int
    ) {
        if (candidateTmdbId <= 0) return
        if (candidateTmdbId == detail.id) return
        if (poster.isNullOrBlank()) return
        val existing = candidates[candidateTmdbId]
        if (existing != null) {
            // Keep the higher score but original position.
            if (score > existing.score) {
                candidates[candidateTmdbId] = existing.copy(score = score)
            }
            return
        }
        candidates[candidateTmdbId] = Candidate(
            BywPick(
                tmdbId = candidateTmdbId,
                type = type,
                name = name,
                posterUrl = poster?.let { TmdbRepository.POSTER_BASE + it },
                backdropUrl = backdrop?.let { TmdbRepository.BACKDROP_BASE + it },
                logoUrl = null,
                overview = overview?.takeIf { it.isNotBlank() },
                imdbId = null
            ),
            score = score,
            order = order++
        )
    }

    // T1: franchise - same collection, ordered by release date so the "next"
    // entry of the saga is the first suggestion.
    val collectionId = detail.belongsToCollection?.id
    if (collectionId != null) {
        runCatching {
            repo.getKBCollectionItems(collectionId)
        }.getOrNull().orEmpty()
            .sortedBy { it.releaseDate.orEmpty() }
            .forEach { part ->
                addCandidate(
                    candidateTmdbId = part.id,
                    type = "movie",
                    name = part.title ?: part.name.orEmpty(),
                    poster = part.posterPath,
                    backdrop = null,
                    overview = null,
                    score = 100
                )
            }
    }

    // T2: key creatives - directors first, then top-billed cast, using combined
    // credits. Each person contributes their top few works.
    val people = buildList {
        addAll(
            detail.credits?.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .map { it.id }
        )
        addAll(
            detail.credits?.cast.orEmpty()
                .sortedBy { it.order }
                .take(3)
                .map { it.id }
        )
    }.distinct().take(4)

    if (people.isNotEmpty()) {
        coroutineScope {
            people.map { personId ->
                async(Dispatchers.IO) {
                    runCatching {
                        repo.getPerson(personId)
                    }.getOrNull()
                }
            }.awaitAll()
        }.filterNotNull().forEach { person ->
            person.combinedCredits?.cast.orEmpty()
                .filter { credit ->
                    val type = credit.mediaType.orEmpty()
                    (type == "movie" || type == "tv") &&
                        !credit.posterPath.isNullOrBlank() &&
                        !isSelfAppearance(credit.character) &&
                        keepScripted(credit.genreIds)
                }
                // Rank by rating weight, not popularity: a talk-show guest spot
                // is popular but rated ~5-6, so popularity handed the row to The
                // Tonight Show.
                .sortedByDescending { personCreditRank(it) }
                .take(4)
                .forEach { credit ->
                    addCandidate(
                        candidateTmdbId = credit.id,
                        type = if (credit.mediaType == "tv") "series" else "movie",
                        name = credit.title ?: credit.name.orEmpty(),
                        poster = credit.posterPath,
                        backdrop = null,
                        overview = null,
                        score = 60
                    )
                }
        }
    }

    // T3: TMDB's own recommendation engine.
    detail.recommendations?.results.orEmpty()
        .filter { !it.posterPath.isNullOrBlank() && keepScripted(it.genreIds) }
        .take(10)
        .forEach { rec ->
            addCandidate(
                candidateTmdbId = rec.id,
                type = if (rec.name != null) "series" else "movie",
                name = (rec.title ?: rec.name).orEmpty(),
                poster = rec.posterPath,
                backdrop = rec.backdropPath,
                overview = rec.overview,
                score = 30
            )
        }

    // T4: keyword neighbors when the title carries them.
    val keywordIds = detail.keywords.list().map { it.id }.take(3)
    if (keywordIds.isNotEmpty()) {
        keywordIds.forEach { kw ->
            runCatching {
                repo.getKeywordItems(kw, mediaType)
            }.getOrNull().orEmpty()
                .take(6)
                .forEach { item ->
                    addCandidate(
                        candidateTmdbId = item.id,
                        type = if (mediaType == "series") "series" else "movie",
                        name = item.name ?: item.title.orEmpty(),
                        poster = item.posterPath,
                        backdrop = item.backdropPath,
                        overview = item.overview,
                        score = 25
                    )
                }
        }
    }

    // Resolve imdb ids only for the survivors (the final ordering), so the
    // stream resolution on PLAY doesn't burn a lookup burst. The pool is a few
    // wider than the row: the watched-history filter below cuts into it, and a
    // pool the same size as the row would then show a short row.
    val ranked = candidates.values
        .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order })
        .toList()
        .take(BYW_PICK_COUNT + 5)

    val filtered = ranked.filter { candidate ->
        val pick = candidate.pick
        // Cross-check watch history by tmdb id: history stores imdb ids, so
        // resolve lazily (single lookup per finalist) - candidates whose imdb id
        // matches a watched parent are dropped.
        val imdb = runCatching {
            repo.resolveImdbId(pick.tmdbId, pick.type)
        }.getOrNull()
        pick.imdbId = imdb
        val seen = imdb != null && imdb in watchedParentIds
        !seen
    }.map { it.pick }

    return filtered.take(BYW_PICK_COUNT)
}

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
 * The background of the screen the player's INFO button brings up: the same
 * accent hairline and 16dp corners the XML shape carried, but with an OPAQUE,
 * theme-aware fill.
 *
 * The old @drawable/info_panel_bg was translucent (#CC10141B), which is wrong
 * on the one surface that is meant to be read: it sits over the player's own
 * translucent overlay gradient, so the two washed into each other and the rails
 * behind them showed through the codec lines. Both players use this - the main
 * player's info screen and the MPV engine's readout pill - so the two engines'
 * info looks the same and both follow the AMOLED / pure-black toggles instead of
 * a fixed panel color.
 */
internal fun infoPanelDrawable(context: Context): GradientDrawable =
    roundedPanelDrawable(
        context,
        playerPanelRaisedColor(context),
        16f
    ).apply {
        val accent = ContextCompat.getColor(context, R.color.kb_accent)
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

    /** The pick whose sources are being resolved right now, if any. */
    private var resolving: Int? = null

    /** The hint line's own text, so a status message can be undone. */
    private var hintDefault: CharSequence? = null

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
        // Captured once, from the layout's own copy: a status message must not
        // become the text it is restored to.
        hintDefault = hintView()?.text
    }

    /**
     * The hint line under the row ("Pick a title, or press BACK to exit").
     *
     * Found by position rather than by id: each player's copy lives in its own
     * layout, and the two ids differ (byw_hint / mpv_byw_hint). The hint is the
     * panel's last direct TextView - the kicker and the title come before it,
     * and the featured strip is a container, so it cannot be mistaken for one.
     */
    private fun hintView(): TextView? {
        for (index in panel.childCount - 1 downTo 0) {
            val child = panel.getChildAt(index)
            if (child is TextView && child !== title) return child
        }
        return null
    }

    /** Shows a one-line status where the row's hint text normally sits. */
    private fun setHint(text: CharSequence) {
        val hint = hintView() ?: return
        if (hintDefault == null) hintDefault = hint.text
        hint.text = text
    }

    /** Puts the hint line's own text back. */
    private fun restoreHint() {
        val hint = hintView() ?: return
        hintDefault?.let { hint.text = it }
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
        resolving = null
        restoreHint()

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
                    runCatching {
                        TmdbRepository.getInstance(host)
                            .getDetailByTmdbId(pick.tmdbId, pick.type)
                    }.getOrNull()
                } ?: return@forEach
                val logo = detail.images?.logos
                    ?.filter { !it.filePath.isNullOrBlank() }
                    ?.sortedWith(compareByDescending { it.iso6391 == "en" })
                    ?.firstOrNull()?.filePath
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
                    runCatching {
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
     * PLAY for one card: resolve its sources here, then hand the pick to the
     * activity only when there is something to play.
     *
     * The press used to fire the activity's own resolve and - when the addons
     * answered nothing - the activity quietly swapped to the DETAILS screen,
     * leaving no way to tell a slow resolve from a dead button. Resolving in the
     * panel makes the press visible while it runs, and an empty answer is
     * reported on the hint line instead of bouncing the user elsewhere. A pick
     * that does resolve is handed off exactly as before, and the activity's own
     * resolve then hits the addon repository's short-lived cache, so this adds
     * no wait of its own.
     */
    private fun playPick(pick: BywPick) {
        if (resolving != null) return
        val resolveScope = scope()
        if (resolveScope == null) {
            // No live scope to resolve on: keep the old behaviour rather than
            // turning the button into a no-op.
            onPlay(pick, imdbFor(pick))
            return
        }
        val imdbId = imdbFor(pick)
        resolving = pick.tmdbId
        setHint("Finding a stream for ${pick.name}…")
        resolveScope.launch {
            val streams = withContext(Dispatchers.IO) {
                runCatching {
                    StreamsViewModel(host.application).resolve(pick.type, imdbId)
                }.getOrNull()
            }.orEmpty()
            resolving = null
            if (!isVisible) return@launch
            if (streams.any { !it.url.isNullOrBlank() }) {
                restoreHint()
                onPlay(pick, imdbId)
            } else {
                setHint("No stream found for ${pick.name} — press DETAILS to open it")
            }
        }
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
                setTextColor(ContextCompat.getColor(host, R.color.kb_accent))
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
