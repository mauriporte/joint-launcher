package com.mau.hub

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App lock: apps you lock (hold an app → Lock app) ask for a 4-digit PIN before they open, from their tile, Open, or a
 * row's tile that jumps into them. Locked apps are per profile; the PIN is the TV's, kept only as a salted hash.
 * shortcut: a PIN on the TV only; approving on the phone (fingerprint, face) comes with the companion app.
 */
class AppLock(private val a: MainActivity) {
    private val p get() = a.hub.prefs
    private var wrong = 0
    private var waitUntil = 0L

    fun locked(): Set<String> = p.getStringSet("locked_apps", null) ?: emptySet()
    fun isLocked(pkg: String?) = pkg != null && Links.canonical(pkg) in locked()
    fun hasPin() = p.contains(PIN)

    /** Runs [go] straight away, or once the PIN is right if [pkg] is locked. */
    fun guard(pkg: String?, go: () -> Unit): Unit = if (!isLocked(pkg)) go() else ask("Enter your PIN", go)

    /** Hold menu: lock (setting a PIN the first time) or unlock (asks the PIN). */
    fun toggle(pkg: String, label: String, done: () -> Unit) {
        val key = Links.canonical(pkg)
        fun save(on: Boolean) { p.edit().putStringSet("locked_apps", locked().let { if (on) it + key else it - key }).apply(); done() }
        when {
            key in locked() -> ask("PIN to unlock $label") { save(false); a.toast("$label unlocked") }
            hasPin() -> { save(true); a.toast("$label locked") }
            else -> newPin { save(true); a.toast("$label locked") }
        }
    }

    /** Settings: a new PIN, after the old one. */
    fun changePin(done: () -> Unit): Unit = if (hasPin()) ask("Current PIN") { newPin(done) } else newPin(done)

    private fun newPin(done: () -> Unit): Unit = pad("Choose a 4-digit PIN") { first ->
        pad("Enter it again") { again ->
            if (again != first) { a.toast("The PINs didn't match. Try again."); newPin(done); return@pad }
            val salt = ByteArray(16).also(SecureRandom()::nextBytes).hex()
            p.edit().putString(PIN, "$salt:${hash(salt, first)}").apply()
            done()
        }
    }

    private fun ask(title: String, ok: () -> Unit) {
        val left = waitUntil - System.currentTimeMillis()
        if (left > 0) { a.toast("Too many wrong PINs. Try again in ${(left + 999) / 1000} s."); return }
        pad(title) { pin ->
            val (salt, h) = p.getString(PIN, ":")!!.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (hash(salt, pin) == h) { wrong = 0; ok() }
            else if (++wrong >= 5) { wrong = 0; waitUntil = System.currentTimeMillis() + 30_000; a.toast("Wrong PIN. Wait 30 s.") }
            else { a.toast("Wrong PIN"); ask(title, ok) }
        }
    }

    /** Four dots and a keypad the remote can reach (or its number keys); done once four digits are in. */
    private fun pad(title: String, entered: (String) -> Unit) {
        val pin = StringBuilder()
        val dots = a.text(34f, Color.WHITE).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
        fun redraw() { dots.text = "●".repeat(pin.length) + "○".repeat(4 - pin.length) }
        redraw()
        lateinit var dialog: AlertDialog
        fun press(d: Char?) {
            if (d == null) { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1) } else if (pin.length < 4) pin.append(d)
            redraw()
            if (pin.length == 4) { dialog.dismiss(); entered(pin.toString()) }
        }
        val keys = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, a.dp(16), 0, 0)
            listOf("123", "456", "789", "⌫0").forEach { line ->
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER
                    line.forEach { c -> addView(a.button(c.toString()) { press(c.takeIf { it != '⌫' }) }.apply {
                        layoutParams = LinearLayout.LayoutParams(a.dp(72), a.dp(48)).apply { setMargins(a.dp(6), a.dp(6), a.dp(6), a.dp(6)) }
                        contentDescription = if (c == '⌫') "Delete" else c.toString()
                    }) }
                })
            }
        }
        dialog = AlertDialog.Builder(a).setTitle(title)
            .setView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(8)); addView(dots); addView(keys) })
            .setNegativeButton("Cancel", null)
            .setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (code - KeyEvent.KEYCODE_0)); true }
                    KeyEvent.KEYCODE_DEL -> { press(null); true }
                    else -> false
                }
            }.show()
        keys.post { ((keys.getChildAt(0) as LinearLayout).getChildAt(0)).requestFocus() } // on "1", not on Cancel
    }

    companion object {
        const val PIN = "app_lock_pin"
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun hash(salt: String, pin: String) = MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).hex()
    }
}
