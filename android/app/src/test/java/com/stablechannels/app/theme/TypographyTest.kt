package com.stablechannels.app.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.ScTypography
import org.junit.Assert.assertEquals
import org.junit.Test

// Harshil ramp invariants (spec §3.2)
class TypographyTest {
    @Test fun `title is 22-28 semibold with negative tracking`() {
        val t = ScTypography.headlineMedium
        assertEquals(22.sp, t.fontSize); assertEquals(28.sp, t.lineHeight)
        assertEquals(FontWeight.SemiBold, t.fontWeight); assertEquals((-0.7).sp, t.letterSpacing)
    }
    @Test fun `section is 19-24 semibold`() {
        val t = ScTypography.titleLarge
        assertEquals(19.sp, t.fontSize); assertEquals(24.sp, t.lineHeight)
        assertEquals(FontWeight.SemiBold, t.fontWeight); assertEquals((-0.6).sp, t.letterSpacing)
    }
    @Test fun `body is 14-5-18 regular`() {
        val t = ScTypography.bodyLarge
        assertEquals(14.5.sp, t.fontSize); assertEquals(18.sp, t.lineHeight)
        assertEquals(FontWeight.Normal, t.fontWeight); assertEquals(0.3.sp, t.letterSpacing)
    }
    @Test fun `amounts use tabular figures`() {
        assertEquals("tnum", ScTextStyles.Amount.fontFeatureSettings)
        assertEquals("tnum", ScTextStyles.AmountSmall.fontFeatureSettings)
        assertEquals(28.sp, ScTextStyles.Amount.fontSize)
        assertEquals(15.sp, ScTextStyles.AmountSmall.fontSize)
    }
}
