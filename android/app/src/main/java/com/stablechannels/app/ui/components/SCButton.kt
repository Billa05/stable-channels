package com.stablechannels.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference
import com.stablechannels.app.ui.theme.scInsetHighlight
import com.stablechannels.app.ui.theme.scShadow
import com.stablechannels.app.ui.theme.ScElevation

enum class SCButtonTone { Neutral, Usd, Btc }

// Pulse halo overlay derived from the button's tone color (state signal, not decoration)
@Composable
private fun BoxScope.PulseHalo(pulse: Boolean, color: Color, shape: androidx.compose.ui.graphics.Shape) {
    if (pulse) {
        key(pulse) {
            val transition = rememberInfiniteTransition(label = "btnPulse")
            val haloAlpha by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(animation = tween(800, easing = EaseInOut), repeatMode = RepeatMode.Reverse),
                label = "btnAlpha"
            )
            Box(Modifier.matchParentSize().clip(shape).background(color.copy(alpha = haloAlpha)))
        }
    }
}

// Circular inset-highlight button (spec §4) — 40-46dp, layered shadow, no flat fill
@Composable
fun SCCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SCButtonTone = SCButtonTone.Neutral,
    size: Dp = 44.dp,
    pulse: Boolean = false,
    iconRotationDegrees: Float = 0f,
    label: String? = null,
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val iconColor = when (tone) {
        SCButtonTone.Neutral -> scheme.onSurface
        SCButtonTone.Usd -> semantic.usdText
        SCButtonTone.Btc -> semantic.btcText
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "scCirclePress")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = scheme.surface,
                interactionSource = interaction,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(size)
                    .scShadow(ScElevation.Raised, CircleShape, insetHighlight = true),
            ) {
                Icon(icon, contentDescription, tint = iconColor,
                    modifier = Modifier.padding(Sp.md).rotate(iconRotationDegrees))
            }
            PulseHalo(pulse, iconColor, CircleShape)
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Sp.xs))
        }
    }
}

// Pill button — filled only for money tones (usdContainer/btcContainer), ink for neutral
@Composable
fun SCPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SCButtonTone = SCButtonTone.Neutral,
    enabled: Boolean = true,
    pulse: Boolean = false,
    busy: Boolean = false,
    iconRotationDegrees: Float = 0f,
    leadingIcon: ImageVector? = null,
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        SCButtonTone.Neutral -> scheme.primary to scheme.onPrimary
        SCButtonTone.Usd -> semantic.usdContainer to semantic.onUsd
        SCButtonTone.Btc -> semantic.btcContainer to semantic.onBtc
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A busy button isn't pressable feedback — the dip would imply interactivity
    val scale by animateFloatAsState(if (pressed && !busy) 0.97f else 1f, label = "scPillPress")
    Box(modifier = modifier) {
        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            contentPadding = PaddingValues(horizontal = Sp.xl, vertical = Sp.md),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .scShadow(ScElevation.Subtle, RoundedCornerShape(50), insetHighlight = true),
        ) {
            if (busy) {
                // Spinner replaces text/icon; LocalContentColor = the exact color the text would render in
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, modifier = Modifier.padding(end = Sp.sm).rotate(iconRotationDegrees))
                }
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
        PulseHalo(pulse, content, RoundedCornerShape(50))
    }
}

@Preview(showBackground = true)
@Composable
private fun SCButtonPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Sp.lg),
            modifier = Modifier.padding(Sp.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
                SCPillButton("Receive", onClick = {})
                SCPillButton("Top up", onClick = {}, tone = SCButtonTone.Usd, leadingIcon = Icons.Rounded.ArrowDownward)
                SCPillButton("Move", onClick = {}, tone = SCButtonTone.Btc, leadingIcon = Icons.Rounded.Bolt)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
                SCCircleButton(Icons.AutoMirrored.Rounded.Send, "Send", onClick = {}, label = "Send")
                SCCircleButton(Icons.Rounded.ArrowDownward, "Receive dollars", onClick = {}, tone = SCButtonTone.Usd, label = "Receive")
                SCCircleButton(Icons.Rounded.Bolt, "Move to Bitcoin", onClick = {}, tone = SCButtonTone.Btc, label = "Move")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SCButtonPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Sp.lg),
            modifier = Modifier.padding(Sp.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
                SCPillButton("Receive", onClick = {})
                SCPillButton("Top up", onClick = {}, tone = SCButtonTone.Usd, leadingIcon = Icons.Rounded.ArrowDownward)
                SCPillButton("Move", onClick = {}, tone = SCButtonTone.Btc, leadingIcon = Icons.Rounded.Bolt)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
                SCCircleButton(Icons.AutoMirrored.Rounded.Send, "Send", onClick = {}, label = "Send")
                SCCircleButton(Icons.Rounded.ArrowDownward, "Receive dollars", onClick = {}, tone = SCButtonTone.Usd, label = "Receive")
                SCCircleButton(Icons.Rounded.Bolt, "Move to Bitcoin", onClick = {}, tone = SCButtonTone.Btc, label = "Move")
            }
        }
    }
}
