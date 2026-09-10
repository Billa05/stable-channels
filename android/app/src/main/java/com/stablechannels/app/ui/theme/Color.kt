package com.stablechannels.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

// WCAG relative luminance + contrast ratio for token invariants
fun contrastRatio(a: Color, b: Color): Double {
    val la = relLuminance(a); val lb = relLuminance(b)
    val lighter = maxOf(la, lb); val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relLuminance(c: Color): Double {
    fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * lin(c.red.toDouble()) + 0.7152 * lin(c.green.toDouble()) + 0.0722 * lin(c.blue.toDouble())
}

// Ink-neutral palette (spec §3.1) — the app has exactly two chromatic colors: teal (USD) and amber (BTC)
internal val ScLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF101014),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8EC),
    onPrimaryContainer = Color(0xFF101014),
    secondary = Color(0xFF6E6E77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8EC),
    onSecondaryContainer = Color(0xFF2B2B33),
    tertiary = Color(0xFF2B2B33),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5F5F7),
    onTertiaryContainer = Color(0xFF101014),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF101014),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101014),
    surfaceVariant = Color(0xFFF5F5F7),
    onSurfaceVariant = Color(0xFF6E6E77),
    outline = Color(0xFFC9C9CF),
    outlineVariant = Color(0xFFE8E8EC),
)

internal val ScDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF0C0C0F),
    primaryContainer = Color(0xFF26262C),
    onPrimaryContainer = Color(0xFFF5F5F7),
    secondary = Color(0xFF8F8F99),
    onSecondary = Color(0xFF0C0C0F),
    secondaryContainer = Color(0xFF26262C),
    onSecondaryContainer = Color(0xFFE4E4E9),
    tertiary = Color(0xFFE4E4E9),
    onTertiary = Color(0xFF0C0C0F),
    tertiaryContainer = Color(0xFF19191F),
    onTertiaryContainer = Color(0xFFF5F5F7),
    error = Color(0xFFF87171),
    onError = Color(0xFF0C0C0F),
    errorContainer = Color(0xFF991B1B),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0C0C0F),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF19191F),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1F1F26),
    onSurfaceVariant = Color(0xFF8F8F99),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF26262C),
)

// Semantic tokens: usdStable/btcNative = text-free fills (bars, dots); *Text = AA on surfaces; *Container/on* = text-bearing fills
@Immutable
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val usdStable: Color,
    val usdText: Color,
    val usdContainer: Color,
    val onUsd: Color,
    val btcNative: Color,
    val btcText: Color,
    val btcContainer: Color,
    val onBtc: Color,
)

internal val ScLightSemanticColors = SemanticColors(
    success = Color(0xFF0F766E),   // one green: teal-700
    warning = Color(0xFFB45309),   // amber-700
    error = Color(0xFFC62828),
    info = Color(0xFF2563EB),
    usdStable = Color(0xFF0D9488), // teal-600 — balance bar / fills without text
    usdText = Color(0xFF0F766E),
    usdContainer = Color(0xFF0F766E),
    onUsd = Color(0xFFFFFFFF),
    btcNative = Color(0xFFF59E0B), // amber-500 — balance bar / fills without text
    btcText = Color(0xFFB45309),
    btcContainer = Color(0xFFB45309),
    onBtc = Color(0xFFFFFFFF),
)

internal val ScDarkSemanticColors = SemanticColors(
    success = Color(0xFF2DD4BF),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    info = Color(0xFF60A5FA),
    usdStable = Color(0xFF2DD4BF),
    usdText = Color(0xFF2DD4BF),
    usdContainer = Color(0xFF2DD4BF),
    onUsd = Color(0xFF0C0C0F),
    btcNative = Color(0xFFFBBF24),
    btcText = Color(0xFFFBBF24),
    btcContainer = Color(0xFFFBBF24),
    onBtc = Color(0xFF0C0C0F),
)
