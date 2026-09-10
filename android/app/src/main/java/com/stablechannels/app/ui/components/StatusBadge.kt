package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference

enum class StatusKind { Positive, Negative, Neutral, Pending }

// Pill status badge (unifies HistoryScreen's StatusBadge + StatusCapsule usage)
@Composable
fun StatusBadge(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val fg = when (kind) {
        StatusKind.Positive -> semantic.usdText
        StatusKind.Negative -> semantic.error
        StatusKind.Neutral -> scheme.onSurfaceVariant
        StatusKind.Pending -> semantic.warning
    }
    Surface(shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Transparent, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg,
            modifier = Modifier.padding(horizontal = Sp.sm, vertical = Sp.xs))
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusBadgePreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs), modifier = Modifier.padding(Sp.lg)) {
            StatusBadge(text = "Completed", kind = StatusKind.Positive)
            StatusBadge(text = "Failed", kind = StatusKind.Negative)
            StatusBadge(text = "Open", kind = StatusKind.Neutral)
            StatusBadge(text = "Pending", kind = StatusKind.Pending)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusBadgePreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs), modifier = Modifier.padding(Sp.lg)) {
            StatusBadge(text = "Completed", kind = StatusKind.Positive)
            StatusBadge(text = "Failed", kind = StatusKind.Negative)
            StatusBadge(text = "Open", kind = StatusKind.Neutral)
            StatusBadge(text = "Pending", kind = StatusKind.Pending)
        }
    }
}
