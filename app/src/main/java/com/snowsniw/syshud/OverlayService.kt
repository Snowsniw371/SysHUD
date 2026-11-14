package com.snowsniw.syshud

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.snowsniw.syshud.ACTION_STOP"
        const val ACTION_TOGGLE_LOCK = "com.snowsniw.syshud.ACTION_TOGGLE_LOCK"
        private const val CHANNEL_ID = "syshud_overlay_channel"
        private const val NOTIF_ID = 101
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: HudPrefs
    private lateinit var monitor: SystemMonitor
    private lateinit var hudView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var hudRoot: LinearLayout
    private lateinit var tvFps: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvBat: TextView
    private lateinit var tvNet: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMetrics()
            handler.postDelayed(this, 1000L)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyStyleAndFlags()
        updateMetrics()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = HudPrefs(this)
        monitor = SystemMonitor(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForegroundCompat()
        setupOverlayView()

        prefs.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        handler.post(updateRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                prefs.lockTouch = !prefs.lockTouch
                updateNotification()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView() {
        hudView = LayoutInflater.from(this).inflate(R.layout.overlay_hud, null)
        hudRoot = hudView.findViewById(R.id.hudRoot)
        tvFps = hudView.findViewById(R.id.tvFps)
        tvCpu = hudView.findViewById(R.id.tvCpu)
        tvRam = hudView.findViewById(R.id.tvRam)
        tvBat = hudView.findViewById(R.id.tvBat)
        tvNet = hudView.findViewById(R.id.tvNet)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            buildWindowFlags(prefs.lockTouch),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.hudX
            y = prefs.hudY
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        hudView.setOnTouchListener { _, event ->
            if (prefs.lockTouch) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(hudView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.hudX = layoutParams.x
                    prefs.hudY = layoutParams.y
                    true
                }
                else -> false
            }
        }

        applyStyleAndFlags()
        windowManager.addView(hudView, layoutParams)
    }

    private fun buildWindowFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (locked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun applyStyleAndFlags() {
        if (!::hudView.isInitialized) return

        val newFlags = buildWindowFlags(prefs.lockTouch)
        if (layoutParams.flags != newFlags) {
            layoutParams.flags = newFlags
            windowManager.updateViewLayout(hudView, layoutParams)
            updateNotification()
        }

        tvFps.visibility = if (prefs.showFps) View.VISIBLE else View.GONE
        tvCpu.visibility = if (prefs.showCpu) View.VISIBLE else View.GONE
        tvRam.visibility = if (prefs.showRam) View.VISIBLE else View.GONE
        tvBat.visibility = if (prefs.showBat) View.VISIBLE else View.GONE
        tvNet.visibility = if (prefs.showNet) View.VISIBLE else View.GONE

        val sizeSp = prefs.textSizeSp
        val views = listOf(tvFps, tvCpu, tvRam, tvBat, tvNet)
        views.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp) }

        val textColor = when (prefs.colorPreset) {
            1 -> Color.parseColor("#F59E0B")
            2 -> Color.parseColor("#7DD3FC")
            else -> Color.parseColor("#F8FAFC")
        }
        views.forEach { it.setTextColor(textColor) }

        hudRoot.alpha = prefs.bgAlphaPercent / 100f
    }

    private fun updateMetrics() {
        if (prefs.showFps) {
            tvFps.text = monitor.readFpsText()
        }
        if (prefs.showCpu) {
            val mhz = monitor.readCpuMaxMhz()
            tvCpu.text = if (mhz > 0) "CPU  $mhz MHz" else "CPU  N/A"
        }
        if (prefs.showRam) {
            tvRam.text = monitor.readRamText()
        }
        if (prefs.showBat) {
            tvBat.text = monitor.readBatteryText()
        }
        if (prefs.showNet) {
            tvNet.text = monitor.readNetworkText()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SysHUD Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls for active resource monitoring overlay"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val lockIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_LOCK },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val lockLabel = if (prefs.lockTouch) "Unlock" else "Lock"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("SysHUD Active")
            .setContentText(if (prefs.lockTouch) "Touches pass through HUD" else "Drag HUD to any corner")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, lockLabel, lockIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        monitor.shutdown()
        if (::hudView.isInitialized) {
            windowManager.removeView(hudView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

