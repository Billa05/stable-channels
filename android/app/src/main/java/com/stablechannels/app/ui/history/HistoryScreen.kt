package com.stablechannels.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.models.PaymentRecord
import com.stablechannels.app.models.TradeRecord
import com.stablechannels.app.ui.components.SegmentedControl
import com.stablechannels.app.ui.components.StatusBadge
import com.stablechannels.app.ui.components.StatusKind
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.Constants
import com.stablechannels.app.util.relativeString
import com.stablechannels.app.util.satsFormatted
import com.stablechannels.app.util.usdFormatted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(appState: AppState, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableStateOf("Orders") }
    var trades by remember { mutableStateOf<List<TradeRecord>>(emptyList()) }
    var payments by remember { mutableStateOf<List<PaymentRecord>>(emptyList()) }
    var selectedTrade by remember { mutableStateOf<TradeRecord?>(null) }
    var selectedPayment by remember { mutableStateOf<PaymentRecord?>(null) }
    val currentPrice by appState.priceService.currentPrice.collectAsState()
    val confirmationUpdateEpoch by appState.confirmationUpdateEpoch.collectAsState()
    val isFlashing by appState.paymentFlash.collectAsState()

    fun loadHistory() {
        trades = appState.databaseService?.getRecentTrades() ?: emptyList()
        payments = appState.databaseService?.getRecentPayments() ?: emptyList()
        selectedTrade = selectedTrade?.let { selected ->
            trades.firstOrNull { it.id == selected.id } ?: selected
        }
        selectedPayment = selectedPayment?.let { selected ->
            payments.firstOrNull { it.id == selected.id } ?: selected
        }
    }

    LaunchedEffect(Unit) {
        loadHistory()
        appState.triggerConfirmationRefresh()
    }
    LaunchedEffect(confirmationUpdateEpoch) { loadHistory() }
    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            loadHistory()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Sp.lg)
    ) {
        // Title — same position as Settings and Home
        Text(
            text = "History",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Sp.md))

        SegmentedControl(
            options = listOf("Orders", "Payments"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            label = { it }
        )

            Spacer(Modifier.height(Sp.lg))

            if (selectedTab == "Orders" && trades.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.SwapHoriz,
                    title = "No Orders",
                    description = "Convert BTC to see orders here."
                )
            } else if (selectedTab == "Payments" && payments.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.ElectricBolt,
                    title = "No Payments",
                    description = "Send or receive payments to see history here."
                )
            } else {
                LazyColumn {
                    if (selectedTab == "Orders") {
                        itemsIndexed(trades) { index, trade ->
                            TradeRow(trade) { selectedTrade = trade }
                            if (index < trades.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    } else {
                        itemsIndexed(payments) { index, payment ->
                            PaymentRow(payment, currentPrice) { selectedPayment = payment }
                            if (index < payments.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                    // Bottom padding for nav bar
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

    // Detail bottom sheets
    selectedTrade?.let { trade ->
        OrderDetailBottomSheet(trade) { selectedTrade = null }
    }
    selectedPayment?.let { payment ->
        PaymentDetailBottomSheet(payment, currentPrice) { selectedPayment = null }
    }
}

@Composable
private fun TradeRow(trade: TradeRecord, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val isBuy = trade.action == "buy"
    val icon = if (isBuy) Icons.Default.TrendingUp else Icons.Default.TrendingDown
    // Buy gains bitcoin (BTC color), sell gains dollars (USD color)
    val tileColor = if (isBuy) semantic.btcNative else semantic.usdStable
    val iconColor = if (isBuy) semantic.btcText else semantic.usdText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with colored background
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tileColor.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.width(Sp.md))

        // Title + time
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isBuy) "USD → BTC" else "BTC → USD",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = trade.date.relativeString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Amount + status
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = trade.amountUSD.usdFormatted(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            StatusBadge(trade.status, statusKindFor(trade.status))
        }
    }
}

@Composable
private fun PaymentRow(payment: PaymentRecord, currentPrice: Double, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val isIncoming = payment.isIncoming
    val icon = if (isIncoming) Icons.Default.ArrowCircleDown else Icons.Default.ArrowCircleUp
    // Incoming value carries the USD color; outgoing stays ink (send is not a money-direction action)
    val tileColor = if (isIncoming) semantic.usdStable else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isIncoming) semantic.usdText else MaterialTheme.colorScheme.onSurface
    val typeLabel = when (payment.paymentType) {
        "stability" -> "Settlement"
        "lightning" -> "Lightning"
        "splice_in" -> "Splice In"
        "splice_out" -> "Splice Out"
        "onchain" -> "Onchain"
        "channel_close" -> "Channel Close"
        "bolt12" -> "Bolt12"
        else -> payment.paymentType
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with colored background
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tileColor.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.width(Sp.md))

        // Title + type + time
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isIncoming) "Received" else "Sent",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$typeLabel · ${payment.date.relativeString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Amount + status
        Column(horizontalAlignment = Alignment.End) {
            val displayUsd = payment.amountUSD ?: run {
                val price = payment.btcPrice?.takeIf { it > 0.0 } ?: currentPrice.takeIf { it > 0.0 }
                price?.let { (payment.amountSats.toDouble() / Constants.SATS_IN_BTC) * it }
            }
            val amountText = displayUsd?.usdFormatted() ?: "${payment.amountSats.satsFormatted()} sats"
            Text(
                text = (if (isIncoming) "+" else "-") + amountText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isIncoming) semantic.usdText else MaterialTheme.colorScheme.onSurface
            )
            val statusLabel = payment.historyStatusLabel()
            StatusBadge(statusLabel, payment.historyStatusKind())
        }
    }
}

private fun statusKindFor(status: String): StatusKind = when (status.lowercase()) {
    "completed", "succeeded", "settled" -> StatusKind.Positive
    "failed" -> StatusKind.Negative
    "pending", "in-flight" -> StatusKind.Pending
    else -> StatusKind.Neutral
}

private fun PaymentRecord.shouldShowConfirmationProgress(): Boolean {
    val onchainTypes = setOf("onchain", "channel_close", "splice_in", "splice_out")
    val hasProgressSignal = status == "pending" || confirmations > 0
    return paymentType in onchainTypes && hasProgressSignal
}

private fun PaymentRecord.historyStatusLabel(): String {
    if (!shouldShowConfirmationProgress()) {
        return status.replaceFirstChar { it.uppercase() }
    }
    val required = requiredConfirmationsForDisplay()
    return if (confirmations >= required) {
        "Confirmed"
    } else {
        "${confirmations}/${required} confirmed"
    }
}

private fun PaymentRecord.historyStatusKind(): StatusKind {
    if (!shouldShowConfirmationProgress()) {
        return statusKindFor(status)
    }
    return if (confirmations >= requiredConfirmationsForDisplay()) StatusKind.Positive else StatusKind.Pending
}

private fun PaymentRecord.requiredConfirmationsForDisplay(): Int {
    return AppState.requiredConfirmationsForType(paymentType)
}

@Composable
private fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Sp.lg))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(Sp.xs))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
