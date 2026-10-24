package com.stickerstudio.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import com.stickerstudio.app.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Palette: violet primary, mint secondary, soft lavender surfaces (light) / deep ink (dark).
private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFF19B98A),
    onSecondary = Color(0xFF04281F),
    secondaryContainer = Color(0xFFC9F5E6),
    onSecondaryContainer = Color(0xFF0B6B52),
    tertiary = Color(0xFFFFC53D),
    background = Color(0xFFFAF9FF),
    onBackground = Color(0xFF17151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17151F),
    surfaceVariant = Color(0xFFEEEAFB),
    onSurfaceVariant = Color(0xFF5E5A70),
    outline = Color(0xFFB9B6CC),
    outlineVariant = Color(0xFFE4E0F2),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7B5CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2A9E),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFF19D3A2),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFC9F5E6),
    tertiary = Color(0xFFFFC53D),
    background = Color(0xFF0E0D14),
    onBackground = Color(0xFFF1EFF8),
    surface = Color(0xFF17151F),
    onSurface = Color(0xFFF1EFF8),
    surfaceVariant = Color(0xFF262336),
    onSurfaceVariant = Color(0xFFB9B6CC),
    outline = Color(0xFF4A4660),
    outlineVariant = Color(0xFF2E2B42),
    error = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Cairo (SIL OFL), a variable font bundled in res/font, used for Arabic and Latin text. */
@OptIn(ExperimentalTextApi::class)
private val CairoFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.cairo, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val base = Typography()

private fun TextStyle.cairo(weight: FontWeight? = null) =
    copy(fontFamily = CairoFamily, fontWeight = weight ?: fontWeight)

private val AppTypography = Typography(
    displayLarge = base.displayLarge.cairo(),
    displayMedium = base.displayMedium.cairo(),
    displaySmall = base.displaySmall.cairo(),
    headlineLarge = base.headlineLarge.cairo(),
    headlineMedium = base.headlineMedium.cairo(FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.cairo(FontWeight.ExtraBold),
    titleLarge = base.titleLarge.cairo(FontWeight.ExtraBold),
    titleMedium = base.titleMedium.cairo(FontWeight.Bold),
    titleSmall = base.titleSmall.cairo(FontWeight.Bold),
    bodyLarge = base.bodyLarge.cairo(),
    bodyMedium = base.bodyMedium.cairo(),
    bodySmall = base.bodySmall.cairo(),
    labelLarge = base.labelLarge.cairo(FontWeight.Bold),
    labelMedium = base.labelMedium.cairo(FontWeight.SemiBold),
    labelSmall = base.labelSmall.cairo(FontWeight.SemiBold),
)

@Composable
fun StickerStudioTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
