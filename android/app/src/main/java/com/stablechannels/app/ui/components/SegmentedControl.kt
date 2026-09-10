package com.stablechannels.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.stablechannels.app.ui.theme.ScElevation
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference
import com.stablechannels.app.ui.theme.scShadow

// iOS-style segmented control on tokens: full-width track, equal-weight segments
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(Sp.xs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .then(if (isSelected) Modifier.scShadow(ScElevation.Subtle, MaterialTheme.shapes.small) else Modifier),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = Sp.sm)) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentedControlPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        var selected by remember { mutableStateOf("Orders") }
        Column(modifier = Modifier.padding(Sp.lg)) {
            SegmentedControl(
                options = listOf("Orders", "Payments"),
                selected = selected,
                onSelect = { selected = it },
                label = { it },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentedControlPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        var selected by remember { mutableStateOf("Payments") }
        Column(modifier = Modifier.padding(Sp.lg)) {
            SegmentedControl(
                options = listOf("Orders", "Payments"),
                selected = selected,
                onSelect = { selected = it },
                label = { it },
            )
        }
    }
}
