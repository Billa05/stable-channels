package com.stablechannels.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavController
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.components.SectionHeader
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.util.Constants

@Composable
fun SettingsHub(appState: AppState, navController: NavController) {
    val onchainSats by appState.onchainBalanceSats.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Sp.lg, bottom = Sp.sm)
        )

        DisclaimerBanner()

        Spacer(Modifier.height(Sp.sm))

        // Wallet section
        SectionHeader(text = "Wallet", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.AccountBalance,
                label = "Stable Position",
                onClick = { navController.navigate(SettingsRoute.StablePosition.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.Hub,
                label = "Channel",
                onClick = { navController.navigate(SettingsRoute.Channel.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.Key,
                label = "Backup",
                onClick = { navController.navigate(SettingsRoute.Backup.route) }
            )
            if (onchainSats > 0) {
                SettingsNavLink(
                    icon = Icons.Default.Send,
                    label = "Send Onchain",
                    onClick = { navController.navigate(SettingsRoute.OnChainSend.route) }
                )
            }

            // Preferences section
            SectionHeader(text = "Preferences", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.Palette,
                label = "Appearance",
                onClick = { navController.navigate(SettingsRoute.Appearance.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.Notifications,
                label = "Notifications",
                onClick = { navController.navigate(SettingsRoute.Notifications.route) }
            )

            // Node & Network section
            SectionHeader(text = "Node & Network", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.Memory,
                label = "Node",
                onClick = { navController.navigate(SettingsRoute.Node.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.Router,
                label = "LSP",
                onClick = { navController.navigate(SettingsRoute.Lsp.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.Cloud,
                label = "Push Connectivity",
                onClick = { navController.navigate(SettingsRoute.PushConnectivity.route) }
            )

            // Privacy & Security section
            SectionHeader(text = "Privacy & Security", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.Lock,
                label = "App Access",
                onClick = { navController.navigate(SettingsRoute.AppAccess.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.PrivacyTip,
                label = "Privacy Policy",
                onClick = { uriHandler.openUri(Constants.PRIVACY_POLICY_URL) }
            )

            // Support section
            SectionHeader(text = "Support", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.MedicalServices,
                label = "Logs & Diagnostics",
                onClick = { navController.navigate(SettingsRoute.Logs.route) }
            )

            // About section
            SectionHeader(text = "About", modifier = Modifier.padding(start = Sp.lg, end = Sp.lg, top = Sp.sm))
            SettingsNavLink(
                icon = Icons.Default.Info,
                label = "About",
                onClick = { navController.navigate(SettingsRoute.About.route) }
            )
            SettingsNavLink(
                icon = Icons.Default.PrivacyTip,
                label = "Privacy Policy",
                onClick = { uriHandler.openUri("https://stablechannels.com/privacy.html") }
            )

            Spacer(Modifier.height(100.dp))
        }
}

@Composable
private fun DisclaimerBanner() {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Sp.lg, vertical = Sp.sm)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium
            )
            .border(
                width = 1.dp,
                color = semantic.warning,
                shape = MaterialTheme.shapes.medium
            )
            .padding(Sp.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(semantic.warning, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = semantic.onBtc,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = "Your keys, your coins.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(Sp.xs))
            Text(
                text = "Stable Channels is a self-custodial wallet. You control your private keys. Third parties do not custody, access, or freeze your funds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsNavLink(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp).clickable(onClick = onClick)
    )
}
