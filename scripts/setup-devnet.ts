/**
 * One-time devnet setup after `anchor deploy`:
 *  1. Creates test SKR (Token-2022 with on-chain metadata) at the vanity address in keys/,
 *     then hands its mint authority to the Rise program's mint_auth PDA.
 *  2. Initialises the faucet.
 *  3. Funds the web faucet wallet that tops up devnet SOL for new users.
 * Safe to re-run: every step checks what already exists.
 */
import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import {
  Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction,
} from "@solana/web3.js";
import {
  AuthorityType, ExtensionType, TOKEN_2022_PROGRAM_ID, TYPE_SIZE, LENGTH_SIZE, createInitializeMetadataPointerInstruction,
  createInitializeMintInstruction, createSetAuthorityInstruction, getMintLen,
} from "@solana/spl-token";
import { createInitializeInstruction, pack, TokenMetadata } from "@solana/spl-token-metadata";
import * as fs from "fs";
import * as path from "path";
import { Rise } from "../target/types/rise";
import idl from "../target/idl/rise.json";

const ROOT = process.env.RISE_ROOT || path.join(__dirname, "..");
const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const load = (p: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync(p, "utf8"))));
const home = process.env.HOME!;

async function main() {
  const conn = new Connection(RPC, "confirmed");
  const admin = load(path.join(home, ".config/solana/id.json"));
  const provider = new anchor.AnchorProvider(conn, new anchor.Wallet(admin), { commitment: "confirmed" });
  anchor.setProvider(provider);
  const program = new Program<Rise>(idl as any, provider);
  const mintFile = fs.readdirSync(path.join(ROOT, "keys")).find((f) => f.startsWith("SKR") && f.endsWith(".json"))!;
  const mintKp = load(path.join(ROOT, "keys", mintFile));
  const mint = mintKp.publicKey;
  const [mintAuth] = PublicKey.findProgramAddressSync([Buffer.from("mint_auth")], program.programId);
  const [faucet] = PublicKey.findProgramAddressSync([Buffer.from("faucet")], program.programId);
  console.log("program", program.programId.toBase58(), "admin", admin.publicKey.toBase58(), "SOL", (await conn.getBalance(admin.publicKey)) / LAMPORTS_PER_SOL);

  // 1. Test SKR mint with metadata, so wallets show its name and logo.
  if (!(await conn.getAccountInfo(mint))) {
    const meta: TokenMetadata = {
      mint, name: "Seeker (Rise devnet)", symbol: "SKR", uri: "https://rise-clockin.vercel.app/skr.json",
      additionalMetadata: [["note", "Test SKR for Rise on Solana devnet. No value."]],
    };
    const mintLen = getMintLen([ExtensionType.MetadataPointer]);
    const metaLen = TYPE_SIZE + LENGTH_SIZE + pack(meta).length;
    const lamports = await conn.getMinimumBalanceForRentExemption(mintLen + metaLen);
    const tx = new Transaction().add(
      SystemProgram.createAccount({ fromPubkey: admin.publicKey, newAccountPubkey: mint, space: mintLen, lamports, programId: TOKEN_2022_PROGRAM_ID }),
      createInitializeMetadataPointerInstruction(mint, admin.publicKey, mint, TOKEN_2022_PROGRAM_ID),
      createInitializeMintInstruction(mint, 6, admin.publicKey, null, TOKEN_2022_PROGRAM_ID),
      createInitializeInstruction({
        programId: TOKEN_2022_PROGRAM_ID, metadata: mint, updateAuthority: admin.publicKey, mint, mintAuthority: admin.publicKey,
        name: meta.name, symbol: meta.symbol, uri: meta.uri,
      }),
      createSetAuthorityInstruction(mint, admin.publicKey, AuthorityType.MintTokens, mintAuth, [], TOKEN_2022_PROGRAM_ID),
    );
    const sig = await sendAndConfirmTransaction(conn, tx, [admin, mintKp]);
    console.log("created test SKR", mint.toBase58(), sig);
  } else console.log("test SKR exists", mint.toBase58());

  // 2. Faucet.
  if (!(await conn.getAccountInfo(faucet))) {
    const sig = await program.methods.initFaucet().accountsPartial({ payer: admin.publicKey, mint, tokenProgram: TOKEN_2022_PROGRAM_ID }).rpc();
    console.log("faucet ready", sig);
  } else console.log("faucet exists");

  // 3. Web faucet wallet for devnet SOL top-ups.
  const faucetWalletPath = path.join(ROOT, "keys", "web-faucet.json");
  if (!fs.existsSync(faucetWalletPath)) fs.writeFileSync(faucetWalletPath, JSON.stringify(Array.from(Keypair.generate().secretKey)));
  const webFaucet = load(faucetWalletPath);
  const want = Number(process.env.WEB_FAUCET_SOL || 0.4) * LAMPORTS_PER_SOL;
  const has = await conn.getBalance(webFaucet.publicKey);
  if (has < want) {
    await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: webFaucet.publicKey, lamports: want - has })), [admin]);
  }
  console.log("web faucet", webFaucet.publicKey.toBase58(), (await conn.getBalance(webFaucet.publicKey)) / LAMPORTS_PER_SOL, "SOL");
  console.log("admin left", (await conn.getBalance(admin.publicKey)) / LAMPORTS_PER_SOL, "SOL");
}

main().catch((e) => { console.error(e); process.exit(1); });
