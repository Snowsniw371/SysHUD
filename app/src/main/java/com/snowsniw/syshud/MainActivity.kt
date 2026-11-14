package com.snowsniw.syshud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var prefs: HudPrefs
    private lateinit var tvPermStatus: TextView
    private lateinit var btnToggleHud: MaterialButton
    private lateinit var swFps: MaterialSwitch

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateUiState()
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    prefs.showFps = true
                    swFps.isChecked = true
                } else {
                    prefs.showFps = false
                    swFps.isChecked = false
                    Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = HudPrefs(this)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        btnToggleHud = findViewById(R.id.btnToggleHud)
        swFps = findViewById(R.id.swFps)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setupControls()
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialSwitch>(R.id.swLockTouch).isChecked = prefs.lockTouch
        updateUiState()
    }

    private fun setupControls() {
        btnToggleHud.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                btnToggleHud.postDelayed({ updateUiState() }, 150)
            } else {
                val serviceIntent = Intent(this, OverlayService::class.java)
                startForegroundService(serviceIntent)
                btnToggleHud.postDelayed({ updateUiState() }, 150)
            }
        }

        val swCpu = findViewById<MaterialSwitch>(R.id.swCpu)
        val swRam = findViewById<MaterialSwitch>(R.id.swRam)
        val swBat = findViewById<MaterialSwitch>(R.id.swBat)
        val swNet = findViewById<MaterialSwitch>(R.id.swNet)
        val swLock = findViewById<MaterialSwitch>(R.id.swLockTouch)

        swFps.isChecked = prefs.showFps
        swCpu.isChecked = prefs.showCpu
        swRam.isChecked = prefs.showRam
        swBat.isChecked = prefs.showBat
        swNet.isChecked = prefs.showNet
        swLock.isChecked = prefs.lockTouch

        swFps.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                prefs.showFps = false
                return@setOnCheckedChangeListener
            }

            if (!isShizukuRunning()) {
                swFps.isChecked = false
                prefs.showFps = false
                Toast.makeText(this, "Shizuku service is not running", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                prefs.showFps = true
            } else {
                swFps.isChecked = false
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }

        swCpu.setOnCheckedChangeListener { _, checked -> prefs.showCpu = checked }
        swRam.setOnCheckedChangeListener { _, checked -> prefs.showRam = checked }
        swBat.setOnCheckedChangeListener { _, checked -> prefs.showBat = checked }
        swNet.setOnCheckedChangeListener { _, checked -> prefs.showNet = checked }
        swLock.setOnCheckedChangeListener { _, checked -> prefs.lockTouch = checked }

        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupColor)
        when (prefs.colorPreset) {
            1 -> chipGroup.check(R.id.chipAmber)
            2 -> chipGroup.check(R.id.chipIce)
            else -> chipGroup.check(R.id.chipWhite)
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            prefs.colorPreset = when (checkedIds.firstOrNull()) {
                R.id.chipAmber -> 1
                R.id.chipIce -> 2
                else -> 0
            }
        }

        val tvSizeLabel = findViewById<TextView>(R.id.tvSizeLabel)
        val sliderSize = findViewById<Slider>(R.id.sliderSize)
        sliderSize.value = prefs.textSizeSp
        tvSizeLabel.text = "Font size: ${prefs.textSizeSp.toInt()} sp"
        sliderSize.addOnChangeListener { _, value, _ ->
            prefs.textSizeSp = value
            tvSizeLabel.text = "Font size: ${value.toInt()} sp"
        }

        val tvAlphaLabel = findViewById<TextView>(R.id.tvAlphaLabel)
        val sliderAlpha = findViewById<Slider>(R.id.sliderAlpha)
        sliderAlpha.value = prefs.bgAlphaPercent.toFloat()
        tvAlphaLabel.text = "Background opacity: ${prefs.bgAlphaPercent}%"
        sliderAlpha.addOnChangeListener { _, value, _ ->
            prefs.bgAlphaPercent = value.toInt()
            tvAlphaLabel.text = "Background opacity: ${value.toInt()}%"
        }
    }

    private fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateUiState() {
        val hasOverlayPerm = Settings.canDrawOverlays(this)
        if (!hasOverlayPerm) {
            tvPermStatus.text = "Status: \"Display over other apps\" permission required"
            btnToggleHud.text = "Grant Permission"
        } else if (OverlayService.isRunning) {
            tvPermStatus.text = "Status: overlay running"
            btnToggleHud.text = "Stop Overlay"
        } else {
            tvPermStatus.text = "Status: ready to start"
            btnToggleHud.text = "Start Overlay"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
