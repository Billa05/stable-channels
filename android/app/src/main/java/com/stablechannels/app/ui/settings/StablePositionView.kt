package com.stablechannels.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.stablechannels.app.AppState
import com.stablechannels.app.services.StabilityService
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.satsFormatted

@Composable
fun StablePositionView(appState: AppState) {
    val sc by appState.stableChannel.collectAsState()
    val btcPrice by appState.priceService.currentPrice.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var copiedCounterparty by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current

    val stabilityResult = remember(sc, btcPrice) {
        StabilityService.checkStabilityAction(sc, btcPrice)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        if (sc.expectedUSD.amount > 0) {
            // Expected USD — prominent
            DetailRow(
                label = "Expected USD",
                value = sc.expectedUSD.formatted,
                valueColor = semantic.usdText
            )

            // Backing Sats
            DetailRow(
                label = "Backing Sats",
                value = sc.backingSats.satsFormatted()
            )

            // Native BTC
            DetailRow(
                label = "Native BTC",
                value = sc.nativeChannelBTC.formatted,
                valueColor = semantic.btcText
            )

            // Stability Status — colored
            val statusColor = when (stabilityResult.action.value) {
                "STABLE" -> semantic.usdText
                "PAY" -> semantic.warning
                "CHECK_ONLY" -> semantic.info
                "HIGH_RISK_NO_ACTION" -> semantic.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            DetailRow(
                label = "Status",
                value = stabilityResult.action.value,
                valueColor = statusColor
            )

            // Distance from Par — colored
            if (stabilityResult.percentFromPar > 0) {
                val parColor = if (stabilityResult.percentFromPar < 0.1) semantic.usdText else semantic.warning
                DetailRow(
                    label = "Distance from Par",
                    value = String.format("%.2f%%", stabilityResult.percentFromPar),
                    valueColor = parColor
                )
            }

            // Counterparty — tap to copy
            val cpk = sc.counterparty
            if (cpk.isNotEmpty()) {
                Spacer(Modifier.height(Sp.md))
                SCCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(cpk))
                            copiedCounterparty = true
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Counterparty",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Sp.xs))
                            Text(
                                text = "${cpk.take(8)}...${cpk.takeLast(8)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = if (copiedCounterparty) "Copied ✓" else "Tap to copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copiedCounterparty) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Text(
                text = "No stable position active",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Sp.sm))
            Text(
                text = "Trade BTC to USD to create a stable position.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
