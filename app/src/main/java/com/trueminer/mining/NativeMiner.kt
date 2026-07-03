package com.trueminer.mining

import android.content.res.AssetManager

object NativeMiner {
    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("trueminer_native")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            loaded = false
        }
    }

    private external fun nativeDescribeDevices(assetManager: AssetManager?): String
    private external fun nativeScan(
        header: ByteArray,
        target: ByteArray,
        startNonce: Long,
        count: Int,
        backendId: Int,
        assetManager: AssetManager?
    ): NativeScanResult

    fun isLoaded(): Boolean = loaded

    fun describeDevices(assetManager: AssetManager?): String {
        if (!loaded) return "Native GPU module not loaded: ${loadError ?: "unknown error"}"
        return try {
            nativeDescribeDevices(assetManager)
        } catch (t: Throwable) {
            "Native GPU check failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun scan(
        header: ByteArray,
        target: ByteArray,
        startNonce: Long,
        count: Int,
        backend: GpuBackend,
        assetManager: AssetManager?
    ): NativeScanResult {
        if (!loaded) {
            return NativeScanResult(false, 0L, ByteArray(32), 0L, "none", loadError ?: "native library not loaded")
        }
        return try {
            nativeScan(header, target, startNonce, count, backend.ordinal, assetManager)
        } catch (t: Throwable) {
            NativeScanResult(false, 0L, ByteArray(32), 0L, "native", t.message ?: t.javaClass.simpleName)
        }
    }
}
