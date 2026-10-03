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

    val kind: WalletKind? get() = prefs.getString("kind", null)?.let { WalletKind.valueOf(it) }
    val address: String? get() = prefs.getString("address", null)
    val walletLabel: String? get() = prefs.getString("label", null)

    private fun practiceKeypair(): Keypair? =
        prefs.getString("seed", null)?.let { Keypair.fromSecretKey(Base64.decode(it, Base64.NO_WRAP)) }

    fun usePractice(): String {
        val existing = practiceKeypair()
        val kp = existing ?: Keypair.generate()
        prefs.edit()
            .putString("kind", WalletKind.Practice.name)
            .putString("seed", Base64.encodeToString(kp.secret.copyOf(32), Base64.NO_WRAP))
            .putString("address", kp.publicKey.toBase58())
            .putString("label", "Practice wallet")
            .apply()
        return kp.publicKey.toBase58()
    }

    suspend fun connectPhone(sender: ActivityResultSender): String {
        val result = adapter.connect(sender)
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
            .putString("label", account.accountLabel ?: auth.walletUriBase?.host ?: "Phone wallet")
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
                val unsigned = shortVec(1) + ByteArray(64) + message
                val result = adapter.transact(s) { signTransactions(arrayOf(unsigned)) }
                when (result) {
                    is TransactionResult.Success -> {
                        result.authResult.authToken.let { prefs.edit().putString("auth", it).apply() }
                        result.payload.signedPayloads.first()
                    }
                    is TransactionResult.NoWalletFound -> throw NoWalletException()
                    is TransactionResult.Failure -> throw UserDeclinedException()
                }
            }
            null -> throw IOException("Connect a wallet first.")
        }
        val sig = rpc.send(signed)
        rpc.confirm(sig)
        return sig
    }

    private fun shortVec(n: Int): ByteArray = byteArrayOf(n.toByte())
}
