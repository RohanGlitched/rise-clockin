// Shared Bankrun harness for the Rise program tests.
//
// Every test file starts its own Bankrun bank with the prebuilt program in tests/fixtures/rise.so,
// so files never share clock or account state. Transactions go straight to the bank (not through
// the Anchor provider) so each test can read the logs, the error code and the compute units.
import * as anchor from "@coral-xyz/anchor";
import { BN, BorshCoder, EventParser, Program } from "@coral-xyz/anchor";
import { BankrunProvider } from "anchor-bankrun";
import { Clock, ProgramTestContext, start } from "solana-bankrun";
import {
  Keypair,
  LAMPORTS_PER_SOL,
  PublicKey,
  SystemProgram,
  Transaction,
  TransactionInstruction,
} from "@solana/web3.js";
import {
  TOKEN_2022_PROGRAM_ID,
  ExtensionType,
  getMintLen,
  createInitializeMintInstruction,
  createAssociatedTokenAccountIdempotentInstruction,
  createMintToInstruction,
  getAssociatedTokenAddressSync,
  unpackAccount,
} from "@solana/spl-token";
import { expect } from "chai";
import { Rise } from "../../target/types/rise";
import idl from "../../target/idl/rise.json";

export const DAY = 86_400;
export const HOUR = 3_600;
export const SKR = 1_000_000n; // 6 decimals
export const EARLY = 30 * 60;
export const NOT_IN = 32_767;
export const DRIP = 500n * SKR;
export const DRIP_COOLDOWN = 3_600;
/** A UTC midnight, well in the future of Bankrun's genesis clock. */
export const T0 = 1_790_000_000 - (1_790_000_000 % DAY);

/** The program's `early()`: how long before the wake time a window opens. */
export const early = (daySecs: number) => Math.min(EARLY, Math.floor(daySecs / 4));

export type PactOpts = {
  seed?: number;
  name?: string;
  stake?: bigint;
  start?: number;
  daySecs?: number;
  days?: number;
  grace?: number;
  maxMembers?: number;
  isPublic?: boolean;
  mint?: PublicKey;
  tokenProgram?: PublicKey;
  creator?: Keypair;
};

export type PactInfo = {
  address: PublicKey;
  vault: PublicKey;
  mint: PublicKey;
  tokenProgram: PublicKey;
  stake: bigint;
  start: number;
  daySecs: number;
  days: number;
  grace: number;
  /** Start of the member's window for `day`. */
  opens: (wake: number, day: number) => number;
  /** The member's wake time on `day`. */
  target: (wake: number, day: number) => number;
  /** Last second the member's window on `day` still counts. */
  closes: (wake: number, day: number) => number;
  /** The program's end_ts: claims work only after this second. */
  end: number;
};

export type SendResult = {
  ok: boolean;
  err: string | null;
  logs: string[];
  cu: bigint;
};

/** mulberry32: a small seeded PRNG so property tests are reproducible. */
export function prng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    next,
    int: (lo: number, hi: number) => lo + Math.floor(next() * (hi - lo + 1)), // inclusive
    chance: (p: number) => next() < p,
    pick: <T>(xs: T[]) => xs[Math.floor(next() * xs.length)],
  };
}

export class Harness {
  ctx!: ProgramTestContext;
  provider!: BankrunProvider;
  program!: Program<Rise>;
  coder!: BorshCoder;
  admin = Keypair.generate();
  /** Test SKR: a Token-2022 mint whose authority is the program's mint_auth PDA. */
  mint!: PublicKey;
  now = 0;
  private slot = 1n;
  private seedCounter = 1;

  /** A fresh bank with test SKR and, unless `faucet` is false, an initialised faucet. */
  static async create(startTs = T0 - HOUR, o: { faucet?: boolean; fixedKeys?: boolean } = {}): Promise<Harness> {
    const h = new Harness();
    // Fixed keys make PDA bump searches, and so compute units, the same on every run.
    if (o.fixedKeys) h.admin = fixedKey(250);
    const programId = new PublicKey((idl as any).address);
    h.ctx = await start(
      [{ name: "rise", programId }],
      [h.admin].map((k) => ({
        address: k.publicKey,
        info: { lamports: 1_000 * LAMPORTS_PER_SOL, data: Buffer.alloc(0), owner: SystemProgram.programId, executable: false },
      })),
    );
    h.provider = new BankrunProvider(h.ctx, new anchor.Wallet(h.admin));
    anchor.setProvider(h.provider);
    h.program = new Program<Rise>(idl as any, h.provider);
    h.coder = new BorshCoder(idl as any);
    await h.setTime(startTs);
    h.mint = await h.createMint({ authority: h.mintAuth, keypair: o.fixedKeys ? fixedKey(251) : undefined });
    if (o.faucet !== false) h.ok(await h.initFaucet(h.admin, h.mint));
    return h;
  }

  async initFaucetIx(payer: PublicKey, mint: PublicKey) {
    return this.program.methods
      .initFaucet()
      .accountsPartial({ payer, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .instruction();
  }
  async initFaucet(payer: Keypair, mint: PublicKey) {
    return this.send([await this.initFaucetIx(payer.publicKey, mint)], [payer]);
  }

  get programId() {
    return this.program.programId;
  }
  get mintAuth() {
    return this.pda(Buffer.from("mint_auth"));
  }
  get faucet() {
    return this.pda(Buffer.from("faucet"));
  }

  pda(...seeds: (Buffer | Uint8Array)[]) {
    return PublicKey.findProgramAddressSync(seeds, this.programId)[0];
  }
  pactPda(creator: PublicKey, seed: number) {
    return this.pda(Buffer.from("pact"), creator.toBuffer(), new BN(seed).toArrayLike(Buffer, "le", 8));
  }
  memberPda(pact: PublicKey, owner: PublicKey) {
    return this.pda(Buffer.from("member"), pact.toBuffer(), owner.toBuffer());
  }
  ticketPda(user: PublicKey) {
    return this.pda(Buffer.from("drip"), user.toBuffer());
  }
  ata(owner: PublicKey, mint = this.mint, tokenProgram = TOKEN_2022_PROGRAM_ID) {
    return getAssociatedTokenAddressSync(mint, owner, true, tokenProgram);
  }

  /** Moves to a new slot and sets the bank's unix time. A new slot also means a new blockhash. */
  async setTime(ts: number) {
    this.slot += 1n;
    this.ctx.warpToSlot(this.slot);
    const c = await this.ctx.banksClient.getClock();
    this.ctx.setClock(new Clock(this.slot, c.epochStartTimestamp, c.epoch, c.leaderScheduleEpoch, BigInt(ts)));
    this.now = ts;
  }

  /** A wallet with SOL for fees and rent, created without a transaction. */
  wallet(sol = 10, keypair?: Keypair): Keypair {
    const k = keypair ?? Keypair.generate();
    this.ctx.setAccount(k.publicKey, {
      lamports: sol * LAMPORTS_PER_SOL,
      data: Buffer.alloc(0),
      owner: SystemProgram.programId,
      executable: false,
    });
    return k;
  }

  async lamports(addr: PublicKey) {
    const acc = await this.ctx.banksClient.getAccount(addr);
    return acc ? BigInt(acc.lamports) : 0n;
  }

  async tokenAmount(addr: PublicKey, tokenProgram = TOKEN_2022_PROGRAM_ID) {
    const acc = await this.ctx.banksClient.getAccount(addr);
    if (!acc) return 0n;
    return unpackAccount(addr, { ...acc, data: Buffer.from(acc.data) } as any, tokenProgram).amount;
  }
  balance(owner: PublicKey, mint = this.mint, tokenProgram = TOKEN_2022_PROGRAM_ID) {
    return this.tokenAmount(this.ata(owner, mint, tokenProgram), tokenProgram);
  }

  /**
   * Signs and processes a transaction in a fresh slot (same unix time), so two identical
   * instructions never collide as a duplicate signature. Never throws on a program error.
   */
  async send(ixs: TransactionInstruction[], signers: Keypair[]): Promise<SendResult> {
    await this.setTime(this.now);
    const tx = new Transaction();
    tx.recentBlockhash = (await this.ctx.banksClient.getLatestBlockhash())![0];
    tx.feePayer = signers[0].publicKey;
    tx.add(...ixs);
    tx.sign(...signers);
    const res = await this.ctx.banksClient.tryProcessTransaction(tx);
    const logs = res.meta?.logMessages ?? [];
    return { ok: res.result === null, err: res.result, logs, cu: res.meta?.computeUnitsConsumed ?? 0n };
  }

  ok(r: SendResult): SendResult {
    if (!r.ok) throw new Error(`transaction failed: ${r.err}\n${r.logs.join("\n")}`);
    return r;
  }

  /** Asserts the transaction failed and that `code` appears in its error or logs. */
  fails(r: SendResult, code: string) {
    expect(r.ok, `expected ${code}, but the transaction succeeded`).to.equal(false);
    expect(`${r.err}\n${r.logs.join("\n")}`).to.contain(code);
  }

  /** Anchor events in a transaction's logs. */
  events(r: SendResult) {
    const parser = new EventParser(this.programId, this.coder);
    return [...parser.parseLogs(r.logs)];
  }

  // ------------------------------------------------------------ tokens

  /** A Token-2022 mint with the given extensions (initialised by `init`), 6 decimals. */
  async createMint(o: {
    authority?: PublicKey;
    freeze?: PublicKey | null;
    exts?: ExtensionType[];
    init?: (m: PublicKey) => TransactionInstruction[];
    after?: (m: PublicKey) => TransactionInstruction[];
    extraLamportsSpace?: number;
    /** Account size, for extensions the JS library can't size. */
    len?: number;
    programId?: PublicKey;
    keypair?: Keypair;
  } = {}): Promise<PublicKey> {
    const kp = o.keypair ?? Keypair.generate();
    const programId = o.programId ?? TOKEN_2022_PROGRAM_ID;
    const len = o.len ?? getMintLen(o.exts ?? []);
    const rent = (await this.ctx.banksClient.getRent()).minimumBalance(BigInt(len + (o.extraLamportsSpace ?? 0)));
    const r = await this.send(
      [
        SystemProgram.createAccount({
          fromPubkey: this.admin.publicKey,
          newAccountPubkey: kp.publicKey,
          space: len,
          lamports: Number(rent),
          programId,
        }),
        ...(o.init ? o.init(kp.publicKey) : []),
        createInitializeMintInstruction(kp.publicKey, 6, o.authority ?? this.admin.publicKey, o.freeze ?? null, programId),
        ...(o.after ? o.after(kp.publicKey) : []),
      ],
      [this.admin, kp],
    );
    this.ok(r);
    return kp.publicKey;
  }

  /** Mints `amount` of an admin-controlled mint to `owner`'s associated account, creating it if needed. */
  async mintTo(mint: PublicKey, owner: PublicKey, amount: bigint, tokenProgram = TOKEN_2022_PROGRAM_ID) {
    const ata = this.ata(owner, mint, tokenProgram);
    this.ok(
      await this.send(
        [
          createAssociatedTokenAccountIdempotentInstruction(this.admin.publicKey, ata, owner, mint, tokenProgram),
          createMintToInstruction(mint, ata, this.admin.publicKey, amount, [], tokenProgram),
        ],
        [this.admin],
      ),
    );
    return ata;
  }

  /** A member's balance in a pact's own token. */
  pactBalance(p: PactInfo, owner: PublicKey) {
    return this.balance(owner, p.mint, p.tokenProgram);
  }
  vaultBalance(p: PactInfo) {
    return this.tokenAmount(p.vault, p.tokenProgram);
  }

  // ------------------------------------------------------------ instructions

  async dripIx(user: PublicKey, mint = this.mint) {
    return this.program.methods
      .drip()
      .accountsPartial({ user, mint, tokenProgram: TOKEN_2022_PROGRAM_ID })
      .instruction();
  }
  async drip(user: Keypair) {
    return this.send([await this.dripIx(user.publicKey)], [user]);
  }

  /** A wallet holding `drips` × 500 test SKR (one drip per hour, so the clock moves). */
  async funded(drips = 1, keypair?: Keypair): Promise<Keypair> {
    const k = this.wallet(10, keypair);
    for (let i = 0; i < drips; i++) {
      if (i > 0) await this.setTime(this.now + DRIP_COOLDOWN);
      this.ok(await this.drip(k));
    }
    return k;
  }

  pactInfo(o: Required<Pick<PactOpts, "stake" | "start" | "daySecs" | "days" | "grace">> & { address: PublicKey; mint: PublicKey; tokenProgram: PublicKey }): PactInfo {
    const e = early(o.daySecs);
    const target = (w: number, d: number) => o.start + d * o.daySecs + w;
    return {
      address: o.address,
      vault: this.ata(o.address, o.mint, o.tokenProgram),
      mint: o.mint,
      tokenProgram: o.tokenProgram,
      stake: o.stake,
      start: o.start,
      daySecs: o.daySecs,
      days: o.days,
      grace: o.grace,
      target,
      opens: (w, d) => target(w, d) - e,
      closes: (w, d) => target(w, d) + o.grace,
      end: o.start + o.days * o.daySecs + o.grace,
    };
  }

  async createPactIx(o: PactOpts = {}) {
    const creator = o.creator ?? this.admin;
    const seed = o.seed ?? this.seedCounter++;
    const args = {
      name: o.name ?? "Morning crew",
      stake: o.stake ?? 10n * SKR,
      start: o.start ?? this.now + HOUR,
      daySecs: o.daySecs ?? DAY,
      days: o.days ?? 3,
      grace: o.grace ?? 600,
      maxMembers: o.maxMembers ?? 10,
      isPublic: o.isPublic ?? true,
      mint: o.mint ?? this.mint,
      tokenProgram: o.tokenProgram ?? TOKEN_2022_PROGRAM_ID,
    };
    const address = this.pactPda(creator.publicKey, seed);
    const ix = await this.program.methods
      .createPact(
        new BN(seed),
        args.name,
        new BN(args.stake.toString()),
        new BN(args.start),
        args.daySecs,
        args.days,
        args.grace,
        args.maxMembers,
        args.isPublic,
      )
      .accountsPartial({ creator: creator.publicKey, pact: address, mint: args.mint, tokenProgram: args.tokenProgram })
      .instruction();
    return { ix, creator, info: this.pactInfo({ ...args, address }) };
  }

  /** Sends create_pact and returns the result together with the pact's computed schedule. */
  async tryCreatePact(o: PactOpts = {}) {
    const { ix, creator, info } = await this.createPactIx(o);
    return { r: await this.send([ix], [creator]), info };
  }
  async createPact(o: PactOpts = {}) {
    const { r, info } = await this.tryCreatePact(o);
    this.ok(r);
    return info;
  }

  async joinIx(p: PactInfo, user: PublicKey, wake: number, name = "Riser", avatar = 1) {
    return this.program.methods
      .join(name, avatar, wake)
      .accountsPartial({ owner: user, pact: p.address, mint: p.mint, tokenProgram: p.tokenProgram })
      .instruction();
  }
  async join(p: PactInfo, user: Keypair, wake: number, name = "Riser") {
    return this.send([await this.joinIx(p, user.publicKey, wake, name)], [user]);
  }

  async clockInIx(pact: PublicKey, user: PublicKey, mission = 0, member?: PublicKey) {
    return this.program.methods
      .clockIn(mission)
      .accountsPartial({ owner: user, pact, member: member ?? this.memberPda(pact, user) })
      .instruction();
  }
  async clockIn(p: PactInfo, user: Keypair, mission = 0) {
    return this.send([await this.clockInIx(p.address, user.publicKey, mission)], [user]);
  }
  /** Sets the clock to `ts`, then clocks in. */
  async clockInAt(p: PactInfo, user: Keypair, ts: number, mission = 0) {
    await this.setTime(ts);
    return this.clockIn(p, user, mission);
  }

  async claimIx(p: PactInfo, user: PublicKey) {
    return this.program.methods
      .claim()
      .accountsPartial({ owner: user, pact: p.address, mint: p.mint, tokenProgram: p.tokenProgram })
      .instruction();
  }
  async claim(p: PactInfo, user: Keypair) {
    return this.send([await this.claimIx(p, user.publicKey)], [user]);
  }

  fetchPact(p: PactInfo | PublicKey) {
    return this.program.account.pact.fetch(p instanceof PublicKey ? p : p.address);
  }
  fetchMember(p: PactInfo | PublicKey, owner: PublicKey) {
    return this.program.account.member.fetch(this.memberPda(p instanceof PublicKey ? p : p.address, owner));
  }
}

/** The program's payout formula, in exact integers. */
export function modelPayout(stake: bigint, totalDeposited: bigint, totalHits: bigint, deposit: bigint, hits: bigint) {
  if (totalHits === 0n) return deposit;
  const pot = totalDeposited - stake * totalHits;
  return stake * hits + (pot * hits) / totalHits;
}

/** A keypair that is the same on every run. */
export function fixedKey(n: number) {
  return Keypair.fromSeed(new Uint8Array(32).fill(n));
}
