package com.stablechannels.app.ui.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.components.AmountStyle
import com.stablechannels.app.ui.components.AmountText
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.home.FundWalletScreen
import com.stablechannels.app.ui.home.generateQRCode
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.Constants
import com.stablechannels.app.util.btcSpacedFormatted
import com.stablechannels.app.util.usdFormatted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(appState: AppState, onDismiss: () -> Unit) {
    var amountUSD by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    var invoiceAmountSats by remember { mutableStateOf<Long?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var isCopied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showOnChain by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current
    val clipboardManager = LocalClipboardManager.current
    val btcPrice by appState.priceService.currentPrice.collectAsState()

    val hasChannel = appState.nodeService.channels.any { it.isChannelReady }

    val enteredUSD = amountUSD.toDoubleOrNull() ?: 0.0
    val enteredSats = if (btcPrice > 0 && enteredUSD > 0) {
        (enteredUSD / btcPrice * Constants.SATS_IN_BTC).toLong()
    } else 0L

    if (showOnChain) {
        FundWalletScreen(appState, onBack = { showOnChain = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Sp.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Toolbar (Done button, centered title, top-right Onchain button)
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
                Text("Done", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "Receive",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = { showOnChain = true },
                modifier = Modifier.align(Alignment.CenterEnd),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Onchain", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(Sp.lg))

        if (invoice != null) {
            val inv = invoice!!

            // Amount summary
            if (invoiceAmountSats != null && invoiceAmountSats!! > 0) {
                if (btcPrice > 0) {
                    val usd = invoiceAmountSats!!.toDouble() / Constants.SATS_IN_BTC * btcPrice
                    AmountText(text = usd.usdFormatted())
                }
                AmountText(
                    text = invoiceAmountSats!!.btcSpacedFormatted(),
                    style = AmountStyle.Small,
                    color = semantic.btcText
                )
                Spacer(Modifier.height(Sp.md))
            }

            val qrBitmap = remember(inv) { generateQRCode(inv.uppercase()) }
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(200.dp)
                )
            }
            Spacer(Modifier.height(Sp.md))
            Text(
                text = inv.take(30) + "..." + inv.takeLast(10),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Sp.md))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString(inv))
                    isCopied = true
                }) { Text(if (isCopied) "Copied!" else "Copy") }
                OutlinedButton(onClick = {
                    invoice = null
                    invoiceAmountSats = null
                    isCopied = false
                }) { Text("New Invoice") }
            }
        } else {
            if (!hasChannel) {
                SCCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Receive a payment over Lightning to activate your account.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(Sp.md))
                AmountText(
                    text = "$${Constants.MAX_CHANNEL_USD.toInt()} Maximum",
                    style = AmountStyle.Small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Sp.lg))
            }

            Text("Amount (USD)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Sp.md))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("$", style = ScTextStyles.Amount)
                Spacer(Modifier.width(2.dp))
                BasicTextField(
                    value = amountUSD,
                    onValueChange = { amountUSD = it.filter { c -> c.isDigit() || c == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = ScTextStyles.Amount.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.width(IntrinsicSize.Min),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (amountUSD.isEmpty()) {
                                Text(
                                    text = "0.00",
                                    style = ScTextStyles.Amount.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        textAlign = TextAlign.Start
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            if (enteredSats > 0) {
                Spacer(Modifier.height(Sp.xs))
                AmountText(
                    text = "${enteredSats.btcSpacedFormatted()}",
                    style = AmountStyle.Small,
                    color = semantic.btcText
                )
            }

            if (!hasChannel && enteredUSD > Constants.MAX_CHANNEL_USD) {
                Spacer(Modifier.height(Sp.xs))
                Text(
                    "Amount exceeds $${Constants.MAX_CHANNEL_USD.toInt()} channel limit",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Spacer(Modifier.height(Sp.sm))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(Sp.lg))
            SCPillButton(
                text = "Generate Invoice",
                onClick = {
                    isGenerating = true
                    error = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sats = enteredSats
                            val inv = if (!hasChannel && sats > 0) {
                                appState.nodeService.receiveViaJitChannel(sats * 1000, "Stable Channels")
                            } else if (sats > 0) {
                                appState.nodeService.receivePayment(sats * 1000, "Stable Channels")
                            } else {
                                appState.nodeService.receiveVariablePayment("Stable Channels")
                            }
                            invoiceAmountSats = if (sats > 0) sats else null
                            invoice = inv.toString()
                            appState.isWaitingForPayment = true
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to generate invoice"
                        }
                        isGenerating = false
                    }
                },
                enabled = !isGenerating && enteredSats > 0 && (hasChannel || enteredUSD <= Constants.MAX_CHANNEL_USD),
                busy = isGenerating,
                modifier = Modifier.fillMaxWidth()
            )

            if (hasChannel) {
                Spacer(Modifier.height(Sp.sm))
                OutlinedButton(
                    onClick = {
                        isGenerating = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val inv = appState.nodeService.receiveVariablePayment("Stable Channels")
                                invoiceAmountSats = null
                                invoice = inv.toString()
                                appState.isWaitingForPayment = true
                            } catch (e: Exception) {
                                error = e.message ?: "Failed to generate invoice"
                            }
                            isGenerating = false
                        }
                    },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Any Amount") }
            }
        }
    }
}
