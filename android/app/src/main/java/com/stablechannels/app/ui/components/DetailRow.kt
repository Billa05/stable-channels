package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference

enum class DetailValueStyle { Body, Mono, Amount }

// Single labeled value row replacing the six per-screen duplicates
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: DetailValueStyle = DetailValueStyle.Body,
    onCopy: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    valueColor: Color = Color.Unspecified,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Sp.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = when (valueStyle) {
                DetailValueStyle.Body -> MaterialTheme.typography.bodyLarge
                DetailValueStyle.Mono -> MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                DetailValueStyle.Amount -> ScTextStyles.AmountSmall
            },
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
        )
        if (onCopy != null) {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(value)); copied = true; onCopy()
            }, modifier = Modifier.padding(start = Sp.xs)) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}

@Preview(showBackground = true)
@Composable
private fun DetailRowPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.sm), modifier = Modifier.padding(Sp.lg)) {
            DetailRow(label = "Status", value = "Open")
            DetailRow(label = "Node ID", value = "03ab9f...e2c1", valueStyle = DetailValueStyle.Mono)
            DetailRow(label = "Amount", value = "$1,234.56", valueStyle = DetailValueStyle.Amount)
            DetailRow(label = "Payment ID", value = "a1b2c3d4e5", onCopy = { })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailRowPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.sm), modifier = Modifier.padding(Sp.lg)) {
            DetailRow(label = "Status", value = "Open")
            DetailRow(label = "Node ID", value = "03ab9f...e2c1", valueStyle = DetailValueStyle.Mono)
            DetailRow(label = "Amount", value = "$1,234.56", valueStyle = DetailValueStyle.Amount)
            DetailRow(label = "Payment ID", value = "a1b2c3d4e5", onCopy = { })
        }
    }
}
