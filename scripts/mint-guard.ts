/**
 * Shows the pact mint guard working on devnet:
 *  1. A private pact backed by test SKR (metadata extensions only) is created.
 *  2. A pact backed by a fresh Token-2022 mint with a permanent delegate is refused with
 *     UnsafeMint. That transaction is sent without preflight so the failure is on chain.
 * Prints both signatures with explorer links.
 */
import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import { Connection, Keypair, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";
import {
  ExtensionType, TOKEN_2022_PROGRAM_ID, createInitializeMintInstruction, createInitializePermanentDelegateInstruction, getMintLen,
} from "@solana/spl-token";
import * as os from "os";
import * as path from "path";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";
import { load } from "./riders";

const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const SKR = new PublicKey("SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38");
const link = (sig: string) => `https://explorer.solana.com/tx/${sig}?cluster=devnet`;

async function main() {
  const payer = load(process.env.PAYER || path.join(os.homedir(), ".config/solana/id.json"));
  const conn = new Connection(RPC, "confirmed");
  const provider = new anchor.AnchorProvider(conn, new anchor.Wallet(payer), { commitment: "confirmed" });
  const program = new Program<Rise>(idl as any, provider);
  const pactFor = (seed: BN) =>
    PublicKey.findProgramAddressSync([Buffer.from("pact"), payer.publicKey.toBuffer(), seed.toArrayLike(Buffer, "le", 8)], program.programId)[0];
  const create = (seed: BN, mint: PublicKey) =>
    program.methods
      .createPact(seed, "Mint guard check", new BN(1_000_000), new BN(Math.floor(Date.now() / 1000) + 3600), 86_400, 1, 600, 2, false)
      .accountsPartial({ creator: payer.publicKey, pact: pactFor(seed), mint, tokenProgram: TOKEN_2022_PROGRAM_ID });

  // 1. Test SKR passes.
  const okSeed = new BN(Date.now());
  const okSig = await create(okSeed, SKR).rpc();
  console.log(`Pact with test SKR created: ${link(okSig)}`);

  // 2. A mint with a permanent delegate is refused.
  const mint = Keypair.generate();
  const len = getMintLen([ExtensionType.PermanentDelegate]);
  await sendAndConfirmTransaction(conn, new Transaction().add(
    SystemProgram.createAccount({
      fromPubkey: payer.publicKey, newAccountPubkey: mint.publicKey, space: len,
      lamports: await conn.getMinimumBalanceForRentExemption(len), programId: TOKEN_2022_PROGRAM_ID,
    }),
    createInitializePermanentDelegateInstruction(mint.publicKey, payer.publicKey, TOKEN_2022_PROGRAM_ID),
    createInitializeMintInstruction(mint.publicKey, 6, payer.publicKey, null, TOKEN_2022_PROGRAM_ID),
  ), [payer, mint]);
  const tx = await create(new BN(Date.now() + 1), mint.publicKey).transaction();
  tx.feePayer = payer.publicKey;
  tx.recentBlockhash = (await conn.getLatestBlockhash("finalized")).blockhash;
  tx.sign(payer);
  const badSig = await conn.sendRawTransaction(tx.serialize(), { skipPreflight: true });
  await conn.confirmTransaction(badSig, "confirmed").catch(() => undefined);
  let logs: string[] = [];
  for (let i = 0; i < 20 && !logs.length; i++) {
    const t = await conn.getTransaction(badSig, { commitment: "confirmed", maxSupportedTransactionVersion: 0 });
    logs = t?.meta?.logMessages ?? [];
    if (!logs.length) await new Promise((r) => setTimeout(r, 1500));
  }
  const refused = logs.some((l) => l.includes("UnsafeMint"));
  console.log(`Pact with a permanent-delegate mint ${refused ? "refused (UnsafeMint)" : "NOT refused"}: ${link(badSig)}`);
  if (!refused) process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
