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

private val KBStreamTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 0.5.sp
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.5.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 1.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 1.5.sp
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
