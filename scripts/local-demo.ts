/**
 * Localnet only: a pact whose "days" last 10 minutes, so time cards fill up quickly
 * while testing the app on the emulator. Re-run to clock the risers in.
 */
import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey } from "@solana/web3.js";
import { TOKEN_2022_PROGRAM_ID } from "@solana/spl-token";
import * as fs from "fs";
import * as path from "path";
import { createHash } from "crypto";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";
import { RISERS, botKey } from "./riders";

const RPC = "http://127.0.0.1:8899";
const D = 600;
const roll = (...p: (string | number)[]) => createHash("sha256").update(p.join("|")).digest().readUInt32LE(0) / 0xffffffff;

async function main() {
  const conn = new Connection(RPC, "confirmed");
  const ROOT = process.env.RISE_ROOT || path.join(__dirname, "..");
  const mintFile = fs.readdirSync(path.join(ROOT, "keys")).find((f) => f.startsWith("SKR") && f.endsWith(".json"))!;
  const mint = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync(path.join(ROOT, "keys", mintFile), "utf8")))).publicKey;
  const prog = (kp: Keypair) => new Program<Rise>(idl as any, new anchor.AnchorProvider(conn, new anchor.Wallet(kp), { commitment: "confirmed" }));
  const now = Math.floor(Date.now() / 1000);
  const riders = RISERS.slice(0, 6);
  const creator = botKey(riders[0].name);
  const [pact] = PublicKey.findProgramAddressSync([Buffer.from("pact"), creator.publicKey.toBuffer(), new BN(99).toArrayLike(Buffer, "le", 8)], prog(creator).programId);
  for (const r of riders) {
    const kp = botKey(r.name);
    if ((await conn.getBalance(kp.publicKey)) < LAMPORTS_PER_SOL) {
      await conn.confirmTransaction(await conn.requestAirdrop(kp.publicKey, 2 * LAMPORTS_PER_SOL), "confirmed");
      await prog(kp).methods.drip().accountsPartial({ user: kp.publicKey, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    }
  }
  if (!(await conn.getAccountInfo(pact))) {
    await prog(creator).methods.createPact(new BN(99), "Sunrise Club", new BN(10e6), new BN(now - D + 240), D, 7, 120, 50, true)
      .accountsPartial({ creator: creator.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    for (const r of riders) {
      const kp = botKey(r.name);
      await prog(kp).methods.join(r.name, r.avatar, 300 + Math.floor(roll(r.name) * 200))
        .accountsPartial({ owner: kp.publicKey, pact, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    }
    console.log("pact", pact.toBase58());
  }
  // Loop: clock risers in through their windows (window = target-150s .. target+120s).
  const p = await prog(creator).account.pact.fetch(pact);
  for (let tick = 0; tick < Number(process.env.TICKS || 1); tick++) {
    const t = Math.floor(Date.now() / 1000);
    console.log("tick", tick, new Date().toISOString());
    for (const r of riders) {
      const kp = botKey(r.name);
      const [member] = PublicKey.findProgramAddressSync([Buffer.from("member"), pact.toBuffer(), kp.publicKey.toBuffer()], prog(kp).programId);
      const m = await prog(kp).account.member.fetch(member);
      for (let d = m.firstDay; d < p.days; d++) {
        const target = p.startTs.toNumber() + d * D + m.wakeOffset;
        if (t < target - 150 || t > target + 120 || m.offsets[d] !== 32767) continue;
        if (roll(r.name, d) > r.reliability) continue;
        const at = target + Math.round((roll(r.name, d, "t") - 0.6) * 200);
        if (t < at) continue;
        await prog(kp).methods.clockIn(0).accountsPartial({ owner: kp.publicKey, pact, member }).rpc().then(() => console.log(r.name, "in", d)).catch((e) => console.log(r.name, "err", String(e).slice(0, 160)));
      }
    }
    if (tick + 1 < Number(process.env.TICKS || 1)) await new Promise((res) => setTimeout(res, 20_000));
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
