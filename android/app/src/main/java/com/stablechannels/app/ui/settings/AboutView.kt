package com.stablechannels.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stablechannels.app.BuildConfig
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp

@Composable
fun AboutView() {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        // App info card
        SCCard(modifier = Modifier.fillMaxWidth()) {
            DetailRow(label = "Version", value = BuildConfig.VERSION_NAME)
            DetailRow(label = "Network", value = "Bitcoin", valueColor = semantic.btcText)
            DetailRow(label = "Custody", value = "Self-custodial", valueColor = semantic.success)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Stable Channels is a self-custodial Bitcoin wallet that maintains a stable USD value using Lightning Network channels. You control your private keys. No third party can access or freeze your funds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
