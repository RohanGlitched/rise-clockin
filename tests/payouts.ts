// Payouts and conservation.
//
//   pot      = total deposited - stake * total kept
//   payout_i = stake * kept_i + pot * kept_i / total kept      (integer division)
//   payout_i = deposit_i                                       when nobody kept a morning
//
// The property test drives the real program through random pacts (member counts, day lengths,
// late joins, wake times, hit rates, stakes, claim order) from a fixed seed and checks every
// payout against that formula, plus conservation: the vault ends holding only rounding dust,
// and dust is at most (members who kept a morning) - 1 base units.
//
// RISE_SEED=<n> RISE_TRIALS=<n> run a different or longer search.
import { Keypair } from "@solana/web3.js";
import { expect } from "chai";
import { DAY, DRIP, DRIP_COOLDOWN, HOUR, Harness, PactInfo, SKR, early, modelPayout, prng } from "./helpers/harness";

const SEED = Number(process.env.RISE_SEED ?? 20261004);
const TRIALS = Number(process.env.RISE_TRIALS ?? 40);

describe("payouts and conservation", () => {
  let h: Harness;
  before(async () => {
    h = await Harness.create();
  });

  type Plan = { k: Keypair; wake: number; firstDay: number; hits: boolean[] };

  /** Settles a pact and returns what each member received (claims in the given order). */
  async function settle(p: PactInfo, members: Keypair[], order = members) {
    await h.setTime(p.end + 1);
    const got = new Map<string, bigint>();
    for (const k of order) {
      const before = await h.balance(k.publicKey);
      h.ok(await h.claim(p, k));
      got.set(k.publicKey.toBase58(), (await h.balance(k.publicKey)) - before);
    }
    return members.map((k) => got.get(k.publicKey.toBase58())!);
  }

  /** Checks every payout against the formula, conservation and the dust bound. */
  async function checkSettlement(p: PactInfo, members: Keypair[], paid: bigint[]) {
    const pact = await h.fetchPact(p);
    const totalDeposited = BigInt(pact.totalDeposited.toString());
    const totalHits = BigInt(pact.totalHits);
    let sumPaid = 0n;
    let keepers = 0n;
    for (let i = 0; i < members.length; i++) {
      const m = await h.fetchMember(p, members[i].publicKey);
      const deposit = BigInt(m.deposit.toString());
      const hits = BigInt(m.hits);
      expect(m.claimed).to.equal(true);
      expect(paid[i], `member ${i}`).to.equal(modelPayout(p.stake, totalDeposited, totalHits, deposit, hits));
      expect(paid[i] >= p.stake * hits, "a member never gets back less than the stakes they kept").to.equal(true);
      if (hits === BigInt(p.days - m.firstDay)) expect(paid[i] >= deposit, "keeping every morning never loses money").to.equal(true);
      sumPaid += paid[i];
      if (hits > 0n) keepers++;
    }
    const dust = totalDeposited - sumPaid;
    expect(dust >= 0n, "paid out more than was deposited").to.equal(true);
    expect(dust <= (keepers > 0n ? keepers - 1n : 0n), `dust ${dust} with ${keepers} keepers`).to.equal(true);
    expect(await h.vaultBalance(p)).to.equal(dust);
    expect(BigInt(pact.totalPaid.toString())).to.equal(sumPaid);
    expect(pact.claims).to.equal(members.length);
    return { dust, totalDeposited, totalHits };
  }

  it(`property: ${TRIALS} random pacts settle exactly by the formula and conserve the vault (seed ${SEED})`, async () => {
    const rng = prng(SEED);
    const pool: Keypair[] = [];
    for (let i = 0; i < 12; i++) pool.push(await h.funded());
    let stats = { pacts: 0, members: 0, lateJoins: 0, clockIns: 0, refunds: 0, dust: 0n, deposited: 0n };

    let base = h.now + HOUR;
    for (let t = 0; t < TRIALS; t++) {
      // Keep every wallet able to cover a deposit (drips are hourly; trials are spaced 2 hours apart).
      await h.setTime(base);
      for (const k of pool) if ((await h.balance(k.publicKey)) < DRIP) h.ok(await h.drip(k));

      const n = rng.int(2, pool.length);
      const days = rng.chance(0.15) ? 1 : rng.int(2, 14);
      const daySecs = rng.pick([600, 3_600, 7_200, DAY, rng.int(600, DAY)]);
      const e = early(daySecs);
      const grace = rng.int(60, Math.min(3_600, daySecs - e - 1));
      const stake = BigInt(rng.pick([1, 7, 999_999, rng.int(1, 30) * Number(SKR), rng.int(1, 30_000_000)]));
      const pHit = rng.pick([0, 0.25, 0.5, 0.8, 1]);
      const start = base + daySecs;
      const p = await h.createPact({ start, daySecs, days, grace, stake, maxMembers: 500 });

      const members = [...pool].sort(() => rng.next() - 0.5).slice(0, n);
      const plans: Plan[] = members.map((k) => {
        const firstDay = days > 1 && rng.chance(0.3) ? rng.int(1, days - 1) : 0;
        const hits = Array.from({ length: days }, (_, d) => d >= firstDay && rng.chance(pHit));
        return { k, wake: rng.int(0, daySecs - 1), firstDay, hits };
      });

      // Build the timeline: joins (late ones just before their first window opens) and clock-ins.
      type Ev = { t: number; run: () => Promise<void> };
      const evs: Ev[] = [];
      for (const pl of plans) {
        const joinAt = pl.firstDay === 0 ? base + 1 : rng.int(p.opens(pl.wake, pl.firstDay - 1), p.opens(pl.wake, pl.firstDay) - 1);
        evs.push({
          t: joinAt,
          run: async () => {
            h.ok(await h.join(p, pl.k, pl.wake));
          },
        });
        pl.hits.forEach((hit, d) => {
          if (!hit) return;
          evs.push({
            t: rng.int(p.opens(pl.wake, d), p.closes(pl.wake, d)),
            run: async () => {
              h.ok(await h.clockIn(p, pl.k));
            },
          });
        });
      }
      evs.sort((a, b) => a.t - b.t);
      for (const ev of evs) {
        await h.setTime(ev.t);
        await ev.run();
      }

      // On-chain state matches the plan.
      for (const pl of plans) {
        const m = await h.fetchMember(p, pl.k.publicKey);
        expect(m.firstDay).to.equal(pl.firstDay);
        expect(BigInt(m.deposit.toString())).to.equal(stake * BigInt(days - pl.firstDay));
        expect(m.hits).to.equal(pl.hits.filter(Boolean).length);
      }

      const order = [...members].sort(() => rng.next() - 0.5);
      const paid = await settle(p, members, order);
      const r = await checkSettlement(p, members, paid);

      stats.pacts++;
      stats.members += n;
      stats.lateJoins += plans.filter((pl) => pl.firstDay > 0).length;
      stats.clockIns += Number(r.totalHits);
      stats.refunds += r.totalHits === 0n ? 1 : 0;
      stats.dust += r.dust;
      stats.deposited += r.totalDeposited;
      base = p.end + 2 * HOUR;
    }
    console.log(
      `      ${stats.pacts} pacts, ${stats.members} members (${stats.lateJoins} late), ${stats.clockIns} clock-ins, ` +
        `${stats.refunds} full refunds; ${stats.deposited} base units locked, ${stats.dust} left as dust`,
    );
  });

  /** Runs a pact where member i keeps the mornings marked 1 in patterns[i]. */
  async function runPattern(days: number, patterns: number[][], stake = 10n * SKR) {
    const p = await h.createPact({ start: h.now + HOUR, days, stake, maxMembers: 500 });
    const wakes = patterns.map((_, i) => 5 * HOUR + i * 600);
    const ms: Keypair[] = [];
    for (let i = 0; i < patterns.length; i++) {
      const k = await h.funded(Math.ceil(Number(stake * BigInt(days)) / Number(DRIP)) || 1);
      h.ok(await h.join(p, k, wakes[i]));
      ms.push(k);
    }
    for (let d = 0; d < days; d++) {
      for (let i = 0; i < ms.length; i++) {
        if (patterns[i][d]) h.ok(await h.clockInAt(p, ms[i], p.target(wakes[i], d) - 60));
      }
    }
    const paid = await settle(p, ms);
    await checkSettlement(p, ms, paid);
    await h.setTime(p.end + DRIP_COOLDOWN);
    return { p, ms, paid };
  }

  it("3-morning pact (shortest app preset): 30 kept + 40 * 3/5 = 54, 18, 18, vault 0", async () => {
    const { paid } = await runPattern(3, [[1, 1, 1], [1, 0, 0], [0, 1, 0]]);
    expect(paid).to.deep.equal([54n * SKR, 18n * SKR, 18n * SKR]);
  });

  it("30-morning pact (longest app preset) with mixed records settles exactly", async () => {
    const r = prng(30);
    const pat = (p: number) => Array.from({ length: 30 }, () => (r.chance(p) ? 1 : 0));
    const { paid, ms } = await runPattern(30, [pat(0.95), pat(0.7), pat(0.4), pat(0.1)], 2n * SKR);
    expect(paid.length).to.equal(ms.length);
    expect(paid[0] > paid[3]).to.equal(true);
  });

  it("1-morning and 64-morning pacts (the program's limits) settle exactly", async () => {
    await runPattern(1, [[1], [0]]);
    const all = Array(64).fill(1);
    const odd = Array.from({ length: 64 }, (_, d) => d % 2);
    const { p, ms } = await runPattern(64, [all, odd], 1n * SKR);
    const m = await h.fetchMember(p, ms[0].publicKey);
    expect([m.hits, m.streak, m.bestStreak]).to.deep.equal([64, 64, 64]);
  });

  it("nobody kept a morning: everyone, late joiners included, gets exactly their deposit (no division by zero)", async () => {
    const p = await h.createPact({ start: h.now + HOUR, days: 4 });
    const a = await h.funded();
    const b = await h.funded();
    h.ok(await h.join(p, a, 6 * HOUR));
    await h.setTime(p.opens(6 * HOUR, 2) - 1);
    h.ok(await h.join(p, b, 6 * HOUR));
    const paid = await settle(p, [a, b]);
    expect(paid).to.deep.equal([4n * p.stake, 2n * p.stake]);
    expect(await h.vaultBalance(p)).to.equal(0n);
  });

  it("everyone kept every morning: each gets exactly their deposit, nothing left over", async () => {
    const { paid } = await runPattern(5, [[1, 1, 1, 1, 1], [1, 1, 1, 1, 1], [1, 1, 1, 1, 1]], 3n * SKR);
    expect(paid).to.deep.equal([15n * SKR, 15n * SKR, 15n * SKR]);
  });

  it("a member who kept nothing gets 0 while others kept mornings, and can't claim twice", async () => {
    const { p, ms, paid } = await runPattern(2, [[1, 1], [0, 0]]);
    expect(paid).to.deep.equal([40n * SKR, 0n]);
    h.fails(await h.claim(p, ms[1]), "AlreadyClaimed");
    h.fails(await h.claim(p, ms[0]), "AlreadyClaimed");
  });

  it("rounding dust stays in the vault: stake of 1 base unit, three keepers share a pot of 5", async () => {
    // 4 members x 2 mornings x 1 = 8 locked; three keep one morning each, one keeps none.
    // pot = 8 - 3 = 5; each keeper gets 1 + floor(5/3) = 2; 6 paid, 2 base units left.
    const { p, paid } = await runPattern(2, [[1, 0], [0, 1], [1, 0], [0, 0]], 1n);
    expect(paid).to.deep.equal([2n, 2n, 2n, 0n]);
    expect(await h.vaultBalance(p)).to.equal(2n); // nothing can ever sweep it
  });

  it("an oversized stake is refused with Overflow instead of wrapping", async () => {
    const p = await h.createPact({ start: h.now + HOUR, days: 3, stake: 2n ** 63n });
    h.fails(await h.join(p, await h.funded(), 6 * HOUR), "Overflow");
    await h.setTime(p.end + 1);
  });
});
