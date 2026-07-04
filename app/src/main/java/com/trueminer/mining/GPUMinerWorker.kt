package com.trueminer.mining

import android.content.res.AssetManager
import android.util.Log
import com.trueminer.pool.MiningJob
import com.trueminer.pool.ShareResult
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GPUMinerWorker(
    private val workerId: Int,
    private val miner: SHA256Miner,
    private val backend: GpuBackend,
    private val assetManager: AssetManager?,
    private val jobQueue: LinkedBlockingQueue<MiningJob>,
    private val resultCallback: (ShareResult) -> Unit,
    private val shutdown: AtomicBoolean,
    private val hashCount: AtomicLong,
    private val statusCallback: ((String) -> Unit)? = null
) : Runnable {

    companion object {
        private const val TAG = "GPUMiner"
        private const val GPU_BATCH_SIZE = 262_144
    }

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        Log.i(TAG, "GPU worker $workerId started with backend=$backend")
        statusCallback?.invoke("GPU worker starting: ${backend.label}")
        var activeJob: MiningJob? = null
        var lastError = ""

        while (!shutdown.get()) {
            try {
                val freshJob = jobQueue.poll(100, TimeUnit.MILLISECONDS)
                if (freshJob != null && freshJob.jobKey != activeJob?.jobKey) {
                    activeJob = freshJob
                    statusCallback?.invoke("GPU received ${freshJob.poolName} job ${freshJob.jobId}")
                }
                val job = activeJob ?: continue

                val start = job.nonceCursor.getAndAdd(GPU_BATCH_SIZE.toLong())
                val endExclusive = minOf(start + GPU_BATCH_SIZE, job.nonceEnd.toLong() and 0xffffffffL)
                if (start >= endExclusive) continue

                val count = (endExclusive - start).toInt()
                val targetBytes = miner.targetToBytes(job.target)
                val result = NativeMiner.scan(job.headerBytes, targetBytes, start, count, backend, assetManager)

                if (result.error.isNotEmpty() && result.error != lastError) {
                    lastError = result.error
                    statusCallback?.invoke("GPU: ${result.error}")
                    if (result.error.contains("GPU unavailable") || result.error.contains("fallback")) {
                        statusCallback?.invoke("GPU_SLOW: GPU fell back to single-threaded CPU. Consider using CPU mode for better performance.")
                    }
                }

                if (result.hashesDone <= 0L) {
                    Thread.sleep(500)
                    continue
                }

                hashCount.addAndGet(result.hashesDone)
                statusCallback?.invoke("GPU batch: ${result.hashesDone} hashes via ${result.backendUsed}")

                if (result.found) {
                    val diff = try { SHA256Miner.DIFF1.toDouble() / BigInteger(1, result.hash).toDouble() } catch (_: Exception) { 0.0 }
                    resultCallback(
                        ShareResult(
                            poolName = job.poolName,
                            jobId = job.jobId,
                            en2 = job.en2,
                            ntime = job.ntime,
                            nonce = result.nonce.toInt(),
                            hashHex = miner.bytesToHex(result.hash),
                            difficulty = diff,
                            isGPU = true,
                            backend = result.backendUsed.ifEmpty { backend.label }
                        )
                    )
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "GPU worker $workerId error", e)
                statusCallback?.invoke("GPU error: ${e.message}")
            }
        }
        Log.i(TAG, "GPU worker $workerId stopped")
    }
}
