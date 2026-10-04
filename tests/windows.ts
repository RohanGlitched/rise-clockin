// Wake windows, to the second.
//
// A member's window on pact day d is [target - early, target + grace], where
// target = start + d * day_secs + wake_offset and early = min(30 min, day_secs / 4).
// Every edge is tested from both sides, plus late joins, the end of the pact and claim timing.
import { Keypair } from "@solana/web3.js";
import { expect } from "chai";
import { DAY, EARLY, HOUR, Harness, NOT_IN, PactInfo, SKR } from "./helpers/harness";

describe("wake windows", () => {
  let h: Harness;
  const W = 6 * HOUR; // 06:00 after the pact's day start

  before(async () => {
    h = await Harness.create();
  });

  /** A fresh pact starting an hour from now, plus `n` funded members who joined before it started. */
  async function pactWith(n: number, wakes: number[] = [], opts: Parameters<Harness["createPact"]>[0] = {}) {
    const p = await h.createPact({ start: h.now + HOUR, ...opts });
    const ms: Keypair[] = [];
    for (let i = 0; i < n; i++) {
      const k = await h.funded();
      h.ok(await h.join(p, k, wakes[i] ?? W));
      ms.push(k);
    }
    return { p, ms };
  }
  /** Moves the clock past the pact so the next test starts clean. */
  async function after(p: PactInfo) {
    await h.setTime(p.end + HOUR);
  }

  it("opens exactly 30 minutes before the wake time: wake-30min counts, one second earlier is refused", async () => {
    const { p, ms: [a] } = await pactWith(1);
    h.fails(await h.clockInAt(p, a, p.opens(W, 0) - 1), "WindowClosed");
    h.ok(await h.clockInAt(p, a, p.opens(W, 0)));
    expect(p.opens(W, 0)).to.equal(p.target(W, 0) - EARLY);
    const m = await h.fetchMember(p, a.publicKey);
    expect(m.offsets[0]).to.equal(-EARLY);
    await after(p);
  });

  it("closes exactly at wake + grace: the last second counts, one second later is refused", async () => {
    const { p, ms: [a] } = await pactWith(1, [], { grace: 600 });
    h.fails(await h.clockInAt(p, a, p.closes(W, 0) + 1), "WindowClosed");
    h.ok(await h.clockInAt(p, a, p.closes(W, 1)));
    const m = await h.fetchMember(p, a.publicKey);
    expect(m.offsets[0]).to.equal(NOT_IN);
    expect(m.offsets[1]).to.equal(600);
    await after(p);
  });

  it("is closed between one day's grace end and the next day's opening", async () => {
    const { p, ms: [a] } = await pactWith(1);
    for (const t of [p.closes(W, 0) + 1, p.closes(W, 0) + 3 * HOUR, p.opens(W, 1) - 1]) {
      h.fails(await h.clockInAt(p, a, t), "WindowClosed");
    }
    h.ok(await h.clockInAt(p, a, p.opens(W, 1)));
    await after(p);
  });

  it("refuses a second clock-in in the same window, even at its last second (AlreadyIn)", async () => {
    const { p, ms: [a] } = await pactWith(1);
    h.ok(await h.clockInAt(p, a, p.opens(W, 0)));
    h.fails(await h.clockInAt(p, a, p.opens(W, 0) + 1), "AlreadyIn");
    h.fails(await h.clockInAt(p, a, p.closes(W, 0)), "AlreadyIn");
    expect((await h.fetchMember(p, a.publicKey)).hits).to.equal(1);
    expect((await h.fetchPact(p)).totalHits).to.equal(1);
    await after(p);
  });

  it("wake offset 0 opens day 0's window 30 minutes before the pact starts", async () => {
    const { p, ms: [a] } = await pactWith(1, [0]);
    h.fails(await h.clockInAt(p, a, p.start - EARLY - 1), "WindowClosed");
    h.ok(await h.clockInAt(p, a, p.start - EARLY));
    expect((await h.fetchMember(p, a.publicKey)).offsets[0]).to.equal(-EARLY);
    await after(p);
  });

  it("refuses a wake offset equal to the day length (BadWake) and accepts day_secs - 1", async () => {
    const p = await h.createPact({ start: h.now + HOUR });
    h.fails(await h.join(p, await h.funded(), DAY), "BadWake");
    h.ok(await h.join(p, await h.funded(), DAY - 1));
    await after(p);
  });

  it("latest wake time: the final window closes one second before end_ts; claim at close, at end_ts and at end_ts+1", async () => {
    const late = DAY - 1;
    const { p, ms: [a, b] } = await pactWith(2, [late, W], { days: 2 });
    expect(p.closes(late, 1)).to.equal(p.end - 1);
    h.ok(await h.clockInAt(p, a, p.closes(late, 1))); // the very last second any window is open
    h.fails(await h.claim(p, a), "NotOver"); // same second
    await h.setTime(p.end);
    h.fails(await h.claim(p, a), "NotOver");
    h.fails(await h.clockIn(p, b), "WindowClosed");
    await h.setTime(p.end + 1);
    const before = await h.balance(a.publicKey);
    h.ok(await h.claim(p, a));
    // a kept 1 of 2, b kept 0 of 2: pot = 40 - 10 = 30, all of it to a
    expect((await h.balance(a.publicKey)) - before).to.equal(10n * SKR + 30n * SKR);
  });

  it("claim exactly at the typical last window close (wake + grace on the final day) is refused", async () => {
    const { p, ms: [a] } = await pactWith(1, [], { days: 1 });
    h.ok(await h.clockInAt(p, a, p.target(W, 0)));
    for (const t of [p.closes(W, 0) - 1, p.closes(W, 0), p.closes(W, 0) + 1, p.end - 1, p.end]) {
      await h.setTime(t);
      h.fails(await h.claim(p, a), "NotOver");
    }
    await h.setTime(p.end + 1);
    h.ok(await h.claim(p, a));
  });

  it("refuses clock-ins after the last morning (NotYourDay)", async () => {
    const { p, ms: [a] } = await pactWith(1, [], { days: 2 });
    h.fails(await h.clockInAt(p, a, p.target(W, 2)), "NotYourDay");
    await after(p);
  });

  it("short days: the early window shrinks to a quarter of the day", async () => {
    // day_secs 600 -> early 150 s; grace must keep early + grace < 600
    const { p, ms: [a] } = await pactWith(1, [300], { daySecs: 600, grace: 60, days: 3 });
    h.fails(await h.clockInAt(p, a, p.target(300, 0) - 151), "WindowClosed");
    h.ok(await h.clockInAt(p, a, p.target(300, 0) - 150));
    h.fails(await h.clockInAt(p, a, p.target(300, 1) + 61), "WindowClosed");
    h.ok(await h.clockInAt(p, a, p.target(300, 2) + 60));
    // day_secs 7199 -> early 1799 s (one second under the 30 minute cap)
    const q = await pactWith(1, [HOUR], { daySecs: 7199, grace: 600, days: 2 });
    h.fails(await h.clockInAt(q.p, q.ms[0], q.p.target(HOUR, 0) - 1800), "WindowClosed");
    h.ok(await h.clockInAt(q.p, q.ms[0], q.p.target(HOUR, 0) - 1799));
    await after(q.p);
  });

  it("windows are fixed in UTC: a one-hour daylight-saving shift lands outside the window", async () => {
    // The app turns a local wake time into a UTC offset once, when you join. After a DST change
    // the phone's alarm rings an hour off; the chain still expects the original UTC instant.
    const { p, ms: [a, b] } = await pactWith(2, [], { grace: 600 });
    h.fails(await h.clockInAt(p, a, p.target(W, 0) + HOUR), "WindowClosed"); // clocks fell back
    h.fails(await h.clockInAt(p, b, p.target(W, 0) - HOUR), "WindowClosed"); // clocks sprang forward
    await after(p);
    // Only the maximum grace (60 min) still catches a fall-back alarm, on its very last second.
    const q = await pactWith(1, [], { grace: 3600 });
    h.ok(await h.clockInAt(q.p, q.ms[0], q.p.target(W, 0) + HOUR));
    await after(q.p);
  });

  describe("late joiners", () => {
    it("joining one second before today's window opens makes today the first morning; at the opening it is tomorrow", async () => {
      const { p } = await pactWith(1, [], { days: 4 });
      const early1 = await h.funded();
      const late1 = await h.funded();
      await h.setTime(p.opens(W, 1) - 1);
      h.ok(await h.join(p, early1, W));
      await h.setTime(p.opens(W, 1));
      h.ok(await h.join(p, late1, W));
      const e = await h.fetchMember(p, early1.publicKey);
      const l = await h.fetchMember(p, late1.publicKey);
      expect(e.firstDay).to.equal(1);
      expect(BigInt(e.deposit.toString())).to.equal(3n * p.stake);
      expect(l.firstDay).to.equal(2);
      expect(BigInt(l.deposit.toString())).to.equal(2n * p.stake);
      // early1 can use today's window; late1 joined after it opened, so today isn't theirs
      h.ok(await h.clockInAt(p, early1, p.target(W, 1)));
      h.fails(await h.clockIn(p, late1), "NotYourDay");
      await after(p);
    });

    it("a joiner with a later wake time still gets today", async () => {
      const { p } = await pactWith(1, [], { days: 3 });
      await h.setTime(p.closes(W, 0) + 1); // 06:10:01 on day 0, after a 06:00 window
      const owl = await h.funded();
      h.ok(await h.join(p, owl, 22 * HOUR));
      expect((await h.fetchMember(p, owl.publicKey)).firstDay).to.equal(0);
      await after(p);
    });

    it("on the final morning: one second before the window opens locks one stake and can clock in; at the opening it is PactOver", async () => {
      const days = 3;
      const { p } = await pactWith(1, [], { days });
      const last = days - 1;
      const j = await h.funded();
      const k = await h.funded();
      await h.setTime(p.opens(W, last) - 1);
      const before = await h.balance(j.publicKey);
      h.ok(await h.join(p, j, W));
      expect(before - (await h.balance(j.publicKey))).to.equal(p.stake);
      const m = await h.fetchMember(p, j.publicKey);
      expect(m.firstDay).to.equal(last);
      await h.setTime(p.opens(W, last));
      h.fails(await h.join(p, k, W), "PactOver");
      h.ok(await h.clockIn(p, j));
      await after(p);
    });

    it("refuses to join after the pact has ended (PactOver)", async () => {
      const { p } = await pactWith(1, [], { days: 1 });
      await h.setTime(p.end + 1);
      h.fails(await h.join(p, await h.funded(), W), "PactOver");
      h.fails(await h.join(p, await h.funded(), 0), "PactOver");
      h.fails(await h.join(p, await h.funded(), DAY - 1), "PactOver");
    });
  });

  describe("streaks", () => {
    it("counts consecutive mornings, resets on a gap and keeps the best run", async () => {
      const pattern = [1, 1, 0, 1, 1, 1, 0, 1];
      const { p, ms: [a] } = await pactWith(1, [], { days: pattern.length });
      const seen: number[] = [];
      for (let d = 0; d < pattern.length; d++) {
        if (!pattern[d]) continue;
        const r = h.ok(await h.clockInAt(p, a, p.target(W, d) + d)); // a different delta each day
        seen.push(Number((h.events(r)[0].data as any).streak));
      }
      expect(seen).to.deep.equal([1, 2, 1, 2, 3, 1]);
      const m = await h.fetchMember(p, a.publicKey);
      expect(m.streak).to.equal(1);
      expect(m.bestStreak).to.equal(3);
      expect(m.hits).to.equal(6);
      expect(m.lastHitDay).to.equal(7);
      expect(m.offsets.slice(0, pattern.length)).to.deep.equal([0, 1, NOT_IN, 3, 4, 5, NOT_IN, 7]);
      await after(p);
    });

    it("a late joiner's first clock-in starts a streak of 1", async () => {
      const { p } = await pactWith(1, [], { days: 4 });
      await h.setTime(p.closes(W, 1) + 1);
      const j = await h.funded();
      h.ok(await h.join(p, j, W));
      h.ok(await h.clockInAt(p, j, p.target(W, 2)));
      h.ok(await h.clockInAt(p, j, p.target(W, 3)));
      const m = await h.fetchMember(p, j.publicKey);
      expect([m.firstDay, m.streak, m.bestStreak]).to.deep.equal([2, 2, 2]);
      await after(p);
    });
  });
});
