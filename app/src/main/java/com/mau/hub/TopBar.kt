package com.mau.hub

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout

/** Google TV-style top bar: search, content-type tabs, TV settings gear, then weather and clock on the right. */
class TopBar(private val a: MainActivity) {
    private val tabStrip = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
    private val weather = a.text(14f, TEXT_SOFT).apply { setPadding(0, 0, a.dp(16), 0) }
    private val search = a.iconButton(R.drawable.ic_search, "Search") { a.searchBar.open() }.apply {
        layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginEnd = a.dp(8) }
        setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7))
        id = View.generateViewId()
        background = iconBackground()
        // leftmost thing on screen, like a row's first tile: Left opens the sidebar
        setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) { a.sidebar.focus(); true } else false }
    }
    // little gear right after the tabs: the TV's own Settings app
    private val gear = a.iconButton(R.drawable.ic_settings, "TV settings") {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }.onFailure { a.toast("Couldn't open Settings") }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginStart = a.dp(6) }
        setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7))
        id = View.generateViewId()
        background = iconBackground() // just the icon at rest; white circle only when focused
    }

    val view = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(30), 0, a.dp(36), 0)
        if (a.hub.touch) {
            // touchscreens have no Left key to slide the sidebar in: a menu button does it; tabs scroll in portrait
            addView(a.iconButton(R.drawable.ic_rows, "Menu") { a.sidebar.show() }.apply { touchSize(this); marginEnd(this, 8) })
            addView(search.also { touchSize(it) })
            addView(android.widget.HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(tabStrip); addView(gear.also { touchSize(it) }) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(12) })
        } else {
            addView(search)
            addView(tabStrip)
            addView(gear)
            addView(View(a), LinearLayout.LayoutParams(0, 0, 1f))
        }
        addView(weather)
        addView(android.widget.TextClock(a).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
    }

    private var pendingTab: Tab? = null
    private val tabSwitch = Runnable { pendingTab?.let(a::selectTab) }

    init { refreshWeather() }

    /** Fingers need bigger targets than a remote's focus: 44dp buttons, 10dp inset icon. */
    private fun touchSize(v: View) {
        v.layoutParams = LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(4) }
        v.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
    }

    private fun marginEnd(v: View, d: Int) { (v.layoutParams as LinearLayout.LayoutParams).marginEnd = a.dp(d) }

    /** Top-bar icons: nothing behind them at rest, a white circle when focused (same as the tabs' pill). */
    private fun iconBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
    }

    /** selected = null while searching or in a single-app view (no tab is lit). Selected tab = white medium text. */
    fun tabs(shown: List<Tab>, selected: Tab?) {
        tabStrip.removeAllViews()
        shown.forEach { t ->
            val isSel = t == selected
            tabStrip.addView(a.text(14f, if (isSel) Color.WHITE else TEXT_MUTED).apply {
                text = t.label
                typeface = Typeface.create(if (isSel) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                val pv = a.dp(if (a.hub.touch) 12 else 5) // taller tap target on touchscreens
                setPadding(a.dp(12), pv, a.dp(12), pv)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = a.dp(16).toFloat(); setColor(Color.WHITE) })
                }
                isFocusable = true; isClickable = true; tag = t
                id = View.generateViewId()
                setOnFocusChangeListener { _, f ->
                    setTextColor(if (f) Color.BLACK else if (isSel) Color.WHITE else TEXT_MUTED)
                    // like Google TV: resting on a tab switches to it
                    a.hub.main.removeCallbacks(tabSwitch)
                    if (f && t != selected) { pendingTab = t; a.hub.main.postDelayed(tabSwitch, 350) }
                }
                setOnClickListener { a.selectTab(t) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(2) })
        }
        // the default focus search jumped from the search icon down into the rows; chain the bar explicitly
        val first = tabStrip.getChildAt(0) ?: return
        search.nextFocusRightId = first.id
        first.nextFocusLeftId = search.id
        val last = tabStrip.getChildAt(tabStrip.childCount - 1)
        last.nextFocusRightId = gear.id
        gear.nextFocusLeftId = last.id
    }

    fun focusTab(t: Tab) { tabStrip.findViewWithTag<View>(t)?.requestFocus() }

    /** Weather in the corner; location found once from the connection, temperature refreshed every 30 minutes. */
    private fun refreshWeather() {
        val hub = a.hub
        hub.pool.execute {
            val text = runCatching {
                val lat = hub.prefs.getString("lat", null)?.toDouble()
                val lon = hub.prefs.getString("lon", null)?.toDouble()
                val (la, lo) = if (lat != null && lon != null) lat to lon else Sources.locate().also { (x, y) ->
                    hub.prefs.edit().putString("lat", x.toString()).putString("lon", y.toString()).apply()
                }
                Sources.weather(la, lo)
            }.getOrNull()
            hub.main.post { weather.text = text.orEmpty() }
        }
        hub.main.postDelayed({ refreshWeather() }, 30 * 60_000L)
    }
}
