package com.kennyb1201.kbstream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.kennyb1201.kbstream.R

// Color tokens -- a private screening room, not another dark-mode SaaS panel
val KBVoid = Color(0xFF0A0E14)
val KBSurface = Color(0xFF141A24)
val KBSurfaceRaised = Color(0xFF1D2530)
val KBAccent = Color(0xFFE8A33D) // brass / projector-bulb warmth -- the one accent
val KBTextHi = Color(0xFFF3EFE4)
val KBTextLo = Color(0xFF8891A0)
val KBDanger = Color(0xFFB0453C)
val KBSuccess = Color(0xFF3DBB6A)

val CardShape = RoundedCornerShape(12.dp())
private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

val OswaldFamily = FontFamily(
    Font(R.font.oswald_medium, FontWeight.Medium),
    Font(R.font.oswald_semibold, FontWeight.SemiBold),
    Font(R.font.oswald_bold, FontWeight.Bold)
)

private val KBStreamColorScheme = darkColorScheme(
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
