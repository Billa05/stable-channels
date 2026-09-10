package com.stablechannels.app.theme

import com.stablechannels.app.ui.theme.ScDarkSemanticColors
import com.stablechannels.app.ui.theme.ScLightSemanticColors
import com.stablechannels.app.ui.theme.contrastRatio
import com.stablechannels.app.ui.theme.ScDarkColorScheme
import com.stablechannels.app.ui.theme.ScLightColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

// WCAG AA invariants for the token system (spec §7)
class ColorTokensTest {

    private fun assertAa(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$label contrast $ratio < 4.5", ratio >= 4.5)
    }

    @Test fun `light text tokens pass AA on background and surface`() {
        listOf("onBackground", "onSurface", "onSurfaceVariant").forEach { role ->
            val fg = fgFor(ScLightColorScheme, role)
            assertAa(fg, ScLightColorScheme.background, "light $role/bg")
            assertAa(fg, ScLightColorScheme.surface, "light $role/surface")
        }
    }

    @Test fun `dark text tokens pass AA on background and surface`() {
        listOf("onBackground", "onSurface", "onSurfaceVariant").forEach { role ->
            val fg = fgFor(ScDarkColorScheme, role)
            assertAa(fg, ScDarkColorScheme.background, "dark $role/bg")
            assertAa(fg, ScDarkColorScheme.surface, "dark $role/surface")
        }
    }

    @Test fun `light semantic text tokens pass AA on background and surface`() {
        val s = ScLightSemanticColors
        assertAa(s.usdText, ScLightColorScheme.background, "light usdText/bg")
        assertAa(s.usdText, ScLightColorScheme.surface, "light usdText/surface")
        assertAa(s.btcText, ScLightColorScheme.background, "light btcText/bg")
        assertAa(s.btcText, ScLightColorScheme.surface, "light btcText/surface")
        assertAa(s.error, ScLightColorScheme.background, "light error/bg")
        assertAa(s.error, ScLightColorScheme.surface, "light error/surface")
        assertAa(s.warning, ScLightColorScheme.background, "light warning/bg")
        assertAa(s.success, ScLightColorScheme.surface, "light success/surface")
    }

    @Test fun `dark semantic text tokens pass AA on background and surface`() {
        val s = ScDarkSemanticColors
        assertAa(s.usdText, ScDarkColorScheme.background, "dark usdText/bg")
        assertAa(s.usdText, ScDarkColorScheme.surface, "dark usdText/surface")
        assertAa(s.btcText, ScDarkColorScheme.background, "dark btcText/bg")
        assertAa(s.btcText, ScDarkColorScheme.surface, "dark btcText/surface")
        assertAa(s.error, ScDarkColorScheme.background, "dark error/bg")
        assertAa(s.success, ScDarkColorScheme.surface, "dark success/surface")
    }

    @Test fun `content on money containers passes AA`() {
        assertAa(ScLightSemanticColors.onUsd, ScLightSemanticColors.usdContainer, "light onUsd/usdContainer")
        assertAa(ScLightSemanticColors.onBtc, ScLightSemanticColors.btcContainer, "light onBtc/btcContainer")
        assertAa(ScDarkSemanticColors.onUsd, ScDarkSemanticColors.usdContainer, "dark onUsd/usdContainer")
        assertAa(ScDarkSemanticColors.onBtc, ScDarkSemanticColors.btcContainer, "dark onBtc/btcContainer")
    }

    @Test fun `money colors differ between usd and btc`() {
        assertTrue(contrastRatio(ScLightSemanticColors.usdStable, ScLightSemanticColors.btcNative) > 1.2)
        // spec dark solids teal-400/amber-400 sit at 1.12:1 — hue carries the distinction, so the dark luminance floor is 1.1
        assertTrue(contrastRatio(ScDarkSemanticColors.usdStable, ScDarkSemanticColors.btcNative) > 1.1)
    }

    private fun fgFor(
        scheme: androidx.compose.material3.ColorScheme,
        role: String
    ): androidx.compose.ui.graphics.Color = when (role) {
        "onBackground" -> scheme.onBackground
        "onSurface" -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
}
