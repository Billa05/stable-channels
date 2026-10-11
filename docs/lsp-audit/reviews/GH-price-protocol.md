# Review G + H: price oracle and wallet/LSP wire protocol

Base: `main` @ 9aff5ec (read-only; nothing built or run). Upstream issues read: #232, #234 (behavior write-up), #235, #284, #161, #45, #414; PRs #145, #195, #233, #239.

All line numbers below are on `main`. "LSP" = `server/stable-channels-lsp`. `stable.rs`, `price_feeds.rs`, `constants.rs` = root crate `src/`.

---

## 1. What is done right

- **Consensus price, not a single feed.** Six direct BTC/USD feeds are fetched in parallel. Values outside $1k to $10M are dropped. The initial median is taken, feeds more than 5% from it are dropped, at least 3 must agree, and the price is the median of the agreeing feeds (`price_feeds.rs:13-21, 214-231, 251-288`). One bad feed cannot move the price beyond the spread of the honest feeds.
- **Per-feed failure is isolated, with bounded timeouts** (`price_feeds.rs:365-449`; `constants.rs:63-73`). This fixes #45.
- **USDT fallback is guarded.** It runs only on a *transient* direct failure, so a large-move quarantine cannot be routed around through the fallback (`price_feeds.rs:457-467`). The peg is anchored on exchange feeds, and aggregators can confirm it but never move it (`price_feeds.rs:290-352`).
- **Accounting never uses a stale or quarantined price.** `get_fresh_cached_price_no_fetch` returns 0 if the price is more than 60 s old or quarantined (`price_feeds.rs:132-138, 240-249`). Every LSP money path reads it: tick `stability_tick.rs:42`, wake pass `stability_tick.rs:292`, events `event_loop.rs:305, 389`, `stable_manager.rs:376`, handlers `handlers/price.rs:15`, `handlers/stable_channels.rs:46, 81`. The tick skips when the price is cold (`stability_tick.rs:43-46`). `price_task` publishes only newly validated prices (`price_task.rs:12-34`), and a refresh that is in flight does not blank the last consensus (`price_feeds.rs:89-98`).
- **Large-move breaker.** A move of more than 10% from the last trusted price quarantines accounting until a new consensus is reached (`price_feeds.rs:100-119, 276-285`).
- **All wallets use the same oracle**: same feeds, same 3/5%/10%/60 s constants (Android `util/PriceOracle.kt:28-38, 210-245`; iOS NSE `Protocols/PriceFetcher.swift:10-47`). Stability thresholds are identical everywhere (`constants.rs:111-115`; Android `Constants.kt:57-59`).
- **The signature covers the exact transmitted bytes.** The payload travels as a JSON *string* inside `{payload, signature}`, and the receiver verifies those bytes. No canonicalization is needed (`messages.rs:8-13`; LSP verify `stable_manager.rs:3094-3100, 1729-1735`; wallets `TradeProtocol.kt:432-439`, `user.rs:3831-3836`).
- **The signer is bound to the channel peer.** The LSP verifies against `counterparty_node_id` of the channel named in the signed payload (`stable_manager.rs:3098, 1733`). `channel_id` wins over `user_channel_id` (`stable_manager.rs:3074-3084`). Wallets verify LSP messages against the channel counterparty or a pinned LSP key (`AppState.kt:2378`, `StableControlParser.swift:35`, `user.rs:3830-3836`).
- **Verify-then-write for trades**: no settlement row is written before the signature checks out (`stable_manager.rs:3122-3133`). An invalid signature gets no signed response, so the LSP is not a signing oracle for attacker-chosen correlation fields (test `correlated_invalid_signature_receives_no_decision_or_response`).
- **Hardened TRADE_V1**:
  - `trade_id` must be unique (`stable_manager.rs:3200-3221`).
  - The decision is idempotent per inbound payment id (`:3171-3198`).
  - The response is bound to `request_hash = sha256(payload)` (`:3166`).
  - `ts` must be within ±15 min (`:3238-3244`).
  - The quote is only a consent bound: allocation always uses the LSP price (`:3296-3310`).
- **STABILITY_PAYMENT_V1 (#233)**:
  - Binds settlement id, channel, exact amount, direction, target, created and expiry times (`stable.rs:668-680`).
  - The LSP checks direction (`stable_manager.rs:1556`) and amount equality (`:1567`) before anything else.
  - Replay is blocked by a durable registry (`:1579-1609`).
  - Expiry is judged at the durable first-receipt time (`:1631-1653`).
  - A malformed signed record never falls back to the unsigned marker (`:1411-1426`).
  - Wallets mirror all of this (`StabilityPaymentProtocol.kt:154-211`, `TradeProtocol.swift:614-669`, `user.rs:2677-2870`) and dedupe on settlement id (`DatabaseService.kt:1604-1625`).
- **Receivers settle at their own price and cap at their own equilibrium** (`stable.rs:762-838`; `StabilityService.kt:74-95`). This prevents "both sides pay" ping-pong and stops a payment from manufacturing a claim.
- **Size caps before parsing** (`constants.rs:13-15`; `stable_manager.rs:1432-1438, 1507-1520`; Android `TradeProtocol.kt:414`, `StabilityPaymentProtocol.kt:162`; iOS `TradeProtocol.swift:623`). serde_json's recursion limit bounds nesting. No panics found in any TLV parser.
- **Monotone `sync_version`** on all wallets (`DatabaseService.kt:1366-1370, 1404-1409`; `SQLitePaymentDatabase.swift:793-796`). Android applies uncorrelated SYNCs by `channel_id`, not `user_channel_id` (`DatabaseService.kt:1337-1351`).

---

## 2. Message table

All signatures are LDK `sign_message`: zbase32, recoverable, over `"Lightning Signed Message:" ‖ payload`. The payload is the exact UTF-8 JSON string carried in the envelope.

| Message | Direction | TLV (amount) | Signed by → verified against | Fields covered by signature | Replay protection | Legacy fallback |
|---|---|---|---|---|---|---|
| `TRADE_V1` (hardened) | wallet → LSP | 13377331 (fee keysend) | user node key → `counterparty_node_id` of the named channel | `type, channel_id, user_channel_id, trade_id, expected_usd, quote_price, ts`. Not covered: fee amount (recomputed), LSP id, network | `trade_id` unique, inbound payment id idempotent, `ts` ±15 min | **Yes.** Without `trade_id` it takes the legacy path: `ts` is optional (0 means no freshness check), ±300 s otherwise, no nonce, rejections are silent (H-2) |
| `SYNC_V1` (uncorrelated) | LSP → wallet | 13377331 (1 msat) | LSP node key → channel counterparty / pinned LSP key | `type, channel_id, user_channel_id (LSP's id), expected_usd, backing_sats, sync_version` | monotone `sync_version` per channel. No timestamp or expiry | Wallets deliberately ignore the signed `backing_sats` (H-6) |
| `SYNC_V1` (trade acceptance) | LSP → wallet | 13377331 (1 msat) | LSP key | above plus `trade_id, trade_payment_id, request_hash` | correlation lookup plus `sync_version` | none |
| `TRADE_REJECTED_V1` | LSP → wallet | 13377331 (1 msat) | LSP key | `type, channel_id, trade_id, trade_payment_id, request_hash, reason_code, decided_at`. Mobile parsers reject unknown keys | correlation, terminal state idempotent | none |
| `STABILITY_PAYMENT_V1` user→LSP | wallet → LSP | 13377333 (amount = signed amount) | user key → counterparty of named channel | `type, settlement_id, channel_id, amount_msat, direction, expected_usd, created_at, expires_at (≤14 d)` | durable settlement registry, expiry at first receipt | **Yes.** The LSP still accepts the unsigned `[0x01]` marker on 13377331 from any sender (H-3) |
| `STABILITY_PAYMENT_V1` LSP→user | LSP → wallet | 13377333 plus `[0x01]` on 13377331 | LSP key | same, `direction=lsp_to_user` | settlement id seen-table on every wallet | The LSP still attaches the marker. All wallets ignore it (`AppState.kt:2138-2141`, `StableControlParser.swift:30-32`, `user.rs:4121-4135`) |
| `REGISTER_PUSH_V1` | wallet → LSP (HTTP) | n/a | user key → `node_id` in request | `type, node_id, token, ts` ±300 s | `ts` window | **Yes.** Unsigned `/api/register-push` is mounted outside auth, and it is the only path both mobile apps use (H-4) |

Domain separation: the only type tag is the JSON `"type"` field, which each parser checks (`stable_manager.rs:3037`, `stable.rs:735`, `TradeProtocol.kt:427-431`). There is no protocol-specific prefix on top of LDK's generic one (H-7). LSP-signed messages cannot be reflected to the LSP, and user-signed ones cannot be reflected to the user, because each side verifies against the other's key and `direction` is checked.

---

## 3. Findings

### G-1: Half of the responding feeds can shift the LSP price ±5%; a majority can set any price after at most 60 s. No smoothing and no payout budget, so the LSP tops up every stable channel at once
- Severity: Medium
- Confidence: Confirmed (mechanism). Exploitation needs control or correlated failure of about half the feeds.
- Where: `src/price_feeds.rs:214-231, 251-288, 240-243, 452-455`; LSP `stable_manager.rs:2287-2340` (amount = |drift| / price, no cap); `stability_tick.rs:40-57`
- Related: #45, #239
- Status on main: open
- What happens:
  1. **Half the feeds, about ±5%.** The median of an even count is the midpoint of the two middle values (`:222-229`). With 3 honest feeds at P and 3 feeds at P(1−X), X ≤ 9.5%, the initial median is P(1−X/2). All six fall within 5% of it, so all six "agree" and the accepted price is P(1−X/2), down to −4.76%. The same holds for 2 bad out of 4 responding feeds.
  2. **Majority, any price.**
     - With 4 of 6 feeds, the honest ones become the outliers.
     - The 10% breaker compares against the last accepted price, which moves on every accepted 15 s refresh. Steps of 9.9% are therefore accepted, about 46% per 60 s tick.
     - A single larger jump only quarantines the price until the anchor is 60 s old (`trusted_price_reference` returns `None`). The next consensus is then accepted unbounded.
  3. **Payout.** `run_tick_plan` pays `amount_sats = |drift| / price` to every connected stable channel in the same tick (`:2340`). Offline peers get a wake push. Limits are only the per-channel 120 s cooldown and channel capacity. There is no per-tick, per-channel or aggregate cap, and no TWAP or second sample.
  4. **No recovery from Android and desktop wallets.** They cap a top-up at their own (correct-price) equilibrium, so the excess becomes native (`StabilityService.kt:92`, `stable.rs:827`). The LSP books backing at the equilibrium of the wrong price (`stable_manager.rs:2446-2447`), sees a phantom surplus once the price corrects, and waits for a payback that never comes. iOS credits the full amount (`PaymentRepository.swift:584`) and then pays it back at its own price.
- Why it matters: a 4.76% downward shift for one tick pays out about 4.76% of the whole stable book (for example $47.6k on a $1M book), and Android and desktop users keep it. A single wrong feed cannot do this. Correlated "last trade" wicks on 3 of 6 venues, or 2 of 4 when two honest feeds are rate-limited or unreachable from the LSP host, can.
- Fix:
  - Require a strict majority of *configured* feeds (≥4 of 6), not ≥3 of those that responded.
  - Pay only when two consecutive accepted refreshes agree within about 0.5% (or use a short TWAP).
  - Make the breaker cumulative over a window (for example ≤10% per 5 min against a slow anchor), not per refresh. Keep paused when the anchor expires during a quarantine instead of accepting any value.
  - Add an aggregate per-tick top-up budget (sats or % of the stable book) and a per-channel cap (for example ≤10% of `expected_usd` per tick). Above the budget, halt and alert.

### G-2: Desktop wallet books LSP top-ups and SYNCs at a stale or quarantined price, permanently losing the hedge
- Severity: Medium
- Confidence: Confirmed (code path). The trigger is the desktop being stale or quarantined while the LSP is fresh.
- Where: `src/user.rs:1005-1077` (`sc.latest_price` is updated only after a fresh refresh); `src/stable.rs:568-571` (`update_balances` keeps the old value when the fresh price is 0); `src/user.rs:2883-2900` (signed top-up booked with `sc.latest_price`, only checked > 0); `src/user.rs:3998-4007` (SYNC delta at `sc.latest_price`)
- Related: #232, #234
- Status on main: open
- What happens:
  1. Start: E = $1000, B = 1,000,000 sats at $100k. BTC falls 12% within a refresh.
  2. The desktop quarantines, so `get_fresh` returns 0 and `latest_price` stays at $100k.
  3. The LSP's anchor expires first (15 s cadence against the desktop's ~30 s). It accepts $88k and pays 136,363 sats with a signed top-up.
  4. Desktop: `backing_after_lsp_to_user_stability(1,000,000, 1000, 100_000, …)` gives equilibrium 1,000,000, already met. Backing is unchanged, all 136,363 sats go to native, and the settlement is persisted as applied.
  5. Later at $88k the desktop is $120 below par (CHECK_ONLY). The LSP is at par. Nothing ever corrects it.
  6. Any desktop price outage longer than 60 s with a live LN link does the same.
  7. Android does it right: the accounting price is 0, so it throws and the event is redelivered (`AppState.kt:2243`, `StabilityService.kt:84-88`).
- Why it matters: the user's USD position silently drops by the size of the top-up (it becomes floating BTC) in exactly the large-move case the product exists for.
- Fix: at both booking sites use `get_fresh_cached_price_no_fetch()`. If it is 0, set `ack = false` and return `Retry`, as Android does. Never persist a settlement priced from `sc.latest_price`.

### G-3: Trades rejected because of LSP-side price conditions forfeit the 1% fee, and wallets cannot check the LSP price first
- Severity: Low
- Confidence: Confirmed
- Where: `stable_manager.rs:3274-3279` (LSP price cold or quarantined → `InvalidQuote`); `:3291-3295` (`QuoteDeviation` > 0.5%); `/GetPrice` sits behind the HMAC layer (`main.rs:241-244, 281-284`), while mobile wallets only call `/api/register-push` and `/api/channel-exists` (`Constants.kt:28-29`)
- Related: #234 ("non-refundable trade fee"), #205
- Status on main: open (known design choice)
- What happens: the fee keysend settles before validation. During the LSP's ≤60 s quarantine after a >10% move, or when the two independent medians (≤60 s old each) differ by more than 0.5% in a fast market, an honest trade is rejected with a misleading reason ("A valid market quote is required"). The fee is refunded only by the manual `REFUND_TRADE_FEE` operator path.
- Why it matters: users lose 1% on routine volatility for a condition they could not see.
- Fix: auto-refund rejections caused by LSP-side conditions (`InvalidQuote` with a cold LSP price, `QuoteDeviation`, `InternalFailure`). Or expose a signed, unauthenticated price and state endpoint so wallets can pre-check.

### G-4: Price refresh "in progress" flag is not panic-safe, so one panic stops pricing until restart
- Severity: Low
- Confidence: Plausible (unverified: whether stdout or stderr can be a closed pipe in deployment)
- Where: `src/price_feeds.rs:89-98, 154-169` (`updating = true` with no RAII guard); `println!` and `eprintln!` in fetch threads `:374-420, 459, 466, 494`; `price_task.rs:24-33`
- Related: none
- Status on main: open
- What happens: `println!`/`eprintln!` panic on a failed write (for example EPIPE when the log pipe closes). The panic propagates out of `thread::scope` and out of `refresh_cached_price` before `finish_refresh` runs. `price_task` logs the JoinError and keeps looping, but every later call fails with "price refresh already in progress". After 60 s the accounting price is 0 for good: no top-ups, all trades rejected with fees kept (G-3).
- Why it matters: a silent, permanent outage of the money path that only a restart fixes.
- Fix: use a drop guard that clears `updating`, or `catch_unwind` around the fetch. Replace `println!` with `tracing`.

### H-1: The equilibrium floor on user→LSP settlements moves value from user to LSP and leaves a phantom surplus that blocks full exits
- Severity: Medium
- Confidence: Confirmed
- Where: `src/stable.rs:778-793` (`.max(equilibrium)` at `:785`), used by `stable_manager.rs:1794, 1843` (signed) and `:2076` (legacy); wallets debit exactly the amount paid (Android `AppState.kt:3784`, desktop re-anchors at its own equilibrium `stable.rs:1128`); full-exit gate `stable.rs:407-413`, `stable_manager.rs:3310-3324`
- Related: #232 (open, rated "no money lost"), #234
- Status on main: open
- What happens (user holds native N ≥ Δ; wallet price above LSP price at payback, within normal band):
  1. Start: E = $1000, B = 1,000,000 sats, N = 50,000.
  2. Price rises: wallet sees $110,000, LSP sees $109,560 (0.4% lower).
  3. Wallet pays a = 90,909 sats, so B_W = 909,091.
  4. LSP: B − a = 909,091 is below its equilibrium 912,742, and the live clamp is 959,091, so B_L = 912,742. Δ = 3,651.
  5. Price falls back to $100k. The LSP tops up 1000 − 912,742·1.0 → 87,258 sats. Wallet: 909,091 + 87,258 = 996,349, capped at 1,000,000.
  6. Round trip with no net price change: the user paid 90,909 and got 87,258 back, about −$3.65 (≈ the price gap × E). Without the floor it is neutral.
  7. The gap never decreases (it ratchets to the largest disagreement seen). The wallet sits below par in its own view and cannot pull.
  8. While the gap is shown as an above-par surplus on the LSP (≥ $0.25 and ≥ 0.1% at a common price), a full-exit trade is rejected with `SettlementRequired` (`stable.rs:407-413`). The wallet sees nothing to settle, and each attempt forfeits the fee.
- Why it matters: a small but systematic transfer to the LSP, a permanent hedge shortfall, and a possible exit deadlock. This is real money, not only a ledger mismatch as #232 states.
- Fix: as #232 proposes, make the result amount-faithful (`current.saturating_sub(amount)` plus the live clamp). Longer term, sign the payer's price or post-state in `STABILITY_PAYMENT_V1` and adopt it within a tolerance.

### H-2: Legacy TRADE_V1 path is still accepted. `ts == 0` disables freshness and there is no nonce, so historical envelopes are replayable forever from any node
- Severity: Medium
- Confidence: Plausible (unverified: where pre-#145 envelopes are retained, for example LDK payment store, backups, logs)
- Where: `stable_manager.rs:3137` (`trade_id` is opt-in), `:3411-3426` (`ts == 0` accepted, otherwise 300 s), `:3427-3609` (no request-hash or nonce dedupe); `messages.rs:31-33` (`ts` defaults to 0). Every current client sends `trade_id` (`user.rs:2548-2556`, `TradeProtocol.kt:253-264`, `TradeProtocol.swift:~300-307`).
- Related: #145 (planned to "flip the gate to reject ts == 0 after wallet rollout"; not done), #235, #284
- Status on main: open
- What happens:
  1. Anyone holding an old signed legacy envelope keysends it from any node with the recomputed fee. The LSP does not and cannot bind the payer.
  2. The signature verifies against the named channel's counterparty, `fee_matches` passes, and the LSP resets the victim's `expected_usd` to the old target.
  3. It saves (`:3604`) and sends an uncorrelated SYNC that the victim's wallet applies.
  4. Envelopes with `ts ≠ 0` can be replayed within 300 s.
- Why it matters: an unauthorized change to a user's hedge (for example $5000 → $50), costing the attacker only 1% of the delta. Removing `trade_id` cannot downgrade a current client's envelope, because `trade_id` is signed. The risk is entirely historical envelopes.
- Fix: reject `TRADE_V1` without `trade_id`, which deletes the legacy branch (#284). At minimum, reject `ts == 0`.

### H-3: LSP still accepts the unsigned `[0x01]` stability marker from any sender, attributed by balance drop
- Severity: Low (Medium if any pre-#288 install remains)
- Confidence: Confirmed
- Where: `stable_manager.rs:1465-1480` (accept), `:2002-2125` (attribution by `sc.stable_receiver_btc − live` ±1 sat, `:2044`); `:2427-2437` (LSP still *sends* the marker, which no wallet reads)
- Related: #161, #235, #284, #414
- Status on main: open
- What happens:
  - **Honest legacy wallet:** #414. A payback inside the same tick as a top-up claim goes unattributed, then the backstop cuts the user's target.
  - **Outsider:** while a victim has an unreconciled spend or in-flight HTLC of N sats, anyone can keysend N sats with `[0x01]`.
    1. The LSP attributes it to the victim and drops backing to live without cutting `expected_usd` (`stable.rs:787-793`).
    2. It clears the backstop debounce.
    3. On the next tick it tops the victim up.
    4. The attacker pays N, so there is no drain, but the books are wrong: the LSP stays over-hedged on that spend.
- Why it matters: an unauthenticated input still mutates accounting, although no current client needs it.
- Fix: remove marker acceptance and stop sending the marker (#284).

### H-4: Unsigned push registration is the only path deployed wallets use. Last write wins, so anyone can redirect a user's wake pushes
- Severity: Medium (overlaps area F)
- Confidence: Confirmed
- Where: `main.rs:285-291` (route mounted after the auth layer); `handlers/register_push.rs:100-125` (no proof of `node_id`); `push/tokens.rs:56-61, 77-81` (`INSERT OR REPLACE`, newest `last_seen` per `node_id` wins); Android `Constants.kt:28`, iOS `Constants.swift:33` both call `/api/register-push`
- Related: #235
- Status on main: open
- What happens: POST `{"device_token":"<attacker>","platform":"android","node_id":"<victim>"}`. The victim's node id is the payee of every invoice they issue. Every later `lsp_to_user` or `user_to_lsp` wake goes to the attacker until the victim re-registers, and the attacker can repeat on a timer.
- Why it matters: an offline victim is never woken. LSP top-ups wait, leaving the user unhedged, and paybacks owed to the LSP are not triggered. The signed `RegisterPush` (`register_push.rs:19-80`) exists but no wallet uses it.
- Fix: ship signed registration in both apps, then delete the legacy route. Until then, do not let an unsigned registration replace a token that was registered with a signature, and rate-limit by node.

### H-5: iOS NSE applies uncorrelated SYNC by the LSP's `user_channel_id`, which never matches a JIT channel, and the deferral blocks background event processing
- Severity: Low
- Confidence: Confirmed
- Where: `ios/.../NotificationService/Services/SQLitePaymentDatabase.swift:770-783` (`WHERE user_channel_id = ?`, no row gives `.retry`); the LSP signs its own id (`stable_manager.rs:2640-2646`); foreground iOS and Android key by `channel_id` (`ChannelRepository.swift:722`, `DatabaseService.kt:1337-1351`); `StableControlParser.swift:53-55` → `IncomingPaymentHandler.swift:51-54`, `LSPToUserHandler.swift:50-53` (`break eventLoop` without ack)
- Related: #303 (fixed this in the foreground only)
- Status on main: open
- What happens: every uncorrelated SYNC that reaches the NSE is deferred. Because it is never acked, it stays at the head of the LDK queue, so each NSE run stops on it and never books a following top-up or payment. Everything waits until the app is opened.
- Why it matters: background wakes stop doing their job for that user, and books are stale while backgrounded.
- Fix: look up by `channel_id` in the NSE, matching #303. On `.retry`, consider continuing past the control event instead of breaking the loop.

### H-6: `backing_sats` has no authoritative side, and divergence is only logged
- Severity: Low
- Confidence: Confirmed
- Where: trades book at each side's own price (LSP `stable_manager.rs:3296-3310`; Android uses the stored wallet allocation `DatabaseService.kt:1152, 1207`, ignoring `sync.backingSats`). Top-up receipt rules differ by platform: Android and desktop cap at their own equilibrium (`StabilityService.kt:92`, `stable.rs:827`), while iOS credits the full amount (`PaymentRepository.swift:584`, `SQLitePaymentDatabase.swift:447`). Detection is telemetry only (`STABILITY_PAYMENT_STATE_DIVERGENCE` `stable_manager.rs:1762-1776`, `allocation_diverged` `AppState.kt:2548`). No periodic resync.
- Related: #232, #234 ("peer backing_sats is validation data")
- Status on main: open (by design)
- What happens:
  - **LSP price 0.4% lower at a $1000 sell.** LSP books 1,004,016 sats and the wallet books 1,000,000. At a common $95k the LSP tops up $46.18 while the wallet expected $50.00, so the wallet is permanently $3.82 below par.
  - **LSP price higher.** The LSP overpays. Android and desktop keep the excess as native; iOS returns it.
  - **Spends.** When wallet backing is above LSP backing, a spend the wallet treats as overflow can be one the LSP treats as native. No SYNC is sent, so `expected_usd` diverges too.
  - **Exit.** An LSP-side phantom surplus also triggers the `SettlementRequired` exit block described in H-1.
- Why it matters: errors stay small (bounded by about 0.5% of each trade or settlement), never heal, depend on the platform, and are invisible to the user.
- Fix: let SYNC carry the LSP's backing as authoritative, or periodically send a signed `{expected_usd, backing_sats}` snapshot that wallets adopt within a tolerance. Unify the receipt rule across platforms. Alert on persistent divergence.

### H-7: The LSP node key doubles as a general signing oracle over REST, and protocol messages carry no domain tag beyond JSON `type`
- Severity: Low
- Confidence: Confirmed
- Where: `main.rs:237` → `handlers/tools.rs:14-23` (`/SignMessage` signs arbitrary bytes with the same LDK Server key used for SYNC, rejections and top-ups, see `stable_manager.rs:245-249, 2648, 2373`)
- Related: server README auth model ("a leaked SC api_key … access only to the SC daemon")
- Status on main: open
- What happens: any holder of the SC api key (operator GUI host, a leaked key) can mint valid `SYNC_V1`, `TRADE_REJECTED_V1` and `STABILITY_PAYMENT_V1(lsp_to_user)` for any user. For example, a SYNC with `sync_version = 2^63−1` makes every wallet drop all later uncorrelated SYNCs as duplicates (`DatabaseService.kt:1369`). Social-engineering the operator into "signing a message" has the same effect.
- Why it matters: a compromised operator credential extends to corrupting users' books, not only LSP funds.
- Fix: refuse to sign payloads that parse as JSON with a protocol `type` (or remove the route). Prefix protocol payloads with a fixed domain string such as `"stable-channels/v1/<type>\n"`. Bound `sync_version` jumps on wallets.

---

## 4. Design observations and answers

- **Q1, price and drain:**
  - The price is the median of ≥3 agreeing feeds within 5%, falling back to a peg-normalized USDT median. Freshness is ≤60 s, with a per-refresh 10% breaker.
  - One wild feed is rejected. One mildly wrong feed only biases the price within the honest spread.
  - If all feeds fail, the price goes cold after 60 s: no top-ups, trades rejected (G-3), signed receipts queued and retried, legacy-marker receipts left unattributed.
  - The LSP never pays on a stale cached price.
  - There is no TWAP, no cumulative rate limit and no payout budget (G-1).
  - "Both pay" needs more than about 0.2% price disagreement. It self-heals because each receiver caps at its own equilibrium. "Neither pays" is benign.
- **Stale-price option for traders:** allocation uses the LSP price, and the 0.5% quote check is only consent, so a user can choose `quote = LSP price`. After a move during the LSP's refresh lag, a sell locks in the stale price and the next tick tops up the difference. The 1% fee exceeds typical lag error. Worth keeping the fee above the maximum price age multiplied by realistic volatility.
- **Q2, protocol:**
  - Signatures bind the exact payload bytes and are verified against the right peer key.
  - Cross-type reuse is prevented only by the `type` field (H-7).
  - Cross-channel reuse is prevented by the signed `channel_id`. Inbound payments are not bound to the channel they arrive on, which is harmless for paid settlements.
  - Cross-time reuse is prevented by `trade_id`, `settlement_id` and `sync_version`, except on the legacy trade path (H-2).
  - Legacy fallbacks that remain: unsigned trade path, unsigned stability marker, unsigned push registration (H-2, H-3, H-4).
  - Signed stability TTL is 14 days. Harmless because replay is blocked by settlement id and the amount must actually be paid.
  - Minor: `handle_signed_stability_payment` writes the raw, unauthenticated envelope to the DB before verifying the signature (`stable_manager.rs:1579-1586`). Each row costs at least 1 sat and expires in 14 days.
- **Q3, divergence:**
  - `expected_usd` is LSP-authoritative via SYNC, but SYNC is sent only after LSP-side deductions or trades. There is no periodic resync.
  - `backing_sats` has no authoritative side (H-6). The #232 floor tilts the gap in the LSP's favor (H-1).
  - Platform rules differ: iOS gives full credit, Android and desktop cap at equilibrium, the desktop books at stale prices (G-2), and the iOS NSE keys SYNC by the wrong id (H-5).
  - Detection is audit telemetry only. A single signed snapshot plus one shared booking rule in `stable.rs` would close most of this.
- **sync_version lives in the `channels` row** (`db.rs:1395-1414`). Restoring the LSP DB from backup lowers versions, and wallets then ignore new uncorrelated SYNCs as duplicates until the counter passes the old value. Consider an epoch field.
- **The tick prices every channel with one sample** taken at the start of the tick, while holding the manager lock across per-channel gRPC sign and send calls (`stable_manager.rs:2192-2560`). After a big move, many channels need payments at once, and the last ones are priced with an older sample.
