# Stable Channels LSP: PR and Issue Catalog

Source: `toneloc/stable-channels` upstream, read 2026-10-11 through GitHub search (bodies only; issue and PR comment threads could not be read, so threads with comments are flagged "N comments unread").
Local repo: `main` @ `9aff5ec`. The clone is shallow (history starts around 2026-08-24, at the #242 merge). So merge status for PRs before that comes from GitHub `merged_at`. For later PRs it is also checked against `git log` merge commits. For key artifacts it is also checked against the code on `main` (grep).

Legend: **M** = merged (date from `merged_at`), **CU** = closed unmerged, **O** = open.

---

## 1. Issues

### 1a. LSP and LSP-accounting issues

| # | Title (short) | State | Bug class | Failure path (short) | Fixing PR |
|---|---|---|---|---|---|
| 416 | One unreadable pending top-up row pauses top-ups for every channel | open | Persistence / fail-closed granularity | Only exists after #400. The tick reads `stability_top_ups_in_flight` all at once, so one bad row fails the whole read. No channel gets a top-up, Release is refused, and the only alarm is a yellow item that points the wrong way (`STABILITY_TOP_UP_LOOKUP_FAILED`, no channel). | none. Introduced by open #400 |
| 414 | Unsigned pay-back right after a top-up claim is not matched; target cut | open | Signed-message matching / attribution + backstop | A legacy `[1]` pay-back is attributed by balance drop against `stable_receiver_btc`, which is refreshed only at the tick, not on claim. Claim and pay-back in the same tick cancel out, so the payment is `STABILITY_RECEIVE_UNATTRIBUTED`. The books keep the top-up, and two ticks later the backstop cuts the target (repro: $50 to $37.50) and sends a SYNC. Pre-#288 wallets only. | none. Issue says the result is the same on the #400 branch. Goes away with #235/#284 |
| 413 | Channel-ready before the channel list is loaded resets the target to $0 | open | Startup event ordering | `handle_channel_ready` looks only at the in-memory list. That list is empty until the first reconcile, which waits for a price. A splice-confirm ChannelReady in that window is treated as a new channel and saved as $0/0/all-native over the existing row. No SYNC, no alarm, does not heal. | none. Neither #400 nor #415 touches `handle_channel_ready` |
| 412 | Release-Acquire unnecessary in event_loop.rs | open | Code quality | Atomic ordering at `event_loop.rs` L50-75 could be Relaxed. Performance nit, no correctness bug. | none |
| 411 | Top-up sent but not recorded can be sent again | open | Send-before-record | `spontaneous_send` runs first and the record call sits in the `Ok` arm. A crash or DB failure between them leaves no row, and the cooldown is in memory only, so a restart clears it. Result: a duplicate top-up, and the first claim is never booked. Same shape: SYNC send, correction SYNC, trade-fee refund (`revenue.rs`). | none. #400 keeps the order and only sets the in-memory cooldown when the record fails |
| 401 | Channel reserve counted as stable backing; owed payment can become unpayable | open | Capacity accounting (reserve) + no backoff | All clients and the LSP compute balance as outbound + reserve, so backing can consist of reserve sats. An above-par settlement then cannot be sent (spendable is 0). The wallet retries every 60s, the LSP wakes the phone every 10 min, and the #344 guard blocks every spend. | none. Related #136, #337, #344 |
| 392 | Unclaimed LSP top-up deducted from the target as a spend | open | Pending vs settled / optimistic booking | The LSP raised backing at send. The live balance (inbound capacity) leaves out the unclaimed HTLC, so after 2 ticks the backstop cuts the target and sends a SYNC. A later claim does not restore it, and a failure rollback is skipped because backing has changed. #376 raises the exposure. | #395 CU. #400 O |
| 324 | Pending Lightning payments trigger a false stable-balance deduction | open (4 comments unread) | Pending vs settled | The backstop uses capacity, which excludes unresolved HTLCs. A user payment pending for 2 ticks or more cuts the target, and if the payment then fails the target is not restored. | #395 CU. #415 O (stacked on #400) |
| 323 | Ordinary SYNC not retried after later delivery failure | closed | Retry / delivery | `send_sync_message` counted LDK accepting the send as success. `PaymentFailed` did not requeue an ordinary SYNC. | #332 M 09-23 |
| 322 | Preserve and settle amounts owed to the LSP when spends clear stable USD | closed | Capacity change + guard | A spend or splice-out before the surplus is paid. A $0 target released the leftover backing to native, and the LSP skipped $0-target channels. | #344 M 09-23 (#331 CU) |
| 321 | Failed balance save still updates memory and sends SYNC | closed | Persistence ordering | `handle_channel_ready_splice` and the backstop mutated memory, ignored the `save_channel` error and sent a SYNC. A restart then reloaded older books. | #326 M 09-23 |
| 372 | Stale DB channel rows inflate Stable USD after force close | closed | Capacity change (close) | The wallet kept the closed channel's row, so the next channel's target was added to it. | #373 M 10-01 (wallet only: touches no `server/` or `src/` code, even though #234 credits it with an LSP startup reconcile) |
| 296 | Android expectedUSD/backingSats desync: $8.53 payment, 100% `invalid_fee` | closed (1 comment) | Partial persistence (wallet) | `reconcileOutgoing` was saved with `preserveBacking=true`, so the target was reduced but backing was not. The phantom surplus was paid. The LSP logged a 16% `STABILITY_PAYMENT_STATE_DIVERGENCE` and applied it anyway. | #299 M 09-11 (wallet). The LSP-side rejection backstop was never done |
| 270 | Signed `STABILITY_PAYMENT_V1` on Android and iOS | closed (2) | Signed messages | Mobile still sent the unsigned `[1]` marker, and iOS dropped signed settlements. | #288 M 09-09 |
| 246 | Android: support signed settlements from #233 | closed | Signed messages / SYNC semantics | During the port, a SYNC re-anchoring the full position at local price was found to suppress stability payments for 24h, and `sync_version` was not enforced. | superseded by #248/#270/#288 |
| 235 | Track: removal of protocol backward compatibility | open | Legacy / auth | The LSP still accepts `ts==0` TRADE_V1 (no freshness check, `stable_manager.rs:3412-3414`), the unsigned `[1]` marker, a wider legacy fee tolerance, and no-`trade_id` requests. | mobile side done (#248, #288). LSP acceptance remains |
| 284 | Chore: remove legacy trade/stability compatibility | open | Legacy / auth | (empty body) | none |
| 234 | Client and Server Behavior (spec) | open | Spec | See section 4 | n/a |
| 232 | Settlement re-anchors to the LSP price, not the paid amount | open | Signed-message / accounting precision | The `.max(equilibrium)` floor in `backing_after_user_to_lsp_stability` (`src/stable.rs:782-786`) re-anchors to the LSP price. Peers then disagree by up to the 0.5% quote band (about 40 sats on $10) instead of about 1 sat. | none |
| 205 | Signed trade rejection and pending-trade expiry | closed (2) | Retry / delivery | A rejected trade gave no response, so the wallet stayed pending forever. | #217 CU, then #238 M 08-14 (LSP/desktop) and #248 M 09-01 (mobile) |
| 204 | iOS/Android: signed TRADE_V1/SYNC_V1 allocations | closed | Signed messages | Mobile omitted `quote_price`/`ts`. Mobile SYNC applied only `expected_usd` and ignored `channel_id`/`sync_version`. | #248 M |
| 187 | Detangled LSP does splice accounting post-confirmation only | closed (1) | Capacity-change timing | The gRPC stream has no SplicePending event, so the splice-out deduction waits for ChannelReady and the wallet shows a stale, higher target until then. (This is an issue, not a PR.) | accepted design. Still post-confirmation on main |
| 163 | LSP: reject concurrent splice-in requests | open | Concurrency | A double-tapped Swap sent two splice-ins. The server accepted both, the channel deadlocked, and it was force-closed in production. | none server-side (client guard only; #164 CU) |
| 162 | Harden/remove the unauthenticated legacy `/api/register-push` | **closed** (1) | Auth / IDOR | Unsigned route mounted outside the auth layer (added by #160), and the newest `last_seen` token wins. Anyone can post a victim's `node_id` and hijack its wake pushes. | **Not fixed on main**: route still at `main.rs:285-291`, handler `register_push.rs:100-135`, `tokens.rs:79-80` picks the newest token |
| 161 | Mobile foreground stability keysends untagged | closed (2) | Signed-message matching | The foreground path used a plain keysend, so the LSP skipped `reconcile_incoming_stability` and fell back to the backstop. | no linked PR. Superseded by #288 |
| 149 | LSP top-ups land in native, not backing | closed (3) | Cross-peer accounting | Wallets left an incoming top-up in native, and the LSP sent no SYNC after a top-up. Books diverged on `backing_sats`. | no linked PR. Reworked by #195/#233/#288/#334 |
| 141 | Label settlements Top-up/Sync in operator GUI | closed (4) | Observability | n/a | #143 M 06-27 |
| 136 | Set `disable_client_reserve=true` | open | Reserve | Would let users spend 100% of their balance, at a theft risk to the LSP. Existing channels keep their reserve. | none (relates #401) |
| 154 | Improve routing (self-hosted gossip/score feed) | open | Feature | n/a | none |
| 283 | Enable on-chain onboarding (LSP splice-in collateral) | open | Feature | n/a | none |
| 358 / 363 / 364 | Disaster recovery: emergency force close, `to_remote` sweep | open (358: 3 comments) | Client state loss / LSP gone | Feature epic. | none |
| 347 | Android corrupt `ldk_node_data.sqlite`: empty ChannelManager, force close ($59) | open (1) | Client state loss | Background gossip strip opens the WAL DB with a second SQLite library. There is no corruption preflight, so the wallet sends a bogus reestablish and the LSP force-closes. Suspected cause; the loss happens at connect time. | none |
| 348 | iOS force-closed 22 min after open; NSE strip runs DELETE + VACUUM ($82) | open (1) | Client state loss | Same end state: the NSE `stripGossipFromDB` runs VACUUM with the system sqlite3, and there is no preflight. | none |
| 337 | Desktop spend guard blocks native payments and shortfalls | open | Guard scope | Raised against the #331 branch. Main's #344 guard (`spend_consumes_lsp_surplus`, `src/stable.rs:479`) only fires for owed-to-LSP overflow and fails open with no price, so the issue may be partly moot. Re-verify. | none |
| 336 | Desktop failed payments block sends/stability after restart | open | Retry / idempotency | The failure handler updates the DB only if the payment is in the in-memory pending list, which is empty after a restart. The DB stays "pending" and the guard blocks forever. Raised against the #331 branch; check whether main inherits it. | none |
| 311 | Android splice-out completes but Stable USD not updated ($5 desync, every trade then `invalid_fee`) | **open** (3) | Capacity change / partial persistence | The splice monitor and the confirmation poller race, and the row is completed without the reconcile. | #314 M 09-12 references it, but the issue is still open (#234 lists it as an open gap) |
| 285 | iOS splice-outs not adjusting USD | open (1) | Event ordering | A deferred SYNC is re-delivered and blocks later splice events. | partly #303/#346/#354 M. Still open |

### 1b. Older or peripheral issues in scope

| # | Title (short) | State | Bug class | Summary | Fix |
|---|---|---|---|---|---|
| 320 | Android incoming stability adds too much to backing | closed | Cross-platform parity | Every received sat went to backing, which created a phantom surplus that was then paid back. | #334 M 09-29 |
| 318 | Android server corrections rejected or overwritten | closed | Persistence race | A correction SYNC went through new-trade capacity checks and got RETRY forever. Splice completion saved an old snapshot over an accepted SYNC. | #333 M 09-29 |
| 319 | Android splice tracking loses background events | closed | Event-ack ordering | The background loop acked `SpliceNegotiated` before saving, and the match rules were inconsistent. | #335 M 09-16 |
| 165 | Desktop negative native under peg | closed | Display | Unclamped split. | #166 M 07-08 |
| 47 | iOS pending-push flag cleared before reconnect outcome | closed | Clear-before-success ordering | Retry state was lost after a failed reconnect. | closed 05-11, no linked PR |
| 45 | `fetch_prices` aborts on first unreachable feed | closed | Price oracle | A `?` on one feed returned the cached $0 price and the UI hung. | #46 M 05-11 |
| 38 | Harden DB migrations in `init_schema` | closed | Persistence (ignored errors) | `let _ = ALTER TABLE` silences IO, lock and disk-full errors. | closed 09-04, no linked PR. Re-check `src/db.rs` |
| 49 | Bidirectional SP positions and funding rate | closed (8) | Design idea | n/a | n/a |
| 263, 264, 279, 316, 302 | Wallet splice/close tracking (splice-out shown failed; close row stuck from hardcoded vout; iOS splice row persisted late; deposit dropped during splice; iOS parity) | closed | Capacity change / persistence (wallet) | Wallet-only. They affect whether wallet books match LSP books. | #265, #266, #280, #325, #303 M |

---

## 2. Pull requests

### 2a. PRs with LSP-side or LSP-accounting effect

| # | Title (short) | Status | LSP-side change (1 line) | Related |
|---|---|---|---|---|
| 415 | do not deduct a payment that is still pending | **O** (stacked on #400; own commit `fd4c873`) | Backstop adds per-channel held HTLC sats from `GetBalances` (`MaybePreimageClaimableHTLC`, `inbound_htlc_rounded_msat`) before counting a gap as a spend; SYNC waits while the books are above live | fixes #324 |
| 400 | book a stability top-up when it is claimed | **O** | Top-up recorded "in flight" at send, booked once on claim (one DB step), node polled for missed outcomes; no 2nd top-up while one is in flight; pay-back offset; operator `ReleaseStabilityPayment` (14-day rule); alarms; schema adds `top_up_sats`, `top_up_offset_sats`, outcome `released` | fixes #392; spawned #411, #413, #414, #416 |
| 402 | deduct only a payment's own amount from backing | M 10-06 | `reconcile_forwarded` and `backing_after_user_to_lsp_stability` cut backing by the payment's own amount (+1 sat), not down to the live balance; `save_channel_after_spend` shifts the pending top-up rollback snapshot; SYNC held while backing > live | #392, #324 |
| 395 | stop deducting stable balance on a capacity drop alone | CU 10-06 | Would have removed the backstop deduction and deferred settlement while balance < backing | #392, #324 |
| 378 | Dioxus GUI rewrite / owner dashboard | M 10-02 | GUI rewrite; daemon: all-channel ledger query, revenue engine + `RefundTradeFee`, GeoIP peer location, "stop retrying refused syncs every minute" | n/a |
| 376 | settle offline users after wake notifications | M 10-07 | Wake watcher polls `list_channels` 1/s for up to 60s after an accepted push; peer-scoped settlement pass with no backstop; push cooldown starts only on provider accept, 300s failure backoff, 20s timeout, reservation | raises #392 exposure |
| 349 | move to latest LDK Server, log on-chain channel txs | M 09-30 | LDK Server `bd95e187`; durable backfill pagination, stream-health checkpoints; audit only | n/a |
| 345 | splices, closes, payment-failure reasons in ledger | M 09-29 | LDK Server client `a56d5a96`; ledger only; daemon refuses to start unless `api_key` is 32 bytes | n/a |
| 344 | guard spends against unsettled LSP surplus | M 09-23 | LSP no longer skips $0-target channels that still have backing; desktop and Android spend guard | fixes #322; spawned #401 |
| 332 | retry ordinary SYNC after delivery failure | M 09-23 | Tracks SYNC payment outcome, retries with the latest saved books (60s to 1h backoff, 10 attempts), recovers outcomes after restart | fixes #323 |
| 331 | preserve unpaid LSP settlement when stable USD clears | CU 09-23 | Superseded by #344; its crash-recovery parts were dropped | #322, #336, #337 |
| 326 | LSP balance-save ordering | M 09-23 | Save, then update memory and send SYNC; a failed save is kept and retried; later events' bookkeeping queues behind it | fixes #321 |
| 288 | sign and verify stability settlements on all clients | M 09-09 | All clients send `STABILITY_PAYMENT_V1` (TLV 13377333); LSP still accepts the legacy `[1]` | fixes #270 |
| 238 | durable signed trade rejections (`TRADE_REJECTED_V1`) | M 08-14 | Signed accept/reject, persisted decisions, response retry, dedupe | #205 |
| 240 | durable two-phase trade confirmation | CU 08-18 | Would add a 1-msat proposal and a 60s reservation before the fee | fee-first risk remains |
| 233 | authenticate and amount-bind stability settlements | M 08-12 | Verify signature, amount, channel, expiry and replay, then apply atomically; fixed a channel-ID representation bug that left settlements pending forever | follow-up to #231 |
| 231 | settle stability payments by amount paid | M 08-11 | `reconcile_incoming_stability` lowers backing by the amount received (floored at equilibrium) instead of resetting to equilibrium; closed the 1-sat debt-erasure exploit | #232 |
| 230 | harden REST + keysend against unauthenticated DoS | M 08-12 | 1 MiB body cap before HMAC + `DefaultBodyLimit`; 20/s stable-TLV fixed-window limit; verify-then-write; 50 MiB audit rotation. **Note:** no TLV rate limiter is found on main (see section 6) | n/a |
| 225 | preserve stability drift across trades | M 08-11 | Trades apply only the `expected_usd` delta at local price; reject unsafe reductions; settlement required before a full exit | #204 |
| 221 | derive trade backing locally on both sides | M 08-08 | Peer-supplied `backing_sats` ignored; each side derives backing at its own price | n/a |
| 217 | signed trade rejections, correlation, expiry | CU 08-08 | Superseded by #238 | #205 |
| 200 | channel ledger | M 07-28 | Append-only Channel Ledger in SQLite, JSONL export | n/a |
| 195 | fix accounting and signed LSP synchronization | M 07-27 | Signed allocations + versioned `SYNC_V1`; atomic, idempotent reconcile; balance excludes commitment fees; fixed splice-in double count and splice-out double deduction; replay protection and channel binding | #204 |
| 175 | bound HTTP client timeouts | M 07-15 | FCM push 10s/20s, REST client 10s/60s (a hung feed had frozen the wallet's stability tick) | n/a |
| 160 | LSP consolidation | M 07-06 | Imported "old LSP" code, including the unauthenticated legacy `/api/register-push` and incoming-stability reconcile | #162, #161 |
| 155 | robust, searchable audit log | M 07-06 | Full audit coverage, atomic audit lines; cold-start reconcile waits for first price, and the tick rebuilds an empty list | precursor of #413 |
| 145 | reject stale signed TRADE_V1 (replay) | M 07-02 | `ts` added, 300s window; `ts==0` still accepted for legacy wallets | #235, #284 |
| 143 | label settlement payments in operator GUI | M 06-27 | `settlement_payments` table + `ListSettlementPayments` | #141 |
| 135 | stable-book sync fix + GUI revamp | M 06-19 | Wallet pushes `expected_usd` over a signed TLV; LSP tick gains the balance-truth **backstop** (two-tick debounce) | root of #324, #392 |
| 108 | TLV trade/sync protocol, splice reconcile, RegisterPush hardening | M 06-04 | TRADE_V1/SYNC_V1 over 1-msat keysend TLV 13377331; splice-out reconcile at post-splice ChannelReady; forwarded reconcile; signed `REGISTER_PUSH_V1` (300s) | #187, #162 |
| 101 | SC REST handlers, push, stability brain | M 06-02 | EditStableChannel, RegisterPush, APNs/FCM; SubscribeEvents consumer; 60s tick (top-up or push wake); startup hydration | n/a |
| 87 | vanilla LDK Server REST proxy routes | M 06-01 | 26 proxy routes; constant-time HMAC compare | n/a |
| 81 | two-daemon architecture | M 05-27 | Vanilla LDK Server + `stable-channels-lsp` over gRPC | n/a |
| 23 | Splicing | M 2026-02-26 | Splice in/out; `user_channel_id` as identity; LSP sends signed SYNC_V1 after deductions | n/a |
| 27 | reconcile_incoming does not reset backing | CU | Shared and iOS; never landed | n/a |
| 14 / 13 / 12 | Sc lsp / daemon-dashboard split (early) | CU | Pre-architecture | n/a |
| 34 | configurable data dir + JSON audit | CU | n/a | n/a |
| 192 | Umbrel LSP package | CU 08-03 (Umbrel packaging later in #210, M 08-02; link not verified) | Packaging | n/a |

### 2b. Wallet PRs that change what the LSP books or receives

| # | Title (short) | Status | Effect | Related |
|---|---|---|---|---|
| 346 | iOS sync parity / harden stability accounting | M 09-26 | iOS spend guard, residual backing at $0, settlement capped at outbound capacity | #322, #344 |
| 335 | Android recover and complete splices | M 09-16 | Save before ack; exact txid match | #319 |
| 334 | Android keep excess incoming stability sats native | M 09-29 | Matches Rust `backing_after_lsp_to_user_stability` | #320 |
| 333 | Android apply and preserve server corrections | M 09-29 | Correction SYNC no longer rejected or overwritten | #318 |
| 314 | Android splice deduction can't be lost; reconcile idempotent | M 09-12 | Backing pinned to the live balance (matches LSP); SYNC keyed on `channel_id` | #311 |
| 303 | iOS splice and trade sync parity | M 09-11 | Bounded SYNC retry; `channel_id` lookup | #302, #268 |
| 299 | Android backing/USD desync on ordinary sends | M 09-11 | Whole reconcile in one transaction | #296 |
| 297 | Android payment errors | M 09-11 | Handles all 9 rejection codes; keeps unknown outcomes unresolved | #291 |
| 268 | Android key sync lookup on `channel_id` | CU | Folded into #266 and #314 | n/a |
| 266 | Android close rows stuck; bounded SYNC retry | M 09-08 | Un-acked SYNC no longer wedges the LDK event queue | #264 |
| 248 | harden Android and iOS trade-result accounting | M 09-01 | Correlated TRADE_V1 (`trade_id`), persist before fee, apply only signed ordered results | #204 |
| 243 | prevent wallet payments before initial chain sync | M 08-24 | A stale block height made HTLCs look expired, causing a force close | n/a |
| 201 / 207 | custom LSP config (Android/iOS) | M 08-03 / 07-30 | Wallet only | #206 |
| 134 / 147 | Android background reliability / TLV classification | CU | n/a | n/a |
| 116 / 76 / 74 / 71 | Android TLV marker, staleness, misc | M | `[1]` marker on background sends | n/a |

The other 74 "LSP" hits have no LSP-side effect: 399, 396, 385, 269, 237 (O), 242, 239, 216, 191, 188, 198, 176, 169, 157, 148, 129, 66, 33, 30, 17, 10.

---

## 3. Recurring bug classes

| Class | Times seen | Examples | What it says about the design |
|---|---|---|---|
| **A. Pending vs settled: live capacity treated as truth** | 6 issues, 5 PRs (still open) | #324, #392, #414, #401; #135 (introduced the backstop), #395 (CU), #402, #400, #415; wallet analogues #296 and #314 item 2 | The books are updated from events, plus a heuristic backstop that reads LDK's inbound capacity. That capacity leaves out in-flight HTLCs and includes the reserve, and the backstop makes a *permanent* `expected_usd` cut after two ticks. Nothing models "pending", so every new flow (user send, LSP top-up, pay-back, splice) finds the gap again. #415 narrows the backstop but does not cover the forwarded-payment or splice-confirm handlers. |
| **B. Send-before-record / optimistic booking** | 6 | #392 (top-up booked at send), #411 (send then record; in-memory cooldown only), #323 (send accepted counted as delivered), desktop stability send crash window (#234), #240 (fee paid before acceptance; CU), #47 | Keysends are side effects with no durable intent log. Mobile moved to claim-before-send; the LSP and desktop have not. The same shape covers SYNC, correction SYNC and the trade-fee refund. |
| **C. Persistence ordering / partial persistence** | 8 | #321, #296, #311, #318, #319, #38, #416; #155 (audit write atomicity); #299 needed 4 review rounds | The in-memory manager list is the working state and the DB trails it. Fixes keep moving to "compute, commit, then publish" (#326) one path at a time. Fail-closed reads are all-or-nothing (#416). |
| **D. Startup and event ordering** | 7 | #413, #155 (cold price leaves list empty), #243, #266/#268 (an un-acked event wedges the LDK queue), #285, #319, #47 | The event subscription starts before hydration, which needs a price. Handlers assume the list is loaded. Acks are tied to processing, so a failure either blocks everything or loses the event. |
| **E. Accounting on capacity changes (splice, close, reserve)** | 12 | #23/#195 (splice double count), #187, #311, #285, #279, #316, #263, #322, #372, #401, #413 (splice ChannelReady), #163, #136 | A splice reaches the daemon only as a re-fired ChannelReady (the gRPC stream has no splice events). Close, reserve and splice each change the balance basis that backing is measured against, and each was patched separately. |
| **F. Signed-message matching / identity / attribution** | 11 | #231 (exploit), #161, #233 (channel-ID representation), #314/#268 (`user_channel_id` vs `channel_id`), #414, #246, #204, #232, #145, #221, #296 (divergence only logged) | Several channel identifiers (`channel_id` changes on splice; `user_channel_id` is node-local and differs per side). Legacy unsigned paths are attributed by balance-drop heuristics. Signed payloads carry `expected_usd`, but the receiver treats a mismatch as telemetry only. |
| **G. Retry / idempotency / backoff** | 10 | #323, #205, #266, #336, #401 (no backoff on failed settle), #376 (push backoff), #314 (non-idempotent reconcile), #402 (repeat event), #378 (refused syncs), live daemon SYNC flood (~2,300 per channel per day per #234) | Each message type grew its own retry machinery (trade response retry, SYNC retry, push backoff, mobile `SyncRetryTracker`). The failure side often has no cooldown (#400 round 2 notes this on main). |
| **H. Auth / DoS on unauthenticated surfaces** | 8 | #108 (RegisterPush IDOR), #160 to #162 (legacy unsigned route; still mounted), #87 (HMAC timing), #230 (OOM, keysend amplification, disk fill), #145 (replay), #231 (unsigned marker), #175 (no HTTP timeouts), unauthenticated `/api/channel-exists` | Compatibility shims reopen holes that earlier PRs closed. The keysend TLV surface is open to any Lightning peer, and each message can trigger gRPC calls under the manager lock. |
| **I. Client state loss leading to LSP force close** | 5 | #347, #348, #243, #134 (CU), #358/#363/#364 | Not an LSP bug, but the LSP sees it: wallets touch LDK's DB with foreign SQLite handles and have no corruption preflight. |
| **J. Cross-implementation divergence** | 8 or more | #149, #165, #296, #318, #320, #246, #303/#346 parity ports, #337 vs Android | The same allocation math is written 5 or more times (LSP Rust, desktop Rust, Kotlin app + background service, Swift app + NSE). Each fix needs N ports, and the books drift until they land. |

---

## 4. Spec summary: invariants for the audit

From #234 (revision 2026-10-06, verified against `adaae4ba`), `stability_logic.md`, and `src/constants.rs` on main.

**Core rule.** Lightning finalizes the *payment*. A *signed application message* authorizes the *accounting instruction* that payment carries. Each peer keeps its own allocation at its own independently admitted price, and peer `backing_sats` is validation data only (#221).

**Invariants.**
1. **Trade.** Before paying the non-refundable 1% fee, the wallet validates capacity, the 99% cap (`MAX_STABLE_ALLOCATION_PERCENT`) and a drift-preserving allocation. It then persists the intent and signs a `TRADE_V1` bound to channel, target, quote, `ts` and a random `trade_id`. The LSP verifies signature, channel, quote (±0.5%), freshness, fee and capacity, and returns exactly **one** durable signed result: a correlated `SYNC_V1` (accept) or `TRADE_REJECTED_V1` (closed reason enum). It retries that result for up to 14 days (`TRADE_RESPONSE_RETRY_WINDOW_SECS`). The wallet's local timeout is 15 min; late signed results still resolve. A rejection does not refund the fee.
2. **Drift is preserved across trades.** Only the target delta converts at the current price, and the LSP rejects deltas that cannot preserve drift (#225).
3. **Stability trigger.** Drift must be **at least $0.25 AND at least 0.1%** (`STABILITY_THRESHOLD_USD`, `STABILITY_THRESHOLD_PERCENT`). Cooldown is 120s. The price must be trusted and the Lightning chain sync no more than 120s old (#243). The LSP pays below-par (`lsp_to_user`) and the user pays above-par.
4. **Signed settlement.** `STABILITY_PAYMENT_V1` (TLV 13377333) binds settlement id, channel, exact amount, direction, `expected_usd`, created time and expiry (14 days, 60s skew). The receiver verifies the signature and the paid amount, rejects replays, and burns the settlement id in the same transaction that credits backing.
5. **A settlement moves backing by its own amount only.** A lower live balance (in-flight HTLC or sibling spend) is left for its own event (#402). It never resets blindly to equilibrium (#231).
6. **What may change `expected_usd`:** an accepted signed trade; the stable-backed overflow of a *completed* send (`STABLE_SPEND_DEDUCTED`, own amount only); a splice-out, proportionally at ChannelReady (`SPLICE_OUT_STABLE_DEDUCTED`); a correction SYNC; an operator edit. The two-tick backstop (`BACKSTOP_STABLE_DEDUCTED`) is the safety net for missed events. Per #324/#392, it must **not** fire on a pending payment.
7. **Save before publish.** The LSP commits the books to the DB before updating memory and before sending SYNC (#326, main only).
8. **SYNC delivery** is tracked and retried with backoff (60s to 1h, 10 attempts), then stops (#332).
9. **Spend guard.** Wallets refuse a spend that would consume an unsettled LSP surplus. A $0 target with leftover backing keeps settling (#344).
10. **Top-up booking.** On main, a top-up is booked **at send** with a rollback row. `stability_logic.md` says "no optimistic reset: `backing_sats` is never reset until the actual channel balance confirms the payment landed". Main violates that rule, and #400 restores it (book at claim).
11. **Onboarding.** LSPS2 JIT channel, zero opening fee; a new stable channel starts at `expected_usd = 0`. Channel close clears active stable state.
12. **Legacy.** The LSP still accepts the unsigned `[1]` marker from pre-#288 clients, credited by amount with unique balance-drop attribution, and still accepts `ts==0` TRADE_V1. Removal is tracked in #235/#284.

**Where the docs disagree with code.**
- `stability_logic.md` gives the threshold as "0.1% or $0.10". The code uses $0.25 and 0.1%, both required.
- It says "`backing_sats` is not touched on the receiver side". It now is: the LSP lowers backing by the amount received, and wallets add only the shortfall (#320/#334).
- It says `expected_usd` "only changes on trades". It also changes on overflow sends, splice-outs, correction SYNCs and the backstop.
- #234 says #373 gives the LSP a startup DB-vs-node reconcile. #373 is wallet-only. The LSP has its own `CHANNEL_MARKED_CLOSED_AT_STARTUP` path from #155.
- **Production is not main.** The live front daemon runs `sc/front-daemon-ldk-server-3e9a07d6` @ `3c4d5e9c` (ldk-server `3e9a07d6`, LDK 0.3-rc2). It lacks #326, #332, #344, #345, #349 and #378. It still floods `SYNC_MESSAGE_FAILED` (about 2,300 per channel per day). An audit of main is not an audit of what runs in production.

**Remaining gaps listed in #234:** legacy marker removal; backstop vs in-flight HTLCs; top-up booked at send; reserve counted as backing; settlement re-anchor (#232); desktop guards (#336/#337); mobile splice-out USD (#285/#311); offline users (#376, now merged); desktop stability send crash window; independent rounding (no canonical signed post-state); non-refundable trade fee; LSP splice-in concurrency (#163).

---

## 5. Open items: LSP-relevant open issues vs open PRs #400 / #415

| Issue | Addressed by #400? | Addressed by #415? | Notes |
|---|---|---|---|
| #392 unclaimed top-up deducted | **Yes** (fixes) | complements | Residual: if the wallet claims and the connection drops before the balance shows the claim, the target can still be cut. Top-ups sent by the old daemon keep the old behaviour. |
| #324 pending payment deducted | No | **Yes** (fixes) | Residuals: the forwarded-payment and splice-confirm handlers still use the live balance; a `GetBalances` failure on 2 ticks in a row falls back to main's behaviour; an offered-but-uncommitted HTLC held over a minute is still deducted; not run on real nodes. |
| #411 send-before-record | No | No | #400 keeps send then record and sets the in-memory cooldown only when the record fails. |
| #413 channel-ready before list load resets to $0 | No | No | Neither PR touches `handle_channel_ready`. |
| #414 unsigned pay-back after claim | No (issue says same result on #400) | No (no HTLC is pending in this scenario) | Fix: refresh `stable_receiver_btc` on claim, or remove the legacy path. |
| #416 one bad in-flight row pauses all top-ups | Introduced by #400 | No | Must be fixed before or together with #400. |
| #401 reserve counted as backing | No | No (#415 only stops held HTLCs from being deducted on reserve channels) | Also needs a failure-side backoff for settlements. |
| #412 atomics nit | No | No | n/a |
| #232 settlement re-anchor | No | No | `.max(equilibrium)` still at `src/stable.rs:785`. |
| #235 / #284 legacy removal | No | No | `ts==0` and the `[1]` marker are still accepted. |
| #163 concurrent splice-in | No | No | No server mutex. |
| #136 `disable_client_reserve` | No | No | Tied to #401. |
| #234 spec | Changes the "booked at send" row | Changes the backstop row | The spec needs updating if they merge. |
| #311 / #285 mobile splice-out USD | No | No | Wallet-side. |
| #336 / #337 desktop guards | No | No | Raised against the #331 branch; may be partly moot on main. |
| #347 / #348 client DB corruption | No | No | Wallet-side. |
| #154, #283, #358/#363/#364, #391 | No | No | Features. |

---

## 6. Review comments of note: design concerns not yet fixed

1. **Signed settlement divergence is only logged.** #299 asked the LSP to reject a `STABILITY_PAYMENT_V1` whose signed `expected_usd` diverges from the LSP's beyond a tolerance, so the HTLC fails and the sats go back. Main still logs `STABILITY_PAYMENT_STATE_DIVERGENCE` and applies it (`stable_manager.rs` ~L1762-1777, comment: "not a reason to discard"). In #296 the LSP accepted an $8.53 overpayment at 16% divergence.
2. **#162 closed, hole still open.** The unauthenticated `/api/register-push` is still mounted outside the auth layer (`main.rs:285-291`), with no node-ownership proof. The newest `last_seen` wins (`push/tokens.rs:79-80`), so wake-push hijack remains possible. `/api/channel-exists` is also unauthenticated and runs `list_channels` per call.
3. **#230's keysend rate limit not found on main.** The PR describes a 20/s fixed-window limit on stable-TLV processing before any gRPC, DB or audit work. On main, no limiter appears in the daemon; the `handle_payment_received` TLV path goes straight to parse, signature verify and DB. The body cap, verify-then-write and 50 MiB audit rotation are present. This needs confirming in Phase 3F; the shallow clone cannot show whether it was removed or never landed.
4. **#400 deploy hazards.** Deploy only when no top-up is pending. Do not roll back to an older daemon while one is in flight ("would mark the top-up settled without counting it and pay again"). The 14-day release is measured by clock, not blocks. A wrong operator answer either cuts the target or pays twice. After a release the node is never asked again.
5. **Failed settlements have no cooldown.** #400 round 2 confirms main retries a failed top-up on every tick, and a failure-side cooldown "is left for its own issue". No such issue was found. Together with #401's unpayable surplus, this makes a retry or wake-push loop.
6. **#402 note.** The snapshot-shift SQL relies on exact f64 equality of `expected_usd` between `settlement_payments` and `channels`.
7. **#395 known limits that still apply.** The reconnect backfill is audit-only, so a `PaymentForwarded` missed while the stream was down is caught only by the backstop. A splice-out whose completion event was missed has the same problem. There is no alert for a channel stuck in a deferred state.
8. **#376 is untested end to end.** The watcher's async loop and the reconnect settlement were never exercised end to end or on a device. It also raises #392 exposure (two-tick tolerance drops to about 61s).
9. **#415 limits.** The forwarded and splice-confirm handlers still read the live balance. One more `GetBalances` per tick and wake pass. The report's shape was checked only against LDK source.
10. **Legacy replay window.** `ts==0` TRADE_V1 is accepted with no freshness check (`stable_manager.rs:3412-3414`). Removing it waits on #235/#284.
11. **#234 open design gaps.** The desktop stability send has a crash window (send before intent). There is no canonical signed post-state, so peers can disagree by a sat. The trade fee is non-refundable on rejection (only a manual `RefundTradeFee` from #378). Splice accounting happens only after confirmation (#187).
12. **#108 to #160 regression pattern.** A hardening PR (#108 signed RegisterPush) was reopened by a later compatibility shim (#160) and tracked as "closed" (#162) while the code remains. Expect the same risk with the legacy `[1]` marker and `ts==0` paths.
