# LSP Audit: Plan

Status: **in progress**. Findings land in [`FINDINGS.md`](./FINDINGS.md) as they are confirmed, so you can follow along commit by commit.

| | |
|---|---|
| Branch | `claude/great-turing-ad4cz4` |
| Base | `main` @ `9aff5ec` (Merge PR #399). `origin/main` (Billa05 fork) and `upstream/main` (`toneloc/stable-channels`) are the same commit, checked 2026-10-11. |
| Issue/PR source | `toneloc/stable-channels` (upstream). The fork has no issues or PRs of its own. |
| Type | Read-only audit. No production code changes on this branch. |

## 1. Goal

Answer three questions about the Stable Channels LSP:

1. **Is it safe for the LSP?** No path lets a user, a peer, or a network attacker drain LSP funds: double top-ups, replayed or forged trades, price manipulation, unauthenticated or under-authenticated endpoints, griefing that makes the LSP overpay.
2. **Is it safe for the user?** No path silently cuts a user's USD target or backing: wrong deductions, lost settlements, desync between the LSP's book and the wallet's book.
3. **Is it smooth and robust?** Every money-moving flow is idempotent, crash-consistent, and correctly retried; restarts, reordered events, partial failures and concurrency do not corrupt state.

The output is a list of what we do right, what we do wrong (bugs and design issues), and a prioritized fix plan.

## 2. Scope

**In scope (the LSP and everything that decides what the LSP pays or books):**

| Area | Files |
|---|---|
| LSP daemon core | `server/stable-channels-lsp/src/` (`stable_manager.rs`, `stability_tick.rs`, `event_loop.rs`, `payment_filter.rs`, `messages.rs`, `trade_response_retry.rs`, `channel_close.rs`, `channel_audit.rs`, `backfill.rs`, `state.rs`, `main.rs`) |
| API surface and auth | `auth.rs`, `tls.rs`, `config.rs`, `handlers/*`, `push/*`, `geoip.rs`, `observability.rs` |
| Shared library the LSP links | `src/stable.rs`, `src/stabilization.rs`, `src/trade.rs`, `src/ledger.rs`, `src/db.rs` and `src/db/*`, `src/price_feeds.rs`, `src/audit.rs`, `src/types.rs`, `src/constants.rs` |
| Wire protocol | `server/sc-protos/`, `server/sc-rest-client/`, the TLV/keysend messages (`TRADE_V1`, `SYNC_V1`, `STABILITY_PAYMENT_V1`, `TRADE_REJECTED_V1`, register-push) |
| Wallet counterparts | Only where they decide what the LSP books or pays: Android `StabilityPaymentProtocol.kt` / `TradeProtocol.kt` / `StabilityService.kt` / `TradeService.kt`, iOS `NotificationService/Services/*` and `StabilityService.swift` / `TradeService.swift`, desktop `src/user.rs` |
| Operator surface | `server/lsp-server-gui/` and `umbrel/` only for auth, secrets and dangerous operator actions |

**Out of scope:** wallet UI, charts, theming, on-chain-only wallet UX, LDK Server internals (we treat it as a trusted dependency and only audit how we call it).

## 3. Method

### Phase 0: Setup (done)
- Branch is on latest `main`; upstream `main` checked and identical.
- Inventory: 74 PRs and ~45 issues upstream touch the LSP or LSP accounting.

### Phase 1: History review (PRs and issues)
- Read every LSP-related PR and issue upstream, open and closed.
- Build a catalog: what each fixed or reported, whether the fix is on `main`, and which **bug class** it belongs to.
- Bug classes we expect to see repeat: send-before-record windows, pending vs settled confusion, event ordering at startup, accounting on capacity changes, signed-message matching, retry/idempotency gaps, persistence ordering (memory updated before DB).
- Output: `PR-ISSUE-CATALOG.md`, plus findings for any open issue that is still reproducible on `main`.

### Phase 2: Architecture map
- Components, state owned by each, and the money-moving state machines:
  1. Trade (buy/sell) with signed quotes, `TRADE_V1`, rejections, response retry.
  2. Stability tick: LSP pays user (top-up) and user pays LSP (pay-back).
  3. `SYNC_V1` after forwards, splices, closes; retry on delivery failure.
  4. Channel lifecycle: JIT open, channel ready, splice in/out, cooperative and force close.
  5. Push wake for offline users (APNs/FCM) and settlement after wake.
  6. Startup reconcile and backfill.
- Output: architecture section in `FINDINGS.md` and the list of invariants we check against.

### Phase 3: Deep code review, by subsystem
Each subsystem is reviewed against the goal questions. Reviewers trace realistic paths from a real input (a keysend, an HTTP request, a restart, an LDK event) to the money effect.

| # | Subsystem | Main questions |
|---|---|---|
| A | Stability engine (`stability_tick.rs`, `run_tick`, top-up and pay-back paths) | Can the LSP pay twice, overpay, or pay on a stale price? Is a top-up recorded before or after the send? |
| B | Trades (`handle_trade_payment`, `handle_trade_message`, rejections, `trade_response_retry.rs`) | Replay, fee and amount binding, partial-failure states, refund paths. |
| C | Payment events and accounting (`event_loop.rs`, `payment_filter.rs`, `handle_payment_received`, `handle_payment_forwarded`, signed stability payments) | Pending vs settled, attribution of payments to the right channel, deductions from backing, unsigned and legacy payloads. |
| D | Channel lifecycle (`handle_channel_ready`, splices, `channel_close.rs`, `channel_audit.rs`, `backfill.rs`) | Ordering at startup, capacity changes, closes, reserve handling. |
| E | Persistence and crash consistency (`src/db.rs`, `persist_pending_book_update`, ledger) | Memory vs DB ordering, transactions, corrupt-row handling, migrations. |
| F | API surface and auth (`auth.rs`, `tls.rs`, `handlers/*`, `register_push.rs`, `push/*`, `config.rs`) | Unauthenticated routes, HMAC details, replay, DoS, operator-only actions reachable by wallets, secrets on disk. |
| G | Price oracle (`price_task.rs`, `src/price_feeds.rs`) | Manipulation, staleness, outlier handling, what happens when feeds disagree or fail. |
| H | Wire protocol and wallet counterparts | Signatures, domain separation, versioning, legacy fallbacks that weaken checks, LSP/wallet book divergence. |

### Phase 4: Validate
- Every finding is checked against the code on `main` with a concrete failure path (inputs, state, wrong result).
- Findings that duplicate an open issue are linked to it; open PRs that would fix them are noted.
- Run the existing test suites where possible (`cargo test -p stable-channels-lsp`, root crate tests) to record baseline.

### Phase 5: Synthesis
- **What we do right:** design choices worth keeping.
- **What we do wrong:** bugs and design issues, by severity.
- **Fix plan:** ordered list, grouped so related fixes land together.

## 4. Severity scale

| Severity | Meaning |
|---|---|
| **Critical** | LSP funds can be drained, or user funds lost, by an outside party or a routine event. |
| **High** | Wrong money movement or wrong booking under realistic conditions (restart, offline user, retry), or a security control that can be bypassed. |
| **Medium** | Wrong state that self-heals or needs operator action; DoS of a non-critical path; defense-in-depth gap on a money path. |
| **Low** | Edge cases with small impact, robustness or observability gaps. |
| **Info** | Design notes, good practice, things to keep. |

## 5. Finding template

```
### LSP-NNN: <one-line title>
- Severity: Critical | High | Medium | Low | Info
- Area: A–H
- Where: path:line
- Related: upstream issue/PR links
- Status on main: open | fixed by #NNN | partially fixed
- What happens: <the failure path: inputs, state, wrong result>
- Why it matters: <who loses what>
- Fix: <what to change>
```

## 6. Deliverables (all in `docs/lsp-audit/`)

| File | Content |
|---|---|
| `PLAN.md` | This plan and the progress log below. |
| `FINDINGS.md` | Living findings file: architecture, invariants, what is right, what is wrong, fix plan. |
| `PR-ISSUE-CATALOG.md` | Every LSP-related PR and issue upstream, with status and bug class. |

## 7. Progress log

| Date | Step |
|---|---|
| 2026-10-11 | Phase 0 done. Plan committed. |
| 2026-10-11 | Architecture map and invariants added to FINDINGS. |
| 2026-10-11 | Phase 1 done: PR-ISSUE-CATALOG.md. Baseline tests green. First two findings (LSP-001, LSP-002). |
| 2026-10-11 | Reviews A, B, F merged into FINDINGS: 28 more findings (LSP-003 to LSP-030), 6 High. Raw notes in reviews/. |
| 2026-10-11 | Review D merged: LSP-031 to LSP-040 (3 High: startup race resets targets to $0, missed startup reconcile, voluntary pay-back). |
| 2026-10-11 | Review E merged: LSP-041 to LSP-047 and a crash-window table; LSP-010 raised to High. |
| 2026-10-11 | Review G/H merged: LSP-048 to LSP-052, protocol message table; LSP-025 and LSP-026 raised to Medium. |
