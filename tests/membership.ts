// Who can join, how many, and who can act for whom.
import { Keypair } from "@solana/web3.js";
import { createAssociatedTokenAccountIdempotentInstruction, TOKEN_2022_PROGRAM_ID } from "@solana/spl-token";
import { expect } from "chai";
import { HOUR, Harness, SKR } from "./helpers/harness";

describe("membership and authorisation", () => {
  let h: Harness;
  const W = 6 * HOUR;
  before(async () => {
    h = await Harness.create();
  });

  it("max members: a 500-seat pact takes 500 joins and refuses the 501st (PactFull)", async () => {
    const p = await h.createPact({ start: h.now + 2 * HOUR, maxMembers: 500, days: 1, stake: 1n * SKR });
    for (let i = 0; i < 500; i++) h.ok(await h.join(p, await h.funded(), (i * 97) % 86_400));
    const pact = await h.fetchPact(p);
    expect(pact.memberCount).to.equal(500);
    expect(BigInt(pact.totalDeposited.toString())).to.equal(500n * SKR);
    expect(await h.vaultBalance(p)).to.equal(500n * SKR);
    h.fails(await h.join(p, await h.funded(), W), "PactFull");
    await h.setTime(p.end + 1);
  });

  it("a 2-seat pact refuses the third member (PactFull)", async () => {
    const p = await h.createPact({ start: h.now + HOUR, maxMembers: 2 });
    h.ok(await h.join(p, await h.funded(), W));
    h.ok(await h.join(p, await h.funded(), W));
    h.fails(await h.join(p, await h.funded(), W), "PactFull");
  });

  it("refuses a second join by the same wallet, even with a different wake time", async () => {
    const p = await h.createPact({ start: h.now + HOUR });
    const a = await h.funded();
    h.ok(await h.join(p, a, W));
    h.fails(await h.join(p, a, W + 600), "already in use");
    expect((await h.fetchPact(p)).memberCount).to.equal(1);
    expect(BigInt((await h.fetchMember(p, a.publicKey)).deposit.toString())).to.equal(3n * p.stake);
  });

  it("informational: a pact created with public = false is still joinable by anyone with its address", async () => {
    // `public` only decides whether the app lists the pact; it is not an access control.
    const p = await h.createPact({ start: h.now + HOUR, isPublic: false });
    h.ok(await h.join(p, await h.funded(), W));
  });

  it("refuses a join that deposits a different mint than the pact's (has_one = mint)", async () => {
    const p = await h.createPact({ start: h.now + HOUR });
    const fake = await h.createMint();
    const a = h.wallet();
    await h.mintTo(fake, a.publicKey, 1_000n * SKR);
    // Even with a vault for the fake mint in place, the pact's own mint is enforced.
    h.ok(await h.send([createAssociatedTokenAccountIdempotentInstruction(a.publicKey, h.ata(p.address, fake), p.address, fake, TOKEN_2022_PROGRAM_ID)], [a]));
    h.fails(await h.join({ ...p, mint: fake }, a, W), "ConstraintHasOne");
  });

  describe("acting for someone else", () => {
    let p: Awaited<ReturnType<Harness["createPact"]>>;
    let q: Awaited<ReturnType<Harness["createPact"]>>;
    let alice: Keypair, bob: Keypair, eve: Keypair;
    before(async () => {
      p = await h.createPact({ start: h.now + HOUR, days: 1 });
      q = await h.createPact({ start: h.now + HOUR, days: 1 });
      alice = await h.funded();
      bob = await h.funded();
      eve = await h.funded();
      h.ok(await h.join(p, alice, W));
      h.ok(await h.join(p, bob, W));
      h.ok(await h.join(q, eve, W));
    });

    it("nobody can clock in with another member's account", async () => {
      await h.setTime(p.target(W, 0));
      const ix = await h.clockInIx(p.address, eve.publicKey, 0, h.memberPda(p.address, alice.publicKey));
      const r = await h.send([ix], [eve]);
      expect(r.ok).to.equal(false);
      expect(r.logs.join("\n")).to.match(/ConstraintSeeds|ConstraintHasOne/);
      expect((await h.fetchMember(p, alice.publicKey)).hits).to.equal(0);
    });

    it("a member account from one pact can't clock in to another pact", async () => {
      const ix = await h.clockInIx(p.address, eve.publicKey, 0, h.memberPda(q.address, eve.publicKey));
      const r = await h.send([ix], [eve]);
      expect(r.ok).to.equal(false);
      expect(r.logs.join("\n")).to.match(/ConstraintSeeds|ConstraintHasOne/);
      expect((await h.fetchPact(p)).totalHits).to.equal(0);
    });

    it("a non-member can't claim from a pact", async () => {
      h.ok(await h.clockIn(p, alice));
      await h.setTime(p.end + 1);
      h.fails(await h.claim(p, eve), "AccountNotInitialized");
    });

    it("a claim can't be pointed at another pact's vault", async () => {
      const ix = await h.program.methods
        .claim()
        .accountsPartial({ owner: alice.publicKey, pact: p.address, mint: p.mint, tokenProgram: TOKEN_2022_PROGRAM_ID, vault: q.vault })
        .instruction();
      const r = await h.send([ix], [alice]);
      expect(r.ok).to.equal(false);
      expect(r.logs.join("\n")).to.match(/ConstraintAssociated|ConstraintTokenOwner|ConstraintSeeds/);
      expect(await h.vaultBalance(q)).to.equal(q.stake);
    });

    it("a claim pays only the signer's own token account", async () => {
      const ix = await h.program.methods
        .claim()
        .accountsPartial({ owner: alice.publicKey, pact: p.address, mint: p.mint, tokenProgram: TOKEN_2022_PROGRAM_ID, userAta: h.ata(bob.publicKey) })
        .instruction();
      const r = await h.send([ix], [alice]);
      expect(r.ok).to.equal(false);
      expect(r.logs.join("\n")).to.match(/ConstraintAssociated|ConstraintTokenOwner|ConstraintSeeds/);
      // and then the honest claims settle the pact: alice kept, bob didn't
      h.ok(await h.claim(p, alice));
      h.ok(await h.claim(p, bob));
      expect(await h.vaultBalance(p)).to.equal(0n);
    });
  });

  describe("rent", () => {
    it("the creator pays for the pact and vault, the joiner for their member account; nothing is ever closed", async () => {
      const rent = await h.ctx.banksClient.getRent();
      const fee = 5_000n;
      const creator = h.wallet();
      const before = await h.lamports(creator.publicKey);
      const p = await h.createPact({ start: h.now + HOUR, days: 1, creator });
      const pactAcc = (await h.ctx.banksClient.getAccount(p.address))!;
      const vaultAcc = (await h.ctx.banksClient.getAccount(p.vault))!;
      expect(pactAcc.data.length).to.equal(8 + 32 + 8 + (4 + 32) + 32 + 8 + 8 + 4 + 2 + 2 + 2 + 2 + 1 + 8 + 4 + 8 + 2 + 1);
      expect(before - (await h.lamports(creator.publicKey))).to.equal(
        rent.minimumBalance(BigInt(pactAcc.data.length)) + rent.minimumBalance(BigInt(vaultAcc.data.length)) + fee,
      );

      const a = await h.funded();
      const b = await h.funded();
      const aBefore = await h.lamports(a.publicKey);
      h.ok(await h.join(p, a, W));
      h.ok(await h.join(p, b, W));
      const memberAcc = (await h.ctx.banksClient.getAccount(h.memberPda(p.address, a.publicKey)))!;
      expect(memberAcc.data.length).to.equal(8 + 32 + 32 + (4 + 20) + 1 + 4 + 2 + 8 + 2 + 2 + 2 + 4 + 1 + 8 + 2 * 64 + 1); // 259
      expect(aBefore - (await h.lamports(a.publicKey))).to.equal(rent.minimumBalance(259n) + fee);

      await h.setTime(p.end + 1);
      h.ok(await h.claim(p, a));
      h.ok(await h.claim(p, b));
      // No close instruction exists: the member, pact and vault accounts (and their rent) stay.
      for (const addr of [p.address, p.vault, h.memberPda(p.address, a.publicKey)]) {
        expect(await h.ctx.banksClient.getAccount(addr)).to.not.equal(null);
      }
    });
  });
});
