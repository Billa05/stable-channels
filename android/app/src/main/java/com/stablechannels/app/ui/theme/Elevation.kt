package com.stablechannels.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class ScElevation { Subtle, Raised, Floating }

// Layered elevation: tight layer + diffuse layer, never borders (spec §3.3)
fun Modifier.scShadow(level: ScElevation, shape: Shape, insetHighlight: Boolean = false): Modifier = composed {
    val dark = LocalDarkTheme.current
    val (tight, diffuse, insetAlpha) = when (level) {
        ScElevation.Subtle -> Triple(2.dp, 10.dp, if (dark) 0.06f else 0.95f)
        ScElevation.Raised -> Triple(4.dp, 18.dp, if (dark) 0.07f else 0.95f)
        ScElevation.Floating -> Triple(8.dp, 28.dp, if (dark) 0.08f else 0.95f)
    }
    val spot = if (dark) 0.45f else 0.13f
    this
        .shadow(diffuse, shape, clip = false, ambientColor = Color.Black.copy(alpha = spot * 0.4f), spotColor = Color.Black.copy(alpha = spot))
        .shadow(tight, shape, clip = false, ambientColor = Color.Black.copy(alpha = spot * 0.7f), spotColor = Color.Black.copy(alpha = spot * 0.7f))
        .then(if (insetHighlight) Modifier.scInsetHighlight(insetAlpha) else Modifier)
}

// 1px top-light edge on interactive surfaces
fun Modifier.scInsetHighlight(topAlpha: Float = 0.95f): Modifier = drawWithContent {
    drawContent()
    val radius = CornerRadius((size.height / 2f).coerceAtMost(24f))
    drawRoundRect(
        color = Color.White.copy(alpha = topAlpha),
        topLeft = Offset(0f, 0f),
        size = size,
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}
