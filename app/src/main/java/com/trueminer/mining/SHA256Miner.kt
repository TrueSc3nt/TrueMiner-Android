package com.trueminer.mining

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/** SHA-256d helpers for Bitcoin-style mining headers. */
class SHA256Miner {
    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256d(data: ByteArray): ByteArray = sha256(sha256(data))

    /**
     * Compute double-SHA256 of an 80-byte header with a specific nonce.
     * The nonce is written into bytes 76..79 in little-endian order.
     */
    fun doubleSha256(header: ByteArray, nonce: Int): ByteArray {
        val fullHeader = ByteArray(80)
        val copyLen = minOf(header.size, 80)
        System.arraycopy(header, 0, fullHeader, 0, copyLen)
        fullHeader[76] = (nonce and 0xFF).toByte()
        fullHeader[77] = ((nonce ushr 8) and 0xFF).toByte()
        fullHeader[78] = ((nonce ushr 16) and 0xFF).toByte()
        fullHeader[79] = ((nonce ushr 24) and 0xFF).toByte()
        return sha256d(fullHeader)
    }

    fun meetsTarget(hash: ByteArray, target: BigInteger): Boolean {
        return BigInteger(1, hash) <= target
    }

    fun nbitsToTarget(nbits: String): BigInteger {
        val nbitsInt = nbits.toLong(16)
        val exponent = (nbitsInt ushr 24).toInt()
        val mantissa = nbitsInt and 0xFFFFFFL
        return BigInteger.valueOf(mantissa).shiftLeft(8 * (exponent - 3))
    }

    fun targetFromDifficulty(diff: Double): BigInteger {
        val safeDiff = if (diff.isFinite() && diff > 0.0) diff else 1.0
        if (safeDiff <= 1.0) return DIFF1
        return BigDecimal(DIFF1)
            .divide(BigDecimal.valueOf(safeDiff), MathContext(80, RoundingMode.DOWN))
            .toBigInteger()
            .coerceAtLeast(BigInteger.ONE)
    }

    fun difficultyFromHash(hash: ByteArray): Double {
        val hashInt = BigInteger(1, hash)
        if (hashInt == BigInteger.ZERO) return Double.POSITIVE_INFINITY
        return DIFF1.toDouble() / hashInt.toDouble()
    }

    fun targetToBytes(target: BigInteger): ByteArray {
        val out = ByteArray(32)
        val raw = target.toByteArray()
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        val DIFF1 = BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16)
    }
}
