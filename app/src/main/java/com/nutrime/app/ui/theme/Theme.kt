package com.nutrime.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nutrime.app.R
import com.nutrime.app.ui.theme.DesignTokens.Accent
import com.nutrime.app.ui.theme.DesignTokens.TextSize
import com.nutrime.app.ui.theme.DesignTokens.Tracking

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Theme colors: the generated tokens plus the accent resolved for the chosen hue. */
@Immutable
data class NutriColors(
    val t: ThemeTokens,
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val accentSofter: Color,
) {
    val isDark get() = t.isDark
    val bg get() = t.bg
    val bgSunken get() = t.bgSunken
    val surface get() = t.surface
    val surfaceRaised get() = t.surfaceRaised
    val surfaceHover get() = t.surfaceHover
    val surfaceActive get() = t.surfaceActive
    val border get() = t.border
    val borderStrong get() = t.borderStrong
    val text get() = t.text
    val textSecondary get() = t.textSecondary
    val textTertiary get() = t.textTertiary
    val textInverse get() = t.textInverse
    val track get() = t.track
    val overlay get() = t.overlay
    val onAccent get() = t.onAccent
    val energy get() = t.energy
    val protein get() = t.protein
    val carbs get() = t.carbs
    val fat get() = t.fat
    val alcohol get() = t.alcohol
    val danger get() = t.danger
    val dangerSoft get() = t.dangerSoft
    val warning get() = t.warning
    val success get() = t.success

    companion object {
        fun of(tokens: ThemeTokens, accent: Accent): NutriColors {
            fun hsl(l: Float, alpha: Float = 1f) = Color.hsl(accent.hue, accent.saturation, l, alpha)
            return NutriColors(
                t = tokens,
                accent = hsl(tokens.accentLightness),
                accentHover = hsl(tokens.accentHoverLightness),
                accentSoft = hsl(tokens.accentLightness, tokens.accentSoftAlpha),
                accentSofter = hsl(tokens.accentLightness, tokens.accentSofterAlpha),
            )
        }
    }
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Maps a variable-font weight token (e.g. 520) to the closest bundled static weight. */
fun weight(token: Int): FontWeight = when {
    token < 470 -> FontWeight.Normal
    token < 560 -> FontWeight.Medium
    token < 650 -> FontWeight.SemiBold
    else -> FontWeight.Bold
}

/** Text styles, named after their role in the web app's stylesheet. */
@Immutable
data class NutriType(
    val hero: TextStyle,
    val display: TextStyle,
    val pageTitle: TextStyle,
    val title: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val small: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val numeric: TextStyle,
)

private fun style(size: TextUnit, weightToken: Int, tracking: Float = 0f, lineHeight: Float = 1.4f) = TextStyle(
    fontFamily = Inter,
    fontSize = size,
    fontWeight = weight(weightToken),
    letterSpacing = tracking.em,
    lineHeight = (size.value * lineHeight).sp,
)

private val W = DesignTokens.Weight

val NutriTypography = NutriType(
    hero = style(TextSize.Xl4, W.Bold, -0.03f, 1.05f).copy(fontFeatureSettings = "tnum"),
    display = style(TextSize.Xl3, W.Bold, Tracking.Tight, 1.15f),
    pageTitle = style(TextSize.Xl2, W.Bold, Tracking.Tight, 1.2f),
    title = style(TextSize.Lg, W.Semibold, Tracking.Tight, 1.3f),
    cardTitle = style(TextSize.Base, W.Semibold, -0.01f, 1.3f),
    body = style(TextSize.Base, W.Regular),
    bodyMedium = style(TextSize.Base, W.Medium),
    small = style(TextSize.Sm, W.Regular),
    caption = style(TextSize.Xs, W.Regular),
    label = style(TextSize.Xs2, W.Semibold, Tracking.Wide),
    numeric = style(TextSize.Base, W.Semibold).copy(fontFeatureSettings = "tnum"),
)

private val LocalNutriColors = staticCompositionLocalOf { NutriColors.of(DesignTokens.Light, Accent.Default) }

object NutriTheme {
    val colors: NutriColors
        @Composable @ReadOnlyComposable get() = LocalNutriColors.current
    val type: NutriType get() = NutriTypography
}

@Composable
fun NutriMeTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: Accent = Accent.Default,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = remember(dark, accent) { NutriColors.of(if (dark) DesignTokens.Dark else DesignTokens.Light, accent) }
    // Material components (text fields, dialogs, menus, pickers) take their colors from the same tokens.
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    val materialColors = scheme.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.accent,
        secondary = colors.textSecondary,
        onSecondary = colors.textInverse,
        secondaryContainer = colors.surfaceActive,
        onSecondaryContainer = colors.text,
        tertiary = colors.accent,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.bgSunken,
        onSurfaceVariant = colors.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surfaceRaised,
        surfaceContainerHigh = colors.surfaceRaised,
        surfaceContainerHighest = colors.surfaceActive,
        surfaceBright = colors.surface,
        surfaceDim = colors.bgSunken,
        inverseSurface = colors.text,
        inverseOnSurface = colors.textInverse,
        outline = colors.borderStrong,
        outlineVariant = colors.border,
        error = colors.danger,
        onError = Color.White,
        errorContainer = colors.dangerSoft,
        onErrorContainer = colors.danger,
        scrim = colors.overlay,
    )
    val r = DesignTokens.Radius
    val t = NutriTypography
    val materialType = Typography().let { d ->
        Typography(
            displayLarge = t.display, displayMedium = t.display, displaySmall = t.display,
            headlineLarge = t.pageTitle, headlineMedium = t.pageTitle, headlineSmall = t.pageTitle,
            titleLarge = t.title, titleMedium = t.cardTitle, titleSmall = t.cardTitle,
            bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.small,
            labelLarge = t.bodyMedium, labelMedium = t.caption, labelSmall = d.labelSmall.copy(fontFamily = Inter),
        )
    }
    CompositionLocalProvider(LocalNutriColors provides colors) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = materialType,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(r.Sm),
                small = RoundedCornerShape(r.Md),
                medium = RoundedCornerShape(r.Lg),
                large = RoundedCornerShape(r.Xl),
                extraLarge = RoundedCornerShape(r.Xl),
            ),
            content = content,
        )
    }
}
