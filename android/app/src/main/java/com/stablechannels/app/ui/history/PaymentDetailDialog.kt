package com.stablechannels.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stablechannels.app.models.PaymentRecord
import com.stablechannels.app.ui.components.DetailRow
import com.stablechannels.app.ui.components.DetailValueStyle
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.components.SheetScaffold
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.satsFormatted
import com.stablechannels.app.util.usdFormatted
import com.stablechannels.app.util.shortString
import com.stablechannels.app.util.Constants
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.stablechannels.app.AppState

private val TXID_REGEX = Regex("^[0-9a-fA-F]{64}$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailBottomSheet(payment: PaymentRecord, currentPrice: Double = 0.0, onDismiss: () -> Unit) {
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
                    text = "Payment Details",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(Modifier.height(Sp.lg))

            SCCard(modifier = Modifier.fillMaxWidth()) {
                DetailRow("Direction", if (payment.isIncoming) "Received" else "Sent")

                val typeLabel = when (payment.paymentType) {
                    "stability" -> "Stability"
                    "splice_in" -> "Splice In"
                    "splice_out" -> "Splice Out"
                    "onchain" -> "Onchain"
                    "channel_close" -> "Channel Close"
                    else -> "Lightning"
                }
                DetailRow("Type", typeLabel)

                val usdVal = payment.amountUSD ?: run {
                    val price = payment.btcPrice?.takeIf { it > 0.0 } ?: currentPrice.takeIf { it > 0.0 }
                    price?.let { (payment.amountSats.toDouble() / Constants.SATS_IN_BTC) * it }
                }
                val amountStr = usdVal?.usdFormatted() ?: "${payment.amountSats.satsFormatted()} sats"
                DetailRow("Amount", amountStr, valueStyle = DetailValueStyle.Amount)

                payment.btcPrice?.let {
                    DetailRow("BTC Price", it.usdFormatted(), valueStyle = DetailValueStyle.Amount)
                }

                if (payment.feeMsat > 0) {
                    DetailRow("Fee", "${payment.feeMsat / 1000} sats", valueStyle = DetailValueStyle.Amount)
                }

                DetailRow("Status", payment.detailStatusLabel())

                DetailRow("Date", payment.date.shortString())

                payment.paymentId?.let { pid ->
                    val displayPid = if (pid.length > 16) pid.take(8) + "..." + pid.takeLast(8) else pid
                    CopyableDetailRow("Payment ID", displayPid, pid)
                }

                payment.explorerTxid()?.let { txid ->
                    val displayTxid = if (txid.length > 16) txid.take(8) + "..." + txid.takeLast(8) else txid
                    CopyableDetailRow("TXID", displayTxid, txid)
                    val context = LocalContext.current
                    val onchainTypes = setOf("channel_close", "onchain", "splice_in", "splice_out")
                    if (payment.paymentType in onchainTypes) {
                        TextButton(
                            onClick = {
                                val cleanTxid = txid.substringBefore(":")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mempool.space/tx/$cleanTxid"))
                                context.startActivity(intent)
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("View on explorer", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                payment.address?.let { addr ->
                    val displayAddr = if (addr.length > 16) addr.take(8) + "..." + addr.takeLast(8) else addr
                    CopyableDetailRow("Address", displayAddr, addr)
                }

                if (payment.shouldShowConfirmationProgress() || payment.confirmations > 0) {
                    val required = payment.requiredConfirmationsForDisplay()
                    val confirmationsLabel = if (payment.confirmations >= required) {
                        "${payment.confirmations} (confirmed)"
                    } else {
                        "${payment.confirmations}/${required}"
                    }
                    DetailRow("Confirmations", confirmationsLabel)
                }
            }

            Spacer(Modifier.height(Sp.xl))

            SCPillButton(text = "Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth(0.6f))
        }
    }
}

private fun PaymentRecord.shouldShowConfirmationProgress(): Boolean {
    val onchainTypes = setOf("onchain", "channel_close", "splice_in", "splice_out")
    val hasProgressSignal = status == "pending" || confirmations > 0
    return paymentType in onchainTypes && hasProgressSignal
}

private fun PaymentRecord.detailStatusLabel(): String {
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

private fun PaymentRecord.requiredConfirmationsForDisplay(): Int {
    return AppState.requiredConfirmationsForType(paymentType)
}

private fun PaymentRecord.explorerTxid(): String? {
    val fromTxid = txid?.substringBefore(":")?.trim()
    if (!fromTxid.isNullOrEmpty() && TXID_REGEX.matches(fromTxid)) {
        return fromTxid
    }

    if (paymentType == "onchain") {
        val fromPaymentId = paymentId
            ?.removePrefix("onchain_receive_")
            ?.trim()
        if (!fromPaymentId.isNullOrEmpty() && TXID_REGEX.matches(fromPaymentId)) {
            return fromPaymentId
        }
    }

    return null
}
