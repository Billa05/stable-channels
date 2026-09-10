package com.stablechannels.app.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stablechannels.app.models.TradeRecord
import com.stablechannels.app.ui.history.OrderDetailBottomSheet
import com.stablechannels.app.models.PaymentRecord
import com.stablechannels.app.ui.history.PaymentDetailBottomSheet
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.components.AmountStyle
import com.stablechannels.app.ui.components.AmountText
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCButtonTone
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.components.SheetScaffold
import com.stablechannels.app.ui.components.StatusCapsule
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.trade.BuyScreen
import com.stablechannels.app.ui.trade.SellScreen
import com.stablechannels.app.ui.transfer.ReceiveScreen
import com.stablechannels.app.ui.transfer.SendScreen
import com.stablechannels.app.util.Constants
import com.stablechannels.app.util.btcSpacedFormatted
import com.stablechannels.app.util.satsFormatted
import com.stablechannels.app.util.usdFormatted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(appState: AppState, modifier: Modifier = Modifier) {
    val totalSats by appState.totalBalanceSats.collectAsState()
    val lightningSats by appState.lightningBalanceSats.collectAsState()
    val btcPrice by appState.priceService.currentPrice.collectAsState()
    val sc by appState.stableChannel.collectAsState()
    val nativeSatsCached by appState.nativeSats.collectAsState()
    val lastRxTxid by appState.lastReceiveTxid.collectAsState()
    val lastCloseTxid by appState.lastCloseTxid.collectAsState()
    val statusMessage by appState.statusMessage.collectAsState()
    val onchainSats by appState.onchainBalanceSats.collectAsState()
    val hasReadyChannel by appState.hasReadyChannel.collectAsState()
    val spendableOnchainSats by appState.spendableOnchainSats.collectAsState()
    val isSyncing by appState.isSyncing.collectAsState()
    val isFlashing by appState.paymentFlash.collectAsState()
    val confirmationUpdateEpoch by appState.confirmationUpdateEpoch.collectAsState()
    val isChannelClosing by appState.isChannelClosingFlow.collectAsState()

    var showSend by remember { mutableStateOf(false) }
    var selectedTrade by remember { mutableStateOf<TradeRecord?>(null) }
    var selectedPayment by remember { mutableStateOf<PaymentRecord?>(null) }
    var showReceive by remember { mutableStateOf(false) }
    var showBuy by remember { mutableStateOf(false) }
    var showSell by remember { mutableStateOf(false) }
    var prefillTradeAmount by remember { mutableDoubleStateOf(0.0) }
    var showBTC by remember { mutableStateOf(false) }
    var latestPendingOnchainReceive by remember { mutableStateOf<PaymentRecord?>(null) }

    LaunchedEffect(isFlashing, confirmationUpdateEpoch, onchainSats, spendableOnchainSats) {
        latestPendingOnchainReceive = withContext(Dispatchers.IO) {
            appState.databaseService?.latestPendingOnchainReceive()
        }
    }

    // Auto-dismiss receive sheet when payment arrives
    val scrollState = rememberScrollState()
    LaunchedEffect(isFlashing) {
        if (isFlashing && showReceive) {
            showReceive = false
            scrollState.animateScrollTo(0)
        }
    }

    val context = LocalContext.current
    var notificationsEnabled by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PermissionChecker.PERMISSION_GRANTED
                } else true
                // Run blocking LDK calls off main thread
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    // Pick up backing increments committed by the background stability
                    // service while this process was cached, before any save can clobber them.
                    appState.onForegroundResume()
                    appState.refreshBalances()
                    appState.detectOnchainDeposit()
                    appState.ensureLSPConnected()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val totalUSD = (totalSats.toDouble() / Constants.SATS_IN_BTC) * btcPrice
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                val startTime = System.currentTimeMillis()
                appState.refreshBalances()
                appState.priceService.fetchPrice()
                appState.recordCurrentPrice()
                // Prevent spinner from flashing on instant fetches
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 500) kotlinx.coroutines.delay(500 - elapsed)
                isRefreshing = false
            }
        },
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                color = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Notification warning
            if (!notificationsEnabled) {
                Card(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Notifications Disabled",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                "Enable notifications for stability payments",
                                color = MaterialTheme.colorScheme.onError.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(Sp.sm))
            }

            Spacer(Modifier.height(Sp.xl))

            // Balance (tap to toggle USD/BTC)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { showBTC = !showBTC }
                    .paymentFlash(isFlashing, tint = semantic.usdStable)
            ) {
                Text(
                    text = "Total Balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Sp.xs))
                if (showBTC) {
                    RollingDigitText(
                        text = totalSats.btcSpacedFormatted() + " BTC"
                    )
                } else {
                    if (btcPrice > 0) {
                        RollingDigitText(
                            text = totalUSD.usdFormatted()
                        )
                    } else if (totalSats > 0) {
                        Text(
                            text = "Fetching price...",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        AmountText(text = "$0.00")
                    }
                }
                AmountText(
                    text = if (showBTC) {
                        if (btcPrice > 0) totalUSD.usdFormatted() else "—"
                    } else totalSats.btcSpacedFormatted() + " BTC",
                    style = AmountStyle.Small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(Sp.sm))

            // Balance bar
            if (lightningSats > 0) {
                BalanceBar(
                    stableUSD = sc.expectedUSD.amount,
                    nativeSats = nativeSatsCached,
                    totalSats = lightningSats,
                    btcPrice = btcPrice,
                    maxSellUSD = (appState.tradeService?.maxSellCents(sc, appState.priceService.accountingPrice.value) ?: 0L) / 100.0,
                    showBtcFormat = showBTC,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    onDragStarted = { appState.ensureLSPConnected() },
                    onTradeRequest = if (hasReadyChannel) { direction, amountUSD ->
                        prefillTradeAmount = amountUSD
                        if (direction == TradeDirection.BUY) showBuy = true else showSell = true
                    } else null
                )
                Spacer(Modifier.height(Sp.xs))
            }

            // Syncing indicator
            if (isSyncing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(Sp.sm))
                    Text(
                        "Syncing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(Sp.sm))
            }

            // On-chain section
            if (onchainSats > 0) {
                val onchainUSD = (onchainSats.toDouble() / Constants.SATS_IN_BTC) * btcPrice
                val isSweeping by appState.isSpliceInFlightFlow.collectAsState()

                SCCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Onchain Account", style = MaterialTheme.typography.labelMedium)
                            Text(
                                onchainUSD.usdFormatted(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val pendingReceiveTxid = latestPendingOnchainReceive?.txid
                        val hasPendingOnchainReceive = latestPendingOnchainReceive != null
                        if (isSweeping) {
                            // 1. Splice-in in progress
                            Spacer(Modifier.height(Sp.xs))
                            PendingRow("Move pending...", appState.spliceTxid, context)
                        } else if (isChannelClosing) {
                            // 2. Channel closing
                            Spacer(Modifier.height(Sp.xs))
                            PendingRow("Channel closing\u2026", lastCloseTxid, context)
                        } else if (hasReadyChannel && spendableOnchainSats > 0) {
                            // Has channel + confirmed funds — offer to sweep
                            Spacer(Modifier.height(Sp.xs))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Move to Lightning Account", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            appState.sweepToChannel()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text("Move", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (hasPendingOnchainReceive) {
                                Spacer(Modifier.height(6.dp))
                                PendingRow("Receiving onchain...", pendingReceiveTxid, context)
                            }
                        } else if (spendableOnchainSats == 0L) {
                            // 3. Unconfirmed deposit (with or without channel)
                            Spacer(Modifier.height(Sp.sm))
                            val pendingCloseId = appState.pendingClosePaymentId
                            // Prefer close txid if known — pendingClosePaymentId may already be
                            // cleared by detectOnchainDeposit even while funds are still unconfirmed
                            val effectiveTxid = if (lastCloseTxid != null) {
                                lastCloseTxid
                            } else {
                                pendingReceiveTxid ?: lastRxTxid
                            }
                            val isClosePending = pendingCloseId != null || lastCloseTxid != null
                            // Use a receive-specific label when we have an explicit pending onchain row.
                            val text = when {
                                isClosePending && effectiveTxid != null -> "Channel closing\u2026"
                                isClosePending -> "Channel closed"
                                hasPendingOnchainReceive -> "Receiving onchain..."
                                else -> "Deposit confirming..."
                            }
                            PendingRow(text, effectiveTxid, context)
                            if (!hasReadyChannel) {
                                Text("Receive a payment over Lightning to activate your account.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            // 4. No channel, confirmed deposit — just needs Lightning
                            Spacer(Modifier.height(Sp.xs))
                            Text("Receive a payment over Lightning to activate your account.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Price chart
            if (btcPrice > 0) {
                PriceChart(
                    appState = appState,
                    databaseService = appState.databaseService,
                    currentPrice = btcPrice
                )
            }

            // Hint text when no channel
            if (!hasReadyChannel) {
                Text("Receive BTC to get started",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Sp.xs))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SCPillButton("Send", onClick = { showSend = true }, modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.ArrowCircleUp)
                SCPillButton("Receive", onClick = { showReceive = true }, modifier = Modifier.weight(1f),
                    pulse = !hasReadyChannel, leadingIcon = Icons.Default.ArrowCircleDown)
            }

            Spacer(Modifier.height(Sp.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SCPillButton("USD → BTC", onClick = { showBuy = true }, modifier = Modifier.weight(1f),
                    tone = SCButtonTone.Btc, iconRotationDegrees = 45f, enabled = hasReadyChannel,
                    leadingIcon = Icons.Default.ArrowCircleUp)
                SCPillButton("BTC → USD", onClick = { showSell = true }, modifier = Modifier.weight(1f),
                    tone = SCButtonTone.Usd, iconRotationDegrees = -45f, enabled = hasReadyChannel,
                    leadingIcon = Icons.Default.ArrowCircleDown)
            }

            // Status capsule
            if (statusMessage.isNotEmpty()) {
                StatusCapsule(
                    message = statusMessage,
                    onClick = {
                        val msg = statusMessage.lowercase()
                        val isTrade = msg.contains("buy") || msg.contains("sell") || msg.contains("trade") || msg.contains("order")
                        val isPayment = msg.contains("payment") || msg.contains("swap") || msg.contains("channel") || msg.contains("moving")
                        
                        if (isTrade || isPayment) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                if (isTrade) {
                                    val trades = appState.databaseService?.getRecentTrades(1)
                                    if (!trades.isNullOrEmpty()) {
                                        selectedTrade = trades.first()
                                    }
                                } else {
                                    val payments = appState.databaseService?.getRecentPayments(1)
                                    if (!payments.isNullOrEmpty()) {
                                        selectedPayment = payments.first()
                                    }
                                }
                            }
                        } else {
                            appState.setStatus("")
                        }
                    }
                )
            }
            // Bottom padding for nav bar
            Spacer(Modifier.height(80.dp))
        }
    }

    // Bottom sheets
    if (showSend) {
        SheetScaffold(onDismiss = { showSend = false }) {
            SendScreen(appState) { showSend = false }
        }
    }
    if (showReceive) {
        SheetScaffold(onDismiss = { showReceive = false }) {
            ReceiveScreen(appState) { showReceive = false }
        }
    }
    if (showBuy) {
        SheetScaffold(onDismiss = { showBuy = false }) {
            BuyScreen(appState, prefillAmountUSD = prefillTradeAmount) { showBuy = false; prefillTradeAmount = 0.0 }
        }
    }
    if (showSell) {
        SheetScaffold(onDismiss = { showSell = false }) {
            SellScreen(appState, prefillAmountUSD = prefillTradeAmount) { showSell = false; prefillTradeAmount = 0.0 }
        }
    }

    selectedTrade?.let { trade ->
        OrderDetailBottomSheet(trade = trade, onDismiss = { selectedTrade = null })
    }
    selectedPayment?.let { payment ->
        PaymentDetailBottomSheet(
            payment = payment,
            currentPrice = btcPrice,
            onDismiss = { selectedPayment = null }
        )
    }
}

@Composable
private fun PendingRow(text: String, txid: String?, context: android.content.Context) {
    if (txid != null) {
        // Has txid — short text + button in one row (matches iOS layout)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("\u231B", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mempool.space/tx/${txid.substringBefore(":")}"))
                    context.startActivity(intent)
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("View on explorer", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        // No txid yet — show text with "pending confirmation" below
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("\u231B", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("pending confirmation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}
