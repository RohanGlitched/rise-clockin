//! Rise: a wake-up alarm with stakes.
//!
//! Friends form a pact, each locks SKR for the length of the pact and picks a
//! wake time. Every morning they clock in on chain inside their wake window.
//! A missed morning forfeits that day's stake to the pot, and when the pact ends
//! the pot is shared among everyone in proportion to the mornings they kept.
//!
//! Payout for member i (stake s per day, h_i mornings kept, H mornings kept by all):
//!   pot      = total deposited - s * H
//!   payout_i = s * h_i + pot * h_i / H
//! Payouts sum to the total deposited, so the vault always covers every claim.

use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_2022::spl_token_2022::extension::ExtensionType;
use anchor_spl::token_interface::{
    self, Mint, MintTo, TokenAccount, TokenInterface, TransferChecked,
};

declare_id!("6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS");

/// Longest pact, in days. Each member keeps one clock-in record per day.
pub const MAX_DAYS: usize = 64;
/// How early before the wake time a clock-in counts.
pub const EARLY_SECS: i64 = 30 * 60;
/// Marks a day with no clock-in.
pub const NOT_IN: i16 = i16::MAX;
/// Test SKR the faucet sends per drip (6 decimals).
pub const DRIP: u64 = 500_000_000;
/// Time between drips for one wallet.
pub const DRIP_COOLDOWN: i64 = 60 * 60;

#[program]
pub mod rise {
    use super::*;

    /// One-time setup of the faucet for the test SKR mint. The mint's authority
    /// must already be the program's `mint_auth` PDA.
    pub fn init_faucet(ctx: Context<InitFaucet>) -> Result<()> {
        let f = &mut ctx.accounts.faucet;
        f.mint = ctx.accounts.mint.key();
        f.bump = ctx.bumps.faucet;
        f.drips = 0;
        Ok(())
    }

    /// Sends test SKR to the caller, at most once an hour per wallet.
    pub fn drip(ctx: Context<Drip>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let ticket = &mut ctx.accounts.ticket;
        require!(
            ticket.last_ts == 0 || now - ticket.last_ts >= DRIP_COOLDOWN,
            RiseError::DripTooSoon
        );
        ticket.last_ts = now;
        ticket.bump = ctx.bumps.ticket;
        ctx.accounts.faucet.drips = ctx.accounts.faucet.drips.checked_add(1).ok_or(RiseError::Overflow)?;

        let seeds: &[&[u8]] = &[b"mint_auth", &[ctx.bumps.mint_auth]];
        token_interface::mint_to(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                MintTo {
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.user_ata.to_account_info(),
                    authority: ctx.accounts.mint_auth.to_account_info(),
                },
                &[seeds],
            ),
            DRIP,
        )
    }

    /// Starts a pact. `start_ts` is the start of day 0 (the app uses UTC midnight).
    #[allow(clippy::too_many_arguments)]
    pub fn create_pact(
        ctx: Context<CreatePact>,
        seed: u64,
        name: String,
        stake_per_day: u64,
        start_ts: i64,
        day_secs: u32,
        days: u16,
        grace_secs: u16,
        max_members: u16,
        public: bool,
    ) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        require!(!name.trim().is_empty() && name.len() <= 32, RiseError::BadName);
        require!(stake_per_day > 0, RiseError::BadStake);
        require!(days >= 1 && days as usize <= MAX_DAYS, RiseError::BadDays);
        require!(day_secs >= 600, RiseError::BadDayLength);
        require!(grace_secs >= 60 && grace_secs <= 3600, RiseError::BadGrace);
        require!(
            early(day_secs) + (grace_secs as i64) < (day_secs as i64),
            RiseError::BadGrace
        );
        require!((2..=500).contains(&max_members), RiseError::BadMembers);
        require!(start_ts >= now - day_secs as i64, RiseError::StartInPast);
        check_mint(&ctx.accounts.mint.to_account_info())?;

        let p = &mut ctx.accounts.pact;
        p.bump = ctx.bumps.pact;
        p.creator = ctx.accounts.creator.key();
        p.seed = seed;
        p.name = name;
        p.mint = ctx.accounts.mint.key();
        p.stake_per_day = stake_per_day;
        p.start_ts = start_ts;
        p.day_secs = day_secs;
        p.days = days;
        p.grace_secs = grace_secs;
        p.max_members = max_members;
        p.member_count = 0;
        p.public = public;
        p.total_deposited = 0;
        p.total_hits = 0;
        p.total_paid = 0;
        p.claims = 0;
        Ok(())
    }

    /// Joins a pact with a wake time, given as seconds after the start of each pact day.
    /// The member's first day is the first one whose wake window has not opened yet,
    /// and the deposit covers the remaining days.
    pub fn join(ctx: Context<Join>, name: String, avatar: u8, wake_offset: u32) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let pact = &mut ctx.accounts.pact;
        require!(!name.trim().is_empty() && name.len() <= 20, RiseError::BadName);
        require!(wake_offset < pact.day_secs, RiseError::BadWake);
        require!(pact.member_count < pact.max_members, RiseError::PactFull);

        let first_day = first_open_day(pact, wake_offset as i64, now);
        require!(first_day < pact.days as i64, RiseError::PactOver);
        let first_day = first_day as u16;
        let deposit = pact
            .stake_per_day
            .checked_mul((pact.days - first_day) as u64)
            .ok_or(RiseError::Overflow)?;

        token_interface::transfer_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.user_ata.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                    authority: ctx.accounts.owner.to_account_info(),
                },
            ),
            deposit,
            ctx.accounts.mint.decimals,
        )?;

        pact.member_count = pact.member_count.checked_add(1).ok_or(RiseError::Overflow)?;
        pact.total_deposited = pact.total_deposited.checked_add(deposit).ok_or(RiseError::Overflow)?;

        let m = &mut ctx.accounts.member;
        m.bump = ctx.bumps.member;
        m.pact = pact.key();
        m.owner = ctx.accounts.owner.key();
        m.name = name;
        m.avatar = avatar;
        m.wake_offset = wake_offset;
        m.first_day = first_day;
        m.deposit = deposit;
        m.hits = 0;
        m.streak = 0;
        m.best_streak = 0;
        m.last_hit_day = -1;
        m.claimed = false;
        m.joined_ts = now;
        m.offsets = [NOT_IN; MAX_DAYS];
        Ok(())
    }

    /// Clocks in for today. Counts only inside the member's wake window:
    /// from 30 minutes before the wake time until the pact's grace period after it.
    pub fn clock_in(ctx: Context<ClockIn>, mission: u8) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let pact = &mut ctx.accounts.pact;
        let m = &mut ctx.accounts.member;

        let day_secs = pact.day_secs as i64;
        let since = now - pact.start_ts - m.wake_offset as i64 + early(pact.day_secs);
        require!(since >= 0, RiseError::WindowClosed);
        let day = since / day_secs;
        let target = pact.start_ts + day * day_secs + m.wake_offset as i64;
        require!(now <= target + pact.grace_secs as i64, RiseError::WindowClosed);
        require!(day >= m.first_day as i64 && day < pact.days as i64, RiseError::NotYourDay);
        let d = day as usize;
        require!(m.offsets[d] == NOT_IN, RiseError::AlreadyIn);

        let delta = (now - target) as i16;
        m.offsets[d] = delta;
        m.hits = m.hits.checked_add(1).ok_or(RiseError::Overflow)?;
        m.streak = if m.last_hit_day == day as i32 - 1 { m.streak + 1 } else { 1 };
        m.best_streak = m.best_streak.max(m.streak);
        m.last_hit_day = day as i32;
        pact.total_hits = pact.total_hits.checked_add(1).ok_or(RiseError::Overflow)?;

        emit!(ClockedIn {
            pact: pact.key(),
            owner: m.owner,
            day: day as u16,
            delta,
            mission,
            streak: m.streak,
            ts: now,
        });
        Ok(())
    }

    /// After the pact ends, pays the member their kept stakes plus their share of the pot.
    pub fn claim(ctx: Context<Claim>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let pact = &ctx.accounts.pact;
        require!(now > end_ts(pact), RiseError::NotOver);
        let m = &mut ctx.accounts.member;
        require!(!m.claimed, RiseError::AlreadyClaimed);

        let amount = payout(pact, m)?;
        m.claimed = true;

        let creator = pact.creator;
        let seed = pact.seed.to_le_bytes();
        let bump = pact.bump;
        let seeds: &[&[u8]] = &[b"pact", creator.as_ref(), &seed, &[bump]];
        if amount > 0 {
            token_interface::transfer_checked(
                CpiContext::new_with_signer(
                    ctx.accounts.token_program.to_account_info(),
                    TransferChecked {
                        from: ctx.accounts.vault.to_account_info(),
                        mint: ctx.accounts.mint.to_account_info(),
                        to: ctx.accounts.user_ata.to_account_info(),
                        authority: ctx.accounts.pact.to_account_info(),
                    },
                    &[seeds],
                ),
                amount,
                ctx.accounts.mint.decimals,
            )?;
        }
        let pact = &mut ctx.accounts.pact;
        pact.total_paid = pact.total_paid.checked_add(amount).ok_or(RiseError::Overflow)?;
        pact.claims = pact.claims.checked_add(1).ok_or(RiseError::Overflow)?;
        Ok(())
    }
}

/// Token-2022 extensions a pact's mint may carry. Pacts hold stakes for weeks, so anything
/// that lets someone other than this program move, tax, block or freeze the vault's tokens
/// (permanent delegate, transfer fees, transfer hooks, pausing, default-frozen accounts...)
/// is refused. Metadata and token-group extensions only describe the token.
const SAFE_MINT_EXTENSIONS: [ExtensionType; 6] = [
    ExtensionType::MetadataPointer,
    ExtensionType::TokenMetadata,
    ExtensionType::GroupPointer,
    ExtensionType::TokenGroup,
    ExtensionType::GroupMemberPointer,
    ExtensionType::TokenGroupMember,
];

/// Walks the mint's extension list (type-length-value entries after the 165-byte base and
/// the account-type byte). Classic SPL Token mints have no extensions and always pass.
fn check_mint(mint: &AccountInfo) -> Result<()> {
    let data = mint.try_borrow_data()?;
    let mut i = 166;
    while i + 4 <= data.len() {
        let ty = u16::from_le_bytes([data[i], data[i + 1]]);
        if ty == ExtensionType::Uninitialized as u16 {
            break;
        }
        require!(
            SAFE_MINT_EXTENSIONS.iter().any(|e| *e as u16 == ty),
            RiseError::UnsafeMint
        );
        let len = u16::from_le_bytes([data[i + 2], data[i + 3]]) as usize;
        i += 4 + len;
    }
    Ok(())
}

fn early(day_secs: u32) -> i64 {
    EARLY_SECS.min(day_secs as i64 / 4)
}

/// First day whose wake window opens after `now`.
fn first_open_day(p: &Pact, wake_offset: i64, now: i64) -> i64 {
    let day_secs = p.day_secs as i64;
    let opens0 = p.start_ts + wake_offset - early(p.day_secs);
    if now < opens0 {
        return 0;
    }
    (now - opens0) / day_secs + 1
}

/// The last wake window of the pact has closed by this time.
pub fn end_ts(p: &Pact) -> i64 {
    p.start_ts + p.days as i64 * p.day_secs as i64 + p.grace_secs as i64
}

pub fn payout(p: &Pact, m: &Member) -> Result<u64> {
    if p.total_hits == 0 {
        return Ok(m.deposit);
    }
    let kept = p.stake_per_day as u128 * m.hits as u128;
    let pot = (p.total_deposited as u128)
        .checked_sub(p.stake_per_day as u128 * p.total_hits as u128)
        .ok_or(RiseError::Overflow)?;
    let share = pot * m.hits as u128 / p.total_hits as u128;
    Ok((kept + share) as u64)
}

// ---------------------------------------------------------------- accounts

#[account]
#[derive(InitSpace)]
pub struct Faucet {
    pub mint: Pubkey,
    pub drips: u64,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct DripTicket {
    pub last_ts: i64,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Pact {
    pub creator: Pubkey,
    pub seed: u64,
    #[max_len(32)]
    pub name: String,
    pub mint: Pubkey,
    pub stake_per_day: u64,
    pub start_ts: i64,
    pub day_secs: u32,
    pub days: u16,
    pub grace_secs: u16,
    pub max_members: u16,
    pub member_count: u16,
    pub public: bool,
    pub total_deposited: u64,
    pub total_hits: u32,
    pub total_paid: u64,
    pub claims: u16,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Member {
    pub pact: Pubkey,
    pub owner: Pubkey,
    #[max_len(20)]
    pub name: String,
    pub avatar: u8,
    pub wake_offset: u32,
    pub first_day: u16,
    pub deposit: u64,
    pub hits: u16,
    pub streak: u16,
    pub best_streak: u16,
    pub last_hit_day: i32,
    pub claimed: bool,
    pub joined_ts: i64,
    /// Seconds from the wake time to the clock-in for each day (negative = early);
    /// `NOT_IN` when the member did not clock in.
    pub offsets: [i16; MAX_DAYS],
    pub bump: u8,
}

#[derive(Accounts)]
pub struct InitFaucet<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(init, payer = payer, space = 8 + Faucet::INIT_SPACE, seeds = [b"faucet"], bump)]
    pub faucet: Account<'info, Faucet>,
    /// CHECK: PDA that must be the mint authority.
    #[account(seeds = [b"mint_auth"], bump)]
    pub mint_auth: UncheckedAccount<'info>,
    #[account(mint::authority = mint_auth, mint::token_program = token_program)]
    pub mint: InterfaceAccount<'info, Mint>,
    pub token_program: Interface<'info, TokenInterface>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Drip<'info> {
    #[account(mut)]
    pub user: Signer<'info>,
    #[account(mut, seeds = [b"faucet"], bump = faucet.bump, has_one = mint)]
    pub faucet: Account<'info, Faucet>,
    #[account(
        init_if_needed, payer = user, space = 8 + DripTicket::INIT_SPACE,
        seeds = [b"drip", user.key().as_ref()], bump
    )]
    pub ticket: Account<'info, DripTicket>,
    /// CHECK: PDA mint authority, signs the mint.
    #[account(seeds = [b"mint_auth"], bump)]
    pub mint_auth: UncheckedAccount<'info>,
    #[account(mut)]
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(
        init_if_needed, payer = user,
        associated_token::mint = mint, associated_token::authority = user,
        associated_token::token_program = token_program
    )]
    pub user_ata: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(seed: u64)]
pub struct CreatePact<'info> {
    #[account(mut)]
    pub creator: Signer<'info>,
    #[account(
        init, payer = creator, space = 8 + Pact::INIT_SPACE,
        seeds = [b"pact", creator.key().as_ref(), &seed.to_le_bytes()], bump
    )]
    pub pact: Account<'info, Pact>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(
        init, payer = creator,
        associated_token::mint = mint, associated_token::authority = pact,
        associated_token::token_program = token_program
    )]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Join<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(mut, has_one = mint)]
    pub pact: Account<'info, Pact>,
    #[account(
        init, payer = owner, space = 8 + Member::INIT_SPACE,
        seeds = [b"member", pact.key().as_ref(), owner.key().as_ref()], bump
    )]
    pub member: Account<'info, Member>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(
        mut, associated_token::mint = mint, associated_token::authority = owner,
        associated_token::token_program = token_program
    )]
    pub user_ata: InterfaceAccount<'info, TokenAccount>,
    #[account(
        mut, associated_token::mint = mint, associated_token::authority = pact,
        associated_token::token_program = token_program
    )]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct ClockIn<'info> {
    pub owner: Signer<'info>,
    #[account(mut)]
    pub pact: Account<'info, Pact>,
    #[account(
        mut, has_one = owner, has_one = pact,
        seeds = [b"member", pact.key().as_ref(), owner.key().as_ref()], bump = member.bump
    )]
    pub member: Account<'info, Member>,
}

#[derive(Accounts)]
pub struct Claim<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(mut, has_one = mint)]
    pub pact: Account<'info, Pact>,
    #[account(
        mut, has_one = owner, has_one = pact,
        seeds = [b"member", pact.key().as_ref(), owner.key().as_ref()], bump = member.bump
    )]
    pub member: Account<'info, Member>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(
        init_if_needed, payer = owner,
        associated_token::mint = mint, associated_token::authority = owner,
        associated_token::token_program = token_program
    )]
    pub user_ata: InterfaceAccount<'info, TokenAccount>,
    #[account(
        mut, associated_token::mint = mint, associated_token::authority = pact,
        associated_token::token_program = token_program
    )]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

// ---------------------------------------------------------------- events

#[event]
pub struct ClockedIn {
    pub pact: Pubkey,
    pub owner: Pubkey,
    pub day: u16,
    pub delta: i16,
    pub mission: u8,
    pub streak: u16,
    pub ts: i64,
}

#[error_code]
pub enum RiseError {
    #[msg("Name must be 1 to 32 characters")]
    BadName,
    #[msg("Stake per day must be more than zero")]
    BadStake,
    #[msg("A pact lasts 1 to 64 days")]
    BadDays,
    #[msg("A pact day must be at least 10 minutes")]
    BadDayLength,
    #[msg("Grace period must be 1 to 60 minutes")]
    BadGrace,
    #[msg("A pact has 2 to 500 members")]
    BadMembers,
    #[msg("The pact cannot start in the past")]
    StartInPast,
    #[msg("Wake time is outside the pact day")]
    BadWake,
    #[msg("This pact is full")]
    PactFull,
    #[msg("This pact has no days left to join")]
    PactOver,
    #[msg("Your wake window is not open")]
    WindowClosed,
    #[msg("This day is not part of your pact")]
    NotYourDay,
    #[msg("You already clocked in today")]
    AlreadyIn,
    #[msg("The pact has not ended yet")]
    NotOver,
    #[msg("You already claimed your payout")]
    AlreadyClaimed,
    #[msg("Test SKR can be claimed once an hour")]
    DripTooSoon,
    #[msg("Amount too large")]
    Overflow,
    #[msg("This token has extensions that could move or freeze pact funds")]
    UnsafeMint,
}
