# Security and anti-cheat

Rise asks people to put SKR behind waking up, so it has to be clear about what is enforced where. This document lists every trust boundary, every attack we considered, and what stops it. Short version: **money rules and time rules are enforced on chain; proof that you physically got up is enforced on the phone and made visible to your pact; the servers never hold user funds or keys.**

## Trust boundaries

| Layer | Trusted for | Not trusted for |
| --- | --- | --- |
| **Rise program (Solana)** | Custody of every pact's SKR, wake windows, one clock-in per morning, payouts, refunds, faucet rate limits | Whether you were really awake (it can't see your room) |
| **Solana `Clock` sysvar** | The time of every clock-in | — |
| **Phone (Rise app)** | Running the alarm and missions, scoring the proof, building transactions | Time, balances, payouts: none of these come from the phone |
| **Wallet (Seed Vault / Phantom / Solflare via MWA)** | Holding keys and signing | — |
| **Rise web API (Vercel)** | Topping up devnet SOL for fees; checking Seeker Genesis Token ownership | Nothing involving SKR, pacts or user keys |
| **Your pact** | Social verification: everyone sees every punch, late mark and miss | — |

## What the program enforces

- **Custody.** Each pact's SKR sits in an associated token account owned by the pact PDA. Only the program can move it, and only in `claim`. There is no admin key, no upgrade-gated escape hatch in the instruction set, no withdraw instruction, and no fee.
- **Conservation.** `payout_i = stake × kept_i + pot × kept_i ÷ Σkept`, with `pot = Σdeposits − stake × Σkept`. Payouts sum to exactly what was deposited (integer division leaves at most a few base units of dust in the vault). If nobody kept a morning, everyone gets their deposit back. Proven in tests and on devnet: see [docs/SETTLEMENT.md](docs/SETTLEMENT.md).
- **Time.** A clock-in counts only if `Clock::unix_timestamp` is within `[wake − 30 min, wake + grace]` for that member's morning. The phone's clock is never used. Changing the device time, time zone or clock app does nothing.
- **No replays, no double counting.** Each member has one slot per morning (`offsets[day]`). A second clock-in for the same morning fails with `AlreadyIn`. Solana rejects duplicate signatures anyway.
- **Only you.** `clock_in` requires the member's owner to sign, and the member PDA is derived from `(pact, owner)`. Nobody can clock in for someone else (tested).
- **Fixed wake windows.** The wake time is written when you join and can't be edited, so you can't move your window after oversleeping. Late joiners lock stakes only for mornings whose window hasn't opened yet.
- **Settlement timing.** `claim` works only after the last window has closed (`NotOver`), and only once per member (`AlreadyClaimed`).
- **Checked arithmetic** (`overflow-checks = true`, `checked_mul`/`checked_add`, `u128` for the pot split).
- **Faucet (devnet test SKR only).** One drip per wallet per hour, enforced by a `DripTicket` PDA. The mint authority is the program's `mint_auth` PDA; nobody can mint outside the faucet.

## Attacks on proof of waking, and what stops them

The chain can't see your bedroom, so this is where a determined cheater has room. Rise layers defences and is honest that they are deterrents, not proofs.

| Attack | Defence |
| --- | --- |
| **Spoofed light sensor** (emulator, sensor-injection app, rooted phone) | The proof analyser records the full lux trace. Real sensors are noisy and continuous; injected values come in a few exact steps and jump instantly. Such traces are scored **low confidence**. |
| **Shaking the phone instead of walking** | Steps must have a human rhythm: metronome-perfect intervals, impossible cadence (>4.5 steps/s) or a near-still accelerometer during counted steps lower the score. Rise Coach raises the step count on risky mornings. |
| **QR wake spot taped next to the bed** | The code is random per user and can be regenerated; scanning within seconds of the mission starting is flagged as suspicious. |
| **Photo of a photo / screen** for "Show the morning" | On-device vision needs a morning scene (daylight, window, kitchen, coffee) above the coach's confidence threshold, held for three seconds (looking away drains the progress). The confidence is recorded. |
| **Hiding a weak proof** | The proof's confidence is **written on chain with the clock-in**: the `mission` byte carries the mission (bits 0–1), the confidence level (bits 2–3) and an AI-vision flag (bit 4), and it's emitted in the `ClockedIn` event. Pacts can see how each morning was proven. |
| **Clocking in early and going back to sleep** | The window opens only 30 minutes before the wake time, and the alarm still has to be stopped by a mission. The time card shows exactly when you clocked in. |
| **Sybil accounts in public pacts** (one person, many seats) | Seeker Genesis Token verification: Sign in with Solana, then the server checks the wallet holds an SGT on mainnet (mint authority, metadata pointer and group membership must all match). The SGT mint identifies the device, so community pacts can allow one seat per Seeker. |

**Residual risk:** a rooted phone with custom sensor injection that mimics noise can still pass. That's acceptable for pacts between friends (the stakes are what you chose, and everyone sees the card). Planned hardening: Play Integrity / Seeker device attestation on the mission result, and signing the proof trace hash into the transaction.

## The web API

- **`/api/sol`** sends ~0.03 devnet SOL to wallets that are nearly empty, so new users can pay fees. Rate-limited per address and refused when the wallet already has enough. Devnet SOL only; the key holds nothing of value and is stored as a Vercel secret, never in the repo.
- **`/api/seeker`** verifies a Sign-in-with-Solana message (domain `rise-clockin.vercel.app`, signed by the claimed wallet, ed25519 checked with tweetnacl), then reads mainnet to look for an SGT. It never sees a private key and doesn't touch SKR or pacts.
- Neither endpoint can move user funds. If either were compromised, the worst case is devnet SOL drained from the faucet wallet or a wrong "verified Seeker" badge.

## The app

- **Keys.** With a phone wallet, Rise never sees a private key: transactions are built in the app and signed by the wallet through MWA. The practice wallet (devnet only) keeps a throwaway key in app-private storage, clearly labelled.
- **What gets signed.** Every transaction is simulated against the program first (with blockhash replacement), so failures show a readable reason before the wallet is opened. The wallet shows the program and accounts being used.
- **Verified identity.** `/.well-known/assetlinks.json` ties `rise-clockin.vercel.app` to the release signing certificate, so wallets can show the app as verified.
- **Network.** Release builds allow HTTPS only; plain HTTP to a local validator is allowed only in debug builds.
- **Privacy.** Camera frames, sensor traces and the coach's model stay on the device. Only the clock-in (and its proof confidence) goes on chain.

## Reporting

Please open a GitHub issue or contact the maintainer for anything security related.
