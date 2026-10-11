# LSP Audit: Findings

Living document. See [`PLAN.md`](./PLAN.md) for scope, method and severity scale. Base: `main` @ `9aff5ec`.

## Status

| Phase | State |
|---|---|
| 1. PR and issue history | done: [`PR-ISSUE-CATALOG.md`](./PR-ISSUE-CATALOG.md) |
| 2. Architecture map | first draft below |
| 3. Code review A–H | done (all eight areas) |
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
| `PaymentForwarded` | `handle_payment_forwarded` | Deducts stable USD when a user's outgoing payment overflows native sats; sends `SYNC_V1`. Attribution uses the first `prev_htlcs` locator's `user_channel_id` and the event total. The payment-events review checked this is correct for ordinary forwards (including MPP parts and multi-channel users); it would need per-HTLC amounts only if trampoline forwards are enabled. |
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

These are design choices worth keeping. Each was checked in the code.

**Concurrency and state**
- **One writer for the books.** Every money path (tick, wake pass, LDK events, REST edits, startup reconcile) runs under one `tokio::sync::Mutex<StableChannelManager>`. Ticks cannot overlap, and a tick cannot race a trade or a forward. Events are read by a separate task into a buffer, so a long hold does not drop them (`state.rs:30`, `event_loop.rs:32-75`).
- **Save before publish (#326).** For trades, signed settlements and forward deductions, the DB is written first, memory only after commit, and SYNC only carries saved values. A failed save is queued in `pending_book_updates` and later events for that channel wait behind it (`stable_manager.rs:385-458`).

**Trades**
- **The LSP computes the allocation itself.** The signature is checked against the counterparty LDK reports for the named channel. The new allocation uses the LSP's own price and LDK's live balances; nothing from the message is booked as-is (`stable_manager.rs:3074-3108`, `3296-3310`).
- **One atomic decision per trade.** An acceptance is one transaction with the channel update, with duplicate and version checks (`src/db.rs:1513-1565`). A trade cannot be both accepted and rejected. Rejections are signed, stored, use a closed reason list, and are re-sent after a crash (`stable_manager.rs:545-690`).
- **Fee checked against what arrived.** The fee is checked against the amount actually received, and a quote more than 0.5% from the LSP price is rejected.

**Settlements**
- **Signed pay-backs are tightly bound.** `STABILITY_PAYMENT_V1` binds amount, direction, channel, expiry and signer. It is written to a durable inbox before any booking, cannot be replayed (primary key plus compare-and-swap), and books atomically (`stable_manager.rs:1500-1950`).
- **A settlement moves backing by its own amount only (#402).** A lower live balance is left for its own event and is never written off by this payment (`src/stable.rs:762-795`).
- **The LSP never pulls funds.** An unpaid surplus stays on the books as a debt; it is not taken.

**Price**
- **Price for money decisions is guarded.** It needs 3 agreeing feeds, at most a 10% move per refresh and at most 60 s age, and is quarantined after a large move. A stale or quarantined price stops the tick.

**API surface**
- **No wallet holds the admin key.** Android and iOS only call two unauthenticated JSON routes (`/api/register-push`, `/api/channel-exists`); the desktop wallet never calls the REST API. The README line that wallets are "equal clients of the same REST surface" is not true in the code, which is the safe outcome.
- **HMAC done carefully.** Constant-time compare and a ±60 s timestamp window (`auth.rs:41-76`). Oversized bodies are rejected before the HMAC read, plus a router-wide body cap (`auth.rs:115-131`, `main.rs:300`).
- **Signed `RegisterPush` is well formed.** It has a type tag, a 300 s freshness window and fails closed. On Umbrel, nginx forwards `/api/` without adding a credential, so the GUI web page is not an open proxy by itself.
- **Secrets on the LDK hop are separate.** The SC daemon uses LDK Server's own api key and pinned cert, independent of its own.

**Testing**
- 322 LSP unit tests and 273 shared-library tests pass on `main`, and many are regression tests named after real incidents.

## What we do wrong

IDs are assigned in the order findings are confirmed and stay stable. The table is sorted by severity. **Confirmed** means traced end to end in the code; **Plausible** means one step is unverified (named in the entry). Raw reviewer notes are in [`reviews/`](./reviews/); this file is the checked summary.

| ID | Severity | Area | Title | Upstream | Open PRs |
|---|---|---|---|---|---|
| LSP-003 | **High** | A | A top-up is sent before it is recorded; if the record fails, the same top-up is sent again every 120 s | #411 | not fixed by #400 |
| LSP-004 | **High** | A | A top-up whose send errors after LDK already dispatched it is sent again 60 s later (Plausible) | #411 class | not fixed |
| LSP-005 | **High** | A | An unclaimed top-up is booked at send, then cut from the user's USD target as a spend | #392 | fixed by #400 |
| LSP-006 | **High** | F | The Umbrel web GUI sends the full admin key, and every signed request, over plain LAN HTTP (Plausible) | #192, #210 | n/a |
| LSP-007 | **High** | B | A trade that never reaches a stored decision keeps the 1% fee, gets no answer, cannot be refunded, and blocks trading on that channel | #205, #240 | not fixed |
| LSP-008 | **High** | B | Every rejection keeps the full fee; a wallet with a stale base state pays for repeated `invalid_fee` rejections and gets no corrective SYNC | #296, #240 | not fixed |
| LSP-031 | **High** | D | At startup, a channel-ready event handled before the channel list is loaded overwrites a tracked channel with a $0 target | #413 | not fixed |
| LSP-032 | **High** | D | If the startup reconcile is missed, one channel-ready makes the list non-empty and every other channel stays unloaded until the stream reconnects | #413 | not fixed |
| LSP-033 | **High** | A/H | Paying the LSP is voluntary: a modified wallet can take every top-up and never pay back, then close or splice out (design) | #344, #401 | not fixed |
| LSP-010 | **High** | A/E | A top-up failure missed during an event-stream gap is never rolled back, so the backstop later cuts the user's target | — | fixed by #400 |
| LSP-041 | **High** | C/E | A user's signed pay-back that arrives during an event-stream gap, or whose inbox write fails, is never booked; the backstop then cuts their target, so they pay twice | — | not fixed |
| LSP-053 | **High** | C | The forward handler counts another still-pending payment from the user as spent, so the deduction stays after that payment fails | #324 | not fixed (#415 covers only the backstop) |
| LSP-001 | Medium | F | Unauthenticated legacy `/api/register-push` lets anyone take over a node's wake pushes | #162 | n/a |
| LSP-002 | Medium | H | A signed settlement whose `expected_usd` disagrees with the LSP's is applied, not refused | #296, #299 | not fixed |
| LSP-009 | Medium | F | The HMAC covers only timestamp and body: requests can be replayed for 60 s, and to a different route | #87 | n/a |
| LSP-011 | Medium | A | A top-up meant for one channel can land on the same peer's other channel and is still booked on the first | — | not fixed |
| LSP-012 | Medium | A/E | The manager lock is held across every LDK Server call, and those calls have no timeout | — | made worse by #400, #415 |
| LSP-013 | Medium | A | No cap on top-up size or rate; the only limit is a cooldown kept in memory and reset on every reconnect; no backoff after failures | #401 | partly (#400 in-flight guard) |
| LSP-014 | Medium | A | The channel reserve counts as stable backing, so a pay-back owed to the LSP can be unpayable | #401, #136 | not fixed |
| LSP-015 | Medium | A | An unsigned pay-back right after a top-up claim is not matched, so the user's target is cut | #414 | not fixed |
| LSP-016 | Medium | F | Anonymous clients can fill the disk through the two unauthenticated routes | #162 | n/a |
| LSP-017 | Medium | F | The api key file is world-readable and written non-atomically, and an empty key file is accepted | #345 | n/a |
| LSP-018 | Medium | F/B | Any Lightning peer can make the LSP write ledger rows and run LDK calls under the main lock for 1 msat; the 20/s limit from #230 is not on main | #230 | not fixed |
| LSP-019 | Medium | F | The REST listener has no timeouts, connection limits or concurrency limits (Plausible) | — | n/a |
| LSP-020 | Medium | B | If a payment lookup fails, the signed trade result carries the wrong id and the wallet discards an acceptance the LSP has booked (Plausible) | — | not fixed |
| LSP-021 | Medium | B | A trade made while a top-up is in flight builds on the unlanded top-up; if the top-up fails, the rollback misses | — | fixed by #400 |
| LSP-022 | Medium | B | Trade results reach offline users only on a timer; nothing fires on reconnect, and an accepted trade is dropped after 14 days with no SYNC fallback | — | not fixed |
| LSP-034 | Medium | D | An operator edit before the list is loaded resets the user's target to $0 | #413 | not fixed |
| LSP-035 | Medium | D | `EditStableChannel` overwrites the books: it erases drift, writes backing 0 on a cold price (LSP over-pays), and never SYNCs the wallet | #225 | partly |
| LSP-036 | Medium | D | The backstop reads pending-splice balances as spends: an LSP splice-in books a phantom spend, and a splice-out is deducted before it confirms with no undo (Plausible) | #187, #285 | not fixed |
| LSP-037 | Medium | D | A channel whose channel-ready event is missed or fails is never registered as stable | #266 | not fixed |
| LSP-042 | Medium | E | The forwarded-spend path still changes memory before the DB and can SYNC unsaved books (#321 class) | #321, #326 | not fixed |
| LSP-043 | Medium | C/E | Event-loop retries and reconnects throw away every buffered event, and one pending settlement whose lookup keeps failing stops live event handling for every channel | #416 class | not fixed |
| LSP-044 | Medium | E | Migrations ignore every `ALTER TABLE` error and there is no schema version | #38 | made worse by #415 |
| LSP-025 | Medium | A/H | Settlements re-anchor to the LSP's price, which moves real value from user to LSP and can block full exits | #232 | not fixed |
| LSP-026 | Medium | B/H | The legacy trade path (no `trade_id`) is still accepted; `ts == 0` envelopes can be replayed from any node to reset a user's target (Plausible) | #145, #235, #284 | not fixed |
| LSP-048 | Medium | G | Half the responding price feeds can shift the LSP price about 5%, a majority can walk it anywhere in steps, and nothing caps the top-ups that follow | #45, #239 | not fixed |
| LSP-049 | Medium | G | The desktop wallet books LSP top-ups and SYNCs at a stale or quarantined price, losing the hedge on large moves | #232 | not fixed |
| LSP-054 | Medium | C | A dropped forward deduction falls back to the backstop, which any later receive cancels, so the spend is never deducted | #324, #395 | not fixed |
| LSP-023 | Low | A | A channel with a USD target but zero recorded backing values the whole balance as stable | #401 | kept by #400 |
| LSP-024 | Low | A | The unsigned pay-back path changes memory before the DB and ignores a failed save | #414 | not fixed |
| LSP-027 | Low | F | TLS "pinning" adds the self-signed cert to the trust store instead of replacing it | — | n/a |
| LSP-028 | Low | F | A huge `max_lines` in `/LdkLog` pre-allocates enough memory to abort the daemon | — | n/a |
| LSP-029 | Low | F | `EditStableChannel` accepts NaN, infinite and negative targets and a cold price, with no SYNC to the wallet | — | n/a |
| LSP-030 | Low | F | The trade-fee refund preimage can be derived from public data (Plausible) | #378 | n/a |
| LSP-038 | Low | D | Reconcile soft-closes rows missing from one channel list and never re-opens them | #372 | not fixed |
| LSP-039 | Low | D | A correction that brings the target to $0 is never SYNCed after a restart | #285, #311 | not fixed |
| LSP-040 | Low | D | A stale pending-splice entry halts the tick for every channel | #163 | not fixed |
| LSP-045 | Low | E | One unreadable `channels` row stops the daemon from starting; corrupt money cells read as 0 or huge values | #416 class | not fixed |
| LSP-046 | Low | E | A half-open event stream still counts as healthy (no keepalive), so a gap is never opened (Plausible) | — | not fixed |
| LSP-047 | Low | E | The ledger is append-only by convention only, and some money audit rows are written outside the transaction that changes the books | — | not fixed |
| LSP-050 | Low | G | The price refresh "in progress" flag is not panic-safe, so one panic stops pricing until restart (Plausible) | — | not fixed |
| LSP-051 | Low | H | The iOS notification extension looks up uncorrelated SYNCs by the LSP's `user_channel_id`, which never matches, and blocks later background events | — | not fixed |
| LSP-052 | Low | H | `/SignMessage` lets any api key holder sign arbitrary bytes with the node key that signs protocol messages | — | not fixed |
| LSP-055 | Low | C | On the first low-balance tick, the regular tick still pays a top-up on backing that includes already-spent sats | #395 | not fixed |

### High

### LSP-003: A top-up is sent before it is recorded; if the record fails, the same top-up is sent again every 120 s
- Severity: **High** (LSP funds leave without limit after a common ops failure)
- Confidence: Confirmed
- Area: A (stability engine)
- Where: `server/stable-channels-lsp/src/stable_manager.rs:2451` (send) → `2464-2501` (record) → `2502-2507` (cooldown set; backing changes only if the record succeeded). The cooldown is memory-only (`stable_manager.rs:3712`) and `reconcile_from_grpc` rebuilds memory on every stream reconnect (`event_loop.rs:300-308`).
- Related: #411. Open PR #400 keeps the same order.
- What happens:
  1. The tick sends a top-up and `spontaneous_send` returns Ok.
  2. `record_stability_settlement_with_rollback` fails (for example a full disk or an I/O error; reads still work), or LDK returns an empty `payment_id`. `persisted = false`.
  3. `sc.backing_sats` is not changed; only the in-memory cooldown is set.
  4. 120 s later the tick computes the same drift from the same backing and pays again. Each extra top-up lands as native sats in the wallet, which never pays it back.
  5. The `PaymentSuccessful` for the unrecorded payment matches no row and is only audited as a plain `PAYMENT_SETTLED` (`event_loop.rs:514-551`). Nothing alarms.
  6. Crash variant: a crash between steps 1 and 2 loses the cooldown, so the first tick after restart pays once more. A stream reconnect resets the cooldown the same way.
- Why it matters: every below-par channel is paid its drift about 30 times an hour until writes recover or LSP liquidity runs out, and the books show nothing.
- Fix: write the intent row (settlement id, channel, amount) before sending, and send with a preimage derived from the settlement id (`SpontaneousSendRequest.preimage`). ldk-node then refuses a second send with the same payment id (`DuplicatePayment`), which makes a resend harmless. If the intent cannot be written, do not send. Until then: stop top-ups for a channel after any failed record, and alarm on an outbound `PaymentSuccessful` that matches no row.

### LSP-004: A top-up whose send errors after LDK already dispatched it is sent again 60 s later
- Severity: **High**
- Confidence: Plausible. Unverified: that the ldk-node rev pinned by LDK Server (`f375e4d`) stores the payment after dispatch like `47dad6d` does (not in the local cache).
- Area: A
- Where: `stable_manager.rs:2513-2529` (the error arm records nothing and leaves the cooldown unset, by design: "so retry can fire").
- What happens: an error can reach the daemon after the HTLC is queued: ldk-node's `send_inner` queues the HTLC and then writes the payment store (`spontaneous.rs:111-135`); or LDK Server restarts or the gRPC stream resets before replying (the client has no timeout and no idempotency key). The daemon audits `STABILITY_PAYMENT_FAILED`; the next tick sends a fresh top-up with a new random preimage, and the first one also completes with no row.
- Why it matters: one double top-up per such error, invisible in the books. There is a test that asserts this retry behavior (`run_tick_send_failure_keeps_cooldown_unset`).
- Fix: same as LSP-003. With a deterministic preimage and an intent row, "unknown outcome" becomes "look up this payment id, then decide".

### LSP-005: An unclaimed top-up is booked at send, then cut from the user's USD target as a spend
- Severity: **High** (user loss)
- Confidence: Confirmed
- Area: A
- Where: `stable_manager.rs:2503-2507` (books `backing_after` at send), backstop `2223-2281`.
- Related: #392. Fixed by open PR #400 (books the top-up when it is claimed).
- What happens: the LSP raises backing as soon as LDK accepts the send. The live balance does not include the unclaimed HTLC, so if the user's wallet is offline for two ticks the backstop reads the gap as a spend, cuts `expected_usd`, and sends a SYNC. A later claim does not restore it. The wake flow from #376 shortens the window to about 61 s.
- Fix: merge #400 (with the #416 fix; see the PR notes below).

### LSP-006: The Umbrel web GUI sends the full admin key, and every signed request, over plain LAN HTTP
- Severity: **High** (full LSP drain if the precondition holds)
- Confidence: Plausible. Confirmed: the GUI fetches the raw key and sends requests over the app proxy's HTTP hop. Unverified: that an attacker on the LAN can also use the Umbrel session cookie (it travels in the same HTTP stream, so likely).
- Area: F
- Where: `server/lsp-server-gui/src/ui/mod.rs:101-118`, `server/lsp-server-gui/src/platform.rs:177-191` (fetches `/setup/key.txt` on every page load), `umbrel/docker/40-provision-gui-api-key.sh:63-81` (writes the hex key there), `umbrel/docker/lsp-gui-nginx.conf.template:35-45`.
- What happens: Umbrel's default LAN access is `http://umbrel.local:<port>`. The `/setup/` allowlist only admits Umbrel's `app_proxy`, which is correct, but the hop from the browser to `app_proxy` is plain HTTP. Anyone passively watching the operator's LAN sees the key in the `/setup/key.txt` response, plus every request's `X-Auth` and body. With the key they can post `/api/OnchainSend` or `/api/SpliceOut`.
- Why it matters: the SC api key unlocks every proxied LDK route (`OnchainSend`, `SpontaneousSend`, `SpliceOut`, `SignMessage`, `ForceCloseChannel`). The README says a leaked SC key "gives the attacker access only to the SC daemon"; in practice that is a full drain, and `SignMessage` also lets the holder forge the LSP's signed messages to wallets.
- Fix: do not serve the raw key to the browser. Sign server-side in a sidecar behind `app_proxy`, or require HTTPS/Tor/Tailscale for the GUI. Split credentials: a read-only key for dashboards and a separate money-moving key (see LSP-009 for the HMAC changes). Mount only the api key file into the GUI container, not the whole data dir with `tls.key` and the DBs (`docker-compose.yml:66-68`).

### LSP-007: A trade that never reaches a stored decision keeps the 1% fee, gets no answer, cannot be refunded, and blocks trading on that channel
- Severity: **High** (user loss and lock-out after routine events)
- Confidence: Confirmed
- Area: B (trades)
- Where: `event_loop.rs:386-387` ("LDK Server does not replay its stream"), `backfill.rs:107-127` (reconnect backfill only writes `PAYMENT_RECONSTRUCTED` audit rows and never re-runs `handle_payment_received`), `stable_manager.rs:3063-3108` (early returns on transient LDK errors), `revenue.rs:668-671` (refund needs a stored rejection). Wallet lock-out: Android `DatabaseService.kt:682-693`, iOS `ChannelRepository.swift:268-272`, desktop `src/db.rs:2253-2257`.
- Related: #205, #240 (two-phase confirmation, closed unmerged).
- What happens: LDK Server claims the trade keysend (and the fee) before the daemon sees it. Nothing is written to an inbox first. Any of these leaves no decision row: the keysend lands while the daemon restarts or its stream reconnects; the daemon crashes mid-trade; a transient `list_channels`, `verify_signature` or `sign_message` error; a DB error while writing the rejection; the channel closes before processing. The operator's `RefundTradeFee` refuses (`NotRejectedTradeFee`). After 15 minutes each wallet marks the trade `uncertain` and refuses every new trade on that channel until a signed result arrives, which never does.
- Why it matters: one LSP restart during a user's trade costs the fee and disables trading on that channel until manual repair on both sides. The signed-settlement path already has a durable inbox; trades do not.
- Fix: write `{payment_id, payment_hash, amount, raw TLV, received_at}` to a `trade_inbox` table as the first action in `handle_payment_received`, and process from it, leaving rows pending on transient errors. For stream gaps (where the TLV is lost), add a signed wallet `TRADE_STATUS_V1` query answered from `trade_decisions`, and turn "received but undecided" into a signed `internal_failure` rejection with an automatic refund. Give wallets an expiry exit from `uncertain`.

### LSP-008: Every rejection keeps the full fee; a wallet with a stale base state pays for repeated `invalid_fee` rejections and gets no corrective SYNC
- Severity: **High** (seen in production: #296, 6 of 6 trades rejected, every fee kept)
- Confidence: Confirmed
- Area: B
- Where: `stable_manager.rs:3280-3290` (fee checked against the LSP's own `expected_usd`), `3270-3295` (LSP-caused rejections: cold or quarantined price, quote vs a price up to 60 s old), `616-690` (rejection sends no SYNC and no refund), `revenue.rs:660-671` (refund is manual only).
- What happens: `TRADE_V1` carries the new target, not the base state the wallet computed it from. If the wallet's `expected_usd` is off by more than about $0.10 (a missed SYNC after its 10 retries, or any wallet bug), the LSP's fee check fails, the trade is rejected with `invalid_fee`, the fee is kept, and nothing tells the wallet how to recover, so the next trade fails the same way. Several rejection reasons are the LSP's own fault (no fresh price, quote drift against its stale price, its own processing delay past 15 minutes, internal failure) and also keep the fee.
- Why it matters: users lose 1% per attempt, and the LSP books revenue from its own outages.
- Fix: add `base_sync_version` (and the old `expected_usd`) to the signed `TRADE_V1`; reject a mismatch with its own reason and queue a SYNC so the wallet converges. Refund automatically (using the existing deterministic-preimage refund path from a worker) for every rejection the user did not cause. Longer term, land the two-phase quote/confirm flow (#240) so the fee is only paid against a signed reservation.

### LSP-031: At startup, a channel-ready event handled before the channel list is loaded overwrites a tracked channel with a $0 target
- Severity: **High** (user's USD position deleted on the LSP side, silently and permanently)
- Confidence: Confirmed
- Area: D (channel lifecycle and startup)
- Where: `server/stable-channels-lsp/src/stable_manager.rs:1303-1316` (checks memory only), `1371-1378` (writes $0 / 0 / all-native over the row by uid, `src/db.rs:1162-1176`), `event_loop.rs:305-311` (connect-time reconcile skipped while the price is cold), `main.rs:167-196` (one startup reconcile attempt, waits up to 60 s for a price).
- Related: #413. Not touched by #400 or #415.
- What happens:
  1. The daemon starts. The event loop connects at once. The price task has not fetched yet, so the connect-time reconcile is skipped (`event_loop.rs:305-311`) and live dispatch begins with an empty in-memory list. This happens on most restarts, not only during feed outages.
  2. A splice on stable channel X confirms, and LDK re-fires `ChannelReady` for X.
  3. `handle_channel_ready` does not find X in memory, takes the new-channel path, and saves $0 / 0 / all-native over X's existing row.
  4. The startup reconcile then loads the $0 row. No SYNC, no alarm. The splice deduction is lost too.
- Why it matters: the LSP stops stabilizing that user while the wallet still shows the old target; every later trade from that wallet fails the fee check (LSP-008).
- Fix: when the uid is not in memory, load it from the DB first and treat the event as a splice if a row exists; never write a $0 row over an existing row (insert-if-absent); best, gate all dispatch on a `hydrated` flag (see LSP-032).

### LSP-032: If the startup reconcile is missed, one channel-ready makes the list non-empty and every other channel stays unloaded until the stream reconnects
- Severity: **High** (stability silently suspended for every user but one, for an unbounded time)
- Confidence: Confirmed
- Area: D
- Where: `stable_manager.rs:1272-1282` (`reconcile_if_empty` reconciles only when the list is empty), `1389` (a new channel is pushed into the empty list), `main.rs:170-195` (single attempt), `event_loop.rs:305-311`.
- What happens: the price stays cold for more than 60 s after start (a feed outage or quarantine), or the one startup reconcile fails on a transient `list_channels` or DB error. Any `ChannelReady` in that window (a new JIT channel, or a splice lock that also triggers LSP-031) adds one entry. From then on the tick's `reconcile_if_empty` sees a non-empty list and never loads the rest. Until the next stream reconnect: no top-ups for those users (the tick iterates memory only), their trades are rejected `InternalFailure` with the fee kept, `ListStableChannels` shows one channel, and any splice lock on them hits LSP-031.
- Fix: replace `is_empty()` with a `hydrated` flag set only by a successful `reconcile_from_grpc`; let reconcile run without a price (books do not need one); hold event dispatch, trades and edits until hydrated.

### LSP-033: Paying the LSP is voluntary: a modified wallet can take every top-up and never pay back, then close or splice out
- Severity: **High** (design; an LSP drain by the user, bounded by position size and price moves)
- Confidence: Confirmed (no server-side enforcement exists)
- Area: A/H
- Where: on user-owes drift the LSP only logs `CHECK_ONLY` or sends a wake push (`stable_manager.rs:2530-2560`); the debt helpers `settlement_owed_to_lsp` and `spend_consumes_lsp_surplus` (`src/stable.rs:465-497`) are used only by wallets; the full-exit gate exists only on the trade path (`src/stable.rs:407-413`); `handle_channel_closed` does no settlement (`stable_manager.rs:920-965`).
- What happens: the LSP pays the shortfall at every new price low. A wallet that never sends `STABILITY_PAYMENT_V1` on rises keeps the surplus, so its backing grows toward `E / p_min`. It can close or splice out whenever the price is above `p_min`; neither path checks for a debt. The LSP's loss is about `E × (p_exit / p_min − 1)` per cycle, and a new channel starts fresh. In effect the LSP has written the user a free put.
- Why it matters: the LSP's hedge assumes the user pays symmetrically; nothing enforces it beyond the honest wallet code.
- Fix: keep a durable per-channel "owed to LSP" balance; net future top-ups against it (pay only above a high-water mark); after a grace period of non-payment, stop stabilizing (target to $0 plus SYNC) or close the channel; alarm on long-standing debts. This caps the loss at one grace window's drift.

### LSP-041: A user's signed pay-back that arrives during an event-stream gap, or whose inbox write fails, is never booked; the backstop then cuts their target, so they pay twice
- Severity: **High** (user loss after a routine restart, does not heal)
- Confidence: Confirmed
- Area: C/E
- Where: `stable_manager.rs:1579-1598` (an error from `register_inbound_stability_settlement` is audited as `STABILITY_PAYMENT_REPLAY_CONFLICT` and dropped, so no inbox row exists for `retry_pending_signed_stability` at `1952` to retry); `backfill.rs:105-127` (reconnect backfill only writes `PAYMENT_RECONSTRUCTED` audit rows; custom TLVs are not reprocessed); `event_loop.rs:386-387` (LDK Server does not replay its stream).
- What happens: price rises, the wallet pays its surplus with a signed `STABILITY_PAYMENT_V1`. If that keysend lands while the daemon is restarting or its stream is reconnecting, or the first DB write fails, the LSP keeps the sats and the books do not move. The user's live balance is now below the LSP's backing, so two ticks later the backstop reads the gap as a spend, cuts `expected_usd`, and SYNCs it. The user paid the drift and then loses about the same amount again from their target.
- Also confirmed independently by the payment-events review, which checked LDK Server's pinned source: delivery is at most once and the custom TLVs ride only in the live event. The legacy `[1]` marker is lost the same way.
- Why it matters: the signed inbox is otherwise the best-built path in the daemon (see "What we do right"); this is the one hole in it, and the same root cause as LSP-007 for trades.
- Fix: treat a DB error on registration as retryable (return `Reconnect`, as `PaymentSuccessful` already does). For stream gaps: before the backstop cuts a target, check `list_payments` for inbound keysends from that peer since the gap that were never classified, and hold the cut with an alarm if one is found. Longer term, have wallets keep a settlement pending until the LSP sends a signed acknowledgement of its `settlement_id`, and resend it otherwise (the inbox makes a resend idempotent).

### LSP-053: The forward handler counts another still-pending payment from the user as spent, so the deduction stays after that payment fails
- Severity: **High** (user loss; hold invoices and swaps make it realistic)
- Confidence: Confirmed
- Area: C (payment events)
- Where: `stable_manager.rs:2805` (`post_user_sats` from `inbound_capacity_msat`, which excludes the user's pending outbound HTLCs), `2826-2832` (`user_sats_before = post + spend`), `src/stable.rs:198-221`.
- Related: #324 (the same bug in the backstop). PR #415 fixes the backstop only and lists this handler as a known limit.
- What happens: $50 stable backed by 50,000 sats, plus 20,000 native. The user has a 20,000-sat hold-invoice or swap payment pending, then pays a 5,000-sat invoice. The `PaymentForwarded` reconstructs the pre-spend balance as 45,000 + 5,000 = 50,000, so native looks like 0 and the whole 5,000 is booked as stable overflow: target $45, SYNC sent. The held payment then fails and the balance returns, leaving $45 stable plus 20,000 native instead of $50 plus 15,000. The over-deduction is `min(spend, pending, native)`, permanent, and already published to the wallet.
- Fix: in `handle_payment_forwarded`, add the user's unresolved HTLCs on this channel to the pre-forward balance (reuse #415's `unresolved_user_payments_msat` from `GetBalances`), and keep the SYNC held while backing is above the live balance.

### Medium

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


### LSP-009: The HMAC covers only timestamp and body: requests can be replayed for 60 s, and to a different route
- Severity: Medium · Confidence: Confirmed (capturing a request needs a plaintext vantage point such as LSP-006, a TLS-terminating proxy, or LSP-027)
- Where: `server/stable-channels-lsp/src/auth.rs:59-61` (MAC over `ts || body`), `server/sc-rest-client/src/client.rs:144-160`
- What happens: there is no nonce cache, so a captured `/SpontaneousSend` (fresh preimage each call) or `/OnchainSend` pays twice if replayed within ±60 s. The path is not in the MAC, so a valid triple is accepted by any route whose request decodes from that body: `CloseChannelRequest` has the same wire encoding as `ForceCloseChannelRequest`, so an observed cooperative close can be replayed as a force close.
- Fix: MAC over `method || path || ts || nonce || body`, keep a nonce cache for the timestamp window, and version the header (`HMAC2`).

### LSP-010: A top-up failure missed during an event-stream gap is never rolled back, so the backstop later cuts the user's target
- Severity: **High** (raised from Medium: two reviewers traced it to a target cut that does not heal) · Confidence: Confirmed · Fixed by open PR #400
- Where: `server/stable-channels-lsp/src/backfill.rs:263-307` handles `sync`+Failed and any kind+Succeeded; a `stability`+Failed row falls through `if payment.status != Succeeded { continue; }`. The comment at `backfill.rs:260-262` says the gap is closed.
- What happens: the LSP booked the top-up at send (LSP-005). If its `PaymentFailed` arrives while the stream is down, the optimistic backing is never undone, so the user's books show sats that never arrived and the backstop later cuts the target.
- Fix: in backfill, roll back `stability` rows whose LDK payment is Failed (as #400 does).

### LSP-011: A top-up meant for one channel can land on the same peer's other channel and is still booked on the first
- Severity: Medium · Confidence: Confirmed from LDK's router code; needs a peer with two usable channels, which the daemon supports (`stability_tick.rs:73-96`)
- Where: `stable_manager.rs:2423-2440` (keysend to `node_id` with `route_parameters: None`); rust-lightning `router.rs:2556-2586` picks first hops by liquidity and can split across channels.
- What happens: channel A is owed 12,500 sats; LDK routes the keysend over the peer's channel B. The LSP books A as topped up; A's live balance has not moved, so two ticks later the backstop cuts A's target. B holds the extra sats as native.
- Fix: book a top-up on the channel whose balance actually rose, or settle per peer, or skip top-ups (with an alarm) to a peer with more than one usable channel; set `max_path_count = 1`.

### LSP-012: The manager lock is held across every LDK Server call, and those calls have no timeout
- Severity: Medium · Confidence: Confirmed
- Where: `stability_tick.rs:47-57`; inside the tick `stable_manager.rs:2156, 2160, 2372, 2451, 2594-2608`; event dispatch `event_loop.rs:388`; the gRPC client is built with no timeout (`ldk-server-client/src/client.rs:120-123`).
- What happens: one stalled LDK Server call holds the lock indefinitely. Event dispatch, failure rollbacks, trade decisions, forward deductions and the REST stable-channel routes all stop while the event buffer grows. With N channels below par, a tick makes 2N sequential round trips under the lock. PRs #400 and #415 add more calls under it.
- Fix: wrap every LDK call in `tokio::time::timeout`; split the tick into decide-and-record under the lock, sign-and-send outside it, record outcomes under it; cap work per tick.

### LSP-013: No cap on top-up size or rate; the only limit is a cooldown kept in memory and reset on every reconnect; no backoff after failures
- Severity: Medium (defense in depth on the money path) · Confidence: Confirmed
- Where: `stable_manager.rs:2340-2341` (amount is the raw drift), `2317` (cooldown is the only limit), `3712` (memory only), `732` and `2528` (failures restore or skip the cooldown); no outbound-liquidity check before sending.
- What happens: nothing says "no top-up above X% of the target", "no more than Y sats per channel per day" or "pause above Z sats per hour". LSP-003, LSP-004 or a bad price that three feeds agree on are limited only by channel liquidity. A channel the LSP can no longer fund sends a failing keysend every 60 s.
- Fix: per-payment, per-channel-per-day and global hourly caps with a circuit breaker and a red alarm; persist the last send time; exponential backoff after failures; check outbound liquidity first.

### LSP-014: The channel reserve counts as stable backing, so a pay-back owed to the LSP can be unpayable
- Severity: Medium · Confidence: Confirmed on the LSP side (wallet side in #401)
- Where: `stable_manager.rs:52-58` (`their_sats` includes the counterparty reserve), used for backing, drift and backstop (`2207`, `2287-2291`); `src/stable.rs:29-62`.
- What happens: a user spends down to the reserve, so backing is the reserve. The price rises; the LSP is owed the surplus and wakes the phone every tick it is offline, but the wallet cannot pay (spendable is 0). The spend guard from #344 then blocks all of the user's spends.
- Fix: exclude the reserve from the balance used for backing (coordinated with wallets), or cap the amount owed at what the user can spend; add backoff (LSP-013).

### LSP-015: An unsigned pay-back right after a top-up claim is not matched, so the user's target is cut
- Severity: Medium (legacy wallets only) · Confidence: Confirmed
- Where: `stable_manager.rs:2044-2061` matches the balance drop against `stable_receiver_btc`, which is refreshed only at the tick (`2210`, `2285`), not on claim (`event_loop.rs:489-541`); backstop `2223-2281`.
- Related: #414. Not fixed by #400. Goes away when the legacy `[1]` marker is removed (#235, #284).
- Fix: refresh the cached balance on claim, or remove the legacy path.

### LSP-016: Anonymous clients can fill the disk through the two unauthenticated routes
- Severity: Medium · Confidence: Confirmed
- Where: `handlers/register_push.rs:101-109` writes the parse error, which embeds the whole input, to the channel ledger as `REGISTER_PUSH_LEGACY_INVALID` (a durable event, `src/ledger.rs:1073`, never pruned); `push/tokens.rs:41-65` has no length limits and never deletes rows; each `/api/channel-exists` call makes one `ListChannels` gRPC call.
- What happens: a 1 MiB invalid body appends about 1 MiB to the SQLite ledger per request, under the DB mutex the stability engine shares. A full disk stops settlement and is one trigger for LSP-003.
- Fix: log only the error class and length; make pre-auth failures operational (JSONL only); validate field lengths; prune `push_tokens`; per-IP rate limit both `/api/*` routes; cache `ListChannels` briefly.

### LSP-017: The api key file is world-readable and written non-atomically, and an empty key file is accepted
- Severity: Medium (fail-open of the only control on money-moving routes) · Confidence: Confirmed
- Where: `server/stable-channels-lsp/src/main.rs:324-355`: `std::fs::write` with the default umask (typically 0644), no fsync or rename; an existing file of any length is accepted. Compare the TLS key, written `0o400` (`tls.rs:155`).
- What happens: off Umbrel, any local user can read the key. If the first start crashes or hits ENOSPC between create and write, the file is left empty; on restart the daemon uses `""` as the HMAC key, and anyone can sign requests to every route, including `/OnchainSend`.
- Fix: require exactly 32 bytes or refuse to start; write a temp file with mode 0600, fsync, rename; create the dir 0700; warn if the key is group- or world-readable.

### LSP-018: Any Lightning peer can make the LSP write ledger rows and run LDK calls under the main lock for 1 msat; the 20/s limit from #230 is not on main
- Severity: Medium · Confidence: Confirmed
- Where: `stable_manager.rs:1428-1460` (TLV 13377331 handling), `3021-3104` (audit rows, then `ListChannels` at `3063` before the channel and signature checks), `event_loop.rs:388-391` (lock held for the whole dispatch); trade spam: fee floor `stable_manager.rs:133` (`.max(1)`) and ±1000 msat tolerance (`136-142`), decision rows never deleted (`src/db.rs:100-121`).
- What happens: a keysend of 1 msat with a well-formed `TRADE_V1` naming a random channel writes about three durable ledger rows (with up to 8 KiB of attacker text) and makes one `ListChannels` call under the lock that serializes the whole stability engine. A real user can also spam tiny trades, each creating a permanent decision row and a 14-day response obligation. #230 described a 20/s fixed-window limiter; it is not in the code on main.
- Fix: restore a rate limit before any audit, DB or gRPC work; resolve the payer's channel from a cached list first and drop messages from peers without a stable channel; make pre-auth failures operational; add a minimum trade size.

### LSP-019: The REST listener has no timeouts, connection limits or concurrency limits
- Severity: Medium · Confidence: Plausible (axum-server 0.7 / hyper 1.x defaults for HTTP/2 streams and header timeouts not checked)
- Where: `main.rs:298-318` (only `DefaultBodyLimit` and `TraceLayer`), `auth.rs:123` (body read with no deadline), `example-config.toml:26-28` (suggests exposing :3002).
- What happens: where :3002 is reachable, an anonymous client opens many HTTP/2 streams each trickling a body up to 1 MiB; the auth layer buffers each without a deadline, so memory grows until the daemon (and the stability engine) is killed.
- Fix: request and body-read timeouts, a global concurrency limit, low h2 `max_concurrent_streams`, per-IP limits on auth failures.

### LSP-020: If a payment lookup fails, the signed trade result carries the wrong id and the wallet discards an acceptance the LSP has booked
- Severity: Medium · Confidence: Plausible (needs a transient `get_payment_details` failure at decision time)
- Where: `stable_manager.rs:595-613` (falls back to the LSP-internal `payment_id` instead of the payment hash), `3168-3169`, `3343-3352`; `event_loop.rs:462-466` does not pass the hash that the event already carries; Android `DatabaseService.kt:1116-1160`.
- What happens: the wallet correlates results by payment hash, so it drops the acceptance while the LSP has booked the new target. The books disagree until a later SYNC.
- Fix: pass the payment hash from the `PaymentReceived` event instead of looking it up; never sign a result with a fallback id.

### LSP-021: A trade made while a top-up is in flight builds on the unlanded top-up; if the top-up fails, the rollback misses
- Severity: Medium · Confidence: Confirmed (needs the top-up HTLC to fail after the trade) · Fixed by open PR #400
- Where: `stable_manager.rs:2445-2447`, `2503-2507` (optimistic backing at send), `3257-3264`, `3302-3310` (trade builds on in-memory backing), `src/db.rs:4546-4561` (rollback compare-and-swap requires the old target).
- What happens: the trade changes `expected_usd`, so the rollback's compare-and-swap no longer matches and is skipped. The user keeps backing for sats that never arrived; the backstop then cuts their target.
- Fix: book top-ups on claim (#400), or reject trades while a top-up is in flight.

### LSP-022: Trade results reach offline users only on a timer; nothing fires on reconnect, and an accepted trade is dropped after 14 days with no SYNC fallback
- Severity: Medium · Confidence: Confirmed code path; trigger depends on users closing the app right after trading
- Where: `src/db.rs:1582-1585` (backoff capped at 1 h), `1640-1644` (abandon after 14 days); `stable_manager.rs:472-541` (no wake push), `3332-3409` (acceptance never queues an ordinary SYNC); `stability_tick.rs:261-299` (the reconnect hook runs only a stability pass).
- Fix: on peer reconnect, flush pending trade responses; send a wake push for a pending result; on abandon, fall back to an ordinary SYNC of the current books.

### LSP-034: An operator edit before the list is loaded resets the user's target to $0
- Severity: Medium · Confidence: Confirmed
- Where: `stable_manager.rs:799-813` (`prior` from memory only; `(None, None) => 0.0`), `835-840`, `880-896`. #400/#415 add a `prior_books` path only for channels already in memory.
- What happens: in the startup window (or while LSP-032 holds), an operator adds a note to channel X. `prior` is None, so the target becomes $0 and backing 0; the "no changes" guard passes because a note was given; `save_channel` overwrites the row and no SYNC is sent.
- Fix: load the prior state from the DB when it is not in memory, and refuse edits until hydrated.

### LSP-035: `EditStableChannel` overwrites the books: it erases drift, writes backing 0 on a cold price (LSP over-pays), and never SYNCs the wallet
- Severity: Medium (operator-only, but a direct LSP over-payment) · Confidence: Confirmed
- Where: `stable_manager.rs:761-918`; backing derivation `835-840`; no `send_sync_message` in the function; stabilization cap in shadow mode by default (`845-858`). Input validation is LSP-029.
- What happens:
  - **Cold price:** a note-only edit while the price is 0 keeps the target ($50) and writes backing 0. The user then spends 80,000 of 100,000 sats; with backing 0 nothing is deducted (`src/stable.rs:198-204`), and the tick values the position at the whole live balance ($20). The LSP sends a $30 top-up it does not owe.
  - **Warm price:** any edit re-anchors backing to `target / price`, erasing accrued drift (for example the $5 the LSP owed after a 10% fall is never paid).
  - **No SYNC:** the wallet keeps its old target, so its next trades fail the fee check (LSP-008).
  - #400/#415 fix the note-only and cold-price cases; target edits still erase drift and still send no SYNC.
- Fix: route target edits through the trade math so drift is preserved; never write backing 0 for an active target; require a warm price; persist, then queue a SYNC; refuse while unhydrated; consider a separate operator role for money-moving routes.

### LSP-036: The backstop reads pending-splice balances as spends: an LSP splice-in books a phantom spend, and a splice-out is deducted before it confirms with no undo
- Severity: Medium · Confidence: Plausible (LDK logic confirmed in the vendored rust-lightning; not verified that LDK Server's own LDK revision matches, and not reproduced on live nodes)
- Where: `stable_manager.rs:52-58` adds the current scope's reserve to `inbound_capacity_msat`, while LDK reports the minimum across pending funding scopes, each with its own 1% reserve (rust-lightning `channel.rs:13554-13570`, `2783-2797`); backstop `stable_manager.rs:2223-2280`; `SpliceNegotiated` is audit-only (`event_loop.rs:618-624`).
- What happens:
  - **LSP splice-in of s sats:** while pending, the user appears `s / 100` sats poorer (new scope's reserve minus old). After two ticks the backstop cuts their USD by `s / 100 × price` and SYNCs it. A 0.1 BTC splice-in costs the user about 100,000 sats of USD position.
  - **User splice-out:** the deduction happens through the backstop about 2 minutes after negotiation, not at channel-ready as #187 and #234 describe; if the splice is abandoned the balance returns but the deduction stays.
- Fix: track a per-uid "splice in flight" flag from `SpliceNegotiated`/`SpliceNegotiationFailed`, suspend the backstop for that uid while set, and settle once at channel-ready.

### LSP-037: A channel whose channel-ready event is missed or fails is never registered as stable
- Severity: Medium · Confidence: Confirmed
- Where: `stable_manager.rs:1011-1065` (reconcile walks DB rows only), `1318-1338`, `1371-1387` (`handle_channel_ready` returns on a gRPC or DB error; nothing retries); LDK Server drops events with no subscriber (ldk-server `main.rs:864-870`).
- What happens: a new JIT channel becomes ready while the daemon restarts or its stream is down, or a call fails. The channel never gets a row: the user's trades are rejected with the fee kept, they get no stability, and only a manual edit (LSP-035) recovers it.
- Fix: in `reconcile_from_grpc`, insert-if-absent a $0 row for every ready channel with no row; queue failed `handle_channel_ready` calls for retry.

### LSP-042: The forwarded-spend path still changes memory before the DB and can SYNC unsaved books
- Severity: Medium · Confidence: Confirmed ordering; "SYNC with unsaved values" Plausible (needs the save to fail while `next_sync_version` succeeds)
- Where: `stable_manager.rs:2829` (`reconcile_forwarded` mutates `sc` in place), `2839` (`STABLE_SPEND_DEDUCTED` written to the ledger before the save), `2879-2891` (save failure only audited), `2895-2905` (SYNC from memory). Same pattern on the legacy paths: LSP-024 (pay-back) and LSP-026 (trade).
- What happens: after a failed save, the tick and later writes work from unsaved books; a later write that copies from memory (`record_stability_settlement_with_rollback` copies `expected_usd` and native) silently persists them, while a restart reverts to the old row and the backstop deducts again at a different price. The wallet can receive two SYNCs with different targets, and the ledger claims a deduction that never committed. #326 fixed only the backstop and splice paths.
- Fix: route all three paths through `PendingBookUpdate` (compute on a clone, save, swap, then audit and SYNC), with the audit row inside the save transaction.

### LSP-043: Event-loop retries and reconnects throw away every buffered event, and one pending settlement whose lookup keeps failing stops live event handling for every channel
- Severity: Medium · Confidence: Confirmed that buffered events are discarded on every retry and `Reconnect` (`event_loop.rs:264`, `340-346`, `354-366`; `Reconnect` returns at `511`, `536`, `569`, `583`); Plausible that a lookup fails persistently. Each retry also re-runs `reconcile_from_grpc`, which resets every channel's cooldown (feeds LSP-003)
- Where: `backfill.rs:267-279` (`?` on each lookup aborts the pass), `event_loop.rs:340-346` (`settlement_outcomes_safe = false` → re-subscribe, never reach dispatch), `src/db.rs:4639-4648` (reads every pending row, no age bound).
- What happens: every loop iteration re-subscribes, re-runs reconcile and backfill, fails on the same row, sleeps 1 s, and starts over; buffered events are dropped with the reader. Trades, pay-backs, forwards and failures are lost for every channel, while the tick keeps paying top-ups and the backstop turns missed events into target cuts. Same class as #416.
- Fix: handle errors per row (block only that channel, alarm with the payment id, move to an operator queue when old); never gate global dispatch on one row.

### LSP-044: Migrations ignore every `ALTER TABLE` error and there is no schema version
- Severity: Medium · Confidence: Confirmed (consequence needs an `ALTER` to fail for a reason other than "duplicate column")
- Where: `src/db.rs:463-722` (34 `let _ = conn.execute("ALTER TABLE …")` sites; `idx_settlement_sync_version` also unchecked); no `PRAGMA user_version`. #38 was closed but the pattern is still on main; #415 adds two more.
- What happens: on the first boot after an upgrade, an `ALTER` that fails with BUSY, an I/O error or a full disk is swallowed and startup succeeds. The first money write naming the column then fails at runtime: if `settlement_payments.backing_sats_before` is missing, every top-up record fails, which is the LSP-003 drain for every top-up. Without a version a downgrade is not detected, which #400's own deploy notes warn about.
- Fix: run schema init in one IMMEDIATE transaction; ignore only "duplicate column name"; verify required columns with `PRAGMA table_info` and refuse to start if missing; set `PRAGMA user_version` and refuse a DB newer than the binary.

### LSP-048: Half the responding price feeds can shift the LSP price about 5%, a majority can walk it anywhere in steps, and nothing caps the top-ups that follow
- Severity: Medium (needs control or a correlated failure of about half the feeds; impact is book-wide) · Confidence: Confirmed (mechanism)
- Where: `src/price_feeds.rs:214-231` (median of whatever feeds responded; even count takes the midpoint), `251-288` (≥3 feeds within 5% of that median "agree"; the 10% breaker compares against the **last accepted** price), `240-243` (an anchor older than 60 s is dropped, so after a quarantine the next consensus is accepted unbounded); payout `stable_manager.rs:2287-2340` (no cap).
- What happens: with 3 honest feeds at P and 3 wrong at P(1−X) for X ≤ 9.5%, all six agree with the midpoint and the accepted price is P(1−X/2), up to −4.76%. Two wrong out of four responding (two honest ones rate-limited) does the same. With a majority, 9.9% steps are accepted every 15 s refresh (about 46% per 60 s tick), and a larger jump only pauses pricing for 60 s. The next tick tops up every stable channel at once with no per-tick budget. Android and desktop wallets cap a top-up at their own (correct) equilibrium and keep the excess as native, so it is not paid back.
- Why it matters: a 4.76% shift for one tick pays out about 4.76% of the whole stable book.
- Fix: require a strict majority of the configured feeds (≥4 of 6), not ≥3 of those that answered; pay only when two consecutive refreshes agree within about 0.5% (or use a short TWAP); make the breaker cumulative over a window against a slow anchor and stay paused when the anchor expires during a quarantine; add the per-tick and per-channel caps from LSP-013.

### LSP-049: The desktop wallet books LSP top-ups and SYNCs at a stale or quarantined price, losing the hedge on large moves
- Severity: Medium (wallet side; user loss in exactly the large-move case the product exists for) · Confidence: Confirmed code path
- Where: `src/user.rs:1005-1077` (`sc.latest_price` only updated on a fresh refresh), `src/stable.rs:568-571` (keeps the old value when the fresh price is 0), `src/user.rs:2883-2900` (signed top-up booked at `sc.latest_price`, only checked `> 0`), `3998-4007` (SYNC delta at the same price).
- What happens: BTC falls 12% within one refresh. The desktop quarantines and keeps its old price; the LSP's anchor expires first and it pays a top-up at the new price. The desktop computes equilibrium at the old price, sees it already met, and books the whole top-up as native. The LSP is at par, the wallet is $120 below par in its own view, and nothing corrects it. Android handles this correctly by refusing and retrying.
- Fix: book with the fresh price only; if it is 0, do not acknowledge and retry, as Android does.

### LSP-054: A dropped forward deduction falls back to the backstop, which any later receive cancels, so the spend is never deducted
- Severity: Medium · Confidence: Confirmed on the LSP side; wallet divergence Plausible (the desktop deducts locally on `PaymentSuccessful`, `src/user.rs:3372`)
- Where: `stable_manager.rs:2778-2784` (channel not loaded yet), `2787-2797` (`list_channels` error), `2816` (cold price: silent return, no audit); backfilled forwards are audit-only (`backfill.rs:425`); the backstop debounce resets as soon as the live balance is back above backing (`2281-2283`).
- What happens: a forward event is lost or skipped. On the first tick the live balance is below backing (count 1). If the user receives enough within about 60 s, the balance is back above backing, the debounce resets, and the spend is never deducted. The received sats silently become stable USD: an unrequested, fee-free trade that skips the stabilization cap. The wallet already deducted locally and no SYNC goes out, so the books disagree, which then shows up as `invalid_fee` trade rejections (LSP-008).
- Fix: write a durable "forward to apply" row when the event arrives (the observed-forward ledger row already holds the data) and retry until applied; never drop a deduction on a cold price or RPC error. Upstream, ask LDK Server to include the forward id in the live `PaymentForwarded` so backfilled forwards can be applied safely.

### Low

### LSP-023: A channel with a USD target but zero recorded backing values the whole balance as stable
- Confidence: Confirmed. Where: `stable_manager.rs:2287-2291` (`backing_sats == 0` means "use the whole balance"); `src/stable.rs:188-249` can leave `backing = 0` with `expected_usd > 0`. The wallet treats 0 differently (`src/stable.rs:542-553`).
- Effect: the user's later native receives (or the reserve) are silently counted as stable. The payment-events review found the mirror case: with zero backing the LSP values the position at the whole balance and never pays a shortfall it owes (the wallet already fixed this rule, the LSP did not). Fix: never use 0 as a sentinel for an active target; store backing explicitly.

### LSP-024: The unsigned pay-back path changes memory before the DB and ignores a failed save
- Confidence: Confirmed. Where: `stable_manager.rs:2064-2099` (memory first), `2101-2114` (save error only audited). The next reconnect reloads the old backing and the backstop cuts the target by the amount paid back. This is the only stability write not using `PendingBookUpdate`. Fix: same transaction pattern as the signed path.

### LSP-025: Settlements re-anchor to the LSP's price, which moves real value from user to LSP and can block full exits
- Severity: Medium (raised from Low by the protocol review). Confidence: Confirmed. Where: `src/stable.rs:782-788` (`.max(equilibrium)` at the LSP price), used by both settlement paths. Related: #232. Effect: this is not only a ledger mismatch as #232 says. Worked example: E = $1000, the wallet sees $110,000 and the LSP $109,560 (0.4% apart, inside the normal band). The wallet pays 90,909 sats; the LSP floors backing at its own equilibrium, 3,651 sats higher. When the price returns, the LSP's top-up is 3,651 sats short, so a round trip with no net price change costs the user about $3.65. The gap ratchets to the largest disagreement seen. While the LSP sees that gap as a surplus it rejects a full-exit trade with `SettlementRequired` (`src/stable.rs:407-413`), the wallet sees nothing to settle, and each attempt costs the fee. Fix: make the result amount-faithful (`current.saturating_sub(amount)` plus the live clamp), as #232 proposes; longer term, sign the payer's price or post-state into `STABILITY_PAYMENT_V1`.

### LSP-026: The legacy trade path (no `trade_id`) is still accepted; `ts == 0` envelopes can be replayed from any node to reset a user's target
- Severity: Medium (raised from Low by the protocol review). Confidence: Confirmed code path; Plausible exploit (needs an old envelope, for example from logs, backups or the LDK payment store). Where: `stable_manager.rs:3412-3426` (`ts == 0` skips the freshness check, and there is no nonce inside the 300 s window), `3447-3488`, `3592-3618` (memory updated before `save_channel`). Effect: LDK does not tell the daemon which channel a keysend came in on, so anyone holding an old signed legacy envelope can keysend it from any node with the recomputed fee. The signature still verifies against the named channel's counterparty, and the LSP resets that user's `expected_usd` to the old target and SYNCs it (for example $5000 back to $50), for 1% of the delta. Envelopes with `ts != 0` can be replayed within 300 s. Rejections on this path are silent and unrefundable, and a failed save leaves memory ahead of the DB with no SYNC. Every current client sends `trade_id`, which is signed, so current envelopes cannot be downgraded. Fix: reject `TRADE_V1` without `trade_id` (#284); at minimum reject `ts == 0` now, as #145 planned.

### LSP-027: TLS "pinning" adds the self-signed cert to the trust store instead of replacing it
- Confidence: Confirmed. Where: `server/sc-rest-client/src/client.rs:95-104` (`add_root_certificate` with reqwest's built-in roots still on). Effect: any public-CA cert for the daemon's DNS name is also trusted. Fix: `tls_built_in_root_certs(false)`, or verify the leaf SPKI hash.

### LSP-028: A huge `max_lines` in `/LdkLog` pre-allocates enough memory to abort the daemon
- Confidence: Confirmed. Where: `handlers/ldk_log.rs:28, 57` (`VecDeque::with_capacity(max_lines)` from a `u32`); the GUI passes the text box through. Effect: one authenticated request (or a typo) stops the stability engine until restart. Fix: clamp (for example 10,000) and do not pre-allocate from input; same in `audit_log.rs`.

### LSP-029: `EditStableChannel` accepts NaN, infinite and negative targets and a cold price, with no SYNC to the wallet
- Confidence: Confirmed (validation missing); effect on later ticks Plausible. Where: `handlers/stable_channels.rs:72-96`, `stable_manager.rs:761-916` (no `is_finite`, sign or price guard; `btc_price == 0` stores `backing_sats = 0` at `835-840`). Fix: validate on the server; confirmation step in the GUI; send a SYNC or document that edits are LSP-local.

### LSP-030: The trade-fee refund preimage can be derived from public data
- Confidence: Plausible (only matters if a refund is routed through a node that saw the original payment). Where: `src/trade.rs:122-126` (`sha256(tag || trade_payment_id)`), `revenue.rs:679, 702-708`. Fix: `HMAC(local_secret, trade_payment_id)`, which keeps it deterministic but unguessable.

### LSP-038: Reconcile soft-closes rows missing from one channel list and never re-opens them
- Confidence: Confirmed. Where: `stable_manager.rs:1016-1035` (comment says a future reconcile clears it, but `load_all_channels` excludes closed rows, `src/db.rs:1942-1950`). Effect: one successful-but-incomplete `list_channels` (wrong node, briefly attached empty store) permanently untracks channels; their next splice then hits LSP-031. Fix: re-activate closed rows whose uid is live; treat a close as final only on `ChannelClosed`, or after N consecutive absences.

### LSP-039: A correction that brings the target to $0 is never SYNCed after a restart
- Confidence: Confirmed. Where: `stable_manager.rs:429-431` (`needs_sync` is memory-only), `1074-1078` (restart re-queues only `expected_usd > 0`). Effect: a splice-out or deduction cuts the target to $0 while the wallet is offline; after a restart nothing is ever sent and the wallet keeps its old target. Fix: persist "SYNC owed" in the same transaction as the books.

### LSP-040: A stale pending-splice entry halts the tick for every channel
- Confidence: Confirmed. Where: `stable_manager.rs:2148-2153` (the whole tick returns while any splice is pending), `2950-2955`, `1274-1276`. Effect: if a channel with a pending splice closes and the close is missed in a stream gap, every tick retries it and returns before settling any channel, until the next reconnect. Fix: drop pending splices whose uid is no longer live, and scope the barrier to that uid.

### LSP-045: One unreadable `channels` row stops the daemon from starting; corrupt money cells read as 0 or huge values
- Confidence: Confirmed. Where: `main.rs:128-131` (`load_all_channels()?` aborts startup), `src/db.rs:1942-1969` (`row?` fails the whole load; `unwrap_or(0)` and `as u64` on money columns). Effect: one damaged row takes down the LSP, or a negative `stable_sats` becomes about 1.8e19 backing. Fix: read rows one at a time, quarantine a bad row with an alarm, use `try_from` for money columns.

### LSP-046: A half-open event stream still counts as healthy, so a gap is never opened
- Confidence: Plausible (needs an HTTP/2 stream that stops delivering without an error; neither side sets keepalive). Where: `event_loop.rs:41-75`, `159-183`, `src/db/stream_checkpoint.rs:184-192`; LDK Server also skips lagged events without telling the client. Effect: events are lost while the checkpoint keeps recording "healthy", so backfill never runs and nobody is alerted. Fix: HTTP/2 keepalive or an application heartbeat; treat a missed heartbeat as a gap.

### LSP-047: The ledger is append-only by convention only, and some money audit rows are written outside the transaction that changes the books
- Confidence: Confirmed. Where: `src/ledger.rs:231-279` (no UPDATE/DELETE triggers, no hash chain, `ON DELETE CASCADE` refs); `STABLE_SPEND_DEDUCTED` before the save (`stable_manager.rs:2839`), `STABILITY_PAYMENT_V1_SENT` before the record (`2454`), `TRADE_ACCEPTED` after the trade commit (`3390`, and `persist_trade_acceptance` writes no ledger row). Effect: a crash can leave a book change with no ledger row, or a ledger row for a change that never committed, and edits leave no trace. Fix: write every book-changing event in the same transaction; add a hash chain and BEFORE UPDATE/DELETE triggers; anchor the chain head externally.

### LSP-050: The price refresh "in progress" flag is not panic-safe
- Confidence: Plausible (needs a panic in a fetch thread, for example `println!` to a closed pipe). Where: `src/price_feeds.rs:89-98`, `154-169` (`updating = true` with no RAII guard). Effect: pricing stops until restart, which stops all ticks. Fix: a drop guard that clears the flag.

### LSP-051: The iOS notification extension looks up uncorrelated SYNCs by the LSP's `user_channel_id`, which never matches, and blocks later background events
- Confidence: Confirmed (wallet side). Where: `ios/StableChannels/NotificationService/Services/SQLitePaymentDatabase.swift:770-783` (`WHERE user_channel_id = ?`); the LSP signs its own id (`stable_manager.rs:2640-2646`), which differs from the wallet's; foreground iOS and Android key by `channel_id`. The extension then stops its event loop without acknowledging, so later background events wait until the app is opened. Fix: key by `channel_id` like the other clients.

### LSP-052: `/SignMessage` lets any api key holder sign arbitrary bytes with the node key that signs protocol messages
- Confidence: Confirmed. Where: `main.rs:237` → `handlers/tools.rs:14-23`; the same key signs SYNC, rejections and top-ups (`stable_manager.rs:245-249`, `2373`, `2648`), and the only domain separation is the JSON `type` field. Effect: whoever holds the api key (see LSP-006, LSP-017) can forge a `SYNC_V1` with a huge `sync_version`, after which the wallet ignores every real SYNC. Fix: remove or gate `/SignMessage` behind a separate operator credential, and prefix protocol payloads with a protocol-specific domain tag that `/SignMessage` refuses to sign.

### LSP-055: On the first low-balance tick, the regular tick still pays a top-up computed on backing that includes already-spent sats
- Confidence: Confirmed. Where: `stable_manager.rs:2219-2224` (the wake pass skips a channel with a possible unreconciled spend) vs `2223-2285` (the regular tick falls through while the backstop count is below 2), then `2334-2506` (top-up). Effect: if a spend's deduction was missed and the price falls before the backstop fires, the LSP tops up drift on sats the user already spent, paying `spend × (p0 − p1)`. The test at `stable_manager.rs:4599` passes only because the price does not move. Fix: skip settlement in the regular tick while the live balance is below backing (with #415, use `recoverable_sats` so pending HTLCs still get top-ups).

### Protocol message table

All signatures are LDK `sign_message` (zbase32, recoverable, over `"Lightning Signed Message:" ‖ payload`); the payload is the exact JSON string in the envelope. From the protocol review, checked against the code.

| Message | Direction | TLV | Verified against | Replay protection | Legacy fallback still accepted |
|---|---|---|---|---|---|
| `TRADE_V1` (with `trade_id`) | wallet → LSP | 13377331 | counterparty of the named channel | unique `trade_id`, payment id idempotent, `ts` ±15 min | **yes**: without `trade_id` it takes the legacy path (LSP-026) |
| `SYNC_V1` | LSP → wallet | 13377331, 1 msat | pinned LSP key | monotone `sync_version` per channel, no expiry | n/a |
| `TRADE_REJECTED_V1` | LSP → wallet | 13377331, 1 msat | LSP key | correlation and terminal state | n/a |
| `STABILITY_PAYMENT_V1` | both ways | 13377333 | counterparty of the named channel | durable settlement registry, expiry ≤14 days | **yes**: unsigned `[0x01]` on 13377331 from any sender (LSP-015) |
| `REGISTER_PUSH_V1` | wallet → LSP, HTTP | n/a | `node_id` in request | `ts` ±300 s | **yes**: unsigned `/api/register-push`, the only path the mobile apps use (LSP-001) |

Note: LDK does not tell the daemon which channel a keysend arrived on, so the LSP cannot bind any message to its payer; it relies on the signature naming the channel. Every signed path is safe under that model except the legacy ones in the last column.

### Crash-window table

What a crash or error right after each step leaves behind, per money path. (a) = money sent but not recorded; (b) = received or recorded but not acted on; (c) = memory changed but DB not. From the persistence review, checked against the code.

| Path | Step | Left behind | Handled? |
|---|---|---|---|
| Top-up | `spontaneous_send` returns, record not yet committed (`stable_manager.rs:2451`) | (a) restart clears the cooldown, backing unchanged → first tick resends | **no**, LSP-003 |
| Top-up | send returns Err after LDK queued it (`2513-2529`) | (a) no cooldown → next tick resends with a new preimage | **no**, LSP-004 |
| Top-up | record fails (`2467`) | (a) same drift paid every 120 s while writes fail | **no**, LSP-003 |
| Top-up | `PaymentSuccessful` missed | settlement stays pending | yes (reconnect re-poll) |
| Top-up | `PaymentFailed` missed or its rollback write fails | (b) optimistic backing stays; backstop cuts target | **no**, LSP-010 (fixed by #400) |
| Signed pay-back | inbox write fails, or keysend lands in a stream gap | (b) LSP keeps the sats; backstop cuts target | **no**, LSP-041 |
| Signed pay-back | registered, then transient failure | inbox row stays pending, retried each tick | yes |
| Signed pay-back | persist (payment, channel CAS, inbox, ledger in one transaction) | atomic | yes |
| Legacy pay-back | memory updated, `save_channel` fails (`2093-2116`) | (c) memory ahead; ledger says reconciled | **no**, LSP-024 |
| Forwarded spend | memory, ledger row, then save fails (`2829-2905`) | (c) memory ahead, ledger claims deduction, SYNC from unsaved books | **no**, LSP-042 |
| Backstop deduction | save fails | kept in `PendingBookUpdate` behind the event barrier | yes (recomputed after a crash) |
| Channel ready, uid not in memory | `save_channel($0)` over existing row (`1371-1377`) | target overwritten with $0 | **no**, LSP-031 |
| Trade | crash or gap before the decision is stored | (b) fee kept, no decision, no response | **no**, LSP-007 |
| Trade | `persist_trade_acceptance` (channel CAS, decision, envelope in one transaction) | atomic | yes |
| Trade | `TRADE_ACCEPTED` audit after commit (`3390`) | book change with no ledger row | partly, LSP-047 |
| Legacy trade | memory, then save fails (`3592-3618`) | (c) target only in memory, no SYNC | **no**, LSP-026 |
| SYNC | version reserved, sign, send, record | untracked SYNC; retry sends a newer version | yes (idempotent by version) |
| Trade-fee refund | claim with deterministic id, send, record (`revenue.rs:679-729`) | ambiguous error resolved by id lookup | **yes: the model pattern for LSP-003** |
| Close | memory removed, DB write fails | reconcile soft-closes non-live rows | yes |

### Notes on the open PRs #400 and #415

- **#400 (book a top-up when it is claimed)** fixes LSP-005, LSP-010 and LSP-021 and adds a one-top-up-in-flight guard. It does **not** fix LSP-003/LSP-004 (it keeps send-then-record). It introduces two problems that should be fixed before merging:
  - **#416:** the tick reads all in-flight top-up rows at once, so one unreadable row pauses top-ups for every channel, with an alarm that names no channel.
  - **Event latency:** every event waits on top-up lookups (`lock_for_event_with_top_ups_resolved`: 3 attempts, 1 s apart, one RPC per in-flight row), so a lookup that keeps failing adds about 2 s per event for up to 14 days. It also adds more LDK calls under the manager lock (LSP-012). Plausible; from the PR code.
  - Deploy hazards from its own description: deploy only with no top-up in flight; never roll back to an older daemon while one is in flight; the 14-day release is by clock, not blocks.
- **#415 (do not deduct a payment that is still pending)** fixes #324 for the backstop, but the forward and splice-confirm handlers still read the live balance, and two failed `GetBalances` calls in a row fall back to today's behavior.

## Fix plan

_Filled in during phase 5._
