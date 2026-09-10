package com.stablechannels.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.Constants

@Composable
fun NodeView(appState: AppState) {
    val clipboardManager = LocalClipboardManager.current
    val isRunning by appState.nodeService.isRunningFlow.collectAsState()
    var showNodeId by remember { mutableStateOf(false) }
    var copiedNodeId by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        // Status section
        SCCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Status", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isRunning) semantic.usdStable else semantic.error,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Text(
                        text = if (isRunning) "Running" else "Stopped",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isRunning) semantic.usdText else semantic.error
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Node ID section
        SCCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!showNodeId) {
                        showNodeId = true
                    } else if (appState.nodeService.nodeId.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(appState.nodeService.nodeId))
                        copiedNodeId = true
                    }
                }
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Node ID", style = MaterialTheme.typography.bodyLarge)
                    if (showNodeId) {
                        Text(
                            text = if (copiedNodeId) "Copied ✓" else "Tap to copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copiedNodeId) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Tap to reveal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showNodeId && appState.nodeService.nodeId.isNotEmpty()) {
                    Spacer(Modifier.height(Sp.sm))
                    Text(
                        text = appState.nodeService.nodeId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Connection info
        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Sp.md)
        )
        DetailRow(label = "Network", value = Constants.DEFAULT_NETWORK)
        DetailRow(
            label = "Explorer",
            value = Constants.PRIMARY_CHAIN_URL.removePrefix("https://").take(20)
        )
    }
}
