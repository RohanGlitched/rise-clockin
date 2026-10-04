// Events and compute units.
import { Keypair } from "@solana/web3.js";
import { expect } from "chai";
import { HOUR, Harness, SendResult, fixedKey } from "./helpers/harness";
import idl from "../target/idl/rise.json";

describe("events and compute units", () => {
  let h: Harness;
  const W = 6 * HOUR;
  before(async () => {
    h = await Harness.create();
  });

  it("clock_in emits ClockedIn with pact, owner, day, delta, mission, streak and ts", async () => {
    const p = await h.createPact({ start: h.now + HOUR, days: 3 });
    const a = await h.funded();
    h.ok(await h.join(p, a, W));
    const mission = 0b1_10_01; // the app's encoding: mission 1, confidence 2, AI-vision flag
    for (const [day, delta, streak] of [[0, -95, 1], [1, 240, 2]]) {
      const ts = p.target(W, day) + delta;
      const r = h.ok(await h.clockInAt(p, a, ts, mission));
      const evs = h.events(r);
      expect(evs).to.have.length(1);
      expect(evs[0].name).to.match(/^[Cc]lockedIn$/);
      const e = evs[0].data as any;
      expect(e.pact.toBase58()).to.equal(p.address.toBase58());
      expect(e.owner.toBase58()).to.equal(a.publicKey.toBase58());
      expect(e.day).to.equal(day);
      expect(e.delta).to.equal(delta);
      expect(e.mission).to.equal(mission);
      expect(e.streak).to.equal(streak);
      expect(e.ts.toNumber()).to.equal(ts);
    }
    await h.setTime(p.end + 1);
  });

  it("informational: the IDL lists Joined, Claimed and PactCreated events, but no instruction emits them", async () => {
    const names = (idl as any).events.map((e: any) => e.name);
    expect(names).to.include.members(["ClockedIn", "Joined", "Claimed", "PactCreated"]);
    const { r: created, info: p } = await h.tryCreatePact({ start: h.now + HOUR, days: 1 });
    h.ok(created);
    const a = await h.funded();
    const joined = h.ok(await h.join(p, a, W));
    h.ok(await h.clockInAt(p, a, p.target(W, 0)));
    await h.setTime(p.end + 1);
    const claimed = h.ok(await h.claim(p, a));
    for (const r of [created, joined, claimed]) expect(h.events(r)).to.have.length(0);
  });

  it("compute-unit snapshot per instruction", async () => {
    // A separate bank with fixed keys: PDA bump searches cost compute and depend on the
    // addresses, so random keys would move these numbers by thousands between runs.
    const c = await Harness.create(undefined, { fixedKeys: true });
    const k = (n: number) => fixedKey(n);
    const rows: [string, SendResult][] = [];
    const fresh = c.wallet(10, k(1));
    const t0 = c.now;
    rows.push(["drip (first: creates ticket + token account)", c.ok(await c.drip(fresh))]);
    await c.setTime(t0 + 3_600);
    rows.push(["drip (repeat)", c.ok(await c.drip(fresh))]);
    const { r: created, info: p } = await c.tryCreatePact({ seed: 1, start: c.now + HOUR, days: 2 });
    rows.push(["create_pact (creates pact + vault)", c.ok(created)]);
    const ms: Keypair[] = [];
    for (let i = 0; i < 3; i++) ms.push(await c.funded(1, k(10 + i)));
    rows.push(["join", c.ok(await c.join(p, ms[0], W))]);
    c.ok(await c.join(p, ms[1], W));
    c.ok(await c.join(p, ms[2], W));
    rows.push(["clock_in (first morning)", c.ok(await c.clockInAt(p, ms[0], p.target(W, 0)))]);
    rows.push(["clock_in (streak continues)", c.ok(await c.clockInAt(p, ms[0], p.target(W, 1)))]);
    c.ok(await c.clockInAt(p, ms[1], p.target(W, 1) + 1));
    await c.setTime(p.end + 1);
    rows.push(["claim (pays kept stakes + pot share)", c.ok(await c.claim(p, ms[0]))]);
    rows.push(["claim (kept nothing, no transfer)", c.ok(await c.claim(p, ms[2]))]);
    const q = await c.createPact({ seed: 2, start: c.now + HOUR, days: 1 });
    const x = await c.funded(1, k(20));
    const y = await c.funded(1, k(21));
    c.ok(await c.join(q, x, W));
    c.ok(await c.join(q, y, W));
    await c.setTime(q.end + 1);
    rows.push(["claim (nobody kept: refund)", c.ok(await c.claim(q, x))]);

    console.log("\n      instruction                                      CU");
    for (const [name, r] of rows) console.log(`      ${name.padEnd(47)} ${String(r.cu).padStart(6)}`);

    // Ceilings leave room for bump-search variance with other keys; crossing one means an
    // instruction got much heavier. All are far below the 200,000 CU default per instruction.
    const ceilings: Record<string, number> = { drip: 100_000, create_pact: 80_000, join: 80_000, clock_in: 30_000, claim: 70_000 };
    for (const [name, r] of rows) expect(Number(r.cu), name).to.be.below(ceilings[name.split(" ")[0]]);
  });
});
