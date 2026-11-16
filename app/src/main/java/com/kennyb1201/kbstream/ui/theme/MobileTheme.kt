package com.kennyb1201.kbstream.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/**
 * The phone/tablet theme: regular Material 3, wearing the exact same brand
 * tokens as [KBStreamTheme].
 *
 * The port does not have a second design system. What changes on a phone is
 * the *widget* layer - a tv-material3 `Card` is a focusable, glow-on-focus
 * surface with no ripple, which is the wrong control for a finger - while the
 * palette, the type scale, the corner radii and every spacing step stay the
 * app's own. So this theme rebuilds nothing: it takes the colours from the
 * same state-backed tokens the TV theme paints with (so the AMOLED and accent
 * toggles move both form factors together) and it *maps* the already-built
 * TV typography slot for slot, rather than restating fifteen type styles in a
 * second file where they would drift.
 *
 * The mapping reads `androidx.tv.material3.MaterialTheme.typography`, so it
 * must be composed **inside** [KBStreamTheme] - which is the case at the app
 * root (MainActivity wraps the whole shell). Outside it the tv theme falls
 * back to tv-material3's default typography, and the brand face would be lost
 * silently, so the KDoc on the composable says so and the mobile screens are
 * only ever reached through that root.
 *
 * Colour slots are chosen for what Material 3 components *consume* rather
 * than to mirror the TV scheme name for name:
 *
 *  - `primary` is the accent; `onPrimary` is [KBVoid] because filled buttons
 *    and selected chips draw their label on the brass, where the bright
 *    [KBTextHi] would be unreadable.
 *  - `surface` / `onSurface` are the card plate and its text; `surfaceVariant`
 *    is the raised plate a secondary row or a text field sits on.
 *  - `outline` is deliberately faint: it is the resting border on a text
 *    field or an outlined chip, which on this palette is an edge, not a frame.
 *  - `error` is [KBDanger] so a destructive row means the same thing on both
 *    form factors.
 *
 * [KBStreamMobileShapes] maps Material 3's five shape slots onto the app's
 * existing radii, again so a phone card and a TV card have the same corner
 * (the touch twins all pass the specific `CardShape` / `KBShapePanel` they
 * need; this is the fallback for components that just ask the theme).
 */
@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Part A (spec 2026-11-16): when the viewer asks for it, the phone's scheme
    // comes from the SYSTEM palette - Material You reads the wallpaper and hands
    // the app a palette, Android 12 and up. Everything else about this theme is
    // unchanged: the type scale, the shapes and the AMOLED tokens are the app's.
    //
    // Below API 31 there is no palette to ask for, so the switch simply keeps
    // today's scheme (A3) - the row stays visible and does nothing, which is the
    // honest answer on a device that has no wallpaper palette to give.
    val dynamic = kbDynamicColorState.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val amoled = kbAmoledBlackState.value
    val scheme = remember(dynamic, amoled, KBAccent) {
        if (dynamic) dynamicScheme(context, amoled) else KBStreamMobileColorScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = rememberBrandTypography(),
        shapes = KBStreamMobileShapes,
        content = content
    )
}

/**
 * The wallpaper's palette, with the app's black policy applied ON TOP of it (A4).
 *
 * AMOLED black is a statement about the PANEL, not about the accent: with it on,
 * the background and the surface plates stay the app's true blacks whatever the
 * wallpaper says, and dynamic colour is free to tint the rest. The two settings
 * therefore never fight - one owns the black, the other owns the hue.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun dynamicScheme(context: Context, amoled: Boolean): ColorScheme {
    val base = dynamicDarkColorScheme(context)
    if (!amoled) return base
    return base.copy(
        background = KBVoid,
        onBackground = KBTextHi,
        surface = KBSurface,
        onSurface = KBTextHi,
        surfaceVariant = KBSurfaceRaised,
        onSurfaceVariant = KBTextLo
    )
}

/**
 * The TV type scale, re-typed as a Material 3 [Typography].
 *
 * `Typography` is a different class in each library even though every slot in
 * both is an `androidx.compose.ui.text.TextStyle`; there is no conversion
 * function, and re-declaring the scale would be the drift this port is
 * supposed to avoid (see the note above [KBStreamTheme]'s own typography about
 * slots that silently kept the platform font). So each slot is copied across
 * by name, once.
 */
@Composable
private fun rememberBrandTypography(): Typography {
    val tv = TvMaterialTheme.typography
    return remember(tv) {
        Typography(
            displayLarge = tv.displayLarge,
            displayMedium = tv.displayMedium,
            displaySmall = tv.displaySmall,
            headlineLarge = tv.headlineLarge,
            headlineMedium = tv.headlineMedium,
            headlineSmall = tv.headlineSmall,
            titleLarge = tv.titleLarge,
            titleMedium = tv.titleMedium,
            titleSmall = tv.titleSmall,
            bodyLarge = tv.bodyLarge,
            bodyMedium = tv.bodyMedium,
            bodySmall = tv.bodySmall,
            labelLarge = tv.labelLarge,
            labelMedium = tv.labelMedium,
            labelSmall = tv.labelSmall
        )
    }
}

/**
 * The brand palette as a Material 3 `ColorScheme`.
 *
 * `get()` rather than a captured value for the same reason the TV scheme is a
 * getter: [KBVoid], [KBSurface] and [KBAccent] are snapshot-backed so the
 * AMOLED / pure-black / accent toggles repaint every screen that reads them.
 */
private val KBStreamMobileColorScheme: androidx.compose.material3.ColorScheme
    get() = darkColorScheme(
        primary = KBAccent,
        onPrimary = KBVoid,
        primaryContainer = KBAccent.copy(alpha = 0.24f),
        onPrimaryContainer = KBTextHi,
        inversePrimary = KBVoid,
        secondary = KBTextHi,
        onSecondary = KBVoid,
        secondaryContainer = KBSurfaceRaised,
        onSecondaryContainer = KBTextHi,
        tertiary = KBAccent,
        onTertiary = KBVoid,
        background = KBVoid,
        onBackground = KBTextHi,
        surface = KBSurface,
        onSurface = KBTextHi,
        surfaceVariant = KBSurfaceRaised,
        onSurfaceVariant = KBTextLo,
        surfaceTint = Color.Transparent,
        inverseSurface = KBTextHi,
        inverseOnSurface = KBVoid,
        outline = KBTextLo.copy(alpha = 0.45f),
        outlineVariant = KBTextLo.copy(alpha = 0.22f),
        error = KBDanger,
        onError = KBVoid
    )

/**
 * Material 3's five shape slots on the app's own radii: the small pill and
 * card corners for controls, the panel corner for sheets and dialogs. A twin
 * that needs a specific corner (a poster's chosen edge, a chip) still passes
 * it explicitly; this is what `Button`/`TextField`/`AlertDialog` pick up when
 * they are not told.
 */
private val KBStreamMobileShapes = Shapes(
    extraSmall = KBShapeSmall,
    small = KBShapeCard,
    medium = KBShapeCard,
    large = KBShapePanel,
    extraLarge = KBShapePanel
)
