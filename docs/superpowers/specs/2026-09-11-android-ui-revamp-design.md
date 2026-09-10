# Android UI Revamp — Design Spec

**Date:** 2026-09-11
**Status:** Approved design, pending implementation plan
**Branch (to be cut):** from `main` — must not carry the uncommitted payment-error WIP on `fix/android-payment-errors-291`
**Refs:** [toneloc/stable-channels#294](https://github.com/toneloc/stable-channels/issues/294), Harshil Tomar's UI system (below), `.superpowers/brainstorm/2149896-1789073302/content/` (approved mockups)

## 1. Motivation & Research Verdict

The Android wallet UI works but has no design system: no Typography, no Shapes, 137 hardcoded
`Color(0x...)` literals outside Theme.kt, 629 hardcoded `.dp` values, and 6 near-identical
"labeled value row" implementations. A whole-app visual revamp establishes a token system and a
shared component library.

Resources evaluated (from issue #294 discussion):

| Resource | Verdict |
|---|---|
| `hamen/material-3-skill` | **Use** — Compose-first MD3 reference + audit. Install as Claude Code plugin during implementation. |
| Harshil Tomar's UI system | **Use** — it *is* the design language (below). |
| Issue #294 (Tony) | **Use** — hard constraint: green/orange reserved exclusively for dollar/bitcoin semantics, colorblind-safe. |
| ui-skills.com | Cherry-pick: `frontend-design` skill (taste layer); playbook items (tabular figures, ≥44px targets, pressed-scale feedback). React/CSS skills skipped. |
| bitcoin.design | Domain reference for wallet patterns (future UX work; informs choices). |
| Appllama MCP + skills | **Rejected** — iOS-only screen library, paid MCP, Expo/RN design skill. Wrong platform/stack. |
| Chani / Blank Spaces | Mood references only (calm, typography-led, dark-first). |

## 2. Locked Decisions

1. **Identity:** Harshil's custom design language on Material 3 machinery (M3 = components/
   theming plumbing, not the visual look). Not MD3-compliant visuals.
2. **Scope:** Visual + cleanup. Same screens, same flows, same text, same behavior. UI layer
   only — `AppState`, services, business logic untouched.
3. **Execution order:** Phase 1 design system → Phase 2 screen sweep → Phase 3 audit.
4. **Primary color:** Ink neutral. Primary actions near-black (light) / near-white (dark).
   The only chromatic colors in the app are the two money colors.
5. **Money colors:** Teal + amber (P1), colorblind-verified. Buy (get sats) = amber,
   Sell (get dollars) = teal. Receive/Send stay neutral ink.
6. **Settings hub section icons:** monochrome ink (no rainbow). Confirmed explicitly.

## 3. Token System

### 3.1 Color tokens (light / dark)

| Token | Light | Dark | Role |
|---|---|---|---|
| `bg` | `#F5F5F7` | `#0C0C0F` | Screen background |
| `surface` | `#FFFFFF` | `#19191F` | Cards, sheets, circles |
| `text` | `#101014` | `#F5F5F7` | Primary text (= M3 onSurface) |
| `muted` | `#75757E` | `#8F8F99` | Secondary text (= onSurfaceVariant) |
| `strong` | `#2B2B33` | `#E4E4E9` | Icons, glyphs |
| `track` | `#E8E8EC` | `#26262C` | 1px dividers, sliders track |
| `primary` (ink) | `#101014` | `#F5F5F7` | Primary buttons (= M3 primary) |
| `onPrimary` | `#FFFFFF` | `#0C0C0F` | Text on primary |
| `usdGreen` solid | `#0D9488` | `#2DD4BF` | Balance bar, fills (USD/stable side) |
| `usdGreen` text | `#0F766E` | `#2DD4BF` | Teal text on light surfaces (AA-safe) |
| `btcOrange` solid | `#F59E0B` | `#FBBF24` | Balance bar, fills (bitcoin side) |
| `btcOrange` text | `#B45309` | `#FBBF24` | Amber text on light surfaces (AA-safe) |

Status colors (`error`, `warning`, `info`) remain functional exceptions — reserved for
error/warning states only, never decoration. The `success` slot is kept but mapped to the
`usdGreen` family values (one green, not two).
Existing `SemanticColors`/`LocalSemanticColors` structure is kept, values retuned; money-color
exclusivity is enforced by using them nowhere else.

### 3.2 Typography (Inter Tight)

| Role | Size/Line | Weight | Tracking | Notes |
|---|---|---|---|---|
| title | 22/28 | SemiBold | −0.7sp | Screen titles (= M3 headlineMedium) |
| section | 19/24 | SemiBold | −0.6sp | Section headers (= titleLarge) |
| body | 14.5/18 | Regular | +0.3sp | Default text (= bodyLarge) |
| caption | 12.5/16 | Regular | +0.2sp | Secondary labels, tab labels |
| amount | 28/34 | SemiBold | −0.9sp | Hero balance, `tnum` |
| amount-sm | 15/20 | Medium | −0.2sp | Row amounts, `tnum` |
| mono | 12.5/17 | Regular | — | Platform monospace: addresses, txids, seed |

Fonts: Inter Tight static TTFs (400 Regular, 500 Medium, 600 SemiBold) bundled in `res/font/`
(Google Fonts, OFL — include the license file). Amounts use `fontFeatureSettings = "tnum"` so
rolling digits and column-aligned numbers don't jitter.

### 3.3 Shape, spacing, elevation

- **Radii:** 12dp (buttons, inputs, chips) · 16dp (cards) · 18dp (sheets, modals) · 50% pills.
  Mapped to `MaterialTheme.shapes` small/medium/large.
- **Spacing tokens:** 4 / 8 / 12 / 16 / 24dp (+ 32 for section gaps); 16dp screen padding —
  matches today's de-facto values, formalized.
- **Elevation:** layered shadow recipes (no borders on cards, ever):
  - Level 1 (subtle card): `0 1px 2px ·04 / 0 4px 10px ·05 / 0 14px 28px ·05` (black alphas)
  - Level 2 (raised): heavier spread; Level 3 (floating: nav pill, FABs): heaviest
  - Dark mode uses stronger black alphas; interactive surfaces add a 1px inset top highlight
    (white α .95 light / α .07 dark)
  - Implemented as a `Modifier.scShadow(level)` helper
- **Dividers:** 1px `track` color only.

## 4. Component Library (`ui/components/`)

| Component | Replaces | Notes |
|---|---|---|
| `SCButton` | ad-hoc `ActionButton` + inline buttons | Circular icon (40–46dp) + pill variants; neutral/teal/amber; inset highlight + layered shadow; pressed-scale feedback |
| `SCCard` | inline Surface/Card calls | Borderless; radius + shadow by depth level |
| `DetailRow` | 6 duplicates (DetailRow ×2, CopyableDetailRow, ChannelDetailRow, SettingsDetailRow, ConfirmRow, AboutRow) | label/value, optional copy, optional mono |
| `SheetScaffold` | ModalBottomSheet block copy-pasted 4× in HomeScreen | Sheet + edge-to-edge + height fraction in one place |
| `SectionHeader` | inline section titles | 19/24 SemiBold −0.6sp |
| `SegmentedControl` | HistoryScreen's iOS-style control | Retuned to tokens |
| `StatusBadge` | HistoryScreen StatusBadge + StatusCapsule | One pill status component |
| `AmountText` | inline tabular text | amount / amount-sm styles + `tnum` |

Preserved (restyled to tokens, not rebuilt): PriceChart (Canvas), RollingDigitText,
`paymentFlash()`, QR scanner overlay, floating nav pill, CurveProgressIndicator.

## 5. Cleanup Folded Into the Sweep

- Migrate 11 `isSystemInDarkTheme()` callers → `LocalDarkTheme` (fixes manual theme-override
  rendering bug). Also fix sheets forcing `containerColor = Color.Black/White` and
  HistoryScreen's background-equality dark detection.
- Delete dead `SettingsScreen.kt` (540 lines) and the unused `compose-charts` dependency.
- Replace hardcoded colors/dp with tokens as each screen is swept; end state: zero raw
  `Color(0x…)` outside theme files.
- Settings hub section icons → monochrome `strong` ink.

## 6. Phase 2 Sweep Order

1. Home + BalanceBar + PriceChart (tokens)
2. Buy / Sell sheets
3. Send / Receive / OnChain / Fund sheets
4. History + Payment/Trade detail sheets
5. Settings hub + 12 sub-screens
6. Scanner, loading/syncing/error/onboarding states

## 7. Verification & Acceptance

- Every new component has `@Preview` in light + dark (repo has 1 today).
- Build green; existing unit tests pass; manual smoke of key flows (send/receive/trade) —
  behavior and all text unchanged.
- Zero raw `Color(0x…)` outside theme files; no `isSystemInDarkTheme()` outside the theme layer.
- material-3-skill audit pass on the M3 machinery; WCAG AA contrast for text roles; final
  colorblind (deuteranopia/protanopia) check of teal/amber in situ. Color is never the only
  signal — glyphs/labels always accompany it.
- Screenshot pass per screen family, both modes.

## 8. Out of Scope

Behavior/flow/text changes, navigation restructure (no NavHost migration, sheets stay sheets),
iOS/desktop apps, new features, QR-stack consolidation (zxing generates, ML Kit scans — both
needed), performance work, i18n.

## 9. Implementation Notes & Risks

- **Branch hygiene:** cut the revamp branch clean from `main`; the current
  `fix/android-payment-errors-291` has unrelated uncommitted changes. At branch creation,
  audit the skip-worktree list (demo-config files) so demo constants don't leak into commits
  or get clobbered by the sweep.
- **Never create PRs** — branch pushed to fork only; user opens PRs.
- Large-file sweeps (SendScreen 839 lines) get extra care + manual smoke.
- Commit style per user rules: title-only messages, no co-author/issue refs.
