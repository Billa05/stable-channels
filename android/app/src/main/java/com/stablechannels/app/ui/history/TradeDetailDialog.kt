package com.stablechannels.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.stablechannels.app.models.TradeRecord
import com.stablechannels.app.ui.components.AmountStyle
import com.stablechannels.app.ui.components.AmountText
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.components.SheetScaffold
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.usdFormatted
import com.stablechannels.app.util.shortString
import com.stablechannels.app.util.btcSpacedFormatted
import com.stablechannels.app.util.Constants
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailBottomSheet(trade: TradeRecord, onDismiss: () -> Unit) {
    val semantic = LocalSemanticColors.current
    SheetScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Sp.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Toolbar (Cancel button, centered title)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Order Details",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(Modifier.height(Sp.lg))

            SCCard(modifier = Modifier.fillMaxWidth()) {
                DetailRow("Action", if (trade.action == "buy") "USD → BTC" else "BTC → USD")
                // Money values carry their side's token color; DetailRow has no value-color slot, so they ride the trailing slot
                DetailRow("Amount", "", trailing = {
                    AmountText(text = trade.amountUSD.usdFormatted(), style = AmountStyle.Small, color = semantic.usdText)
                })
                DetailRow("BTC Amount", "", trailing = {
                    AmountText(text = Math.round(trade.amountBTC * Constants.SATS_IN_BTC).btcSpacedFormatted(), style = AmountStyle.Small, color = semantic.btcText)
                })
                DetailRow("BTC Price", "", trailing = {
                    AmountText(text = trade.btcPrice.usdFormatted(), style = AmountStyle.Small, color = semantic.usdText)
                })
                DetailRow("Fee", "", trailing = {
                    AmountText(text = trade.feeUSD.usdFormatted(), style = AmountStyle.Small, color = semantic.usdText)
                })
                DetailRow("Status", trade.status.replaceFirstChar { it.uppercase() })
                DetailRow("Date", trade.date.shortString())
                trade.paymentId?.let { pid ->
                    val displayPid = if (pid.length > 16) pid.take(8) + "..." + pid.takeLast(8) else pid
                    CopyableDetailRow("Payment ID", displayPid, pid)
                }
            }

            Spacer(Modifier.height(Sp.xl))

            SCPillButton(text = "Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth(0.6f))
        }
    }
}

// Component DetailRow's copied check latches, so the 2s revert icon lives here on the trailing slot
@Composable
fun CopyableDetailRow(label: String, value: String, fullValue: String) {
    val semantic = LocalSemanticColors.current
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    DetailRow(
        label = label,
        value = value,
        trailing = {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(fullValue))
                    copied = true
                },
                modifier = Modifier
                    .padding(start = Sp.xs)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp),
                    tint = if (copied) semantic.usdText else MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}
