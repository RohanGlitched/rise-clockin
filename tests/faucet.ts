// The devnet test-SKR faucet: rate limit edges, mint binding and initialisation.
import { TOKEN_2022_PROGRAM_ID } from "@solana/spl-token";
import { expect } from "chai";
import { DRIP, DRIP_COOLDOWN, Harness } from "./helpers/harness";

describe("faucet", () => {
  let h: Harness;
  before(async () => {
    h = await Harness.create();
  });

  it("cooldown: a drip 3599 s after the last is refused (DripTooSoon); at 3600 s it pays", async () => {
    const a = h.wallet();
    const t = h.now;
    h.ok(await h.drip(a));
    expect(await h.balance(a.publicKey)).to.equal(DRIP);
    h.fails(await h.drip(a), "DripTooSoon"); // same second
    await h.setTime(t + DRIP_COOLDOWN - 1);
    h.fails(await h.drip(a), "DripTooSoon");
    await h.setTime(t + DRIP_COOLDOWN);
    h.ok(await h.drip(a));
    expect(await h.balance(a.publicKey)).to.equal(2n * DRIP);
    const ticket = await h.program.account.dripTicket.fetch(h.ticketPda(a.publicKey));
    expect(ticket.lastTs.toNumber()).to.equal(t + DRIP_COOLDOWN);
  });

  it("each wallet has its own ticket, and the faucet counts every drip", async () => {
    const before = (await h.program.account.faucet.fetch(h.faucet)).drips.toNumber();
    const a = h.wallet();
    const b = h.wallet();
    h.ok(await h.drip(a));
    h.ok(await h.drip(b)); // a's cooldown doesn't touch b
    h.fails(await h.drip(a), "DripTooSoon");
    expect((await h.program.account.faucet.fetch(h.faucet)).drips.toNumber()).to.equal(before + 2);
  });

  it("a wallet can't use another wallet's ticket to skip its cooldown", async () => {
    const a = h.wallet();
    const b = h.wallet();
    h.ok(await h.drip(a));
    const ix = await h.program.methods
      .drip()
      .accountsPartial({ user: b.publicKey, mint: h.mint, tokenProgram: TOKEN_2022_PROGRAM_ID, ticket: h.ticketPda(a.publicKey) })
      .instruction();
    h.fails(await h.send([ix], [b]), "ConstraintSeeds");
  });

  it("only the faucet's mint can be dripped, even another mint the program is authority of", async () => {
    const other = await h.createMint({ authority: h.mintAuth });
    const a = h.wallet();
    h.fails(await h.send([await h.dripIx(a.publicKey, other)], [a]), "ConstraintHasOne");
  });

  it("init_faucet runs once", async () => {
    h.fails(await h.initFaucet(h.admin, h.mint), "already in use");
  });

  describe("on a fresh deployment", () => {
    let f: Harness;
    before(async () => {
      f = await Harness.create(undefined, { faucet: false });
    });

    it("refuses a mint whose authority isn't the program's mint_auth PDA", async () => {
      const foreign = await f.createMint(); // authority: the admin wallet
      f.fails(await f.initFaucet(f.admin, foreign), "ConstraintMintMintAuthority");
    });

    it("informational: any wallet can be the one to call init_faucet first", async () => {
      // There is no admin check: whoever initialises first picks the faucet's mint (it still has
      // to be a mint whose authority is mint_auth). Devnet's faucet was initialised at deploy time.
      const stranger = f.wallet();
      f.ok(await f.initFaucet(stranger, f.mint));
      expect((await f.program.account.faucet.fetch(f.faucet)).mint.toBase58()).to.equal(f.mint.toBase58());
    });
  });
});
