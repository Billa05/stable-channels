# Review F: API surface, authentication, TLS, push, config and secrets

Base: `main` @ `9aff5ec` (the working branch adds only `docs/lsp-audit/*`). Paths are relative to the repo root; `lsp/` = `server/stable-channels-lsp/src/`.

## Headline answer: can a wallet reach the money-moving routes?

**No. No wallet build holds the SC api_key.**
- **Desktop wallet** (`src/user.rs`, `src/constants.rs`): it never talks to the REST surface. It has no HMAC or `X-Auth` code and no `:3002` URL. It reaches the LSP only over Lightning (keysend plus custom TLVs).
- **Android:** it makes only two HTTP calls to the LSP, both unsigned JSON to `https://stablechannels.com`. One is `POST /api/register-push` (`android/.../util/Constants.kt:28`, `push/FCMService.kt:60-82`). The other is `POST /api/channel-exists` (`Constants.kt:29`, `AppState.kt:4295-4311`). `LspPreferencesManager.kt` stores only a pubkey and an address.
- **iOS:** it uses the same two unsigned URLs (`Utilities/Constants.swift:33-34`, `App/AppState.swift:1591-1621`, `App/StableChannelsApp.swift:151-172`). `LSPConfig.token` (`Domain/LSPConfig.swift:7`) is the LSPS2 JIT token passed to `setLiquiditySourceLsps2` (`Services/NodeService.swift:247-251`), not the REST key.
- The README says wallets are "equal clients of the same REST surface". The code does not match that: the HMAC surface is operator-only in practice.
- **The trap:** the node-signed `/RegisterPush` sits **inside** the HMAC layer (`lsp/main.rs:253-256`, wrapped by the layer at `:281`). A wallet that adopted it as the README describes would have to ship the admin key. That key also authorizes `/OnchainSend`, `/SpontaneousSend`, `/SpliceOut` and `/SignMessage`. This is also why the unsigned legacy route (F-3) has never been retired.

## 1. What is done right

- **Constant-time HMAC comparison:** `ring::constant_time::verify_slices_are_equal` (`lsp/auth.rs:65-75`).
- **Timestamp freshness:** the timestamp is inside the MAC (`auth.rs:59-61`), and requests outside ±60 s are refused (`auth.rs:8, 51-57`). This bounds replay to about two minutes.
- **The body is capped before HMAC buffering:**
  - Content-Length is checked before the read, and the streaming read is capped at 1 MiB (`auth.rs:115-131`).
  - A router-wide `DefaultBodyLimit` also covers the two unsigned routes (`main.rs:298-300`).
- **The auth layer covers every route except the two unsigned `/api/*` routes.** The layering is commented in place: routes at `main.rs:203-280`, layer at `:281-284`, exempt routes at `:285-297`.
- **The api_key is 256-bit** and comes from `ring::rand::SystemRandom` (`main.rs:330-334`).
- **The TLS private key is written `create_new` with mode `0400`** (`lsp/tls.rs:30-39, 155`). The cert is ECDSA P-256, generated locally.
- **Signed RegisterPush is well constructed:**
  - It signs canonical JSON with a `REGISTER_PUSH_V1` type tag, which gives domain separation from the other signed messages (`lsp/messages.rs:145-153`).
  - It enforces a 300 s freshness window and checks the signature against the claimed `node_id` (`handlers/register_push.rs:15-43`).
  - It fails closed (`:66-72`).
- **`/api/channel-exists` validates `node_id`** as 66 hex characters starting `02` or `03` before calling LDK (`handlers/channel_exists.rs:32-54`).
- **Push delivery is well bounded:**
  - Provider calls run outside the `PushService` lock with a 20 s cap (`push/mod.rs:151, 270-298`).
  - There is a per-node cooldown and a failure backoff that is reserved before the send (`push/mod.rs:147-149, 238-257`).
  - The FCM HTTP client has connect and overall timeouts (`push/fcm.rs:110-116`).
  - Each wake runs in its own `JoinSet` task, so a panic fails only that wake (`stability_tick.rs:229-239, 251`).
- **Keysend TLVs are size-capped (8 KiB) and checked for UTF-8 before parsing** (`stable_manager.rs:1432`, `:1508-1519`). The trade settlement row is written only after the signature verifies (comment at `:1452-1456`).
- **The trade-fee refund is idempotent and narrowly scoped** (`lsp/revenue.rs:661-741`):
  - It applies only to trades recorded as `rejected`.
  - The amount comes from LDK's succeeded inbound payment, and the refund goes to the recorded counterparty.
  - It uses a deterministic payment id, so an ambiguous error cannot lead to a second refund.
- **Umbrel packaging handles its secrets carefully:**
  - The pre-start hook uses `umask 077` and `chmod 600` on the configs, which contain the bitcoind RPC password (`umbrel/stable-channels-lsp/hooks/pre-start:6, 100, 134`).
  - sc-lsp `:3002` is not published to the host (`docker-compose.yml:43-55` has no `ports:`).
  - `/setup/` is IP-allow-listed to `app_proxy` and fails closed with `deny all` (`umbrel/docker/40-provision-gui-api-key.sh:8-38`, `lsp-gui-nginx.conf.template:30-33`).
  - nginx `/api/` forwards **without** injecting a credential (`lsp-gui-nginx.conf.template:35-45`). The GUI origin is therefore not an unauthenticated drain; the browser must still compute the HMAC.
- **The operator GUI asks for confirmation** before every send (`lsp-server-gui/src/ui/confirm.rs:24-25`), send-all (`ui/onchain.rs:104-107`), force-close (`ui/channels.rs:795-800`) and splice-out (`ui/channels.rs:884-885`).
- **Secrets are not logged:**
  - Startup logs the api_key **path** only (`main.rs:90-93`).
  - APNs and FCM log only token prefixes (`push/apns.rs:107`, `push/fcm.rs:94`).
  - `TraceLayer::new_for_http()` does not log headers by default, so `X-Auth` stays out of the logs.

## 2. Route × credential matrix

**Credentials.** There is one credential: the SC api_key, a single 32-byte key shared by everyone who holds it. There are no per-client or per-route scopes. These hold it:
- **Operator GUI, desktop:** it reads `<storage>/<network>/api_key` (`lsp-server-gui/src/config.rs:34-37`) or the operator types it in.
- **Operator GUI, web on Umbrel:** the browser fetches it from `/setup/key.txt` on every page load (`ui/mod.rs:101-118`, `platform.rs:177-191`).
- **The Umbrel `gui` container:** it has a read-only mount of the whole sc-lsp data dir (`docker-compose.yml:66-68`).
- **Any local user who can read the key file.** The default mode is 0644 (see F-5).

Wallets do not hold the key.

| Route | Handler | Auth | Money-moving |
|---|---|---|---|
| `/GetNodeInfo`, `/GetBalances`, `/ListChannels` | `handlers/proxy.rs` | HMAC | no |
| `/ListPeers` | `peers.rs:14` | HMAC | no |
| `/ConnectPeer`, `/DisconnectPeer` | `peers.rs:25,36` | HMAC | no, but disconnecting stable users disrupts settlement |
| `/ListPayments`, `/GetPaymentDetails`, `/ListForwardedPayments` | `payments.rs` | HMAC | no |
| `/Bolt11Receive`, `/Bolt12Receive`, `/OnchainReceive` | `lightning.rs`, `onchain.rs` | HMAC | no |
| `/Bolt11Send`, `/Bolt12Send`, `/SpontaneousSend` | `lightning.rs` | HMAC | **YES** (arbitrary destination; keysend with arbitrary `custom_tlvs`) |
| `/OnchainSend` | `onchain.rs` | HMAC | **YES** (arbitrary address; `all_funds`) |
| `/OpenChannel` | `channels.rs` | HMAC | **YES** (commits on-chain funds; `push_to_counterparty_msat` gifts funds) |
| `/CloseChannel`, `/ForceCloseChannel` | `channels.rs` | HMAC | **YES** (fees and timelocks; breaks stable peg) |
| `/SpliceIn` | `channels.rs` | HMAC | yes (moves on-chain funds into a channel) |
| `/SpliceOut` | `channels.rs` | HMAC | **YES** (arbitrary `address`) |
| `/UpdateChannelConfig` | `channels.rs` | HMAC | indirect (fees, dust exposure) |
| `/GraphListChannels`, `/GraphGetChannel`, `/GraphListNodes`, `/GraphGetNode` | `graph.rs` | HMAC | no (large responses) |
| `/SignMessage` | `tools.rs:14` | HMAC | **YES, indirectly**: signs with the LSP node key, the same oracle the LSP uses for SYNC_V1, STABILITY_PAYMENT_V1 and trade replies (`stable_manager.rs:638, 2373, 2648, 3354`) |
| `/VerifySignature`, `/ExportPathfindingScores` | `tools.rs` | HMAC | no |
| `/GetPrice` | `price.rs` | HMAC | no |
| `/ListStableChannels` | `stable_channels.rs:38` | HMAC | no (returns user IPs and countries) |
| `/EditStableChannel` | `stable_channels.rs:72` | HMAC | **YES** (sets the LSP's USD obligation that drives top-ups) |
| `/ListSettlementPayments`, `/GetRevenue` | `stable_channels.rs`, `revenue.rs:13` | HMAC | no |
| `/RefundTradeFee` | `handlers/revenue.rs:34`, `revenue.rs:661` | HMAC | **YES** (bounded: refunds a rejected trade's fee to the recorded counterparty) |
| `/RegisterPush` | `register_push.rs:45` | HMAC **and** node signature | no |
| `/AuditLog`, `/ListChannelLedgerEvents`, `/LdkLog` | `audit_log.rs`, `channel_ledger.rs`, `ldk_log.rs` | HMAC | no (`/LdkLog` can crash the daemon, see F-9) |
| `/api/register-push` | `register_push.rs:100` | **NONE** | no (writes the push-token store and the channel ledger) |
| `/api/channel-exists` | `channel_exists.rs:38` | **NONE** | no (one `ListChannels` gRPC call per request; membership oracle) |

## 3. Findings

### F-1: The Umbrel web GUI sends the admin api_key, and every signed request, over plaintext LAN HTTP
- **Severity:** High
- **Confidence:** Plausible. The key and request exposure is confirmed; the unverified step is Umbrel's `app_proxy` session cookie also being sniffable on HTTP.
- **Where:**
  - `server/lsp-server-gui/src/ui/mod.rs:101-118`
  - `server/lsp-server-gui/src/platform.rs:60` ("Umbrel serves plain HTTP") and `:177-191`
  - `umbrel/docker/40-provision-gui-api-key.sh:63-81`
  - `umbrel/docker/lsp-gui-nginx.conf.template:35-45`
- **Related:** #192, #210
- **Status on main:** open
- **What happens:**
  1. On every page load, the WASM GUI fetches `/setup/key.txt`; the key is not persisted. That file is the raw admin api_key.
  2. The fetch goes from the browser to Umbrel's `app_proxy` over `http://umbrel.local:<port>`, Umbrel's default LAN access mode.
  3. Every later request carries `X-Auth` and its body in cleartext on the same hop, and auto-refresh produces a steady stream of them.
  4. A passive observer on the operator's LAN (shared Wi-Fi, a compromised IoT device) captures the key. They also capture the Umbrel proxy session, which travels in the same HTTP stream.
  5. They can then POST `/api/OnchainSend {address, all_funds}` or `/api/SpliceOut` through `app_proxy`. nginx rewrites `/api/X` to `/X` and forwards it to sc-lsp, which accepts the valid HMAC.
- **Why it matters:** this drains the LSP's entire on-chain and Lightning balance. It contradicts the README claim that "the key itself never travels on the wire after provisioning".
- **Fix:**
  - Stop serving the raw key to the browser:
    - **Either:** sign server-side in a small sidecar behind `app_proxy`, and bind the GUI to a scoped read-mostly key.
    - **Or:** provision the key once by explicit operator action into `sessionStorage`, and require HTTPS or Tor/Tailscale for the GUI.
  - At minimum:
    - Add a separate money-moving credential (see the design observations).
    - Put a prominent "use Tor/Tailscale, not LAN HTTP" requirement in the Umbrel description.
    - Mount only `<network>/api_key` into the gui container, not the whole data dir with `tls.key` and the DBs (`docker-compose.yml:66-68`).

### F-2: The HMAC binds neither path nor a nonce, so captured requests can be replayed for 60 s, including to a different route
- **Severity:** Medium
- **Confidence:** Confirmed for the code path. Capturing a request needs a plaintext vantage point: F-1, a TLS-terminating proxy, or F-8.
- **Where:**
  - `lsp/auth.rs:41-76`: the MAC is over `ts || body` only (`:59-61`).
  - `server/sc-rest-client/src/client.rs:144-160`
- **Related:** #87, #230
- **Status on main:** open
- **What happens:**
  - **Same-route replay within ±60 s:**
    - `/SpontaneousSend` draws a fresh preimage on each call, so it pays twice.
    - `/OnchainSend` pays twice (BDK picks new UTXOs).
    - `/Bolt12Send` re-pays the offer.
    - `/Bolt11Send` is safe because LDK rejects a duplicate hash.
  - **Cross-route replay:** a valid `(ts, body, X-Auth)` triple is accepted by every route whose request message decodes from that body.
    - `CloseChannelRequest{1:user_channel_id, 2:counterparty_node_id}` has the same wire encoding as `ForceCloseChannelRequest`. An observer of an operator's cooperative close can replay it to `/ForceCloseChannel` before the close completes.
    - `UpdateChannelConfigRequest{1,2,3:ChannelConfig}` also decodes as `ForceCloseChannel` whenever the `ChannelConfig` bytes are valid UTF-8, which they are for small field values.
- **Why it matters:** it turns read-only eavesdropping into double payments and unwanted force-closes, which cost on-chain fees and timelocks and break a user's stable channel.
- **Fix:**
  - MAC over `method || path || ts || nonce || body`.
  - Keep a short-lived server-side nonce cache covering the timestamp window, with ±60 s as the TTL.
  - Bump the header format, for example `HMAC2 <ts>:<nonce>:<hex>`, and keep v1 temporarily for read-only routes only.

### F-3: Unauthenticated `/api/register-push` lets anyone take over or suppress a node's wake pushes (same as LSP-001)
- **Severity:** Medium
- **Confidence:** Confirmed
- **Where:**
  - `lsp/main.rs:285-291`
  - `lsp/handlers/register_push.rs:87-135`
  - `lsp/push/tokens.rs:56-60` (`INSERT OR REPLACE` keyed by token) and `:77-81` (newest `last_seen` wins)
- **Related:** #162 (closed, but the route is still live), #108, #160, #376
- **Status on main:** open
- **What happens:**
  1. Anyone POSTs `{"device_token":"<attacker token>","platform":"android","node_id":"<victim>"}`.
  2. That row becomes the newest one for the victim's node.
  3. Every later wake goes to the attacker's device. The FCM data also carries the victim's `node_id` and the direction (`push/fcm.rs:62-70`).
  4. Alternatively, a garbage token makes every send fail and back off.
  5. The victim stays asleep, so pay-backs and top-ups after a wake (#376) never run.

  Node ids are not secret: they appear in every invoice the wallet issues.

  **Extra:** a non-ASCII token panics the APNs logger at `&device_token[..16]` when byte 16 is not a char boundary (`push/apns.rs:107, 140, 147, 157`). The `JoinSet` contains the panic (`stability_tick.rs:233-239`), so the effect is the same as suppression.
- **Why it matters:** it defeats the node-ownership proof that #108 added, and it delays settlement for targeted users.
- **Fix:**
  - Move signed `/RegisterPush` **outside** the HMAC layer. Wallets cannot hold the admin key, which is why the signed path is unusable today.
  - Ship wallet signing, then delete the legacy route.
  - In the interim:
    - Never let an unsigned row shadow a signed one.
    - Cap field lengths: token 512, `node_id` exactly 66 hex characters, `platform` in {ios, android}, `environment` in {sandbox, production}.
    - Use char-safe prefixes when logging.

### F-4: The two unsigned routes allow unbounded disk growth and DB-lock contention
- **Severity:** Medium
- **Confidence:** Confirmed. The full-string echo by serde is standard `Unexpected::Str` behaviour but was not run here.
- **Where:**
  - `lsp/handlers/register_push.rs:101-109`: the parse error is written to the ledger as `REGISTER_PUSH_LEGACY_INVALID`.
  - `src/ledger.rs:1073`: that event is in `CHANNEL_STATE_EVENTS`, so it becomes a durable SQLite row.
  - `src/ledger.rs:456-482`: there is no dedup key and no pruning.
  - `lsp/push/tokens.rs:41-65`: there is no length limit and rows are never deleted.
  - `lsp/handlers/channel_exists.rs:57`
  - `lsp/main.rs:298-302`: no rate, concurrency or timeout layer.
- **Related:** #230, #162
- **Status on main:** open
- **What happens:**
  - **Ledger growth:** a body of `"AAAA…"` (a 1 MiB JSON string) fails to parse as `LegacyPushRequest`. serde_json's message embeds the whole string, so each request appends about 1 MiB to `ledger_events` under the shared DB mutex in an IMMEDIATE transaction. That mutex is the one the stability engine uses. The JSONL mirror is rotated (`src/audit.rs:129-133`); the SQLite ledger is not.
  - **Token-store growth:** valid bodies with distinct tokens and 1 MiB fields grow `push_tokens.db` the same way. Each insert holds the `PushService` mutex across a synchronous sqlite open and write.
  - **gRPC amplification:** each `/api/channel-exists` call costs one `ListChannels` gRPC call to LDK Server.
- **Why it matters:** an anonymous client on the public `/api/*` proxy can fill the disk. `auth.rs:13-16` notes that this halts stability payments. The traffic also slows ledger writes and floods the operator's ledger UI with integrity events.
- **Fix:**
  - Log only the error class and the body length, never `e.to_string()`.
  - Move `REGISTER_PUSH_LEGACY_INVALID` to `OPERATIONAL_EVENTS`.
  - Validate field lengths and formats before any write.
  - Prune `push_tokens` rows older than 30 days and keep at most N per node.
  - Add a per-IP rate limit, for example `tower_governor`, on both `/api/*` routes.
  - Cache `ListChannels` for a few seconds in `channel_exists`.

### F-5: The api_key file is world-readable by default, written non-atomically, and an empty key file fails open
- **Severity:** Medium
- **Confidence:** Confirmed
- **Where:** `lsp/main.rs:324-355`
  - The key is written with `std::fs::write` at `:344`, which uses the umask default, typically 0644, without fsync or rename.
  - Any existing file is accepted whatever its length (`:326-328`).
  - The network dir is created 0755 (`:82`).
- **Related:** none
- **Status on main:** open
- **What happens:**
  - **Readable key:** outside Umbrel, where pre-start makes the parent 0700, any local user can read `<dir>/<network>/api_key`, which grants full LSP control. The TLS key is 0400, so the stronger secret has the weaker mode.
  - **Empty key accepted:**
    1. A crash or ENOSPC between create and write on first start leaves a 0-byte file.
    2. The daemon exits.
    3. Umbrel's `restart: on-failure` brings it back.
    4. `path.exists()` reads the empty file, so `api_key = ""` and the daemon starts normally.
    5. `HMAC("", ts||body)` is computable by anyone, so every route, `/OnchainSend` included, accepts unauthenticated requests.

    The GUI's `-s` check never provisions an empty key, so the operator only sees a GUI that cannot connect.
- **Why it matters:** a fail-open of the only control on the money-moving surface.
- **Fix:**
  - Require exactly 32 bytes and refuse to start otherwise.
  - Write a temp file with `OpenOptions::mode(0o600).create_new(true)`, fsync it, then rename it into place.
  - Create the dir 0700.
  - Warn at startup if the key file is group- or world-readable.

### F-6: Any Lightning peer can make the LSP write durable ledger rows and run gRPC under the global manager lock for 1 msat each
- **Severity:** Medium
- **Confidence:** Confirmed
- **Where:**
  - `lsp/stable_manager.rs:1428-1460` (handles TLV 13377331)
  - `lsp/stable_manager.rs:3021-3104` (`MESSAGE_RECEIVED` and `TRADE_PARSED_PAYLOAD_OK` audits, then a `ListChannels` gRPC call at `:3063`, then `TRADE_CHANNEL_NOT_FOUND` or `TRADE_SIGNATURE_INVALID`)
  - `lsp/event_loop.rs:388-391` (the manager lock is held for the whole dispatch)
  - `src/ledger.rs:1045-1060` (all of those events are durable channel-ledger events)
- **Related:** #230. Its "fixed-window 20/s rate limit on stable-TLV processing" is **absent on main**; the body cap, verify-then-write and JSONL rotation did land.
- **Status on main:** partially fixed (#230's rate limit missing)
- **What happens:**
  1. A peer keysends 1 msat with a well-formed `TRADE_V1` envelope that names a random `channel_id`.
  2. The LSP records about three ledger rows carrying attacker strings of up to 8 KiB.
  3. It performs one `ListChannels` call while holding the manager mutex. That mutex serializes the stability tick, wake settlement and `EditStableChannel`.

  At HTLC throughput this costs a few thousand sats per day and produces gigabytes of unpruned ledger.
- **Why it matters:** it degrades and eventually halts stability settlement (disk full), and it pollutes the authoritative channel ledger.
- **Fix:**
  - Restore the per-window rate limit before any audit, DB or gRPC work.
  - Make pre-authentication failures operational (JSONL-only) or aggregate them per window.
  - Match the channel from a cached list keyed by the payer, so `TRADE_*` work runs only for counterparties with a stable channel.

### F-7: The REST listener has no timeouts, connection limits or concurrency limits
- **Severity:** Medium
- **Confidence:** Plausible. The unverified step is the runtime defaults in axum-server 0.7 and hyper 1.x for HTTP/2 `max_concurrent_streams` and header read timeouts.
- **Where:**
  - `lsp/main.rs:298-318`: only `DefaultBodyLimit` and `TraceLayer`; no `TimeoutLayer`, `ConcurrencyLimitLayer` or rate limit.
  - `lsp/auth.rs:123`: `to_bytes` has no deadline.
  - `example-config.toml:26-28`: suggests exposing `:3002` publicly via `tls.hosts`.
- **Related:** #230
- **Status on main:** open
- **What happens:**
  - **Slow uploads:** where `:3002` is directly reachable, an unauthenticated client opens many HTTP/2 streams (ALPN `h2`, `tls.rs:436`), each trickling up to 1 MiB. The auth middleware buffers each body without a deadline before checking the HMAC, so memory grows by about 1 MiB per open stream. The "concurrent maxima stay bounded" claim at `auth.rs:16` assumes a concurrency bound that does not exist.
  - **Slow upstream:** a hung LDK Server call likewise pins handler tasks forever.
- **Why it matters:** OOM kills the daemon, and the stability engine with it.
- **Fix:**
  - Add `tower::timeout` (about 15 s for the body read, about 30–60 s per request).
  - Add a global `ConcurrencyLimitLayer`.
  - Set h2 `max_concurrent_streams` low (for example 32) and enable HTTP/1 header read timeouts on the axum-server builder.
  - Add a per-IP rate limit on unauthenticated failures.

### F-8: Client TLS "pinning" is additive, so any public-CA certificate for the host is also trusted
- **Severity:** Low
- **Confidence:** Confirmed
- **Where:**
  - `server/sc-rest-client/src/client.rs:95-104`: `add_root_certificate` keeps the built-in roots.
  - `server/sc-rest-client/Cargo.toml`: `reqwest` `rustls-tls` means webpki roots are included.
- **Related:** none
- **Status on main:** open
- **What happens:** when the desktop GUI dials the daemon by DNS name, a certificate from any public CA for that name passes, for example one obtained through a DNS or BGP hijack plus ACME. The attacker can then harvest signed requests (F-2) and show the operator false balances.
- **Why it matters:** the README's claim that "cert pinning proves who's answering" does not hold.
- **Fix:**
  - `ClientBuilder::tls_built_in_root_certs(false)` together with the pinned certificate.
  - Better: compare the leaf SPKI hash through a custom verifier.

### F-9: A client-controlled `max_lines` in `/LdkLog` can abort the daemon
- **Severity:** Low
- **Confidence:** Confirmed for the code path. The abort occurs where the allocation fails, which is Linux's default heuristic overcommit.
- **Where:**
  - `lsp/handlers/ldk_log.rs:28, 57`: `VecDeque::with_capacity(max_lines)` with `max_lines` taken from a `u32`.
  - The GUI passes its text box through unchanged (`lsp-server-gui/src/actions.rs:377-379`).
- **Related:** none
- **Status on main:** open
- **What happens:** `max_lines = 4294967295` asks for about 96 GiB. Allocation failure hits `handle_alloc_error`, which aborts the process. Any key holder, or an operator typing a large number into the "lines" box, stops the stability engine until it restarts.
- **Why it matters:** an authenticated single request kills the money engine.
- **Fix:** clamp `max_lines`, for example to 10 000, and do not pre-allocate from client input. Apply the same clamp in `audit_log.rs`.

### F-10: `EditStableChannel` accepts non-finite or negative targets and cold prices, with no confirmation and no wallet SYNC
- **Severity:** Low
- **Confidence:** Confirmed for the missing validation. The effect on later ticks is Plausible and was not traced into the stability math.
- **Where:**
  - `lsp/handlers/stable_channels.rs:72-96`
  - `lsp/stable_manager.rs:761-916`: no `is_finite`, sign or `btc_price > 0` guard. Compare the trade path at `:3044-3046`.
  - `lsp/stable_manager.rs:835-840`: `btc_price == 0` stores `backing_sats = 0`.
  - The GUI parses `"inf"` and `"NaN"` as `f64` (`lsp-server-gui/src/actions.rs:1259`).
  - The edit form has no confirm step (`ui/stable_channels.rs:259`).
- **Related:** #413
- **Status on main:** open
- **What happens:**
  - An operator, or a script holding the key, sets `expected_usd` to `inf`, `NaN` or `-5`, or edits during a stale price. The bad record is persisted and drives the next tick's par calculation.
  - No SYNC_V1 is sent, so the wallet's book silently diverges from the LSP's.
- **Why it matters:** this route directly sets the LSP's USD obligation to a user.
- **Fix:**
  - Reject non-finite or negative values and `btc_price <= 0` on the server.
  - Require a confirmation step showing the old and new target in the GUI.
  - Send a SYNC_V1, or document clearly that an edit is LSP-local.

### F-11: The trade-fee refund preimage can be derived from public data
- **Severity:** Low
- **Confidence:** Plausible. The unverified step is whether a refund to a direct peer is ever routed through an intermediary.
- **Where:**
  - `src/trade.rs:122-126`: `preimage = sha256("stable-channels/trade-fee-refund/" || trade_payment_id)`.
  - Used at `lsp/revenue.rs:679, 702-708`.
- **Related:** none
- **Status on main:** open
- **What happens:** `trade_payment_id` is the payment hash of the user's original trade keysend, which any hop on that route knows. If the refund keysend is routed through a node that also saw the original payment, that node can compute the preimage and settle the HTLC upstream without forwarding it, keeping the refund.
- **Why it matters:** the loss is small (one trade fee) and needs a multi-hop route. Keysend security assumes intermediaries cannot know the preimage.
- **Fix:** derive the preimage as `HMAC(local_secret, trade_payment_id)`. That stays deterministic for idempotency but is unguessable. Alternatively, restrict the refund route to the direct channel.

## 4. Design observations

1. **One all-powerful key, and the README's containment claim is misleading.** "A leaked SC daemon api_key … gives the attacker access only to the SC daemon" understates the risk. The SC daemon proxies every money-moving LDK call plus `/SignMessage`, which signs with the LSP node key: the same oracle that authenticates SYNC_V1, STABILITY_PAYMENT_V1 and trade replies to wallets. A leaked SC key is therefore a full LSP drain plus the ability to forge LSP protocol messages. Recommendations:
   - Split keys into read-only, operator-admin and money-moving, with a separate key or a second confirmation for sends, splices and closes.
   - Remove or scope `/SignMessage`; it is not needed by the GUI's normal flows.
   - Add key rotation tooling.
2. **Wallets and the REST surface.** Wallets never use HMAC. If mobile is ever moved onto `sc-rest-client` (the README says it is "consumed by mobile wallet apps"), it must get its own unauthenticated-but-signed surface. Signed `/RegisterPush` must move outside `auth_middleware` before mobile signing ships, or F-3 cannot be closed.
3. **Money-moving REST calls are not audited by the SC daemon.** The proxy handlers (`lightning.rs`, `onchain.rs`, `channels.rs`, `tools.rs`) call no `audit_event`, so an operator or a leaked key can drain funds without leaving an SC ledger row. Add an audit row (route, a non-secret request summary, result) for every non-read route.
4. **Push and the restore guard are hardcoded to `stablechannels.com`** in both mobile apps, even when the user configures a custom LSP (Android `LspPreferencesManager`, `Constants.kt:28-29`; iOS `Constants.swift:33-34`). Consequences:
   - Custom-LSP users leak their `node_id` and push token to the default operator.
   - They are never woken by their own LSP.
   - The fail-open restore guard (`AppState.kt:4289-4294`) asks the wrong LSP, gets `exists:false`, and proceeds with a wipe that can force-close the channel with the custom LSP.
5. **Umbrel nginx rewrites `/api/X` to `/X`** (`lsp-gui-nginx.conf.template:42`), so the daemon's own `/api/register-push` and `/api/channel-exists` are reachable there only as `/api/api/...`. A self-hosted Umbrel LSP cannot serve wallets' push or restore calls as shipped.
6. **The signed RegisterPush signature omits `platform` and `environment`** (`messages.rs:145-153`), and there is no nonce within its 300 s window. This is harmless today because only key holders can call it. Include both fields when the route moves out of the HMAC layer.
7. **The production topology is not in the repo.** The comments refer to an "Apache-proxied" public `/api/*` (`register_push.rs:96-98`, `auth.rs:13-15`). Whether the HMAC routes are internet-reachable on `stablechannels.com` depends on that unseen config. F-2 and F-7 matter most if they are.
8. **The `/setup/` allow-list resolves `app_proxy`'s IP once, at gui container start** (`40-provision-gui-api-key.sh:8-38`). If `app_proxy` is re-created with a new IP, another container that is later given the old IP could fetch `key.txt` without going through Umbrel auth. This is an edge case; prefer a shared secret header injected by `app_proxy`, or drop `key.txt` (F-1).
