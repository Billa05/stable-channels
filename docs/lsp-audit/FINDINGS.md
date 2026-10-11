# LSP Audit: Findings

Living document. See [`PLAN.md`](./PLAN.md) for scope, method and severity scale. Base: `main` @ `9aff5ec`.

## Status

| Phase | State |
|---|---|
| 1. PR and issue history | done: [`PR-ISSUE-CATALOG.md`](./PR-ISSUE-CATALOG.md) |
| 2. Architecture map | first draft below |
| 3. Code review A–H | in progress |
| 4. Validation | not started |
| 5. Synthesis | not started |

## Summary

_Filled in at the end._

## Baseline

- `cargo test -p stable-channels-lsp --locked` on `main`: **322 passed, 0 failed**.
- `cargo test -p stable-channels -p sc-protos -p sc-rest-client --lib --no-default-features --locked` on `main`: **273 passed, 0 failed, 2 ignored**. (Run with `BITCOIND_SKIP_DOWNLOAD=1`: the sandbox blocks the `bitcoind` download that the regtest dev-dependency does at build time; unit tests do not use it.)

## What the PR and issue history tells us

Full table in [`PR-ISSUE-CATALOG.md`](./PR-ISSUE-CATALOG.md). About 45 LSP issues and 74 LSP PRs upstream fall into ten bug classes. The same few classes keep coming back:

| Class | Count | Examples | Root cause |
|---|---|---|---|
| Pending vs settled | 6 issues, 5 PRs | #324, #392, #414, #401, #395, #402, #400, #415 | The two-tick **backstop** compares books to LDK's live balance. That balance leaves out in-flight HTLCs and includes the channel reserve, and the backstop makes a **permanent** `expected_usd` cut. There is no "pending" state, so every new flow (user send, top-up, pay-back, splice) hits the same gap. |
| Send before record | 6 | #392, #411, #323, #240, #47 | Keysends are side effects with no durable intent row written first. Mobile moved to claim-before-send; the LSP did not. Same shape for SYNC, correction SYNC and the trade-fee refund. |
| Persistence ordering | 8 | #321, #296, #311, #318, #319, #38, #416 | In-memory list is the working state and the DB trails it. Fixed path by path (#326) rather than structurally. |
| Startup and event ordering | 7 | #413, #155, #266, #285, #319 | Event loop and tick start before the channel list is loaded. |
| Capacity changes (splice, close, reserve) | 12 | #187, #311, #372, #401, #413, #163 | Splices reach the daemon only as a re-fired `ChannelReady`; each capacity change was patched separately. |
| Signed-message matching and channel identity | 11 | #231 (exploit), #161, #233, #314, #414, #232 | Several channel identifiers; legacy unsigned paths attributed by balance-drop heuristics. |
| Retry, idempotency, backoff | 10 | #323, #205, #266, #336, #401, #376 | Each message type grew its own retry machinery; failed settlements have no backoff. |
| Auth and DoS | 8 | #108, #162, #87, #230, #145 | Compatibility shims reopened holes earlier PRs closed (#108 → #160 → #162). |
| Client state loss → force close | 5 | #347, #348 | Wallet-side, but the LSP pays the on-chain cost. |
| Cross-implementation drift | 8+ | #149, #296, #318, #320 | The same allocation math is written 5+ times (LSP, desktop, Kotlin app and service, Swift app and NSE). |

**Open LSP issues vs the open PRs:**

| Issue | #400 | #415 | Note |
|---|---|---|---|
| #392 unclaimed top-up cut as a spend | fixes | helps | Residual window if the claim lands but the balance has not updated yet. |
| #324 pending payment cut as a spend | no | fixes | Forward and splice-confirm handlers still read the live balance. |
| #411 top-up send before record | no | no | #400 keeps send-then-record. |
| #413 ready-before-load resets USD target to $0 | no | no | Neither PR touches `handle_channel_ready`. |
| #414 unsigned pay-back after claim | no | no | Goes away when the legacy path is removed (#235/#284). |
| #416 one bad in-flight row pauses all top-ups | **introduced** | no | Must land with #400. |
| #401 reserve counted as backing | no | no | Plus no backoff on failed settlements. |
| #232 settlement re-anchors to LSP price | no | no | `.max(equilibrium)` still at `src/stable.rs:785`. |
| #235/#284 legacy removal, #163 splice concurrency, #136 reserve | no | no | |

**Important context:** per the spec issue #234, the production front daemon runs branch `sc/front-daemon-ldk-server-3e9a07d6` @ `3c4d5e9c`, not `main`. That build lacks #326, #332, #344, #345, #349 and #378 (save-before-publish, SYNC retry, the surplus guard, and more). This audit is of `main`; production is behind it.

## Architecture and invariants

### Components

```
 Wallets (Android / iOS / desktop)          Operator GUI (Dioxus desktop / WASM)
   │  keysend + custom TLVs                    │  REST, X-Auth = HMAC(api_key, body), pinned TLS
   │  (TRADE_V1, STABILITY_PAYMENT_V1,          │
   │   SYNC_V1, TRADE_REJECTED_V1)              ▼
   │  /api/register-push, /api/channel-exists ─► stable-channels-lsp (axum, :3002)
   ▼                                             ├─ auth middleware (HMAC) on every route except the two /api/* ones
 Lightning p2p ◄──► LDK Server (vanilla) ◄─gRPC─┤─ proxy handlers → LDK Server (incl. sends, opens, closes, splices)
                     LDK Node + BDK + LSPS2      ├─ event_loop: LDK event stream → StableChannelManager
                                                 ├─ stability_tick: every 60s → run_tick → top-ups, SYNC retries, wake pushes
                                                 ├─ trade_response_retry, revenue, observability tasks
                                                 ├─ price_task → watch channel (BTC/USD)
                                                 └─ sqlite (src/db.rs): stable books, pending ops, ledger, checkpoints
```

### How state is shared
- One `StableChannelManager` behind one `tokio::sync::Mutex` (`server/stable-channels-lsp/src/state.rs:30`). The event loop, the stability tick, the wake watcher, REST edit handlers and startup reconcile all take this lock. This makes the manager a single writer, which is good for consistency; the reviewers are checking whether the lock is held across LDK Server network calls.
- sqlite through the shared `Database` handle (`src/db.rs`), also used directly by the event loop for settlement and trade-response status updates (`event_loop.rs:489-607`).

### Startup order (`server/stable-channels-lsp/src/main.rs:85-215`)
1. TLS config, api key, LDK Server client, DB open, legacy audit import.
2. Persist an event-stream "gap" checkpoint before any task starts.
3. Spawn price task.
4. Spawn startup reconcile, which **waits up to 60s for a price** before calling `reconcile_from_grpc`.
5. Spawn event loop, stability tick, trade-response retry, revenue, observability **immediately**, without waiting for step 4.
6. Serve REST.

Steps 4 and 5 race: LDK events and ticks can be processed before the manager has reconciled against LDK Server's channel list. Upstream issue #413 is one consequence; the lifecycle reviewer is checking for others.

### Event → handler map (`server/stable-channels-lsp/src/event_loop.rs:400-636`)

| LDK event | Handler | Money effect |
|---|---|---|
| `ChannelStateChanged(Ready)` | `handle_channel_ready` | Creates/updates stable record; splice accounting on re-fire after splice. |
| `ChannelStateChanged(Closed)` | `handle_channel_closed` | Closes the stable record. |
| `PaymentReceived` | `handle_payment_received` | Trades, signed stability pay-backs, legacy pay-backs (by custom TLVs). |
| `PaymentForwarded` | `handle_payment_forwarded` | Deducts stable USD when a user's outgoing payment overflows native sats; sends `SYNC_V1`. Uses only the **first** HTLC of `prev_htlcs`/`next_htlcs` for attribution. |
| `PaymentSuccessful` | DB: `mark_trade_response_delivered`, `mark_settlement_succeeded` | Confirms LSP-sent trade replies and top-ups. |
| `PaymentFailed` | DB: `mark_sync_payment_failed`, `mark_trade_response_failed`; `handle_failed_stability_payment` | Rolls back a failed top-up. |
| `PaymentClaimable`, `SpliceNegotiated`, `SpliceNegotiationFailed` | audit only | none |

### Money-moving flows under review
1. **Trade:** wallet keysend with `TRADE_V1` → LSP validates quote, fee, amounts, replay → books new `expected_usd` → signed reply (or `TRADE_REJECTED_V1`), reply retried until delivered.
2. **Top-up (LSP pays):** tick sees user below par → LSP keysends `STABILITY_PAYMENT_V1` → book on success, roll back on failure.
3. **Pay-back (user pays):** tick sees user above par → LSP waits (or wakes the wallet via push) → wallet keysends signed settlement → LSP books it.
4. **Forward deduction + SYNC:** user's outgoing payment overflows native → LSP cuts `expected_usd` → `SYNC_V1` to wallet, retried on failure.
5. **Splice in/out, close:** capacity changes booked when the spliced channel re-fires Ready; closes end the record.
6. **Wake:** offline user with pending settlement → APNs/FCM push → watch for reconnect → settle.

### Invariants the audit checks
1. **No double pay:** each drift amount is paid at most once, across retries, restarts and failures.
2. **Record before or atomically with send:** any LSP outbound payment has a durable intent row keyed by an idempotency key before the send.
3. **Settled only:** only settled (not pending, not failed) payments change a stable book.
4. **Attribution:** every booked payment is matched to exactly one channel and one purpose (trade, settlement, ordinary).
5. **Memory follows disk:** in-memory books change only after the DB write succeeds; SYNC carries only persisted values.
6. **Bounded exposure:** per-payment, per-channel and per-tick caps on what the LSP can pay, and a sane price.
7. **Authenticated control:** only the operator can reach money-moving REST routes; wallets act only through signed messages bound to their own channel.
8. **Converging books:** LSP and wallet books converge after any interruption, with the LSP as the authority.

## What we do right

_Filled in as reviews complete._

## What we do wrong

IDs are assigned in the order findings are confirmed and stay stable. The table is sorted by severity.

| ID | Severity | Area | Title | Upstream |
|---|---|---|---|---|
| LSP-001 | Medium | F | Unauthenticated legacy `/api/register-push` lets anyone take over a node's wake pushes | #162 (closed, still open on main) |
| LSP-002 | Medium | H | A signed settlement whose `expected_usd` disagrees with the LSP's is applied, not refused | #296, #299 |

### LSP-001: Unauthenticated legacy `/api/register-push` lets anyone take over a node's wake pushes
- Severity: Medium
- Area: F (API surface)
- Where: `server/stable-channels-lsp/src/main.rs:285-291` (route mounted after the auth layer), `server/stable-channels-lsp/src/handlers/register_push.rs:99-134`, `server/stable-channels-lsp/src/push/tokens.rs:73-79` (newest `last_seen` wins)
- Related: #162 (closed as tracked, but the route is still live), #108 added the signed `REGISTER_PUSH_V1` and #160 re-added this unsigned route.
- Status on main: open.
- What happens: anyone who can reach the daemon posts `{"device_token": "<attacker token>", "platform": "...", "node_id": "<victim node id>"}` to `/api/register-push`. No HMAC and no node signature are checked. `load_token_for_node` returns the newest row for that node, so every later wake push for the victim goes to the attacker's device. The push carries the direction (`lsp_to_user` or `user_to_lsp`), so the attacker learns when the victim has a pending settlement and in which direction. The provider accepts the push, which starts the per-node cooldown, so the victim's real device is not woken either.
- Why it matters: an offline victim is never woken to receive a top-up or to pay back, so their stable position drifts unsettled (#376 depends on wakes). It also leaks settlement timing per node.
- Fix: remove the legacy route once deployed wallets sign registrations, or until then accept a legacy registration only for a node id that has no signed registration on file, and never let an unsigned row override a signed one.

### LSP-002: A signed settlement whose `expected_usd` disagrees with the LSP's is applied, not refused
- Severity: Medium
- Area: H (protocol)
- Where: `server/stable-channels-lsp/src/stable_manager.rs:1762-1777`
- Related: #296 (the LSP accepted an $8.53 overpayment at 16% divergence from a buggy Android build), #299 (asked for an LSP-side rejection; only the wallet fix landed).
- Status on main: open.
- What happens: `handle_signed_stability_payment` compares the signed `expected_usd` with its own, and on mismatch only writes `STABILITY_PAYMENT_STATE_DIVERGENCE` to the audit log, then applies the payment. `backing_after_user_to_lsp_stability` (`src/stable.rs:762-795`) floors backing at the LSP's equilibrium, so any amount above the LSP's own computed surplus comes out of the user's native sats and stays with the LSP. When a wallet bug makes it pay a surplus that does not exist, the LSP keeps the sats.
- Why it matters: the signature already proves the wallet's view of the books; a large divergence is strong evidence of a wallet bug, and the user loses the overpayment. Keeping it also hides the bug from both sides.
- Fix: LDK Server claims keysends before the daemon sees them, so the daemon cannot fail the HTLC. Instead, above a tolerance (for example 1% or $0.50), refund the part of the amount above the LSP's own computed surplus (`amount - (backing - equilibrium)`) with a keysend recorded before sending, and send a correction `SYNC_V1`. Keep the audit row.

## Fix plan

_Filled in during phase 5._
