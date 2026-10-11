# Review B: Trades (buy/sell of the stable USD position)

Scope: `main` @ 9aff5ec, read-only. Files traced: `server/stable-channels-lsp/src/stable_manager.rs` (trade handler, rejections, response retry), `messages.rs`, `trade_response_retry.rs`, `revenue.rs`, `handlers/revenue.rs`, `event_loop.rs`, `backfill.rs`, `src/trade.rs`, `src/stable.rs`, `src/db.rs`, wallet counterparts (Android `TradeProtocol.kt`, `TradeService.kt`, `DatabaseService.kt`; iOS `TradeProtocol.swift`, `TradeService.swift`, `ChannelRepository.swift`; desktop `src/user.rs`). Open PRs `upstream-pr-400` and `upstream-pr-415` diffed for trade code.

## How a trade works on main (for orientation)

1. The wallet signs `TRADE_V1 {channel_id, user_channel_id, trade_id, expected_usd (absolute target), quote_price, ts}` and sends it as a keysend TLV whose amount is the 1% fee. All three current clients send `trade_id`, so they use the "hardened" path (Android `TradeProtocol.kt:262-272`, iOS `TradeProtocol.swift:304-307`, desktop `src/user.rs:2545-2556`).
2. LDK Server (vanilla) auto-claims the keysend. The SC daemon gets `PaymentReceived` over a fire-and-forget event stream (`event_loop.rs:462-466`) and runs `handle_trade_payment` under the global manager lock (`event_loop.rs:388-390`).
3. Hardened path (`stable_manager.rs:3137-3410`): verify the signature against the counterparty of the channel named in the payload, then dedupe by payment id and trade id. Then check: freshness ±15 min, fee equals the expected fee for `current.expected_usd → new` at `quote_price` ±1 sat, quote within 0.5% of the LSP price, target ≤ live receiver value, drift-preserving allocation, and the shadow-mode cap. Acceptance is written atomically with the channel row (`db.rs:1513-1565`). Any rejection is signed and persisted (`stable_manager.rs:616-690`). A worker re-sends the response for up to 14 days (`trade_response_retry.rs`, `stable_manager.rs:545-590`).
4. There is no fee refund on rejection. The operator can refund a fee manually through `RefundTradeFee` (`revenue.rs:660-741`), but only for a trade with a persisted `rejected` decision.
5. The two-phase "quote, then confirm, then pay the fee" flow (PR #240) and PR #217 were closed without merging. Main only has the fee-first flow.

## 1. What is done right

- **The signature is bound to the channel's real counterparty.** The channel named in the payload is authoritative and there is no fallback when it is present (`stable_manager.rs:3074-3092`). The signature is checked against `chan.counterparty_node_id` from LDK (`3094-3108`), not a key the sender supplies. Responses go only to that node (`3191-3193`, `3370-3371`). Test: `correlated_unknown_explicit_channel_does_not_fall_back_or_respond`.
- **Verify, then write.** The `settlement_payments` label is written only after the signature verifies (`stable_manager.rs:3122-3133`), so a forged envelope cannot plant rows.
- **The LSP never books a value the client supplies.** Allocation is derived locally from LDK's live balances (`channel_peer_balances`, `stable_manager.rs:52-58`) at the LSP's own fresh price. The quote is only a consent bound (`stable_manager.rs:3296-3310`, `3534-3537`; `src/stable.rs:384-445`). A trade cannot make the LSP "buy" BTC it never received: no sats move except the fee, and `backing ≤ live receiver sats` is enforced (`src/stable.rs:402-405`, `444`). Tests: `trade_uses_lsp_price_instead_of_client_allocation`, `trade_ignores_legacy_allocation_not_derived_from_quote`.
- **The fee is bound to the money actually received.** The LDK-reported `amount_msat` is compared with the fee recomputed from the LSP's own `expected_usd` (`stable_manager.rs:3280-3290`, `104-134`). A missing amount is fatal (`3159-3165`).
- **Price freshness.** Trade decisions use only a cached price that is no more than 60 s old and not quarantined (`src/price_feeds.rs:18`, `134-138`, `240-249`). When the cache is cold, the trade is rejected instead of priced at 0 (`stable_manager.rs:3273-3279`).
- **Replay and idempotency.** `trade_decisions` has `inbound_payment_id` as primary key and `trade_id UNIQUE` (`db.rs:100-121`). Acceptance re-checks for duplicates and compare-and-swaps `sync_version` inside one IMMEDIATE transaction together with the channel-row update (`db.rs:1537-1564`). Memory is updated only after commit (`stable_manager.rs:3379-3388`). A duplicate delivery of the same payment re-queues the exact stored response (`3171-3182`, `db.rs:1464-1481`). The LSP can never both accept and reject one trade: the acceptance transaction checks for duplicates, and rejection is `INSERT OR IGNORE`.
- **Rejections are signed, durable and closed-vocabulary.** The rejection payload uses a closed reason enum with `deny_unknown_fields` (`src/trade.rs:8-21`, `84-95`). It is persisted before delivery (`db.rs:1484-1510`). Every rejection branch leaves the allocation unchanged (test `every_authenticated_correlated_rejection_keeps_allocation_unchanged`).
- **Response delivery is crash-safe.** Each attempt is reserved durably with a compare-and-swap on the attempt count before sending (`db.rs:1587-1600`). In-flight sends are reconciled from LDK after a crash or a missed event (`stable_manager.rs:546-570`). Retries are bounded: 5 s doubling to a 1 h cap, a 14-day deadline, and pruning after 30 days (`db.rs:1582-1585`, `1640-1654`).
- **Drift is preserved across trades.** Only the change in target is converted at the current price, and a full exit is refused while settlement drift is actionable (`src/stable.rs:407-434`; `stable_manager.rs:3310-3324`).
- **Domain separation.** The inner `type` is inside the signed bytes and each parser requires its own type (`stable_manager.rs:3037-3043`; `messages.rs:131-144`). Signed stability payments travel in a separate TLV type, and a malformed signed record never falls back to the trade or legacy-marker path (`stable_manager.rs:1411-1426`). A SYNC, STABILITY_PAYMENT or REGISTER_PUSH signature cannot be replayed as a TRADE_V1, or the other way round.
- **The refund is exactly-once.** The claim row is written before the send, the preimage is deterministic so LDK de-duplicates on the payment hash, and the refund can only cover a `rejected` decision. The amount is what was received and it goes to the decision's counterparty (`revenue.rs:660-741`; `db.rs:956-1021`). The endpoint is behind HMAC auth, and the wallets do not hold the SC api key (`main.rs:277-284`; a grep of the Android and iOS sources finds no `X-Auth` or api key).

## 2. Findings

### B-1: A trade keysend that never reaches a durable decision keeps the fee, gets no answer, cannot be refunded, and blocks trading on that channel in every wallet
- Severity: High
- Confidence: Confirmed
- Where: `server/stable-channels-lsp/src/event_loop.rs:386-390`, `backfill.rs:107-170`, `stable_manager.rs:3063-3072`, `3083-3092`, `3094-3108`, `3200-3207`, `637-655`, `657-689`; `revenue.rs:668-671`; Android `DatabaseService.kt:682-693`, `1059-1071`; iOS `ChannelRepository.swift:268-272`; desktop `src/db.rs:2253-2257`
- Related: #205 (its expiry goal), #240 (closed, unmerged)
- Status on main: open. PR 400/415 do not change it (they touch only the top-up paths in `event_loop.rs` and `backfill.rs`).
- What happens: LDK Server claims the keysend before the SC daemon sees it. The daemon's only trigger is the live `PaymentReceived` event, which is not replayed: the comment at `event_loop.rs:386-387` says "LDK Server does not replay its stream". Reconnect backfill only writes `PAYMENT_RECONSTRUCTED` audit rows for payments (`backfill.rs:107-127`) and never re-runs `handle_payment_received`; LDK payment records do not carry the TLV in any case. There is no inbox write before processing. So any of these routine events ends with the fee kept and no `trade_decisions` row:
  - (a) The trade keysend lands while the daemon is restarting or its stream is reconnecting.
  - (b) The daemon crashes anywhere inside `handle_trade_payment`.
  - (c) A transient `list_channels` error (`3063-3072`: audit, then `return`).
  - (d) A transient `verify_signature` gRPC error, treated as an invalid signature (`3101-3108`).
  - (e) `sign_message` or the DB write fails inside `reject_correlated_trade` (`637-655`, `681-688`).
  - (f) The channel closes between the payment and the processing (`3083-3092`).

  `RefundTradeFee` then refuses with `NotRejectedTradeFee` because no decision exists (`revenue.rs:668-671`). On the wallet side, the trade becomes `uncertain` after 15 min (Android `DatabaseService.kt:1059-1071`). The only exits from that state are a signed acceptance or rejection (`DatabaseService.kt:1228`, `1308`) or a failed fee send (`942`, `1016`). Meanwhile `recordPreparedTrade` refuses every new trade on that channel while an `uncertain` row exists (`682-693`). iOS (`ChannelRepository.swift:268-272`) and desktop (`src/db.rs:2253-2257`, which blocks on `outcome_unknown`) behave the same way.
- Why it matters: one LSP restart during a user's trade costs the user the 1% fee and permanently disables trading on that channel. Recovery needs the operator to keysend manually plus manual surgery on the wallet database. The inbound stability path has a durable inbox (`register_inbound_stability_settlement`, `stable_manager.rs:1579-1599`); trades do not.
- Fix:
  1. In `handle_payment_received`, persist `{payment_id, payment_hash, amount_msat, raw TLV, received_at}` to a `trade_inbox` table as the first action, before any gRPC call. Process trades from the inbox, and leave rows pending on transient LDK or DB errors (retry like `pending_book_updates`).
  2. For stream gaps, where the TLV is unrecoverable, add a wallet-initiated status query: a signed 1-msat `TRADE_STATUS_V1` carrying `trade_id` and the original payment hash. The LSP answers from `trade_decisions`. If it has no decision for a payment that LDK shows as received, it persists a signed `internal_failure` rejection, which makes the fee refundable.
  3. Let `RefundTradeFee` also cover inbound keysends from a stable-channel counterparty that have no accepted decision.
  4. Give the wallets an expiry-based exit from `uncertain` (for example after `RESPONSE_RETRY_WINDOW_SECS`) that resyncs from the LSP.

### B-2: Every rejection keeps the full fee, and a stale wallet base state produces repeated paid `invalid_fee` rejections with no corrective SYNC
- Severity: High
- Confidence: Confirmed (seen in production in #296: 6 of 6 trades rejected, each fee kept)
- Where: `stable_manager.rs:3280-3290` (fee checked against the LSP's `current.expected_usd`), `3270-3279` (cold LSP price gives `InvalidQuote`), `3291-3295`, `3247-3253`, `616-690` (no SYNC or refund on rejection); `revenue.rs:660-671` (manual refund only); `handlers/revenue.rs:34-47`
- Related: #296, #238, #240 (two-phase, closed unmerged), #205
- Status on main: open. #296 fixed only the Android root cause of that particular desync. PR 400/415 do not change this.
- What happens: the signed request carries an absolute target, not the base state it was computed from. The LSP recomputes the fee from its own `current.expected_usd` (`3280-3286`). If the wallet's view of `expected_usd` differs by more than about $0.10 (for example a missed ordinary SYNC, whose retries stop after 10 attempts, `stable_manager.rs:29-35`, or any wallet-side bug like #296), the fee check fails. The trade is rejected with `invalid_fee`, the fee is kept, and the LSP sends nothing else. The wallet keeps its stale base, so every following trade fails the same way.

  Several rejections are also caused purely by the LSP:
  - `InvalidQuote` when the LSP's price cache is cold or quarantined (`3275-3278`).
  - `QuoteDeviation` against an LSP price that can be up to 60 s old (`3291-3295`).
  - `StaleRequest` when the LSP's own event processing is delayed by more than 15 min, for example while `lock_for_event` spins on an unsaved correction (`stable_manager.rs:370-383`).
  - `InternalFailure`.

  All of them keep the fee, and the only refund is a manual operator action.
- Why it matters: users lose 1% per attempt under realistic conditions, and the LSP earns "revenue" from its own outages. The rejection code does not tell the wallet how to recover.
- Fix:
  1. Add `base_sync_version` (and `old_expected_usd`) to the signed `TRADE_V1`. Reject a mismatch with a distinct code, and queue an ordinary SYNC (`startup_sync_pending`) so the wallet converges.
  2. Automatically queue a durable refund for every rejection not caused by the user. Reuse the deterministic-preimage path in `revenue.rs` from a worker; it is already exactly-once.
  3. In the longer term, land the two-phase quote/confirm flow (#240) so the fee is only paid against a signed reservation.

### B-3: If the payment lookup fails, the signed result carries the LSP-local payment id instead of the payment hash, so the wallet discards an acceptance the LSP has already booked
- Severity: Medium
- Confidence: Plausible. Unverified step: a transient `get_payment_details` failure, or a missing `kind`, at the moment of the decision.
- Where: `stable_manager.rs:595-613` (falls back to `inbound_payment_id`), `3168-3169`, `3343-3352`, `629-636`; `event_loop.rs:462-466` (the event's `Payment.kind`, which contains the hash, is not passed); Android `DatabaseService.kt:1116-1160`
- Related: commit 4fdbe49 ("correlate trade results by payment hash, not LDK payment id")
- Status on main: open. PR 400/415 do not change it.
- What happens: the comment at `592-594` says that from ldk-node 0.8 the inbound payment id is not the hash. Wallets store `trade_payment_id` as their outbound keysend id, which is the hash (Android `TradeService.kt:150-175`). When `correlation_payment_id` cannot read the hash, it silently uses the LSP-local id. The acceptance then commits on the LSP side with `expected_usd` changed (`3365-3388`) and is retried until LDK reports the 1-msat response as delivered (`event_loop.rs:496-500`). The wallet compares `storedPaymentId != correlation.tradePaymentId`, gets INVALID (`DatabaseService.kt:1155-1160`), and the trade stays `uncertain`. The LSP's book now holds the new target while the wallet keeps the old one, and trading is blocked (see B-1).
- Why it matters: the two books diverge silently, and stability payments are computed from different targets on each side.
- Fix: pass the hash from the `PaymentReceived` event's `Payment.kind` into `handle_trade_payment` and drop the extra gRPC call. If the hash is still unavailable, do not decide; leave the trade in the inbox from B-1.

### B-4: A trade made while an LSP top-up is in flight absorbs the optimistic top-up; if the top-up then fails, the rollback misses and the user silently loses the amount owed
- Severity: Medium
- Confidence: Confirmed. The code path is traced; the trigger needs the top-up HTLC to fail after the trade is processed.
- Where: `stable_manager.rs:2445-2447`, `2503-2507` (optimistic `backing_after` on send); `3257-3264`, `3302-3310` (the trade builds on in-memory backing); `src/db.rs:4546-4561` (rollback compare-and-swap needs `stable_sats = after AND expected_usd = old`); `stable_manager.rs:720-733` (in-memory guard)
- Related: #411, PR #400, PR #415
- Status on main: open. Fixed by open PR #400: the top-up is recorded as in flight without touching backing and is booked on claim. Its test `trade_while_a_top_up_is_on_the_way` covers both outcomes. PR #415 also refuses a full exit while a top-up is pending.
- What happens (worked example):
  1. The position is E = $50 with backing 50,000 sats at $100k. The price drops to $80k.
  2. The tick sends a $10 top-up (12,500 sats) and sets backing to 62,500 before it lands.
  3. A hardened buy of $15 (E: 50 → 35) arrives and books backing = 62,500 − 62,500 + 43,750 = 43,750.
  4. The top-up fails. `rollback_failed_stability_settlement` marks it `failed`, but the compare-and-swap misses because the row now holds 43,750 and $35. The in-memory guard misses too.
  5. The books show $35 backed by 43,750 sats, which is exactly at par, so no new top-up is sent. The user's true entitlement was $90 and the books now show $80.
- Why it matters: the user silently loses the stability amount the LSP owed.
- Fix: merge PR #400. Until then, the trade handler should reject with `settlement_required` (or compute from the pre-top-up backing) while `settlement_payments` holds a pending outbound stability payment for the channel.

### B-5: Results are delivered only by a backoff timer: nothing fires when the peer reconnects, no push wake is sent, and after 14 days an accepted trade is abandoned with no ordinary SYNC fallback
- Severity: Medium
- Confidence: Confirmed for the code path. Plausible for the trigger, which depends on mobile users who close the app after trading.
- Where: `src/db.rs:1582-1585` (backoff capped at 3600 s), `1640-1644` (abandon); `stable_manager.rs:472-541` (no wake), `3332-3409` (acceptance never adds `startup_sync_pending`); `stability_tick.rs:261-299` (the reconnect hook only runs a stability pass)
- Related: #205, #248
- Status on main: open. PR 400/415 do not change it.
- What happens: if the user is offline when the decision is made, each send fails and the next attempt is delayed 5 s, then 10 s, and so on up to 1 h. When the user reopens the app, nothing triggers delivery; it waits for the next hourly slot. A short mobile session can miss it again and again. After 14 days the row is `abandoned`. For an acceptance, the LSP has already changed `expected_usd` and `sync_version` but never queues an ordinary SYNC, so the wallet keeps its old target permanently and stays blocked (B-1).
- Why it matters: the LSP and wallet books diverge for an accepted trade, and the user can no longer trade on that channel.
- Fix:
  - On peer connect (the `WakeWatcher` hook already exists), reset `next_response_attempt_at = now` for that counterparty's pending responses.
  - Include pending trade results in push-wake decisions.
  - On abandoning an *accepted* decision, insert the channel into `startup_sync_pending` so the ordinary SYNC path delivers the allocation.
  - Add the wallet status query from B-1 so delivery can also be pulled.

### B-6: Trades are effectively free to spam: a 1-msat fee floor with 1-sat tolerance, no minimum size and no rate limit, while each request does several gRPC calls under the global manager lock and creates a permanent decision row plus a 14-day response obligation
- Severity: Medium
- Confidence: Plausible. Unverified: the request rate an attacker can reach per channel.
- Where: `stable_manager.rs:133` (`.max(1)`), `136-142` (±1000 msat), `3288`; `stable_manager.rs:3063`, `3094`, `3169`, `637` (gRPC calls inside the lock, `event_loop.rs:388-390`); `src/db.rs:100-121` (rows are never deleted, only envelopes pruned), `1612-1618`, `stable_manager.rs:546-570` (every in-flight response is polled every second)
- Related: #230
- Status on main: open. PR 400/415 do not change it.
- What happens: any channel peer can sign trades whose expected fee rounds below 1 sat (|Δ| up to about $0.19 at $100k) and pay 1 msat for each. Even a request that is rejected (for example a no-op, `3267-3269`) costs the LSP the following, all while holding the lock that also serializes forwards accounting and stability ticks:
  - `list_channels`, `verify_signature`, `get_payment_details` and `sign_message`;
  - two or three DB writes;
  - a signed decision row that is never deleted;
  - a 1-msat response keysend retried for up to 14 days.

  A peer that holds the response HTLCs instead of failing them keeps them `in_flight`, and the retry worker then calls `get_payment_details` for each of them every second.

  Splitting a trade into many micro-trades also avoids most of the 1% fee. That is a small revenue leak, bounded by the fee itself.
- Why it matters: a cheap way to slow down or deny service on the LSP's main event path, plus unbounded growth of the DB and the retry queue.
- Fix:
  - Enforce a minimum trade size or fee (for example ≥ 10 sats) and reject below it before doing any gRPC work.
  - Rate-limit trades per counterparty, checked before `list_channels`.
  - Cap pending responses per counterparty.
  - Back off the polling of in-flight responses.
  - Look up the trade's channel without listing every channel.

### B-7: The legacy (no `trade_id`) path is still reachable: unbounded replay when `ts == 0`, silent unrefundable rejections, free no-op trades, and memory updated before the DB
- Severity: Low (no current client uses this path; it is reachable by old or custom clients)
- Confidence: Confirmed
- Where: `stable_manager.rs:3412-3426` (`ts == 0` skips freshness; there is no nonce inside the 300 s window), `3447-3488` (quote optional, tolerance 0.5% of the fee), `3592` then `3604-3618` (`*sc = updated` before `save_channel`; a save failure returns with memory ahead of the DB and no SYNC), `3633-3645`
- Related: #145, #235, #284
- Status on main: open, tracked by #235/#284. PR 400/415 do not change it.
- What happens:
  - A signed legacy envelope with `ts == 0` can be replayed forever by anyone who holds it, on a fresh keysend that pays the fee. It re-sets the absolute target at the LSP's current price.
  - Any legacy rejection is silent: there is no decision row, so the fee cannot be refunded (`revenue.rs:668-671`).
  - A no-op legacy trade costs 1 msat and forces a signed SYNC and a `sync_version` bump.
  - If `save_channel` fails, the in-memory target drives ticks until a restart silently reverts it to the DB value.
- Why it matters: this extra code path is weaker than the hardened one, and its DB-failure case breaks the persist-then-publish rule used everywhere else.
- Fix: remove the path (#284). If it must stay for now, reject `ts == 0`, require `quote_price`, and route the update through `pending_book_updates` / `persist_pending_book_update`.

### B-8: The signed SYNC acceptance echoes a user-chosen `user_channel_id` string
- Severity: Info
- Confidence: Confirmed
- Where: `stable_manager.rs:3339-3345`
- Related: none
- Status on main: open
- What happens: `response_user_channel_id = payload.user_channel_id.unwrap_or(chan.user_channel_id)`, so the LSP signs an arbitrary string from the sender inside a `SYNC_V1`. Wallets key on `channel_id`, which comes from the LSP (Android `DatabaseService.kt:1155-1157`, `1185-1187`), so I found no exploit.
- Fix: always sign `chan.user_channel_id`, or reject a payload whose `user_channel_id` disagrees with the channel.

## 3. Design observations

1. **Fee-first is the root of the user-loss findings.** LDK Server claims the fee before the LSP has decided anything, so every rejection, drop or crash turns into "user paid, got nothing" (B-1, B-2, B-7). Two designs close this class. One is a reservation: a 1-msat signed proposal, then a signed quote reservation, then the fee (#240, closed and unmerged). The other is a durable automatic refund for every terminal state that is not "accepted", driven by the existing exactly-once refund code in `revenue.rs`.
2. **Trades lack the inbox that stability payments have.** The stability path persists the raw signed payload on first receipt and retries from it (`register_inbound_stability_settlement`). The trade path processes straight off a non-replayable stream. Adopting the same inbox pattern for trades closes B-1 (a, b, c, d, e) and B-3.
3. **The protocol has no guaranteed terminal outcome, while the wallets assume one.** All three wallets refuse new trades until a signed result arrives, but the LSP has many silent `return`s before it records a decision. Either every authenticated trade must get a decision, or the wallet needs a pull query and an expiry exit. Today neither exists.
4. **The base state is not signed.** The request carries an absolute target. Correctness then depends on the fee check implicitly catching a stale base, which only works for mismatches above about $0.10 and reports them as `invalid_fee`. Signing `base_sync_version` makes the race explicit and lets the LSP answer with a corrective SYNC.
5. **Optimistic bookkeeping of concurrent flows.** Trades build on the in-memory allocation, which can include optimistic, unconfirmed top-ups. Any flow that books before the money lands needs a compare-and-swap that survives later trades, or should not book until claim (PR #400's approach).
6. **One global lock with network I/O inside it.** The trade handler makes 4–5 gRPC calls while holding the manager mutex that also serializes forwards, splices and ticks. This amplifies B-6 and stretches every other event's latency. Doing the signature check and channel lookup before taking the lock, and re-validating under it, would shrink the critical section.
7. **Oracle-lag arbitrage is bounded only by the 1% fee and 60 s staleness.** Trades are priced at the LSP's cached price, which may be up to 60 s old (`price_feeds.rs:18`), and the client's quote must sit within 0.5% of that price. A user with a faster feed gains whenever the LSP's price lags the market by more than 1%. This is acceptable today. A tighter freshness bound for trades (for example ≤ 20 s) or a volatility pause would close it.
8. **The backing cap is in shadow mode.** `enforce_max_stabilization = false` by default (`config.rs:17`, `stable_manager.rs:746`). That is a documented rollout decision (`docs/99-percent-limit.md`). Until it is enforced, a trade can label 100% of the user's balance, including their channel reserve, as backing.
