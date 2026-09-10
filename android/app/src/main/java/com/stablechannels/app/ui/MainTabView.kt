package com.stablechannels.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stablechannels.app.AppState
import com.stablechannels.app.ui.history.HistoryScreen
import com.stablechannels.app.ui.home.HomeScreen
import com.stablechannels.app.ui.settings.SettingsNavHost
import com.stablechannels.app.ui.theme.ScElevation
import com.stablechannels.app.ui.theme.scShadow

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    HISTORY("History", Icons.Default.AccessTime),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun MainTabView(appState: AppState) {
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var showBottomBar by remember { mutableStateOf(true) }
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    LaunchedEffect(selectedTab) {
        if (selectedTab != Tab.SETTINGS) {
            showBottomBar = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(systemBarsPadding)
    ) {
        when (selectedTab) {
            Tab.HOME -> HomeScreen(appState)
            Tab.HISTORY -> HistoryScreen(appState)
            Tab.SETTINGS -> SettingsNavHost(appState, onShowBottomBar = { showBottomBar = it })
        }
        if (showBottomBar) {
            ModernBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun ModernBottomNavBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    val tabShape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 228.dp)
                .fillMaxWidth()
                .scShadow(ScElevation.Floating, pillShape),
            shape = pillShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Tab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    val animatedColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200),
                        label = "color"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(tabShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(tab) }
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                shape = tabShape
                            )
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = animatedColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
