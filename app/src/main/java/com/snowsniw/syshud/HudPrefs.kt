package com.snowsniw.syshud

import android.content.Context

class HudPrefs(context: Context) {
    val prefs = context.getSharedPreferences("syshud_prefs", Context.MODE_PRIVATE)

    var showFps: Boolean
        get() = prefs.getBoolean("show_fps", false)
        set(v) = prefs.edit().putBoolean("show_fps", v).apply()

    var showCpu: Boolean
        get() = prefs.getBoolean("show_cpu", true)
        set(v) = prefs.edit().putBoolean("show_cpu", v).apply()

    var showRam: Boolean
        get() = prefs.getBoolean("show_ram", true)
        set(v) = prefs.edit().putBoolean("show_ram", v).apply()

    var showBat: Boolean
        get() = prefs.getBoolean("show_bat", true)
        set(v) = prefs.edit().putBoolean("show_bat", v).apply()

    var showNet: Boolean
        get() = prefs.getBoolean("show_net", true)
        set(v) = prefs.edit().putBoolean("show_net", v).apply()

    var lockTouch: Boolean
        get() = prefs.getBoolean("lock_touch", false)
        set(v) = prefs.edit().putBoolean("lock_touch", v).apply()

    var textSizeSp: Float
        get() = prefs.getFloat("text_size", 12f)
        set(v) = prefs.edit().putFloat("text_size", v).apply()

    var bgAlphaPercent: Int
        get() = prefs.getInt("bg_alpha", 85)
        set(v) = prefs.edit().putInt("bg_alpha", v).apply()

    // 0 = White, 1 = Amber (RTSS), 2 = Ice Blue
    var colorPreset: Int
        get() = prefs.getInt("color_preset", 0)
        set(v) = prefs.edit().putInt("color_preset", v).apply()

    var hudX: Int
        get() = prefs.getInt("hud_x", 24)
        set(v) = prefs.edit().putInt("hud_x", v).apply()

    var hudY: Int
        get() = prefs.getInt("hud_y", 80)
        set(v) = prefs.edit().putInt("hud_y", v).apply()
}
