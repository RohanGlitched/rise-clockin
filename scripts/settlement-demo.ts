/**
 * Settlement proof on devnet, end to end in about 35 minutes.
 *
 * Pact A ("Settlement proof"): 3 members, 3 "mornings" of 10 minutes each, 10 SKR a morning.
 *   Morning 1: Aman and Priya clock in, Mei misses.
 *   Morning 2: Aman in, Priya misses, Mei clocks in late.
 *   Morning 3: Aman in, Priya and Mei miss.
 *   Kept: Aman 3, Priya 1, Mei 1 (5 total). Locked 90. Pot = 90 - 10*5 = 40.
 *   Expected payouts: Aman 30 + 40*3/5 = 54, Priya 10 + 8 = 18, Mei 10 + 8 = 18. Vault ends at 0.
 * Pact B ("Refund proof"): 2 members, 1 morning, nobody clocks in. Each gets their 10 back.
 *
 * Writes every transaction and the vault balances to docs/SETTLEMENT.md.
 */
import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";
import { TOKEN_2022_PROGRAM_ID, getAssociatedTokenAddressSync } from "@solana/spl-token";
import * as fs from "fs";
import * as path from "path";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";
import { botKey, load } from "./riders";

const ROOT = process.env.RISE_ROOT || path.join(__dirname, "..");
const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const D = 600, GRACE = 120, EARLY = 150, SKR = 1_000_000, STAKE = 10 * SKR;
const conn = new Connection(RPC, "confirmed");
const ex = (sig: string) => `https://explorer.solana.com/tx/${sig}?cluster=devnet`;
const exa = (a: string) => `https://explorer.solana.com/address/${a}?cluster=devnet`;
const log: string[] = [];
const rows: string[] = [];
const note = (s: string) => { console.log(new Date().toISOString(), s); log.push(s); };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const now = () => Math.floor(Date.now() / 1000);

function prog(kp: Keypair) {
  return new Program<Rise>(idl as any, new anchor.AnchorProvider(conn, new anchor.Wallet(kp), { commitment: "confirmed" }));
}

async function retry<T>(f: () => Promise<T>, tries = 5): Promise<T> {
  for (let i = 0; ; i++) {
    try { return await f(); } catch (e) { if (i >= tries - 1) throw e; await sleep(1500 * (i + 1)); }
  }
}

async function tokenBal(owner: PublicKey, mint: PublicKey) {
  const ata = getAssociatedTokenAddressSync(mint, owner, true, TOKEN_2022_PROGRAM_ID);
  try { return Number((await conn.getTokenAccountBalance(ata)).value.amount); } catch { return 0; }
}

async function main() {
  const admin = load(path.join(process.env.HOME!, ".config/solana/id.json"));
  const mintFile = fs.readdirSync(path.join(ROOT, "keys")).find((f) => f.startsWith("SKR") && f.endsWith(".json"))!;
  const mint = load(path.join(ROOT, "keys", mintFile)).publicKey;
  const [aman, priya, mei, kofi, sofia] = ["Aman", "Priya", "Mei", "Kofi", "Sofia"].map(botKey);

  // Top up fees and rent for the five members.
  for (const kp of [aman, priya, mei, kofi, sofia]) {
    const bal = await conn.getBalance(kp.publicKey);
    if (bal < 0.03 * LAMPORTS_PER_SOL) {
      await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: kp.publicKey, lamports: 0.04 * LAMPORTS_PER_SOL - bal })), [admin]);
    }
  }

  const seedA = Date.now(), seedB = seedA + 1;
  const pactA = PublicKey.findProgramAddressSync([Buffer.from("pact"), aman.publicKey.toBuffer(), new BN(seedA).toArrayLike(Buffer, "le", 8)], prog(aman).programId)[0];
  const pactB = PublicKey.findProgramAddressSync([Buffer.from("pact"), kofi.publicKey.toBuffer(), new BN(seedB).toArrayLike(Buffer, "le", 8)], prog(kofi).programId)[0];
  const vaultOf = (p: PublicKey) => getAssociatedTokenAddressSync(mint, p, true, TOKEN_2022_PROGRAM_ID);
  const start = now() + 30;
  const record = async (step: string, sig: string) => { rows.push(`| ${step} | [${sig.slice(0, 16)}…](${ex(sig)}) |`); note(`${step}: ${sig}`); };

  // Pact A
  let sig = await retry(() => prog(aman).methods.createPact(new BN(seedA), "Settlement proof", new BN(STAKE), new BN(start), D, 3, GRACE, 10, false)
    .accountsPartial({ creator: aman.publicKey, pact: pactA, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
  await record("Create pact A (3 mornings × 10 min, 10 SKR)", sig);
  const wake: Record<string, number> = { Aman: 300, Priya: 360, Mei: 420 };
  for (const [name, kp] of [["Aman", aman], ["Priya", priya], ["Mei", mei]] as [string, Keypair][]) {
    sig = await retry(() => prog(kp).methods.join(name, 0, wake[name]).accountsPartial({ owner: kp.publicKey, pact: pactA, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
    await record(`${name} joins, locks 30 SKR`, sig);
  }
  // Pact B
  const startB = now() + 30;
  sig = await retry(() => prog(kofi).methods.createPact(new BN(seedB), "Refund proof", new BN(STAKE), new BN(startB), D, 1, GRACE, 10, false)
    .accountsPartial({ creator: kofi.publicKey, pact: pactB, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
  await record("Create pact B (1 morning, nobody will wake)", sig);
  for (const [name, kp] of [["Kofi", kofi], ["Sofia", sofia]] as [string, Keypair][]) {
    sig = await retry(() => prog(kp).methods.join(name, 0, 300).accountsPartial({ owner: kp.publicKey, pact: pactB, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
    await record(`${name} joins pact B, locks 10 SKR`, sig);
  }
  const vaultA0 = await tokenBal(pactA, mint);
  note(`Vault A after joins: ${vaultA0 / SKR} SKR`);

  // Who clocks in each morning, and how late (seconds from wake time).
  const plan: [string, Keypair, number, number][] = [
    ["Aman", aman, 0, -60], ["Priya", priya, 0, -20],
    ["Aman", aman, 1, -90], ["Mei", mei, 1, 75],
    ["Aman", aman, 2, -30],
  ];
  for (const [name, kp, day, delta] of plan) {
    const at = start + day * D + wake[name] + delta;
    while (now() < at) await sleep(2000);
    const member = PublicKey.findProgramAddressSync([Buffer.from("member"), pactA.toBuffer(), kp.publicKey.toBuffer()], prog(kp).programId)[0];
    sig = await retry(() => prog(kp).methods.clockIn(0).accountsPartial({ owner: kp.publicKey, pact: pactA, member }).rpc());
    await record(`Morning ${day + 1}: ${name} clocks in ${delta > 0 ? `${delta}s late (red ink)` : `${-delta}s early`}`, sig);
  }
  rows.push("| Morning 1: Mei sleeps in | no transaction, window closes |", "| Morning 2: Priya sleeps in | no transaction, window closes |", "| Morning 3: Priya and Mei sleep in | no transaction, windows close |");

  // Before the end, a claim must fail.
  try {
    await prog(aman).methods.claim().accountsPartial({ owner: aman.publicKey, pact: pactA, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    note("UNEXPECTED: early claim succeeded");
  } catch (e: any) { note(`Early claim rejected as expected: ${String(e?.error?.errorCode?.code ?? e?.message).slice(0, 60)}`); rows.push("| Early claim before the pact ends | rejected by the program (`NotOver`) |"); }

  // Refund pact ends first (1 morning).
  const endB = startB + D + GRACE + 5;
  while (now() < endB) await sleep(3000);
  for (const [name, kp] of [["Kofi", kofi], ["Sofia", sofia]] as [string, Keypair][]) {
    const before = await tokenBal(kp.publicKey, mint);
    sig = await retry(() => prog(kp).methods.claim().accountsPartial({ owner: kp.publicKey, pact: pactB, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
    const after = await tokenBal(kp.publicKey, mint);
    await record(`Pact B: ${name} claims refund (+${(after - before) / SKR} SKR)`, sig);
  }
  const vaultB = await tokenBal(pactB, mint);

  // Settlement of pact A.
  const endA = start + 3 * D + GRACE + 5;
  while (now() < endA) await sleep(3000);
  const paid: Record<string, number> = {};
  for (const [name, kp] of [["Aman", aman], ["Priya", priya], ["Mei", mei]] as [string, Keypair][]) {
    const before = await tokenBal(kp.publicKey, mint);
    sig = await retry(() => prog(kp).methods.claim().accountsPartial({ owner: kp.publicKey, pact: pactA, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc());
    const after = await tokenBal(kp.publicKey, mint);
    paid[name] = (after - before) / SKR;
    await record(`Pact A: ${name} claims +${paid[name]} SKR`, sig);
  }
  const vaultA = await tokenBal(pactA, mint);
  const pa = await prog(aman).account.pact.fetch(pactA);

  const md = `# Settlement proof (Solana devnet)

Run on ${new Date().toISOString().slice(0, 10)} with \`scripts/settlement-demo.ts\`. Each "morning" lasts 10 minutes so a whole pact settles in about half an hour. Every row links to the transaction on Solana Explorer.

- Pact A "Settlement proof": [${pactA.toBase58()}](${exa(pactA.toBase58())}) — vault [${vaultOf(pactA).toBase58()}](${exa(vaultOf(pactA).toBase58())})
- Pact B "Refund proof": [${pactB.toBase58()}](${exa(pactB.toBase58())}) — vault [${vaultOf(pactB).toBase58()}](${exa(vaultOf(pactB).toBase58())})

## What happened

| Step | Transaction |
| --- | --- |
${rows.join("\n")}

## Conservation check

| | Expected | On chain |
| --- | --- | --- |
| Pact A locked | 90 SKR | ${vaultA0 / SKR} SKR (vault after joins) |
| Mornings kept | Aman 3, Priya 1, Mei 1 = 5 | total_hits = ${pa.totalHits} |
| Pot (locked − 10 × kept) | 40 SKR | — |
| Aman: 3 × 10 + 40 × 3/5 | 54 SKR | ${paid["Aman"]} SKR |
| Priya: 1 × 10 + 40 × 1/5 | 18 SKR | ${paid["Priya"]} SKR |
| Mei: 1 × 10 + 40 × 1/5 | 18 SKR | ${paid["Mei"]} SKR |
| Total paid out | 90 SKR | ${(paid["Aman"] + paid["Priya"] + paid["Mei"])} SKR (total_paid = ${pa.totalPaid.toNumber() / SKR}) |
| Pact A vault after settlement | 0 | ${vaultA / SKR} |
| Pact B (nobody woke) refunds | 10 + 10 | vault after = ${vaultB / SKR} |
`;
  fs.writeFileSync(path.join(ROOT, "docs", "SETTLEMENT.md"), md);
  note("wrote docs/SETTLEMENT.md");
}

main().catch((e) => { console.error(e); process.exit(1); });
