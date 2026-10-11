# Review C: payment events and accounting (Stable Channels LSP)

Scope: `server/stable-channels-lsp/src/{event_loop,payment_filter,messages,state,main,backfill,stability_tick}.rs`, `stable_manager.rs` (receive / forward / SYNC paths), `src/stable.rs`, the settlement and SYNC tables in `src/db.rs`, and `src/db/{stream_checkpoint,forward_history}.rs`. Baseline is `main` @ 9aff5ec. PR 400 is `upstream-pr-400` and PR 415 is `upstream-pr-415`. Upstream LDK Server delivery behaviour was checked against the pinned source in `~/.cargo/git/checkouts/ldk-server-16db9a3dfc9d95ee/bd95e18`.

## 1. What is done right

- **The signed pay-back path is durable, bound to the payment, and safe to replay.** The amount, direction and channel are checked against the payment (`stable_manager.rs:1556-1577`). The payment is registered in a durable inbox before any book changes (`stable_manager.rs:1579`, `db.rs:4053`). Expiry is judged at the first durable receipt time (`stable_manager.rs:1642`). The signature is checked against the named channel's counterparty (`stable_manager.rs:1730-1734`). The `settlement_id` and `payment_id` are unique keys (`db.rs:725-738`). The tick retries the inbox before the backstop runs (`stable_manager.rs:2156`, `1952-1985`).
- **No inbound payment can credit more than it delivered.** `backing_after_user_to_lsp_stability` never takes backing below equilibrium. It also clamps to the live balance only for the drop this payment explains (`src/stable.rs:762-800`). An unsigned legacy marker sent by a third party can therefore move value between users but cannot extract value: each credited sat was paid.
- **Forwarded-spend math is per-event and repeat-safe.** `reconcile_forwarded` deducts only the forward's own overflow (`src/stable.rs:188-250`). A repeated event or a sibling spend is "explained" by the live balance and deducts nothing more. Covered by `stable_manager.rs:4577-4707`. Attribution uses LDK's per-channel `prev_user_channel_id` (`event_loop.rs:470-476`, `stable_manager.rs:2775-2808`), so MPP parts and users with several channels are attributed per channel.
- **SYNC delivery is well designed.**
  - The version is reserved durably before signing (`stable_manager.rs:2625`, `db.rs:1395`).
  - Retries rebuild the payload from committed DB books, not from a failed payload or memory (`stable_manager.rs:1173-1192`).
  - Only the newest attempt can require a retry (`db.rs:4034-4050`).
  - Outcomes are polled, not only taken from events (`stable_manager.rs:1229-1270`).
  - Offline or unusable channels consume no version (`stable_manager.rs:1128-1172`).
  - Retries back off and stop at a cap (`stable_manager.rs:38-47`, `1199-1227`), and a stuck attempt is abandoned after 1 h.
  - SYNCs therefore do not pile up while a wallet is offline. A stale SYNC cannot overwrite newer state: the wallet applies only newer versions (`db.rs:1659-1720`).
- **Ordering barrier for failed corrections.** `lock_for_event` keeps the next event behind any unsaved backstop or splice correction (`stable_manager.rs:370-383`, `event_loop.rs:386-396`, `PendingBookUpdate` at `298-306`, `385-433`, `2252`). The manager mutex serialises events, ticks and edits, so there are no concurrent book writers.
- **Failed top-ups are rolled back with a compare-and-swap.** The rollback restores only the exact optimistic state (`db.rs:4498-4560`, `stable_manager.rs:719-737`). `save_channel_after_spend` moves the rollback snapshot by the spend in the same transaction (`db.rs:1153-1172`).
- **The event reader drains without blocking.** It never applies backpressure to LDK Server's lossy broadcast stream (`event_loop.rs:28-75`). Stream-gap coverage is tracked durably and honestly (`src/db/stream_checkpoint.rs`).

## 2. Findings

### C-1: Inbound protocol keysends that arrive while the event stream is down are never applied; a signed pay-back is later booked as a spend
- Severity: High
- Confidence: Confirmed
- Where: `event_loop.rs:462-467` (the only path to `handle_payment_received`); `backfill.rs:105-125` (payments are reconstructed for audit only); `stable_manager.rs:2223-2283` (backstop). Upstream: `ldk-server/src/main.rs:833-860` (events are marked handled even with zero subscribers, or after a failed `payment()` lookup); `ldk-server/src/service.rs:490-491` (`Lagged` means "skip missed events", without telling the client); ldk-node `PaymentKind::Spontaneous { hash, preimage }` (`src/payment/store.rs:415`, which stores no custom TLVs).
- Related: #324, #395 ("reconnect backfill is audit-only"), #234
- Status on main: open. Not addressed by PR 400 or PR 415.
- What happens: LDK Server delivers events at most once. Any `PaymentReceived` produced while the SC daemon is stopped, restarting, redeploying or resubscribing is lost. The same happens if a subscriber lags or LDK Server cannot look up the payment. The custom TLVs exist only in that event, and the reconnect backfill only audits payments. A wallet's signed `STABILITY_PAYMENT_V1` of X sats (owed surplus) is therefore never applied:
  - If native < X, live drops below backing. Two ticks later `reconcile_outgoing` deducts X·price from `expected_usd` and SYNCs it. The LSP now sees the user above par by X again (backing worth E against target E − X·p) and waits for a second payment of X. The user pays the surplus twice and their target is cut.
  - If native ≥ X, the LSP's books keep X as owed surplus. Later top-ups are netted against it, so the user is underpaid by X. The LSP's books and the wallet's books stay diverged.
  - A `TRADE_V1` lost the same way leaves the fee claimed with no decision row. No signed result or retry follows. The wallet-side outcome after the 15-minute timeout is Plausible: fee lost.
  - Reconciliation still reports `completed` and closes the gap (`event_loop.rs:318-339`).
- Why it matters: deploys and restarts are routine, and wallets pay back whenever they are above par. Each loss costs the user one stability payment, or one trade fee. It does not heal itself.
- Fix:
  - Short term: let wallets re-present signed envelopes out of band, keyed by payment hash, either through a REST call or a 1-msat "receipt" keysend. The LSP checks the referenced inbound payment in LDK's store (status, amount, direction) and then registers it in the existing inbox. Until the inbox is checked, the backstop must not deduct.
  - Proper fix: an at-least-once event source. LDK Server persists events and supports `SubscribeEvents` from a cursor, and the SC daemon acks only after its DB commit.

### C-2: On main, a failed top-up whose `PaymentFailed` is missed is never rolled back, and the backstop then cuts the user's target by the top-up
- Severity: High
- Confidence: Confirmed
- Where: `stable_manager.rs:2446-2506` (top-up booked at send: backing becomes equilibrium in the DB and in memory); `event_loop.rs:586-588` (rollback only from the live event); `backfill.rs:282-288` (reconnect treats `Failed` only for `kind='sync'` and skips failed stability rows). Nothing on the tick polls stability outcomes: `reconcile_sync_outcomes` covers SYNC only (`stable_manager.rs:1229`).
- Related: #392, PR 400
- Status on main: open. Fixed by PR 400 for top-ups sent after the upgrade, which are booked at claim and polled every tick and before events. Top-ups pending at upgrade still depend on the live event, as PR 400 itself notes.
- What happens:
  1. The LSP sends a top-up of a sats and books backing as B + a.
  2. The payment fails during a stream gap. A DB error on `mark_sync_payment_failed` that returns `Reconnect` (`event_loop.rs:560-570`) has the same effect.
  3. The row stays `pending` forever. Live = B, backing = B + a, so after two ticks `reconcile_outgoing` deducts a·p from `expected_usd` and sends a SYNC.
  4. A later rollback cannot apply, because the CAS no longer matches (`db.rs:4558`).
  The LSP keeps the sats it never delivered, and the user's target drops by exactly the top-up it was owed.
- Why it matters: this is user fund loss triggered by an ordinary reconnect.
- Fix: merge PR 400. A minimal fix on main is to treat `Failed` for `kind='stability'` in `reconcile_pending_settlement_outcomes`, and in a per-tick poll, by calling `handle_failed_stability_payment` under the manager lock.

### C-3: Event-loop retry and reconnect paths discard every buffered event
- Severity: Medium
- Confidence: Confirmed. The trigger, a `get_payment_details` error, is realistic but not reproduced.
- Where: `event_loop.rs:264` (the subscription and buffer are scoped to the loop iteration); `event_loop.rs:340-346` (`!settlement_outcomes_safe`, then sleep and `continue`); `event_loop.rs:354-366` (`Reconnect`, then `break`); `backfill.rs:270-279` (one failed lookup aborts the whole function with `?`); `Reconnect` returns at `event_loop.rs:511,536,569,583`.
- Related: #321, #326, PR 400 (adds more `Reconnect` returns)
- Status on main: open. Unchanged in PR 400 and PR 415.
- What happens: the loop subscribes first, then reconciles for seconds (up to 20 pages of payments and 20 of forwards). It buffers live events meanwhile so they are not lost.
  - If any pending settlement lookup errors, `continue` drops `_reader` (the `JoinSet` aborts the reader) and `events`. All events buffered during reconciliation are discarded. LDK Server has already marked them handled.
  - One persistently failing lookup repeats this every iteration, so live dispatch never starts and every event is lost while it lasts.
  - A `Reconnect` from a DB write failure on one event also discards all events queued behind it.
  - Each iteration also re-runs `reconcile_from_grpc`, which resets every channel's `last_stability_payment` to 0 (`stable_manager.rs:3712`). That shortens the only guard against the #411 resend.
- Why it matters: the lost events feed C-1 (receives), C-2 (top-up failures) and C-6 (forwards).
- Fix:
  - Keep the same subscription across reconciliation retries: loop on `reconcile_*` without resubscribing, or dispatch the buffered events between attempts.
  - Make settlement-outcome reconciliation per row, not all or nothing.
  - Replace `Reconnect` on DB errors with an in-place retry that holds the envelope, as `lock_for_event` already does.

### C-4: The forward handler counts another still-pending payment from the user as spent, so the deduction stays after that payment fails
- Severity: High
- Confidence: Confirmed
- Where: `stable_manager.rs:2805` (`post_user_sats` from `inbound_capacity_msat`, which excludes pending HTLCs from the user); `stable_manager.rs:2826-2832` (`user_sats_before = post + spend`); `src/stable.rs:198-221`.
- Related: #324. PR 415 lists this as a known limit.
- Status on main: open. PR 415 fixes only the tick backstop, not this handler.
- What happens: a $50 position is backed by 50,000 sats, with 20,000 native (70,000 total, at $100k).
  1. The user has a 20,000-sat hold-invoice or swap payment pending.
  2. The user then pays a 5,000-sat invoice, which settles. Live = 45,000, and the `PaymentForwarded` for 5,000 gives `user_sats_before` = 50,000.
  3. Native is computed as 0, overflow as 5,000, the target becomes $45 and backing 45,000.
  4. A SYNC is sent, because backing ≤ live.
  5. The held payment fails. Live returns to 65,000 and the books show $45 stable plus 20,000 native.
  The correct result is $50 stable plus 15,000 native: the 5,000-sat spend was covered by native. The over-deduction is min(T, P, N) for spend T, pending payment P and native balance N. It is permanent and published to the wallet.
- Why it matters: this is the same false-deduction class as #324. Hold invoices (swaps, HODL invoices) make it realistic.
- Fix: in `handle_payment_forwarded`, add the user's other unresolved HTLCs on this channel to the pre-forward balance before `reconcile_forwarded`. Reuse PR 415's `unresolved_user_payments_msat(get_balances)`. Keep the SYNC held while backing is above the live balance.

### C-5: The forward path mutates memory and sends a SYNC even when the save fails (#321 is only partly fixed)
- Severity: Medium
- Confidence: Confirmed
- Where: `stable_manager.rs:2812-2874` (`sc` mutated in place before saving); `stable_manager.rs:2879-2891` (a `save_channel_after_spend` error is only audited); `stable_manager.rs:2893-2906` (SYNC built from the unsaved in-memory values). The legacy `reconcile_incoming_stability` has the same pattern at `stable_manager.rs:2093-2115`.
- Related: #321 (closed; it fixed the splice and backstop paths only), #326
- Status on main: open, and unchanged by PR 400 and PR 415. PR 400 adds a separate DB-first top-up step but leaves this ordering.
- What happens:
  1. A forward deduction is computed and written to memory. The save fails (DB locked or full).
  2. A SYNC carrying the unsaved `expected_usd` and backing is delivered, and the wallet applies it.
  3. The DB still holds the old books, and the top-up rollback snapshot was not shifted, because that transaction failed.
  4. After a restart or reconnect, `reconcile_from_grpc` reloads the old books. The backstop then re-deducts at a later price and sends another SYNC.
  5. While the process keeps running, the next top-up persists the in-memory books through `record_stability_settlement_with_rollback`.
  The SYNC can therefore publish state the DB never held, and a top-up failing in that window rolls back against a stale snapshot.
- Why it matters: it breaks the "save before publish" invariant that #321 and #326 set out to guarantee.
- Fix: compute the forward's result on a clone. Commit it through `PendingBookUpdate`, extended to carry a `keep_pending_rollback` flag for `save_channel_after_spend`, with `needs_sync = deducted`. Only then update memory and send the SYNC. Apply the same change to `reconcile_incoming_stability`.

### C-6: A dropped forward deduction falls back to the balance backstop, which any later receive cancels; the spend is never deducted and no SYNC is sent
- Severity: Medium
- Confidence: Confirmed on the LSP side. Plausible for the wallet divergence, because the desktop wallet deducts locally on `PaymentSuccessful` (`src/user.rs:3372`).
- Where: `stable_manager.rs:2778-2784` (channel not yet hydrated, e.g. at startup), `2787-2797` (`list_channels` error), `2816` (price cold: silent return with no audit). The accounting price is cold after 60 s without refresh (`price_feeds.rs:18`). Also C-1 and C-3 losses, and `backfill.rs:425` (backfilled forwards are audit-only). The debounce is cleared as soon as live ≥ backing (`stable_manager.rs:2281-2283`).
- Related: #324, #395, #296
- Status on main: open. Not addressed by PR 400 or PR 415.
- What happens: the forward event is lost or skipped. On the first tick live < backing (count 1). The user then receives R ≥ T − N within about 60 s. Live returns to ≥ backing, the debounce resets, and the spend is never deducted. The LSP keeps E with backing B, so the received sats silently become stable USD: an unrequested, fee-free trade that skips the stabilization cap. The wallet has already deducted locally and no SYNC goes out. The two books disagree, which leads to `InvalidFee` trade rejections where the fee is not refunded (the #296 symptom), and to mismatched settlement amounts.
- Why it matters: the backstop is the only recovery for every dropped forward, and it is not monotonic.
- Fix: make forward handling durable. Write a "forward to apply" row (`prev_user_channel_id` and total msat) when the event arrives; the observed-forward ledger row already holds this. Retry it until applied. Never drop a deduction because of a cold price or an RPC error. Upstream, ask LDK Server to put the forward `id` in the live `PaymentForwarded`, so the daemon can key deductions by id and apply backfilled forwards safely.

### C-7: On the first low-balance tick the regular tick still pays a top-up computed on the full book backing
- Severity: Low
- Confidence: Confirmed
- Where: `stable_manager.rs:2219-2224` (the wake pass skips a channel with a possible unreconciled spend) compared with `2223-2285` (the regular tick falls through when the count is below 2), then `2334-2506` (top-up).
- Related: #395 (closed unmerged; it added "settlement waits while balance below backing")
- Status on main: open. Not changed by PR 400 or PR 415.
- What happens: a spend T settled at p0 but its deduction was missed. On the first tick at p1 < p0 the LSP tops up drift on B, which still includes T. The backstop then deducts T at roughly p1. The LSP has paid T·(p0 − p1) for sats already spent. The test at `stable_manager.rs:4599` passes only because the price does not move.
- Fix: in the regular tick, `continue` (skip settlement) while their_sats is below backing. With PR 415, use `recoverable_sats < backing`, so that pending HTLCs still get their top-ups.

### C-8: The LSP values a zero-backing position at the user's whole live balance (the wallet already fixed this rule)
- Severity: Low
- Confidence: Confirmed mechanism. Plausible trigger: it needs live to reach exactly 0, so a zero-reserve LSPS2 channel (`disable_client_reserve`), or an operator edit made while the price is cold (`stable_manager.rs:835-839`).
- Where: `stable_manager.rs:2287-2291`. Compare the wallet's fix in `src/stable.rs:538-553` and `927-933`. Backing reaches 0 with target > 0 through `src/stable.rs:207-220` when a below-par user spends everything.
- Related: #322 (the mirror case)
- Status on main: open, and in PR 400 and PR 415.
- What happens: once a user's backing is 0, any native sats they later hold count as the stable position. If they cover the target, the LSP reports `CHECK_ONLY` (and sends "user_to_lsp" wake pushes) and never pays the shortfall E′ it owes. Otherwise it pays only E′ − native and books the native sats as backing. The user loses min(E′, native).
- Fix: value the position as `backing_sats·price` (0 when backing is 0), or use the wallet's `effective_backing_sats`.

### C-9: Soft-closed channels are never re-hydrated by reconcile, despite the comment saying they are
- Severity: Low
- Confidence: Plausible. Unverified step: LDK returning a successful `ListChannels` that omits a live channel. The response is not paginated (`ldk-server/src/api/list_channels.rs`), so this needs a misconfigured or restored node.
- Where: `stable_manager.rs:1009-1031` (soft-close when the channel is missing from the snapshot); `db.rs:1942-1949` (`load_all_channels` loads only active rows, so the reappearing channel is ignored).
- Status on main: open.
- What happens: the channel silently drops out of stability service. Forwards are not deducted. Signed pay-backs are invalidated as `channel_not_stable` while their sats are kept (`stable_manager.rs:1680-1704`). A later splice `Ready` re-registers the channel at $0 (#413 class).
- Fix: in reconcile, rejoin live channels against all rows, including closed ones. Soft-close only on `ChannelClosed`, or when the channel is absent from several consecutive snapshots.

## 3. Design observations

1. **At-most-once events drive the accounting.** LDK Server marks events handled before delivery and silently skips lagged ones. The daemon has no event identities to checkpoint. The stream checkpoint tracks only audit coverage and marks a gap `completed` even though receive and failure effects cannot be rebuilt. Applied receipts are deduplicated per kind (settlement id, payment id, explained balance), but missed events have no recovery. Closing this class needs one of:
   - an at-least-once source: persisted events, subscription from a cursor, and an ack after the SC commit;
   - an idempotency key on every accounting input (forward id in live events, payment id for receives), stored in the same transaction as the book change;
   - out-of-band re-presentation of signed envelopes, checked against LDK's payment store.
2. **One balance backstop is the fallback for everything.** It cannot tell apart a settled spend, a pending HTLC (#324, C-4), an unclaimed top-up (#392), a failed but un-rolled-back top-up (C-2), a lost pay-back (C-1), or a later receive (C-6). Its debounce resets whenever the balance recovers. Replace it with explained-balance accounting: track expected user sats as committed live balance plus known settled flows, take pending HTLCs from `GetBalances`, and deduct only unexplained settled outflow. A recorded gap must never be erased by an unrelated inflow.
3. **Commit-then-publish is not universal.** Backstop, splice, trade and signed pay-back writes go through DB-first or `PendingBookUpdate`. The forward path and the legacy pay-back path still mutate memory first (C-5). Routing every book change through one helper would close this class.
4. **Protocol keysends are auto-claimed before validation.** A malformed, expired, mismatched or replayed signed pay-back or trade is still claimed. Its sats stay with the LSP, and the backstop later reads the drop as a spend. Validating at `PaymentClaimable` with manual claim, or refunding invalid ones, would bounce them instead.
5. **`stable_receiver_btc` does two jobs.** It is the tick's cache and also the legacy-marker attribution baseline. Several paths refresh it (forward handler, reconnect rebuild, tick), and each refresh can cancel a legacy pay-back's attribution. #414 is one instance; the forward handler at `stable_manager.rs:2821` is another. Removing the unsigned path (#235, #284) closes this.
6. **Per-HTLC attribution.** The handler uses the first `prev_htlcs` locator and the event total (`event_loop.rs:470-476`). `HtlcLocator.amount_msat` is available and should be used if multi-HTLC (trampoline) forwards are ever enabled.
7. **A non-cooperative wallet keeps the upside.** It can spend above-par surplus and never pay it, and the LSP cannot pull (#322 excludes modified clients). Loss is capped at the unpaid surplus. Server-side enforcement would need HTLC interception, refusing forwards that consume owed surplus.
