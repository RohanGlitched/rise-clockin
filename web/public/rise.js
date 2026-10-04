// Reads Rise pacts straight from Solana devnet. No backend, no indexer.
export const RPC = "https://api.devnet.solana.com";
export const PROGRAM = "6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS";
export const APK = "/rise.apk";

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
export function b58(bytes) {
  let n = 0n;
  for (const b of bytes) n = n * 256n + BigInt(b);
  let s = "";
  while (n > 0n) { s = ALPHABET[Number(n % 58n)] + s; n /= 58n; }
  for (const b of bytes) { if (b === 0) s = "1" + s; else break; }
  return s;
}

async function sha8(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return d.slice(0, 8);
}

async function rpc(method, params) {
  const r = await fetch(RPC, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) });
  const j = await r.json();
  if (j.error) throw new Error(j.error.message);
  return j.result;
}

const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

class Reader {
  constructor(d) { this.d = d; this.v = new DataView(d.buffer, d.byteOffset, d.byteLength); this.p = 8; }
  u8() { return this.d[this.p++]; }
  u16() { const x = this.v.getUint16(this.p, true); this.p += 2; return x; }
  i16() { const x = this.v.getInt16(this.p, true); this.p += 2; return x; }
  u32() { const x = this.v.getUint32(this.p, true); this.p += 4; return x; }
  i32() { const x = this.v.getInt32(this.p, true); this.p += 4; return x; }
  u64() { const x = this.v.getBigUint64(this.p, true); this.p += 8; return Number(x); }
  key() { const k = b58(this.d.slice(this.p, this.p + 32)); this.p += 32; return k; }
  str() { const n = this.u32(); const s = new TextDecoder().decode(this.d.slice(this.p, this.p + n)); this.p += n; return s; }
}

function decodePact(address, d) {
  const r = new Reader(d);
  return {
    address, creator: r.key(), seed: r.u64(), name: r.str(), mint: r.key(), stakePerDay: r.u64(), startTs: r.u64(),
    daySecs: r.u32(), days: r.u16(), graceSecs: r.u16(), maxMembers: r.u16(), memberCount: r.u16(), public: r.u8() !== 0,
    totalDeposited: r.u64(), totalHits: r.u32(), totalPaid: r.u64(), claims: r.u16(),
  };
}

function decodeMember(address, d) {
  const r = new Reader(d);
  const m = {
    address, pact: r.key(), owner: r.key(), name: r.str(), avatar: r.u8(), wakeOffset: r.u32(), firstDay: r.u16(), deposit: r.u64(),
    hits: r.u16(), streak: r.u16(), bestStreak: r.u16(), lastHitDay: r.i32(), claimed: r.u8() !== 0, joinedTs: r.u64(),
  };
  m.offsets = Array.from({ length: 64 }, () => r.i16());
  return m;
}

async function accounts(kind) {
  const disc = b58(await sha8(`account:${kind}`));
  const res = await rpc("getProgramAccounts", [PROGRAM, { encoding: "base64", commitment: "confirmed", filters: [{ memcmp: { offset: 0, bytes: disc } }] }]);
  return res.map((a) => [a.pubkey, fromB64(a.account.data[0])]);
}

export async function loadPacts() {
  const [pacts, members] = await Promise.all([accounts("Pact"), accounts("Member")]);
  const ms = members.map(([a, d]) => decodeMember(a, d));
  return pacts.map(([a, d]) => {
    const p = decodePact(a, d);
    p.members = ms.filter((m) => m.pact === a);
    return p;
  });
}

/** The pact at `address`, or null when no Rise pact lives there. Throws only when devnet itself fails. */
export async function loadPact(address) {
  if (!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address)) return null;
  const info = await rpc("getAccountInfo", [address, { encoding: "base64", commitment: "confirmed" }]);
  if (!info.value || info.value.owner !== PROGRAM) return null;
  const data = fromB64(info.value.data[0]);
  const disc = await sha8("account:Pact");
  if (data.length < 8 || disc.some((b, i) => data[i] !== b)) return null;
  const p = decodePact(address, data);
  // Only this pact's members: the pact key sits right after the 8-byte discriminator.
  const res = await rpc("getProgramAccounts", [PROGRAM, {
    encoding: "base64", commitment: "confirmed",
    filters: [{ memcmp: { offset: 0, bytes: b58(await sha8("account:Member")) } }, { memcmp: { offset: 8, bytes: address } }],
  }]);
  p.members = res.map((a) => decodeMember(a.pubkey, fromB64(a.account.data[0])));
  return p;
}

export const NOT_IN = 32767;
export const skr = (u) => (u / 1e6).toLocaleString(undefined, { maximumFractionDigits: 2 });
export const dayIndex = (p, now) => (now < p.startTs ? -1 : Math.floor((now - p.startTs) / p.daySecs));
export const target = (p, m, d) => p.startTs + d * p.daySecs + m.wakeOffset;
export const closes = (p, m, d) => target(p, m, d) + p.graceSecs;
export const fmt = (sec) => new Date(sec * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
/** "6:30a" / "10:03p": short enough for a card cell, still unambiguous. */
export const fmtShort = (sec) => {
  const d = new Date(sec * 1000);
  const h = d.getHours(), m = String(d.getMinutes()).padStart(2, "0");
  return `${h % 12 || 12}:${m}${h < 12 ? "a" : "p"}`;
};
export const isOver = (p, now = Date.now() / 1000) => now > p.startTs + p.days * p.daySecs + p.graceSecs;
export const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
export function pot(p, now) {
  let missed = 0;
  for (const m of p.members) for (let d = m.firstDay; d < p.days; d++) if (m.offsets[d] === NOT_IN && now > closes(p, m, d)) missed++;
  return missed * p.stakePerDay;
}

const AVATAR = ["#FFD166", "#F2A0A1", "#7FC8A9", "#9DB4FF", "#FFB877", "#C9A7F5", "#6FD3E3", "#4C8DAE"];
export const avatarColor = (i) => AVATAR[((i % 8) + 8) % 8];

/** Renders a pact as a manila time card (same rules as the app). */
export function timeCard(p, now = Date.now() / 1000) {
  const over = isOver(p, now);
  const today = Math.max(-1, Math.min(dayIndex(p, now), p.days - 1));
  // Pacts with short test "days" would repeat the same calendar date; label those by morning.
  const byDate = p.daySecs >= 86400;
  const end = Math.max(Math.min(Math.max(today, 0) + 3, p.days - 1), Math.min(6, p.days - 1));
  const start = Math.max(0, end - 6);
  const days = []; for (let d = start; d <= end; d++) days.push(d);
  const rows = [...p.members].sort((a, b) => b.hits - a.hits || b.streak - a.streak).slice(0, 8);
  const head = days.map((d) => {
    const dt = new Date((p.startTs + d * p.daySecs + p.daySecs / 2) * 1000);
    const mark = d === today && !over ? "today" : "";
    return byDate
      ? `<th class="${mark}"><span>${dt.toLocaleDateString([], { weekday: "short" }).slice(0, 2)}</span><b>${dt.getDate()}</b></th>`
      : `<th class="${mark}"><span>Day</span><b>${d + 1}</b></th>`;
  }).join("");
  const body = rows.map((m) => {
    const cells = days.map((d) => {
      if (d < m.firstDay) return `<td class="na">–</td>`;
      const off = m.offsets[d];
      if (off !== NOT_IN) return `<td><i class="${off > 0 ? "late" : "on"}" style="--r:${((d * 7 + m.hits * 13) % 7) - 3}deg">${fmtShort(target(p, m, d) + off)}</i></td>`;
      if (now > closes(p, m, d)) return `<td><span class="hole" title="Missed"></span></td>`;
      return `<td class="na">·</td>`;
    }).join("");
    return `<tr><th><span class="av" style="background:${avatarColor(m.avatar)}"></span>${escapeHtml(m.name)}</th>${cells}</tr>`;
  }).join("");
  const d = dayIndex(p, now);
  const sub = d < 0 ? "Starts soon" : over ? `Finished after ${plural(p.days, "morning")}` : `Morning ${d + 1} of ${p.days}, ${skr(p.stakePerDay)} SKR a morning`;
  return `<div class="card"><div class="card-top"><div><h3>${escapeHtml(p.name)}</h3><p>${sub}</p></div></div>
    <table><thead><tr><th></th>${head}</tr></thead><tbody>${body}</tbody></table>
    <div class="card-foot"><span><i class="k on"></i>On time</span><span><i class="k late"></i>Late</span><span><span class="hole sm"></span>Missed</span><b>Pot ${skr(pot(p, now))} SKR</b></div></div>`;
}

export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
