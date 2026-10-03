// The seeded early risers: devnet keypairs in keys/bots/ with their own habits.
import { Keypair } from "@solana/web3.js";
import * as fs from "fs";
import * as path from "path";

const ROOT = process.env.RISE_ROOT || path.join(__dirname, "..");
const DAY = 86_400;
const IST = 5.5 * 3600;

/** Name, sign, wake time in IST (hh:mm), how often they make it, and how early they tend to be (minutes). */
export const RISERS = [
  { name: "Aman", avatar: 0, wake: "05:45", reliability: 0.95, lean: -12 },
  { name: "Priya", avatar: 1, wake: "06:00", reliability: 0.9, lean: -6 },
  { name: "Mei", avatar: 6, wake: "06:15", reliability: 0.85, lean: -3 },
  { name: "Diego", avatar: 4, wake: "06:30", reliability: 0.55, lean: 4 },
  { name: "Sofia", avatar: 5, wake: "06:30", reliability: 0.8, lean: -1 },
  { name: "Kofi", avatar: 2, wake: "07:00", reliability: 0.9, lean: -9 },
  { name: "Lena", avatar: 3, wake: "06:45", reliability: 0.75, lean: 2 },
  { name: "Arjun", avatar: 7, wake: "07:15", reliability: 0.7, lean: 1 },
];

export const load = (p: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync(p, "utf8"))));

export function botKey(name: string): Keypair {
  const dir = path.join(ROOT, "keys", "bots");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${name.toLowerCase()}.json`);
  if (!fs.existsSync(file)) fs.writeFileSync(file, JSON.stringify(Array.from(Keypair.generate().secretKey)));
  return load(file);
}

/** Wake offset from the pact's UTC-midnight start, for a wake time given in IST. */
export function offsetFor(wakeIst: string, startTs: number) {
  const [h, m] = wakeIst.split(":").map(Number);
  const utc = (((h * 3600 + m * 60 - IST) % DAY) + DAY) % DAY;
  return (((utc - (startTs % DAY)) % DAY) + DAY) % DAY;
}

