/**
 * Seeds devnet with two public pacts and a few early risers, so the app and the
 * website show real time cards from day one. Each early riser is a devnet keypair
 * in keys/bots/ that clocks in through scripts/bots.ts on its own schedule.
 */
import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";
import { TOKEN_2022_PROGRAM_ID } from "@solana/spl-token";
import * as fs from "fs";
import * as path from "path";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";
import { RISERS, botKey, load, offsetFor } from "./riders";

const ROOT = process.env.RISE_ROOT || path.join(__dirname, "..");
const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const DAY = 86_400;

const PACTS = [
  { seed: 1, name: "Sunrise Club", stake: 10, days: 14, members: ["Aman", "Priya", "Mei", "Diego", "Sofia", "Kofi"] },
  { seed: 2, name: "No snooze October", stake: 25, days: 7, members: ["Lena", "Arjun", "Mei", "Diego"] },
];

async function main() {
  const conn = new Connection(RPC, "confirmed");
  const admin = load(path.join(process.env.HOME!, ".config/solana/id.json"));
  const programFor = (kp: Keypair) => new Program<Rise>(idl as any, new anchor.AnchorProvider(conn, new anchor.Wallet(kp), { commitment: "confirmed" }));
  const mintFile = fs.readdirSync(path.join(ROOT, "keys")).find((f) => f.startsWith("SKR") && f.endsWith(".json"))!;
  const mint = load(path.join(ROOT, "keys", mintFile)).publicKey;

  // Fund and drip each early riser.
  for (const r of RISERS) {
    const kp = botKey(r.name);
    const bal = await conn.getBalance(kp.publicKey);
    if (bal < 0.015 * LAMPORTS_PER_SOL) {
      await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: kp.publicKey, lamports: 0.03 * LAMPORTS_PER_SOL - bal })), [admin]);
    }
    try {
      await programFor(kp).methods.drip().accountsPartial({ user: kp.publicKey, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    } catch (e: any) {
      if (!String(e).includes("DripTooSoon")) throw e;
    }
    console.log(r.name, kp.publicKey.toBase58());
  }

  const now = Math.floor(Date.now() / 1000);
  const start = now - (now % DAY);
  for (const p of PACTS) {
    const creator = botKey(p.members[0]);
    const prog = programFor(creator);
    const [pact] = PublicKey.findProgramAddressSync([Buffer.from("pact"), creator.publicKey.toBuffer(), new BN(p.seed).toArrayLike(Buffer, "le", 8)], prog.programId);
    let startTs = start;
    if (!(await conn.getAccountInfo(pact))) {
      await prog.methods.createPact(new BN(p.seed), p.name, new BN(p.stake * 1e6), new BN(start), DAY, p.days, 600, 50, true)
        .accountsPartial({ creator: creator.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
      console.log("created", p.name, pact.toBase58());
    } else {
      startTs = (await prog.account.pact.fetch(pact)).startTs.toNumber();
    }
    for (const name of p.members) {
      const r = RISERS.find((x) => x.name === name)!;
      const kp = botKey(name);
      const [member] = PublicKey.findProgramAddressSync([Buffer.from("member"), pact.toBuffer(), kp.publicKey.toBuffer()], prog.programId);
      if (await conn.getAccountInfo(member)) continue;
      await programFor(kp).methods.join(name, r.avatar, offsetFor(r.wake, startTs))
        .accountsPartial({ owner: kp.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
      console.log("  joined", name);
    }
  }
  console.log("admin left", (await conn.getBalance(admin.publicKey)) / LAMPORTS_PER_SOL, "SOL");
}

main().catch((e) => { console.error(e); process.exit(1); });
