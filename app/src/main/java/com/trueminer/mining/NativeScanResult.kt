package com.trueminer.mining

data class NativeScanResult(
    val found: Boolean,
    val nonce: Long,
    val hash: ByteArray,
    val hashesDone: Long,
    val backendUsed: String,
    val error: String
)
