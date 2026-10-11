# Review A: stability engine (top-ups and pay-backs)

Base: `main` @ 9aff5ec. PR branches: `upstream-pr-400` (b7e86cf), `upstream-pr-415` (fd4c873).
Paths are relative to the repo root. `SM` = `server/stable-channels-lsp/src/stable_manager.rs` on main.
PR 400 line numbers refer to `git show upstream-pr-400:<path>`.
Read-only review. Nothing was built or run.

## 1. What is done right

- **One serialization point for every money path.** The tick, the wake pass, event dispatch and operator edits all take the same `tokio::sync::Mutex<StableChannelManager>`: `stability_tick.rs:47-57`, `stability_tick.rs:297-299`, `event_loop.rs:388`, `handlers/stable_channels.rs:84`. Ticks cannot overlap (`MissedTickBehavior::Delay`, `stability_tick.rs:37-38`). A tick cannot interleave with a trade, a forward or an outcome event, so "two ticks pay the same drift" and "tick races a forward" are not possible on main.
- **The event stream is drained off the lock.** A reader task copies events into an unbounded channel (`event_loop.rs:50-62`). A long lock hold therefore delays events but does not make LDK Server's 1024-slot broadcast lag and drop them (`ldk-server/src/main.rs:308`, `service.rs:490`). Wake pushes are also sent outside the lock (`stability_tick.rs:58-61`, `213-258`).
- **The accounting price is guarded.** It needs at least 3 feeds within 5% of each other, a move of at most 10% from the last trusted price, an age of at most 60 s, and a quarantine after a large move (`src/price_feeds.rs:15-18`, `134-137`, `245-249`). The tick and the wake pass skip work when the price is cold (`stability_tick.rs:42-46`, `292-296`; `SM:2157`).
- **The LSP never pulls.** When the user is above par the tick only checks (`SM:2531-2547`). Any surplus stays in `backing_sats` as debt, so a wallet that refuses to pay back cannot also collect the next top-up: the next fall first eats the unpaid surplus.
- **Signed inbound pay-backs are well bound** (`SM:1500-1950`):
  - The direction must be user-to-LSP (`SM:1556`) and the signed amount must equal the received amount (`SM:1567`).
  - The envelope is registered in a durable inbox *before* any accounting. `settlement_id` is the primary key and `payment_id` is UNIQUE, and a replay whose fields conflict is rejected (`src/db.rs:4053-4133`).
  - Expiry is judged at the first receipt time, so retries are safe (`SM:1621-1649`).
  - The signature must come from the named channel's counterparty (`SM:1729-1765`).
  - The allocation is written by an atomic compare-and-swap with one reload-and-retry (`SM:1786-1830`).
  - The inbox is retried on every tick (`SM:2156`, `1952-1999`).
  - A malformed signed record is never downgraded to the unsigned marker (`SM:1418-1425`).
- **Pay-back math cannot be shaved.** `backing_after_user_to_lsp_stability` reduces backing by the amount actually paid, floored at equilibrium. It clamps to the live balance only for the drop that this payment explains (`src/stable.rs:762-800`), so a 1-sat payment cannot erase a large surplus.
- **Main's optimistic top-up booking is atomic and reversible.** The settlement row, the optimistic allocation and the ledger event are written in one transaction (`src/db.rs:4226-4387`). Rollback is a compare-and-swap on backing, native and target, so a late failure cannot overwrite a newer trade or SYNC (`src/db.rs:4498-4627`).
- **An unsaved correction is a barrier.** A failed save is kept as a `PendingBookUpdate`, and both events and ticks wait until it commits (`SM:370-458`, `SM:2148-2153`).
- **The backstop is debounced.** It needs 2 regular ticks (`SM:2190`, `2223-2281`). A wake pass neither advances the count nor tops up a channel that may have an unreconciled spend (`SM:2217-2222`).
- **PR 400 (if merged).** A durable in-flight row blocks a second top-up (`upstream-pr-400 SM:2873`). An unreadable list fails closed (`SM:900-923`). The claim is booked atomically and only once (`src/db.rs` `claim_stability_top_up`). Missed outcomes are recovered by asking the node before every tick and every event (`SM:419-437`, `900-975`), including legacy rows (`SM:977-1021`).

## 2. Findings

### A-1: Send-before-record: a top-up whose record fails is sent again every 120 s, without bound
- Severity: High
- Confidence: Confirmed
- Where: `SM:2451` (send) → `SM:2464-2501` (record) → `SM:2502-2507` (cooldown set, backing changed only if persisted). Cooldown is memory-only: `SM:3712` (`last_stability_payment: 0` on every rebuild), and `reconcile_from_grpc` runs on every stream reconnect (`event_loop.rs:300-308`).
- Related: #411
- Status on main: open. PR 400 does not fix it: same order at `upstream-pr-400 SM:3000-3051`, and `if !persisted { sc.last_stability_payment = now }`.
- What happens:
  1. The tick sends a top-up and `spontaneous_send` returns Ok.
  2. `record_stability_settlement_with_rollback` fails, for example with SQLITE_FULL on a full disk or an I/O error, while reads still work. The process has a single SQLite connection (`src/db.rs:406`), so writes fail only on storage errors.
  3. `persisted = false`. `sc.backing_sats` is unchanged and only the in-memory cooldown is set.
  4. 120 s later the tick computes the same drift from the same `backing_sats`. The live balance is ignored when `backing_sats > 0` (`SM:2287`). The tick pays again.
  5. Each extra top-up lands in the wallet as native sats, because `backing_after_lsp_to_user_stability` keeps backing at equilibrium (`src/stable.rs:806-838`). The wallet never pays it back.
  6. The PaymentSuccessful for an unrecorded payment matches no row and is audited only as a plain `PAYMENT_SETTLED` (`event_loop.rs:514-551`). Nothing alarms.
  7. This repeats every 120 s for every below-par channel until writes recover or the LSP's outbound liquidity runs out.
  8. Crash variant: a crash between steps 1 and 2 loses the in-memory cooldown, so the first tick after restart pays again, once. A stream reconnect has the same effect on the cooldown.
- Why it matters: this drains LSP funds after a common ops failure (full disk). The rate is drift × 30 per hour per channel, and no alarm says money left without being booked.
- Fix:
  - Write the intent before sending: settlement id, channel, amount, and a preimage derived from the settlement id.
  - Send with that preimage (`SpontaneousSendRequest.preimage`). ldk-node then refuses a second send of a pending or succeeded payment with the same id (`DuplicatePayment`; ldk-node `src/payment/spontaneous.rs:63-76`), which makes resends idempotent.
  - If the intent cannot be written, do not send.
  - Until that lands: stop all top-ups for a channel after any failed record, and raise an alarm on an outbound PaymentSuccessful that matches no row.

### A-2: A send that errors after LDK has already dispatched it is retried 60 s later
- Severity: High
- Confidence: Plausible. Unverified step: that LDK Server's pinned ldk-node `f375e4d` behaves like `47dad6d`. That rev is not in the local cache.
- Where: `SM:2513-2529` (the error arm records nothing and leaves the cooldown unset: "Do not bump last_stability_payment so retry can fire").
- Related: #411 (same class, a different trigger)
- Status on main: open. PR 400: unchanged (`upstream-pr-400 SM:3060-3074`).
- What happens: two ways an error can reach the daemon after the HTLC is already queued.
  - In ldk-node `47dad6d`, `send_inner` calls `channel_manager.send_spontaneous_payment` (the HTLC is queued) and then `self.payment_store.insert(payment)?` (`src/payment/spontaneous.rs:111-135`). A store write error at that point returns Err after dispatch.
  - LDK Server can crash or restart, or the HTTP/2 stream can reset, after the node accepted the send but before the reply. The client builds `reqwest::Client` with no timeout and no idempotency key (`ldk-server-client/src/client.rs:115-125`, `513-552`).
  - In both cases the daemon audits `STABILITY_PAYMENT_FAILED` and sets no cooldown. The next tick, 60 s later, sends a fresh top-up with a new random preimage. The first one completes too, and no row exists for it.
- Why it matters: a double top-up for each such error, and the books show nothing.
- Fix: same as A-1. A deterministic preimage plus an intent written before the send turns "unknown outcome" into "query `get_payment_details` for this payment id, then decide".

### A-3: An unclaimed top-up is booked at send, then cut from the user's target as a spend
- Severity: High
- Confidence: Confirmed
- Where:
  - `SM:2446-2447` and `2467-2507`: backing is set to equilibrium at send, in the DB and in memory.
  - `SM:52-58`: the live balance is `inbound_capacity`, which leaves out the pending HTLC.
  - `SM:2223-2281`: the backstop deducts after 2 ticks.
  - `event_loop.rs:489-541`: a claim only marks the row.
  - `src/db.rs:4555-4559`: the rollback compare-and-swap fails once the target has changed.
- Related: #392, #324
- Status on main: open. Fixed by PR 400: the books change only at claim (`upstream-pr-400 SM:748-795`; `db.rs claim_stability_top_up`). The residual gap, a claim whose balance update arrives after the claim event, is documented in the PR.
- What happens: the issue's example, $50 on 50,000 sats, price down to $80k.
  1. A 12,500-sat top-up is booked at send.
  2. The phone sleeps holding the HTLC, so `their_sats` stays at 50,000 against 62,500 booked.
  3. Two ticks later `reconcile_outgoing` cuts the target to about $40 and sends a SYNC.
  4. A later claim does not restore the target. A later failure is not rolled back, because the compare-and-swap no longer matches.
- Why it matters: a routine event (a phone sleeping mid-HTLC) permanently cuts the user's USD target.
- Fix: merge PR 400. Together with PR 415, also have the backstop ignore any channel that has an LSP→user stability payment without a terminal outcome.

### A-4: On main, a top-up failure missed during a stream gap is never rolled back
- Severity: Medium
- Confidence: Confirmed
- Where: `server/stable-channels-lsp/src/backfill.rs:263-307`. It handles `sync`+Failed and any kind+Succeeded. A `stability`+Failed row falls through `if payment.status != Succeeded { continue; }`. `reconcile_sync_outcomes` (`SM:1229-1270`) covers only `sync`. No other caller reads pending stability rows.
- Related: #392 (same end state, different cause)
- Status on main: open. Fixed by PR 400: `resolve_legacy_stability_payments` applies `handle_failed_stability_payment` when the node reports Failed (`upstream-pr-400 SM:977-1021`).
- What happens:
  1. A top-up is sent and booked optimistically (A-3).
  2. Its PaymentFailed is emitted while the daemon is restarting or between stream subscriptions. LDK Server does not replay its stream.
  3. The backfill marks nothing, and the row stays `pending` forever.
  4. The books hold the unpaid sats, so `their_sats < backing` and the backstop cuts the target.
  5. Because the row stays pending, a later operator investigation finds no failure record.
- Why it matters: the target is cut after a routine restart, and the comment says this gap is closed (`backfill.rs:260-262`) when it is not.
- Fix: in `reconcile_pending_settlement_outcomes`, call the rollback for `kind = 'stability'` with status Failed. Or merge PR 400.

### A-5: A top-up for one channel can land on the peer's other channel and is still booked on the first
- Severity: Medium
- Confidence: Confirmed through LDK's router. The precondition is a peer with two or more usable channels to the LSP. The daemon explicitly supports such peers (`stability_tick.rs:73-96`, test `reconnect_ends_the_watch_once_for_the_whole_peer`; #392 discusses it).
- Where:
  - `SM:2423-2440`: the keysend goes to `node_id` with `route_parameters: None`.
  - rust-lightning `090e09f` `lightning/src/routing/router.rs:2556-2586`: first hops to the payee are chosen by outbound liquidity (ascending once a channel can carry the payment), and MPP can split the payment across channels.
  - `SM:2467` / `upstream-pr-400 claim_stability_top_up`: the settlement is booked against the channel that triggered it.
- Related: none found
- Status on main: open. PR 400/415: not fixed. The claim adds `top_up_sats` to the named channel.
- What happens:
  1. Channel A (backing 50,000, no native) is owed 12,500 sats.
  2. The peer's channel B has a smaller outbound limit that still covers the payment, so LDK routes the keysend over B (or splits it over both).
  3. The LSP books A at 62,500. A's live balance is still 50,000, so after 2 ticks the backstop cuts A's target by about $10.
  4. B's 12,500 extra sats sit as native.
- Why it matters: the user's stable position on A is cut even though nothing was spent. The value moves into a volatile balance and does not heal.
- Fix:
  - Book a top-up only on the channel whose balance actually rose, by checking per-channel balances before and after the claim.
  - Or settle per peer: aggregate the peer's stable channels, or allow one stable channel per peer.
  - At minimum, skip top-ups to a peer that has more than one usable channel and raise an alarm.
  - Set `max_path_count = 1` to stop splitting.

### A-6: The manager lock is held across every gRPC call, and those calls have no timeout
- Severity: Medium
- Confidence: Confirmed
- Where:
  - `stability_tick.rs:47-57`: the lock is held for `reconcile_if_empty` and `run_tick_plan`.
  - Inside the tick: `SM:2156` (signed inbox: list_channels and verify per row), `2160` (list_channels), `2372` (sign per channel), `2451` (send per channel), `2594-2608` (SYNC sends).
  - `ldk-server-client/src/client.rs:120-123`: `Client::builder()` sets no timeout.
  - The wake pass bounds only its own `list_channels` (`stability_tick.rs:264`). The `run_tick_plan` it then calls has no bound (`stability_tick.rs:297-299`).
- Related: —
- Status on main: open. PR 400 adds a `get_payment_details` per in-flight top-up per tick and per event, also under the lock. PR 415 adds `get_balances` per tick.
- What happens:
  - One LDK Server call that stalls (the server is stuck on a node lock, or accepts the connection and never answers) holds the manager lock indefinitely.
  - Event dispatch then blocks in `lock_for_event` (`event_loop.rs:388`). PaymentFailed rollbacks, trade decisions, forward deductions and REST `ListStableChannels`/`EditStableChannel` (`handlers/stable_channels.rs:50,84`) all stop, while the unbounded event buffer grows.
  - Without a stall, a price drop that leaves N channels below par costs 2N sequential round trips under the lock in one tick.
- Why it matters: availability of every money path depends on the slowest single RPC.
- Fix:
  - Put a `tokio::time::timeout` around each LDK call.
  - Split the tick into three steps:
    1. Decide and write intents under the lock.
    2. Sign and send outside the lock.
    3. Record outcomes under the lock.
  - Cap per-tick work.

### A-7: PR 400: one unreadable pending top-up row pauses top-ups for every channel
- Severity: Medium
- Confidence: Confirmed (in the PR code)
- Where:
  - `upstream-pr-400 src/db.rs:4433-4452`: `u64::try_from(top_up_sats)?` and `row.get(3)?` inside `rows.collect()`, so one bad row fails the whole read.
  - `upstream-pr-400 SM:909-923`: returns `None`.
  - `upstream-pr-400 SM:2873`: `top_ups_in_flight.is_none_or(..)` skips every channel.
- Related: #416
- Status on main: n/a (new in PR 400). Not fixed in PR 400.
- What happens: a row with `top_up_sats = -1` (hand edit or storage damage) makes the read fail. From then on no channel is topped up and no release works. The dashboard shows one yellow item, and the alarm is raised once per episode.
- Why it matters: it stops the peg for every user and the alarm points the wrong way. No funds move.
- Fix:
  - Parse row by row. A bad row blocks only its own channel, and that channel is still treated as having a top-up in flight.
  - Raise a red, repeating "top-ups paused" alarm when the table itself cannot be read.

### A-8: PR 400: every event waits on top-up lookups, and a lookup that keeps failing adds 2 s per event for up to 14 days
- Severity: Medium
- Confidence: Plausible. Unverified step: that one stored payment can make `get_payment_details` error persistently. The PR body says an undecodable entry does this (`PersistenceFailed`).
- Where: `upstream-pr-400 SM:419-437` (`lock_for_event_with_top_ups_resolved`: 3 attempts with a 1 s sleep between them), `SM:900-975` (an RPC per in-flight and legacy row), `event_loop.rs:300,388` (on PR 400: used for every dispatch and every reconnect).
- Related: #400
- Status on main: n/a (new in PR 400)
- What happens:
  - Before each event, the daemon queries every in-flight top-up.
  - If one lookup errors, it sleeps 1 s and tries again, twice. Then it handles the event anyway.
  - A payment whose lookup always errors becomes releasable only after 14 days (`TOP_UP_MAX_IN_FLIGHT_SECS`). Until then every event is delayed by at least 2 s plus 3×N RPCs.
  - An LSP that routes many forwards falls behind, and the unbounded event buffer grows. Trade replies and SYNC outcomes arrive late.
- Why it matters: one bad payment entry degrades the whole event pipeline for two weeks.
- Fix:
  - Resolve in-flight top-ups on a timer (the tick), not before every event.
  - Before an event, look only at the in-flight rows of the channel that event touches.
  - Remember a failing payment id and skip it for a backoff period.

### A-9: An unsigned pay-back sent right after a top-up claim is not attributed, then cut as a spend
- Severity: Medium (only legacy, pre-#288 wallets send the unsigned marker)
- Confidence: Confirmed
- Where: `SM:2044-2061` matches the drop against `sc.stable_receiver_btc`, which is set at the tick (`SM:2210`, `2285`) and not refreshed when a top-up is claimed (`event_loop.rs:489-541`). The backstop then deducts (`SM:2223-2281`).
- Related: #414, #235, #284
- Status on main: open. PR 400: not fixed (the issue reports the same result on the #400 branch). PR 400 adds `offset_pending_top_up` for an *unbooked* top-up, which does not cover this case.
- What happens: the issue's steps. The claim (+12,500) and the pay-back (−12,500) between two ticks cancel out, so the drop is 0, there is no match, `STABILITY_RECEIVE_UNATTRIBUTED` is audited, and 2 ticks later the target is cut by the amount paid back. The same miss happens whenever any other balance change lands between the snapshot and the pay-back. With two channels showing the same drop, the payment is ambiguous and nothing is booked.
- Why it matters: a user who pays exactly what is owed has the target cut.
- Fix: refresh `stable_receiver_btc` when a top-up to that channel is claimed, or match against "snapshot + claimed top-ups since". Better, retire the unsigned path (#284).

### A-10: The channel reserve counts as stable backing, so a pay-back owed to the LSP can be unpayable
- Severity: Medium
- Confidence: Confirmed on the LSP side. The wallet send side is in #401.
- Where: `SM:52-58` (`their_sats` includes `counterparty_unspendable_punishment_reserve`), used for every backing, drift and backstop decision (`SM:2207`, `2287-2291`). `src/stable.rs:29-62` (`reconcile_outgoing` keeps the remaining sats, the reserve, as backing).
- Related: #401
- Status on main: open. PR 400/415: not fixed. PR 415 keeps the reserve in `recoverable_sats`.
- What happens:
  1. The user spends down to the reserve, so backing equals the reserve.
  2. The price rises. The LSP sees a surplus owed to it and queues a `user_to_lsp` wake on every tick while the phone is offline (`SM:2556-2558`).
  3. The wallet cannot pay, because spendable is 0.
  4. The state never clears until the price falls back or the user receives sats. The wallet's spend guard (#344) can block all spends.
- Why it matters: a stuck obligation, push spam, and blocked user spends. The LSP's loss is small (the drift on the reserve).
- Fix: use `inbound_capacity` alone as the user balance for backing (coordinated with the wallets), or cap the amount owed at what the user can spend and stop asking for the rest.

### A-11: No cap on top-up size or frequency, a memory-only cooldown, and no backoff on failures
- Severity: Medium (a defense-in-depth gap on the money path)
- Confidence: Confirmed
- Where:
  - `SM:2340-2341`: the amount is the raw drift with no ceiling.
  - `SM:2317`: the cooldown is the only rate limit, held only in memory (`SM:3712`) and reset by every `reconcile_from_grpc` (`event_loop.rs:300-308`).
  - `SM:732`: a failed top-up restores the old cooldown.
  - `SM:2528`: a send error starts no cooldown.
  - There is no check of `next_outbound_htlc_limit_msat` before sending, unlike the SYNC path at `SM:1136-1139`.
- Related: #401 ("a failed stability send should back off")
- Status on main: open. PR 400 adds the in-flight guard (one top-up per channel at a time) but no cap and no backoff.
- What happens:
  - Every amount check is relative to the LSP's own books and price. Nothing says "no single top-up above X% of `expected_usd`", "no more than Y sats per channel per day", or "stop all top-ups above Z sats per hour".
  - A bug like A-1/A-2, or a wrong price that 3 feeds agree on, is limited only by channel liquidity.
  - When the LSP's side of a channel is exhausted, or the wallet rejects keysends, the tick signs and sends a failing keysend every 60 s, and ldk-node stores a Failed payment record each time (`spontaneous.rs:139-158`).
- Why it matters: nothing caps the damage from the other findings, and a channel the LSP can no longer fund (collateral exhausted) shows up only as repeated `STABILITY_PAYMENT_FAILED` audits.
- Fix:
  - Add per-top-up, per-channel-per-day and global hourly caps, with a circuit breaker that pauses top-ups and raises a red alarm.
  - Persist the last send time.
  - Use exponential backoff after failures.
  - Check outbound liquidity before sending.
  - Raise a distinct alarm when the LSP's collateral in a channel cannot cover the drift.

### A-12: The zero-backing sentinel turns the user's native sats (or reserve) into stable backing
- Severity: Low
- Confidence: Confirmed (LSP side)
- Where: `SM:2287-2291`: with `backing_sats == 0` the stable value is the *whole* balance. `src/stable.rs:188-249` (`reconcile_forwarded`) leaves `backing = 0` with `expected_usd > 0` when a below-par user spends everything. `upstream-pr-400 SM:2876-2894` writes the held balance as backing before sending.
- Related: #401. The wallet treats 0 differently: `effective_backing_sats`, `src/stable.rs:542-553`.
- Status on main: open. PR 400 keeps the behavior.
- What happens:
  1. The price falls, and the user spends everything before the top-up. The target becomes the shortfall (say $10) with backing 0.
  2. The user's remaining balance (the reserve, or later native receives) is valued as stable.
  3. The LSP pays only `target − balance` and books backing at equilibrium. The native sats are now counted as stable without any trade.
- Why it matters: a small silent reclassification of user BTC, and a divergence from the wallet's view.
- Fix: never use 0 as a sentinel for an active target. Store backing explicitly and treat `backing == 0 && expected > 0` as "everything is owed".

### A-13: The unsigned pay-back changes memory before the database and ignores a failed save
- Severity: Low
- Confidence: Confirmed
- Where: `SM:2064-2099` (memory mutated) then `SM:2101-2114` (a `save_channel` error is only audited). `reconcile_from_grpc` reloads from the database on every stream reconnect (`SM:967-1082`, `event_loop.rs:300-308`).
- Related: #414
- Status on main: open. PR 400: same, and it adds a second, separate write (`offset_stability_top_up_on_the_way`) that is not atomic with the first.
- What happens: the save fails, and the next stream reconnect or restart reloads the old backing. The live balance is below it, so 2 ticks later the backstop cuts the target by the amount paid back. This is the only stability write that does not use `PendingBookUpdate` or a transaction.
- Why it matters: losing a pay-back after a DB hiccup cuts the user's target.
- Fix: write the database first in one transaction (allocation + settlement row + offset), as the signed path does, or route it through `PendingBookUpdate`.

### A-14: Settlements land on the LSP's own equilibrium instead of the amount paid
- Severity: Low
- Confidence: Confirmed
- Where: `src/stable.rs:782-788` (`.max(equilibrium)` at the LSP price), used by both the signed path (`SM:1794`, `1843`) and the unsigned path (`SM:2076-2082`). Top-ups book `backing_after = expected/price` at the LSP price (`SM:2446-2447`).
- Related: #232
- Status on main: open. PR 400/415: not fixed. In PR 400 the "unabsorbed" sats that the floor produces are taken off any pending top-up for the channel (`upstream-pr-400 SM:2606`, `db.rs offset_pending_top_up`), even when the leftover is only a price-divergence residue.
- What happens: the wallet and the LSP disagree by up to the ±0.5% quote band (about 40 sats on $10), and each later settlement is computed on different backing.
- Why it matters: small, but it keeps the books permanently apart and feeds A-9 and A-12 style mismatches.
- Fix: as #232 proposes, drop the floor. Longer term, carry the price or the post-state in the signed payload.

### Answers to the three questions

**1. Drain.**
- Can the LSP pay twice? Yes:
  - after a failed record, every 120 s (A-1);
  - after a crash in the send-to-record window, once (A-1, #411);
  - after an error that arrives after dispatch, once per error (A-2).
- Overlapping ticks, a tick racing a trade or a forward, and the wake pass racing a tick cannot cause it: all of them share one lock.
- No cap exists per channel or globally. The cooldown is the only limit and it lives in memory (A-11).
- Price: a stale price (over 60 s) or a quarantined one stops the tick. A spike needs 3 feeds to agree and at most a 10% step per refresh; the top-up is then exactly the drift at that price, with no cap.
- Can a user trigger extra top-ups? Rejecting top-ups causes only failing retries (A-11), not loss. A pay-back cannot raise backing (`src/stable.rs:782-788`).
- Can a wallet fake a claim or a pay-back? No:
  - A claim needs LDK's PaymentSuccessful for the LSP's own payment.
  - A signed pay-back needs a real PaymentReceived with the exact signed amount, signed by the named channel's counterparty, and is idempotent per settlement id and payment id.
  - The unsigned marker is matched by a global balance drop, so another peer's payment can be booked to a different channel. That only forgives debt the LSP was paid for; the actual payer can be hurt (A-9).

**2. User loss.**
- Yes:
  - an unclaimed top-up is cut as a spend (A-3);
  - a missed failure is never undone (A-4);
  - a top-up that lands on a sibling channel (A-5);
  - an unattributed unsigned pay-back (A-9);
  - an unsaved unsigned pay-back (A-13);
  - native sats reclassified as backing (A-12).
- A signed pay-back cannot be double-counted, because the inbox uses a primary key plus an atomic compare-and-swap.

**3. Robustness.**
- Ordering:
  - The signed path and the optimistic top-up write the database first, then memory.
  - The unsigned path writes memory first (A-13).
  - A top-up is sent before it is recorded (A-1, A-2).
- Lock: one async mutex is held across every RPC, and the RPCs have no timeouts (A-6). There is no deadlock risk: the push mutex is separate and pushes run outside the lock, and the DB mutex is only taken in synchronous code. The risk is starvation.
- LDK payment states:
  - Pending: booked optimistically on main (A-3).
  - Failed: rolled back from live events, but not from backfill (A-4).
  - Succeeded: marked from events and from backfill.
  - Unknown: treated as Failed (A-2).
- When LDK Server is unreachable, the tick returns early at `list_channels` (`SM:2160-2166`). A stall rather than an error blocks everything (A-6).

### Gaps in test coverage (from the tests at `SM:3756+`)

The test closest to A-2, `run_tick_send_failure_keeps_cooldown_unset` (`SM:5151`), asserts the retry-after-error behavior that A-2 describes. No test covers:
- a record that fails after a successful send (A-1);
- a send that errors after dispatch (A-2);
- a backfill that sees a failed stability payment (A-4);
- a peer with two channels receiving a top-up (A-5);
- a slow or stalled LDK call holding the lock (A-6);
- a failed `save_channel` in `reconcile_incoming_stability` followed by a reconnect (A-13).

## 3. Design observations

1. **Sends are not idempotent: send first, record second, random preimage.** Every keysend the LSP originates (top-up, SYNC, trade response, fee refund) uses `preimage: None`. Once a send's outcome is uncertain (a crash, a failed write, a transport error), the daemon cannot ask "did I already pay this?". A-1 and A-2 come from this. ldk-node already offers the fix: a caller-chosen preimage plus its `DuplicatePayment` check (`spontaneous.rs:63-76`). Write the intent (settlement id → deterministic preimage → payment id) before sending. Resolve every intent by payment id before deciding anything new. That closes the whole class across all four senders.
2. **The backstop guesses spends from a gap, so every unexplained gap becomes a target cut.** The books are compared with a "live balance" that leaves out in-flight HTLCs, includes the reserve, and may sit on a sibling channel. Any gap lasting 2 ticks is read as a spend. #324, #392, #402, #414, A-4, A-5, A-9 and A-13 are all "a gap with a different explanation". Each PR (402, 400, 415) whitelists one more explanation. Two structural options:
   - (a) Deduct only on positive evidence: a forward or payment record from LDK for that channel, or a per-channel balance ledger that accounts for every known in-flight item. Treat any leftover gap as an alarm, not a deduction.
   - (b) Keep the backstop, but make "this channel has any unresolved LSP-originated or user-originated payment" a hard skip, with a red alarm if the gap lasts past N hours.
3. **Safety state lives in memory.** `last_stability_payment`, `spend_debounce` and `stability_throttle` are memory-only. `reconcile_from_grpc` rebuilds memory from the database on every stream reconnect, so any state that was only in memory is lost silently (A-1 cooldown, A-13 backing). Rule: the database is the only source of truth, memory is a cache rebuilt from it, and no money decision depends on memory-only state.
4. **One lock covers deciding, network I/O and recording.** It gives correct serialization, but it couples availability to the slowest RPC (A-6, A-8). Use three phases: decide and record intents under the lock, perform I/O outside it with timeouts, and apply outcomes under the lock. With idempotent intents (point 1), concurrency becomes safe.
5. **A channel is the accounting unit, but a keysend can only target a node.** LDK chooses the first hop (A-5), and the legacy marker carries no channel at all (A-9). Either settle per peer, or verify where a payment landed before booking it.
6. **Each peer prices independently and settlements carry no post-state.** Every settlement re-anchors at the local price (A-14). Adding the sender's price or the resulting `backing_sats` to the signed payload makes both sides converge exactly.
7. **No limits or circuit breaker anywhere on the money path** (A-11). Caps on per-payment size, per-channel per day and globally per hour, plus a "pause top-ups" switch the operator can flip and the daemon trips on anomalies, would turn the drains in A-1 and A-2 into an alarm with bounded loss.
