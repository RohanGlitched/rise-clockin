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
- **Safe mints only.** A pact's token is fixed when it's created, and `create_pact` refuses any Token-2022 mint whose extensions would let someone other than the program move, tax, block or freeze the vault's tokens: permanent delegate, transfer fees, transfer hooks, pausing, default-frozen accounts and so on (`UnsafeMint`). Only metadata and token-group extensions are allowed, which is what test SKR carries. Proven on devnet: [a pact with test SKR is created](https://explorer.solana.com/tx/54jEiCPF96HcP7GJXFcauCsU8MbGX42dGJpsgL3gy6jbtVLjrgj523k2xdRX1G7sEu69uyxPwMTV8p6NCQ8nTCm6?cluster=devnet), [a pact with a permanent-delegate mint is refused on chain](https://explorer.solana.com/tx/5vU82ct7gHBihTV5tJz63LY3xZBNufBmsFjwWYS83iT835CpZnfU2vziFyRUuuDs6ycnbsTrmt6UsDpxS9ZKs4KU?cluster=devnet) (`scripts/mint-guard.ts`), and in the Bankrun tests. One gap: the guard reads only the extension list, not the base mint's freeze authority (see *Program audit* below; test SKR has none).
- **Conservation.** `payout_i = stake × kept_i + pot × kept_i ÷ Σkept`, with `pot = Σdeposits − stake × Σkept`. Payouts sum to exactly what was deposited (integer division leaves at most a few base units of dust in the vault). If nobody kept a morning, everyone gets their deposit back. Proven in tests and on devnet: see [docs/SETTLEMENT.md](docs/SETTLEMENT.md).
- **Time.** A clock-in counts only if `Clock::unix_timestamp` is within `[wake − 30 min, wake + grace]` for that member's morning. The phone's clock is never used. Changing the device time, time zone or clock app does nothing.
- **No replays, no double counting.** Each member has one slot per morning (`offsets[day]`). A second clock-in for the same morning fails with `AlreadyIn`. Solana rejects duplicate signatures anyway.
- **Only you.** `clock_in` requires the member's owner to sign, and the member PDA is derived from `(pact, owner)`. Nobody can clock in for someone else (tested).
- **Fixed wake windows.** The wake time is written when you join and can't be edited, so you can't move your window after oversleeping. Late joiners lock stakes only for mornings whose window hasn't opened yet.
- **Settlement timing.** `claim` works only after the last window has closed (`NotOver`), and only once per member (`AlreadyClaimed`).
- **Checked arithmetic.** Every counter and balance update uses `checked_add`/`checked_mul` (and `u128` for the pot split), and release builds keep `overflow-checks = true` as a second net.
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

- **Keys.** With a phone wallet, Rise never sees a private key: transactions are built in the app and signed by the wallet through MWA. The practice wallet (devnet only, clearly labelled) keeps its throwaway seed sealed with an AES-256-GCM key held in the Android Keystore, so a copy of the app's storage alone can't recover it. Backups are disabled.
- **What gets signed.** Every transaction is simulated against the program first (with blockhash replacement), so failures show a readable reason before the wallet is opened. The wallet shows the program and accounts being used.
- **Verified identity.** `/.well-known/assetlinks.json` ties `rise-clockin.vercel.app` to the release signing certificate, so wallets can show the app as verified.
- **Network.** Release builds allow HTTPS only; plain HTTP to a local validator is allowed only in debug builds.
- **Privacy.** Camera frames, sensor traces and the coach's model stay on the device. Only the clock-in (and its proof confidence) goes on chain.

## Automated security review (4 October 2026)

An automated review of commit `5d94a7e` (code patterns, dependency advisories, Rust crates, CI configuration) listed the items below. Each one was checked against the code. Its code findings were unverified pattern matches; this is what each turned out to be and what changed.

| Finding | Outcome |
| --- | --- |
| Practice wallet seed stored in SharedPreferences (High) | **Fixed.** The seed is sealed with an AES-256-GCM key in the Android Keystore. Older installs are migrated when the app starts. Verified on device: the migrated key signed a devnet faucet transaction. |
| `init_if_needed` on `ticket` and `user_ata` in `drip` (High ×2) | **Not an issue.** The ticket's address is derived from the caller's own wallet, and the one-hour cooldown is enforced for new and existing tickets alike. For an existing token account Anchor still checks its mint, owner and token program. Covered by the "drips test SKR once an hour" test. |
| Token-2022 transfer fees could under-fund `join`/`claim` accounting (Medium ×2) | **Fixed at the source.** Pacts can no longer be created with mints that carry transfer fees, a permanent delegate, transfer hooks or other extensions that can move or freeze funds (see *Safe mints only*). New test, live devnet proof, program upgraded. |
| `pact` read before a CPI and written after (Medium ×2) | **Not an issue.** The CPIs are token transfers, which never write the pact account, so there is nothing stale to reload. |
| Additions that may overflow (Medium ×5) | **Hardened.** They could only abort, never wrap, because release builds use `overflow-checks = true`; they are now explicit `checked_add` calls. |
| CI actions referenced by tag (Medium ×2) | **Fixed.** Every third-party action is pinned to a full commit SHA. |
| Exported `MainActivity` (Medium) | **By design.** It is the launcher and the target of invite links. A link only carries a pact address, which is now validated as a public key, and nothing happens until the user taps Join and approves the transaction. |
| `java.util.Random` / Python `random` (Medium ×3) | **Not an issue.** They only place decorative stars in the sky and the README art. No key, nonce or token is ever made with them. |
| `@solana/web3.js` v1 in scripts and the web API (Low ×8) | **Accepted for now.** Anchor 0.31's client is built on web3.js v1. The Android app doesn't use it. |
| `uuid` 8.3.2 (via web3.js's RPC client) | **Fixed.** Overridden to 11.1.1 in the website and scripts; RPC calls tested. |
| `serialize-javascript` 6.0.2 (via mocha) | **Fixed.** Overridden to 7.1.2 (test tooling only). |
| `stream-json` 1.9.1 (via web3.js's RPC client) | **Not reachable.** Only the RPC library's server-side stream parser uses it; Rise uses the client. Will follow the upstream fix. |
| `toml` 3.0.0 (via Anchor) | **Not shipped.** Anchor only parses `Anchor.toml` in workspace mode, which neither the scripts nor the app use. |
| `bigint-buffer` 1.1.5 (via spl-token) | **No fix published.** The overflow is in its native binding, which doesn't load in our environments (the pure-JS fallback runs). Inputs are fixed-layout account data read from RPC. |
| `rand` 0.7, `bincode` 1.3, `borsh` 0.10, `libsecp256k1` 0.6 (Rust) | **Upstream.** They come in through the Solana and Anchor crates, not Rise's own code; they will move with the next Anchor release. |
| Crate has no licence | **Fixed.** `license = "MIT"`, matching the repository's LICENSE. |

## Test coverage of this document

Every rule in *What the program enforces* has a Bankrun test against the built program. Run them all with `npm test` (WSL/Linux) or `npm run test:ci` (Linux; the same command CI runs). File names are under `tests/`.

| Rule | Test that proves it |
| --- | --- |
| Custody: only `claim` moves vault SKR, to the member's own account | membership.ts: "a claim can't be pointed at another pact's vault", "a claim pays only the signer's own token account", "a non-member can't claim from a pact" |
| Safe mints only | mints.ts: one "refuses …" test per extension (TransferFeeConfig, PermanentDelegate, TransferHook, NonTransferable, DefaultAccountState, MintCloseAuthority, InterestBearingConfig, ConfidentialTransferMint, allowed + denied mixed, and extensions newer than the bundled Token-2022); "accepts MetadataPointer with on-mint TokenMetadata", "accepts GroupPointer and GroupMemberPointer", "accepts a classic SPL Token mint …" |
| The pact's mint is the only one that can be deposited | membership.ts: "refuses a join that deposits a different mint than the pact's" |
| Conservation, and the refund when nobody kept a morning | payouts.ts: "property: 40 random pacts settle exactly by the formula and conserve the vault", "3-morning pact …", "30-morning pact …", "1-morning and 64-morning pacts …", "nobody kept a morning …", "everyone kept every morning …", "rounding dust stays in the vault …" |
| Time: `[wake − 30 min, wake + grace]` on Solana's clock | windows.ts: "opens exactly 30 minutes before the wake time …", "closes exactly at wake + grace …", "is closed between one day's grace end and the next day's opening", "short days: the early window shrinks …", "windows are fixed in UTC …" |
| No replays, no double counting | windows.ts: "refuses a second clock-in in the same window, even at its last second (AlreadyIn)" |
| Only you | membership.ts: "nobody can clock in with another member's account", "a member account from one pact can't clock in to another pact"; rise.ts: "only the member can clock in for themselves" |
| Fixed wake windows; late joiners lock only the mornings left | membership.ts: "refuses a second join by the same wallet, even with a different wake time"; windows.ts: every test under "late joiners" |
| Settlement timing | windows.ts: "latest wake time: the final window closes one second before end_ts …", "claim exactly at the typical last window close … is refused"; payouts.ts: "a member who kept nothing gets 0 … and can't claim twice" |
| Checked arithmetic | payouts.ts: "an oversized stake is refused with Overflow instead of wrapping" |
| Pact settings and member limits | settings.ts: every test; membership.ts: "max members: a 500-seat pact takes 500 joins and refuses the 501st", "a 2-seat pact refuses the third member" |
| Faucet: one drip per wallet per hour, only test SKR, only through `mint_auth` | faucet.ts: "cooldown: a drip 3599 s after the last is refused …", "each wallet has its own ticket …", "a wallet can't use another wallet's ticket …", "only the faucet's mint can be dripped …", "init_faucet runs once", "refuses a mint whose authority isn't the program's mint_auth PDA" |
| Proof confidence is written on chain with the clock-in | events-cu.ts: "clock_in emits ClockedIn with pact, owner, day, delta, mission, streak and ts" |

Compute units per instruction are logged by events-cu.ts ("compute-unit snapshot per instruction"); the largest is a first drip at about 60,000 CU.

## Program audit (4 October 2026)

A line-by-line review of `programs/rise/src/lib.rs` as deployed, with a test for each finding. The program was not changed: each fix below would need a program upgrade.

| Finding | Severity | Evidence | Suggested fix |
| --- | --- | --- | --- |
| **Daylight-saving and time-zone changes.** The app turns the local wake time into a UTC offset once, at join (`Store.wakeOffsetFor`), but schedules the alarm by local time (`AlarmScheduler.nextWake`). After a DST change or a flight, the alarm rings an hour off the on-chain window and the morning is lost. The program side is correct and consistent (UTC). | Medium (app) | windows.ts: "windows are fixed in UTC: a one-hour daylight-saving shift lands outside the window" | Schedule pact alarms from the on-chain target (`start + day × day_secs + wake_offset`), and warn when the phone's UTC offset differs from the one used at join. |
| **The upgrade authority is the one admin power.** No instruction can move pact funds, but the program is upgradeable, so whoever holds the upgrade key could deploy code that does. | Medium (trust) | `solana program show 6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS` | Publish the upgrade authority; move it to a multisig or make the program immutable before real SKR. |
| **No upper bound on day length or start time.** A pact with 4,294,967,295-second days starting in ten years is accepted; anyone who joins locks their stake for over 8,000 years. Only reachable through an invite to a pact made outside the app. | Low | settings.ts: "informational: day length and start time have no upper bound" | Cap `day_secs` (e.g. ≤ 86,400) and `start_ts` (e.g. ≤ now + 30 days). |
| **Member names are limited by bytes, the app trims by characters.** A 7-letter Devanagari name is 21 bytes and fails with `BadName`, whose message also says "1 to 32 characters" for a 20-byte limit. | Low | settings.ts: "member name limit is bytes, not characters …" | Trim names to 20 UTF-8 bytes in the app; give join its own message. |
| `public` is a listing hint, not access control: anyone with a private pact's address can join it. | Info | membership.ts: "informational: a pact created with public = false is still joinable …" | Say so in the app, or add an invite key if private pacts need to be private. |
| The IDL lists `Joined`, `Claimed` and `PactCreated` events that the program never emits. | Info | events-cu.ts: "informational: the IDL lists Joined, Claimed and PactCreated events …" | Regenerate `target/idl/rise.json` from `lib.rs`, or emit them. |
| `init_faucet` has no admin check: on a fresh deployment the first caller picks the faucet's mint (it must still be a mint whose authority is `mint_auth`). Devnet's faucet was set up at deploy time. | Info | faucet.ts: "informational: any wallet can be the one to call init_faucet first" | Gate it on the upgrade authority if the program is deployed again. |
| A vault address is derivable from (creator, seed), so someone who guesses a seed can create the vault first and block that seed. The app's seed is the current time in milliseconds; a retry uses a new one. | Info | settings.ts: "informational: anyone can pre-create a pact's vault and block that one seed" | Use `init_if_needed` for the vault, or accept it. |
| Rent for pact, vault and member accounts is never returned, and rounding dust (at most one base unit less than the number of keepers) stays in the vault. | Info | membership.ts: "the creator pays for the pact and vault …"; payouts.ts: "rounding dust stays in the vault …" | Optional `close_member` / `close_pact` after settlement. |
| The faucet's hourly limit is per wallet, so new wallets get new drips. Test SKR only. | Info | faucet.ts: "each wallet has its own ticket …" | None needed on devnet. |

What held up, and why:

- **Accounts.** Member PDAs are `("member", pact, owner)` with `has_one = owner` and `has_one = pact`; the vault is the pact PDA's associated token account, checked by address, mint and token program on every use; `has_one = mint` ties join and claim to the pact's mint. Every instruction that moves value needs the owner's signature. `Account<Pact>` checks the owner and discriminator, and only `create_pact` makes one.
- **Windows.** `early + grace < day_secs` is enforced, so two windows never overlap and each clock-in maps to exactly one day. The delta always fits in `i16` (−1,800 to +3,600), and `offsets[day]` is bounds-checked by `day < days ≤ 64`.
- **Frozen totals.** No join or clock-in can land after `end_ts`: the latest window closes at `end_ts − 1`, and join needs a morning that hasn't opened. So every claim sees the same `total_hits` and `total_deposited`, and claim order doesn't matter (the property test claims in random order).
- **Payout math.** `stake × total_hits ≤ total_deposited` always, the split runs in `u128`, `total_hits = 0` refunds instead of dividing by zero, and `claimed` is set before the transfer. The property test settled 40 random pacts exactly (261 members, 880 clock-ins); a 300-pact run with another seed also passed.
- **Mint allowlist.** It compares raw `u16` type codes, so an extension added to Token-2022 later is refused by default.
- **`init_if_needed`.** The drip ticket's seeds include the caller, and existing token accounts are still checked for mint, owner and token program.

## Reporting

Please open a GitHub issue or contact the maintainer for anything security related.
