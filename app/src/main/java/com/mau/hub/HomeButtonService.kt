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
    override fun onServiceConnected() { wanted(this, true) }

    // the user switched it off (an app update kills the process without calling this, so it stays "wanted")
    override fun onUnbind(intent: Intent?): Boolean { wanted(this, false); return super.onUnbind(intent) }

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("home_button", Context.MODE_PRIVATE)
        private fun wanted(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wanted", on).commit()

        /**
         * Updating the app switches this service off. If it was on before, and the user granted WRITE_SECURE_SETTINGS
         * once over adb (`adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`), switch it back on.
         * Touches only this app's own entry in the enabled-services list.
         */
        fun restore(c: Context) {
            if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
            if (!prefs(c).getBoolean("wanted", false)) return
            val me = ComponentName(c, HomeButtonService::class.java).flattenToString()
            val cr = c.contentResolver
            val enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotEmpty() }
            if (me in enabled) return
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (enabled + me).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
    }

    /** Runs right after an update installs, so Home works again without opening the app first. */
    class Restorer : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) restore(c) }
    }
}
