package com.trueminer.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.AssetManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.trueminer.R
import com.trueminer.mining.CPUMinerWorker
import com.trueminer.mining.GPUMinerWorker
import com.trueminer.mining.GpuBackend
import com.trueminer.mining.MiningMode
import com.trueminer.mining.NativeMiner
import com.trueminer.mining.SHA256Miner
import com.trueminer.pool.MiningJob
import com.trueminer.pool.ShareResult
import com.trueminer.pool.StratumConnection
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class MinerService : Service() {
    private val binder = MinerBinder()
    private val miner = SHA256Miner()
    private val shutdown = AtomicBoolean(false)
    private val hashCount = AtomicLong(0)
    private val http = OkHttpClient()

    private val btcPools = mutableListOf<StratumConnection>()
    private val bchPools = mutableListOf<StratumConnection>()
    private val btcJobQueue = LinkedBlockingQueue<MiningJob>()
    private val bchJobQueue = LinkedBlockingQueue<MiningJob>()
    private val workers = mutableListOf<Thread>()
    private val listeners = mutableListOf<Thread>()
    private var statsThread: Thread? = null

    private var numCores = 4
    private var miningMode = MiningMode.CPU
    private var gpuBackend = GpuBackend.AUTO
    private var gpuDifficulty = 0.0
    private var telegramToken = ""
    private var telegramChatId = ""
    private var currentDiff = 1.0
    private var lastShareTime = System.currentTimeMillis()
    private var shareCount = 0

    var onStatusUpdate: ((String) -> Unit)? = null
    var onHashrateUpdate: ((String) -> Unit)? = null
    var onShareFoundCallback: ((ShareResult) -> Unit)? = null
    var onTelegramMessage: ((String) -> Unit)? = null
    var onBtcPoolStatus: ((String, Boolean) -> Unit)? = null
    var onBchPoolStatus: ((String, Boolean) -> Unit)? = null
    var onWorkerCount: ((Int) -> Unit)? = null
    var onGpuFallback: ((String) -> Unit)? = null

    inner class MinerBinder : Binder() {
        fun getService(): MinerService = this@MinerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("TrueMiner ready"))
        return START_STICKY
    }

    fun startMining(
        btcAddr: String,
        bchAddr: String,
        btcPoolHost: String,
        btcPoolPort: Int,
        bchPoolHost: String = "",
        bchPoolPort: Int = 0,
        cores: Int = 4,
        mode: MiningMode = MiningMode.CPU,
        backend: GpuBackend = GpuBackend.AUTO,
        manualDifficulty: Double = 0.0,
        tgToken: String = "",
        tgChat: String = "",
        assetManager: AssetManager? = null
    ) {
        stopMiningInternal(stopSelfAfter = false)
        numCores = cores.coerceAtLeast(1)
        miningMode = mode
        gpuBackend = backend
        gpuDifficulty = manualDifficulty
        telegramToken = tgToken.trim()
        telegramChatId = tgChat.trim()
        currentDiff = if (manualDifficulty > 0) manualDifficulty else 1.0
        lastShareTime = System.currentTimeMillis()
        shareCount = 0
        hashCount.set(0)
        shutdown.set(false)

        try {
            startForeground(NOTIFICATION_ID, createNotification("Starting miner..."))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground notification: ${e.message}")
        }

        Thread {
            try {
                val gpuInfo = if (mode != MiningMode.CPU) NativeMiner.describeDevices(assetManager) else "CPU mode"
                onStatusUpdate?.invoke("Starting ${mode.label}. $gpuInfo")

                val btcPool = StratumConnection("BTC", btcPoolHost, btcPoolPort, btcAddr)
                if (btcPool.connect()) {
                    btcPools.add(btcPool)
                    updateNotification("Connected to BTC pool")
                    onStatusUpdate?.invoke("Connected to BTC $btcPoolHost:$btcPoolPort")
                    onBtcPoolStatus?.invoke("$btcPoolHost:$btcPoolPort", true)
                    val btcCpu = when (mode) {
                        MiningMode.CPU -> cpuWorkersForPool(hasDualPool = bchAddr.isNotEmpty())
                        MiningMode.GPU -> 0
                        MiningMode.BOTH -> cpuWorkersForPool(hasDualPool = bchAddr.isNotEmpty())
                    }
                    val btcGpu = if (mode == MiningMode.GPU || mode == MiningMode.BOTH) 1 else 0
                    startPoolWorkers("BTC", btcJobQueue, btcCpu, btcGpu, assetManager)
                    startListener(btcPool, btcJobQueue) { max(1, btcCpu + btcGpu) }
                } else {
                    onStatusUpdate?.invoke("Could not connect to BTC pool")
                    onBtcPoolStatus?.invoke("Connection failed", false)
                }

                if (bchAddr.isNotEmpty() && bchPoolHost.isNotEmpty()) {
                    val bchPool = StratumConnection("BCH", bchPoolHost, bchPoolPort, bchAddr)
                    if (bchPool.connect()) {
                        bchPools.add(bchPool)
                        updateNotification("Connected to BTC + BCH pools")
                        onStatusUpdate?.invoke("Connected to BCH $bchPoolHost:$bchPoolPort")
                        onBchPoolStatus?.invoke("$bchPoolHost:$bchPoolPort", true)
                        val bchCpu = when (mode) {
                            MiningMode.CPU -> max(1, numCores / 2)
                            MiningMode.GPU -> 0
                            MiningMode.BOTH -> max(1, numCores / 2)
                        }
                        val bchGpu = if (mode == MiningMode.GPU || mode == MiningMode.BOTH) 1 else 0
                        startPoolWorkers("BCH", bchJobQueue, bchCpu, bchGpu, assetManager)
                        startListener(bchPool, bchJobQueue) { max(1, bchCpu + bchGpu) }
                    } else {
                        onBchPoolStatus?.invoke("Failed: $bchPoolHost:$bchPoolPort", false)
                        onStatusUpdate?.invoke("BCH pool connection failed: $bchPoolHost:$bchPoolPort")
                    }
                } else if (bchAddr.isEmpty()) {
                    onBchPoolStatus?.invoke("Disabled", false)
                }

                val engine = when (mode) {
                    MiningMode.CPU -> "CPU"
                    MiningMode.GPU -> "GPU ${backend.label}"
                    MiningMode.BOTH -> "CPU + GPU ${backend.label}"
                }
                val message = "Mining with ${workers.size} workers ($engine)"
                updateNotification(message)
                onStatusUpdate?.invoke(message)
                onWorkerCount?.invoke(workers.size)
                startStatsReporter()
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                onStatusUpdate?.invoke("Error: ${e.message}")
                updateNotification("Miner error")
            }
        }.start()
    }

    private fun cpuWorkersForPool(hasDualPool: Boolean): Int {
        if (miningMode == MiningMode.GPU) return 0
        return if (hasDualPool) max(1, numCores / 2) else numCores
    }

    private fun startPoolWorkers(
        poolPrefix: String,
        queue: LinkedBlockingQueue<MiningJob>,
        cpuCount: Int,
        gpuCount: Int,
        assetManager: AssetManager?
    ) {
        repeat(cpuCount) { i ->
            val worker = CPUMinerWorker(
                workerId = workers.size,
                miner = miner,
                jobQueue = queue,
                resultCallback = ::onShareFound,
                shutdown = shutdown,
                hashCount = hashCount,
                statusCallback = { onStatusUpdate?.invoke(it) }
            )
            Thread(worker, "$poolPrefix-CPU-$i").also { it.start(); workers.add(it) }
        }
        repeat(gpuCount) { i ->
            val worker = GPUMinerWorker(
                workerId = workers.size,
                miner = miner,
                backend = gpuBackend,
                assetManager = assetManager,
                jobQueue = queue,
                resultCallback = ::onShareFound,
                shutdown = shutdown,
                hashCount = hashCount,
                statusCallback = { msg ->
                    onStatusUpdate?.invoke(msg)
                    if (msg.startsWith("GPU_SLOW:")) {
                        onGpuFallback?.invoke(msg.removePrefix("GPU_SLOW: ").trim())
                    }
                }
            )
            Thread(worker, "$poolPrefix-GPU-$i").also { it.start(); workers.add(it) }
        }
    }

    private fun startListener(pool: StratumConnection, jobQueue: LinkedBlockingQueue<MiningJob>, consumerCount: () -> Int) {
        val t = Thread({ listenForJobs(pool, jobQueue, consumerCount) }, "${pool.poolName}-listener")
        t.start()
        listeners.add(t)
    }

    fun adjustDifficulty() {
        if (gpuDifficulty > 0) { currentDiff = gpuDifficulty; return }
        val now = System.currentTimeMillis()
        val elapsed = now - lastShareTime
        if (elapsed > 3_600_000 && currentDiff > 1.0) {
            currentDiff = maxOf(1.0, currentDiff / 2.0)
            onStatusUpdate?.invoke("Diff -> ${String.format("%.1f", currentDiff)}")
        } else if (elapsed < 60_000 && shareCount > 0) {
            currentDiff = minOf(currentDiff * 2.0, 10000.0)
            shareCount = 0
            onStatusUpdate?.invoke("Diff -> ${String.format("%.1f", currentDiff)}")
        }
    }

    private fun listenForJobs(pool: StratumConnection, jobQueue: LinkedBlockingQueue<MiningJob>, consumerCount: () -> Int) {
        while (!shutdown.get()) {
            try {
                val line = pool.readMessage() ?: continue
                val msg = Gson().fromJson(line, Map::class.java) as? Map<*, *> ?: continue
                when (msg["method"]) {
                    "mining.notify" -> {
                        val params = msg["params"] as? List<*> ?: continue
                        val job = parseJob(pool, params)
                        if (job != null) {
                            jobQueue.clear()
                            repeat(consumerCount().coerceAtLeast(1)) { jobQueue.offer(job) }
                            onStatusUpdate?.invoke("${pool.poolName} new job ${job.jobId} diff=${String.format("%.2f", pool.poolDifficulty)}")
                        }
                    }
                    "mining.set_difficulty" -> {
                        val params = msg["params"] as? List<*> ?: continue
                        val diff = (params.firstOrNull() as? Number)?.toDouble() ?: 1.0
                        pool.setDifficulty(diff)
                        currentDiff = diff
                    }
                }
            } catch (e: Exception) {
                if (!shutdown.get()) {
                    try { pool.disconnect() } catch (_: Exception) {}
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                    pool.connect()
                }
            }
        }
    }

    private fun parseJob(pool: StratumConnection, params: List<*>): MiningJob? {
        return try {
            val jobId = params[0] as String
            val prevhash = params[1] as String
            val coinb1 = params[2] as String
            val coinb2 = params[3] as String
            val merkleBranch = (params[4] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val version = params[5] as String
            val nbits = params[6] as String
            val ntime = params[7] as String
            val cleanJobs = params.getOrNull(8) as? Boolean ?: true
            val en2 = pool.nextExtranonce2()
            val target = if (gpuDifficulty > 0) miner.targetFromDifficulty(gpuDifficulty) else miner.targetFromDifficulty(pool.poolDifficulty)
            val header = buildHeader(pool.extranonce1, en2, version, prevhash, coinb1, coinb2, merkleBranch, ntime, nbits)
            MiningJob(
                poolName = pool.poolName,
                jobId = jobId,
                prevhash = prevhash,
                coinb1 = coinb1,
                coinb2 = coinb2,
                merkleBranch = merkleBranch,
                version = version,
                nbits = nbits,
                ntime = ntime,
                cleanJobs = cleanJobs,
                headerBytes = header,
                target = target,
                en2 = en2,
                poolId = pool.poolName.hashCode(),
                nonceCursor = AtomicLong(0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Job parse failed", e)
            null
        }
    }

    private fun buildHeader(
        extranonce1: String,
        en2: String,
        version: String,
        prevhash: String,
        coinb1: String,
        coinb2: String,
        merkleBranch: List<String>,
        ntime: String,
        nbits: String
    ): ByteArray {
        val coinbase = hexToBytes(coinb1 + extranonce1 + en2 + coinb2)
        var merkleRoot = miner.sha256d(coinbase)
        for (branch in merkleBranch) {
            merkleRoot = miner.sha256d(merkleRoot + hexToBytes(branch))
        }

        val header = ByteArray(80)
        reverseBytes(hexToBytes(version)).copyInto(header, 0, 0, 4)
        hexToBytes(prevhash).copyInto(header, 4, 0, 32)
        reverseBytes(merkleRoot).copyInto(header, 36, 0, 32)
        reverseBytes(hexToBytes(ntime)).copyInto(header, 68, 0, 4)
        reverseBytes(hexToBytes(nbits)).copyInto(header, 72, 0, 4)
        // nonce is filled by CPU/GPU workers at bytes 76..79
        return header
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun reverseBytes(bytes: ByteArray): ByteArray = bytes.reversedArray()

    private fun onShareFound(result: ShareResult) {
        val pool = if (result.poolName == "BTC") btcPools.firstOrNull() else bchPools.firstOrNull()
        val accepted = pool?.submit(result.jobId, result.en2, result.ntime, String.format("%08x", Integer.reverseBytes(result.nonce))) ?: false
        lastShareTime = System.currentTimeMillis()
        shareCount++
        val engine = if (result.isGPU) result.backend else "CPU"
        val text = "${result.poolName} share ${if (accepted) "accepted" else "submitted"} via $engine diff=${String.format("%.4f", result.difficulty)}"
        updateNotification(text)
        onShareFoundCallback?.invoke(result)
        onStatusUpdate?.invoke(text)
        sendTelegram("${result.poolName} share found\nEngine: $engine\nAccepted: $accepted\nDiff: ${String.format("%.6f", result.difficulty)}\nNonce: ${String.format("%08x", result.nonce)}")
    }

    private fun startStatsReporter() {
        statsThread?.interrupt()
        statsThread = Thread {
            var last = System.currentTimeMillis()
            while (!shutdown.get()) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                adjustDifficulty()
                val now = System.currentTimeMillis()
                val elapsed = ((now - last).coerceAtLeast(1)).toDouble() / 1000.0
                last = now
                val h = hashCount.getAndSet(0)
                val rate = formatHashrate(h / elapsed)
                val status = "Hashrate: $rate"
                onHashrateUpdate?.invoke(status)
                updateNotification("$rate • ${workers.size} workers • Diff ${String.format("%.2f", currentDiff)}")
            }
        }.also { it.start() }
    }

    private fun formatHashrate(h: Double): String = when {
        h >= 1e12 -> String.format("%.2f TH/s", h / 1e12)
        h >= 1e9 -> String.format("%.2f GH/s", h / 1e9)
        h >= 1e6 -> String.format("%.2f MH/s", h / 1e6)
        h >= 1e3 -> String.format("%.2f KH/s", h / 1e3)
        else -> String.format("%.2f H/s", h)
    }

    private fun sendTelegram(message: String) {
        if (telegramToken.isBlank() || telegramChatId.isBlank()) return
        onTelegramMessage?.invoke(message)
        Thread {
            try {
                val body = FormBody.Builder()
                    .add("chat_id", telegramChatId)
                    .add("text", message)
                    .build()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$telegramToken/sendMessage")
                    .post(body)
                    .build()
                http.newCall(req).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Telegram send failed: ${e.message}")
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "TrueMiner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrueMiner")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mining)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    }

    fun stopMining() = stopMiningInternal(stopSelfAfter = true)

    private fun stopMiningInternal(stopSelfAfter: Boolean) {
        shutdown.set(true)
        workers.forEach { it.interrupt() }
        listeners.forEach { it.interrupt() }
        statsThread?.interrupt()
        workers.clear()
        listeners.clear()
        btcJobQueue.clear()
        bchJobQueue.clear()
        btcPools.forEach { it.disconnect() }
        bchPools.forEach { it.disconnect() }
        btcPools.clear()
        bchPools.clear()
        NativeMiner.cleanup()
        onHashrateUpdate?.invoke("Hashrate: 0 H/s")
        onBtcPoolStatus?.invoke("Disconnected", false)
        onBchPoolStatus?.invoke("Disconnected", false)
        onWorkerCount?.invoke(0)
        try { stopForeground(true) } catch (_: Exception) {}
        if (stopSelfAfter) stopSelf()
    }

    override fun onDestroy() {
        stopMiningInternal(stopSelfAfter = false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MinerService"
        private const val CHANNEL_ID = "trueminer_channel"
        private const val NOTIFICATION_ID = 1
    }
}
