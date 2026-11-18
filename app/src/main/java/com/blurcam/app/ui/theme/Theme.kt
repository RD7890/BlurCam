@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.blurcam.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blurcam.app.R

/** Flat palette. No gradients, no shadows anywhere in the app. */
object Bc {
    val Ink = Color(0xFF0B0C0F)
    val Panel = Color(0xFF14161B)
    val PanelHi = Color(0xFF1C1F26)
    val Line = Color(0xFF2A2E37)
    val Text = Color(0xFFF4F5F7)
    val TextDim = Color(0xFF9097A3)
    val TextMute = Color(0xFF5B616C)
    val Accent = Color(0xFFFF5B2E)   // aperture orange
    val Mint = Color(0xFF3DDC97)
    val Rec = Color(0xFFFF3B4E)
    val Amber = Color(0xFFFFC857)
}

object Motion {
    val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter() = tween<T>(durationMillis = 460, easing = ExpoOut)
    fun <T> quick() = tween<T>(durationMillis = 180, easing = Standard)
    fun <T> snappy() = spring<T>(dampingRatio = 0.72f, stiffness = 420f)
}

private fun grotesk(w: Int) = Font(
    R.font.space_grotesk, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

private fun mono(w: Int) = Font(
    R.font.jetbrains_mono, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

val Grotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
val Mono = FontFamily(mono(400), mono(500), mono(700))

object BcType {
    val Display = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-1.2).sp)
    val Title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)
    val Small = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val Button = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.1).sp)
    val Label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp)
    val Value = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp)
    val Time = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
}

@Composable
fun BlurCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Bc.Accent,
            onPrimary = Bc.Ink,
            background = Bc.Ink,
            onBackground = Bc.Text,
            surface = Bc.Panel,
            onSurface = Bc.Text,
        ),
        content = content
    )
}
