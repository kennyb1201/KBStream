package com.kennyb1201.kbstream.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val dynamic = DynamicAccentRules.applies(
        sdkInt = Build.VERSION.SDK_INT,
        switchOn = kbDynamicColorState.value
    )
    val amoled = kbAmoledBlackState.value
    // F2: a wallpaper change has to re-derive the scheme, or the accent is a
    // snapshot of whatever the wallpaper was when this screen was first drawn.
    val wallpaperRevision = rememberWallpaperRevision(context, active = dynamic)
    // The SDK floor is part of the CONDITION, not a fact the reader has to take
    // on trust: [DynamicAccentRules.applies] already refuses a palette below API
    // 31, but it answers with a Boolean and Lint cannot see through it - so the
    // release gate read this call as an unguarded API-31 use and stopped the
    // build (0.9.356 never published). The guard spells out the same decision in
    // the form both the compiler and a reader can check at the call site.
    val scheme =
        if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remember(context, amoled, wallpaperRevision) { dynamicScheme(context, amoled) }
        } else {
            // The unchanged path, key for key: with the switch off this still
            // caches the brand scheme and still rebuilds it when the accent
            // moves.
            remember(dynamic, amoled, KBAccent) { KBStreamMobileColorScheme }
        }
    // Part A F1: the accent itself follows the wallpaper. Published AFTER the
    // pass rather than written during it - a snapshot value the content below
    // reads must not change under the composition that is building it, and the
    // extra pass this costs is the one where every KBAccent read moves at once.
    // Cleared when the switch is off, so the picked accent comes back exactly.
    SideEffect {
        kbDynamicAccentState.value = if (dynamic) scheme.primary else null
    }
    // Expressive pass (spec 2026-11-22, F2): the phone is the only place in the
    // app that composes an expressive theme, and it is composed HERE rather than
    // at the shell root on purpose - see the hard rule below.
    //
    // Everything above this line is untouched: the same `scheme` object, the same
    // dynamic/AMOLED decision, the same wallpaper revision and the same
    // SideEffect. The wrapper only adds what 1.5.0 made public - the increased
    // shape slots, the emphasized type styles and the expressive motion scheme -
    // so accent, AMOLED, pure-black and dynamic colour repaint exactly as they
    // did, because they feed the same values into it.
    //
    // The hard rule this pass carries: MobileTheme is the ONLY M3 theme provider
    // in the app. A TV composes inside KBStreamTheme (tv-material3) with no M3
    // provider above it, which is why a shared component that reads M3 typography
    // - KBQrCode is the one - keeps reading M3's defaults there and picks up the
    // expressive scale here. Adding a provider at the shell root to "share" it
    // would move the TV's type and break the identity this pass is measured by.
    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = rememberBrandTypography(),
        shapes = KBStreamMobileShapes,
        motionScheme = MotionScheme.expressive(),
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
 * The wallpaper palette's primary, for the callers that are not Compose (the
 * player chrome and the guide rows read [themeAccentColor] as an ARGB int).
 *
 * Material You answers this from the system resources the platform keeps in step
 * with the wallpaper; there is no cache to invalidate here.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
internal fun dynamicWallpaperPrimary(context: Context): Color =
    dynamicDarkColorScheme(context).primary

/**
 * A counter that ticks when the wallpaper changes, for [MobileTheme]'s scheme.
 *
 * Android gives a running app no recomposition on a wallpaper change, so with
 * F2's "the accent follows the wallpaper" this is the missing wire: the system
 * broadcast bumps this, the scheme is recomputed against the new palette, and
 * [SideEffect] publishes the new primary. Registered only while the switch
 * actually applies - on a device without a palette there is nothing to follow.
 */
@Composable
private fun rememberWallpaperRevision(context: Context, active: Boolean): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, active) {
        if (!active) return@DisposableEffect onDispose { }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                revision++
            }
        }
        // A protected SYSTEM broadcast, which is why no exported flag is needed
        // on API 33+: the platform sends this one, not another app.
        @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
        val registered = runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED)
            )
        }.isSuccess
        onDispose {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return revision
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
            labelSmall = tv.labelSmall,
            // The expressive cut (spec 2026-11-22, F2). All fifteen slots are
            // filled, not just the three families the phone headlines with: a
            // slot left at its M3 default would drop the brand face on the one
            // screen that asks for the emphasized style, which is the drift this
            // file exists to prevent. Only display/headline/title are actually
            // emphasized - the body and label cuts are the TV styles verbatim,
            // because metadata at ten inches and at arm's length want the same
            // weight, and M3's own emphasized body is heavier than the plate
            // under it was designed for.
            displayLargeEmphasized = tv.displayLarge.emphasized(),
            displayMediumEmphasized = tv.displayMedium.emphasized(),
            displaySmallEmphasized = tv.displaySmall.emphasized(),
            headlineLargeEmphasized = tv.headlineLarge.emphasized(),
            headlineMediumEmphasized = tv.headlineMedium.emphasized(),
            headlineSmallEmphasized = tv.headlineSmall.emphasized(),
            titleLargeEmphasized = tv.titleLarge.emphasized(),
            titleMediumEmphasized = tv.titleMedium.emphasized(),
            titleSmallEmphasized = tv.titleSmall.emphasized(),
            bodyLargeEmphasized = tv.bodyLarge,
            bodyMediumEmphasized = tv.bodyMedium,
            bodySmallEmphasized = tv.bodySmall,
            labelLargeEmphasized = tv.labelLarge,
            labelMediumEmphasized = tv.labelMedium,
            labelSmallEmphasized = tv.labelSmall
        )
    }
}

/**
 * M3's expressive "emphasized" cut of a type slot: one weight step heavier and
 * slightly tighter tracking, at the same size, line height and family.
 *
 * Derived from the TV slot rather than restated with literal sizes, for the same
 * reason the base scale is mapped rather than re-declared: a size change to
 * [KBStreamTypography] must move the phone's emphasized styles with it. The
 * weight step is taken from whatever the slot already is, so a style the TV
 * scale already sets heavy becomes heavier instead of being pulled back to a
 * literal SemiBold.
 */
private fun TextStyle.emphasized(): TextStyle {
    val base = fontWeight ?: FontWeight.Normal
    // Tracking is tightened only where it is measured in sp: an em-based
    // tracking is already relative to the size, and scaling it twice would
    // shrink it away. Everything the brand scale actually declares is sp.
    val tracking = if (letterSpacing.isSp) (letterSpacing.value * 0.92f).sp else letterSpacing
    return copy(
        fontWeight = FontWeight((base.weight + 100).coerceAtMost(FontWeight.Black.weight)),
        letterSpacing = tracking
    )
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
    extraLarge = KBShapePanel,
    // The expressive increased slots (spec 2026-11-22, F2). Phone-only values,
    // declared here rather than in Theme.kt: the TV's corner scale is a KB token
    // both form factors share and must not move, while these three exist only
    // for the phone's expressive components. They step up from the app's own
    // panel radius (18dp) in the same proportion M3 steps its own scale, and the
    // existing five slots above keep their exact radii so nothing that asks the
    // theme today changes corner.
    largeIncreased = RoundedCornerShape(24.dp),
    extraLargeIncreased = RoundedCornerShape(28.dp),
    extraExtraLarge = RoundedCornerShape(32.dp)
)
