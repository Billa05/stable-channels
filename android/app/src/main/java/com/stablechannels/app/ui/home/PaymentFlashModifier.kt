package com.stablechannels.app.ui.home

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.stablechannels.app.ui.theme.LocalSemanticColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Modifier that applies a payment flash animation:
 * - Scale 1.0 → 1.08 over 300ms (ease-out)
 * - Scale 1.08 → 1.0 over 400ms (ease-in-out)
 * - Tint overlay during scale-up phase
 *
 * Restarts from beginning if a new flash triggers during animation.
 */
@Composable
fun Modifier.paymentFlash(isFlashing: Boolean, tint: Color = Color.Unspecified): Modifier {
    val flashTint = if (tint == Color.Unspecified) LocalSemanticColors.current.usdStable else tint
    val scale = remember { Animatable(1f) }
    val tintAlpha = remember { Animatable(0f) }

    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            // Reset to start if re-triggered
            scale.snapTo(1f)
            tintAlpha.snapTo(0f)

            // Phase 1: scale up + green tint (300ms ease-out)
            coroutineScope {
                launch {
                    scale.animateTo(
                        targetValue = 1.08f,
                        animationSpec = tween(durationMillis = 300, easing = EaseOut)
                    )
                }
                launch {
                    tintAlpha.animateTo(
                        targetValue = 0.35f,
                        animationSpec = tween(durationMillis = 300, easing = EaseOut)
                    )
                }
            }

            // Phase 2: scale down + remove tint (400ms ease-in-out)
            coroutineScope {
                launch {
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 400, easing = EaseInOut)
                    )
                }
                launch {
                    tintAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 400, easing = EaseInOut)
                    )
                }
            }
        }
    }

    val currentScale by scale.asState()
    val currentTintAlpha by tintAlpha.asState()

    return this
        .graphicsLayer {
            scaleX = currentScale
            scaleY = currentScale
            alpha = 1f - (currentTintAlpha * 0.3f) // slight dim during flash
        }
        .drawWithContent {
            drawContent()
            if (currentTintAlpha > 0f) {
                drawRect(
                    color = flashTint.copy(alpha = currentTintAlpha),
                    size = size
                )
            }
        }
}
