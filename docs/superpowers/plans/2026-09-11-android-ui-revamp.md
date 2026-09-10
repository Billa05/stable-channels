# Android UI Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the Android stable-channels wallet with an ink-neutral, Inter Tight design system (teal/amber reserved for USD/BTC semantics per issue #294), consolidating duplicated components and fixing dark-mode bugs — zero behavior/text/flow changes.

**Architecture:** Phase 1 builds a token system (color/typography/shape/spacing/elevation) + shared component library on top of the existing Material 3 machinery. Phase 2 sweeps all 35 live screens onto the tokens, screen-family by screen-family. Phase 3 audits (contrast, colorblind, MD3, raw-color grep, screenshots).

**Tech Stack:** Jetpack Compose (BOM 2024.12.01), Material 3, Inter Tight variable font (bundled resource), JUnit unit tests for token invariants.

**Spec:** `docs/superpowers/specs/2026-09-11-android-ui-revamp-design.md` — read it first; this plan argues from it. Approved mockups: `.superpowers/brainstorm/2149896-1789073302/content/visual-style.html` (option A) and `design-tokens.html` (pair P1).

## Global Constraints

- **No behavior, flow, navigation, or text-string changes.** UI layer only (`ui/`, `res/font/`, `build.gradle.kts` deps). Never touch `AppState.kt`, `services/`, `push/`, `models/`, `util/` logic.
- **Money-color exclusivity (#294):** teal (`usdStable*`) and amber (`btcNative*`) tokens are used ONLY for dollar-side and bitcoin-side semantics (balance bar, Buy/Sell, amounts about money direction). Everything else is ink. Receive/Send = neutral. Settings icons = monochrome ink.
- **All commits: title-only message, no body, no co-author, no issue refs.** (User rule.)
- **Never create PRs.** Push branch to `origin` (fork) only; the user opens PRs.
- **Comments: single-line only** (user rule).
- **No new runtime dependencies.** The only dependency change is REMOVING `io.github.bytebeats:compose-charts` (dead). Fonts are bundled resources, not deps.
- **Comments and UI copy in English; code style matches surrounding code.**
- Build/test commands (run from `android/`): `./gradlew :app:compileDebugKotlin` (fast check), `./gradlew :app:testDebugUnitTest` (unit tests), `./gradlew :app:assembleDebug` (full build).
- Package root: `com.stablechannels.app` at `android/app/src/main/java/com/stablechannels/app/`.
- Existing test infra: JUnit 4 + Robolectric already configured (`app/src/test/`). Token tests are plain JUnit — no Robolectric needed.

## File Structure (created/modified map)

| File | Action | Responsibility |
|---|---|---|
| `ui/theme/Color.kt` | Create | Raw color vals + ink M3 schemes + `SemanticColors` v2 + contrast test helpers data |
| `ui/theme/Theme.kt` | Modify | Use new schemes; add `override: ThemePreference?` param for previews |
| `ui/theme/Type.kt` | Create | `InterTight` FontFamily (variable font), `ScTypography`, `ScTextStyles` (Amount) |
| `ui/theme/Shapes.kt` | Create | `ScShapes` (12/16/18 radii) |
| `ui/theme/Spacing.kt` | Create | `Sp` spacing tokens (4/8/12/16/24/32) |
| `ui/theme/Elevation.kt` | Create | `Modifier.scShadow()`, `Modifier.scInsetHighlight()` |
| `ui/components/SCButton.kt` | Create | `SCCircleButton`, `SCPillButton`, `SCButtonTone` |
| `ui/components/SCCard.kt` | Create | `SCCard`, `CardDepth` |
| `ui/components/SectionHeader.kt` | Create | `SectionHeader` |
| `ui/components/AmountText.kt` | Create | `AmountText`, `AmountStyle` |
| `ui/components/DetailRow.kt` | Create | `DetailRow`, `DetailValueStyle` (replaces 6 dups) |
| `ui/components/SheetScaffold.kt` | Create | `SheetScaffold` (+ moved `SheetEdgeToEdgeEffect`) |
| `ui/components/SegmentedControl.kt` | Create | Generic `SegmentedControl` |
| `ui/components/StatusBadge.kt` | Create | `StatusBadge`, `StatusKind` |
| `ui/components/StatusCapsule.kt` | Modify | Restyle to tokens only |
| `res/font/inter_tight.ttf` | Create | Inter Tight variable font |
| `app/build.gradle.kts` | Modify | Remove compose-charts dep |
| `ui/settings/SettingsScreen.kt` | Delete | Dead code (540 lines) |
| All 35 live `ui/**.kt` | Modify | Sweep onto tokens (Tasks 7–12) |
| `app/src/test/.../theme/ColorTokensTest.kt` | Create | WCAG contrast invariants |
| `app/src/test/.../theme/TypographyTest.kt` | Create | Type ramp invariants |

---

### Task 1: Branch, fonts, spec commit

**Files:**
- Create: `android/app/src/main/res/font/inter_tight.ttf`
- Create: `android/app/InterTight-OFL.txt` (license — NOT inside `res/`; AAPT only accepts fonts there)
- Commit: spec, plan, `.gitignore` edit (already made), fonts

**Interfaces:**
- Produces: branch `feature/android-ui-revamp` off `upstream/main`; font resource `R.font.inter_tight` for Task 3.

- [ ] **Step 1: Snapshot current state and stash unrelated WIP**

The working tree is on `fix/android-payment-errors-291` with unrelated uncommitted changes (BackupView.kt, SettingsScreen.kt, Constants.kt, src/constants.rs, .gitignore). Stash ONLY those tracked files so the new branch is clean; untracked docs/ and .superpowers/ survive checkout untouched.

```bash
cd /home/biresh/Downloads/coding/stable-channels
git status --short | tee /tmp/pre-branch-status.txt
git stash push -m "wip: android payment errors 291 (set aside for ui revamp branch)" -- \
  android/app/src/main/java/com/stablechannels/app/ui/settings/BackupView.kt \
  android/app/src/main/java/com/stablechannels/app/ui/settings/SettingsScreen.kt \
  android/app/src/main/java/com/stablechannels/app/util/Constants.kt \
  src/constants.rs .gitignore
```

Note: demo-config files are skip-worktree'd on this machine (signet/mutinynet + demo LSP); they are intentionally NOT carried to the new branch. The revamp compiles and previews without them; re-apply locally only if on-device signet testing is needed.

- [ ] **Step 2: Cut the branch from upstream/main**

```bash
git fetch upstream
git checkout -b feature/android-ui-revamp upstream/main
git status --short   # expect: only untracked docs/ and .superpowers/
```

- [ ] **Step 3: Re-add the .gitignore entry (stashed away in step 1)**

Append to `.gitignore` (the stash removed our edit):

```
# Superpowers brainstorm mockups
.superpowers/
```

- [ ] **Step 4: Download Inter Tight variable font + license**

```bash
mkdir -p android/app/src/main/res/font
curl -L -o android/app/src/main/res/font/inter_tight.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/intertight/InterTight%5Bwght%5D.ttf"
curl -L -o android/app/InterTight-OFL.txt \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/intertight/OFL.txt"
ls -la android/app/src/main/res/font/   # inter_tight.ttf should be ~300-800KB, non-empty
```

If the URL 404s, find the current path via `curl -s https://api.github.com/repos/google/fonts/contents/ofl/intertight | grep download_url`.

- [ ] **Step 5: Verify the resource compiles**

Run: `cd android && ./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL (font resource is valid, name `inter_tight` is a legal resource name)

- [ ] **Step 6: Commit**

```bash
git add .gitignore docs/superpowers/specs/2026-09-11-android-ui-revamp-design.md \
  docs/superpowers/plans/2026-09-11-android-ui-revamp.md \
  android/app/src/main/res/font/inter_tight.ttf android/app/InterTight-OFL.txt
git commit -m "android ui revamp: spec, plan, inter tight font"
```

---

### Task 2: Color tokens — SemanticColors v2 + ink schemes + contrast tests

**Files:**
- Create: `android/app/src/main/java/com/stablechannels/app/ui/theme/Color.kt`
- Create: `android/app/src/test/java/com/stablechannels/app/theme/ColorTokensTest.kt`
- Modify: `android/app/src/main/java/com/stablechannels/app/ui/theme/Theme.kt`

**Interfaces:**
- Produces (used by every later task):
  - `val ScLightColorScheme: ColorScheme` / `val ScDarkColorScheme: ColorScheme` (internal)
  - `data class SemanticColors(success, warning, error, info, usdStable, usdText, usdContainer, onUsd, btcNative, btcText, btcContainer, onBtc: Color)` — all `Color`
  - `val LocalSemanticColors` (existing name, new shape)
  - `fun contrastRatio(a: Color, b: Color): Double` (test helper, lives in Color.kt so tests can use it)

Spec refinement note: spec §3.1 listed `muted #75757E` and `error #DC2626` (light) — both land at ~4.4:1 on the `#F5F5F7` background, just under AA. This task pins `muted = #6E6E77` and `error = #C62828` so every text token passes AA on background AND surface (spec §7 requirement). The spec's money-color values are unchanged; `usdContainer`/`btcContainer` are the AA-safe fill colors for text-bearing buttons (white text on them ≥ 4.5:1); the `solid` tokens (`usdStable`/`btcNative`) are for text-free fills (balance bar, dots).

- [ ] **Step 1: Write the failing contrast test**

`android/app/src/test/java/com/stablechannels/app/theme/ColorTokensTest.kt`:

```kotlin
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
        assertTrue(contrastRatio(ScDarkSemanticColors.usdStable, ScDarkSemanticColors.btcNative) > 1.2)
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.stablechannels.app.theme.ColorTokensTest"`
Expected: COMPILATION FAILURE — `ScLightColorScheme`, `contrastRatio` etc. unresolved.

- [ ] **Step 3: Write `Color.kt`**

`android/app/src/main/java/com/stablechannels/app/ui/theme/Color.kt`:

```kotlin
package com.stablechannels.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

// WCAG relative luminance + contrast ratio for token invariants
fun contrastRatio(a: Color, b: Color): Double {
    val la = relLuminance(a); val lb = relLuminance(b)
    val lighter = maxOf(la, lb); val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relLuminance(c: Color): Double {
    fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * lin(c.red.toDouble()) + 0.7152 * lin(c.green.toDouble()) + 0.0722 * lin(c.blue.toDouble())
}

// Ink-neutral palette (spec §3.1) — the app has exactly two chromatic colors: teal (USD) and amber (BTC)
internal val ScLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF101014),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8EC),
    onPrimaryContainer = Color(0xFF101014),
    secondary = Color(0xFF6E6E77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8EC),
    onSecondaryContainer = Color(0xFF2B2B33),
    tertiary = Color(0xFF2B2B33),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5F5F7),
    onTertiaryContainer = Color(0xFF101014),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF101014),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101014),
    surfaceVariant = Color(0xFFF5F5F7),
    onSurfaceVariant = Color(0xFF6E6E77),
    outline = Color(0xFFC9C9CF),
    outlineVariant = Color(0xFFE8E8EC),
)

internal val ScDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF0C0C0F),
    primaryContainer = Color(0xFF26262C),
    onPrimaryContainer = Color(0xFFF5F5F7),
    secondary = Color(0xFF8F8F99),
    onSecondary = Color(0xFF0C0C0F),
    secondaryContainer = Color(0xFF26262C),
    onSecondaryContainer = Color(0xFFE4E4E9),
    tertiary = Color(0xFFE4E4E9),
    onTertiary = Color(0xFF0C0C0F),
    tertiaryContainer = Color(0xFF19191F),
    onTertiaryContainer = Color(0xFFF5F5F7),
    error = Color(0xFFF87171),
    onError = Color(0xFF0C0C0F),
    errorContainer = Color(0xFF991B1B),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0C0C0F),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF19191F),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1F1F26),
    onSurfaceVariant = Color(0xFF8F8F99),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF26262C),
)

// Semantic tokens: usdStable/btcNative = text-free fills (bars, dots); *Text = AA on surfaces; *Container/on* = text-bearing fills
@Immutable
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val usdStable: Color,
    val usdText: Color,
    val usdContainer: Color,
    val onUsd: Color,
    val btcNative: Color,
    val btcText: Color,
    val btcContainer: Color,
    val onBtc: Color,
)

internal val ScLightSemanticColors = SemanticColors(
    success = Color(0xFF0F766E),   // one green: teal-700
    warning = Color(0xFFB45309),   // amber-700
    error = Color(0xFFC62828),
    info = Color(0xFF2563EB),
    usdStable = Color(0xFF0D9488), // teal-600 — balance bar / fills without text
    usdText = Color(0xFF0F766E),
    usdContainer = Color(0xFF0F766E),
    onUsd = Color(0xFFFFFFFF),
    btcNative = Color(0xFFF59E0B), // amber-500 — balance bar / fills without text
    btcText = Color(0xFFB45309),
    btcContainer = Color(0xFFB45309),
    onBtc = Color(0xFFFFFFFF),
)

internal val ScDarkSemanticColors = SemanticColors(
    success = Color(0xFF2DD4BF),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    info = Color(0xFF60A5FA),
    usdStable = Color(0xFF2DD4BF),
    usdText = Color(0xFF2DD4BF),
    usdContainer = Color(0xFF2DD4BF),
    onUsd = Color(0xFF0C0C0F),
    btcNative = Color(0xFFFBBF24),
    btcText = Color(0xFFFBBF24),
    btcContainer = Color(0xFFFBBF24),
    onBtc = Color(0xFF0C0C0F),
)
```

- [ ] **Step 4: Rewire `Theme.kt` to the new tokens**

In `Theme.kt`: delete the old palette vals (lines 27–91), old schemes (95–147), old `SemanticColors` class + light/dark instances (151–181); keep `LocalDarkTheme`, `ThemePreference`, `rememberThemePreference`, `findActivity`. Replace with:

```kotlin
val LocalSemanticColors = staticCompositionLocalOf { ScLightSemanticColors }
```

In `StableChannelsTheme`, change the scheme/semantic selection and add a preview override param:

```kotlin
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
    // ... rest unchanged (SideEffect + CompositionLocalProvider)
```

Fix the now-unused imports. Grep check: `grep -rn "0xFF007AFF\|0xFFF2F2F7\|0xFF10B981\|0xFF059669" android/app/src/main/` → no hits in theme; callers outside theme still compile because `LocalSemanticColors` field names `success/warning/error/info/btcNative/usdStable` still exist (usages of removed fields: none existed — old class had only these six names). Old scheme was `private` so nothing external referenced it.

- [ ] **Step 5: Run tests + compile**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.stablechannels.app.theme.ColorTokensTest" :app:compileDebugKotlin`
Expected: ALL PASS + compiles (downstream files that referenced `MaterialTheme.colorScheme.primary` etc. still compile — slot names unchanged, only values changed).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/theme/ android/app/src/test/java/com/stablechannels/app/theme/
git commit -m "android theme: ink-neutral tokens with AA-tested money colors"
```

---

### Task 3: Typography, shapes, spacing tokens

**Files:**
- Create: `android/app/src/main/java/com/stablechannels/app/ui/theme/Type.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/theme/Shapes.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/theme/Spacing.kt`
- Create: `android/app/src/test/java/com/stablechannels/app/theme/TypographyTest.kt`
- Modify: `android/app/src/main/java/com/stablechannels/app/ui/theme/Theme.kt` (pass typography + shapes into MaterialTheme)

**Interfaces:**
- Consumes: `R.font.inter_tight` (Task 1)
- Produces: `val InterTight: FontFamily`; `val ScTypography: Typography`; `object ScTextStyles { val Amount: TextStyle; val AmountSmall: TextStyle }`; `val ScShapes: Shapes`; `object Sp { val xs, sm, md, lg, xl, xxl: Dp }`

- [ ] **Step 1: Write the failing ramp test**

`android/app/src/test/java/com/stablechannels/app/theme/TypographyTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.stablechannels.app.theme.TypographyTest"`
Expected: COMPILATION FAILURE — `ScTypography`/`ScTextStyles` unresolved.

- [ ] **Step 3: Write the three token files**

`Type.kt`:

```kotlin
package com.stablechannels.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stablechannels.app.R

// Single variable font; weights selected via variation settings
val InterTight = FontFamily(
    Font(R.font.inter_tight, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_tight, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_tight, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val ScTypography = Typography(
    headlineLarge = TextStyle(InterTight, 26.sp, 32.sp, FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(InterTight, 22.sp, 28.sp, FontWeight.SemiBold, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(InterTight, 20.sp, 26.sp, FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(InterTight, 19.sp, 24.sp, FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(InterTight, 16.sp, 22.sp, FontWeight.Medium, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(InterTight, 14.sp, 20.sp, FontWeight.Medium, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(InterTight, 14.5.sp, 18.sp, FontWeight.Normal, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(InterTight, 13.5.sp, 18.sp, FontWeight.Normal, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(InterTight, 12.5.sp, 16.sp, FontWeight.Normal, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(InterTight, 14.sp, 20.sp, FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    labelMedium = TextStyle(InterTight, 12.5.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(InterTight, 11.sp, 14.sp, FontWeight.Medium, letterSpacing = 0.2.sp),
)

// Non-M3 wallet roles (spec §3.2)
object ScTextStyles {
    val Amount = TextStyle(InterTight, 28.sp, 34.sp, FontWeight.SemiBold, letterSpacing = (-0.9).sp,
        fontFeatureSettings = "tnum")
    val AmountSmall = TextStyle(InterTight, 15.sp, 20.sp, FontWeight.Medium, letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum")
}
```

`Shapes.kt`:

```kotlin
package com.stablechannels.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ScShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
```

`Spacing.kt`:

```kotlin
package com.stablechannels.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Sp {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}
```

- [ ] **Step 4: Wire into MaterialTheme**

In `Theme.kt`'s `MaterialTheme(...)` call add:

```kotlin
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ScTypography,
            shapes = ScShapes,
            content = content
        )
```

- [ ] **Step 5: Run tests + compile**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.stablechannels.app.theme.TypographyTest" :app:compileDebugKotlin`
Expected: PASS + compiles. (If `Font(..., variationSettings = ...)` needs an opt-in, add `@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)` on the `InterTight` val — compose-ui 1.7.6 in BOM 2024.12.01 normally does not.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/theme/ android/app/src/test/java/com/stablechannels/app/theme/
git commit -m "android theme: inter tight ramp, shapes and spacing tokens"
```

---

### Task 4: Elevation — layered shadows + inset highlight modifiers

**Files:**
- Create: `android/app/src/main/java/com/stablechannels/app/ui/theme/Elevation.kt`

**Interfaces:**
- Consumes: `LocalDarkTheme`
- Produces: `enum class ScElevation { Subtle, Raised, Floating }`; `fun Modifier.scShadow(level: ScElevation, shape: Shape, insetHighlight: Boolean = false): Modifier`

Design (spec §3.3): layered = two stacked `shadow` layers (tight + diffuse). No borders on cards, ever.

- [ ] **Step 1: Write `Elevation.kt`**

```kotlin
package com.stablechannels.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

enum class ScElevation { Subtle, Raised, Floating }

// Layered elevation: tight layer + diffuse layer, never borders (spec §3.3)
fun Modifier.scShadow(level: ScElevation, shape: Shape, insetHighlight: Boolean = false): Modifier = composed {
    val dark = LocalDarkTheme.current
    val (tight, diffuse, insetAlpha) = when (level) {
        ScElevation.Subtle -> Triple(2.dp, 10.dp, if (dark) 0.06f else 0.95f)
        ScElevation.Raised -> Triple(4.dp, 18.dp, if (dark) 0.07f else 0.95f)
        ScElevation.Floating -> Triple(8.dp, 28.dp, if (dark) 0.08f else 0.95f)
    }
    val spot = if (dark) 0.45f else 0.13f
    this
        .shadow(diffuse, shape, clip = false, ambientColor = Color.Black.copy(alpha = spot * 0.4f), spotColor = Color.Black.copy(alpha = spot))
        .shadow(tight, shape, clip = false, ambientColor = Color.Black.copy(alpha = spot * 0.7f), spotColor = Color.Black.copy(alpha = spot * 0.7f))
        .then(if (insetHighlight) Modifier.scInsetHighlight(insetAlpha) else Modifier)
}

// 1px top-light edge on interactive surfaces
fun Modifier.scInsetHighlight(topAlpha: Float = 0.95f): Modifier = drawWithContent {
    drawContent()
    val radius = CornerRadius(size.height / 2.coerceAtMost(24f))
    drawRoundRect(
        color = Color.White.copy(alpha = topAlpha),
        topLeft = Offset(0f, 0f),
        size = size,
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}
```

Notes for implementer: `radius` above approximates the component's rounding for pill/circle shapes — if `shape` is a `RoundedCornerShape`, prefer `shape.createOutline(size, layoutDirection, this).let { /* use its path bounds */ }` is overkill; the simple half-height radius covers our pill/circle/card cases. Keep it simple.

- [ ] **Step 2: Compile check**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/theme/Elevation.kt
git commit -m "android theme: layered shadow and inset highlight modifiers"
```

---

### Task 5: Core components — SCButton, SCCard, SectionHeader, AmountText

**Files:**
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/SCButton.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/SCCard.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/SectionHeader.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/AmountText.kt`

**Interfaces:**
- Consumes: `ScElevation`/`scShadow` (Task 4), `LocalSemanticColors` (Task 2), `ScTextStyles`/`ScTypography` (Task 3), `StableChannelsTheme(override=...)` (Task 2) for previews.
- Produces:
  - `enum class SCButtonTone { Neutral, Usd, Btc }`
  - `@Composable fun SCPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: SCButtonTone = Neutral, enabled: Boolean = true, leadingIcon: ImageVector? = null)`
  - `@Composable fun SCCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: SCButtonTone = Neutral, size: Dp = 44.dp, label: String? = null)`
  - `enum class CardDepth { Subtle, Raised }`
  - `@Composable fun SCCard(modifier: Modifier = Modifier, depth: CardDepth = CardDepth.Subtle, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun SectionHeader(text: String, modifier: Modifier = Modifier)`
  - `enum class AmountStyle { Hero, Small }`
  - `@Composable fun AmountText(text: String, modifier: Modifier = Modifier, style: AmountStyle = AmountStyle.Hero, color: Color = Color.Unspecified)`

- [ ] **Step 1: Write `SCButton.kt`**

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.scInsetHighlight
import com.stablechannels.app.ui.theme.scShadow
import com.stablechannels.app.ui.theme.ScElevation

enum class SCButtonTone { Neutral, Usd, Btc }

// Circular inset-highlight button (spec §4) — 40-46dp, layered shadow, no flat fill
@Composable
fun SCCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SCButtonTone = SCButtonTone.Neutral,
    size: Dp = 44.dp,
    label: String? = null,
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val iconColor = when (tone) {
        SCButtonTone.Neutral -> scheme.onSurface
        SCButtonTone.Usd -> semantic.usdText
        SCButtonTone.Btc -> semantic.btcText
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "scCirclePress")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = scheme.surface,
            interactionSource = interaction,
            modifier = modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(size)
                .scShadow(ScElevation.Raised, CircleShape, insetHighlight = true),
        ) {
            Icon(icon, contentDescription, tint = iconColor, modifier = Modifier.padding(Sp.md))
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Sp.xs))
        }
    }
}

// Pill button — filled only for money tones (usdContainer/btcContainer), ink for neutral
@Composable
fun SCPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SCButtonTone = SCButtonTone.Neutral,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        SCButtonTone.Neutral -> scheme.primary to scheme.onPrimary
        SCButtonTone.Usd -> semantic.usdContainer to semantic.onUsd
        SCButtonTone.Btc -> semantic.btcContainer to semantic.onBtc
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "scPillPress")
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = Sp.xl, vertical = Sp.md),
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .scShadow(ScElevation.Subtle, RoundedCornerShape(50), insetHighlight = true),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, modifier = Modifier.padding(end = Sp.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
```

- [ ] **Step 2: Write `SCCard.kt`, `SectionHeader.kt`, `AmountText.kt`**

`SCCard.kt`:

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.ScElevation
import com.stablechannels.app.ui.theme.Sp
import com.stablechannels.app.ui.theme.scShadow
import androidx.compose.foundation.shape.RoundedCornerShape

enum class CardDepth { Subtle, Raised }

// Borderless layered-shadow card (spec §4)
@Composable
fun SCCard(
    modifier: Modifier = Modifier,
    depth: CardDepth = CardDepth.Subtle,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.scShadow(if (depth == CardDepth.Raised) ScElevation.Raised else ScElevation.Subtle, shape),
    ) {
        Column(modifier = Modifier.padding(Sp.lg), content = content)
    }
}
```

`SectionHeader.kt`:

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stablechannels.app.ui.theme.Sp

// 19/24 semibold section header (spec §3.2)
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = Sp.sm),
    )
}
```

`AmountText.kt`:

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.material3.Material3Experimental
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.stablechannels.app.ui.theme.ScTextStyles

enum class AmountStyle { Hero, Small }

// Tabular-figures amount text (spec §3.2)
@Composable
fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    style: AmountStyle = AmountStyle.Hero,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = if (style == AmountStyle.Hero) ScTextStyles.Amount else ScTextStyles.AmountSmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        modifier = modifier,
    )
}
```

- [ ] **Step 3: Add light + dark previews for each component**

At the bottom of each of the four files add previews (pattern shown once here; repeat per file with real sample content — icons from `androidx.compose.material.icons.Icons`):

```kotlin
@Preview(showBackground = true)
@Composable
private fun SCCardPreviewLight() {
    StableChannelsTheme(override = ThemePreference.LIGHT) {
        SCCard { Text("Pending settlement", style = MaterialTheme.typography.bodyLarge) }
    }
}

@Preview(showBackground = true)
@Composable
private fun SCCardPreviewDark() {
    StableChannelsTheme(override = ThemePreference.DARK) {
        SCCard { Text("Pending settlement", style = MaterialTheme.typography.bodyLarge) }
    }
}
```

Imports: `androidx.compose.ui.tooling.preview.Preview`, `com.stablechannels.app.ui.theme.StableChannelsTheme`, `com.stablechannels.app.ui.theme.ThemePreference`.

- [ ] **Step 4: Compile + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/components/
git commit -m "android components: buttons, cards, headers, amounts"
```

---

### Task 6: Row/sheet components — DetailRow, SheetScaffold, SegmentedControl, StatusBadge

**Files:**
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/DetailRow.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/SheetScaffold.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/SegmentedControl.kt`
- Create: `android/app/src/main/java/com/stablechannels/app/ui/components/StatusBadge.kt`
- Modify: `android/app/src/main/java/com/stablechannels/app/ui/components/StatusCapsule.kt` (restyle only)

**Interfaces:**
- Produces:
  - `enum class DetailValueStyle { Body, Mono, Amount }`
  - `@Composable fun DetailRow(label: String, value: String, modifier: Modifier = Modifier, valueStyle: DetailValueStyle = DetailValueStyle.Body, onCopy: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null)`
  - `@Composable fun SheetScaffold(onDismiss: () -> Unit, modifier: Modifier = Modifier, title: String? = null, heightFraction: Float = 0.9f, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun <T> SegmentedControl(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier)`
  - `enum class StatusKind { Positive, Negative, Neutral, Pending }`
  - `@Composable fun StatusBadge(text: String, kind: StatusKind, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write `DetailRow.kt`**

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stablechannels.app.ui.theme.ScTextStyles
import com.stablechannels.app.ui.theme.Sp

enum class DetailValueStyle { Body, Mono, Amount }

// Single labeled value row replacing the six per-screen duplicates
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: DetailValueStyle = DetailValueStyle.Body,
    onCopy: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Sp.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = when (valueStyle) {
                DetailValueStyle.Body -> MaterialTheme.typography.bodyLarge
                DetailValueStyle.Mono -> MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                DetailValueStyle.Amount -> ScTextStyles.AmountSmall
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onCopy != null) {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(value)); copied = true; onCopy()
            }, modifier = Modifier.padding(start = Sp.xs)) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}
```

- [ ] **Step 2: Write `SheetScaffold.kt`**

Move the existing `SheetEdgeToEdgeEffect` composable (currently private in `HomeScreen.kt`) into this file unchanged, and wrap the standard sheet pattern (copy-pasted 4× in HomeScreen today):

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
```

Follow the existing HomeScreen sheet call-sites for anything this scaffold must also carry (e.g. `dragHandle` styling) — port those parameters in rather than re-inventing; the goal is one canonical sheet.

- [ ] **Step 3: Write `SegmentedControl.kt` and `StatusBadge.kt`**

`SegmentedControl.kt` — extract HistoryScreen's segmented control (trades/payments toggle) into a generic component; keep its exact layout/animation behavior, replace hardcoded colors with `MaterialTheme.colorScheme.*` (selected segment = `surface` + `scShadow(Subtle)`, unselected = transparent, text = `onSurface`/`onSurfaceVariant`):

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.stablechannels.app.ui.theme.Sp

// iOS-style segmented control on tokens
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                color = if (isSelected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = Sp.xs),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = Sp.lg, vertical = Sp.sm)) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

`StatusBadge.kt`:

```kotlin
package com.stablechannels.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stablechannels.app.ui.theme.LocalSemanticColors
import com.stablechannels.app.ui.theme.Sp

enum class StatusKind { Positive, Negative, Neutral, Pending }

// Pill status badge (unifies HistoryScreen's StatusBadge + StatusCapsule usage)
@Composable
fun StatusBadge(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    val fg = when (kind) {
        StatusKind.Positive -> semantic.usdText
        StatusKind.Negative -> semantic.error
        StatusKind.Neutral -> scheme.onSurfaceVariant
        StatusKind.Pending -> semantic.warning
    }
    Surface(shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Transparent, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg,
            modifier = Modifier.padding(horizontal = Sp.sm, vertical = Sp.xs))
    }
}
```

- [ ] **Step 4: Restyle `StatusCapsule.kt`**

Keep structure/animations; replace `RoundedCornerShape(50)` (fine to keep), set `color = MaterialTheme.colorScheme.surface` + add `scShadow(ScElevation.Raised, ...)` instead of `tonalElevation = 3.dp`, text style `labelMedium`, color `onSurface`. No signature change.

- [ ] **Step 5: Previews + compile + commit**

Add light/dark `@Preview` for DetailRow (Body/Mono/Amount + copy), SegmentedControl (two options), StatusBadge (all four kinds) — same pattern as Task 5 Step 3.

Run: `cd android && ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/components/
git commit -m "android components: rows, sheets, segments, status"
```

---

## Sweeps — read this pattern table first (Tasks 7–12 all apply it)

Apply these transformations to every file listed. Verification per file after editing:

```bash
cd /home/biresh/Downloads/coding/stable-channels
grep -nE "Color\(0x[0-9A-Fa-f]{8}\)|isSystemInDarkTheme|RoundedCornerShape\((12|16|18)\.dp\)" <FILE> \
  | grep -v "RoundedCornerShape(50)" || echo "CLEAN"
```

Expected: `CLEAN` (rounded 50 stays for pills; canvas px math in PriceChart is exempt but its colors must come from tokens).

| # | Find (representative) | Replace with |
|---|---|---|
| P1 | `isSystemInDarkTheme()` | `LocalDarkTheme.current` (+ import swap `ui.theme.LocalDarkTheme`) |
| P2 | `Color(0xFF10B981)`, `0xFF059669`, `0xFF34D399`, `0xFF6EE7B7` (green) | `semantic.usdStable` for fills/bars/dots; `semantic.usdText` for text/icon color |
| P3 | `Color(0xFFF59E0B)`, `0xFFD97706`, `0xFFFBBF24`, `0xFF92400E` (amber/orange) | `semantic.btcNative` for fills; `semantic.btcText` for text/icons |
| P4 | `Color(0xFFEF4444)`, `0xFFDC2626`, `0xFFF87171` | `semantic.error` (or `MaterialTheme.colorScheme.error`) |
| P5 | `Color(0xFF3B82F6)`, `0xFF2563EB`, `0xFF60A5FA`, `0xFF007AFF`, `0xFF0A84FF` | `semantic.info` — ONLY if genuinely informational; if it's an old "primary action" accent, it becomes ink (`colorScheme.primary`/`onSurface`) |
| P6 | `Color(0xFF8B5CF6)`, `0xFF6366F1`, `0xFF6B7280`, `0xFF9CA3AF`, other grays | ink family: icons/labels → `onSurface`/`onSurfaceVariant`; separators → `outlineVariant`; dark backgrounds `0xFF1C1C1E`-ish → `surfaceVariant`/`surface` |
| P7 | ad-hoc `fontSize = X.sp` + weight on Text | nearest `MaterialTheme.typography` slot (24-28 titles → `headlineMedium`, 18-19 → `titleLarge`, ~14 body → `bodyLarge`, 12-13 → `bodySmall`/`labelMedium`); amounts → `AmountText`/`ScTextStyles` |
| P8 | `RoundedCornerShape(12.dp)` | `MaterialTheme.shapes.small` |
| P9 | `RoundedCornerShape(16.dp)` | `MaterialTheme.shapes.medium` |
| P10 | `RoundedCornerShape(18.dp)`+ | `MaterialTheme.shapes.large` |
| P11 | labeled-value row implementations | `DetailRow(...)` |
| P12 | ModalBottomSheet blocks w/ edge-to-edge | `SheetScaffold(...)` |
| P13 | `ActionButton`-style tonal buttons | `SCPillButton` / `SCCircleButton` |
| P14 | card `Surface`/`Card` with border or tonalElevation | `SCCard` |
| P15 | hardcoded `Spacer(Modifier.height/width(N.dp))` where N ∈ {4,8,12,16,24,32} | `Sp.xs/sm/md/lg/xl/xxl` |
| P16 | text "Settlement/amount" style Text for balances | `AmountText` (keep surrounding Text content EXACTLY the same) |

Rules: **string resources and literal labels never change**; layout structure changes only where a component from Tasks 5–6 replaces it 1:1; if a file uses a color with no clean token mapping, stop and pick the closest ink token — never mint a new hex. Where `MaterialTheme.colorScheme.surface` was forced to `Color.Black/White` for sheets, the scheme now provides it — delete the forcing.

---

### Task 7: Sweep A — Home surface

**Files:**
- Modify: `ui/home/HomeScreen.kt` (665 lines; hosts all sheets)
- Modify: `ui/home/BalanceBar.kt` (306)
- Modify: `ui/home/PriceChart.kt` (462)
- Modify: `ui/home/FundWalletScreen.kt` (175)
- Modify: `ui/home/RollingDigitText.kt` (68)
- Modify: `ui/home/PaymentFlashModifier.kt` (83)
- Modify: `ui/MainTabView.kt`

**Interfaces:**
- Consumes: all components + tokens from Tasks 2–6.

- [ ] **Step 1: Migrate HomeScreen**

Apply the full pattern table. Specifics:
- The four copy-pasted `ModalBottomSheet + SheetEdgeToEdgeEffect + fillMaxHeight(0.9f)` blocks → `SheetScaffold` (this REMOVES `SheetEdgeToEdgeEffect` from HomeScreen — it moved in Task 6).
- `ActionButton` → `SCPillButton`/`SCCircleButton`. The four home actions map: Buy → `SCButtonTone.Btc`, Sell → `SCButtonTone.Usd`, Receive → `Neutral`, Send → `Neutral` (#294).
- Balance display Texts → `AmountText`.
- `isSystemInDarkTheme()` in `SheetEdgeToEdgeEffect` moved out in Task 6; any remaining direct calls → `LocalDarkTheme.current`.
- Section titles → `SectionHeader`.

- [ ] **Step 2: Migrate BalanceBar**

Green segment fill → `semantic.usdStable`, orange → `semantic.btcNative`; labels/amounts → tokens per table; drag-gesture logic untouched.

- [ ] **Step 3: Migrate PriceChart (careful — Canvas)**

Do NOT touch the drawing math/perf structures (`key(priceHistory)`, 200-point cap, `derivedStateOf`). Replace only color inputs: line/gradient uses BTC price → chart stroke may use `semantic.btcText`/`btcNative` with alpha ramp; grid/axis → `outlineVariant`; scrub label text → typography slots. Colors must come from `LocalSemanticColors`/`colorScheme` captured OUTSIDE the Canvas draw lambda (capture as vals before `Canvas {}`).

- [ ] **Step 4: Migrate FundWalletScreen, RollingDigitText, PaymentFlashModifier, MainTabView**

- FundWalletScreen: QR block untouched (zxing bitmap); surrounding chrome per table; the embedded copy in ReceiveScreen is Task 9's concern if duplicated.
- RollingDigitText: internal TextStyle → `ScTextStyles.Amount` (keeps odometer animation intact).
- PaymentFlashModifier: hard-coded green tint → `semantic.usdStable` passed in as a param with the current green as call-site default replaced at HomeScreen call-site.
- MainTabView: nav pill → `scShadow(Floating)` + surface; active/inactive icon colors → `animateColorAsState(onSurface, onSurfaceVariant)` (already animated — keep animation, swap endpoints); pill radius stays 50.

- [ ] **Step 5: Verify + commit**

```bash
cd android && ./gradlew :app:compileDebugKotlin
cd .. && for f in HomeScreen BalanceBar PriceChart FundWalletScreen RollingDigitText PaymentFlashModifier; do
  grep -nE "Color\(0x[0-9A-Fa-f]{8}\)|isSystemInDarkTheme" android/app/src/main/java/com/stablechannels/app/ui/home/$f.kt || echo "$f CLEAN"; done
grep -nE "Color\(0x|isSystemInDarkTheme" android/app/src/main/java/com/stablechannels/app/ui/MainTabView.kt || echo "MainTabView CLEAN"
```

Expected: compile SUCCESSFUL, every file CLEAN.

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/
git commit -m "android home surface on design tokens"
```

---

### Task 8: Sweep B — Trade sheets

**Files:**
- Modify: `ui/trade/BuyScreen.kt` (351)
- Modify: `ui/trade/SellScreen.kt` (339)

**Interfaces:** Consumes: components + tokens (Tasks 2–6).

- [ ] **Step 1: Migrate both screens with the pattern table**

Specifics: two-step flow (amount → confirm) keeps its structure; `ConfirmRow` → `DetailRow`; confirm button: Buy → `SCPillButton(tone = Btc)`, Sell → `SCPillButton(tone = Usd)`; the curve loader (CurveProgressIndicator) restyles in Task 12 (components dir) — only call-site colors here; USD amounts → `AmountText(style = Small)`; BTC amounts → `AmountText` with `color = semantic.btcText`.

- [ ] **Step 2: Verify + commit**

Same grep as Task 7 on both files → CLEAN; `./gradlew :app:compileDebugKotlin` SUCCESSFUL.

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/trade/
git commit -m "android trade sheets on design tokens"
```

---

### Task 9: Sweep C — Transfer sheets

**Files:**
- Modify: `ui/transfer/SendScreen.kt` (839 — largest file, extra care)
- Modify: `ui/transfer/ReceiveScreen.kt` (304)
- Modify: `ui/transfer/OnChainScreen.kt` (388)

**Interfaces:** Consumes: components + tokens.

- [ ] **Step 1: Migrate SendScreen (839 lines — go section by section)**

Invoice/address parsing, `InputTypeIndicator`, fee logic, scanner hooks: UNTOUCHED. Visual chrome per pattern table: input field → scheme `surface`/`outline`; Send button → `SCPillButton` Neutral (Send is not a money-direction action); amount preview → `AmountText`.

- [ ] **Step 2: Migrate ReceiveScreen + OnChainScreen**

Receive button chrome → Neutral (NOT green — #294); QR blocks untouched; on-chain fee-rate selector per table.

- [ ] **Step 3: Verify + commit**

Grep all three files CLEAN; compile SUCCESSFUL.

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/transfer/
git commit -m "android transfer sheets on design tokens"
```

---

### Task 10: Sweep D — History + detail sheets

**Files:**
- Modify: `ui/history/HistoryScreen.kt` (410)
- Modify: `ui/history/PaymentDetailDialog.kt` (222)
- Modify: `ui/history/TradeDetailDialog.kt` (190)

**Interfaces:** Consumes: `SegmentedControl`, `StatusBadge`, `DetailRow`, `SheetScaffold`.

- [ ] **Step 1: Migrate HistoryScreen**

Delete the inline segmented control (now `SegmentedControl`); inline `StatusBadge` → new `StatusBadge` (map statuses: succeeded/settled → `Positive`, failed → `Negative`, pending → `Pending`, other → `Neutral`); `EmptyStateView` → tokens; dark-mode detection via background-equality → `LocalDarkTheme.current`.

- [ ] **Step 2: Migrate detail dialogs**

Both become `SheetScaffold` contents; `DetailRow`/`CopyableDetailRow` → `DetailRow(onCopy = ...)`; trade amounts direction-coloring: USD-side values `usdText`, BTC-side `btcText`.

- [ ] **Step 3: Verify + commit**

Grep all three files CLEAN; compile SUCCESSFUL.

```bash
git add android/app/src/main/java/com/stablechannels/app/ui/history/
git commit -m "android history on design tokens"
```

---

### Task 11: Sweep E — Settings (incl. deletions + dep removal)

**Files:**
- Delete: `ui/settings/SettingsScreen.kt` (540 lines, dead — nothing references `SettingsScreen(`)
- Modify: `android/app/build.gradle.kts` (remove line 123-124 `// Charts` + compose-charts dep)
- Modify: `ui/settings/SettingsHub.kt` (253), `StablePositionView.kt` (166), `ChannelView.kt` (206), `BackupView.kt` (467), `OnChainSendSettingsView.kt` (14), `AppearanceView.kt` (83), `NotificationsView.kt` (93), `NodeView.kt` (135), `LspSettingsView.kt` (248), `PushConnectivityView.kt` (197), `AppAccessView.kt` (145), `LogsView.kt` (71), `AboutView.kt` (61), `SettingsNavigation.kt`

**Interfaces:** Consumes: components + tokens.

- [ ] **Step 1: Confirm SettingsScreen.kt is dead, then delete**

```bash
grep -rn "SettingsScreen(" android/app/src/main/java/ | grep -v "ui/settings/SettingsScreen.kt" || echo "DEAD - safe to delete"
git rm android/app/src/main/java/com/stablechannels/app/ui/settings/SettingsScreen.kt
```

- [ ] **Step 2: Remove dead chart dependency**

In `build.gradle.kts` delete:
```kotlin
    // Charts
    implementation("io.github.bytebeats:compose-charts:0.2.1")
```

- [ ] **Step 3: Migrate SettingsHub — monochrome sections**

Section colors (`0xFF8B5CF6` purple, `0xFF6366F1` indigo, `0xFF6B7280` gray, …) → ALL become ink: icons `colorScheme.onSurfaceVariant`, section headers `SectionHeader`. This is the confirmed monochrome decision. `SettingsNavLink` rows → tokens per table; `DisclaimerBanner` → `warning`-tone only if it's a genuine warning (it is — keep warning semantics).

- [ ] **Step 4: Migrate the 12 sub-screens + navigation chrome**

Per pattern table. BackupView: seed-word grid keeps `FontFamily.Monospace`; the existing seed/restore logic untouched. `SettingsSubViewScaffold`'s `CenterAlignedTopAppBar` → scheme colors (`surface`, `onSurface`), title typography `headlineSmall`. `AboutRow` → `DetailRow`.

- [ ] **Step 5: Verify + commit**

```bash
cd android && ./gradlew :app:compileDebugKotlin && ./gradlew :app:assembleDebug
cd .. && grep -rnE "Color\(0x[0-9A-Fa-f]{8}\)" android/app/src/main/java/com/stablechannels/app/ui/settings/ || echo "settings CLEAN"
```

Expected: both builds SUCCESSFUL; settings CLEAN.

```bash
git add -A android/app/
git commit -m "android settings monochrome on design tokens, drop dead chart dep"
```

---

### Task 12: Sweep F — Scanner, phase states, remaining chrome

**Files:**
- Modify: `ui/scanner/QRScannerScreen.kt` (330)
- Modify: `ui/ContentView.kt` (Loading/Syncing/Error states)
- Modify: `ui/components/CurveProgressIndicator.kt` (hardcoded default colors → scheme/semantic params)
- Modify: `MainActivity.kt` (only if it references theme colors — status bar handling stays)

**Interfaces:** Consumes: tokens.

- [ ] **Step 1: Migrate scanner overlay**

CameraX/ML Kit logic untouched. Overlay reticle Canvas colors → `onSurface`/`error` tokens captured outside draw lambdas; permission denial UI per table.

- [ ] **Step 2: Migrate ContentView phases + CurveProgressIndicator**

Loading/Syncing/Error screens: `PulsatingLogo` animation kept, colors → ink/`primary`; ErrorView → `semantic.error` text, `SCPillButton` Neutral retry. CurveProgressIndicator: default color params change from hardcoded to `MaterialTheme.colorScheme.primary`-derived defaults (keep param override capability).

- [ ] **Step 3: Verify + commit**

Grep CLEAN across `ui/`; full unit suite:

```bash
cd android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: SUCCESSFUL, all existing tests + the two new token test classes pass.

```bash
git add android/app/src/main/java/com/stablechannels/app/
git commit -m "android scanner and phase states on design tokens"
```

---

### Task 13: Audit + final verification + push

**Files:** None created (audits + fixes found by them).

- [ ] **Step 1: Raw-token sweep assertion**

```bash
grep -rnE "Color\(0x[0-9A-Fa-f]{8}\)" android/app/src/main/java/com/stablechannels/app/ui/ \
  | grep -v "ui/theme/" || echo "UI CLEAN - zero hardcoded colors outside theme"
grep -rn "isSystemInDarkTheme" android/app/src/main/java/com/stablechannels/app/ui/ \
  | grep -v "ui/theme/Theme.kt" || echo "DARK MODE CLEAN"
```

Expected: both CLEAN. Any hits: fix (they're pattern-table applications), re-compile, amend into the relevant sweep commit via a fixup commit (do NOT rewrite pushed history).

- [ ] **Step 2: Full build + test suite**

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: SUCCESSFUL, zero failures.

- [ ] **Step 3: MD3 machinery audit**

Run the `material-3` skill's audit against the theme layer (`/material-3 audit` referencing `ui/theme/`): verify colorScheme slot sanity, typography non-collisions, shape scale usage, dynamic-color OFF (we ship fixed ink palettes — ensure `dynamicDarkColorScheme`/`dynamicLightColorScheme` are NOT used), and edge-to-edge/insets still correct. Fix findings in theme files only.

- [ ] **Step 4: Manual on-device/emulator pass (user-assisted if no emulator)**

Install debug build; in BOTH theme modes (Settings → Appearance) walk: Home → drag balance bar → Buy flow → Sell flow → Send → Receive → OnChain → History tabs + detail sheets → Settings incl. Backup seed view → Scanner. Confirm: no text/flow changes vs. `main`, money colors only where dollars/bitcoin move, dark override renders correctly on every screen (the old bug). Capture screenshots per family for the PR description.

- [ ] **Step 5: Push to fork (never open a PR)**

```bash
git push -u origin feature/android-ui-revamp
```

Then hand off: report the branch name + summary; the USER opens the PR.

---

## Self-Review (done at plan time)

- **Spec coverage:** token system §3 → Tasks 2–4; components §4 → Tasks 5–6; cleanup §5 → Tasks 7–12 (dark-mode fix is pattern P1 applied in every sweep; dead code + dep in Task 11; monochrome in Task 11); sweep order §6 → Tasks 7–12 exactly; verification §7 → Task 13 (+ contrast/colorblind unit tests in Task 2 + manual pass 13.4); branch hygiene §9 → Task 1. Fonts §3.2 → Task 1+3. ✔
- **Placeholders:** none — every step has concrete code, commands, or an exact pattern-table row.
- **Type consistency:** `SCButtonTone`/`SCCircleButton`/`SCPillButton`/`SCCard`/`CardDepth`/`SectionHeader`/`AmountText`/`AmountStyle`/`DetailRow`/`DetailValueStyle`/`SheetScaffold`/`SegmentedControl`/`StatusBadge`/`StatusKind`/`ScElevation`/`scShadow`/`Sp`/`ScTypography`/`ScTextStyles` names are used identically in Tasks 5–12. SemanticColors field set (12 fields) matches all usages. ✔
- Known deliberate deviations, both noted inline: spec `muted`/`error` hexes adjusted for AA (Task 2 note); `*Container`/`on*` token pairs added for AA-safe filled buttons (Task 2 note).
