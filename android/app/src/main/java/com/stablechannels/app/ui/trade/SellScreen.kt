package com.stablechannels.app.ui.trade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.models.PendingTradePayment
import com.stablechannels.app.services.StabilizationPolicy
import com.stablechannels.app.ui.components.AmountStyle
import com.stablechannels.app.ui.components.AmountText
import com.stablechannels.app.ui.components.CurveProgressIndicator
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.DetailValueStyle
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCButtonTone
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.Constants
import com.stablechannels.app.util.usdFormatted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SellScreen(appState: AppState, prefillAmountUSD: Double = 0.0, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(TradeStep.AMOUNT) }
    var amountText by remember { mutableStateOf(if (prefillAmountUSD > 0) String.format(Locale.US, "%.2f", prefillAmountUSD) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var pendingPaymentId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current

    val sc by appState.stableChannel.collectAsState()
    // Trading fails closed while the displayed cache is stale or quarantined.
    val btcPrice by appState.priceService.accountingPrice.collectAsState()
    val lightningSats by appState.lightningBalanceSats.collectAsState()
    val maxSellUSD = remember(sc, btcPrice, lightningSats, appState.tradeService) {
        (appState.tradeService?.maxSellCents(sc, btcPrice) ?: 0L) / 100.0
    }
    val amountUSD = amountText.toDoubleOrNull() ?: 0.0
    val feeUSD = amountUSD * Constants.STABLE_CHANNEL_TRADE_FEE_RATE
    val feeLabel = String.format(Locale.US, "Fee (%.0f%%)", Constants.STABLE_CHANNEL_TRADE_FEE_RATE * 100)
    val btcAmount = if (btcPrice > 0) (amountUSD - feeUSD) / btcPrice else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Sp.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Toolbar header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (step != TradeStep.DONE) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = if (step == TradeStep.CONFIRM) "Review BTC -> USD" else "BTC → USD",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Spacer(Modifier.height(Sp.lg))

        when (step) {
            TradeStep.AMOUNT -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("How much BTC to convert to USD?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = {
                            amountText = String.format(Locale.US, "%.2f", maxSellUSD)
                            error = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Max", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(Sp.md))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("$", style = ScTextStyles.Amount)
                    Spacer(Modifier.width(2.dp))
                    BasicTextField(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter { c -> c.isDigit() || c == '.' }
                            error = null
                        },
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
                                if (amountText.isEmpty()) {
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

                if (amountUSD > 0 && btcPrice > 0) {
                    Spacer(Modifier.height(Sp.xs))
                    AmountText(
                        text = "~ ${String.format(Locale.US, "%.8f", btcAmount)}",
                        style = AmountStyle.Small,
                        color = semantic.btcText
                    )
                }

                Spacer(Modifier.height(Sp.sm))
                AmountText(
                    text = "Maximum additional trade: ${maxSellUSD.usdFormatted()}",
                    style = AmountStyle.Small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                error?.let {
                    Spacer(Modifier.height(Sp.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (btcPrice <= 0.0) {
                    Spacer(Modifier.height(Sp.sm))
                    Text(
                        "A fresh BTC/USD consensus is required before trading",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(Sp.lg))
                SCPillButton(
                    text = "Continue",
                    onClick = {
                        if (!amountUSD.isFinite() || amountUSD <= 0) {
                            error = "Enter a positive amount"
                        } else if (amountUSD > maxSellUSD) {
                            error = StabilizationPolicy.limitExceededMessage((maxSellUSD * 100 + 1e-7).toLong())
                        } else {
                            error = null
                            step = TradeStep.CONFIRM
                        }
                    },
                    enabled = btcPrice > 0.0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            TradeStep.CONFIRM -> {
                SCCard(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Amount", amountUSD.usdFormatted(), valueStyle = DetailValueStyle.Amount)
                    DetailRow(feeLabel, feeUSD.usdFormatted(), valueStyle = DetailValueStyle.Amount)
                    DetailRow("BTC Price", btcPrice.usdFormatted(), valueStyle = DetailValueStyle.Amount)
                    DetailRow("You receive", (amountUSD - feeUSD).usdFormatted(), valueStyle = DetailValueStyle.Amount)
                }

                error?.let {
                    Spacer(Modifier.height(Sp.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(Sp.xl))
                SCPillButton(
                    text = "Confirm Order",
                    onClick = {
                        isExecuting = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                appState.ensureLSPConnected()
                                val tradePrice = appState.priceService.currentAccountingPrice()
                                if (tradePrice <= 0.0) {
                                    throw Exception("A fresh BTC/USD consensus is required before trading")
                                }
                                val service = appState.tradeService ?: throw Exception("Trade service unavailable")
                                val liveSc = appState.stableChannel.value
                                val result = service.executeSell(liveSc, amountUSD, feeUSD, tradePrice)
                                val awaitingResult = appState.addPendingTradePayment(result.paymentId, PendingTradePayment(
                                    newExpectedUSD = result.newExpectedUSD,
                                    price = tradePrice,
                                    tradeDbId = result.tradeDbId,
                                    action = "sell"
                                ))
                                pendingPaymentId = result.paymentId
                                if (awaitingResult) {
                                    appState.setStatus(String.format(Locale.US, "Order pending (fee: $%.2f)", feeUSD))
                                }
                                step = TradeStep.DONE
                            } catch (e: Exception) {
                                error = e.message ?: "Trade failed"
                            }
                            isExecuting = false
                        }
                    },
                    tone = SCButtonTone.Usd,
                    enabled = !isExecuting && btcPrice > 0.0,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Sp.sm))
                TextButton(onClick = { step = TradeStep.AMOUNT }) { Text("Back") }
            }

            TradeStep.DONE -> {
                // Only a signed, correlated acceptance confirms the order and only a signed
                // rejection fails it. Absence from the pending map proves nothing — a
                // rejection also clears it, and the old absence heuristic showed
                // "Order Confirmed" for rejected trades (caught by e2e flow 13).
                val tradeOutcomes by appState.tradeOutcomes.collectAsState()
                val outcome = pendingPaymentId?.let { tradeOutcomes[it] }
                // Background services commit results straight to SQLite without touching
                // the in-memory outcome map, so poll the database while the result is
                // unknown — otherwise a trade resolved while backgrounded stays "Pending".
                LaunchedEffect(pendingPaymentId) {
                    val pid = pendingPaymentId ?: return@LaunchedEffect
                    while (!appState.tradeOutcomes.value.containsKey(pid)) {
                        appState.refreshTradeOutcome(pid)
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                val isConfirmed = outcome?.accepted == true
                val isRejected = outcome?.accepted == false

                if (isRejected) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = "Rejected",
                        tint = semantic.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(Sp.sm))
                    Text("Order Rejected", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Sp.sm))
                    Text(
                        outcome?.message ?: "The provider could not process the trade.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (isConfirmed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Confirmed",
                        tint = semantic.success,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(Sp.sm))
                    Text("Order Confirmed", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Sp.sm))
                    Text(
                        "Your order has been confirmed.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    CurveProgressIndicator(size = 56.dp)
                    Spacer(Modifier.height(Sp.md))
                    Text("Order Pending", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Sp.sm))
                    Text(
                        "Your order is being processed. Balance will update when the payment confirms.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(Sp.lg))
                if (isConfirmed || isRejected) {
                    SCPillButton(text = "Done", onClick = onDismiss)
                }
            }
        }
    }
}
