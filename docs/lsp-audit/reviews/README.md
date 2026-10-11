# Raw reviewer notes

One file per review area, written during phase 3 and kept as evidence. Each reviewer read the code on `main` @ `9aff5ec` (plus the open PR branches for #400 and #415) without building or running it.

These notes are unedited. Some items are marked Plausible, and finding ids here (A-1, B-2, F-5, ...) are local to each file. [`../FINDINGS.md`](../FINDINGS.md) is the checked summary with the stable `LSP-NNN` ids.

| File | Area |
|---|---|
| `A-stability-engine.md` | A: top-ups, pay-backs, the stability tick |
| `B-trades.md` | B: trades, rejections, response retry, fee refunds |
| `D-channel-lifecycle.md` | D: channel ready, splices, closes, startup reconcile, operator edits |
| `E-persistence-concurrency.md` | E: DB ordering, crash windows, migrations, locks, ledger |
| `F-api-auth-push.md` | F: REST surface, HMAC, TLS, push, secrets, Umbrel |
| `GH-price-protocol.md` | G and H: price oracle, wire protocol, wallet counterparts |
