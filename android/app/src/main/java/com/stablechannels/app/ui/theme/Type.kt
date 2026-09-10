package com.stablechannels.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stablechannels.app.R

// Single variable font; weights selected via variation settings
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val InterTight = FontFamily(
    Font(R.font.inter_tight, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_tight, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_tight, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val ScTypography = Typography(
    headlineLarge = TextStyle(fontFamily = InterTight, fontSize = 26.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = InterTight, fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = InterTight, fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = InterTight, fontSize = 19.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = InterTight, fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(fontFamily = InterTight, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = InterTight, fontSize = 14.5.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontFamily = InterTight, fontSize = 13.5.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = InterTight, fontSize = 12.5.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = InterTight, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    labelMedium = TextStyle(fontFamily = InterTight, fontSize = 12.5.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = InterTight, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

// Non-M3 wallet roles (spec §3.2)
object ScTextStyles {
    val Amount = TextStyle(fontFamily = InterTight, fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp, fontFeatureSettings = "tnum")
    val AmountSmall = TextStyle(fontFamily = InterTight, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp, fontFeatureSettings = "tnum")
}
