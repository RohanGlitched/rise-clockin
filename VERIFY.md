# Verify Rise yourself

Everything a judge needs to check the claims in the demo: deployed addresses, transactions, how to rebuild each part from source, and how to reproduce the devnet demo.

## Deployed on Solana devnet

| What | Address |
| --- | --- |
| Rise program | [`6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS`](https://explorer.solana.com/address/6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS?cluster=devnet) |
| Test SKR (Token-2022, on-chain metadata) | [`SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38`](https://explorer.solana.com/address/SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38?cluster=devnet) |
| Faucet PDA (`"faucet"`) | derived from the program |
| Public pact "Sunrise Club" | [`6mKwohdjprE3F8Ujxz24AtfJv9Q6G9xCdAMR2SLgKhRK`](https://rise-clockin.vercel.app/join/6mKwohdjprE3F8Ujxz24AtfJv9Q6G9xCdAMR2SLgKhRK) |
| Public pact "No snooze October" | [`63XaKeZjLePbFwSspPjtsf3xbGygWJjize4FmqC8AXcQ`](https://rise-clockin.vercel.app/join/63XaKeZjLePbFwSspPjtsf3xbGygWJjize4FmqC8AXcQ) |

## Transactions from the demo

| Moment | Transaction |
| --- | --- |
| Clock-in on a OnePlus 10T, signed in Phantom (10:02:56 IST, Oct 4) | [K64SPtnc…ksZJ](https://explorer.solana.com/tx/K64SPtncP1VEJNzDBca8dCiyDFuBddUu8HSpDnFMRz8Aip4oCfV9HJdMfkCt9P78eJKYJ8nMby5wrXxMjnvksZJ?cluster=devnet) |
| Full settlement run: lock, clock-ins, a late clock-in, missed mornings, a rejected early claim, final claims, a nobody-woke refund, and the vault reaching zero | [docs/SETTLEMENT.md](docs/SETTLEMENT.md) |

## Rebuild from source

**Program and tests** (Linux or WSL; Rust stable, Solana CLI 4.x, Node 22):

```bash
cargo build-sbf --manifest-path programs/rise/Cargo.toml --sbf-out-dir target/deploy
npm ci
mkdir -p tests/fixtures && cp target/deploy/rise.so tests/fixtures/
TS_NODE_TRANSPILE_ONLY=1 npx ts-mocha -p ./tsconfig.json -t 1000000 tests/rise.ts
```

Six tests run on Bankrun with a controllable clock: faucet rate limit, settings validation, a full 3-member pact (early, late and missed mornings, closed windows, double clock-in, streaks, exact payouts, vault drained to zero), late joiners, the nobody-woke refund, and clock-in authorisation. The same steps run in [CI](.github/workflows/ci.yml) on every push.

**Android app** (JDK 21, Android SDK 36):

```bash
cd android
./gradlew :app:assembleDebug        # or assembleRelease with your own keystore
```

**Website and API:**

```bash
cd web && npm ci && npx vercel dev
```

## Reproduce the devnet demo

1. Install the APK from https://rise-clockin.vercel.app/rise.apk (or build it).
2. Connect a devnet wallet (Phantom: Settings → Developer settings → Testnet mode → Devnet) or use the practice wallet.
3. Tap **Get 500 test SKR**. The app tops up devnet SOL for fees through `/api/sol`, then calls the program's `drip`.
4. Set your wake time about 35 minutes from now, then join **Sunrise Club** (or start a pact). Your first morning is today if its window hasn't opened yet.
5. Once the window opens (30 minutes before your wake time), tap **Rehearse the alarm** on Today. Because a window is open, it's the real alarm: sunrise, the spoken stakes, a mission, then a clock-in that lands on chain. Tap **See the proof on Solana** on the stamp.

**Reproduce the settlement proof** (10-minute "mornings", about 35 minutes; needs a funded devnet keypair and test SKR in five keypairs under `keys/bots/`):

```bash
bash scripts/build-scripts.sh
RISE_ROOT=$(pwd) node scripts/dist/settlement-demo.js   # writes docs/SETTLEMENT.md
```

## Where each claim lives in the code

| Claim | Code |
| --- | --- |
| Wake window checked against Solana's clock | `programs/rise/src/lib.rs` → `clock_in` |
| Payout formula and conservation | `lib.rs` → `payout`, `claim`; tests in `tests/rise.ts` |
| Mobile Wallet Adapter signing (sign-and-send, fallback, re-auth) | `android/.../solana/Wallet.kt` |
| Seeker Genesis Token check | `web/api/seeker.js`, `android/.../data/Store.kt` → `verifySeeker` |
| Sensor missions | `android/.../ui/screens/Missions.kt` |
| On-device AI: vision mission | `Missions.kt` → `PhotoMission` (ML Kit image labeling) |
| On-device AI: Rise Coach | `android/.../ai/WakeCoach.kt` |
| On-device AI: proof anomaly scoring, on-chain encoding | `android/.../ai/ProofAnalyzer.kt` |
| Alarm, sunrise, spoken stakes | `android/.../alarm/AlarmService.kt`, `AlarmActivity.kt` |
| Security model | [SECURITY.md](SECURITY.md) |
