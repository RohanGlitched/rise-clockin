package app.rise.clockin.solana

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import org.sol4k.Base58
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.TransactionMessage
import org.sol4k.instruction.Instruction
import java.io.IOException

enum class WalletKind { Phone, Practice }

class NoWalletException : IOException("No Solana wallet app found on this phone. Install Phantom or Solflare, or use a practice wallet.")
class UserDeclinedException : IOException("The wallet request was declined.")

/** Signed proof that a wallet belongs to whoever is holding the phone (Sign in with Solana). */
data class SignInProof(val address: String, val message: ByteArray, val signature: ByteArray)

/**
 * Either the phone's own wallet through Mobile Wallet Adapter (Seed Vault on Seeker,
 * Phantom, Solflare...), or a practice keypair kept on this device for devnet.
 */
class Wallet(context: Context, private val rpc: Rpc) {
    private val prefs = context.getSharedPreferences("rise.wallet", Context.MODE_PRIVATE)

    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://rise-clockin.vercel.app"),
            iconUri = Uri.parse("icon.png"),
            identityName = "Rise",
        ),
    ).apply {
        blockchain = Solana.Devnet
        authToken = prefs.getString("auth", null)
    }

    init {
        // Seal a seed left in the clear by an older version as soon as the app starts.
        if (prefs.contains("seed")) runCatching { practiceKeypair() }
    }

    val kind: WalletKind? get() = prefs.getString("kind", null)?.let { WalletKind.valueOf(it) }
    val address: String? get() = prefs.getString("address", null)
    val walletLabel: String? get() = prefs.getString("label", null)

    /** The practice seed is sealed with a Keystore key; older installs kept it in the clear and are moved over. */
    private fun practiceKeypair(): Keypair? {
        prefs.getString("seed_sealed", null)?.let {
            return Keypair.fromSecretKey(KeystoreBox.open(Base64.decode(it, Base64.NO_WRAP)))
        }
        val legacy = prefs.getString("seed", null) ?: return null
        val seed = Base64.decode(legacy, Base64.NO_WRAP)
        prefs.edit().putString("seed_sealed", sealSeed(seed)).remove("seed").apply()
        return Keypair.fromSecretKey(seed)
    }

    private fun sealSeed(seed: ByteArray) = Base64.encodeToString(KeystoreBox.seal(seed), Base64.NO_WRAP)

    fun usePractice(): String {
        val existing = practiceKeypair()
        val kp = existing ?: Keypair.generate()
        prefs.edit()
            .putString("kind", WalletKind.Practice.name)
            .putString("seed_sealed", sealSeed(kp.secret.copyOf(32)))
            .remove("seed")
            .putString("address", kp.publicKey.toBase58())
            .putString("label", "Practice wallet")
            .apply()
        return kp.publicKey.toBase58()
    }

    suspend fun connectPhone(sender: ActivityResultSender): String {
        val result = transactFresh(sender) { }
        val auth = when (result) {
            is TransactionResult.Success -> result.authResult
            is TransactionResult.NoWalletFound -> throw NoWalletException()
            is TransactionResult.Failure -> throw UserDeclinedException()
        }
        val account = auth.accounts.first()
        val address = Base58.encode(account.publicKey)
        prefs.edit()
            .putString("kind", WalletKind.Phone.name)
            .putString("address", address)
            .putString("auth", auth.authToken)
            .putString("label", walletName(auth, account.accountLabel))
            .apply()
        return address
    }

    /** Sign in with Solana through the phone wallet; used to prove Seeker ownership. */
    suspend fun signIn(sender: ActivityResultSender): SignInProof {
        val payload = SignInWithSolana.Payload("rise-clockin.vercel.app", "Prove this Seeker is yours to join Rise's Seeker pacts.")
        val result = adapter.signIn(sender, payload)
        val res = when (result) {
            is TransactionResult.Success -> result.payload
            is TransactionResult.NoWalletFound -> throw NoWalletException()
            is TransactionResult.Failure -> throw UserDeclinedException()
        }
        result.authResult.authToken.let { prefs.edit().putString("auth", it).apply() }
        return SignInProof(Base58.encode(res.publicKey), res.signedMessage, res.signature)
    }

    fun signOut() {
        prefs.edit().clear().apply()
        adapter.authToken = null
    }

    /**
     * Builds a transaction from the instructions, has the wallet sign it, sends it to devnet
     * and waits for confirmation. Returns the signature.
     */
    suspend fun send(instructions: List<Instruction>, sender: ActivityResultSender?): String {
        val owner = PublicKey(address ?: throw IOException("Connect a wallet first."))
        val blockhash = rpc.latestBlockhash()
        val message = TransactionMessage.newMessage(owner, blockhash, instructions).serialize()
        val signed: ByteArray = when (kind) {
            WalletKind.Practice -> {
                val kp = practiceKeypair() ?: throw IOException("Practice wallet missing. Set it up again from You.")
                shortVec(1) + kp.sign(message) + message
            }
            WalletKind.Phone -> {
                val s = sender ?: throw IOException("Open Rise to sign with your wallet.")
                // Check it would succeed before bothering the wallet, then build it fresh inside the session.
                rpc.simulate(shortVec(1) + ByteArray(64) + message)
                return signAndSendWithWallet(s) {
                    shortVec(1) + ByteArray(64) + TransactionMessage.newMessage(owner, rpc.latestBlockhash(), instructions).serialize()
                }
            }
            null -> throw IOException("Connect a wallet first.")
        }
        val sig = rpc.send(signed)
        rpc.confirm(sig)
        return sig
    }

    /**
     * MWA 2.0: the wallet signs and submits (Phantom, Solflare, Seed Vault). Wallets that only
     * sign get the older sign-then-we-send path.
     */
    private suspend fun signAndSendWithWallet(s: ActivityResultSender, build: suspend () -> ByteArray): String {
        val sent = transactFresh(s) { signAndSendTransactions(arrayOf(build())) }
        when (sent) {
            is TransactionResult.Success -> {
                prefs.edit().putString("auth", sent.authResult.authToken).apply()
                val sig = Base58.encode(sent.payload.signatures.first())
                rpc.confirm(sig)
                return sig
            }
            is TransactionResult.NoWalletFound -> throw NoWalletException()
            is TransactionResult.Failure -> {
                android.util.Log.w("RiseWallet", "signAndSend failed: ${sent.message}", sent.e)
                if (sent.e is InterruptedException || sent.message.contains("declin", true) || sent.message.contains("reject", true)) throw UserDeclinedException()
            }
        }
        val signed = transactFresh(s) { signTransactions(arrayOf(build())) }
        return when (signed) {
            is TransactionResult.Success -> {
                prefs.edit().putString("auth", signed.authResult.authToken).apply()
                val sig = rpc.send(signed.payload.signedPayloads.first())
                rpc.confirm(sig)
                sig
            }
            is TransactionResult.NoWalletFound -> throw NoWalletException()
            is TransactionResult.Failure -> {
                android.util.Log.w("RiseWallet", "signTransactions failed: ${signed.message}", signed.e)
                throw UserDeclinedException()
            }
        }
    }

    private fun walletName(auth: com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult, fallback: String?): String {
        val host = auth.walletUriBase?.host.orEmpty() + " " + (fallback ?: "")
        return when {
            host.contains("phantom", true) -> "Phantom"
            host.contains("solflare", true) -> "Solflare"
            host.contains("backpack", true) -> "Backpack"
            host.contains("seedvault", true) || host.contains("solanamobile", true) -> "Seed Vault"
            else -> fallback ?: "Phone wallet"
        }
    }

    /**
     * Runs a wallet session; if the wallet rejects our saved auth token (e.g. it was issued
     * before the app's identity was verified), forget it and authorize afresh once.
     */
    private suspend fun <T> transactFresh(
        s: ActivityResultSender,
        block: suspend com.solana.mobilewalletadapter.clientlib.AdapterOperations.(com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult) -> T,
    ): TransactionResult<T> {
        val first = adapter.transact(s, null, block)
        val authFailed = first is TransactionResult.Failure &&
            generateSequence<Throwable>(first.e) { it.cause }.any { it.message?.contains("authoriz", true) == true }
        if (!authFailed || adapter.authToken == null) return first
        adapter.authToken = null
        prefs.edit().remove("auth").apply()
        return adapter.transact(s, null, block)
    }

    private fun shortVec(n: Int): ByteArray = byteArrayOf(n.toByte())
}
