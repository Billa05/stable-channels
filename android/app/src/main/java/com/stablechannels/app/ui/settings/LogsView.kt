package com.stablechannels.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.components.SCCard
import com.stablechannels.app.ui.theme.Sp

@Composable
fun LogsView() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.lg)
    ) {
        Text("Logs & Diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Sp.sm))
        Text(
            "Save app logs to a file for debugging and support.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Sp.lg))

        SCCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { com.stablechannels.app.services.LogExporter.shareLogs(context) }
                        .padding(vertical = Sp.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(Sp.lg))
                    Text("Share the logs", color = MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { com.stablechannels.app.services.LogExporter.downloadLogs(context) }
                        .padding(vertical = Sp.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(Sp.lg))
                    Text("Download logs", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
