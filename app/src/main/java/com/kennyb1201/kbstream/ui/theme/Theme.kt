package com.kennyb1201.kbstream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.kennyb1201.kbstream.R

// Base palette -- a private screening room, not another dark-mode SaaS panel.
// The public KBVoid / KBSurface / KBSurfaceRaised tokens below are state-backed
// so the AMOLED toggle can swap them app-wide without touching call sites:
// reads inside composables are snapshot-tracked, so flipping the toggle
// recomposes every screen that paints one of these colors.
private val KBVoidDefault = Color(0xFF0A0E14)
private val KBSurfaceDefault = Color(0xFF141A24)
private val KBSurfaceRaisedDefault = Color(0xFF1D2530)
private val KBVoidAmoled = Color(0xFF000000)
private val KBSurfaceAmoled = Color(0xFF06080B)
private val KBSurfaceRaisedAmoled = Color(0xFF0D1117)
private val KBSurfacePureBlack = Color(0xFF000000)
private val KBSurfaceRaisedPureBlack = Color(0xFF050505)

/** Backing state for the AMOLED black toggle. Init from prefs at app start. */
val kbAmoledBlackState = mutableStateOf(false)

/**
 * Backing state for the Pure Black Surface toggle (KB-style): when on,
 * cards / panels / containers join the background at true black. Requires
 * the AMOLED toggle — the getters below enforce that dependency, so a
 * synced blob that flips AMOLED off also lifts pure black.
 */
val kbPureBlackSurfaceState = mutableStateOf(false)

private val pureBlackActive: Boolean
    get() = kbAmoledBlackState.value && kbPureBlackSurfaceState.value

val KBVoid: Color
    get() = if (kbAmoledBlackState.value) KBVoidAmoled else KBVoidDefault
val KBSurface: Color
    get() = when {
        pureBlackActive -> KBSurfacePureBlack
        kbAmoledBlackState.value -> KBSurfaceAmoled
        else -> KBSurfaceDefault
    }
val KBSurfaceRaised: Color
    get() = when {
        pureBlackActive -> KBSurfaceRaisedPureBlack
        kbAmoledBlackState.value -> KBSurfaceRaisedAmoled
        else -> KBSurfaceRaisedDefault
    }

val KBAccent = Color(0xFFE8A33D) // brass / projector-bulb warmth -- the one accent
val KBTextHi = Color(0xFFF3EFE4)
val KBTextLo = Color(0xFF8891A0)
val KBDanger = Color(0xFFB0453C)
val KBSuccess = Color(0xFF3DBB6A)
// Semantic badge companions (muted, same screening-room palette): season
// finales, next-up, new seasons on Up-Next cards. Raw Material-palette
// literals previously used here clashed with the brass accent.
val KBRust = Color(0xFFA8542E)   // burnt sienna — season finale
val KBSteel = Color(0xFF3E5C76)  // desaturated navy — next up
val KBPlum = Color(0xFF6E4E7E)   // muted aubergine — new season

val CardShape = RoundedCornerShape(12.dp())

// The corner scale. Radii had drifted to fourteen distinct literals
// (2/3/4/5/6/8/10/12/13/14/16/18/20/999) with no rule behind which surface
// got which: two dialogs a screen apart at 16 and 18 and 20, the same inner
// row at 14 on one screen and 12 on the next. Two dp of corner is invisible
// at ten feet, so the drift bought nothing and only made the code
// unpredictable. Everything now comes from this scale. The only literals left
// are the 2–5dp hairline radii on progress bars and dividers, where the shape
// of a few-dp-tall bar genuinely does depend on the exact value.
val KBShapePanel = RoundedCornerShape(18.dp()) // dialogs, panels, hero cards
val KBShapeCard = CardShape // cards, tiles, inner rows, list rows
val KBShapeChip = RoundedCornerShape(10.dp()) // rating / badge chips
val KBShapeSmall = RoundedCornerShape(8.dp()) // small pills, poster fans
val KBShapePill = RoundedCornerShape(999.dp()) // avatars, fully-round pills

// The focus scale. D-pad focus is the app's most-touched feedback — a viewer
// crosses a rail in twenty focus steps — and like the corner radii it had
// drifted to nine hand-picked values (1.0 / 1.015 / 1.02 / 1.03 / 1.04 / 1.05 /
// 1.06 / 1.08 / 1.1) with no rule behind them: two chips in one row grew by
// different amounts, and the same kind of button grew by 1.04 on one screen
// and 1.08 on another. The rule that actually applies is the surface's own
// size — a fixed percentage of a chip is a few pixels, and the same percentage
// of a full-bleed row is a lurch that shoves its neighbours — so the scale is
// now chosen by surface class and nothing else:
//
//   row (1.02) < card (1.03) < button (1.04) < chip (1.06) < tile (1.08)
//
// KBFocusNone is not "unpolished": a surface wider than about half the screen
// takes its focus cue from colour and border instead, because growing it would
// move more than it lights up.
const val KBFocusNone = 1f
const val KBFocusRow = 1.02f
const val KBFocusCard = 1.03f
const val KBFocusButton = 1.04f
const val KBFocusChip = 1.06f
const val KBFocusTile = 1.08f

// The press-in: what an interactive surface does under Select. tv-material3's
// default press scale is 1f, so until now a click produced nothing but the
// ripple — the one interaction a viewer performs thousands of times had no
// physical feel. A surface that travels toward the viewer when focused and
// back away when pressed is most of the difference between poking a picture
// and pressing a button.
const val KBFocusPressed = 0.97f

// Focus glow radius: the shared card, and the smaller controls that host it.
val KBFocusGlow = 12.dp()
val KBFocusGlowSmall = 8.dp()

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

val OswaldFamily = FontFamily(
    Font(R.font.oswald_medium, FontWeight.Medium),
    Font(R.font.oswald_semibold, FontWeight.SemiBold),
    Font(R.font.oswald_bold, FontWeight.Bold)
)

private val KBStreamColorScheme get() = darkColorScheme(
    primary = KBAccent,
    background = KBVoid,
    surface = KBSurface,
    onBackground = KBTextHi,
    onSurface = KBTextHi
)

// EVERY slot is defined, not just the handful the first pass needed. A
// Typography slot that is left out silently keeps TV Material3's default,
// which is the platform font — so screen titles, dialog titles, section
// labels and chips written against headlineSmall / headlineMedium /
// titleSmall / labelMedium were rendering in Roboto while the rest of the app
// was in Oswald. Which typeface a heading got depended on nothing but which
// style name the call site happened to pick. Sizes stay close to the Material
// defaults they replaced (Oswald is condensed, so the same sp reads slightly
// smaller) and the hierarchy is strictly ordered:
//   displayLarge > displayMedium > displaySmall > headlineLarge >
//   headlineMedium > headlineSmall > titleLarge > titleMedium > titleSmall
private val KBStreamTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 0.5.sp
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.5.sp
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.5.sp
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.5.sp
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.5.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.5.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 1.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 1.5.sp
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.3.sp, lineHeight = 24.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.3.sp, lineHeight = 20.sp
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp, lineHeight = 16.sp
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.8.sp
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp
    )
)

@Composable
fun KBStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KBStreamColorScheme,
        typography = KBStreamTypography,
        content = content
    )
}
