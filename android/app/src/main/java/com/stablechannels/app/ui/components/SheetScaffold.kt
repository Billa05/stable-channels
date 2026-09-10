package com.stablechannels.app.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.stablechannels.app.ui.theme.LocalDarkTheme
import com.stablechannels.app.ui.theme.Sp

// Standard modal sheet: edge-to-edge handling + height + title in one place (spec §4)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    heightFraction: Float = 0.9f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = modifier,
    ) {
        SheetEdgeToEdgeEffect()
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(heightFraction).padding(horizontal = Sp.xl),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = Sp.lg))
            }
            content()
        }
    }
}

// Edge-to-edge for a ModalBottomSheet's dialog window. Android 15+ enforces
// transparent system bars, so the (deprecated) color setters only run on older
// versions, where they are still the only way to clear the bars.
@Composable
internal fun SheetEdgeToEdgeEffect() {
    val view = LocalView.current
    val isDark = LocalDarkTheme.current
    DisposableEffect(view, isDark) {
        var context = view.context
        var dialog: android.app.Dialog? = null
        while (context is android.content.ContextWrapper) {
            if (context is android.app.Dialog) {
                dialog = context
                break
            }
            context = context.baseContext
        }
        val window = dialog?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        onDispose {}
    }
}
