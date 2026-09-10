package com.stablechannels.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.stablechannels.app.push.FCMService
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import com.stablechannels.app.util.relativeString

@Composable
fun PushConnectivityView() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current

    val prefs = FCMService.getPrefs(context)
    var fcmToken by remember { mutableStateOf(prefs.getString("fcm_token", null)) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryError by remember { mutableStateOf<String?>(null) }
    var copiedToken by remember { mutableStateOf(false) }

    val nodeId = FCMService.getNodeId(context)
    val isRegistered = fcmToken != null && nodeId != null

    val lastHeartbeat = prefs.getLong("main_app_last_active", 0L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        // FCM Token card
        SCCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (fcmToken != null) {
                        clipboardManager.setText(AnnotatedString(fcmToken!!))
                        copiedToken = true
                    }
                }
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("FCM Token", style = MaterialTheme.typography.bodyLarge)
                    if (fcmToken != null) {
                        Text(
                            text = if (copiedToken) "Copied ✓" else "Tap to copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copiedToken) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(Sp.sm))
                if (fcmToken != null) {
                    val truncated = if (fcmToken!!.length > 24) {
                        "${fcmToken!!.take(16)}...${fcmToken!!.takeLast(8)}"
                    } else {
                        fcmToken!!
                    }
                    Text(
                        text = truncated,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "No token available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(Sp.lg))

        // Registration status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Registration", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isRegistered) semantic.usdStable else semantic.error,
                    modifier = Modifier.size(8.dp)
                ) {}
                Text(
                    text = if (isRegistered) "Registered" else "Unregistered",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isRegistered) semantic.usdText else semantic.error
                )
            }
        }

        Spacer(Modifier.height(Sp.lg))

        // Last heartbeat
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Last Heartbeat", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (lastHeartbeat > 0) {
                    Date(lastHeartbeat * 1000).relativeString()
                } else {
                    "No heartbeat recorded"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Sp.xl))

        // Retry token retrieval
        SCPillButton(
            text = "Retry Token Retrieval",
            onClick = {
                isRetrying = true
                retryError = null
                scope.launch {
                    try {
                        val token = withTimeoutOrNull(10_000L) {
                            FirebaseMessaging.getInstance().token.await()
                        }
                        if (token != null) {
                            FCMService.saveToken(context, token)
                            fcmToken = token
                            copiedToken = false
                            retryError = null
                        } else {
                            retryError = "Token could not be retrieved (timeout)"
                        }
                    } catch (e: Exception) {
                        retryError = e.message ?: "Token retrieval failed"
                    } finally {
                        isRetrying = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRetrying,
            busy = isRetrying
        )

        if (retryError != null) {
            Spacer(Modifier.height(Sp.sm))
            Text(
                text = retryError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
