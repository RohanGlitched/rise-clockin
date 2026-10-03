# Rise

**The alarm your friends are betting on.** Rise is an Android alarm for Solana Seeker. Every morning you finish a short mission (find daylight, walk 30 steps, or scan the code by your kettle) and clock in on Solana before your wake window closes. Sleep in, and that morning's SKR goes to the pot, which is split among the friends who made it.

- Android app: native Kotlin and Jetpack Compose, Mobile Wallet Adapter (Seed Vault, Phantom, Solflare) or a built-in practice wallet
- Solana program: Anchor, deployed on devnet at [`6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS`](https://explorer.solana.com/address/6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS?cluster=devnet)
- Test SKR: a Token-2022 mint with on-chain metadata, [`SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38`](https://explorer.solana.com/address/SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38?cluster=devnet), dripped by the program's own faucet
- Website: [rise-clockin.vercel.app](https://rise-clockin.vercel.app) — live time cards read from devnet, invite links, APK download

## How a morning works

1. **Sunrise.** A few minutes before your wake time the alarm takes over the lock screen and the screen glows warmer and brighter, like a sunrise lamp.
2. **Ring.** At your wake time the alarm climbs from a gentle chime to full volume and reads the stakes out loud: who in your pact is already up, and how much SKR is on the line.
3. **Mission.** Slide the sun to start your mission. The alarm only stops when the phone's sensors agree you're up: the light sensor reads daylight, the step counter reaches 30, or the camera scans your wake-spot code.
4. **Clock in.** Sign once. The Rise program checks Solana's clock: the clock-in counts only between 30 minutes before your wake time and the end of the grace period.
5. **Stamp.** Your time is punched onto the pact's time card in blue-black ink (on time) or red (late). Missed mornings show as a hole punched clean through the card.

## The pot

Each member locks `stake × mornings`. A morning you keep earns your stake back; a missed one stays in the pot. When the pact ends:

```
pot      = total deposited − stake × (mornings kept by everyone)
payout_i = stake × kept_i + pot × kept_i / (mornings kept by everyone)
```

Payouts always sum to what was deposited, so the vault can pay every claim. If nobody wakes up at all, everyone gets their deposit back. Late joiners lock stakes only for the mornings left.

## What makes it Seeker-native

- **Seed Vault signing** through Mobile Wallet Adapter, with Sign in with Solana.
- **Seeker Genesis Token check:** the Rise server verifies the SIWS signature and looks for an SGT in that wallet on mainnet (mint authority, metadata pointer and group membership must all match).
- **SKR is the stake and the reward** of every pact. No staking: SKR moves between friends based on who gets up.
- **Phone hardware is the proof of waking:** ambient light sensor, step detector, camera with on-device QR scanning (ML Kit), exact alarms, lock-screen full-screen intent, text-to-speech.

## Repository

```
programs/rise/        Anchor program (pacts, wake windows, clock-in, payouts, test-SKR faucet)
tests/rise.ts         Program tests on Bankrun with a controllable clock
android/              Kotlin + Jetpack Compose app
web/                  Vercel site: landing page, invite pages, devnet SOL faucet, Seeker check
scripts/              Devnet setup, seeding, and the early risers who keep the demo pacts alive
```

## Build and test

```bash
# Program (inside WSL / Linux)
anchor build
bash scripts/test.sh                 # 6 tests: faucet, validation, full pact, late join, refund, auth

# Android
cd android && ./gradlew :app:assembleRelease

# Point a debug build at a local validator
bash scripts/localnet.sh &
bash scripts/local-setup.sh
cd android && ./gradlew :app:assembleDebug -Prpc=http://10.0.2.2:8899
```

## Program accounts

| Account | Seeds | Holds |
| --- | --- | --- |
| `Pact` | `pact, creator, seed` | name, stake per morning, start, day length, mornings, grace, totals |
| `Member` | `member, pact, owner` | name, sign, wake offset, first morning, deposit, streaks, one clock-in record per morning |
| Vault | ATA of the pact | the locked SKR |
| `Faucet`, `DripTicket` | `faucet`, `drip, user` | test-SKR faucet state, hourly limit per wallet |

Instructions: `create_pact`, `join`, `clock_in`, `claim`, `drip`, `init_faucet`.
