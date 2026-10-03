/**
 * Clocks the seeded early risers in, each on their own habits: some mornings they
 * sleep through, some they're early, Diego is often late. Run every few minutes.
 * Decisions are hashed from (rider, pact, day), so re-runs agree with each other.
 * Two RPC reads per pass (all members, all pacts) keep it well inside public rate limits.
 */
import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { Connection, Keypair } from "@solana/web3.js";
import { createHash } from "crypto";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";
import { RISERS, botKey } from "./riders";

const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const NOT_IN = 32767;
const EARLY = 30 * 60;

function roll(...parts: (string | number)[]) {
  const h = createHash("sha256").update(parts.join("|")).digest();
  return h.readUInt32LE(0) / 0xffffffff;
}

async function main() {
  const conn = new Connection(RPC, "confirmed");
  const now = Math.floor(Date.now() / 1000);
  const riders = new Map<string, { r: (typeof RISERS)[number]; kp: Keypair }>();
  for (const r of RISERS) { const kp = botKey(r.name); riders.set(kp.publicKey.toBase58(), { r, kp }); }

  const reader = new Program<Rise>(idl as any, new anchor.AnchorProvider(conn, new anchor.Wallet(Keypair.generate()), { commitment: "confirmed" }));
  const [members, pacts] = await Promise.all([reader.account.member.all(), reader.account.pact.all()]);
  const pactBy = new Map(pacts.map((p) => [p.publicKey.toBase58(), p.account]));

  for (const { publicKey, account: m } of members) {
    const rider = riders.get(m.owner.toBase58());
    const p = pactBy.get(m.pact.toBase58());
    if (!rider || !p) continue;
    const { r, kp } = rider;
    const start = p.startTs.toNumber();
    for (let d = m.firstDay; d < p.days; d++) {
      const target = start + d * p.daySecs + m.wakeOffset;
      if (now < target - EARLY || now > target + p.graceSecs || m.offsets[d] !== NOT_IN) continue;
      const pactKey = m.pact.toBase58();
      if (roll(r.name, pactKey, d, "up") > r.reliability) continue; // sleeps in
      // When they get up: around their habit, within the window.
      const minutes = Math.max(-28, Math.min(9, r.lean + (roll(r.name, pactKey, d, "t") - 0.5) * 14));
      if (now < target + Math.round(minutes * 60)) continue;
      const prog = new Program<Rise>(idl as any, new anchor.AnchorProvider(conn, new anchor.Wallet(kp), { commitment: "confirmed" }));
      try {
        const sig = await prog.methods.clockIn(Math.floor(roll(r.name, d, "m") * 3))
          .accountsPartial({ owner: kp.publicKey, pact: m.pact, member: publicKey }).rpc();
        console.log(new Date().toISOString(), r.name, "clocked in", p.name, "day", d, sig);
      } catch (e: any) {
        console.log(new Date().toISOString(), r.name, "failed", String(e?.message ?? e).slice(0, 140));
      }
    }
  }
}

main().catch((e) => { console.error(new Date().toISOString(), String(e?.message ?? e).slice(0, 200)); process.exit(1); });
