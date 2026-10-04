// The mint guard: which Token-2022 extensions create_pact accepts and refuses.
//
// check_mint walks the mint's TLV list and refuses any extension outside an allowlist of six
// descriptive ones (metadata and token-group). Each denied extension the bundled Token-2022
// program can initialise gets its own case here, so a regression names the extension.
import { PublicKey, TransactionInstruction } from "@solana/web3.js";
import {
  TOKEN_PROGRAM_ID,
  TOKEN_2022_PROGRAM_ID,
  AccountState,
  ExtensionType,
  getMintLen,
  MintLayout,
  createFreezeAccountInstruction,
  createInitializeDefaultAccountStateInstruction,
  createInitializeGroupMemberPointerInstruction,
  createInitializeGroupPointerInstruction,
  createInitializeInterestBearingMintInstruction,
  createInitializeMetadataPointerInstruction,
  createInitializeMintCloseAuthorityInstruction,
  createInitializeNonTransferableMintInstruction,
  createInitializePermanentDelegateInstruction,
  createInitializeTransferFeeConfigInstruction,
  createInitializeTransferHookInstruction,
} from "@solana/spl-token";
import { createInitializeInstruction as createInitializeMetadataInstruction, pack, TokenMetadata } from "@solana/spl-token-metadata";
import { expect } from "chai";
import { DAY, HOUR, Harness, SKR } from "./helpers/harness";

describe("mint guard (UnsafeMint)", () => {
  let h: Harness;
  before(async () => {
    h = await Harness.create();
  });

  /** Creates a mint carrying `exts`, then tries to open a pact with it. */
  async function pactWithMint(exts: ExtensionType[], init: (m: PublicKey) => TransactionInstruction[], extra: { freeze?: PublicKey; len?: number } = {}) {
    const mint = await h.createMint({ exts, init, freeze: extra.freeze, len: extra.len });
    const { r } = await h.tryCreatePact({ mint, name: "Mint check" });
    return r;
  }

  const admin = () => h.admin.publicKey;
  const denied: [string, ExtensionType[], (m: PublicKey) => TransactionInstruction[], { freeze?: boolean; len?: number }?][] = [
    ["TransferFeeConfig (taxes every transfer, vault ends short)", [ExtensionType.TransferFeeConfig],
      (m) => [createInitializeTransferFeeConfigInstruction(m, admin(), admin(), 100, 1_000_000n, TOKEN_2022_PROGRAM_ID)]],
    ["PermanentDelegate (can pull tokens out of the vault)", [ExtensionType.PermanentDelegate],
      (m) => [createInitializePermanentDelegateInstruction(m, admin(), TOKEN_2022_PROGRAM_ID)]],
    ["TransferHook (third-party program can block or tax transfers)", [ExtensionType.TransferHook],
      (m) => [createInitializeTransferHookInstruction(m, admin(), PublicKey.unique(), TOKEN_2022_PROGRAM_ID)]],
    ["NonTransferable (claims could never pay out)", [ExtensionType.NonTransferable],
      (m) => [createInitializeNonTransferableMintInstruction(m, TOKEN_2022_PROGRAM_ID)]],
    ["DefaultAccountState=Frozen (vault starts frozen)", [ExtensionType.DefaultAccountState],
      (m) => [createInitializeDefaultAccountStateInstruction(m, AccountState.Frozen, TOKEN_2022_PROGRAM_ID)], { freeze: true }],
    ["MintCloseAuthority", [ExtensionType.MintCloseAuthority],
      (m) => [createInitializeMintCloseAuthorityInstruction(m, admin(), TOKEN_2022_PROGRAM_ID)]],
    ["InterestBearingConfig", [ExtensionType.InterestBearingConfig],
      (m) => [createInitializeInterestBearingMintInstruction(m, admin(), 500, TOKEN_2022_PROGRAM_ID)]],
    // The JS library can't size or build this one, so the instruction is written by hand:
    // ConfidentialTransferExtension (27) / InitializeMint (0), no authority, no auto-approve, no auditor.
    ["ConfidentialTransferMint", [], (m) => [
      new TransactionInstruction({
        programId: TOKEN_2022_PROGRAM_ID,
        keys: [{ pubkey: m, isSigner: false, isWritable: true }],
        data: Buffer.concat([Buffer.from([27, 0]), Buffer.alloc(32), Buffer.from([0]), Buffer.alloc(32)]),
      }),
    ], { len: 165 + 1 + 4 + 65 }],
    ["an allowed extension followed by a denied one (MetadataPointer + PermanentDelegate)",
      [ExtensionType.MetadataPointer, ExtensionType.PermanentDelegate],
      (m) => [
        createInitializeMetadataPointerInstruction(m, admin(), m, TOKEN_2022_PROGRAM_ID),
        createInitializePermanentDelegateInstruction(m, admin(), TOKEN_2022_PROGRAM_ID),
      ]],
  ];

  for (const [label, exts, init, extra] of denied) {
    it(`refuses ${label}`, async () => {
      const r = await pactWithMint(exts, init, { freeze: extra?.freeze ? admin() : undefined, len: extra?.len });
      h.fails(r, "UnsafeMint");
    });
  }

  // Pausable (26), ScaledUiAmount (25), PermissionedBurn (28) and ConfidentialMintBurn (24) are newer
  // than the Token-2022 build bundled with Bankrun, so it can't create them and rejects such mints
  // itself (InvalidAccountData while sizing the vault) before check_mint runs. On a live cluster the
  // vault gets created and check_mint refuses them: its allowlist compares raw u16 type codes, so
  // any type it doesn't list, including ones added after it was written, is UnsafeMint.
  it("refuses mints with extensions newer than the bundled Token-2022 (Pausable, ScaledUiAmount, PermissionedBurn, ConfidentialMintBurn)", async () => {
    for (const [ty, len] of [[26, 33], [25, 56], [28, 32], [24, 64]]) {
      const base = Buffer.alloc(82);
      MintLayout.encode(
        { mintAuthorityOption: 1, mintAuthority: admin(), supply: 0n, decimals: 6, isInitialized: true, freezeAuthorityOption: 0, freezeAuthority: PublicKey.default } as any,
        base,
      );
      const tlv = Buffer.alloc(4 + len);
      tlv.writeUInt16LE(ty, 0);
      tlv.writeUInt16LE(len, 2);
      const mint = PublicKey.unique();
      h.ctx.setAccount(mint, {
        lamports: 1_000_000_000,
        data: Buffer.concat([base, Buffer.alloc(165 - 82), Buffer.from([1]), tlv]),
        owner: TOKEN_2022_PROGRAM_ID,
        executable: false,
      });
      const { r } = await h.tryCreatePact({ mint });
      expect(r.ok, `extension type ${ty} was accepted`).to.equal(false);
      expect([r.err, ...r.logs].join("\n")).to.match(/UnsafeMint|InvalidAccountData|invalid account data/);
    }
  });

  it("accepts MetadataPointer with on-mint TokenMetadata (what test SKR carries)", async () => {
    const meta: TokenMetadata = {
      mint: PublicKey.default,
      name: "Test SKR",
      symbol: "SKR",
      uri: "https://rise-clockin.vercel.app/skr.json",
      additionalMetadata: [],
    };
    const metaLen = 4 + pack(meta).length;
    const mint = await h.createMint({
      exts: [ExtensionType.MetadataPointer],
      init: (m) => [createInitializeMetadataPointerInstruction(m, admin(), m, TOKEN_2022_PROGRAM_ID)],
      after: (m) => [
        createInitializeMetadataInstruction({
          programId: TOKEN_2022_PROGRAM_ID,
          metadata: m,
          updateAuthority: admin(),
          mint: m,
          mintAuthority: admin(),
          name: meta.name,
          symbol: meta.symbol,
          uri: meta.uri,
        }),
      ],
      extraLamportsSpace: metaLen,
    });
    const acc = await h.ctx.banksClient.getAccount(mint);
    expect(acc!.data.length).to.be.greaterThan(getMintLen([ExtensionType.MetadataPointer])); // metadata really landed
    const { r } = await h.tryCreatePact({ mint });
    h.ok(r);
  });

  it("accepts GroupPointer and GroupMemberPointer", async () => {
    const mint = await h.createMint({
      exts: [ExtensionType.GroupPointer, ExtensionType.GroupMemberPointer],
      init: (m) => [
        createInitializeGroupPointerInstruction(m, admin(), m, TOKEN_2022_PROGRAM_ID),
        createInitializeGroupMemberPointerInstruction(m, admin(), m, TOKEN_2022_PROGRAM_ID),
      ],
    });
    h.ok((await h.tryCreatePact({ mint })).r);
  });

  it("accepts a Token-2022 mint with no extensions", async () => {
    const mint = await h.createMint();
    h.ok((await h.tryCreatePact({ mint })).r);
  });

  it("accepts a classic SPL Token mint and settles a pact in it end to end", async () => {
    const mint = await h.createMint({ programId: TOKEN_PROGRAM_ID });
    const p = await h.createPact({ mint, tokenProgram: TOKEN_PROGRAM_ID, days: 1, stake: 7n * SKR });
    const a = h.wallet();
    const b = h.wallet();
    await h.mintTo(mint, a.publicKey, 7n * SKR, TOKEN_PROGRAM_ID);
    await h.mintTo(mint, b.publicKey, 7n * SKR, TOKEN_PROGRAM_ID);
    h.ok(await h.join(p, a, 6 * HOUR));
    h.ok(await h.join(p, b, 6 * HOUR));
    h.ok(await h.clockInAt(p, a, p.target(6 * HOUR, 0)));
    await h.setTime(p.end + 1);
    h.ok(await h.claim(p, a));
    h.ok(await h.claim(p, b));
    expect(await h.pactBalance(p, a.publicKey)).to.equal(14n * SKR);
    expect(await h.pactBalance(p, b.publicKey)).to.equal(0n);
    expect(await h.vaultBalance(p)).to.equal(0n);
  });

  // ------------------------------------------------------------------ known issue
  // check_mint reads only the extension list. A freeze authority lives in the base mint, so a
  // mint whose freeze authority is set passes the guard, and that authority can freeze the vault
  // and stop every claim. SECURITY.md promises nobody else can "freeze the vault's tokens".

  it("known issue (evidence): a mint with a freeze authority is accepted, and freezing the vault blocks claims", async () => {
    const mint = await h.createMint({ freeze: admin() });
    const p = await h.createPact({ mint, days: 1 });
    const a = h.wallet();
    const b = h.wallet();
    await h.mintTo(mint, a.publicKey, 10n * SKR);
    await h.mintTo(mint, b.publicKey, 10n * SKR);
    h.ok(await h.join(p, a, 6 * HOUR));
    h.ok(await h.join(p, b, 6 * HOUR));
    h.ok(await h.send([createFreezeAccountInstruction(p.vault, mint, admin(), [], TOKEN_2022_PROGRAM_ID)], [h.admin]));
    await h.setTime(p.end + 1);
    h.fails(await h.claim(p, a), "frozen");
    expect(await h.vaultBalance(p)).to.equal(20n * SKR); // stuck until the freeze authority thaws it
  });

  // documents known issue: create_pact should refuse mints with a freeze authority
  // (suggested fix: `require!(ctx.accounts.mint.freeze_authority.is_none(), RiseError::UnsafeMint)`).
  it.skip("refuses a mint with a freeze authority", async () => {
    const mint = await h.createMint({ freeze: admin() });
    h.fails((await h.tryCreatePact({ mint, start: h.now + DAY })).r, "UnsafeMint");
  });
});
