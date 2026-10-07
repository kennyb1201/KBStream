package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.DEFAULT_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.ui.theme.themeVoidWindowColor

/*
 * The players are the one corner of this app that is not Compose: their chrome
 * is inflated from XML, and an XML drawable resolves @color/kb_surface,
 * @color/kb_surface_raised and @color/kb_accent ONCE, at inflation. Those are
 * static resources, so they can only ever be the default palette - #141A24
 * buttons, #1D2530 panels, a brass focus ring - while the rest of the app
 * paints from the live theme tokens (KBVoid / KBSurface / KBSurfaceRaised /
 * KBAccent), which is what the AMOLED and pure-black toggles and the global
 * accent move. Hence this pass: re-resolve the same fills, strokes and text
 * from the CURRENT theme, over the whole view tree, right after the layout is
 * inflated.
 *
 * Reported: "player buttons and surfaces aren't following amoled toggle and
 * focus ring around buttons aren't following theme colors". Both halves were
 * the same gap. What the earlier per-activity walk could reach was a single
 * shape (panel_bg, pill_chip_bg) and a ripple whose content is a single shape
 * (button_surface_bg / button_accent_bg). What it could NOT reach - and so left
 * on the default palette under every theme - was any chrome drawn by a SELECTOR:
 * @drawable/mpv_control_bg, the background of every control-bar button in both
 * players, whose focused state is the app's focus look (a raised plate behind a
 * 2dp accent stroke), plus the selector inside @drawable/picker_item_bg's ripple
 * and @drawable/channel_guide_item_bg. A StateListDrawable is neither a
 * GradientDrawable nor a single-shape ripple, so the twenty-five buttons kept
 * #141A24 and the D-pad ring stayed brass - exactly the pair of symptoms above.
 *
 * Matching is by TONE rather than by resource id, so a new control is covered
 * without anybody adding it to a hand-kept list, and a drawable that happens to
 * be some other colour (badge art, artwork, a scrim) is left alone.
 */
private const val XML_VOID_RGB = 0x0A0E14
private const val XML_SURFACE_RGB = 0x141A24
private const val XML_RAISED_RGB = 0x1D2530

/** The focused chrome's ring: the 2dp accent stroke every focused surface draws. */
private const val FOCUS_RING_DP = 2f

/** The selection marker's ring: the hairline accent outline that means "you are here". */
private const val SELECTION_RING_DP = 1f

/** Fallback corner for a chrome plate whose own radius cannot be read back. */
private const val CHROME_CORNER_DP = 10f

/**
 * Paints a player's own window in the void tone the current theme paints its
 * shell with, before the video surface exists.
 *
 * The theme's `colorBackground` is a static resource, so it can only ever be the
 * ordinary #0A0E14 void: an AMOLED install therefore drew the ordinary tone
 * wherever the video surface does not reach (the letterbox edges, the frame
 * before the surface paints), which is the same mismatch MainActivity's launch
 * window had. [themeVoidWindowColor] is the window's own read of the toggle, and
 * this is the one call site per engine.
 */
internal fun applyPlayerWindowTone(activity: android.app.Activity) {
    activity.window.setBackgroundDrawable(
        ColorDrawable(themeVoidWindowColor(activity))
    )
}

/**
 * Whether the theme has moved away from the palette the XML chrome was inflated
 * with.
 *
 * When it has not, every rebuild below would be an identity, so the whole pass
 * is skipped - the same guard the players used before it, and the reason a
 * caller can run the pass unconditionally.
 */
internal fun chromeThemeMoved(context: Context): Boolean =
    AppPreferences.getAmoledBlack(context) ||
        AppPreferences.getAccentIndex(context, DEFAULT_ACCENT_INDEX) != DEFAULT_ACCENT_INDEX

/**
 * Themes the classic-View chrome in [root]: every control-bar button, panel,
 * pill, picker row, cast card and guide row under it.
 *
 * A tree walk rather than a list of ids, because which chrome a title shows
 * depends on the title. Call it once after the layout is inflated, and again
 * for anything inflated later (picker rows, cast cards) - those land long after
 * the activity's own tree was themed. A no-op when the theme is still the
 * default palette.
 */
internal fun retintPlayerChrome(root: View, context: Context) {
    if (!chromeThemeMoved(context)) return
    retintPlayerChromeView(root, context)
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            retintPlayerChrome(root.getChildAt(index), context)
        }
    }
}

/**
 * One view of that walk: retint the accent it carries as a COLOUR (text, seek
 * and progress tints, image tints), then replace its background if that
 * background is one of the XML chrome fills.
 */
internal fun retintPlayerChromeView(view: View, context: Context) {
    if (!chromeThemeMoved(context)) return
    val xmlAccent = ContextCompat.getColor(context, R.color.kb_accent)
    val accent = themeAccentColor(context)
    if (accent != xmlAccent) retintAccentView(view, accent, xmlAccent)
    val background = view.background ?: return
    themedChromeBackground(context, background)?.let { view.background = it }
}

/**
 * The themed stand-in for [background], or null when it is not chrome (and so
 * is left exactly as the XML inflated it).
 *
 * Every kind of drawable the chrome actually uses is handled, because each one
 * is a place the theme can silently stop at:
 *
 *  - a plain shape (panel_bg, pill_chip_bg, circle_avatar_bg);
 *  - a selector (mpv_control_bg, channel_guide_item_bg) - state by state, which
 *    is what carries the focus ring;
 *  - a ripple over either of those (button_surface_bg, button_accent_bg,
 *    picker_item_bg) - its content is swapped and its press flash re-resolved,
 *    while its mask and geometry are kept;
 *  - a flat @color fill (the fatal-error card's @color/kb_void), which is a
 *    ColorDrawable and so invisible to a drawable-shaped walk.
 *
 * Callers must replace the view's background with the result rather than
 * mutating in place: every view inflated from one XML resource shares one
 * ConstantState, so recolouring the inflated drawable itself would repaint every
 * other view that came from it.
 */
internal fun themedChromeBackground(context: Context, background: Drawable): Drawable? = when {
    background is RippleDrawable -> themedRipple(context, background)
    background is StateListDrawable -> themedSelector(context, background)
    background is GradientDrawable -> themedShape(context, background)
    background is ColorDrawable -> themedChromeColor(context, background.color)?.let {
        ColorDrawable(it)
    }
    else -> null
}

/**
 * A shape recoloured to the theme's fill, with everything else it was inflated
 * with left alone - shape type, corners, and (for gradient drawables) the stops
 * and orientation - because the copy comes off the shape's own ConstantState and
 * only its colours are then rewritten.
 *
 * A stroke cannot be recoloured this way: the platform exposes no getter for a
 * shape's stroke width or colour (only the setters), so a stroke has to be
 * rebuilt from a known convention instead - which is what [themedPlate] does for
 * the focus rings, the one stroke in the chrome that matters.
 */
private fun themedShape(context: Context, shape: GradientDrawable): GradientDrawable? {
    val fill = shape.color?.defaultColor
    val themedFill = fill?.let { themedChromeColor(context, it) }
    // A gradient has stops instead of a solid fill (the control bar's overlay
    // scrim), and they are void-toned: under AMOLED that scrim has to go black
    // like the void it sits on, or the bar keeps a navy haze over a pure-black
    // screen.
    val stops = shape.colors
    val themedStops: IntArray? = stops?.map { themedChromeColor(context, it) ?: it }?.toIntArray()
    val stopsMoved = stops != null && themedStops != null && !themedStops.contentEquals(stops)
    if (themedFill == null && !stopsMoved) return null
    val copy = shape.constantState?.newDrawable()?.mutate() as? GradientDrawable ?: return null
    themedFill?.let { copy.setColor(it) }
    if (stopsMoved) themedStops?.let { copy.colors = it }
    return copy
}

/**
 * A selector rebuilt state by state.
 *
 * This is the branch the focus rings needed. The state sets and their ORDER are
 * copied as they are - so the focused state stays first, and the in-player
 * guide's third "this is the channel playing now" state survives - while each
 * state's plate is rebuilt from the app's own focus / selection conventions,
 * resolved from the CURRENT theme:
 *
 *   focused  -> the raised plate behind a 2dp accent stroke (the app's one focus
 *               look, the same pair the pills and the SKIP INTRO control draw)
 *   selected -> the raised plate with a hairline accent outline, so "you are
 *               here" survives scrolling away from the focused row
 *   default  -> the theme's surface fill, with whatever else that state had
 *
 * A state that is neither focused, selected nor the default is left exactly as
 * the XML had it: this maps the conventions the chrome is written in, and does
 * not invent a look for a state it does not know.
 */
private fun themedSelector(context: Context, selector: StateListDrawable): StateListDrawable? {
    if (selector.stateCount == 0) return null
    var changed = false
    val rebuilt = StateListDrawable()
    for (index in 0 until selector.stateCount) {
        val states = runCatching { selector.getStateSet(index) }.getOrNull()
        // addState() refuses a null drawable, so a state that does not resolve
        // means leaving the whole selector as it was rather than half-rebuilding
        // it around a hole.
        val stateDrawable = runCatching { selector.getStateDrawable(index) }.getOrNull()
            ?: return null
        val spec = chromePlateSpec(context, states)
        val themed = when {
            spec != null -> themedPlate(context, stateDrawable, spec)

            // The default state: no state at all matches, which the platform
            // hands back as an EMPTY set rather than null. Only its fill moves,
            // so whatever else that state carried (the hairline outline the
            // picker rows do not have, the radius they all do) is left as it
            // was.
            states == null || states.isEmpty() ->
                (stateDrawable as? GradientDrawable)?.let { themedShape(context, it) }

            else -> null
        }
        if (themed != null) changed = true
        rebuilt.addState(states, themed ?: stateDrawable)
    }
    return if (changed) rebuilt else null
}

/** One chrome state's plate, resolved from the current theme. */
internal data class ChromePlateSpec(
    val fill: Int,
    val ringColor: Int,
    val ringWidthDp: Float
)

/**
 * The plate the chrome draws for [state], or null when that state is not one of
 * the two the app's focus language describes.
 *
 * Split out from the drawable it paints because a stroke cannot be read back
 * off a GradientDrawable (the platform has getters for a shape's fill, corners
 * and gradient, and only SETTERS for its stroke), so the ring is not something a
 * test - or a later reader - can recover from the built drawable. As a spec it
 * is stated once, in one place, and both the builder and the test read it:
 * focused is the raised plate behind a 2dp accent stroke, selected is the same
 * plate with the hairline accent outline.
 */
internal fun chromePlateSpec(context: Context, state: IntArray?): ChromePlateSpec? {
    val focused = state?.contains(android.R.attr.state_focused) == true
    val selected = state?.contains(android.R.attr.state_selected) == true
    return when {
        focused -> ChromePlateSpec(
            fill = playerPanelRaisedColor(context),
            ringColor = themeAccentColor(context),
            ringWidthDp = FOCUS_RING_DP
        )

        selected -> ChromePlateSpec(
            fill = playerPanelRaisedColor(context),
            ringColor = themeAccentColor(context),
            ringWidthDp = SELECTION_RING_DP
        )

        else -> null
    }
}

/**
 * The chrome plate for one state: the spec's fill behind its ring, at the corner
 * the state's own drawable already used.
 *
 * The radius is READ back from that drawable (getCornerRadius is public, unlike
 * the stroke getters), so the control bar's 10dp buttons, the picker's 8dp rows
 * and the guide's 10dp rows each keep their own corner instead of collapsing to
 * one.
 */
private fun themedPlate(
    context: Context,
    original: Drawable,
    spec: ChromePlateSpec
): GradientDrawable {
    val density = context.resources.displayMetrics.density
    val radius = (original as? GradientDrawable)
        ?.cornerRadius
        ?.takeIf { it > 0f }
        ?: (CHROME_CORNER_DP * density)
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(spec.fill)
        cornerRadius = radius
        setStroke((spec.ringWidthDp * density).toInt().coerceAtLeast(1), spec.ringColor)
    }
}

/**
 * A ripple whose CONTENT is themed and whose press flash is re-resolved, with
 * its mask, geometry and padding left alone.
 *
 * The flash has to be rewritten too, because it is the same brass: the surface
 * fill buttons flash the accent while the accent-filled SKIP INTRO control
 * flashes the void, and a flash left at the XML accent would tint every press on
 * a themed install with the default brass. On API 31+ the ripple's own effect
 * colour can be read back and re-resolved directly; below that the XML pairing
 * is reconstructed from the content's fill - an accent-filled control flashes
 * the void, anything else flashes the accent - which is exactly the rule the two
 * XML ripples are written in.
 */
private fun themedRipple(context: Context, ripple: RippleDrawable): RippleDrawable? {
    if (ripple.numberOfLayers <= 0) return null
    // Layer 0 is only the CONTENT while a layer is tagged as one: an untagged
    // single-item ripple (which is how these are written) leaves no id at all,
    // so fall back to the first layer. A mask-only ripple then resolves to its
    // mask, whose colour matches no chrome tone, so nothing changes for it.
    val contentIndex = (0 until ripple.numberOfLayers)
        .firstOrNull { index ->
            runCatching { ripple.getId(index) == android.R.id.content }.getOrDefault(false)
        } ?: 0
    val content = runCatching { ripple.getDrawable(contentIndex) }.getOrNull() ?: return null
    val themed = themedChromeBackground(context, content) ?: return null
    val flash = themedRippleFlash(context, (content as? GradientDrawable)?.color?.defaultColor)
    val copy = ripple.constantState?.newDrawable()?.mutate() as? RippleDrawable ?: return null
    copy.setDrawable(contentIndex, themed)
    copy.setColor(ColorStateList.valueOf(flash))
    return copy
}

/**
 * The press flash a chrome ripple should flash after the theme has moved,
 * reconstructed from [contentFill] - the fill the ripple sits behind.
 *
 * It has to be reconstructed rather than read: a ripple's own flash colour is
 * only readable through RippleDrawable#getEffectColor(), which is API 31 while
 * this app ships minSdk 26, so the players' chrome walk deliberately never
 * calls it (lint's NewApi would fail the release build). The rule below is the
 * one the two XML ripples are actually written in - an accent-filled control
 * flashes the void, anything else flashes the accent - so every press on a
 * themed install flashes the chosen theme instead of the brass it was inflated
 * with.
 */
internal fun themedRippleFlash(context: Context, contentFill: Int?): Int {
    val xmlAccent = ContextCompat.getColor(context, R.color.kb_accent)
    val accentFilled = contentFill != null &&
        (contentFill and 0x00FFFFFF) == (xmlAccent and 0x00FFFFFF)
    return if (accentFilled) {
        ContextCompat.getColor(context, R.color.kb_void)
    } else {
        themeAccentColor(context)
    }
}

/**
 * One colour re-resolved from the theme, or null when it is not one of the
 * chrome tones.
 *
 * Alpha is carried over rather than discarded: the chrome writes the same tone
 * at several strengths (the accent as a 2dp ring, as a quarter-strength info
 * hairline, the void as the overlay scrim's three gradient stops), and all of
 * those have to keep their own transparency and only change hue - otherwise a
 * scrim would go opaque, or a focus ring would lose its edge.
 *
 * The accent is matched against @color/kb_accent, the brass the XML resolved,
 * not against the chosen accent: a rebuild is only needed when the two differ,
 * and matching the chosen colour would also hit its own fill on a second pass.
 */
private fun themedChromeColor(context: Context, argb: Int): Int? {
    val amoled = AppPreferences.getAmoledBlack(context)
    val rgb = argb and 0x00FFFFFF
    val alpha = argb and 0xFF000000.toInt()
    val xmlAccent = ContextCompat.getColor(context, R.color.kb_accent)
    val accent = themeAccentColor(context)
    return when {
        rgb == XML_VOID_RGB ->
            if (amoled) alpha else null

        rgb == XML_SURFACE_RGB ->
            if (amoled) alpha or (playerPanelSurfaceColor(context) and 0x00FFFFFF) else null

        // Also the cast card's avatar oval: @drawable/circle_avatar_bg hard-codes
        // the raised tone, so it is themed by the same rule as the panels it
        // matches - the circle is all that shows for a cast member with no photo.
        rgb == XML_RAISED_RGB ->
            if (amoled) alpha or (playerPanelRaisedColor(context) and 0x00FFFFFF) else null

        rgb == (xmlAccent and 0x00FFFFFF) && accent != xmlAccent ->
            alpha or (accent and 0x00FFFFFF)

        else -> null
    }
}
