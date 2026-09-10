package com.stablechannels.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.stablechannels.app.AppState
import com.stablechannels.app.Phase
import com.stablechannels.app.R
import com.stablechannels.app.ui.components.SCPillButton
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp

@Composable
fun ContentView(appState: AppState) {


    val phase by appState.phase.collectAsState()
    val errorMessage by appState.errorMessage.collectAsState()

    when (phase) {
        Phase.LOADING -> LoadingView()
        Phase.ONBOARDING -> SyncingView() // Auto-create handles this
        Phase.SYNCING -> SyncingView()
        Phase.WALLET -> MainTabView(appState)
        Phase.ERROR -> ErrorView(errorMessage) { appState.start() }
    }
}

@Composable
private fun PulsatingLogo() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    Image(
        painter = painterResource(R.mipmap.ic_launcher),
        contentDescription = "Stable Channels",
        modifier = Modifier
            .size(90.dp)
            .clip(CircleShape)
            .scale(scale)
    )
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Sp.xl)
        ) {
            PulsatingLogo()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stable Channels", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Sp.xs))
                Text(
                    "Self-custodial bitcoin trading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Sp.xl)
        ) {
            PulsatingLogo()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(Sp.md))
                Text("Syncing wallet...")
                Text(
                    "This may take a moment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Sp.xxl)
        ) {
            Text("Error", style = MaterialTheme.typography.headlineMedium, color = semantic.error)
            Spacer(Modifier.height(Sp.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Sp.lg))
            SCPillButton("Retry", onClick = onRetry)
        }
    }
}
