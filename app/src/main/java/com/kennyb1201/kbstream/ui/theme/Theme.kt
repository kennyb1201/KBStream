package com.kennyb1201.kbstream.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences

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

/**
 * Re-reads the AMOLED / pure-black prefs into the live theme state.
 *
 * The stored prefs are the source of truth and the state below is a Compose
 * snapshot value; this is the single place the two are tied together, so the
 * settings store itself can stay a plain data-layer object with no UI import.
 *
 * Call it wherever the stored value may have moved behind the theme's back:
 * before the first composition at launch, on every active-profile switch (the
 * toggles are profile-scoped), and right after a remote sync blob is applied.
 */
fun refreshThemeMirrors(context: Context) {
    kbAmoledBlackState.value = AppPreferences.getAmoledBlack(context)
    kbPureBlackSurfaceState.value = AppPreferences.getPureBlackSurface(context)
    kbAccentIndexState.value =
        AppPreferences.getAccentIndex(context, DEFAULT_ACCENT_INDEX)
}

/**
 * The chosen accent as an ARGB int, for the XML views (player chrome, guide
 * rows) that tint themselves at runtime rather than through a Compose token.
 * Reads the STORED index, not the live state: it is called from activities
 * that read their theming straight from prefs.
 */
fun themeAccentColor(context: Context): Int =
    KBAccentPalette
        .getOrNull(AppPreferences.getAccentIndex(context, DEFAULT_ACCENT_INDEX))
        ?.color
        ?.toArgb()
        ?: KBAccentPalette[DEFAULT_ACCENT_INDEX].color.toArgb()

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

/** One choice in the accent palette: a name for the settings grid, and its colour. */
data class KBAccentColor(val name: String, val color: Color)

/**
 * The app's accent palette - a broad spread of hues for the global theme
 * changer, all bright enough to read AS the accent on the dark screening-room
 * surfaces (a mid-tone that looks right as a chip fill is invisible as a
 * 1dp border). The FIRST entry is the default, which is why the stored
 * preference is an index rather than a colour: index 0 means "untouched".
 */
val KBAccentPalette: List<KBAccentColor> = listOf(
    KBAccentColor("Brass", Color(0xFFE8A33D)),
    KBAccentColor("Amber", Color(0xFFF0B44A)),
    KBAccentColor("Gold", Color(0xFFF2C14E)),
    KBAccentColor("Ember", Color(0xFFE8752F)),
    KBAccentColor("Coral", Color(0xFFFF6B5C)),
    KBAccentColor("Crimson", Color(0xFFE14B57)),
    KBAccentColor("Ruby", Color(0xFFD2385A)),
    KBAccentColor("Rose", Color(0xFFF06A8A)),
    KBAccentColor("Magenta", Color(0xFFE247A6)),
    KBAccentColor("Orchid", Color(0xFFC05CE0)),
    KBAccentColor("Violet", Color(0xFF9B6BE8)),
    KBAccentColor("Indigo", Color(0xFF6E7BE8)),
    KBAccentColor("Sapphire", Color(0xFF4C82E8)),
    KBAccentColor("Azure", Color(0xFF3C9BE8)),
    KBAccentColor("Ice", Color(0xFF7FD3E8)),
    KBAccentColor("Cyan", Color(0xFF35C4D8)),
    KBAccentColor("Teal", Color(0xFF2FC2A8)),
    KBAccentColor("Emerald", Color(0xFF35C56B)),
    KBAccentColor("Lime", Color(0xFF8FD14A)),
    KBAccentColor("Chartreuse", Color(0xFFC2D64B)),
    KBAccentColor("Sand", Color(0xFFD9C08A)),
    KBAccentColor("Copper", Color(0xFFC98A5E)),
    KBAccentColor("Slate", Color(0xFF8FA3BF)),
    KBAccentColor("Neon Pink", Color(0xFFFF4FA3))
)

/** The accent index used when the viewer has never chosen one (the brass). */
const val DEFAULT_ACCENT_INDEX = 0

/** Backing state for the accent choice. Init from prefs at app start. */
val kbAccentIndexState = mutableStateOf(DEFAULT_ACCENT_INDEX)

/**
 * The one accent, app-wide. Every Compose call site reads this getter, so
 * changing [kbAccentIndexState] recomposes the whole UI - the same mechanism
 * the AMOLED surface tokens above use. The palette is never empty, so the
 * default is always a real colour.
 */
val KBAccent: Color
    get() = KBAccentPalette
        .getOrNull(kbAccentIndexState.value)
        ?.color
        ?: KBAccentPalette[DEFAULT_ACCENT_INDEX].color
val KBTextHi = Color(0xFFF3EFE4)
val KBTextLo = Color(0xFF8891A0)
val KBDanger = Color(0xFFB0453C)
val KBSuccess = Color(0xFF3DBB6A)
// Semantic badge companions (muted, same screening-room palette): season
// finales, next-up, new seasons on Up-Next cards. Raw Material-palette
// literals previously used here clashed with the brass accent.
val KBRust = Color(0xFFA8542E)   // burned sienna — season finale
val KBSteel = Color(0xFF3E5C76)  // desaturated navy — next up
val KBPlum = Color(0xFF6E4E7E)   // muted aubergine — new season

val CardShape = RoundedCornerShape(12.dp)

// The corner scale. Radii had drifted to fourteen distinct literals
// (2/3/4/5/6/8/10/12/13/14/16/18/20/999) with no rule behind which surface
// got which: two dialogs a screen apart at 16 and 18 and 20, the same inner
// row at 14 on one screen and 12 on the next. Two dp of corner is invisible
// at ten feet, so the drift bought nothing and only made the code
// unpredictable. Everything now comes from this scale. The only literals left
// are the 2–5dp hairline radii on progress bars and dividers, where the shape
// of a few-dp-tall bar genuinely does depend on the exact value.
val KBShapePanel = RoundedCornerShape(18.dp) // dialogs, panels, hero cards
val KBShapeCard = CardShape // cards, tiles, inner rows, list rows
val KBShapeChip = RoundedCornerShape(10.dp) // rating / badge chips
val KBShapeSmall = RoundedCornerShape(8.dp) // small pills, poster fans
val KBShapePill = RoundedCornerShape(999.dp) // avatars, fully-round pills

// A firmer-than-card radius for a poster's "Pill" edge. Not KBShapePill: a
// capsule rounds a portrait poster's short side by half its width, which eats
// into the artwork and clips the corners of the image. This is a plain, larger
// corner instead, so a pill poster reads as "more curved than rounded" rather
// than as a lozenge.
val KBShapeSoftPill = RoundedCornerShape(20.dp)

// The focus scale. D-pad focus is the app's most-touched feedback — a viewer
// crosses a rail in twenty focus steps — and like the corner radii it had
// drifted to nine hand-picked values (1.0 / 1.015 / 1.02 / 1.03 / 1.04 / 1.05 /
// 1.06 / 1.08 / 1.1) with no rule behind them: two chips in one row grew by
// different amounts, and the same kind of button grew by 1.04 on one screen
// and 1.08 on another. The rule that actually applies is the surface's own
// size — a fixed percentage of a chip is a few pixels, and the same percentage
// of a full-bleed row is a lurch that shoves its neighbors — so the scale is
// now chosen by surface class and nothing else:
//
//   row (1.02) < card (1.03) < button (1.04) < chip (1.06) < tile (1.08)
//
// KBFocusNone is not "unpolished": a surface wider than about half the screen
// takes its focus cue from color and border instead, because growing it would
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
val KBFocusGlow = 12.dp
val KBFocusGlowSmall = 8.dp

// Side room a horizontal CHIP row must leave inside its own scroll bounds.
//
// A LazyRow clips its content to its own bounds, so a chip that grows by
// KBFocusChip (and carries a KBFocusGlowSmall glow) was cut flat along the
// row's left edge: the discover screens' genre row clipped the focused "All"
// chip, and the Library filter strip had the same cut on its first control,
// which is the one focus lands on. Neither could be fixed by padding the row
// from outside - a parent's padding is OUTSIDE the clip, which is why the
// chip still lost its left border and glow there.
//
// A row pairs `contentPadding = PaddingValues(horizontal = KBFocusChipInset)`
// (the inset is inside the clip, so the growth has somewhere to go) with
// `offset(x = -KBFocusChipInset)` (which cancels it on the outside, leaving
// the caller's left alignment - 28dp on the discover screens, 24dp in the
// Library - exactly as it was). The last chip gets the mirrored room, so
// focusing it at the end of the row is not clipped either.
//
// 12dp covers the widest chip at this scale (growth is 3% of a chip's own
// width per side, so only a 400dp chip would outgrow it) plus the 8dp glow.
val KBFocusChipInset = 12.dp

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
