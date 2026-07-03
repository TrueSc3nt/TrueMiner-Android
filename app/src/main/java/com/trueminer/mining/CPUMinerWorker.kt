package com.trueminer.mining

import android.util.Log
import com.trueminer.pool.MiningJob
import com.trueminer.pool.ShareResult
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CPUMinerWorker(
    private val workerId: Int,
    private val miner: SHA256Miner,
    private val jobQueue: LinkedBlockingQueue<MiningJob>,
    private val resultCallback: (ShareResult) -> Unit,
    private val shutdown: AtomicBoolean,
    private val hashCount: AtomicLong,
    private val statusCallback: ((String) -> Unit)? = null
) : Runnable {

    companion object {
        private const val TAG = "CPUMiner"
        private const val BATCH_SIZE = 32768
    }

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        Log.i(TAG, "CPU worker $workerId started")
        var activeJob: MiningJob? = null

        while (!shutdown.get()) {
            try {
                val freshJob = jobQueue.poll(100, TimeUnit.MILLISECONDS)
                if (freshJob != null && freshJob.jobKey != activeJob?.jobKey) {
                    activeJob = freshJob
                    statusCallback?.invoke("CPU worker $workerId received ${freshJob.poolName} job ${freshJob.jobId}")
                }

                val job = activeJob ?: continue
                val start = job.nonceCursor.getAndAdd(BATCH_SIZE.toLong())
                val endExclusive = minOf(start + BATCH_SIZE, job.nonceEnd.toLong() and 0xffffffffL)
                if (start >= endExclusive) continue

                var processed = 0L
                var n = start
                while (n < endExclusive && !shutdown.get()) {
                    val nonce = n.toInt()
                    val hash = miner.doubleSha256(job.headerBytes, nonce)
                    processed++
                    if (miner.meetsTarget(hash, job.target)) {
                        val hashHex = miner.bytesToHex(hash)
                        val diff = try { SHA256Miner.DIFF1.toDouble() / BigInteger(1, hash).toDouble() } catch (_: Exception) { 0.0 }
                        resultCallback(
                            ShareResult(
                                poolName = job.poolName,
                                jobId = job.jobId,
                                en2 = job.en2,
                                ntime = job.ntime,
                                nonce = nonce,
                                hashHex = hashHex,
                                difficulty = diff,
                                isGPU = false,
                                backend = "CPU"
                            )
                        )
                    }
                    n++
                }
                hashCount.addAndGet(processed)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "CPU worker $workerId error", e)
            }
        }
        Log.i(TAG, "CPU worker $workerId stopped")
    }
}
