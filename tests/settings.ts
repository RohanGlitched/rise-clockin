// create_pact and join input validation, each limit tested on both sides.
import { expect } from "chai";
import { createAssociatedTokenAccountIdempotentInstruction, TOKEN_2022_PROGRAM_ID } from "@solana/spl-token";
import { DAY, HOUR, Harness, SKR } from "./helpers/harness";

describe("pact settings", () => {
  let h: Harness;
  before(async () => {
    h = await Harness.create();
  });

  const refuses = async (o: Parameters<Harness["tryCreatePact"]>[0], code: string) => h.fails((await h.tryCreatePact(o)).r, code);
  const accepts = async (o: Parameters<Harness["tryCreatePact"]>[0]) => h.ok((await h.tryCreatePact(o)).r);

  it("days: 0 and 65 refused (BadDays); 1 and 64 accepted", async () => {
    await refuses({ days: 0 }, "BadDays");
    await refuses({ days: 65 }, "BadDays");
    await accepts({ days: 1 });
    await accepts({ days: 64 });
  });

  it("stake: 0 refused (BadStake); 1 base unit accepted", async () => {
    await refuses({ stake: 0n }, "BadStake");
    await accepts({ stake: 1n });
  });

  it("day length: 599 s refused (BadDayLength); 600 s accepted", async () => {
    await refuses({ daySecs: 599, grace: 60 }, "BadDayLength");
    await accepts({ daySecs: 600, grace: 60 });
  });

  it("grace: 59 s and 3601 s refused (BadGrace); 60 s and 3600 s accepted", async () => {
    await refuses({ grace: 59 }, "BadGrace");
    await refuses({ grace: 3601 }, "BadGrace");
    await accepts({ grace: 60 });
    await accepts({ grace: 3600 });
  });

  it("grace must leave a gap before the next window: early + grace < day length", async () => {
    // 600 s days open 150 s early, so grace can be at most 449 s
    await refuses({ daySecs: 600, grace: 450 }, "BadGrace");
    await accepts({ daySecs: 600, grace: 449 });
    // 5400 s days: early 1350 + grace 3600 < 5400 is fine; 4049 s days are not
    await accepts({ daySecs: 5400, grace: 3600 });
    await refuses({ daySecs: 4049, grace: 3600 }, "BadGrace");
  });

  it("max members: 1 and 501 refused (BadMembers); 2 and 500 accepted", async () => {
    await refuses({ maxMembers: 1 }, "BadMembers");
    await refuses({ maxMembers: 501 }, "BadMembers");
    await accepts({ maxMembers: 2 });
    await accepts({ maxMembers: 500 });
  });

  it("start: up to one day in the past is accepted; one second more is StartInPast", async () => {
    await refuses({ start: h.now - DAY - 1 }, "StartInPast");
    await accepts({ start: h.now - DAY });
  });

  it("pact name: empty, blank and 33 bytes refused (BadName); 32 bytes accepted", async () => {
    await refuses({ name: "" }, "BadName");
    await refuses({ name: "   " }, "BadName");
    await refuses({ name: "x".repeat(33) }, "BadName");
    await accepts({ name: "x".repeat(32) });
  });

  it("the same creator and seed can't open a second pact", async () => {
    await accepts({ seed: 4242 });
    const { r } = await h.tryCreatePact({ seed: 4242 });
    h.fails(r, "already in use");
  });

  it("informational: anyone can pre-create a pact's vault and block that one seed", async () => {
    // The vault is `init`, and its address is derivable from (creator, seed). A griefer who
    // guesses the seed can create the vault first; the creator just retries with another seed.
    const seed = 777;
    const pact = h.pactPda(h.admin.publicKey, seed);
    const griefer = h.wallet();
    h.ok(await h.send([createAssociatedTokenAccountIdempotentInstruction(griefer.publicKey, h.ata(pact), pact, h.mint, TOKEN_2022_PROGRAM_ID)], [griefer]));
    h.fails((await h.tryCreatePact({ seed })).r, "Provided owner is not allowed"); // the ATA program refuses to create it again
    await accepts({ seed: seed + 1 });
  });

  it("informational: day length and start time have no upper bound", async () => {
    // A pact with u32::MAX-second days starting in ten years is accepted. Anyone who joins it
    // locks their stake until it ends, thousands of years from now. The app always uses 24 h days.
    const p = await h.createPact({ start: h.now + 10 * 365 * DAY, daySecs: 4_294_967_295, days: 64, stake: 1n * SKR });
    const yearsLocked = (p.end - h.now) / (365 * DAY);
    expect(yearsLocked).to.be.greaterThan(8_000);
    h.ok(await h.join(p, await h.funded(), 6 * HOUR));
  });

  describe("join", () => {
    it("member name: empty, blank and 21 bytes refused (BadName); 20 bytes accepted", async () => {
      const p = await h.createPact({ start: h.now + HOUR });
      for (const name of ["", "  ", "y".repeat(21)]) h.fails(await h.join(p, await h.funded(), 6 * HOUR, name), "BadName");
      h.ok(await h.join(p, await h.funded(), 6 * HOUR, "y".repeat(20)));
    });

    it("member name limit is bytes, not characters: 7 Devanagari letters (21 bytes) are refused", async () => {
      // The app trims names to 20 characters, so a long non-Latin name fails to join.
      const p = await h.createPact({ start: h.now + HOUR });
      const name = "रोहनसिंह".slice(0, 7);
      expect(name.length).to.equal(7);
      expect(Buffer.byteLength(name)).to.equal(21);
      h.fails(await h.join(p, await h.funded(), 6 * HOUR, name), "BadName");
    });

    it("refuses a deposit the wallet can't cover", async () => {
      const p = await h.createPact({ start: h.now + HOUR, days: 64, stake: 10n * SKR }); // 640 SKR
      h.fails(await h.join(p, await h.funded(), 6 * HOUR), "insufficient funds");
    });
  });
});
