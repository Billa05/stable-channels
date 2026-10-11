# Review E: persistence, crash consistency and concurrency (LSP)

Base: `main` @ 9aff5ec (working branch adds only docs). Open PRs checked: `upstream-pr-400`, `upstream-pr-415` (415 stacks on 400).
Read-only review. No build or tests run. Paths are relative to the repo root unless noted; `sm415:` means `server/stable-channels-lsp/src/stable_manager.rs` on `upstream-pr-415`. `SM` means `server/stable-channels-lsp/src/stable_manager.rs` on main, `EL` means `event_loop.rs` and `BF` means `backfill.rs` in the same directory.

## 0. Short answers to the four questions

**Q1 Crash windows / transactions / sqlite config.**
- The DB primitives that change the books are each one `BEGIN IMMEDIATE` transaction that also appends the ledger row. These are `save_channel_inner` (db.rs:1096-1265), `record_stability_settlement_with_rollback` (db.rs:4226-4385), `rollback_failed_stability_settlement` (db.rs:4498-4626), `mark_settlement_succeeded` (db.rs:4389-4495), `mark_channel_closed` (db.rs:1814-1883) and the signed pay-back path (db.rs:3308-3575).
- Exception: `persist_trade_acceptance` is atomic for the books and the decision row, but it writes no ledger row (db.rs:1513-1565).
- The network send is never covered by an intent record, except for trade-fee refunds and trade responses. Main gaps: E-1, E-2, E-3, E-4.
- sqlite config: no PRAGMAs are set. The defaults apply:
  - rollback journal (`DELETE`), not WAL;
  - `synchronous=FULL`, so commits are durable;
  - `busy_timeout` 5 s (rusqlite default, `inner_connection.rs:121`);
  - `foreign_keys` on (bundled default, plus ledger.rs:233).
- There is one `Connection` behind `Arc<std::sync::Mutex>` (db.rs:272-275), shared by every task.
- Files:
  - Book DB: `<storage.disk.dir_path>/stablechannels.db` (db.rs:98, main.rs:110). It is separate from LDK Server's store.
  - Push tokens: `push_tokens.db` in the same directory, opened on every call (push/tokens.rs:13-19).
  - The schema code is shared with the desktop wallet (src/user.rs:770), but the file is not.
  - Side note: the DB is not network-scoped, while `api_key` is (`<dir>/<network>/api_key`, config.rs:97-101). Two networks that use one `dir_path` would share books.

**Q2 Poison rows and migrations.**
- Most list reads are fail-closed for everyone: one bad row fails the whole read.
  - `channels`: the daemon refuses to start (E-8).
  - Pending settlements: live event dispatch stops for every channel (E-5).
  - Inbox: `pending_inbound_stability_settlements` fails as a whole.
  - Trade responses: `due_trade_responses` fails as a whole.
- Numeric money columns of `channels` are coerced silently (`unwrap_or(0)`, `as u64`) instead of failing (E-8).
- Migrations:
  - Each step is idempotent.
  - The two table rebuilds and the ledger backfills are transactional and have completion markers.
  - Every `ALTER TABLE` error is swallowed, and there is no schema version (E-7; #38 is closed but the pattern is still on main).

**Q3 Concurrency.**
- One `tokio::sync::Mutex<StableChannelManager>` (state.rs:28) serializes the tick, event dispatch, the wake pass, REST edit/list and startup or reconnect reconcile. The channel books therefore have a single writer.
- DB writers outside that lock touch only settlement, decision and refund tables, never `channels`:
  - backfill outcome reconciliation;
  - the trade-response worker;
  - the refund handler.
- The manager lock is held across every LDK Server gRPC call, and none of those calls has a timeout. That gives head-of-line blocking across all channels (E-9).
- No `std::sync::Mutex` is held across `.await`: the DB guard always lives inside a sync function, and the push lock is released around the send (push/mod.rs:156-161).
- Blocking sqlite and JSONL I/O run on runtime worker threads under the manager lock.
- Ordering bug: live events are dispatched before the manager is hydrated (E-3).
- Atomics (#412): correct, only stronger than needed (E-12).

**Q4 Ledger.**
- Book-changing DB primitives append before/after snapshots with payment, settlement and channel refs and dedup keys, in the same transaction. That is enough to reconstruct what was paid and why for committed transitions.
- Gaps:
  - Several money-relevant rows are written outside the state transaction, so they can be missing or false after a crash or a failed save.
  - There is no tamper evidence: no hash chain and no UPDATE/DELETE guards.
  - The JSONL fallback, which is the only record while SQLite fails, rotates away.
- Details in E-11.

## 1. What is done right

- **Save first, then memory, then SYNC, for the backstop and splice corrections.** The exact correction is kept in `PendingBookUpdate` and retried, and an event barrier stops later events from overtaking it (#326). Evidence: SM:298-305, 385-433, 2233-2262, 2914-3018; `lock_for_event` SM:370-383; EL:385-397.
- **Every DB primitive that changes the books commits the ledger row in the same transaction** (`save_channel_inner` db.rs:1096-1265, `record_stability_settlement_with_rollback` 4226-4385, `mark_settlement_succeeded` 4389-4495, `mark_channel_closed` 1814-1883). The mirror is written only after commit (`mirror_committed_ledger_event`).
- **Compare-and-swap guards stop late events from undoing newer allocations.**
  - The failed-top-up rollback restores the books only if they still equal the optimistic snapshot (db.rs:4555-4562).
  - The signed pay-back checks `backing_before` (db.rs:3409-3415).
  - Trade acceptance CAS-es on `sync_version` (db.rs:1543-1550).
  - `save_channel_after_spend` moves a pending rollback snapshot by the same spend (db.rs:1140-1158).
- **The signed pay-back uses a durable inbox before any accounting.**
  - Registration detects replays and conflicts (db.rs:4053-4133).
  - The allocation, the `payments` row, the inbox transition to `applied` and the ledger row are one transaction (db.rs:3308-3575, `state='applied'` at 3473).
  - Pending rows are retried from the DB on every tick (SM:1952-2000).
  - Expiry is judged against the durable first-receipt time (SM:1621-1655).
- **Trade results use an outbox.**
  - The decision and the signed response are persisted before any send (db.rs:1484-1565).
  - Each attempt is reserved, then sent, then marked in flight (SM:478-543).
  - Backoff and a 14-day deadline apply (db.rs:1582-1645).
  - In-flight outcomes are polled every second (SM:545-593, trade_response_retry.rs).
- **Trade-fee refunds are the model for idempotent sends.**
  - The claim row carries a deterministic preimage and payment id before the keysend (revenue.rs:679-681, db.rs:957-984).
  - Only a server-reported rejection releases the claim. An ambiguous error keeps it and is resolved by looking up that id (revenue.rs:709-727).
- **SYNC is idempotent by version.**
  - The version is reserved atomically before signing (SM:2625, db.rs:1395-1414).
  - Retries rebuild from the committed books, never from memory (SM:1173-1188).
  - Outcomes are polled and stuck SYNCs are aged out (SM:1229-1270).
  - Refused sends count toward the retry cap (SM:2716).
- **A failed `PaymentSuccessful` write is not lost.** It forces a reconnect (EL:502-537), and reconnect re-polls every pending settlement from LDK before live dispatch resumes (BF:262-300).
- **Stream-gap bookkeeping is crash-safe.**
  - The gap is opened durably before subscribing (main.rs:122-127, stream_checkpoint.rs:54-134).
  - It is closed only together with a committed reconciliation result (stream_checkpoint.rs:140-181, EL:323-339).
  - The heartbeat cannot advance past an open gap (stream_checkpoint.rs:184-192).
- **Durable sqlite defaults (FULL sync), with no relaxed durability.** Schema rebuilds that matter are transactional: `trade_decisions` (db.rs:177-200), the payment-id dedup (db.rs:368-394) and the ledger reference backfills with metadata markers (ledger.rs:282-410).
- **Push and wake I/O stay off the manager lock.** The tick returns a plan and the pushes go out on a separate task (stability_tick.rs:47-61, 213-258). The wake watcher's RPC has a timeout (stability_tick.rs:263-274).

## 2. Crash-window table

Legend:
- (a) money sent but not recorded;
- (b) recorded or received but not acted on;
- (c) memory changed but DB not.

| Path | Step (file:line) | What a crash or error right after this step leaves | Handled? |
|---|---|---|---|
| Top-up (LSP→user) | sign payload SM:2372 | nothing | yes |
| | `spontaneous_send` SM:2451, before the record commits | (a). After restart the cooldown is 0 (SM:3712) and the backing is unchanged, so the first tick re-sends | **no**, E-1 (#411) |
| | `spontaneous_send` returns Err for a transport failure after LDK queued the payment (SM:2519-2529) | (a). No cooldown is set; the next tick re-sends with a new random preimage | **no**, E-1 |
| | `record_stability_settlement_with_rollback` SM:2467 fails (Err or Ok(false)) | (a). Backing is not moved, only the cooldown is set; the same drift is paid again every 120 s while writes fail | **no**, E-1 |
| | record committed, memory not yet updated (SM:2502-2506) | DB ahead of memory; restart reloads the DB | yes |
| | `PaymentSuccessful` missed, or its write fails | settlement stays pending | yes: Reconnect (EL:527-537) and backfill re-poll (BF:287-300) |
| | `PaymentFailed` missed (gap or restart), or the rollback write fails (SM:699-720) | (b). Optimistic `backing_after` stays booked; the backstop later cuts the target | **no**, E-2 (fixed by PR 400) |
| Signed pay-back (user→LSP) | `register_inbound_stability_settlement` SM:1579 fails | (b). LSP keeps the sats and the books are unchanged; the backstop cuts the target | **no**, E-6 |
| | registered, then a transient failure (lookup, signature, price, persist) | inbox row stays pending | yes, retried every tick (SM:1952-2000) |
| | persist (db.rs:3308: payment, channel CAS, inbox and ledger in one transaction) | atomic | yes |
| | memory after commit (SM:1922) | DB ahead of memory | yes |
| Legacy unsigned pay-back | `record_settlement` SM:1467 (its own write), memory SM:2093, `save_channel` SM:2101 fails | (c). Memory ahead; `STABILITY_RECEIVED_RECONCILED` is still logged (SM:2116) | **no**, E-4 |
| | crash before the save | (b). Lost; the backstop cuts the target | **no**, E-4 / E-6 |
| Forwarded spend | ledger observation SM:2764 | forward recorded, books not | the backstop catches it about two ticks later (SM:2223) |
| | memory mutated SM:2829, ledger `STABLE_SPEND_DEDUCTED` SM:2839, `save_channel_after_spend` SM:2879 fails | (c). Memory ahead, the ledger claims the deduction, and SYNC goes out with unpersisted books (SM:2895) | **no**, E-4 |
| Backstop deduction | `save_channel` SM:2233 fails | correction kept in `PendingBookUpdate` (memory only) behind the event barrier | yes. Lost on crash, then recomputed from the live balance |
| Splice (Ready, tracked) | proposal, persist, memory, then SYNC via `retry_startup_sync` (SM:2914-3018) | yes. The Ready event itself is not replayed after a crash | partly: reconcile plus backstop |
| Ready (not in memory) | `save_channel(…, 0.0, 0, their_sats, None)` SM:1371-1377 | **overwrites an existing stable row with $0** when memory is not hydrated | **no**, E-3 |
| Hardened trade | `record_settlement('trade')` SM:3125 | label only | yes |
| | crash before `persist_trade_acceptance` | (b). Fee received, no decision, no response; never recovered | **no**, E-6 |
| | `persist_trade_acceptance` SM:3365 (channel CAS, decision and envelope in one transaction) | atomic | yes |
| | memory SM:3379-3388, then `TRADE_ACCEPTED` SM:3390 outside the transaction | books changed with no ledger row | partly, E-11 |
| | response: reserve SM:478, send SM:497, in-flight SM:511 | crash after send: a duplicate 1-msat response; the wallet dedups by trade_id | yes |
| Legacy trade | memory SM:3592, then `save_channel` SM:3604 fails and returns | (c). Target lives only in memory, no SYNC | **no**, E-4 |
| SYNC | reserve version SM:2625, sign, send, then `record_sync_payment` | untracked SYNC; restart or retry sends a newer version | yes (idempotent by version) |
| Trade-fee refund | claim with deterministic id, send, record (revenue.rs:679-729) | ambiguous error resolved by id lookup | yes (model pattern) |
| Close | memory removed SM:929-939, then `mark_channel_closed` fails | memory gone, DB row active | yes: reconcile soft-closes non-live rows (SM:1016-1036) |
| Edit | DB SM:880, memory, then audit | yes. Note-only edit with no prior in memory writes $0 | partly, E-3 |

## 3. Findings

### E-1: A top-up has no durable intent and no idempotency key. A crash, a DB write error or an ambiguous send error makes the LSP pay the same shortfall again, every cooldown while the DB keeps failing
- Severity: High
- Confidence: Confirmed for the crash and DB-error paths. Plausible for the ambiguous-error path. Unverified step: that LDK Server can queue a keysend while the client still sees a transport error.
- Where:
  - SM:2372-2530 (send at 2451, record at 2467, memory at 2502-2506, Err branch at 2519-2529);
  - the cooldown is memory-only: `build_stable_channel` sets 0 (SM:3712), and `reconcile_from_grpc` rebuilds the list on every stream reconnect (SM:1068, EL:305-308).
- Related: #411
- Status on main: open. **Not fixed by PR 400/415.** The order is the same there (sm415:3036 send, then 3052 `record_stability_top_up_in_flight`), and sm415:3087-3090 says "the cooldown is the only brake left".
- What happens:
  1. **Crash between send and commit.** The DB keeps `backing_before`. On restart `last_stability_payment=0`, so the first tick computes the same drift from `backing_before` and pays again. When the first payment succeeds, `mark_settlement_succeeded` finds no row (EL:518-534 logs only `PAYMENT_SETTLED`). The books count one top-up; the user received two.
  2. **The record write fails** (I/O error, full SC volume, external lock held longer than the 5 s busy timeout, a column missing because an `ALTER` failed silently, see E-7):
     - `persisted=false`, so backing does not move and only the cooldown is set (SM:2502-2506).
     - 120 s later the tick sees the identical drift (`stable_usd_value = backing_sats * price`, SM:2287-2291) and pays it again. After a stream reconnect this happens after 60 s, because the cooldown was rebuilt as 0.
     - This repeats for every below-par channel for as long as writes fail. Each extra payment lands as unbooked native sats on the user side.
  3. **Ambiguous `spontaneous_send` error** (connection reset, LDK Server restart mid-call): it is treated as "nothing left", no cooldown is set, and a fresh random preimage is used on the next tick. The refund path makes exactly this distinction (revenue.rs:709-727); the top-up path does not.
- Why it matters:
  - The LSP overpays by the drift amount each time. In case 2 the drain has no time bound: for example $1,000 stable at a 2% drop is about $20 every 120 s, about $600 per hour per channel.
  - The overpayments are invisible in the books.
  - It needs a fault on the LSP host; an outside party cannot trigger it.
- Fix:
  - Before sending, write an intent row: settlement_id, channel, amount, and a preimage and payment id derived from settlement_id (as `trade_fee_refund_preimage` does).
  - Send with that preimage. If the intent write fails, do not send.
  - Allow no new top-up for a channel while it has an open intent; this must survive a restart.
  - On a send error or a missing record, resolve by `get_payment_details(derived id)`. LDK refuses a duplicate payment id, so a re-send can never create a second payment.
  - Derive the cooldown from the newest intent's `recorded_at` instead of memory.

### E-2: A missed or unpersisted `PaymentFailed` for a top-up is never rolled back, so the optimistic backing stays booked and the backstop then cuts the user's target
- Severity: High
- Confidence: Confirmed
- Where:
  - EL:588 is the only production caller of `handle_failed_stability_payment`;
  - SM:699-720: on a DB error it audits, returns `None`, and the event is consumed;
  - BF:282-288: outcome reconciliation handles `Failed` only for `kind='sync'`, so a failed `stability` row is skipped;
  - SM:2467-2506: backing moves to `backing_after` at send time.
- Related: #392 (same end state for an unclaimed top-up), PR 400
- Status on main: open. **Fixed by PR 400:** every tick and every event polls each in-flight top-up and each legacy pending row (sm415:917-1000).
- What happens:
  1. A top-up is sent and recorded, so backing becomes `backing_after`.
  2. The HTLC later fails (the wallet rejects an expired envelope, the peer drops, a route fails).
  3. The `PaymentFailed` event is lost if either:
     - it fires while the SC daemon is restarting or in a stream gap (LDK Server drops events that have no subscriber; ldk-server main.rs ~655); or
     - `rollback_failed_stability_settlement` hits a write error.
  4. Nothing revisits the row. The books keep `backing_after`, while the live balance shows `backing_before`.
  5. Outcome:
     - If native < gap: after two ticks the backstop (SM:2223-2262) deducts the gap from `expected_usd` and SYNCs the wallet.
     - Otherwise: the user's native sats are silently reclassified as backing.
  6. The row stays `pending` forever and is re-queried on every reconnect.
- Why it matters: the user loses the value of a top-up they never received, and nothing alerts the operator.
- Fix: on main, poll pending `stability` rows (in the tick and in `reconcile_pending_settlement_outcomes`) and apply `rollback_failed_stability_settlement` when LDK reports `Failed`. Or land PR 400.

### E-3: Auto-registration on `ChannelReady` (and a note-only edit) overwrites an existing stable position with $0 when memory is not hydrated, and live events are dispatched before hydration
- Severity: High
- Confidence: Confirmed (code path traced end to end). Hitting it needs a restart while the price cache is cold, which lasts as long as the price feeds are down or quarantined.
- Where:
  - EL:305-311: the reconnect reconcile is skipped when the price is cold, and backfill plus live dispatch go ahead;
  - main.rs:166-196: the startup reconcile waits at most 60 s for a price, then gives up;
  - stability_tick.rs:42-46: the tick skips while the price is cold, so `reconcile_if_empty` never runs;
  - SM:1303-1306: whether a channel is "tracked" is a memory-only check, followed by SM:1371-1377 `save_channel(channel_id, uid, 0.0, 0, their_sats, None)`;
  - db.rs:1163-1176: the UPDATE by `user_channel_id` replaces `expected_usd`, `stable_sats` and `note`, and clears `closed_at`;
  - edit: SM:806-817 falls back to 0.0, and SM:880-887 saves it.
- Related: none found (overlaps area D).
- Status on main: open. Unchanged in PR 400/415 (same save at sm415 ~1901).
- What happens:
  1. The daemon restarts while the price feeds are unreachable or quarantined, so `get_fresh_cached_price_no_fetch()` returns 0.
  2. The event loop subscribes, skips hydration and starts live dispatch with `stable_channels` empty.
  3. A `ChannelStateChanged(Ready)` arrives for an existing stable channel. LDK Server emits Ready on splice lock (ldk-server main.rs:442-469), for example when the user's splice-in or splice-out confirms.
  4. `handle_channel_ready` does not find the uid in memory and "auto-registers" it, writing `expected_usd=0, backing=0, note=NULL` over the persisted books.
  5. When the price comes back, `reconcile_from_grpc` loads $0 from SQLite.
  6. No SYNC is sent, because the startup SYNC covers only `expected_usd>0` (SM:1074-1078). The wallet keeps its target.
  7. From then on the LSP never tops up, while it still keeps the wallet's pay-backs (`backing_after_user_to_lsp_stability(0, 0, …)` returns `Some(0)`, stable.rs:762-788).
  8. Other events in the same window are also mishandled:
     - forwards are skipped as "not on a stable channel" (SM:2781-2783);
     - hardened trades are rejected with `InternalFailure`;
     - legacy trades are dropped;
     - unsigned pay-backs are left unattributed.
  9. An operator note-only edit while the list is empty also writes $0 (SM:806-817).
- Why it matters: the user silently loses stability protection, and the LSP books lose the position. No alarm is raised.
- Fix:
  - Gate live dispatch until `reconcile_from_grpc` has succeeded once. Keep the events in the existing unbounded buffer. Hydrating the books needs no price; the USD fields can be filled later.
  - In `handle_channel_ready` and in edit, consult the DB (`load_channel`), not memory.
  - Register new channels with `INSERT … ON CONFLICT DO NOTHING`, never by overwriting an existing row with defaults.

### E-4: Memory-before-DB ordering (#321 class) is still present on the forwarded-spend, legacy-trade and legacy pay-back paths, and the forward path still SYNCs unpersisted books
- Severity: Medium
- Confidence: Confirmed for the ordering. The step "SYNC with unpersisted values" is Plausible: it needs `save_channel_after_spend` to fail while `next_sync_version` succeeds, for example a failure inside the save transaction (ledger append, settlement snapshot UPDATE) or a transient lock.
- Where:
  - Forward: SM:2829 (`reconcile_forwarded` mutates `sc` in place), SM:2839 (`STABLE_SPEND_DEDUCTED` goes to the ledger before the save), SM:2879-2891 (a save failure is only audited), SM:2895-2905 (SYNC from the in-memory values).
  - Legacy trade: SM:3592 (`*sc = updated`) runs before the save at SM:3604. On failure it returns at SM:3612-3617 with memory already changed.
  - Legacy pay-back: memory at SM:2093-2098 before the save at SM:2101; SM:2116 logs `STABILITY_RECEIVED_RECONCILED` even after a failed save.
- Related: #321, #326 (which fixed only the backstop and splice paths)
- Status on main: partially fixed by #326. PR 400/415 leave these paths as they are (sm415:3460).
- What happens after a save failure:
  - The tick, the SYNC logic and later writes work from unpersisted books.
  - A later write that copies values from memory (`record_stability_settlement_with_rollback` writes `expected_usd` and native from memory, db.rs:4282-4296) silently persists them.
  - A restart instead reverts to the pre-spend row. The backstop then deducts again, at a different price, and the wallet can receive two SYNCs with different targets.
  - The ledger already holds `STABLE_SPEND_DEDUCTED` for a deduction that never committed.
  - Legacy trade: the fee is kept and the target change exists only in memory, with no SYNC. Whether the trade survives depends on what comes first: a restart, or another write from memory.
- Why it matters: the books, the ledger and the wallet can disagree, and the outcome depends on timing. It mostly heals through the backstop, but at the wrong price and with confusing SYNCs.
- Fix:
  - Route all three paths through `PendingBookUpdate`: compute `proposed` on a clone, save it, then swap it into memory, then audit and SYNC.
  - Write the audit row inside the save transaction.

### E-5: One pending settlement whose LDK lookup errors stops all live event dispatch
- Severity: Medium
- Confidence: Plausible. The control flow is confirmed. Unverified step: a persistent per-payment error from `GetPaymentDetails`, from a malformed id or from an LDK store read or decode failure for that entry; ldk-server `api/get_payment_details.rs:20-21` passes both up.
- Where:
  - BF:267-279: `?` on each lookup aborts the whole pass;
  - EL:340-346: `settlement_outcomes_safe=false` leads to `continue`, a re-subscribe, and dispatch is never reached;
  - db.rs:4639-4648: the query reads every pending row, with no age bound.
- Related: #416 (same class for PR 400's list of top-ups on the way)
- Status on main: open. PR 400 moves new-style top-ups to a separate list that has the same global fail-closed behaviour (#416).
- What happens:
  - Every loop iteration re-subscribes, re-runs `reconcile_from_grpc` and a full backfill (up to 20 `list_payments` pages), fails on the same row, sleeps 1 s and starts over.
  - Events buffered in that iteration are dropped together with the reader.
  - Trades, pay-backs, forwards and `PaymentFailed` events are lost for every channel until someone fixes the row by hand.
  - Meanwhile the tick keeps paying top-ups, and the backstop turns missed forwards and pay-backs into target cuts.
- Why it matters: one bad row stops all events for every channel. The lost events also lead to wrong bookings that do not heal.
- Fix:
  - Handle errors per row: a row that fails blocks only its own channel, raises an alarm with its payment id, and moves to an operator queue once it is old enough.
  - Never gate global dispatch on a single row.

### E-6: Inbound protocol payments that arrive during an event gap, or whose first DB write fails, are never processed
- Severity: Medium
- Confidence: Confirmed. No replay or polling path exists, and stream gaps are routine (restart, reconnect).
- Where:
  - SM:1579-1597: an error from `register_inbound_stability_settlement` is logged as `STABILITY_PAYMENT_REPLAY_CONFLICT` and the handler returns. No inbox row exists, so `retry_pending_signed_stability` (SM:1952) never sees it.
  - SM:3125-3132: a failed trade settlement record is only audited.
  - BF:105-140: reconnect only audits payments as `PAYMENT_RECONSTRUCTED`; custom TLVs are not processed again.
- Related: none
- Status on main: open. Not addressed by PR 400/415.
- What happens:
  - **Pay-back that misses the inbox.** The LSP keeps the sats and the books do not change. The live balance drops below backing, and after two ticks the backstop deducts the gap from `expected_usd`. The user pays the drift and then loses it a second time from their target.
  - **Trade fee received in a gap.** There is no decision row and no response is sent. The refund tool refunds only rejected decisions (revenue.rs:668-670), so this fee cannot be refunded through it.
- Why it matters: the user loses money with no alert, and the operator tool cannot fix it.
- Fix:
  - Treat a DB error on registration as retryable: return `Reconnect`, as `PaymentSuccessful` already does, instead of logging a conflict.
  - Before the backstop cuts a target, check `list_payments` for inbound keysends from that peer since the gap that have not been classified. If one is found, hold the cut and raise an operator alarm.
  - Longer term, have wallets keep a settlement or trade pending until they receive an LSP-signed acknowledgement of its settlement_id or trade_id.

### E-7: Schema migrations swallow every `ALTER TABLE` error, and there is no schema version
- Severity: Medium
- Confidence: Confirmed for the error handling. The consequence is Plausible: it needs an `ALTER` to fail for a reason other than "duplicate column".
- Where:
  - db.rs:463-722: 34 `let _ = conn.execute("ALTER TABLE …")` sites. The creation of `idx_settlement_sync_version` is also unchecked.
  - No `PRAGMA user_version` anywhere.
- Related: #38 is closed, but main still has the pattern. PR 415 adds two more such ALTERs (`top_up_sats`, `top_up_offset_sats`).
- Status on main: open.
- What happens:
  - On the first boot after an upgrade, an `ALTER` that fails with BUSY (lock held longer than 5 s), an I/O error or a full disk is swallowed, and startup succeeds.
  - The first money write that names the column then fails at runtime. For example, if `settlement_payments.backing_sats_before` is missing, every top-up record fails, which is E-1 case 2 for every top-up.
  - Other statements fail hard (`CREATE UNIQUE INDEX idx_trades_trade_id`, db.rs:493-497). A partial migration therefore fails closed in some places and open in others.
  - Without a version, a downgrade is not detected. PR 400 itself warns "Do not roll back to an older daemon while a top-up is on the way".
- Why it matters: a one-off failure at upgrade time turns into a permanent fault on the money path.
- Fix:
  - Run `init_schema` in one IMMEDIATE transaction.
  - Ignore only the "duplicate column name" error.
  - Check the required columns with `PRAGMA table_info` and refuse to start if any is missing.
  - Set `PRAGMA user_version`, and refuse to open a DB whose version is newer than the binary.

### E-8: One unreadable `channels` row stops the daemon from starting, and corrupt numeric cells are read as 0 or as huge values
- Severity: Low. It needs storage damage or a hand edit, but it takes down the whole LSP.
- Confidence: Confirmed
- Where:
  - main.rs:128-131: `load_all_channels()?` aborts startup;
  - db.rs:1942-1969: `row?` fails the whole load; at 1951-1952 `row.get(3).unwrap_or(0)` and `as u64` are applied to i64 values;
  - SM:995-1006: reconcile returns early and leaves the list empty, which opens the E-3 window.
- Related: #416
- Status on main: open.
- What happens:
  - A row whose `channel_id`, `expected_usd` or `note` cannot be read makes the daemon exit at boot.
  - A `stable_sats` cell of the wrong type is read as 0 backing.
  - A negative `stable_sats` becomes about 1.8e19, which the backstop treats as a huge unreconciled spend.
- Why it matters: one damaged row takes down the whole LSP, or silently produces wrong books.
- Fix:
  - Read the rows one at a time. Quarantine a bad row: skip its channel, raise an alarm with its uid, keep the others.
  - Use `try_from` for money columns, and treat a failure as "row unreadable", not as 0.

### E-9: One manager lock is held across LDK Server calls that have no timeout, so every channel waits on the slowest call
- Severity: Medium
- Confidence: Confirmed
- Where:
  - state.rs:28.
  - stability_tick.rs:47-57: the lock is held across `reconcile_if_empty` and `run_tick_plan`. These make the following calls in sequence:
    - `list_channels`;
    - `sign_message` and `spontaneous_send` for every channel;
    - `get_payment_details` for every pending SYNC (SM:1229-1270);
    - up to 32 × (`list_channels` + `verify_signature`) for the inbox (SM:1952-2000).
  - EL:388 (every event), stability_tick.rs:297-299 (wake pass), handlers/stable_channels.rs:50 and 84.
  - No timeouts in `stable_manager.rs`, unlike stability_tick.rs:263.
  - `LdkServerClient` builds a reqwest `Client` with no timeout (ldk-server-client `client.rs:120-123`, rev bd95e18).
- Related: none
- Status on main: open. PR 400 adds, under the same lock, a `get_payment_details` call per in-flight top-up before every event (sm415:437-458, 917-990).
- What happens:
  - One hung or slow LDK Server call stalls everything:
    - all event dispatch: the buffer grows without bound and is lost on restart;
    - every channel's tick;
    - `ListStableChannels` and `EditStableChannel`.
  - With N channels below par, one tick holds the lock for 2N sequential RPCs, and trades wait behind it (`TRADE_RESULT_TIMEOUT`).
  - `lock_for_event` is a global barrier: a correction that cannot be saved for one channel holds back events for every channel (SM:370-383).
  - Blocking work also runs on async worker threads while the lock is held: sqlite commits with FULL fsync and the JSONL appends. The revenue rebuild's reader (revenue.rs:512-523) competes for the same single DB mutex.
- Why it matters: one slow LDK call delays settlements and trades for every user, and buffered events are lost on restart.
- Fix:
  - Wrap every LDK call in `tokio::time::timeout`, and treat a timeout as an ambiguous outcome (see E-1).
  - Build the tick plan under the lock, release the lock for the network sends, then re-acquire it to commit. Per-channel in-flight markers keep other tasks off those channels.
  - Better: one actor per channel (a single writer per channel), with the global barrier made per-channel.

### E-10: Event-stream liveness is inferred from the absence of errors, so a half-open stream keeps advancing the health checkpoint
- Severity: Low
- Confidence: Plausible. Unverified step: an HTTP/2 stream that stops delivering without returning an error. Neither side configures keepalive (ldk-server-client `client.rs:808`, ldk-server `service.rs:470-503`). On loopback deployments this is rare.
- Where: EL:41-75 (`active` is cleared only on error or drop), EL:159-183 (checkpoint while active and the queue is empty), stream_checkpoint.rs:184-192. In addition, LDK Server skips lagged events with no signal to the client (`service.rs:490-491`).
- Related: none
- Status on main: open.
- What happens: events are lost while the checkpoint keeps recording "healthy up to now". No gap is opened, backfill does not run, and the ledger's coverage claim is wrong.
- Why it matters: events can be lost with no gap on record, so nothing is backfilled and nobody is alerted.
- Fix: enable HTTP/2 keepalive on the streaming client, or have an application heartbeat. Treat a missed heartbeat as a gap.

### E-11: The ledger is append-only by convention only, and several money-relevant audit rows are written outside the transaction that changes the books
- Severity: Low
- Confidence: Confirmed
- Where:
  - ledger.rs:231-279: no UPDATE or DELETE triggers, no hash chain, and refs use `ON DELETE CASCADE`.
  - audit.rs:47-79: state events are appended in a transaction of their own. audit.rs:121-147: the JSONL mirror rotates at 50 MB and keeps one generation.
  - Rows written outside the book transaction:
    - `STABLE_SPEND_DEDUCTED` before the save (SM:2839 vs 2879);
    - `STABILITY_RECEIVED_RECONCILED` after a failed save (SM:2116);
    - `STABILITY_PAYMENT_V1_SENT` before the settlement record (SM:2454);
    - `TRADE_ACCEPTED` (SM:3390) after `persist_trade_acceptance`, which changes `channels` and `trade_decisions` with no ledger row (db.rs:1513-1565).
- Related: none
- Status on main: open.
- What happens:
  - A crash between the trade commit and its audit row leaves a change to the books with no ledger row. It can be reconstructed only from `trade_decisions`.
  - A failed save leaves a ledger row that claims a change which never committed.
  - Anyone with write access to the file can edit or delete rows without leaving a trace.
  - While SQLite is down, the JSONL file is the only record, and it rotates.
- Why it matters: an operator cannot fully reconstruct what was paid and why from the ledger alone, and cannot detect edits.
- Fix:
  - Append every book-changing event inside the same transaction: add the ledger row to `persist_trade_acceptance`, and route the E-4 paths through the save transactions.
  - Add a hash chain column (`hash = H(prev_hash‖row)`) and BEFORE UPDATE/DELETE triggers on `ledger_events`.
  - Periodically write the chain head to the JSONL file or to an external store.
  - Do not rotate the JSONL file away while SQLite writes are failing.

### E-12 (Info): The atomics in the event reader (#412) are correct; their orderings are just stronger than needed
- Severity: Info
- Confidence: Confirmed
- Where: EL:41-75, 171-175.
- Related: #412
- Status on main: open as an issue; no bug.
- What happens: nothing goes wrong. The `AtomicBool` only publishes a liveness flag, and no other data depends on it: the mpsc channel synchronizes the items. Release/Acquire is harmless, and Relaxed would be just as correct. The part that matters is the check order: the time is sampled before the liveness and queue checks, and the code gets that right.
- Why it matters: it does not affect correctness.
- Fix: none needed beyond #412's simplification.

## 4. Design observations (structural causes and the design change that closes each class)

1. **Use an intent log with idempotency keys for every outbound value send.** The refund path (deterministic preimage, claim before send, resolve by id) and the trade-response outbox already show how. Top-ups should follow:
   - record the intent (settlement_id, channel, amount, derived payment id) before sending;
   - send with a preimage derived from settlement_id;
   - resolve ambiguity with `get_payment_details`.

   This closes E-1. PR 400's claim-time booking is complementary: it fixes what the books contain, not whether a send was recorded.
2. **The event stream delivers each event at most once; treat it that way.** LDK Server drops events when no one is subscribed, skips lagged events and never replays. Every money effect that is driven by an event needs a polling backstop that reads LDK's payment store:

   | Event-driven effect | Polling backstop |
   |---|---|
   | settlement success | yes |
   | SYNC outcome | yes |
   | trade response | yes |
   | failed top-up | **no** on main (E-2); yes in PR 400 |
   | inbound protocol payment | **no** (E-6) |
   | forward | approximated by the balance backstop |
3. **Make SQLite the single source of truth and memory a cache, enforced by the type system.**
   - Make `stable_channels` private, and change it only through a `commit(uid, proposed)` that saves first, then swaps it in memory, then audits or SYNCs. That closes E-4 by construction.
   - Never infer "not in memory, so it is new" (E-3). Gate dispatch on hydration.
4. **Persist the small control state that guards money.** Today these live only in memory:
   - the per-channel cooldown and last send;
   - `PendingBookUpdate`;
   - knowledge of an unrecorded send.

   A restart or a stream reconnect erases them (E-1). Store them as DB intents, or derive them from intents.
5. **Use one writer per channel instead of one global lock and global barriers.** A per-channel actor or queue removes head-of-line blocking (E-9). It also lets every fail-closed read be scoped to its own channel, so that one bad row pauses only its own channel (E-5, E-8, #416). Fail-closed is right for safety, but it should apply per channel, not to everyone.
6. **Bound every LDK call with a timeout, and classify the outcome.** The three outcomes are: rejected (nothing left), succeeded, and ambiguous (resolve by id). This should be one helper used by every send, as `refund_trade_fee` already does inline.
7. **Make the schema explicit:**
   - versioned migrations in a single transaction;
   - verification of the required columns after migrating;
   - `user_version`, so the binary refuses a newer DB and a downgrade is caught.
8. **Make the ledger trustworthy:**
   - every change to the books commits its ledger row in the same transaction;
   - a hash chain plus no-UPDATE/no-DELETE triggers;
   - the chain head exported to the JSONL file or to an external store.
