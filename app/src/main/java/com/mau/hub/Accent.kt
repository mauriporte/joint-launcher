package com.mau.hub

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

internal const val ORANGE = 0xFFE8650A.toInt() // the A.R.T. accent until someone picks another

/** The accent every screen paints with: this device's override, else the shared one (master overlay "accent"), else [ORANGE]. */
internal var ACCENT = ORANGE
    private set

/**
 * Shared + override (A.R.T. accent spec): "accent" in the .30 master overlay is for every device; a local override
 * (never written to the master) wins on this device. Views read [ACCENT] when they are built, so a change recreates the activity.
 */
internal object Accent {
    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** "#RRGGBB" → colour, anything else → null. */
    fun parse(hex: String?): Int? = hex?.trim()?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.let { Color.parseColor(it) }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun override(c: Context) = parse(prefs(c).getString("accent_override", null))
    fun shared(c: Context) = parse(prefs(c).getString("accent_shared", null))

    /** Recomputes [ACCENT]; true when it changed. */
    fun load(c: Context): Boolean {
        val now = override(c) ?: shared(c) ?: ORANGE
        // Joint Remote (0.36+) on this device takes its accent from us; "" = the default orange; it ignores repeats
        runCatching { c.sendBroadcast(android.content.Intent("com.mau.remote.ACCENT").setPackage("com.mau.remote").putExtra("accent", if (now == ORANGE) "" else hex(now))) }
        return (now != ACCENT).also { ACCENT = now }
    }

    /** The master's "accent" as last read (null/blank/invalid = none: back to the orange). */
    fun sharedFromMaster(c: Context, master: JSONObject): Boolean {
        val v = parse(master.optString("accent")) ?.let(::hex)
        if (v != prefs(c).getString("accent_shared", null)) prefs(c).edit().putString("accent_shared", v).apply()
        return load(c)
    }

    fun setOverride(c: Context, color: Int?): Boolean {
        prefs(c).edit().putString("accent_override", color?.let(::hex)).apply()
        return load(c)
    }

    /** "All devices": PUT replaces the whole master, so read it, change only "accent", and send the rest back as it was. */
    fun writeShared(c: Context, color: Int) {
        val url = BuildConfig.OVERLAY_MASTER.ifEmpty { error("this build has no master overlay address") }
        val auth = HaWidget.auth(c)
        val m = JSONObject(Sources.get(url, auth)).put("accent", hex(color))
        Sources.put(url, m.toString(), auth)
        sharedFromMaster(c, m)
    }

    /** A darker shade of the accent (the dashboard's group tiles: #B34700 next to the default orange). */
    fun dark(color: Int = ACCENT) = Color.rgb((Color.red(color) * 0.77f).toInt(), (Color.green(color) * 0.70f).toInt(), (Color.blue(color) * 0.7f).toInt())

    /** A lighter shade, for links on dark pages (#F39A52 next to the default orange). */
    fun light(color: Int = ACCENT) = Color.rgb(Color.red(color) + (255 - Color.red(color)) * 35 / 100, Color.green(color) + (255 - Color.green(color)) * 35 / 100, Color.blue(color) + (255 - Color.blue(color)) * 35 / 100)

    /** Swatches for the picker; the first is the default. */
    val SWATCHES = listOf(0xFFE8650A, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
        0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFFB300, 0xFF6D4C41, 0xFF757575).map { it.toInt() }
}
