package com.snowsniw.syshud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class SystemMonitor(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val memInfo = ActivityManager.MemoryInfo()
    private val coreCount = Runtime.getRuntime().availableProcessors()

    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastNetTime = SystemClock.elapsedRealtime()

    private val fpsExecutor = Executors.newSingleThreadExecutor()
    private val isPollingFps = AtomicBoolean(false)
    private val latestFps = AtomicInteger(-1)
    private var cachedLayer: String? = null
    private var lastLayerScanTime = 0L

    fun readFpsText(): String {
        val shizukuReady = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        if (!shizukuReady) {
            latestFps.set(-1)
            return "FPS  N/A"
        }

        if (isPollingFps.compareAndSet(false, true)) {
            fpsExecutor.execute {
                try {
                    latestFps.set(pollSurfaceFlingerFps())
                } catch (_: Throwable) {
                    latestFps.set(-1)
                } finally {
                    isPollingFps.set(false)
                }
            }
        }

        val fps = latestFps.get()
        return if (fps >= 0) "FPS  $fps" else "FPS  --"
    }

    private fun pollSurfaceFlingerFps(): Int {
        val nowMs = SystemClock.elapsedRealtime()
        var layer = cachedLayer

        if (layer == null || nowMs - lastLayerScanTime > 3000L) {
            layer = findActiveLayer()
            cachedLayer = layer
            lastLayerScanTime = nowMs
        }

        if (layer == null) return -1

        val output = runShizukuCommand("dumpsys SurfaceFlinger --latency \"$layer\"")
        if (output.isEmpty()) {
            cachedLayer = null
            return -1
        }

        val lines = output.lines()
        if (lines.size < 2) {
            cachedLayer = null
            return -1
        }

        val nowNano = System.nanoTime()
        val oneSecondAgo = nowNano - 1_000_000_000L
        var frameCount = 0
        var validRows = 0

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue

            val actualPresentTime = parts[1].trim().toLongOrNull() ?: continue
            if (actualPresentTime <= 0L || actualPresentTime == Long.MAX_VALUE) continue

            validRows++
            if (actualPresentTime >= oneSecondAgo) {
                frameCount++
            }
        }

        if (validRows == 0) {
            cachedLayer = null
            return -1
        }

        return frameCount
    }

    private fun findActiveLayer(): String? {
        val listOutput = runShizukuCommand("dumpsys SurfaceFlinger --list")
        if (listOutput.isEmpty()) return null

        val ignoredKeywords = listOf(
            "StatusBar", "NavigationBar", "NotificationShade", "Wallpaper",
            "ScreenDecor", "SysHUD", "InputMethod", "PipMenu", "EdgeBackGesture"
        )

        val candidates = listOutput.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && ignoredKeywords.none { line.contains(it, ignoreCase = true) }
            }

        // Priority 1: Game / video SurfaceView BLAST buffer layer (Android 12+)
        candidates.lastOrNull { it.contains("SurfaceView") && it.contains("BLAST") }?.let { return it }

        // Priority 2: Classic SurfaceView layer
        candidates.lastOrNull { it.contains("SurfaceView") }?.let { return it }

        // Priority 3: Top regular app window layer
        return candidates.lastOrNull { line ->
            line.contains('/') && line.contains('#') &&
                    !line.contains("Launcher", ignoreCase = true) &&
                    !line.contains("miui.home", ignoreCase = true)
        }
    }

    private fun runShizukuCommand(cmd: String): String {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
        return try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
        } finally {
            process.destroy()
        }
    }

    fun readCpuMaxMhz(): Int {
        var maxFreqKhz = 0
        for (i in 0 until coreCount) {
            try {
                val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (file.canRead()) {
                    val khz = file.readText().trim().toIntOrNull() ?: 0
                    if (khz > maxFreqKhz) maxFreqKhz = khz
                }
            } catch (_: Exception) {
            }
        }
        return maxFreqKhz / 1000
    }

    fun readRamText(): String {
        activityManager.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val usedGb = (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "RAM  %.1f / %.1f GB", usedGb, totalGb)
    }

    fun readBatteryText(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val currentAmps = if (abs(rawCurrent) > 10_000) {
            abs(rawCurrent) / 1_000_000.0
        } else {
            abs(rawCurrent) / 1_000.0
        }
        val watts = currentAmps * (voltageMv / 1000.0)

        return if (watts > 0.1) {
            String.format(Locale.US, "PWR  %.1f°C · %.1fW", tempC, watts)
        } else {
            String.format(Locale.US, "PWR  %.1f°C", tempC)
        }
    }

    fun readNetworkText(): String {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val elapsedSec = ((now - lastNetTime).coerceAtLeast(1L)) / 1000.0

        val rxSpeed = ((rx - lastRxBytes).coerceAtLeast(0L)) / elapsedSec
        val txSpeed = ((tx - lastTxBytes).coerceAtLeast(0L)) / elapsedSec

        lastRxBytes = rx
        lastTxBytes = tx
        lastNetTime = now

        return "NET  ↓${formatSpeed(rxSpeed)} ↑${formatSpeed(txSpeed)}"
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            String.format(Locale.US, "%.1fM", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0fK", kb)
        }
    }

    fun shutdown() {
        fpsExecutor.shutdownNow()
    }
}

