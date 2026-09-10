package com.stablechannels.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.ScElevation
import com.stablechannels.app.ui.theme.scShadow

/**
 * A pill-shaped status capsule with slide+fade enter/exit animations.
 *
 * - Pill shape with RoundedCornerShape(50)
 * - Layered raised shadow (ScElevation.Raised) on surface color
 * - Slide-up + fade-in on appear (300ms)
 * - Slide-down + fade-out on disappear (300ms)
 */
@Composable
fun StatusCapsule(
    message: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = message.isNotEmpty(),
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(durationMillis = 300)),
        exit = slideOutVertically(
            targetOffsetY = { it / 2 },
            animationSpec = tween(durationMillis = 300)
        ) + fadeOut(animationSpec = tween(durationMillis = 300)),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            onClick = { onClick?.invoke() },
            modifier = Modifier.scShadow(ScElevation.Raised, RoundedCornerShape(50))
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
