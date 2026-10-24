package com.mau.hub

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Netflix-style icon rail, hidden until Left on a row's first tile (or on the search button): the app's own icon
 * (Home), Search, one icon per app (picks the single-app view), Rows and Settings. Apps reorder with a long press.
 */
class Sidebar(private val a: MainActivity) {
    val rail = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, a.dp(12), 0, a.dp(12))
        clipChildren = false // move-mode chevrons sit just outside the icon
    }
    // scrolls when there are more apps than fit; fillViewport keeps Rows/Settings pinned to the bottom otherwise
    val panel = ScrollView(a).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        visibility = View.GONE
        setBackgroundColor(0xFF050505.toInt()) // solid panel (fully: the section icons it covers mustn't show through); the content behind is dimmed
        addView(rail)
    }
    val dim = View(a).apply {
        setBackgroundColor(0xB3000000.toInt()); visibility = View.GONE
        setOnClickListener { hide() } // touchscreens: tap outside the panel to close it
    }

    /** Rebuilt on first open after each home load (installed apps / connections may have changed). */
    var dirty = true
    val isOpen get() = panel.visibility == View.VISIBLE

    private fun build() {
        rail.removeAllViews()
        val apps = a.apps.sidebar()
        val current = a.appFilter?.takeIf { f -> apps.any { it.filter == f } }
        // the app's own icon at the top IS the Home button (everything view), then Search, then the apps
        item(a.getDrawable(if (a.hub.touch) R.drawable.icon_bare else R.drawable.icon), "Home", HOME, selected = current == null && !a.inSearch) { a.pickApp(null) }
        item(a.getDrawable(R.drawable.ic_search), "Search", SEARCH, tint = true) { a.searchBar.open() }
        // tablets: your app icons are the top of Home; this jumps there, the way a provider's icon shows its content
        if (a.hub.touch) item(a.getDrawable(R.drawable.ic_apps), "Apps", "$HOME:apps", tint = true) { a.showApps() }
        apps.forEach { app ->
            lateinit var v: FrameLayout
            v = item(null, app.label, app.key, selected = app.filter == current, onLongClick = {
                MoveMode.holdMenu(a, v, beside = true, listOf("Open" to { a.pickApp(app.filter) }) + a.moveMode.options(v, rail, horizontal = false, host = v) +
                    listOfNotNull(app.pkg?.let { "Change icon" to { IconPicker.forApp(a, app) { dirty = true; a.loadHome() } ?: Unit } },
                        app.pkg?.let { "Rename" to { a.apps.rename(a, app) { dirty = true; a.refreshApps() } } }))
            }) { a.pickApp(app.filter) }
            val icon = v.getChildAt(1) as ImageView
            app.logo?.let { a.apps.logoInto(it, icon) }
            app.pkg?.let { a.apps.iconInto(it, icon) }
        }
        rail.addView(View(a), LinearLayout.LayoutParams(0, a.dp(8), 1f))
        item(a.getDrawable(R.drawable.ic_rows), "Rows", "$HOME:rows", tint = true) { hide(); a.chooseRows() }
        // tablet: pen and paper, so it isn't confused with the corner gear (the tablet's own settings)
        item(a.getDrawable(if (a.hub.touch) R.drawable.ic_edit_note else R.drawable.ic_settings), "Settings", "$HOME:settings", tint = true) { hide(); a.actions.settings() }
    }

    fun show() {
        if (dirty) { build(); dirty = false }
        if (isOpen) return
        panel.visibility = View.VISIBLE
        dim.visibility = View.VISIBLE
        panel.translationX = -(panel.width.takeIf { it > 0 } ?: a.dp(RAIL_DP)).toFloat()
        panel.animate().translationX(0f).setDuration(160).start()
    }

    fun hide() { panel.visibility = View.GONE; dim.visibility = View.GONE }

    /** Slide in, focused on the entry for what's on screen (or on [on]). */
    fun focus(on: String? = null) {
        show()
        val key = on ?: a.apps.sidebar().firstOrNull { it.filter != null && it.filter == a.appFilter }?.key ?: HOME
        rail.post { (rail.findViewWithTag<View>(key) ?: rail.findViewWithTag(HOME))?.requestFocus() }
    }

    fun anchor(tag: String): View? = rail.findViewWithTag(tag)

    /**
     * Icon-only entry. Apps get Google-TV-style rounded square icons; tinted vector icons for Search/Home/Rows/Settings.
     * Orange marker on the focused entry; focused icon grows. Long-press = reorder (apps only).
     */
    private fun item(
        icon: Drawable?, label: String, key: String, tint: Boolean = false, selected: Boolean = false,
        onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
    ): FrameLayout {
        val marker = View(a).apply {
            background = GradientDrawable().apply { cornerRadius = a.dp(2).toFloat(); setColor(ACCENT) }
            visibility = View.INVISIBLE // follows focus (see the focus listener below)
        }
        val img = ImageView(a).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (tint) setColorFilter(if (selected) Color.WHITE else 0xFFB3B3B3.toInt())
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            // pack icons and Joint Launcher's own on the tablet stand alone (like the Apps grid); app icons keep Google TV's rounded tile
            background = if (tint || (a.hub.touch && (key == HOME || a.apps.packs.active() != null))) null else GradientDrawable().apply { cornerRadius = a.dp(12).toFloat(); setColor(0xFF2A2B30.toInt()) }
            alpha = if (selected) 1f else 0.75f
        }
        val item = FrameLayout(a).apply {
            contentDescription = label // icon-only, so screen readers still get a name
            tag = key
            isFocusable = true; isClickable = true
            onLongClick?.let { cb -> setOnLongClickListener { cb(); true } }
            // full-width square band, edge to edge of the panel (no rounded corners)
            background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(0x33FFFFFF)) }
            addView(marker, FrameLayout.LayoutParams(a.dp(4), -1, Gravity.START)) // full height of the item
            addView(img, FrameLayout.LayoutParams(a.dp(if (tint) 28 else 40), a.dp(if (tint) 28 else 40), Gravity.CENTER))
            setOnFocusChangeListener { _, f ->
                marker.visibility = if (f) View.VISIBLE else View.INVISIBLE
                img.animate().scaleX(if (f) 1.18f else 1f).scaleY(if (f) 1.18f else 1f).alpha(if (f || selected) 1f else 0.75f).setDuration(120).start()
                if (tint) img.setColorFilter(if (f || selected) Color.WHITE else 0xFFB3B3B3.toInt())
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN) { a.backToContent(); true } else false
            }
        }
        rail.addView(item, LinearLayout.LayoutParams(-1, a.dp(if (tint) 46 else 52)).apply { bottomMargin = a.dp(2) })
        return item
    }

    companion object {
        const val HOME = "rail:home"
        const val SEARCH = "rail:home:search"
    }
}
