package com.mau.hub

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV keeps the Home button for its own launcher (and its setup "recovery" app if that's disabled), ignoring the
 * default-home setting. This accessibility service catches the Home key first and opens Joint Launcher instead.
 * The user switches it on in Settings > Accessibility; switching it off gives Home back to Google TV.
 */
class HomeButtonService : AccessibilityService() {
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
}
