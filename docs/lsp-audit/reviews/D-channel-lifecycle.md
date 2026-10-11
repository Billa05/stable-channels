# Review D: channel lifecycle and startup

Scope: `main` @ 9aff5ec (daemon unchanged by the docs-only HEAD 6722907). Paths below are relative to `server/stable-channels-lsp/src/` unless they start with `src/` (the shared crate). I checked LDK behaviour against the vendored rust-lightning revs 369a2cf and 090e09f, and LDK Server event emission against the vendored ldk-server bd95e18. Nothing was built or run.

## 1. What is done right

- **Splice accounting comes from state, so replays are harmless.** `handle_channel_ready_splice` refreshes the live balance and then runs `recompute_native` + `reconcile_outgoing` on a copy (`stable_manager.rs:2959-2974`). Running it a second time finds nothing new to deduct (test `splice_replay_does_not_double_deduct`, `stable_manager.rs:8061`).
- **A splice correction is never lost to a failed save.** The correction is kept as a `PendingBookUpdate` (`stable_manager.rs:2999-3017`). `lock_for_event` holds every later event until that correction commits (`stable_manager.rs:370-383, 435-458`). A splice that has no snapshot yet is retried, but it does not block the stream (`stable_manager.rs:455-457`).
- **`user_channel_id` is the logical key everywhere**, not the physical channel id:
  - reconcile map: `stable_manager.rs:994-1001`
  - tick map: `stable_manager.rs:2166-2172`
  - splice lookup: `stable_manager.rs:2950-2953`
  - DB update by uid first: `src/db.rs:1161-1176`
  - wake watcher matches uid plus counterparty: `stability_tick.rs:196-200`

  The splice handler also re-stamps `channel_id` (`stable_manager.rs:2966-2967`).
- **Closed channels are soft-closed and excluded from all later accounting.** The close is guarded so it is idempotent and has a ledger row (`src/db.rs:1814-1888`). Closed rows are filtered out of the reconcile and tick loads (`src/db.rs:1942-1969`). `handle_channel_closed` also drops the channel's pending splice, pending correction, debounce and throttle state (`stable_manager.rs:938-943`).
- **Each (re)connect reconciles before any live event is dispatched,** under the same correction barrier (`event_loop.rs:297-312`). Backfill only writes audit records and never changes the books (`backfill.rs:425`), so it cannot double-book a forward.
- **The restart gap is recorded durably before any task starts** (`main.rs:122-127`). A failed write aborts startup rather than claiming coverage it does not have.
- **LDK Server down at startup recovers on its own.** The event loop retries subscribe with backoff and reconciles on connect (`event_loop.rs:240-312`). The tick also retries `reconcile_if_empty` every minute (`stability_tick.rs:49`).
- **The startup SYNC is safe.** It is rebuilt from committed DB books, never from memory. It is skipped while the live balance is below backing, so an unreconciled spend is never published (`stable_manager.rs:1171-1181`). Routing peers with no stable position are skipped (`stable_manager.rs:1074-1078`).
- **The tick leaves possibly-stale channels alone:**
  - it does not touch a channel with a pending splice (`stable_manager.rs:2198-2201`);
  - a wake pass never tops up a channel whose live balance is below backing (`stable_manager.rs:2217-2222`);
  - a channel that is not usable gets only a push, never a send (`stable_manager.rs:2343, 2548-2560`).
- **`channel_peer_balances` does not assign the funder's commitment fee to the peer** (`stable_manager.rs:49-58`). It uses LDK's per-peer fields rather than `value - local`.

## 2. Findings

### D-1: A ChannelReady handled before the channel list is loaded overwrites a tracked channel with a $0 target (#413)
- Severity: High
- Confidence: Confirmed
- Where: `stable_manager.rs:1303-1316` (memory-only check), `stable_manager.rs:1371-1378` (writes 0/0/all-native over the row), `event_loop.rs:305-311` (connect reconcile skipped while the price is cold), `main.rs:167-196` (one startup attempt, then gives up after 60 s)
- Related: #413. Same class as D-2, D-3 and D-9.
- Status on main: open. PR 400 and PR 415 do not touch `handle_channel_ready`, `event_loop` dispatch order or `main.rs` startup order.
- What happens:
  1. The daemon starts. The event loop subscribes at once and skips its reconcile because the price is cold.
  2. A splice on stable channel X locks. LDK emits ChannelReady again for X (LDK `events/mod.rs`: "a splice funding transaction has been confirmed").
  3. `dispatch_event` → `handle_channel_ready`. X is not in the empty in-memory list, so it goes down the new-channel path. `save_channel(…, 0.0, 0, their_sats)` UPDATEs the existing row by uid (`src/db.rs:1162-1176`).
  4. The later reconcile loads the $0 row. No SYNC is sent and no alarm is raised. The splice deduction is also lost.
- Why it matters:
  - The LSP stops topping up the user, while the wallet still shows the old target.
  - It does not heal on its own.
  - D-2 can keep this window open indefinitely.
- Fix:
  - In `handle_channel_ready`, when the uid is not in memory, `db.load_channel(uid)` first. If a row exists, hydrate from it and treat the event as a splice.
  - Never write a $0 row over an existing row; use an insert-if-absent.
  - Better: gate all dispatch on a "hydrated" flag (see Design 1).

### D-2: After a missed startup reconcile, one ChannelReady makes the list non-empty and the other channels are never loaded
- Severity: High
- Confidence: Confirmed
- Where: `stable_manager.rs:1272-1282` (`reconcile_if_empty` reconciles only when `stable_channels.is_empty()`), `stable_manager.rs:1389` (new channel pushed into the empty list), `main.rs:170-195` (single attempt), `event_loop.rs:305-311`
- Related: #413 (this extends its window)
- Status on main: open. Not changed by PR 400 or PR 415; PR 415 only adds a top-up resolver before the cold-price `continue` in `stability_tick.rs`.
- What happens:
  - Trigger: the price stays cold for more than 60 s after start (feed outage or quarantine). Alternatively, the single startup reconcile fails (`list_channels` or DB error, `stable_manager.rs:982-1006`) while the stream is already up.
  - During that window any ChannelReady arrives (a new JIT channel, or a splice lock that becomes D-1). `handle_channel_ready` has no price gate and pushes one entry.
  - When the price warms, the tick calls `reconcile_if_empty`. The list is not empty, so it only runs `retry_startup_sync`.
  - Every other stable channel stays out of memory until the next event-stream reconnect. Until then:
    - no top-ups are paid (`run_tick_plan` iterates memory only);
    - correlated trades are rejected `InternalFailure` (`stable_manager.rs:3257-3264`) and the 1% fee is kept, because a rejection does not refund;
    - legacy trades are dropped (`stable_manager.rs:3431-3442`);
    - `ListStableChannels` shows one channel;
    - any splice lock on those channels hits D-1.
- Why it matters: this is a routine event (a feed outage at restart) that silently suspends stability for every user except one, for an unbounded time.
- Fix:
  - Replace `is_empty()` with a `hydrated: bool` that only a successful `reconcile_from_grpc` sets.
  - Let `reconcile_from_grpc` run without a price. It needs no price for books; the display fields can be filled in at the next tick.
  - Hold event dispatch, trades and edits until `hydrated` is set.

### D-3: `edit_stable_channel` reads the prior state from memory only, so a note edit before hydration resets the target to $0
- Severity: Medium
- Confidence: Confirmed
- Where: `stable_manager.rs:799-813` (`prior` from memory; `(None, None) => 0.0`), `stable_manager.rs:835-840`, `stable_manager.rs:880-896`
- Related: #413 (same root cause)
- Status on main: open. PR 400 and PR 415 add a `prior_books` path, but it applies only when `prior` is in memory; `(None, None)` still produces 0.
- What happens:
  1. The operator adds a note to channel X ($50 / 50,000 / 50,000) in the startup window, or while D-2 holds.
  2. `prior` is None, so `expected_usd_f = 0.0` and `backing = 0`.
  3. The "no changes" guard passes because a note was given (`stable_manager.rs:818-823`).
  4. `save_channel` overwrites the DB row with $0 / 0 / all-native and audits `STABLE_EDITED target_usd: 0`. No SYNC is sent.
- Why it matters: a routine operator action silently deletes a user's USD position on the LSP side.
- Fix: load the prior state from `db.load_channel(uid)` when it is not in memory, and refuse edits until the manager is hydrated.

### D-4: EditStableChannel is a raw overwrite of the books: it re-prices backing, writes backing 0 on a cold price, never SYNCs the wallet, and does not validate its input
- Severity: Medium
- Confidence: Confirmed
- Where: `stable_manager.rs:761-918`. Backing derivation is at `835-840`. There is no `send_sync_message` or `startup_sync_pending` anywhere in the function. The route is `main.rs:249-252` behind the single HMAC key (`auth.rs:98-138`).
- Related: #225 (trades must preserve drift), #401
- Status on main: open.
  - PR 400 and PR 415 fix the note-only re-price (when the prior is in memory) and refuse a target edit while the price is cold.
  - Still open in both PRs: target edits re-price backing and erase drift; there is no SYNC; there is no finite or non-negative check; the stabilization cap is in shadow mode by default (`max_stabilization_rejected` with `enforce=false`, `stable_manager.rs:845-858`).
- Who can call it: any holder of the SC daemon api_key. There is no role split between the operator GUI and any other client. Today's mobile wallets use only the unsigned `/api/register-push` and `/api/channel-exists`, but the README describes wallets as peers on the same REST surface.
- What happens, (a) cold price on main:
  1. A note-only edit while `get_fresh_cached_price_no_fetch()` returns 0 writes `backing_sats = 0` and keeps E = $50 (`stable_manager.rs:835-839`).
  2. The user spends 80,000 of 100,000 sats. `reconcile_forwarded` treats every sat as native because backing is 0, so nothing is deducted (`src/stable.rs:198-204`).
  3. The backstop never fires, because `their_sats < 0` is never true.
  4. The tick values the position as the whole live balance when backing is 0 (`stable_manager.rs:2287-2291`): $20 against a $50 target.
  5. The LSP sends a $30 top-up. The correct result is E = $20 and no top-up, so this is a direct LSP over-payment.
- What happens, (b) warm price on main: a note-only edit re-anchors backing to E/price (`stable_manager.rs:835-840`). Any drift that has built up is erased.
  - Example: the price fell 10% while the user was offline. The $5 the LSP owed becomes 5,555 native sats moved into backing. The LSP never pays.
  - The wallet still holds the old backing and waits for a top-up that will not come.
  - Target edits on both PRs still do this.
- What happens, (c) no SYNC: the wallet keeps its old target. Each correlated trade afterwards computes the fee from a different `old_expected`. The LSP rejects it `InvalidFee` (`stable_manager.rs:3278-3289`) and keeps the 1% fee.
- What happens, (d) bad input: `expected_usd = +inf` is stored and produces endless failing u64::MAX-msat sends. A negative value is cast to backing 0.
- Fix:
  - Route target edits through `trade_backing_after_delta` so drift is preserved.
  - Never write backing 0 for a target of 0.01 or more.
  - Require a finite target of at least 0 and a warm price.
  - Persist the edit, then queue a SYNC.
  - Refuse while unhydrated (D-3).
  - Consider a separate operator key or role for money-moving routes.

### D-5: The backstop reads pending-splice balances as spends: an LSP-side splice-in books a phantom spend, and a user splice-out is deducted before it confirms, with no undo
- Severity: Medium
- Confidence: Plausible. LDK logic is confirmed in vendored rust-lightning 369a2cf and 090e09f. Not verified: that LDK Server bd95e18's ldk-node `f375e4d` uses the same LDK logic, and nothing was reproduced on live nodes.
- Where:
  - `stable_manager.rs:52-58` adds the *current* scope's `counterparty_unspendable_punishment_reserve` to `inbound_capacity_msat`.
  - LDK `inbound_capacity_msat` is the minimum across all pending funding scopes (rust-lightning `channel.rs:13554-13570`), and each scope uses its own reserve (`channel.rs:5246-5256`, `sign/tx_builder.rs:579-580`).
  - A splice scope's reserve is 1% of the *new* capacity (`channel.rs:2783-2797`).
  - The ChannelDetails reserve comes from `channel.funding()`, the current scope (`channel_state.rs:528-546`).
  - Backstop: `stable_manager.rs:2223-2280`.
  - `SpliceNegotiated` is audit-only: `event_loop.rs:618-624`.
- Related: #187, #234 (both document deduction at channel-ready), #285, #311, #324
- Status on main: open. PR 415 replaces `their_sats` with `recoverable_sats = inbound_capacity + held + reserve`, which still carries both effects.
- What happens, (a) LSP splice-in:
  1. The operator splices in s sats on the LSP side of user channel X (`/SpliceIn`, `handlers/channels.rs:48-57`). The user's balance `bal` is unchanged.
  2. While the splice is pending, LDK reports `inbound = min(bal - r_old, bal - r_new)`, which is `bal - r_new`. The SC daemon adds `r_old`, so the user appears at `bal - s/100`.
  3. For a fully stable user, after 2 ticks `reconcile_outgoing` cuts E by `s/100 * price` and SYNCs it.
  4. At splice lock the balance is `bal` again and the s/100 sats become native.
  5. Net effect: 1% of the LSP's splice-in moves from the user's USD position to BTC without the user doing anything. For example, 0.1 BTC spliced in costs the user about 100,000 sats (about $100) of USD position.
- What happens, (b) user splice-out:
  - The live balance drops when the splice is negotiated. The backstop deducts about 2 minutes later and labels it `BACKSTOP_STABLE_DEDUCTED`.
  - At ChannelReady only the last s/100 is left to deduct, so `SPLICE_OUT_STABLE_DEDUCTED` rarely fires. The documentation says the opposite.
  - If the splice is abandoned (inputs double-spent, or a SpliceFailed after signing), the balance returns but the USD deduction stays.
  - A SYNC arriving in the middle of a splice is also the scenario #285 describes on iOS (deferred syncs). This link is unverified.
- Why it matters: a routine LSP liquidity operation causes a wrong user booking, and splice accounting does not match the documented design.
- Fix: handle `SpliceNegotiated` and `SpliceNegotiationFailed` by tracking a per-uid "splice in flight" flag.
  - While the flag is set, suspend the backstop for that uid. Settle once at ChannelReady (the existing handler), and clear the flag on failure.
  - Alternatively, compute the user's balance from one scope consistently: add the reserve of the scope that LDK's minimum came from.

### D-6: A channel whose ChannelReady is missed or fails is never registered, because reconcile only walks DB rows
- Severity: Medium
- Confidence: Confirmed
- Where:
  - `stable_manager.rs:1011-1065`: reconcile iterates `load_all_channels()` only; live channels without a row are ignored.
  - `stable_manager.rs:1318-1338, 1371-1387`: `handle_channel_ready` returns on a `list_channels` error, a not-found, or a DB error, and nothing retries it.
  - LDK Server drops events that have no subscriber and marks them handled (ldk-server `main.rs:864-870, 462-464`).
- Related: #266
- Status on main: open. Not changed by PR 400 or PR 415.
- What happens: a new JIT channel becomes ready while the SC daemon is restarting, the stream is in a gap, or a gRPC call fails. The channel never gets a row.
  - The user's correlated trades are rejected `InternalFailure` and the fee is kept (`stable_manager.rs:3257-3264`).
  - The user gets no stability, and the channel does not appear in `ListStableChannels`.
  - The only recovery is a manual `EditStableChannel`, which also triggers D-4.
- Why it matters: a routine restart during onboarding leaves a user permanently unserved and charged for rejected trades.
- Fix: in `reconcile_from_grpc`, insert-if-absent a $0 row for every live `is_channel_ready` channel that has no row. Also queue a failed `handle_channel_ready` for retry, as is already done for splices.

### D-7: Paying the LSP is voluntary for the user, and close, splice-out and spend all keep an unpaid surplus
- Severity: High. This is cross-area (A/H). Arguably Critical: it is an LSP drain by an outside party, but it is bounded by price movement and position size.
- Confidence: Confirmed. No server-side enforcement exists; the economics is the standard free-option argument.
- Where:
  - On user_to_lsp drift the LSP only logs CHECK_ONLY or pushes a wake (`stable_manager.rs:2530-2546, 2548-2560`).
  - `settlement_owed_to_lsp` and `spend_consumes_lsp_surplus` (`src/stable.rs:465-497`) are used only by wallets; the daemon has no reference to them.
  - The full-exit gate exists only on the trade path (`src/stable.rs:407-413`).
  - `handle_channel_closed` does no settlement (`stable_manager.rs:920-965`).
- Related: #344 (client-only spend guard), #401, #376
- Status on main: open, by design. Not changed by PR 400 or PR 415.
- What happens: a user with a modified wallet accepts LSP top-ups on every fall and never sends `STABILITY_PAYMENT_V1` on rises.
  - Because the drift is measured against E, the LSP pays every new low.
  - The user's backing grows to about E/p_min, and the user can close or splice out whenever the price is above p_min.
  - Close and splice-out bypass the trade path's "SettlementRequired" gate.
  - The LSP's loss is about E·(p_exit/p_min − 1) per cycle. It repeats across channel lifetimes, and opening a new channel resets nothing in the LSP's favour.
- Why it matters: the LSP's hedge assumes the user pays symmetrically, and that is not enforced.
- Fix:
  - Keep a durable per-channel "owed to LSP" balance.
  - Net future top-ups against it, so top-ups are paid only above a high-water mark.
  - After a grace period of non-payment, stop stabilizing (set E to 0 and SYNC) or close the channel.
  - Alarm on users who owe for a long time. This caps the loss at the drift during one grace window.

### D-8: The balance formula can exceed what the user can ever withdraw, and the reserve counts as backing
- Severity: Low
- Confidence: (a) Plausible: not verified that LSPS2 config produces balance < reserve. (b) Plausible: not verified that LDK Server accepts inbound user-funded channels. Reserve-as-backing is Confirmed (#401).
- Where: `stable_manager.rs:52-58`. Trade capacity uses this figure at `stable_manager.rs:3293-3299` and `3536-3544`. LDK `inbound = remote_balance_before_fee - holder_reserve` saturates at 0 (`sign/tx_builder.rs:579-580`).
- Related: #401, #136
- Status on main: open. PR 415 keeps the reserve in `recoverable_sats`.
- What happens:
  - (a) A fresh JIT channel where the user's balance is below the 1% / 1,000-sat client reserve reports `0 + reserve`, which is more than the real balance. A trade can then book backing on sats that do not exist; the LSP tops up on them, and the backstop never fires.
  - (b) On a user-funded inbound channel, "before fee" includes the commitment fee and anchors that the user's close output never receives.
  - In all cases the reserve is backing that the user cannot spend in the channel (#401).
- Why it matters: small LSP over-payment, bounded by the reserve or commitment fee times the price move. It is also the root of #401.
- Fix: use `inbound_capacity_msat` (spendable) for backing caps. Treat the reserve as non-backable, together with the #401 client change, or set `disable_client_reserve` (#136) for new channels.

### D-9: Reconcile soft-closes any row missing from a successful `list_channels` and never revisits closed rows
- Severity: Low
- Confidence: Confirmed
- Where: `stable_manager.rs:1016-1035`. The comment says the close is cleared by a future reconcile, but `load_all_channels` excludes closed rows (`src/db.rs:1942-1950`).
- Related: #372 (wallet-side analogue), #373
- Status on main: open. Not changed by PR 400 or PR 415.
- What happens: if LDK Server answers successfully with an incomplete set (wrong node or config, a restored or empty store briefly attached), every missing row is closed. When the channels come back they stay untracked: trades are rejected and there is no stability. The next splice ChannelReady overwrites the row with $0, as in D-1.
- Why it matters: a recoverable operator mistake becomes permanent USD loss on the LSP's books.
- Fix: in reconcile, re-activate closed rows whose uid is live, and only treat a close as final on a ChannelClosed event. Or require the absence to persist across N reconciles before closing.

### D-10: A correction that brings the target to $0 is never SYNCed after a restart
- Severity: Low
- Confidence: Confirmed
- Where:
  - `stable_manager.rs:429-431`: `needs_sync` lives only in the in-memory `startup_sync_pending`.
  - `stable_manager.rs:1169-1172`: unusable channels are skipped without recording an attempt.
  - `stable_manager.rs:1074-1078`: restart re-queues only `expected_usd > 0`.
- Related: #285, #311
- Status on main: open. Not changed by PR 400 or PR 415.
- What happens: a splice-out, backstop or forward deduction cuts E to 0 while the mobile wallet is offline. The daemon restarts before the wallet reconnects. Nothing is ever published, and the wallet keeps its old target.
- Fix: persist "SYNC owed" with the books, in the same transaction as the save, and re-queue it whatever E is.

### D-11: A stale `pending_splices` entry halts the tick for every channel, and `reconcile_if_empty` refuses to clear it
- Severity: Low (narrow trigger)
- Confidence: Confirmed
- Where:
  - `stable_manager.rs:2148-2153`: the whole tick returns while any splice is pending.
  - `stable_manager.rs:2950-2955`: a channel that is in memory but missing from the list returns early and the entry stays.
  - `stable_manager.rs:1274-1276`: no reconcile while anything is pending.
- Related: #163
- Status on main: open.
- What happens:
  1. A splice lock for X is recorded in `pending_splices`.
  2. X closes and the ChannelClosed is lost in a stream gap.
  3. The reconnect reconcile is skipped because the price is cold (`event_loop.rs:305-311`).
  4. Once the price warms, every tick retries X, finds it missing, and returns before settling any channel. This lasts until the next reconnect.
- Fix: drop a pending splice whose uid is no longer in a successful `list_channels`. Scope the barrier to that uid instead of the whole tick.

## 3. Design observations

1. **Memory is treated as the record of "is this channel tracked", but the DB is the truth.**
   - D-1, D-2, D-3 and the D-2 trade rejections all come from handlers that look only at `self.stable_channels`.
   - Event dispatch, REST edits and trades are all live before the first reconcile, and that reconcile needs a price it does not use for books.
   - Fix: add an explicit `Unhydrated → Hydrated` state. Queue events while unhydrated, and refuse edits and trades with a retryable error.
   - Make every "new channel" path insert-if-absent in the DB, never overwrite.
2. **LDK Server's stream is lossy, with no replay** (`ldk-server main.rs:864-870`).
   - Anything that must happen once per lifecycle event has to be derivable from a snapshot: registration, splice settlement, close, and SYNC owed.
   - Reconcile covers balances and closes, but not registration (D-6) or SYNCs owed (D-10). It also does not re-open rows (D-9).
   - Each reconnect rebuilds the in-memory channels from DB rows and sets `last_stability_payment = 0` (`stable_manager.rs:3712`).
3. **"Balance below backing means a spend" mixes LDK funding scopes.**
   - During a splice LDK reports the minimum of each scope's spendable amount. The daemon adds back the current scope's reserve (D-5).
   - Fix: consume `SpliceNegotiated` and `SpliceNegotiationFailed`, which are already in the stream, as a per-channel "capacity changing" state that suspends balance inference.
   - The same applies to the pending-HTLC version of this problem (#324).
4. **Settlement is asymmetric.**
   - LSP→user payments are automatic. User→LSP payments, and the guards against spending or exiting while owing, exist only in wallet code.
   - Close and splice-out are exits that the LSP cannot gate (D-7).
   - Fix: on the LSP side, track what each user owes and net it against future top-ups, with a time bound on non-payment.
5. **Operator edits should use the same allocation math as trades:** drift-preserving delta, validation, cap, persisted SYNC. Money-moving REST routes (`EditStableChannel`, `RefundTradeFee`, the proxied `SpliceIn`/`SpliceOut`/`ForceClose`) share one api_key with read-only routes.
6. **The SC daemon has no splice admission control** (#163). User splices are accepted inside LDK, and the daemon only sees them in audit records. Rejecting concurrent splice-ins needs an LDK Server or LDK hook, not a daemon mutex.
7. **The splice handler persists a stale `native_sats`.** `recompute_native` updates only `native_channel_btc`.
   - After a splice-in the row keeps the old native value, so `backing + native` no longer equals the live balance.
   - The ledger's `CHANNEL_ACCOUNTING_STATE_COMMITTED` is skipped because "accounting unchanged" (`src/db.rs:1127-1137`).
   - This is only an observability gap, but it hides splice-ins from the accounting ledger.
