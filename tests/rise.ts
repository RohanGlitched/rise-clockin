import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import { BankrunProvider } from "anchor-bankrun";
import { Clock, ProgramTestContext, start } from "solana-bankrun";
import {
  Keypair,
  LAMPORTS_PER_SOL,
  PublicKey,
  SystemProgram,
  Transaction,
} from "@solana/web3.js";
import {
  TOKEN_2022_PROGRAM_ID,
  ExtensionType,
  getMintLen,
  createInitializeMintInstruction,
  getAssociatedTokenAddressSync,
  unpackAccount,
} from "@solana/spl-token";
import { expect } from "chai";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";

const DAY = 86_400;
const SKR = 1_000_000; // 6 decimals
const STAKE = 10 * SKR;
const T0 = 1_790_000_000 - (1_790_000_000 % DAY); // a UTC midnight

describe("rise", () => {
  let ctx: ProgramTestContext;
  let provider: BankrunProvider;
  let slot = 1n;
  let program: Program<Rise>;
  const admin = Keypair.generate();
  const alice = Keypair.generate();
  const bob = Keypair.generate();
  const cara = Keypair.generate();
  let mint: PublicKey;

  const pda = (...seeds: (Buffer | Uint8Array)[]) =>
    PublicKey.findProgramAddressSync(seeds, program.programId)[0];
  const ata = (owner: PublicKey) =>
    getAssociatedTokenAddressSync(mint, owner, true, TOKEN_2022_PROGRAM_ID);
  const tokenAmount = async (addr: PublicKey) => {
    const acc = await ctx.banksClient.getAccount(addr);
    if (!acc) return 0n;
    return unpackAccount(addr, { ...acc, data: Buffer.from(acc.data) } as any, TOKEN_2022_PROGRAM_ID).amount;
  };
  const balance = (owner: PublicKey) => tokenAmount(ata(owner));
  const setTime = async (ts: number) => {
    slot += 1n;
    ctx.warpToSlot(slot);
    const c = await ctx.banksClient.getClock();
    ctx.setClock(new Clock(slot, c.epochStartTimestamp, c.epoch, c.leaderScheduleEpoch, BigInt(ts)));
  };
  const memberPda = (pact: PublicKey, owner: PublicKey) =>
    pda(Buffer.from("member"), pact.toBuffer(), owner.toBuffer());

  async function drip(user: Keypair) {
    await program.methods
      .drip()
      .accountsPartial({ user: user.publicKey, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .signers([user])
      .rpc();
  }

  async function createPact(seed: number, opts: Partial<{ days: number; start: number; grace: number }> = {}) {
    const pact = pda(Buffer.from("pact"), admin.publicKey.toBuffer(), new BN(seed).toArrayLike(Buffer, "le", 8));
    await program.methods
      .createPact(new BN(seed), "Morning crew", new BN(STAKE), new BN(opts.start ?? T0), DAY, opts.days ?? 3, opts.grace ?? 600, 10, true)
      .accountsPartial({ creator: admin.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .signers([admin])
      .rpc();
    return pact;
  }

  async function join(pact: PublicKey, user: Keypair, name: string, wake: number) {
    await program.methods
      .join(name, 1, wake)
      .accountsPartial({ owner: user.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .signers([user])
      .rpc();
  }

  async function clockIn(pact: PublicKey, user: Keypair) {
    await program.methods
      .clockIn(0)
      .accountsPartial({ owner: user.publicKey, pact, member: memberPda(pact, user.publicKey) })
      .signers([user])
      .rpc();
  }

  async function claim(pact: PublicKey, user: Keypair) {
    await program.methods
      .claim()
      .accountsPartial({ owner: user.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .signers([user])
      .rpc();
  }

  async function expectError(p: Promise<unknown>, code: string) {
    try {
      await p;
    } catch (e: any) {
      const text = String(e?.message ?? e) + JSON.stringify(e?.logs ?? e?.transactionLogs ?? "");
      expect(text).to.contain(code);
      return;
    }
    throw new Error(`expected ${code}`);
  }

  before(async () => {
    const programId = new PublicKey((idl as any).address);
    ctx = await start(
      [{ name: "rise", programId }],
      [admin, alice, bob, cara].map((k) => ({
        address: k.publicKey,
        info: { lamports: 10 * LAMPORTS_PER_SOL, data: Buffer.alloc(0), owner: SystemProgram.programId, executable: false },
      })),
    );
    provider = new BankrunProvider(ctx, new anchor.Wallet(admin));
    anchor.setProvider(provider);
    program = new Program<Rise>(idl as any, provider);
    await setTime(T0 - 3600);

    // Test SKR: a Token-2022 mint whose authority is the program's mint_auth PDA.
    const mintKp = Keypair.generate();
    mint = mintKp.publicKey;
    const mintAuth = pda(Buffer.from("mint_auth"));
    const len = getMintLen([]);
    const tx = new Transaction().add(
      SystemProgram.createAccount({
        fromPubkey: admin.publicKey,
        newAccountPubkey: mint,
        space: len,
        lamports: Number((await ctx.banksClient.getRent()).minimumBalance(BigInt(len))),
        programId: TOKEN_2022_PROGRAM_ID,
      }),
      createInitializeMintInstruction(mint, 6, mintAuth, null, TOKEN_2022_PROGRAM_ID),
    );
    await provider.sendAndConfirm!(tx, [admin, mintKp]);
    await program.methods
      .initFaucet()
      .accountsPartial({ payer: admin.publicKey, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .signers([admin])
      .rpc();
  });

  it("drips test SKR once an hour", async () => {
    await drip(alice);
    expect((await balance(alice.publicKey))).to.equal(500n * BigInt(SKR));
    await expectError(drip(alice), "DripTooSoon");
    await setTime(T0 - 3600 + 3601);
    await drip(alice);
    await drip(bob);
    await drip(cara);
    expect((await balance(alice.publicKey))).to.equal(1000n * BigInt(SKR));
  });

  it("validates pact settings", async () => {
    await expectError(createPact(90, { days: 0 }), "BadDays");
    await expectError(createPact(91, { days: 65 }), "BadDays");
    await expectError(createPact(92, { grace: 30 }), "BadGrace");
    await expectError(createPact(93, { start: T0 - 3 * DAY }), "StartInPast");
  });

  it("runs a full pact: windows, streaks, forfeits and payouts", async () => {
    const pact = await createPact(1, { days: 3 });
    // wake times: alice 06:00, bob 06:30, cara 07:00 (seconds after day start)
    await join(pact, alice, "Alice", 6 * 3600);
    await join(pact, bob, "Bob", 6.5 * 3600);
    await join(pact, cara, "Cara", 7 * 3600);
    const vault = getAssociatedTokenAddressSync(mint, pact, true, TOKEN_2022_PROGRAM_ID);
    let p = await program.account.pact.fetch(pact);
    expect(p.memberCount).to.equal(3);
    expect(p.totalDeposited.toNumber()).to.equal(9 * STAKE);

    // Day 0: window not open yet at 05:20 for alice
    await setTime(T0 + 5 * 3600 + 20 * 60);
    await expectError(clockIn(pact, alice), "WindowClosed");
    // alice 2 minutes early, bob 5 minutes late (inside 10 min grace), cara oversleeps
    await setTime(T0 + 6 * 3600 - 120);
    await clockIn(pact, alice);
    await expectError(clockIn(pact, alice), "AlreadyIn");
    await setTime(T0 + 6.5 * 3600 + 300);
    await clockIn(pact, bob);
    await setTime(T0 + 7 * 3600 + 601);
    await expectError(clockIn(pact, cara), "WindowClosed");

    const a = await program.account.member.fetch(memberPda(pact, alice.publicKey));
    expect(a.offsets[0]).to.equal(-120);
    expect(a.offsets[1]).to.equal(32767);
    const b = await program.account.member.fetch(memberPda(pact, bob.publicKey));
    expect(b.offsets[0]).to.equal(300);

    // Day 1: alice and cara in, bob misses
    await setTime(T0 + DAY + 6 * 3600);
    await clockIn(pact, alice);
    await setTime(T0 + DAY + 7 * 3600 + 10);
    await clockIn(pact, cara);
    // Day 2: alice only
    await setTime(T0 + 2 * DAY + 6 * 3600 + 30);
    await clockIn(pact, alice);

    const a2 = await program.account.member.fetch(memberPda(pact, alice.publicKey));
    expect(a2.hits).to.equal(3);
    expect(a2.streak).to.equal(3);
    expect(a2.bestStreak).to.equal(3);

    // Day 3 does not exist
    await setTime(T0 + 3 * DAY + 6 * 3600);
    await expectError(clockIn(pact, alice), "NotYourDay");
    await setTime(T0 + 3 * DAY + 599);
    await expectError(claim(pact, alice), "NotOver");

    // After the end: hits alice 3, bob 1, cara 1 => H = 5; pot = 90 - 50 = 40 SKR
    await setTime(T0 + 3 * DAY + 601);
    const before = await Promise.all([alice, bob, cara].map((k) => balance(k.publicKey)));
    await claim(pact, alice);
    await claim(pact, bob);
    await claim(pact, cara);
    await expectError(claim(pact, alice), "AlreadyClaimed");
    const after = await Promise.all([alice, bob, cara].map((k) => balance(k.publicKey)));
    const gained = after.map((a, i) => a - before[i]);
    expect(gained[0]).to.equal(BigInt(30 * SKR + 24 * SKR)); // 30 kept + 40*3/5
    expect(gained[1]).to.equal(BigInt(10 * SKR + 8 * SKR)); //  10 kept + 40*1/5
    expect(gained[2]).to.equal(BigInt(10 * SKR + 8 * SKR));
    const left = await tokenAmount(vault);
    expect(left).to.equal(0n);
    p = await program.account.pact.fetch(pact);
    expect(p.totalPaid.toNumber()).to.equal(9 * STAKE);
  });

  it("lets a late joiner in for the remaining days only", async () => {
    await setTime(T0 + 4 * DAY);
    const start = T0 + 4 * DAY;
    const pact = await createPact(2, { days: 5, start });
    await join(pact, alice, "Alice", 6 * 3600);
    // bob joins on day 1 at 09:00, after his 06:00 window: first day is day 2
    await setTime(start + DAY + 9 * 3600);
    await join(pact, bob, "Bob", 6 * 3600);
    const b = await program.account.member.fetch(memberPda(pact, bob.publicKey));
    expect(b.firstDay).to.equal(2);
    expect(b.deposit.toNumber()).to.equal(3 * STAKE);
    // cara joins day 1 at 09:00 with a 22:00 wake time: day 1 still counts
    await join(pact, cara, "Cara", 22 * 3600);
    const c = await program.account.member.fetch(memberPda(pact, cara.publicKey));
    expect(c.firstDay).to.equal(1);
    expect(c.deposit.toNumber()).to.equal(4 * STAKE);
  });

  it("refunds everyone when nobody wakes up", async () => {
    await setTime(T0 + 10 * DAY);
    const start = T0 + 10 * DAY;
    const pact = await createPact(3, { days: 2, start });
    await join(pact, alice, "Alice", 6 * 3600);
    await join(pact, bob, "Bob", 6 * 3600);
    await setTime(start + 2 * DAY + 601);
    const before = await balance(bob.publicKey);
    await claim(pact, bob);
    expect((await balance(bob.publicKey)) - before).to.equal(BigInt(2 * STAKE));
  });

  it("only the member can clock in for themselves", async () => {
    await setTime(T0 + 20 * DAY);
    const start = T0 + 20 * DAY;
    const pact = await createPact(4, { days: 2, start });
    await join(pact, alice, "Alice", 6 * 3600);
    await setTime(start + 6 * 3600);
    try {
      await program.methods
        .clockIn(0)
        .accountsPartial({ owner: bob.publicKey, pact, member: memberPda(pact, alice.publicKey) })
        .signers([bob])
        .rpc();
      throw new Error("should fail");
    } catch (e: any) {
      expect(String(e.message)).to.not.equal("should fail");
    }
  });
});
