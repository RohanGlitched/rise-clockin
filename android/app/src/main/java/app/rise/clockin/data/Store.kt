package app.rise.clockin.data

import android.content.Context
import app.rise.clockin.BuildConfig
import app.rise.clockin.solana.Config
import app.rise.clockin.solana.Member
import app.rise.clockin.solana.Pact
import app.rise.clockin.solana.RiseProgram
import app.rise.clockin.solana.Rpc
import app.rise.clockin.solana.Wallet
import app.rise.clockin.solana.WalletKind
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sol4k.Base58
import org.sol4k.PublicKey
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/** How you prove you're up before the alarm stops. */
enum class Mission(val id: Int, val title: String, val line: String) {
    Light(0, "Find the light", "Open the curtains or step outside until the light meter fills."),
    Walk(1, "Walk it off", "Take 30 steps. The alarm counts them."),
    Spot(2, "Scan your wake spot", "Scan the code you stuck by the kettle or the bathroom mirror."),
    Photo(3, "Show the morning", "Point the camera at daylight, a window or your coffee. On-device AI checks it."),
    ;
    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: Light }
}

data class Profile(
    val name: String = "",
    val avatar: Int = 0,
    /** Local wake time, minutes after midnight. */
    val wakeMinutes: Int = 6 * 60 + 30,
    val mission: Mission = Mission.Light,
    val sunriseLead: Int = 5,
    val voice: Boolean = true,
    val alarmOn: Boolean = true,
    val spotCode: String = "",
    val onboarded: Boolean = false,
    val seekerMint: String? = null,
)

data class PactView(val pact: Pact, val members: List<Member>, val me: Member?) {
    val ranked: List<Member> get() = members.sortedWith(compareByDescending<Member> { it.hits }.thenByDescending { it.streak }.thenBy { it.joinedTs })
}

data class AppState(
    val address: String? = null,
    val kind: WalletKind? = null,
    val walletLabel: String? = null,
    val sol: Long = 0,
    val skr: Long = 0,
    val pacts: List<PactView> = emptyList(),
    val discover: List<PactView> = emptyList(),
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
)

/** Result of a clock-in, shown on the stamp screen. */
data class ClockInResult(val signature: String?, val clockedAt: Long, val pacts: List<PactView>, val rehearsal: Boolean)

object Store {
    lateinit var rpc: Rpc; private set
    lateinit var wallet: Wallet; private set
    private lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences("rise.profile", Context.MODE_PRIVATE) }
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val _coach = MutableStateFlow<app.rise.clockin.ai.CoachReport?>(null)
    /** Rise Coach's latest prediction, retrained on the phone after every refresh. */
    val coach: StateFlow<app.rise.clockin.ai.CoachReport?> = _coach.asStateFlow()
    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        rpc = Rpc(BuildConfig.RPC_URL)
        wallet = Wallet(appContext, rpc)
        _profile.value = loadProfile()
        syncWallet()
    }

    // ------------------------------------------------------------ profile

    private fun loadProfile() = Profile(
        name = prefs.getString("name", "") ?: "",
        avatar = prefs.getInt("avatar", 0),
        wakeMinutes = prefs.getInt("wake", 6 * 60 + 30),
        mission = Mission.of(prefs.getInt("mission", 0)),
        sunriseLead = prefs.getInt("lead", 5),
        voice = prefs.getBoolean("voice", true),
        alarmOn = prefs.getBoolean("alarmOn", true),
        spotCode = prefs.getString("spot", null) ?: newSpotCode().also { prefs.edit().putString("spot", it).apply() },
        onboarded = prefs.getBoolean("onboarded", false),
        seekerMint = prefs.getString("seeker", null),
    )

    private fun newSpotCode() = "rise-spot:" + java.util.UUID.randomUUID().toString().take(13)

    fun updateProfile(f: (Profile) -> Profile) {
        val p = f(_profile.value)
        _profile.value = p
        prefs.edit()
            .putString("name", p.name).putInt("avatar", p.avatar).putInt("wake", p.wakeMinutes)
            .putInt("mission", p.mission.id).putInt("lead", p.sunriseLead).putBoolean("voice", p.voice)
            .putBoolean("alarmOn", p.alarmOn).putString("spot", p.spotCode).putBoolean("onboarded", p.onboarded)
            .putString("seeker", p.seekerMint)
            .apply()
    }

    fun newWakeSpot() = updateProfile { it.copy(spotCode = newSpotCode()) }

    private fun syncWallet() = _state.update {
        it.copy(address = wallet.address, kind = wallet.kind, walletLabel = wallet.walletLabel)
    }

    // ------------------------------------------------------------ wallet

    fun usePracticeWallet() { wallet.usePractice(); syncWallet() }

    suspend fun connectPhoneWallet(sender: ActivityResultSender) { wallet.connectPhone(sender); syncWallet() }

    fun signOut() {
        wallet.signOut()
        _state.value = AppState()
        updateProfile { it.copy(seekerMint = null) }
    }

    // ------------------------------------------------------------ reads

    suspend fun refresh() = refreshLock.withLock {
        val address = wallet.address ?: return@withLock
        _state.update { it.copy(loading = true, error = null) }
        try {
            val owner = PublicKey(address)
            val sol = rpc.balance(address)
            val skr = rpc.tokenBalance(RiseProgram.ata(owner).toBase58())
            val memberDisc = Base58.encode(RiseProgram.MEMBER_DISC)
            val mine = rpc.programAccounts(Config.PROGRAM.toBase58(), null, listOf(0 to memberDisc, 40 to address))
                .mapNotNull { (a, d) -> RiseProgram.decodeMember(a, d) }
            val pactDisc = Base58.encode(RiseProgram.PACT_DISC)
            val allPacts = rpc.programAccounts(Config.PROGRAM.toBase58(), null, listOf(0 to pactDisc))
                .mapNotNull { (a, d) -> RiseProgram.decodePact(a, d) }
                .filter { it.mint == Config.SKR_MINT.toBase58() }
            val now = System.currentTimeMillis() / 1000
            val myPactIds = mine.map { it.pact }.toSet()
            val relevant = allPacts.filter { it.address in myPactIds || (it.public && !it.isOver(now) && it.memberCount < it.maxMembers) }
            val members = loadMembers(relevant.map { it.address })
            val views = relevant.map { p ->
                val ms = members[p.address].orEmpty()
                PactView(p, ms, ms.firstOrNull { it.owner == address })
            }
            _state.update {
                it.copy(
                    sol = sol, skr = skr,
                    pacts = views.filter { v -> v.me != null }.sortedWith(compareBy<PactView> { v -> v.pact.isOver(now) }.thenByDescending { v -> v.pact.startTs }),
                    discover = views.filter { v -> v.me == null }.sortedByDescending { v -> v.pact.memberCount },
                    loading = false, loadedOnce = true,
                )
            }
            val report = withContext(Dispatchers.Default) { app.rise.clockin.ai.WakeCoach.report(views, address, now) }
            app.rise.clockin.ai.WakeCoach.current = report
            _coach.value = report
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadedOnce = true, error = RiseProgram.explain(e)) }
        }
    }

    private suspend fun loadMembers(pacts: List<String>): Map<String, List<Member>> {
        if (pacts.isEmpty()) return emptyMap()
        val memberDisc = Base58.encode(RiseProgram.MEMBER_DISC)
        // One scan of all members is cheaper than one request per pact while Rise is young.
        val all = rpc.programAccounts(Config.PROGRAM.toBase58(), null, listOf(0 to memberDisc))
            .mapNotNull { (a, d) -> RiseProgram.decodeMember(a, d) }
        val set = pacts.toSet()
        return all.filter { it.pact in set }.groupBy { it.pact }
    }

    suspend fun loadPact(address: String): PactView? {
        val data = rpc.accountData(address) ?: return null
        val pact = RiseProgram.decodePact(address, data) ?: return null
        val members = loadMembers(listOf(address))[address].orEmpty()
        return PactView(pact, members, members.firstOrNull { it.owner == wallet.address })
    }

    // ------------------------------------------------------------ writes

    /** Tops up devnet SOL for fees from the Rise faucet, then drips 500 test SKR. */
    suspend fun getTestTokens(sender: ActivityResultSender?): String {
        val address = wallet.address ?: throw IOException("Connect a wallet first.")
        if (rpc.balance(address) < 8_000_000) {
            // The Rise faucet first; the cluster's own airdrop as a fallback (always works on a local validator).
            runCatching { requestSol(address) }.recoverCatching {
                rpc.confirm(rpc.requestAirdrop(address, 50_000_000))
            }.getOrThrow()
        }
        val sig = try {
            wallet.send(listOf(RiseProgram.drip(PublicKey(address))), sender)
        } catch (e: Exception) {
            android.util.Log.w("Rise", "drip failed", e); throw e
        }
        refresh()
        return sig
    }

    private suspend fun requestSol(address: String) = withContext(Dispatchers.IO) {
        val conn = (URL(BuildConfig.API_URL + "/api/sol").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("address", address).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val msg = runCatching { JSONObject(conn.errorStream.bufferedReader().readText()).optString("error") }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "The devnet SOL faucet is busy. Try again in a minute.")
            }
            val sig = JSONObject(conn.inputStream.bufferedReader().readText()).optString("signature")
            if (sig.isNotBlank()) rpc.confirm(sig)
        } finally { conn.disconnect() }
    }

    /** Wake offset for a pact, from the local wake time and the pact's UTC day start. */
    fun wakeOffsetFor(pact: Pact, wakeMinutes: Int = profile.value.wakeMinutes): Int {
        val tz = ZonedDateTime.now().offset.totalSeconds
        val utc = Math.floorMod(wakeMinutes * 60 - tz, Config.DAY)
        return Math.floorMod(utc - (pact.startTs % Config.DAY).toInt(), pact.daySecs)
    }

    /** Creates a pact that starts tomorrow (UTC) and joins it in the same transaction. */
    suspend fun startPact(name: String, stakeSkr: Int, days: Int, public: Boolean, sender: ActivityResultSender?): String {
        val address = wallet.address ?: throw IOException("Connect a wallet first.")
        val owner = PublicKey(address)
        val seed = System.currentTimeMillis()
        val now = seed / 1000
        // Start at the most recent UTC midnight, so the first morning can be today if its window hasn't opened yet.
        val start = now - now % Config.DAY
        val stake = stakeSkr * 1_000_000L
        val pactKey = RiseProgram.pact(owner, seed)
        val stub = Pact(pactKey.toBase58(), address, seed, name, Config.SKR_MINT.toBase58(), stake, start, Config.DAY, days, 600, 50, 0, public, 0, 0, 0, 0)
        val p = profile.value
        val ixs = listOf(
            RiseProgram.createPact(owner, seed, name, stake, start, Config.DAY, days, 600, 50, public),
            RiseProgram.join(owner, pactKey, p.name.ifBlank { "Me" }.take(20), p.avatar, wakeOffsetFor(stub)),
        )
        wallet.send(ixs, sender)
        refresh()
        return pactKey.toBase58()
    }

    suspend fun join(pact: Pact, sender: ActivityResultSender?): String {
        val address = wallet.address ?: throw IOException("Connect a wallet first.")
        val p = profile.value
        val sig = wallet.send(listOf(RiseProgram.join(PublicKey(address), PublicKey(pact.address), p.name.ifBlank { "Me" }.take(20), p.avatar, wakeOffsetFor(pact))), sender)
        refresh()
        return sig
    }

    /** Pacts whose wake window is open for me right now. */
    fun openForClockIn(now: Long = System.currentTimeMillis() / 1000): List<PactView> =
        state.value.pacts.filter { v -> v.me?.openDay(v.pact, now) != null }

    suspend fun clockIn(mission: Mission, sender: ActivityResultSender?, proof: app.rise.clockin.ai.ProofScore? = null): ClockInResult {
        refresh()
        val now = System.currentTimeMillis() / 1000
        val open = openForClockIn(now)
        if (open.isEmpty()) return ClockInResult(null, now, emptyList(), rehearsal = true)
        val owner = PublicKey(wallet.address!!)
        val missionByte = proof?.let { app.rise.clockin.ai.ProofTrace.encode(mission.id, it) } ?: mission.id
        val sig = wallet.send(open.map { RiseProgram.clockIn(owner, PublicKey(it.pact.address), missionByte) }, sender)
        refresh()
        val ids = open.map { it.pact.address }.toSet()
        return ClockInResult(sig, now, state.value.pacts.filter { it.pact.address in ids }, rehearsal = false)
    }

    suspend fun claim(pact: Pact, sender: ActivityResultSender?): String {
        val sig = wallet.send(listOf(RiseProgram.claim(PublicKey(wallet.address!!), PublicKey(pact.address))), sender)
        refresh()
        return sig
    }

    /** Asks the Rise server to check the signed-in wallet for a Seeker Genesis Token on mainnet. */
    suspend fun verifySeeker(sender: ActivityResultSender): String? {
        val proof = wallet.signIn(sender)
        val mint = withContext(Dispatchers.IO) {
            val conn = (URL(BuildConfig.API_URL + "/api/seeker").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
                val body = JSONObject().put("address", proof.address).put("message", b64(proof.message)).put("signature", b64(proof.signature))
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException("Seeker check is unavailable right now. Try again soon.")
                JSONObject(conn.inputStream.bufferedReader().readText()).optString("mint").takeIf { it.isNotBlank() }
            } finally { conn.disconnect() }
        }
        updateProfile { it.copy(seekerMint = mint) }
        return mint
    }
}
