package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.ScElevation
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference
import com.stablechannels.app.ui.theme.scShadow
import androidx.compose.foundation.shape.RoundedCornerShape

enum class CardDepth { Subtle, Raised }

// Borderless layered-shadow card (spec §4)
@Composable
fun SCCard(
    modifier: Modifier = Modifier,
    depth: CardDepth = CardDepth.Subtle,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.scShadow(if (depth == CardDepth.Raised) ScElevation.Raised else ScElevation.Subtle, shape),
    ) {
        Column(modifier = Modifier.padding(Sp.lg), content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun SCCardPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.md), modifier = Modifier.padding(Sp.lg)) {
            SCCard { Text("Pending settlement", style = MaterialTheme.typography.bodyLarge) }
            SCCard(depth = CardDepth.Raised) {
                Text("Channel capacity", style = MaterialTheme.typography.titleMedium)
                Text("250,000 sats", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SCCardPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.md), modifier = Modifier.padding(Sp.lg)) {
            SCCard { Text("Pending settlement", style = MaterialTheme.typography.bodyLarge) }
            SCCard(depth = CardDepth.Raised) {
                Text("Channel capacity", style = MaterialTheme.typography.titleMedium)
                Text("250,000 sats", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
