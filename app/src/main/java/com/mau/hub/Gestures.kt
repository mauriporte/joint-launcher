package com.mau.hub

import android.app.AlertDialog
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Touchscreens: swipes across Home and double-taps on empty space, like Pro Launcher. Each is set in Settings → Gestures:
 * finger right opens the widget page (it sits to the left of Home), finger left opens an app (Beeper), a double-tap
 * does nothing until you pick something. Swipes that start on something that scrolls sideways (a row of titles, a
 * widget's list) are left to it.
 */
class Gestures(private val a: MainActivity) {
    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var tracking = false // this touch may still become a swipe
    private var side = false     // it started on something that scrolls sideways: no left/right swipe
    private var swallow = false  // it became one: the rest of the touch is ours
    private var lastTapAt = 0L; private var lastTapX = 0f; private var lastTapY = 0f

    /** From MainActivity.dispatchTouchEvent: true = handled here, don't pass it on. */
    fun onTouch(e: MotionEvent, pass: (MotionEvent) -> Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; t0 = e.eventTime; swallow = false
                tracking = allowed() && e.rawX > a.dp(32)
                side = tracking && scrolls(a.window.decorView, e.rawX.toInt(), e.rawY.toInt()) { it.canScrollHorizontally(1) || it.canScrollHorizontally(-1) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (swallow) return true
                if (!tracking) return false
                val dx = e.rawX - x0; val dy = e.rawY - y0
                val swipe = when {
                    !side && abs(dx) > a.dp(90) && abs(dx) > 2 * abs(dy) -> { swiped(right = dx > 0); true }
                    // the phone: up and down too, unless what's under the finger scrolls that way (the drawer's list)
                    a.hub.phone && abs(dy) > a.dp(80) && abs(dy) > 2 * abs(dx) -> {
                        val dir = if (dy < 0) 1 else -1
                        !scrolls(a.window.decorView, x0.toInt(), y0.toInt()) { it.canScrollVertically(dir) } && vertical(up = dy < 0)
                    }
                    !a.hub.phone && abs(dy) > a.dp(48) -> { tracking = false; false } // scrolling the tablet's Home
                    else -> false
                }
                if (swipe) {
                    tracking = false; swallow = true
                    pass(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }) // the tile under the finger isn't tapped
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swallow) { swallow = false; return true }
                val tap = abs(e.rawX - x0) < a.dp(12) && abs(e.rawY - y0) < a.dp(12) && e.eventTime - t0 < 300
                if (tap && allowed() && !drawerOpen() && empty(a.window.decorView, e.rawX.toInt(), e.rawY.toInt())) {
                    val again = e.eventTime - lastTapAt < 350 && abs(e.rawX - lastTapX) < a.dp(40) && abs(e.rawY - lastTapY) < a.dp(40)
                    lastTapAt = if (again) 0 else e.eventTime; lastTapX = e.rawX; lastTapY = e.rawY
                    if (again) run(action(DOUBLE_TAP))
                }
            }
            MotionEvent.ACTION_CANCEL -> if (swallow) { swallow = false; return true }
        }
        return false
    }

    private fun allowed() = !a.moveMode.active && !a.widgets.editing && !a.sidebar.isOpen && !a.detail.isShown
    private fun drawerOpen() = a.hub.phone && a.phoneHome.drawer.isOpen

    private fun swiped(right: Boolean) {
        val page = a.widgetPage
        when {
            drawerOpen() -> Unit // the drawer's own lists
            page.isOpen -> if (!right) page.close() // the page is to the left of Home: finger left goes back
            else -> run(action(if (right) SWIPE_RIGHT else SWIPE_LEFT))
        }
    }

    /** Phone: up opens every app, down closes them (or, on Home, pulls the notifications down). False = not ours. */
    private fun vertical(up: Boolean): Boolean {
        if (a.widgetPage.isOpen) return false // it scrolls
        val drawer = a.phoneHome.drawer
        when {
            drawer.isOpen -> if (!up) drawer.close() else return false
            up -> drawer.open()
            else -> expandNotifications()
        }
        return true
    }

    fun action(key: String): String = a.hub.prefs.getString(key, null) ?: DEFAULTS.getValue(key)

    private fun run(what: String) {
        when {
            what == WIDGETS -> a.widgetPage.open()
            what.startsWith("app:") -> {
                val pkg = what.removePrefix("app:")
                if (a.hub.phone) HomeButtonService.watch(a, pkg) // a swipe right from its left edge comes back here
                a.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    // it slides in from the side the finger came from, like a page next to Home
                    val opts = android.app.ActivityOptions.makeCustomAnimation(a, R.anim.page_in_right, R.anim.page_out_left).toBundle()
                    runCatching { a.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
                } ?: a.toast("That app isn't installed")
            }
            what == "search" -> if (a.hub.phone) a.phoneHome.drawer.open(typing = true) else a.searchBar.open()
            what == "notifications" -> expandNotifications()
            what == "lock" -> if (!HomeButtonService.global(a, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
                a.toast("To lock the screen, turn on Joint Launcher in Settings → Accessibility")
        }
    }

    // the status bar's own call (needs only EXPAND_STATUS_BAR, an ordinary permission); not in the public SDK
    @android.annotation.SuppressLint("WrongConstant")
    private fun expandNotifications() = runCatching {
        val sb = a.getSystemService("statusbar")
        sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
    }.onFailure { a.toast("Couldn't open the notifications") }

    /** True when something under (x, y) scrolls the way [can] asks: the swipe is its. */
    private fun scrolls(v: View, x: Int, y: Int, can: (View) -> Boolean): Boolean {
        if (!under(v, x, y)) return false
        if (can(v)) return true
        return v is ViewGroup && (v.childCount - 1 downTo 0).any { scrolls(v.getChildAt(it), x, y, can) }
    }

    /** True when nothing under (x, y) takes taps (no tile, button, header or widget): a double-tap there is a gesture. */
    private fun empty(v: View, x: Int, y: Int): Boolean {
        if (!under(v, x, y)) return true
        if (v.isClickable || v is android.webkit.WebView || v is android.widget.EditText) return false
        return v !is ViewGroup || (0 until v.childCount).all { empty(v.getChildAt(it), x, y) }
    }

    private fun under(v: View, x: Int, y: Int): Boolean {
        if (!v.isShown || v.alpha == 0f) return false
        val at = IntArray(2).also(v::getLocationOnScreen)
        return x >= at[0] && y >= at[1] && x < at[0] + v.width && y < at[1] + v.height
    }

    /** Settings → Gestures: one line per gesture. */
    fun choose(key: String, done: () -> Unit) {
        val opts = (if (key == SWIPE_RIGHT) listOf(WIDGETS to "Widget page") else emptyList()) +
            listOf("app" to "Open an app…", "notifications" to "Notifications", "search" to "Search", "lock" to "Lock the screen", NONE to "Nothing")
        AlertDialog.Builder(a).setTitle(LABELS.getValue(key)).setItems(opts.map { it.second }.toTypedArray()) { _, i ->
            val v = opts[i].first
            if (v == "app") pickApp { pkg -> save(key, "app:$pkg"); done() } else { save(key, v); done() }
        }.show()
    }

    private fun save(key: String, v: String) {
        a.hub.prefs.edit().putString(key, v).apply()
        a.loadHome() // the Widgets section moves between Home and the widget page
    }

    private fun pickApp(chosen: (String) -> Unit) {
        val pm = a.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        AlertDialog.Builder(a).setTitle("Open which app?").setItems(apps.map { it.second }.toTypedArray()) { _, i -> chosen(apps[i].first) }.show()
    }

    /** What a gesture does, for its Settings line. */
    fun describe(key: String): String = action(key).let { v ->
        when {
            v.startsWith("app:") -> v.removePrefix("app:").let { pkg ->
                runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            }
            else -> mapOf(WIDGETS to "Widget page", "notifications" to "Notifications", "search" to "Search", "lock" to "Lock the screen").getOrDefault(v, "Nothing")
        }
    }

    /** The Widgets section lives on the widget page while a swipe opens it, instead of on Home. */
    fun widgetsOnPage() = action(SWIPE_RIGHT) == WIDGETS

    companion object {
        const val SWIPE_RIGHT = "gesture_swipe_right"
        const val SWIPE_LEFT = "gesture_swipe_left"
        const val DOUBLE_TAP = "gesture_double_tap"
        const val WIDGETS = "widgets"
        const val NONE = "none"
        val LABELS = mapOf(SWIPE_RIGHT to "Swipe right", SWIPE_LEFT to "Swipe left", DOUBLE_TAP to "Double-tap on empty space")
        private val DEFAULTS = mapOf(SWIPE_RIGHT to WIDGETS, SWIPE_LEFT to "app:com.beeper.android", DOUBLE_TAP to NONE)
    }
}

/** The widget page, to the left of Home: all your widgets, scrolling down as far as the grid goes. */
class WidgetPage(private val a: MainActivity) {
    private val body = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        // same side room as the Widgets section on Home (page 20 + rows 8/12), so widgets keep their size (Widgets.gridWidth)
        setPadding(a.dp(28), a.dp(8), a.dp(12), a.dp(24))
    }
    val view: View = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(body, FrameLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }
    val isOpen get() = view.visibility == View.VISIBLE

    fun refresh() { if (isOpen) a.rows.fillWidgets(body, onPage = true) }

    fun open() {
        if (isOpen) return
        val w = a.window.decorView.width.toFloat()
        view.visibility = View.VISIBLE; refresh()
        view.translationX = -w; view.animate().translationX(0f).setDuration(220).start()
        a.page.animate().translationX(w).setDuration(220).start()
    }

    fun close() {
        if (!isOpen) return
        val w = a.window.decorView.width.toFloat()
        a.widgets.editing = false
        view.animate().translationX(-w).setDuration(220).withEndAction { view.visibility = View.GONE; body.removeAllViews() }.start()
        a.page.animate().translationX(0f).setDuration(220).start()
    }
}
