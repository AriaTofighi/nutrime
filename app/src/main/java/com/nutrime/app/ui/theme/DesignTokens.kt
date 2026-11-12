// GENERATED from design/tokens.json by tools/gen_design_tokens.py. Do not edit by hand.
package com.nutrime.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens shared with the web app (see design/tokens.json). */
object DesignTokens {
    object TextSize {
        val Xs2 = 11.sp
        val Xs = 12.sp
        val Sm = 12.5f.sp
        val Md = 13.sp
        val Base = 14.sp
        val Lg = 16.sp
        val Xl = 18.sp
        val Xl2 = 22.sp
        val Xl3 = 30.sp
        val Xl4 = 36.sp
    }

    /** Variable-font weights (the static Inter files round to the nearest 100). */
    object Weight {
        const val Regular = 420
        const val Medium = 520
        const val Semibold = 600
        const val Bold = 680
    }

    /** Letter spacing in em. */
    object Tracking {
        const val Tight = -0.022f
        const val Wide = 0.06f
    }

    object Space {
        val S1 = 4.dp
        val S2 = 8.dp
        val S3 = 12.dp
        val S4 = 16.dp
        val S5 = 20.dp
        val S6 = 24.dp
        val S8 = 32.dp
        val S10 = 40.dp
        val S12 = 48.dp
    }

    object Radius {
        val Xs = 3.dp
        val Sm = 5.dp
        val Md = 7.dp
        val Lg = 10.dp
        val Xl = 14.dp
        val Xl2 = 20.dp
        val Full = 999.dp
    }

    object Motion {
        val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        const val DurationFast = 120
        const val Duration = 200
        const val DurationSlow = 420
    }

    object Control {
        val HeightSm = 28.dp
        val Height = 34.dp
        val HeightLg = 40.dp
        val RowHeight = 48.dp
    }

    enum class Accent(val label: String, val hue: Float, val saturation: Float, val swatchLightness: Float) {
        EMERALD("Emerald", 158f, 0.64f, 0.4f),
        INDIGO("Indigo", 238f, 0.72f, 0.58f),
        ROSE("Rose", 346f, 0.74f, 0.52f),
        AMBER("Amber", 32f, 0.9f, 0.48f);

        companion object {
            val Default = EMERALD
        }
    }

    val Light = ThemeTokens(
        isDark = false,
        bg = Color(0xFFF6F6F4),
        bgSunken = Color(0xFFEEEEEB),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFFFFFFF),
        surfaceHover = Color(0xFFF6F6F4),
        surfaceActive = Color(0xFFEEEEEB),
        border = Color(0x14161615),
        borderStrong = Color(0x24161615),
        text = Color(0xFF161615),
        textSecondary = Color(0xFF5D5D58),
        textTertiary = Color(0xFF7B7B75),
        textInverse = Color(0xFFFFFFFF),
        track = Color(0x12161615),
        overlay = Color(0x52161615),
        energy = Color(0xFFF0643C),
        protein = Color(0xFF2F9E6E),
        carbs = Color(0xFF2B8FD6),
        fat = Color(0xFFA463D6),
        alcohol = Color(0xFFD6487B),
        danger = Color(0xFFD43F2E),
        dangerSoft = Color(0x1AD43F2E),
        warning = Color(0xFFD9861A),
        success = Color(0xFF2F9E6E),
        accentLightness = 0.36f,
        accentHoverLightness = 0.31f,
        accentSoftAlpha = 0.1f,
        accentSofterAlpha = 0.06f,
        onAccent = Color(0xFFFFFFFF),
    )

    val Dark = ThemeTokens(
        isDark = true,
        bg = Color(0xFF17181C),
        bgSunken = Color(0xFF131418),
        surface = Color(0xFF1F2025),
        surfaceRaised = Color(0xFF26272D),
        surfaceHover = Color(0xFF26272D),
        surfaceActive = Color(0xFF2E3036),
        border = Color(0x12FFFFFF),
        borderStrong = Color(0x1FFFFFFF),
        text = Color(0xFFF4F4F6),
        textSecondary = Color(0xFFA9ABB4),
        textTertiary = Color(0xFF878A94),
        textInverse = Color(0xFF17181C),
        track = Color(0x12FFFFFF),
        overlay = Color(0x99000000),
        energy = Color(0xFFFF7A52),
        protein = Color(0xFF3FC48A),
        carbs = Color(0xFF4AA8F0),
        fat = Color(0xFFBB84EA),
        alcohol = Color(0xFFEE6B98),
        danger = Color(0xFFFF6A58),
        dangerSoft = Color(0x1FFF6A58),
        warning = Color(0xFFF2A33A),
        success = Color(0xFF3FC48A),
        accentLightness = 0.56f,
        accentHoverLightness = 0.62f,
        accentSoftAlpha = 0.14f,
        accentSofterAlpha = 0.08f,
        onAccent = Color(0xFF08140F),
    )
}

/** Resolved colors for one theme (light or dark); the accent hue is applied in NutriMeTheme. */
data class ThemeTokens(
    val isDark: Boolean,
    val bg: Color,
    val bgSunken: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHover: Color,
    val surfaceActive: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textInverse: Color,
    val track: Color,
    val overlay: Color,
    val energy: Color,
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val alcohol: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val success: Color,
    val accentLightness: Float,
    val accentHoverLightness: Float,
    val accentSoftAlpha: Float,
    val accentSofterAlpha: Float,
    val onAccent: Color,
)
