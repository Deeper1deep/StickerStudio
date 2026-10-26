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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Palette: violet primary + aqua accent, WhatsApp-green action colour. Dark is the signature look
// (deep ink surfaces, neon accents); light is the same system on soft lavender.
private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF2A12A8),
    secondary = Color(0xFF16A35A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF3DF),
    onSecondaryContainer = Color(0xFF0A5C32),
    tertiary = Color(0xFF00B89A),
    background = Color(0xFFF6F4FF),
    onBackground = Color(0xFF17151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17151F),
    surfaceVariant = Color(0xFFEFEBFF),
    onSurfaceVariant = Color(0xFF605B78),
    outline = Color(0xFFB4B0CB),
    outlineVariant = Color(0xFFE4E0F2),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8F6BFF),
    onPrimary = Color(0xFF0B0B14),
    primaryContainer = Color(0xFF241F42),
    onPrimaryContainer = Color(0xFFC9BAFF),
    secondary = Color(0xFF27E27E),
    onSecondary = Color(0xFF05240F),
    secondaryContainer = Color(0xFF0F3A23),
    onSecondaryContainer = Color(0xFF9DF5C4),
    tertiary = Color(0xFF26E6C4),
    background = Color(0xFF0B0B14),
    onBackground = Color(0xFFF2F1FA),
    surface = Color(0xFF15151F),
    onSurface = Color(0xFFF2F1FA),
    surfaceVariant = Color(0xFF1D1D2B),
    onSurfaceVariant = Color(0xFFA19EBA),
    outline = Color(0xFF4A4A63),
    outlineVariant = Color(0xFF2A2A3C),
    error = Color(0xFFFFB4AB),
)

/** True when the current theme is the dark one. */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Violet -> aqua, used for progress bars and accents. */
@Composable
fun brandBrush(): Brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))

/** Gradient behind the big "create" card. */
@Composable
fun heroBrush(): Brush =
    if (isDarkTheme()) Brush.linearGradient(listOf(Color(0xFF2B1F6E), Color(0xFF10343B)))
    else Brush.linearGradient(listOf(Color(0xFF6D4AFF), Color(0xFFA184FF)))

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
