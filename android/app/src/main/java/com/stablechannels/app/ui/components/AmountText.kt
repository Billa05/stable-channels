package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.StableChannelsTheme
import com.stablechannels.app.ui.theme.ThemePreference

enum class AmountStyle { Hero, Small }

// Tabular-figures amount text (spec §3.2)
@Composable
fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    style: AmountStyle = AmountStyle.Hero,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = if (style == AmountStyle.Hero) ScTextStyles.Amount else ScTextStyles.AmountSmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun AmountTextPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.sm), modifier = Modifier.padding(Sp.lg)) {
            AmountText("$1,234.56")
            AmountText("0.00042100 BTC", style = AmountStyle.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AmountTextPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.sm), modifier = Modifier.padding(Sp.lg)) {
            AmountText("$1,234.56")
            AmountText("0.00042100 BTC", style = AmountStyle.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
