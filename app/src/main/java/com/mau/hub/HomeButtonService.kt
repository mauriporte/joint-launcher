package com.mau.hub

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV keeps the Home button for its own launcher (and its setup "recovery" app if that's disabled), ignoring the
 * default-home setting. This accessibility service catches the Home key first and opens Joint Launcher instead.
 * The user switches it on in Settings > Accessibility; switching it off gives Home back to Google TV.
 */
class HomeButtonService : AccessibilityService() {
    // the app asks for a system action (lock the screen) by broadcast: this service runs in its own process
    private val asked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!i.hasExtra("watch")) { performGlobalAction(i.getIntExtra("action", 0)); return }
            // the launcher says this just before it opens the app, and the app's window can be reported before this arrives:
            // put the strip up now (any other app coming up takes it down). Remembered for when Android restarts the service.
            watched = i.getStringExtra("watch")
            prefs(this@HomeButtonService).edit().putString("watched", watched).apply()
            showStrip()
        }
    }

    // phone: the app a swipe opens (Beeper). While it's on screen, a thin strip on the left edge (free with the 3-button
    // bar) takes a swipe to the right back to Home, as if Beeper were a page beside it
    private var watched: String? = null
    private var strip: android.view.View? = null

    private fun showStrip() {
        if (strip != null) return
        val dp = resources.displayMetrics.density
        var x0 = 0f
        strip = android.view.View(this).apply {
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> x0 = e.rawX
                    android.view.MotionEvent.ACTION_MOVE -> if (e.rawX - x0 > 70 * dp) { hideStrip(); performGlobalAction(GLOBAL_ACTION_HOME) }
                }
                true
            }
        }
        val lp = android.view.WindowManager.LayoutParams((16 * dp).toInt(), -1,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.START }
        runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(strip, lp) }.onFailure { strip = null }
    }

    private fun hideStrip() {
        strip?.let { runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) } }
        strip = null
    }

    override fun onServiceConnected() {
        wanted(this, true)
        watched = prefs(this).getString("watched", null)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(asked, android.content.IntentFilter(ACTION), RECEIVER_NOT_EXPORTED)
        else registerReceiver(asked, android.content.IntentFilter(ACTION))
    }
    override fun onDestroy() { hideStrip(); runCatching { unregisterReceiver(asked) }; super.onDestroy() }

    // the user switched it off (an app update kills the process without calling this, so it stays "wanted")
    override fun onUnbind(intent: Intent?): Boolean { if (!restoring) wanted(this, false); return super.onUnbind(intent) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addCategory(Intent.CATEGORY_HOME) // MainActivity treats this like Home: back to the top of the home screen
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }
        return true // both down and up consumed, so Google TV's launcher never sees the press
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || watched == null) return
        val pkg = event.packageName?.toString() ?: return
        // the shade or the keyboard opening over the app isn't leaving it
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().substringBefore('/')
        if (pkg == "com.android.systemui" || pkg == ime) return
        // our own strip appearing is a window of this app too: only Joint Launcher's Home screen counts as leaving the app
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        if (pkg == watched) showStrip() else hideStrip()
    }
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var restoring = false // our own off-and-on below isn't the user switching it off
        private const val ACTION = "com.mau.hub.GLOBAL_ACTION"

        /** Phone: while [pkg] (the swipe-left app) is on screen, a swipe right from the left edge goes back Home. */
        fun watch(c: Context, pkg: String) = c.sendBroadcast(Intent(ACTION).setPackage(c.packageName).putExtra("watch", pkg))

        /** A system action only an accessibility service may do (lock the screen, for a gesture); false while it's switched off. */
        fun global(c: Context, action: Int): Boolean {
            val me = ComponentName(c, HomeButtonService::class.java).flattenToString()
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').contains(me)
            if (on) c.sendBroadcast(Intent(ACTION).setPackage(c.packageName).putExtra("action", action))
            return on
        }

        private fun prefs(c: Context) = c.getSharedPreferences("home_button", Context.MODE_PRIVATE)
        private fun wanted(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wanted", on).commit()

        /**
         * Updating (or force-stopping) the app stops this service. If it was on before, and the user granted WRITE_SECURE_SETTINGS
         * once over adb (`adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`), switch it back on.
         * Touches only this app's own entry in the enabled-services list.
         */
        /** done: an update receiver's goAsync(), finished once the service is back on the list (see the off/on below). */
        fun restore(c: Context, done: BroadcastReceiver.PendingResult? = null) {
            try { restoreNow(c) } finally { done?.finish() }
        }

        // Runs on a background thread when it has to wait, so the off/on below survives the process being torn down
        // right after an update (a delayed re-add on the main thread was lost with it, leaving the service off).
        private fun restoreNow(c: Context) {
            // asked of Android: the service lives in its own process (":home"), so `running` here never sees it
            val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            if (am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo.serviceInfo.packageName == c.packageName }) return
            if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
            if (!prefs(c).getBoolean("wanted", false)) return
            val me = ComponentName(c, HomeButtonService::class.java).flattenToString()
            val cr = c.contentResolver
            // read again right before each write: Joint Remote repairs its own entry in this same list, and a stale copy would erase it
            fun enabled() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotEmpty() }
            // listed but not running (Android stops it when the app is force-stopped and marks it crashed): taking it off the
            // list and, a moment later, putting it back makes Android start it again (both at once read as no change)
            fun add() = runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ((enabled() - me) + me).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                restoring = false
            }
            if (me !in enabled()) { add(); return }
            restoring = true
            runCatching { Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (enabled() - me).joinToString(":")) }
            Thread.sleep(800) // both at once read as no change
            add()
        }
    }

    /** Runs right after an update installs, so Home works again without opening the app first. */
    class Restorer : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val done = goAsync() // keeps the process alive until the service is back on
            Thread { restore(c, done) }.start()
        }
    }
}
