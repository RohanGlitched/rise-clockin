// Devnet SOL for fees, so anyone can try Rise without a faucet login.
// Sends a small amount once per address while its balance is low.
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction } from "@solana/web3.js";
import bs58 from "bs58";

const RPC = process.env.RPC_URL || "https://api.devnet.solana.com";
const DRIP = Number(process.env.SOL_DRIP || 0.03) * LAMPORTS_PER_SOL;
const recent = new Map(); // address -> last drip time (per warm instance)

export default async function handler(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Use POST." });
  let address;
  try {
    address = new PublicKey((req.body && req.body.address) || "");
  } catch {
    return res.status(400).json({ error: "That isn't a Solana address." });
  }
  const key = address.toBase58();
  const last = recent.get(key) || 0;
  if (Date.now() - last < 10 * 60 * 1000) return res.status(429).json({ error: "Devnet SOL was just sent to this wallet. Try again in a few minutes." });
  try {
    const conn = new Connection(RPC, "confirmed");
    const have = await conn.getBalance(address);
    if (have >= DRIP / 2) return res.status(200).json({ signature: "", note: "Wallet already has enough devnet SOL." });
    const faucet = Keypair.fromSecretKey(bs58.decode(process.env.FAUCET_SECRET));
    const left = await conn.getBalance(faucet.publicKey);
    if (left < DRIP * 2) return res.status(503).json({ error: "The Rise faucet is out of devnet SOL. Get some at faucet.solana.com." });
    const tx = new Transaction().add(SystemProgram.transfer({ fromPubkey: faucet.publicKey, toPubkey: address, lamports: DRIP }));
    const signature = await conn.sendTransaction(tx, [faucet]);
    recent.set(key, Date.now());
    return res.status(200).json({ signature });
  } catch (e) {
    return res.status(502).json({ error: "Devnet is busy. Try again in a minute." });
  }
}
