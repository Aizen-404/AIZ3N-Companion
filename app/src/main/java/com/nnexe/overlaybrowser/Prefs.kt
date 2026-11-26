package com.nnexe.overlaybrowser

import android.content.Context

/** Everything the floating console remembers between launches. Sizes and positions are in pixels. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var x: Int
        get() = sp.getInt("x", -1)
        set(v) = sp.edit().putInt("x", v).apply()
    var y: Int
        get() = sp.getInt("y", -1)
        set(v) = sp.edit().putInt("y", v).apply()
    var width: Int
        get() = sp.getInt("w", -1)
        set(v) = sp.edit().putInt("w", v).apply()
    var height: Int
        get() = sp.getInt("h", -1)
        set(v) = sp.edit().putInt("h", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit().putInt("by", v).apply()

    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()
    var pausedOutput: Boolean
        get() = sp.getBoolean("paused_output", false)
        set(v) = sp.edit().putBoolean("paused_output", v).apply()
    var opacity: Float
        get() = sp.getFloat("opacity", 1f).coerceIn(MIN_OPACITY, 1f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    var host: String
        get() = sp.getString("agent_host", null) ?: DEFAULT_HOST
        set(v) = sp.edit().putString("agent_host", v.trim().ifEmpty { DEFAULT_HOST }).apply()
    var port: Int
        get() = sp.getInt("agent_port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("agent_port", v.coerceIn(1, 65535)).apply()

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 39481

        /** Below this the window is too faint to find again. */
        const val MIN_OPACITY = 0.1f
    }
}
