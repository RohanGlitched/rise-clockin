package app.rise.clockin.solana

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RpcException(message: String, val logs: List<String> = emptyList()) : IOException(message)

/** A small JSON-RPC client for the handful of Solana calls Rise needs. */
class Rpc(private val url: String) {
    private var nextId = 1

    private suspend fun call(method: String, params: JSONArray): Any? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                return@withContext callOnce(method, params)
            } catch (e: RpcException) {
                throw e
            } catch (e: IOException) {
                // Public devnet RPC rate-limits bursts; back off and retry a few times.
                if (++attempt >= 4) throw IOException("Can't reach Solana devnet. Check your connection and try again.", e)
                delay(400L * attempt * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun callOnce(method: String, params: JSONArray): Any? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", method).put("params", params)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 429 || code >= 500) throw IOException("RPC HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val logs = err.optJSONObject("data")?.optJSONArray("logs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                android.util.Log.w("RiseRpc", "$method failed: $err")
                throw RpcException(err.optString("message", "RPC error"), logs)
            }
            return json.opt("result")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun latestBlockhash(): String =
        // Finalized: every node behind a load-balanced RPC already knows it.
        ((call("getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "finalized"))) as JSONObject)
            .getJSONObject("value")).getString("blockhash")

    suspend fun balance(address: String): Long =
        (call("getBalance", JSONArray().put(address).put(JSONObject().put("commitment", "confirmed"))) as JSONObject).getLong("value")

    suspend fun accountData(address: String): ByteArray? {
        val res = call("getAccountInfo", JSONArray().put(address).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))) as JSONObject
        val v = res.opt("value")
        if (v == null || v == JSONObject.NULL) return null
        return Base64.decode((v as JSONObject).getJSONArray("data").getString(0), Base64.DEFAULT)
    }

    suspend fun multipleAccounts(addresses: List<String>): List<ByteArray?> {
        if (addresses.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray?>()
        addresses.chunked(100).forEach { chunk ->
            val res = call(
                "getMultipleAccounts",
                JSONArray().put(JSONArray(chunk)).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")),
            ) as JSONObject
            val arr = res.getJSONArray("value")
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                out += if (v == null || v == JSONObject.NULL) null
                else Base64.decode((v as JSONObject).getJSONArray("data").getString(0), Base64.DEFAULT)
            }
        }
        return out
    }

    /** getProgramAccounts with memcmp filters (offset to base58 bytes) and an optional size. */
    suspend fun programAccounts(program: String, dataSize: Int?, memcmp: List<Pair<Int, String>>): List<Pair<String, ByteArray>> {
        val filters = JSONArray()
        dataSize?.let { filters.put(JSONObject().put("dataSize", it)) }
        memcmp.forEach { (offset, bytes) -> filters.put(JSONObject().put("memcmp", JSONObject().put("offset", offset).put("bytes", bytes))) }
        val cfg = JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("filters", filters)
        val arr = call("getProgramAccounts", JSONArray().put(program).put(cfg)) as JSONArray
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            o.getString("pubkey") to Base64.decode(o.getJSONObject("account").getJSONArray("data").getString(0), Base64.DEFAULT)
        }
    }

    suspend fun requestAirdrop(address: String, lamports: Long): String =
        call("requestAirdrop", JSONArray().put(address).put(lamports)) as String

    suspend fun tokenBalance(ata: String): Long {
        return try {
            val res = call("getTokenAccountBalance", JSONArray().put(ata).put(JSONObject().put("commitment", "confirmed"))) as JSONObject
            res.getJSONObject("value").getString("amount").toLong()
        } catch (e: RpcException) {
            0L // no token account yet
        }
    }

    /** Simulates without checking signatures, so failures surface with program logs before any wallet prompt. */
    suspend fun simulate(tx: ByteArray) {
        val b64 = Base64.encodeToString(tx, Base64.NO_WRAP)
        val sim = call(
            "simulateTransaction",
            JSONArray().put(b64).put(
                JSONObject().put("encoding", "base64").put("commitment", "confirmed")
                    .put("sigVerify", false).put("replaceRecentBlockhash", true),
            ),
        ) as JSONObject
        val value = sim.getJSONObject("value")
        val err = value.opt("err")
        if (err != null && err != JSONObject.NULL) {
            val logsArr = value.optJSONArray("logs")
            val logs = if (logsArr != null) List(logsArr.length()) { logsArr.getString(it) } else emptyList()
            throw RpcException("Transaction failed: $err", logs)
        }
    }

    /** Simulates first so failures come back with program logs, then sends. */
    suspend fun send(tx: ByteArray): String {
        val b64 = Base64.encodeToString(tx, Base64.NO_WRAP)
        val sim = call(
            "simulateTransaction",
            JSONArray().put(b64).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("sigVerify", false).put("replaceRecentBlockhash", true)),
        ) as JSONObject
        val value = sim.getJSONObject("value")
        val err = value.opt("err")
        if (err != null && err != JSONObject.NULL) {
            val logsArr = value.optJSONArray("logs")
            val logs = if (logsArr != null) List(logsArr.length()) { logsArr.getString(it) } else emptyList()
            throw RpcException("Transaction failed: $err", logs)
        }
        return call(
            "sendTransaction",
            JSONArray().put(b64).put(JSONObject().put("encoding", "base64").put("skipPreflight", true).put("maxRetries", 5)),
        ) as String
    }

    /** Waits until the signature is confirmed, or throws after ~45 s. */
    suspend fun confirm(signature: String) {
        repeat(60) {
            val res = call("getSignatureStatuses", JSONArray().put(JSONArray().put(signature))) as JSONObject
            val st = res.getJSONArray("value").opt(0)
            if (st != null && st != JSONObject.NULL) {
                st as JSONObject
                val err = st.opt("err")
                if (err != null && err != JSONObject.NULL) throw RpcException("Transaction failed: $err")
                val c = st.optString("confirmationStatus")
                if (c == "confirmed" || c == "finalized") return
            }
            delay(750)
        }
        throw IOException("Solana devnet is slow to confirm. Your transaction may still land; pull to refresh in a minute.")
    }
}
