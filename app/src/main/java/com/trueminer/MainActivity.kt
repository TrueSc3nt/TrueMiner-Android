package com.trueminer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.trueminer.mining.GpuBackend
import com.trueminer.mining.MiningMode
import com.trueminer.mining.NativeMiner
import com.trueminer.network.MinerService

class MainActivity : AppCompatActivity() {
    private var minerService: MinerService? = null
    private var bound = false

    private lateinit var etBtcAddress: EditText
    private lateinit var etBchAddress: EditText
    private lateinit var etBtcPool: EditText
    private lateinit var etBchPool: EditText
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var etCores: EditText
    private lateinit var etDifficulty: EditText
    private lateinit var spinnerMiningMode: Spinner
    private lateinit var spinnerGpuBackend: Spinner
    private lateinit var switchBCH: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHashrate: TextView
    private lateinit var tvShares: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvBtcPoolStatus: TextView
    private lateinit var tvBchPoolStatus: TextView
    private lateinit var tvWorkers: TextView

    private val prefs by lazy { getSharedPreferences("trueminer_settings", Context.MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            minerService = (service as MinerService.MinerBinder).getService()
            bound = true
            minerService?.onStatusUpdate = { runOnUiThread { tvStatus.text = it } }
            minerService?.onHashrateUpdate = { runOnUiThread { tvHashrate.text = it } }
            minerService?.onShareFoundCallback = { share ->
                runOnUiThread {
                    val poolLabel = if (share.poolName == "BTC") "BTC" else "BCH"
                    val engineLabel = if (share.isGPU) "GPU" else "CPU"
                    val diffStr = minerService?.formatDifficultyForUI(share.difficulty) ?: String.format("diff=%.4f", share.difficulty)
                    tvShares.append("[$poolLabel] $engineLabel ${String.format("%08x", share.nonce)} $diffStr\n")
                }
            }
            minerService?.onLogMessage = { msg ->
                runOnUiThread { tvShares.append("$msg\n") }
            }
            minerService?.onTelegramMessage = { msg -> runOnUiThread { tvStatus.text = "Telegram: $msg" } }
            minerService?.onBtcPoolStatus = { addr, connected ->
                runOnUiThread {
                    tvBtcPoolStatus.text = if (connected) "BTC: $addr" else "BTC: $addr"
                    tvBtcPoolStatus.setTextColor(if (connected) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
                }
            }
            minerService?.onBchPoolStatus = { addr, connected ->
                runOnUiThread {
                    tvBchPoolStatus.text = if (connected) "BCH: $addr" else "BCH: $addr"
                    tvBchPoolStatus.setTextColor(if (connected) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
                }
            }
            minerService?.onWorkerCount = { count ->
                runOnUiThread {
                    val cpuCount = count // Will be overridden by log message
                    tvWorkers.text = "$count workers"
                }
            }
            minerService?.onGpuFallback = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    Toast.makeText(this@MainActivity, "GPU fell back to CPU - GPU mode may be slower", Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            minerService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSpinners()
        setupDefaultsAndSavedSettings()
        setupActions()
        requestNotificationPermissionIfNeeded()
        startAndBindService()
        checkGpuSupport()
    }

    private fun bindViews() {
        etBtcAddress = findViewById(R.id.etBtcAddress)
        etBchAddress = findViewById(R.id.etBchAddress)
        etBtcPool = findViewById(R.id.etBtcPool)
        etBchPool = findViewById(R.id.etBchPool)
        etTgToken = findViewById(R.id.etTgToken)
        etTgChat = findViewById(R.id.etTgChat)
        etCores = findViewById(R.id.etCores)
        etDifficulty = findViewById(R.id.etDifficulty)
        spinnerMiningMode = findViewById(R.id.spinnerMiningMode)
        spinnerGpuBackend = findViewById(R.id.spinnerGpuBackend)
        switchBCH = findViewById(R.id.switchBCH)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)
        tvHashrate = findViewById(R.id.tvHashrate)
        tvShares = findViewById(R.id.tvShares)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvBtcPoolStatus = findViewById(R.id.tvBtcPoolStatus)
        tvBchPoolStatus = findViewById(R.id.tvBchPoolStatus)
        tvWorkers = findViewById(R.id.tvWorkers)
        tvShares.movementMethod = ScrollingMovementMethod()
    }

    private fun setupSpinners() {
        spinnerMiningMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            MiningMode.values().map { it.label }
        )
        spinnerGpuBackend.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            GpuBackend.values().map { it.label }
        )
    }

    private fun setupDefaultsAndSavedSettings() {
        etBtcPool.setText(prefs.getString(KEY_BTC_POOL, "solo.ckpool.org:3333"))
        etBchPool.setText(prefs.getString(KEY_BCH_POOL, "solo.bchpool.org:3333"))
        etBtcAddress.setText(prefs.getString(KEY_BTC_ADDRESS, ""))
        etBchAddress.setText(prefs.getString(KEY_BCH_ADDRESS, ""))
        etTgToken.setText(prefs.getString(KEY_TG_TOKEN, ""))
        etTgChat.setText(prefs.getString(KEY_TG_CHAT, ""))
        val defaultCores = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        etCores.setText(prefs.getInt(KEY_CORES, defaultCores).toString())
        etDifficulty.setText(prefs.getString(KEY_DIFFICULTY, ""))
        switchBCH.isChecked = prefs.getBoolean(KEY_BCH_ENABLED, false)
        etBchAddress.isEnabled = switchBCH.isChecked
        etBchPool.isEnabled = switchBCH.isChecked
        spinnerMiningMode.setSelection(prefs.getInt(KEY_MODE, MiningMode.CPU.ordinal).coerceIn(0, MiningMode.values().lastIndex))
        spinnerGpuBackend.setSelection(prefs.getInt(KEY_BACKEND, GpuBackend.AUTO.ordinal).coerceIn(0, GpuBackend.values().lastIndex))
    }

    private fun setupActions() {
        switchBCH.setOnCheckedChangeListener { _, isChecked ->
            etBchAddress.isEnabled = isChecked
            etBchPool.isEnabled = isChecked
        }
        btnStart.setOnClickListener { startMining() }
        btnStop.setOnClickListener { stopMining() }
        btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MinerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun checkGpuSupport() {
        Thread {
            val info = NativeMiner.describeDevices(assets)
            runOnUiThread { tvDeviceInfo.text = info }
        }.start()
    }

    private fun startMining() {
        val btcAddr = etBtcAddress.text.toString().trim()
        if (btcAddr.isEmpty()) {
            Toast.makeText(this, "Enter BTC address", Toast.LENGTH_SHORT).show()
            return
        }

        val (poolHost, poolPort) = parsePool(etBtcPool.text.toString(), 3333)
        if (poolHost.isBlank()) {
            Toast.makeText(this, "Enter BTC pool host", Toast.LENGTH_SHORT).show()
            return
        }

        val cores = etCores.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val diff = etDifficulty.text.toString().trim().toDoubleOrNull() ?: 0.0
        val mode = MiningMode.values()[spinnerMiningMode.selectedItemPosition.coerceIn(0, MiningMode.values().lastIndex)]
        val backend = GpuBackend.values()[spinnerGpuBackend.selectedItemPosition.coerceIn(0, GpuBackend.values().lastIndex)]

        var bchAddr = ""
        var bchHost = ""
        var bchPort = 0
        if (switchBCH.isChecked) {
            bchAddr = etBchAddress.text.toString().trim()
            if (bchAddr.isEmpty()) {
                Toast.makeText(this, "Enter BCH address or disable BCH", Toast.LENGTH_SHORT).show()
                return
            }
            val parsed = parsePool(etBchPool.text.toString(), 3333)
            bchHost = parsed.first
            bchPort = parsed.second
        }

        saveSettings()
        tvShares.text = ""
        tvBtcPoolStatus.text = "BTC: Connecting..."
        tvBtcPoolStatus.setTextColor(0xFFFFD166.toInt())
        tvBchPoolStatus.text = "BCH: Connecting..."
        tvBchPoolStatus.setTextColor(0xFFFFD166.toInt())
        tvWorkers.text = "Starting..."
        minerService?.startMining(
            btcAddr = btcAddr,
            bchAddr = bchAddr,
            btcPoolHost = poolHost,
            btcPoolPort = poolPort,
            bchPoolHost = bchHost,
            bchPoolPort = bchPort,
            cores = cores,
            mode = mode,
            backend = backend,
            manualDifficulty = diff,
            tgToken = etTgToken.text.toString(),
            tgChat = etTgChat.text.toString(),
            assetManager = assets
        )
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Starting ${mode.label}..."
    }

    private fun stopMining() {
        minerService?.stopMining()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Stopped"
        tvHashrate.text = "0 H/s"
        tvBtcPoolStatus.text = "BTC: Disconnected"
        tvBtcPoolStatus.setTextColor(0xFFFF6B6B.toInt())
        tvBchPoolStatus.text = if (switchBCH.isChecked) "BCH: Disconnected" else "BCH: Off"
        tvBchPoolStatus.setTextColor(if (switchBCH.isChecked) 0xFFFF6B6B.toInt() else 0xFF888888.toInt())
        tvWorkers.text = "0 workers"
    }

    private fun parsePool(text: String, defaultPort: Int): Pair<String, Int> {
        val clean = text.trim().removePrefix("stratum+tcp://")
        val idx = clean.lastIndexOf(':')
        return if (idx > 0 && idx < clean.lastIndex) {
            val host = clean.substring(0, idx)
            val port = clean.substring(idx + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            clean to defaultPort
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_BTC_ADDRESS, etBtcAddress.text.toString().trim())
            .putString(KEY_BCH_ADDRESS, etBchAddress.text.toString().trim())
            .putString(KEY_BTC_POOL, etBtcPool.text.toString().trim())
            .putString(KEY_BCH_POOL, etBchPool.text.toString().trim())
            .putString(KEY_TG_TOKEN, etTgToken.text.toString().trim())
            .putString(KEY_TG_CHAT, etTgChat.text.toString().trim())
            .putInt(KEY_CORES, etCores.text.toString().toIntOrNull() ?: 1)
            .putString(KEY_DIFFICULTY, etDifficulty.text.toString().trim())
            .putBoolean(KEY_BCH_ENABLED, switchBCH.isChecked)
            .putInt(KEY_MODE, spinnerMiningMode.selectedItemPosition)
            .putInt(KEY_BACKEND, spinnerGpuBackend.selectedItemPosition)
            .apply()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY_BTC_ADDRESS = "btc_address"
        private const val KEY_BCH_ADDRESS = "bch_address"
        private const val KEY_BTC_POOL = "btc_pool"
        private const val KEY_BCH_POOL = "bch_pool"
        private const val KEY_TG_TOKEN = "tg_token"
        private const val KEY_TG_CHAT = "tg_chat"
        private const val KEY_CORES = "cores"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_BCH_ENABLED = "bch_enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_BACKEND = "backend"
    }
}
