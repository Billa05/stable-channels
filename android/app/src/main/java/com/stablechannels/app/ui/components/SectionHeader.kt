package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference

// 19/24 semibold section header (spec §3.2)
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = Sp.sm),
    )
}

@Preview(showBackground = true)
@Composable
private fun SectionHeaderPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(modifier = Modifier.padding(Sp.lg)) {
            SectionHeader("Balances")
            Text("Across all channels", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionHeaderPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(modifier = Modifier.padding(Sp.lg)) {
            SectionHeader("Balances")
            Text("Across all channels", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
