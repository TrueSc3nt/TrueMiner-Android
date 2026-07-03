package com.trueminer.pool

import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.math.BigInteger
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StratumConnection(
    val poolName: String,
    val host: String,
    val port: Int,
    val address: String
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    var connected = false
        private set
    var extranonce1 = ""
        private set
    var extranonce2Size = 4
        private set
    var poolDifficulty = 1.0
        private set

    private val gson = Gson()
    private var messageId = AtomicInteger(1)
    private val extranonce2Counter = AtomicLong(0)

    fun connect(): Boolean {
        disconnect()
        for (attempt in 1..5) {
            try {
                socket = Socket(host, port)
                socket?.soTimeout = 30000
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                writer = PrintWriter(socket?.getOutputStream(), true)

                val subResp = sendRequest("mining.subscribe", listOf("TrueMiner/1.3"))
                val subResult = subResp?.get("result") as? List<*>
                if (subResult != null && subResult.size >= 3) {
                    extranonce1 = subResult[1] as? String ?: ""
                    extranonce2Size = when (val v = subResult[2]) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: 4
                        else -> 4
                    }
                }

                sendRequest("mining.authorize", listOf(address, "x"))
                connected = true
                Log.i("Stratum", "Connected to $host:$port en1=$extranonce1 en2Size=$extranonce2Size")
                return true
            } catch (e: Exception) {
                Log.e("Stratum", "Connection attempt $attempt failed: ${e.message}")
                disconnect()
                try { Thread.sleep(2500L * attempt) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }

    fun nextExtranonce2(): String {
        val value = extranonce2Counter.getAndIncrement()
        val width = extranonce2Size * 2
        return value.toString(16).padStart(width, '0').takeLast(width)
    }

    fun submit(jobId: String, en2: String, ntime: String, nonceHex: String): Boolean {
        return try {
            val response = sendRequest("mining.submit", listOf(address, jobId, en2, ntime, nonceHex))
            response?.get("result") == true
        } catch (e: Exception) {
            Log.e("Stratum", "Submit failed: ${e.message}")
            false
        }
    }

    fun setDifficulty(diff: Double) {
        poolDifficulty = if (diff.isFinite() && diff > 0.0) diff else 1.0
    }

    fun readMessage(): String? = try { reader?.readLine() } catch (_: Exception) { null }

    private fun sendRequest(method: String, params: List<Any>): Map<String, Any>? {
        val id = messageId.getAndIncrement()
        val request = mapOf("id" to id, "method" to method, "params" to params)
        writer?.println(gson.toJson(request))

        while (true) {
            val response = reader?.readLine() ?: return null
            val parsed = try { gson.fromJson(response, Map::class.java) as Map<String, Any> } catch (_: Exception) { null }
            if (parsed != null && (parsed["id"] as? Number)?.toInt() == id) return parsed
            // Ignore async notifications while waiting for our request response. The listener will receive new ones after connect.
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        reader = null
        writer = null
        connected = false
    }
}

data class MiningJob(
    val poolName: String,
    val jobId: String,
    val prevhash: String,
    val coinb1: String,
    val coinb2: String,
    val merkleBranch: List<String>,
    val version: String,
    val nbits: String,
    val ntime: String,
    val cleanJobs: Boolean,
    val headerBytes: ByteArray = ByteArray(80),
    val target: BigInteger = BigInteger.ZERO,
    val nonceStart: Int = 0,
    val nonceEnd: Int = -1,
    val en2: String = "",
    val poolId: Int = 0,
    val nonceCursor: AtomicLong = AtomicLong(0L)
) {
    val jobKey: String get() = "$poolName:$jobId:$ntime:$en2"
}

data class ShareResult(
    val poolName: String,
    val jobId: String,
    val en2: String,
    val ntime: String,
    val nonce: Int,
    val hashHex: String,
    val difficulty: Double,
    val isGPU: Boolean = false,
    val backend: String = if (isGPU) "GPU" else "CPU"
)
