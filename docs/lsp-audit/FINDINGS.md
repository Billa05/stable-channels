# LSP Audit: Findings

Living document. See [`PLAN.md`](./PLAN.md) for scope, method and severity scale. Base: `main` @ `9aff5ec`.

## Status

| Phase | State |
|---|---|
| 1. PR and issue history | in progress |
| 2. Architecture map | first draft below |
| 3. Code review A–H | in progress |
| 4. Validation | not started |
| 5. Synthesis | not started |

## Summary

_Filled in at the end._

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

_Findings are added here as they are confirmed, highest severity first._

## Fix plan

_Filled in during phase 5._
