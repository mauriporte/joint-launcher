package com.mau.hub

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The TV's quick panel (the top bar's gear): HDMI inputs, straight to the TV's audio output, sound and picture pages,
 * a sleep timer, ADB debugging and the screensaver, without digging through the TV's settings. A slim panel down the
 * right side, so what's playing stays in view.
 */
class QuickPanel(private val a: MainActivity) {
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), 0, a.dp(24), a.dp(24)) }
    private lateinit var dialog: Dialog

    fun show() {
        dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        draw()
        dialog.setContentView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            addView(a.text(24f, Color.WHITE).apply {
                text = "Quick settings"; typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(a.dp(24), a.dp(20), a.dp(16), a.dp(4))
            })
            addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dialog.window?.apply {
            setLayout(minOf(a.resources.displayMetrics.widthPixels * 2 / 5, a.dp(440)), -1)
            setGravity(Gravity.END)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private fun draw() {
        list.removeAllViews()

        val inputs = hdmiInputs()
        if (inputs.isNotEmpty()) {
            heading("Input")
            buttons(*inputs.map { (info, label, connected) ->
                (if (connected) label else "$label (nothing plugged in)") to { open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))) }
            }.toTypedArray())
        }

        heading("Sound and picture")
        // the TV maker's own pages, opened directly: one press instead of Settings > Display & Sound > ...
        buttons("Audio output" to { open(Intent().setClassName("com.android.tv.settings", "com.xiaomi.gtv.settings.sound.MiAudioOutputActivity"),
                    Intent(Settings.ACTION_SOUND_SETTINGS)) },
            "Sound" to { open(Intent("android.settings.SOUND_SPEAKER_STATUS"), Intent(Settings.ACTION_SOUND_SETTINGS)) },
            "Picture" to { open(Intent("com.mitv.settings.PICTURE_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS)) })

        heading("Sleep timer")
        list.addView(a.text(14f, TEXT_MUTED).apply {
            text = SleepTimer.left(a)?.let { "The TV turns off in ${(it + 59_999) / 60_000} min" } ?: "Off"
        })
        buttons(*(listOf(0) + SleepTimer.CHOICES).map { m ->
            (if (m == 0) "Off" else "$m min") to { SleepTimer.set(a, m); draw() }
        }.toTypedArray())

        heading("More")
        val adb = Settings.Global.getInt(a.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        buttons("ADB debugging: ${if (adb) "on" else "off"}" to { adb(!adb) },
            "Screensaver now" to { dialog.dismiss(); open(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator")) },
            "All TV settings" to { open(Intent(Settings.ACTION_SETTINGS)) })
    }

    /** The HDMI inputs as (input, its name on the TV, something plugged in), in port order. */
    private fun hdmiInputs(): List<Triple<TvInputInfo, String, Boolean>> {
        val tim = a.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching {
            tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(a) }
                .map { Triple(it, (it.loadCustomLabel(a) ?: it.loadLabel(a)).toString(), tim.getInputState(it.id) != TvInputManager.INPUT_STATE_DISCONNECTED) }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }

    /** Switching ADB off also cuts off whatever reaches the TV over the network (Home Assistant's remote, installs from the PC). */
    private fun adb(on: Boolean) {
        fun write() {
            runCatching { Settings.Global.putInt(a.contentResolver, Settings.Global.ADB_ENABLED, if (on) 1 else 0) }
                .onFailure { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); return }
            draw()
        }
        if (on) write()
        else AlertDialog.Builder(a).setTitle("Switch off ADB debugging?")
            .setMessage("Anything that controls the TV over the network through ADB (Home Assistant, installs from a computer) stops working until you switch it back on.")
            .setPositiveButton("Switch off") { _, _ -> write() }.setNegativeButton("Cancel", null).show()
    }

    /** Opens the first of [intents] the TV has. */
    private fun open(vararg intents: Intent) {
        for (i in intents) if (runCatching { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) { dialog.dismiss(); return }
        a.toast("This TV doesn't have that")
    }

    private fun heading(t: String) = list.addView(a.text(19f, Color.WHITE).apply {
        text = t; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(24); bottomMargin = a.dp(4) })

    // wraps onto new lines, like the apps grid, so every choice is reachable with the remote
    private fun buttons(vararg b: Pair<String, () -> Unit>) = list.addView(FlowBox(a).apply {
        b.forEach { (label, go) -> addView(a.button(label) { go() }) }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4) })
}

/**
 * Sleep timer. When it runs out, a countdown gives you a minute to press a button and keep watching; then the TV is put
 * to sleep through Android's "inattentive sleep" timeout (set to its minimum, which an app may do with
 * WRITE_SECURE_SETTINGS), and that setting is put back as soon as the TV is awake or starts again.
 */
object SleepTimer {
    val CHOICES = listOf(15, 30, 45, 60, 90, 120)
    private const val ATTENTIVE = "attentive_timeout"
    private const val SLEEP = "com.mau.hub.SLEEP"
    private const val RESTORE = "com.mau.hub.SLEEP_RESTORE"

    private fun prefs(c: Context) = c.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
    private fun alarm(c: Context, action: String) =
        PendingIntent.getBroadcast(c, 0, Intent(action).setClass(c, Receiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Milliseconds until the TV turns off, or null if no timer is set. */
    fun left(c: Context) = prefs(c).getLong("at", 0).minus(System.currentTimeMillis()).takeIf { it > 0 }

    /** Turns off in [minutes] (0: no timer). */
    fun set(c: Context, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(alarm(c, SLEEP))
        if (minutes == 0) { prefs(c).edit().remove("at").apply(); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs(c).edit().putLong("at", at).apply()
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c, SLEEP))
    }

    /** Time's up and nobody pressed a button: Android puts the TV to sleep at once (the remote was last used over a minute ago). */
    fun sleepNow(c: Context) {
        val cr = c.contentResolver
        val p = prefs(c)
        if (!p.contains("attentive")) p.edit().putString("attentive", Settings.Secure.getString(cr, ATTENTIVE) ?: "-1").commit()
        if (runCatching { Settings.Secure.putString(cr, ATTENTIVE, "1") }.isFailure) { c.toast("Couldn't turn the TV off: Joint Launcher needs WRITE_SECURE_SETTINGS"); restore(c); return }
        // delivered when the TV next wakes; the boot receiver and Home cover a power cut
        c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 20_000, alarm(c, RESTORE))
    }

    /** Puts the inattentive-sleep setting back to what it was before the timer used it. */
    fun restore(c: Context) {
        val p = prefs(c)
        p.edit().remove("at").apply()
        val old = p.getString("attentive", null) ?: return
        if (runCatching { Settings.Secure.putString(c.contentResolver, ATTENTIVE, old) }.isSuccess) p.edit().remove("attentive").apply()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                SLEEP -> c.startActivity(Intent(c, Countdown::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> restore(c) // RESTORE or the TV starting up
            }
        }
    }

    /** "Turning off in 60": any button keeps the TV on. */
    class Countdown : Activity() {
        private val main = Handler(Looper.getMainLooper())
        private var left = 60
        private val text by lazy { text(34f, Color.WHITE).apply { gravity = Gravity.CENTER } }
        private val tick = object : Runnable {
            override fun run() {
                // stays up while the TV goes off: closing first would bring Home back, which puts the old setting back too soon
                if (left == 0) { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); sleepNow(this@Countdown); main.postDelayed({ finish() }, 15_000); return }
                text.text = "The TV turns off in $left s\n\nPress any button to keep watching"
                left--; main.postDelayed(this, 1000)
            }
        }

        override fun onCreate(b: Bundle?) {
            super.onCreate(b)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(text.apply { setBackgroundColor(0xCC000000.toInt()) })
            tick.run()
        }

        override fun onKeyDown(code: Int, e: KeyEvent?): Boolean { finish(); return true }
        // a button, Home or another app coming up: keep watching (left is 0 only once the TV is being turned off)
        override fun onPause() { super.onPause(); if (left > 0) { main.removeCallbacks(tick); prefs(this).edit().remove("at").apply(); finish() } }
        override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    }
}
