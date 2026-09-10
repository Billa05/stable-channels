package com.stablechannels.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.satsFormatted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ChannelView(appState: AppState) {
    val sc by appState.stableChannel.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCloseConfirm by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current

    val channels = appState.nodeService.channels
    val hasReadyChannel = channels.any { it.isChannelReady }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        if (channels.isNotEmpty() && !appState.isChannelClosing) {
            val ch = channels.first()

            // Status with colored dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (ch.isChannelReady) semantic.usdStable else semantic.warning,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Text(
                        text = if (ch.isChannelReady) "Ready" else "Pending",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (ch.isChannelReady) semantic.usdText else semantic.warning
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Capacity
            DetailRow("Capacity", ch.channelValueSats.toLong().satsFormatted())

            // Outbound
            DetailRow("Outbound", (ch.outboundCapacityMsat.toLong() / 1000).satsFormatted())

            // Inbound
            DetailRow("Inbound", (ch.inboundCapacityMsat.toLong() / 1000).satsFormatted())

            // Funding Tx
            appState.fundingTxid?.let { txid ->
                if (txid.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SCCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = "Funding Transaction",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Sp.xs))
                            Text(
                                text = "${txid.take(8)}...${txid.takeLast(8)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(Sp.sm))
                            TextButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mempool.space/tx/${txid.substringBefore(":")}"))
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("View on explorer ↗", color = semantic.info)
                            }
                        }
                    }
                }
            }

            if (hasReadyChannel) {
                Spacer(Modifier.height(Sp.xxl))
                OutlinedButton(
                    onClick = { showCloseConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Text("Close channel")
                }
            }
        } else if (appState.isChannelClosing) {
            // Channel is closing — show status
            Spacer(Modifier.height(Sp.xxl))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = semantic.warning
                )
                Spacer(Modifier.height(Sp.lg))
                Text(
                    text = "Closing channel...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(Sp.sm))
                Text(
                    text = "Funds will be swept to your onchain wallet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = "No channel open yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Sp.sm))
            Text(
                text = "Receive bitcoin over Lightning to open your first channel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            title = { Text("Close channel") },
            text = { Text("This will cooperatively close the channel and return your funds to your onchain wallet after confirmation.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    appState.isChannelClosing = true
                    appState.setStatus("Closing channel...")
                    appState.prepareChannelCloseTracking(sc.userChannelId)
                    scope.launch(Dispatchers.IO) {
                        try {
                            appState.nodeService.closeChannel(sc.userChannelId, sc.counterparty)
                            appState.refreshBalances()
                        } catch (e: Exception) {
                            appState.setStatus("Close failed: ${e.message}")
                            appState.isChannelClosing = false
                        }
                    }
                }) { Text("Close channel", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
