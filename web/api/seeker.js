// Seeker verification: checks a Sign-in-with-Solana signature, then looks for a
// Seeker Genesis Token in that wallet on mainnet. All four SGT properties must match.
import { Connection, PublicKey } from "@solana/web3.js";
import { TOKEN_2022_PROGRAM_ID, getMetadataPointerState, getTokenGroupMemberState, unpackMint } from "@solana/spl-token";
import nacl from "tweetnacl";

const MAINNET = process.env.MAINNET_RPC_URL || "https://api.mainnet-beta.solana.com";
const SGT_MINT_AUTHORITY = "GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4";
const SGT_METADATA_ADDRESS = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te";
const SGT_GROUP_MINT_ADDRESS = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te";
const DOMAIN = "rise-clockin.vercel.app";

export async function findSgtMint(conn, owner) {
  const { value } = await conn.getParsedTokenAccountsByOwner(owner, { programId: TOKEN_2022_PROGRAM_ID });
  const mints = value.map((a) => a.account.data.parsed?.info?.mint).filter(Boolean).map((m) => new PublicKey(m));
  for (let i = 0; i < mints.length; i += 100) {
    const batch = mints.slice(i, i + 100);
    const infos = await conn.getMultipleAccountsInfo(batch);
    for (let j = 0; j < infos.length; j++) {
      if (!infos[j]) continue;
      let mint;
      try { mint = unpackMint(batch[j], infos[j], TOKEN_2022_PROGRAM_ID); } catch { continue; }
      const pointer = getMetadataPointerState(mint);
      const member = getTokenGroupMemberState(mint);
      if (
        mint.mintAuthority?.toBase58() === SGT_MINT_AUTHORITY &&
        pointer?.authority?.toBase58() === SGT_MINT_AUTHORITY &&
        pointer?.metadataAddress?.toBase58() === SGT_METADATA_ADDRESS &&
        member?.group?.toBase58() === SGT_GROUP_MINT_ADDRESS
      ) return mint.address.toBase58();
    }
  }
  return null;
}

export default async function handler(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Use POST." });
  const { address, message, signature } = req.body || {};
  let owner;
  try { owner = new PublicKey(address); } catch { return res.status(400).json({ error: "Bad address." }); }
  const msg = Buffer.from(message || "", "base64");
  const sig = Buffer.from(signature || "", "base64");
  const text = msg.toString("utf8");
  // The signed message must be a Sign-in-with-Solana message for Rise, by this wallet.
  if (!text.startsWith(`${DOMAIN} wants you to sign in with your Solana account:`) || !text.includes(owner.toBase58()))
    return res.status(400).json({ error: "This sign-in message isn't for Rise." });
  if (!nacl.sign.detached.verify(msg, sig, owner.toBytes())) return res.status(401).json({ error: "Signature doesn't match the wallet." });
  try {
    const mint = await findSgtMint(new Connection(MAINNET, "confirmed"), owner);
    return res.status(200).json({ seeker: Boolean(mint), mint });
  } catch {
    return res.status(502).json({ error: "Couldn't reach Solana mainnet. Try again." });
  }
}
