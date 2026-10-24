package com.mau.hub

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Hold OK on an app in the sidebar or the apps row to move it, Google TV's way: the app lifts (grows, casts a
 * shadow), the others dim, and thin white chevrons sit just outside the icon on the sides it can move to.
 * onDrop(container, order) saves the new order.
 */
class MoveMode(private val a: Activity, private val touch: Boolean, private val onDrop: (LinearLayout, List<String>) -> Unit) {
    /** The app being moved, the list it lives in, its axis, and the arrow views drawn on it. */
    private class State(val view: View, val container: LinearLayout, val horizontal: Boolean, val arrows: Map<Int, View>) {
        var armed = false // the OK release that ends the long-press must not also drop the app
    }
    private var moving: State? = null
    val active get() = moving != null

    /** App entries are the children tagged with an app key (the sidebar also holds home, search, rows, settings). */
    private fun appChildren(c: LinearLayout) = (0 until c.childCount).map(c::getChildAt).filter { (it.tag as? String)?.startsWith("rail:") == false }

    /** host = the FrameLayout the arrows are drawn on. armed = started from the hold menu (no key still held down). */
    fun start(v: View, container: LinearLayout, horizontal: Boolean, host: FrameLayout, armed: Boolean = true) {
        if (moving != null) return
        val reach = (if (horizontal) host.width else host.height) / 2f + a.dp(12)
        fun chevron(dir: Int, rotation: Float) = ImageView(a).apply {
            setImageResource(R.drawable.ic_chevron); setColorFilter(Color.WHITE); this.rotation = rotation
            if (horizontal) translationX = dir * reach else translationY = dir * reach
        }.also { host.addView(it, FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER)) }
        val arrows = if (horizontal) mapOf(-1 to chevron(-1, 180f), 1 to chevron(1, 0f)) else mapOf(-1 to chevron(-1, 270f), 1 to chevron(1, 90f))
        appChildren(container).forEach { if (it !== v) it.animate().alpha(0.35f).setDuration(150).start() } // dim the others
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start() // lift
        v.elevation = a.dp(16).toFloat()
        moving = State(v, container, horizontal, arrows).also { it.armed = armed }
        updateArrows()
    }

    /**
     * The hold menu's move entries: "Move" (then the remote's arrows) on a TV; on touchscreens, which have no arrow keys,
     * "Move earlier" / "Move later", one step per tap.
     */
    fun options(v: View, container: LinearLayout, horizontal: Boolean, host: FrameLayout): List<Pair<String, () -> Unit>> =
        if (!touch) listOf("Move" to { start(v, container, horizontal, host) })
        else listOf("Move earlier" to { step(v, container, -1) }, "Move later" to { step(v, container, 1) })

    private fun step(v: View, container: LinearLayout, d: Int) {
        val apps = appChildren(container)
        val target = apps.getOrNull(apps.indexOf(v) + d) ?: return
        val to = container.indexOfChild(target)
        container.removeView(v)
        container.addView(v, to)
        onDrop(container, appChildren(container).mapNotNull { it.tag as? String })
    }

    /** Arrows only on sides the app can still move to. */
    private fun updateArrows() {
        val m = moving ?: return
        val apps = appChildren(m.container)
        val pos = apps.indexOf(m.view)
        m.arrows[-1]?.visibility = if (pos > 0) View.VISIBLE else View.INVISIBLE
        m.arrows[1]?.visibility = if (pos < apps.size - 1) View.VISIBLE else View.INVISIBLE
    }

    private fun shift(d: Int) {
        val m = moving ?: return
        val apps = appChildren(m.container)
        val target = apps.getOrNull(apps.indexOf(m.view) + d) ?: return
        val to = m.container.indexOfChild(target)
        // removing the focused view would hand focus to a card behind (closing the sidebar / scrolling the page);
        // block focus everywhere for the swap, then give it straight back to the moved app
        val root = a.window.decorView as ViewGroup
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        m.container.removeView(m.view)
        m.container.addView(m.view, to)
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        m.view.requestFocus()
        updateArrows()
    }

    /** Drop the app: undo the dimming and arrows, hand the new order to onDrop. */
    fun end() {
        val m = moving ?: return
        moving = null
        m.arrows.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val apps = appChildren(m.container)
        apps.forEach { it.animate().alpha(1f).setDuration(150).start() }
        m.view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        m.view.elevation = 0f
        onDrop(m.container, apps.mapNotNull { it.tag as? String })
        m.view.requestFocus()
    }

    /** While moving, the remote drives the app: arrows on its axis move it, OK or Back drops it, everything else is ignored. */
    fun onKey(e: KeyEvent): Boolean {
        val m = moving ?: return false
        if (e.action == KeyEvent.ACTION_UP) { m.armed = true; return true }
        if (e.repeatCount > 0 && !m.armed) return true // still holding OK from the long-press
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (m.horizontal) shift(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (m.horizontal) shift(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (!m.horizontal) shift(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!m.horizontal) shift(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (m.armed) end()
            KeyEvent.KEYCODE_BACK -> end()
        }
        return true
    }

    companion object {
        /** Hold OK on an app: a small Google-TV-style menu next to it (beside = to its right, else above it). */
        fun holdMenu(a: Activity, anchor: View, beside: Boolean, options: List<Pair<String, () -> Unit>>) {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { cornerRadius = a.dp(14).toFloat(); setColor(0xF2202127.toInt()) }
                elevation = a.dp(12).toFloat()
            }
            val popup = android.widget.PopupWindow(box, -2, -2, true)
            options.forEach { (label, act) ->
                box.addView(a.button(label) { popup.dismiss(); act() }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(4) }
                })
            }
            box.measure(0, 0)
            if (beside) popup.showAsDropDown(anchor, anchor.width, -(anchor.height + box.measuredHeight) / 2)
            else popup.showAsDropDown(anchor, (anchor.width - box.measuredWidth) / 2, -(anchor.height + box.measuredHeight + a.dp(8)))
            box.getChildAt(0)?.requestFocus()
        }
    }
}
