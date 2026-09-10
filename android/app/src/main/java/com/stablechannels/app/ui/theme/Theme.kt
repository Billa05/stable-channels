package com.stablechannels.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalSemanticColors = staticCompositionLocalOf { ScLightSemanticColors }

val LocalDarkTheme = staticCompositionLocalOf {
    false
}

// ─── Theme Preference ────────────────────────────────────────────────────────

enum class ThemePreference(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System");

    companion object {
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_THEME = "theme_preference"

        fun load(context: Context): ThemePreference {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_THEME, null)
            return entries.find { it.name == stored } ?: SYSTEM
        }

        fun save(context: Context, preference: ThemePreference) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, preference.name)
                .apply()
        }
    }
}

// ─── Theme Composable ────────────────────────────────────────────────────────

@Composable
private fun rememberThemePreference(): ThemePreference {
    val context = LocalContext.current
    var preference by remember { mutableStateOf(ThemePreference.load(context)) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_preference") {
                preference = ThemePreference.load(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return preference
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun StableChannelsTheme(
    override: ThemePreference? = null,
    content: @Composable () -> Unit
) {
    val themePreference = override ?: rememberThemePreference()

    val darkTheme = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) ScDarkColorScheme else ScLightColorScheme
    val semanticColors = if (darkTheme) ScDarkSemanticColors else ScLightSemanticColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSemanticColors provides semanticColors,
        LocalDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ScTypography,
            shapes = ScShapes,
            content = content
        )
    }
}
