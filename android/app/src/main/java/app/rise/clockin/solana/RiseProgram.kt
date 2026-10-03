package app.rise.clockin.solana

import org.sol4k.AccountMeta
import org.sol4k.Base58
import org.sol4k.PublicKey
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.Instruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Addresses of the Rise deployment on Solana devnet. */
object Config {
    const val CLUSTER = "devnet"
    val PROGRAM = PublicKey("6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS")
    /** Test SKR: a Token-2022 mint the Rise faucet can drip on devnet. */
    val SKR_MINT = PublicKey("SKRxp6EbHDAzboW6GvwhL8ARHDU5pQLmtZEh7t4XX38")
    const val SKR_DECIMALS = 6
    val TOKEN_2022 = PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ATA_PROGRAM = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val SYSTEM = PublicKey("11111111111111111111111111111111")
    const val NOT_IN: Short = Short.MAX_VALUE
    const val EARLY_SECS = 30 * 60
    const val DAY = 86_400

    fun explorerTx(sig: String) = "https://explorer.solana.com/tx/$sig?cluster=devnet"
    fun explorerAddress(a: String) = "https://explorer.solana.com/address/$a?cluster=devnet"
}

data class Pact(
    val address: String,
    val creator: String,
    val seed: Long,
    val name: String,
    val mint: String,
    val stakePerDay: Long,
    val startTs: Long,
    val daySecs: Int,
    val days: Int,
    val graceSecs: Int,
    val maxMembers: Int,
    val memberCount: Int,
    val public: Boolean,
    val totalDeposited: Long,
    val totalHits: Int,
    val totalPaid: Long,
    val claims: Int,
) {
    val endTs get() = startTs + days.toLong() * daySecs + graceSecs
    fun dayIndex(now: Long): Int = if (now < startTs) -1 else ((now - startTs) / daySecs).toInt()
    fun isOver(now: Long) = now > endTs
    /** Forfeited stakes so far: deposits minus every stake still earned back. */
    fun potSoFar(members: List<Member>, now: Long): Long = members.sumOf { it.forfeited(this, now) } * stakePerDay
}

data class Member(
    val address: String,
    val pact: String,
    val owner: String,
    val name: String,
    val avatar: Int,
    val wakeOffset: Int,
    val firstDay: Int,
    val deposit: Long,
    val hits: Int,
    val streak: Int,
    val bestStreak: Int,
    val lastHitDay: Int,
    val claimed: Boolean,
    val joinedTs: Long,
    val offsets: ShortArray,
) {
    fun target(p: Pact, day: Int): Long = p.startTs + day.toLong() * p.daySecs + wakeOffset
    fun windowOpens(p: Pact, day: Int) = target(p, day) - minOf(Config.EARLY_SECS.toLong(), p.daySecs / 4L)
    fun windowCloses(p: Pact, day: Int) = target(p, day) + p.graceSecs
    fun isIn(day: Int) = day in offsets.indices && offsets[day] != Config.NOT_IN

    /** Days whose window has closed without a clock-in. */
    fun forfeited(p: Pact, now: Long): Int =
        (firstDay until p.days).count { d -> !isIn(d) && now > windowCloses(p, d) }

    /** The day whose wake window is open right now, if any and not already used. */
    fun openDay(p: Pact, now: Long): Int? = (firstDay until p.days).firstOrNull { d ->
        now in windowOpens(p, d)..windowCloses(p, d) && !isIn(d)
    }

    /** Payout if the pact ended now (mirrors the program's formula). */
    fun projectedPayout(p: Pact): Long {
        if (p.totalHits == 0) return deposit
        val pot = p.totalDeposited - p.stakePerDay * p.totalHits
        return p.stakePerDay * hits + pot * hits / p.totalHits
    }
}

object RiseProgram {
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun ixDisc(name: String) = sha("global:$name").copyOf(8)
    private fun accDisc(name: String) = sha("account:$name").copyOf(8)

    val PACT_DISC = accDisc("Pact")
    val MEMBER_DISC = accDisc("Member")

    // sol4k takes seeds as PublicKey wrappers; any byte length works.
    fun pda(vararg seeds: ByteArray): PublicKey = PublicKey.findProgramAddress(seeds.map { PublicKey(it) }, Config.PROGRAM).publicKey
    fun faucet() = pda("faucet".toByteArray())
    fun mintAuth() = pda("mint_auth".toByteArray())
    fun ticket(user: PublicKey) = pda("drip".toByteArray(), user.bytes())
    fun pact(creator: PublicKey, seed: Long) = pda("pact".toByteArray(), creator.bytes(), le64(seed))
    fun member(pact: PublicKey, owner: PublicKey) = pda("member".toByteArray(), pact.bytes(), owner.bytes())
    fun ata(owner: PublicKey, mint: PublicKey = Config.SKR_MINT): PublicKey =
        PublicKey.findProgramAddress(listOf(owner, Config.TOKEN_2022, mint), Config.ATA_PROGRAM).publicKey

    // -------------------------------------------------------------- instructions

    fun drip(user: PublicKey): Instruction = BaseInstruction(
        ixDisc("drip"),
        listOf(
            AccountMeta.signerAndWritable(user),
            AccountMeta.writable(faucet()),
            AccountMeta.writable(ticket(user)),
            AccountMeta(mintAuth()),
            AccountMeta.writable(Config.SKR_MINT),
            AccountMeta.writable(ata(user)),
            AccountMeta(Config.TOKEN_2022),
            AccountMeta(Config.ATA_PROGRAM),
            AccountMeta(Config.SYSTEM),
        ),
        Config.PROGRAM,
    )

    fun createPact(
        creator: PublicKey, seed: Long, name: String, stakePerDay: Long, startTs: Long,
        daySecs: Int, days: Int, graceSecs: Int, maxMembers: Int, public: Boolean,
    ): Instruction {
        val pact = pact(creator, seed)
        val data = Buf().bytes(ixDisc("create_pact")).u64(seed).str(name).u64(stakePerDay).u64(startTs)
            .u32(daySecs).u16(days).u16(graceSecs).u16(maxMembers).u8(if (public) 1 else 0).done()
        return BaseInstruction(
            data,
            listOf(
                AccountMeta.signerAndWritable(creator),
                AccountMeta.writable(pact),
                AccountMeta(Config.SKR_MINT),
                AccountMeta.writable(ata(pact)),
                AccountMeta(Config.TOKEN_2022),
                AccountMeta(Config.ATA_PROGRAM),
                AccountMeta(Config.SYSTEM),
            ),
            Config.PROGRAM,
        )
    }

    fun join(owner: PublicKey, pact: PublicKey, name: String, avatar: Int, wakeOffset: Int): Instruction = BaseInstruction(
        Buf().bytes(ixDisc("join")).str(name).u8(avatar).u32(wakeOffset).done(),
        listOf(
            AccountMeta.signerAndWritable(owner),
            AccountMeta.writable(pact),
            AccountMeta.writable(member(pact, owner)),
            AccountMeta(Config.SKR_MINT),
            AccountMeta.writable(ata(owner)),
            AccountMeta.writable(ata(pact)),
            AccountMeta(Config.TOKEN_2022),
            AccountMeta(Config.SYSTEM),
        ),
        Config.PROGRAM,
    )

    fun clockIn(owner: PublicKey, pact: PublicKey, mission: Int): Instruction = BaseInstruction(
        Buf().bytes(ixDisc("clock_in")).u8(mission).done(),
        listOf(
            AccountMeta.signer(owner),
            AccountMeta.writable(pact),
            AccountMeta.writable(member(pact, owner)),
        ),
        Config.PROGRAM,
    )

    fun claim(owner: PublicKey, pact: PublicKey): Instruction = BaseInstruction(
        ixDisc("claim"),
        listOf(
            AccountMeta.signerAndWritable(owner),
            AccountMeta.writable(pact),
            AccountMeta.writable(member(pact, owner)),
            AccountMeta(Config.SKR_MINT),
            AccountMeta.writable(ata(owner)),
            AccountMeta.writable(ata(pact)),
            AccountMeta(Config.TOKEN_2022),
            AccountMeta(Config.ATA_PROGRAM),
            AccountMeta(Config.SYSTEM),
        ),
        Config.PROGRAM,
    )

    // -------------------------------------------------------------- decoding

    fun decodePact(address: String, data: ByteArray): Pact? {
        if (data.size < 8 || !data.copyOf(8).contentEquals(PACT_DISC)) return null
        val r = Reader(data, 8)
        return Pact(
            address = address,
            creator = r.key(),
            seed = r.u64(),
            name = r.str(),
            mint = r.key(),
            stakePerDay = r.u64(),
            startTs = r.u64(),
            daySecs = r.u32(),
            days = r.u16(),
            graceSecs = r.u16(),
            maxMembers = r.u16(),
            memberCount = r.u16(),
            public = r.u8() != 0,
            totalDeposited = r.u64(),
            totalHits = r.u32(),
            totalPaid = r.u64(),
            claims = r.u16(),
        )
    }

    fun decodeMember(address: String, data: ByteArray): Member? {
        if (data.size < 8 || !data.copyOf(8).contentEquals(MEMBER_DISC)) return null
        val r = Reader(data, 8)
        return Member(
            address = address,
            pact = r.key(),
            owner = r.key(),
            name = r.str(),
            avatar = r.u8(),
            wakeOffset = r.u32(),
            firstDay = r.u16(),
            deposit = r.u64(),
            hits = r.u16(),
            streak = r.u16(),
            bestStreak = r.u16(),
            lastHitDay = r.i32(),
            claimed = r.u8() != 0,
            joinedTs = r.u64(),
            offsets = ShortArray(64) { r.i16() },
        )
    }

    /** Human sentence for a failed program call, from its logs. */
    fun explain(e: Throwable): String {
        val text = (e.message ?: "") + " " + ((e as? RpcException)?.logs?.joinToString(" ") ?: "")
        val known = mapOf(
            "WindowClosed" to "Your wake window isn't open. It opens 30 minutes before your wake time and closes after the grace period.",
            "AlreadyIn" to "You already clocked in this morning.",
            "NotYourDay" to "This morning isn't part of your pact.",
            "PactFull" to "This pact is full.",
            "PactOver" to "This pact has no mornings left to join.",
            "DripTooSoon" to "You can get test SKR once an hour. Try again a little later.",
            "NotOver" to "The pact hasn't ended yet.",
            "AlreadyClaimed" to "You already collected your payout.",
            "BadName" to "Pick a name between 1 and 20 characters.",
            "insufficient funds" to "Not enough test SKR. Tap Get test SKR first.",
            "Attempt to debit an account but found no record of a prior credit" to "This wallet needs a little devnet SOL for fees. Tap Get test SKR.",
            "already in use" to "That already exists on chain. Pull to refresh.",
        )
        known.forEach { (k, v) -> if (text.contains(k, ignoreCase = true)) return v }
        return e.message?.takeIf { it.length < 140 } ?: "Something went wrong talking to Solana. Try again."
    }

    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}

private class Buf {
    private val out = java.io.ByteArrayOutputStream()
    private fun le(n: Int, v: Long) = apply { for (i in 0 until n) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun u8(v: Int) = le(1, v.toLong())
    fun u16(v: Int) = le(2, v.toLong())
    fun u32(v: Int) = le(4, v.toLong())
    fun u64(v: Long) = le(8, v)
    fun str(s: String) = apply { val b = s.toByteArray(); u32(b.size); out.write(b) }
    fun done(): ByteArray = out.toByteArray()
}

private class Reader(val d: ByteArray, var p: Int) {
    private val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    fun u8() = (d[p++].toInt() and 0xFF)
    fun u16() = (bb.getShort(p).toInt() and 0xFFFF).also { p += 2 }
    fun i16() = bb.getShort(p).also { p += 2 }
    fun u32() = bb.getInt(p).also { p += 4 }
    fun i32() = bb.getInt(p).also { p += 4 }
    fun u64() = bb.getLong(p).also { p += 8 }
    fun key() = Base58.encode(d.copyOfRange(p, p + 32)).also { p += 32 }
    fun str(): String { val n = u32(); return String(d, p, n).also { p += n } }
}
