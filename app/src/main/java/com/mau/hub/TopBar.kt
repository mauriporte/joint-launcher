package com.mau.hub

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** Google TV-style top bar: search, content-type tabs, TV settings gear, then weather and clock on the right. */
class TopBar(private val a: MainActivity) {
    private val tabStrip = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
    // before the bar is built below: flipClock() fills clocks, init starts the weather timer
    private var clocks: List<android.widget.TextClock> = emptyList()
    private val tvClock = android.widget.TextClock(a) // the TV's clock; Settings' format and timezone apply to it too
    // tablet corner: the built-in clock, or a widget you picked for it (Settings → Clock), e.g. Flip Clock's real flip clock
    private val corner = android.widget.FrameLayout(a)
    // tablet: shows/hides Home's section titles (icons instead); also a switch in Settings
    private val titlesButton = a.iconButton(R.drawable.ic_text_fields, "Section titles") {
        a.hub.prefs.edit().putBoolean("section_titles", !a.hub.prefs.getBoolean("section_titles", true)).apply()
        applyTitlesButton(); a.loadHome()
    }
    private val weatherTimer = Runnable { refreshWeather() }
    private val weather = a.text(14f, TEXT_SOFT).apply { setPadding(0, 0, a.dp(16), 0) }
    private val search = a.iconButton(R.drawable.ic_search, "Search") { a.searchBar.open() }.apply {
        layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginEnd = a.dp(8) }
        setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7))
        id = View.generateViewId()
        background = iconBackground()
        // leftmost thing on screen, like a row's first tile: Left opens the sidebar
        setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) { a.sidebar.focus(); true } else false }
    }
    // little gear right after the tabs: the TV's (or tablet's) own Settings app; Joint Launcher's are in the sidebar
    private val gear = a.iconButton(R.drawable.ic_settings, if (a.hub.touch) "Tablet settings" else "TV settings") { a.actions.systemSettings() }.apply {
        layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginStart = a.dp(6) }
        setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7))
        id = View.generateViewId()
        background = iconBackground() // just the icon at rest; white circle only when focused
    }

    val view = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (a.hub.touch) setPadding(a.dp(17), 0, a.dp(19), 0) else setPadding(a.dp(30), 0, a.dp(36), 0) // tablet: menu icon and clock line up with the section icons and arrows below
        if (a.hub.touch) {
            // touchscreens have no Left key to slide the sidebar in: a menu button does it; tabs scroll in portrait
            // the menu button is Joint Launcher's own icon (in its colours, like the top of the sidebar it opens)
            addView(a.iconButton(R.drawable.icon_bare, "Menu") { a.sidebar.show() }.apply {
                touchSize(this); marginEnd(this, 8); background = a.bareButton(); setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
                clearColorFilter(); onFocusChangeListener = null
            })
            // padlock, between the menu icon and search, a little smaller: locks the desktop as it is (Home's section
            // headers, arrows and edit buttons go; see MainActivity.desktopLocked)
            val lock = a.iconButton(R.drawable.ic_lock_open, "Lock the desktop") {}.apply {
                touchSize(this); background = a.bareButton(); setPadding(a.dp(13), a.dp(13), a.dp(13), a.dp(13))
            }
            addView(lock)
            addView(search.also { touchSize(it) })
            // arrow: folds away everything after it (tabs, buttons, weather, clock); remembered
            val collapse = a.iconButton(R.drawable.ic_chevron, "Hide the top bar") {}.apply { touchSize(this); background = a.bareButton() }
            addView(collapse)
            val tabs = android.widget.HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabStrip)
            }
            addView(tabs, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(12) })
            // the corner, right to left: flip clock, weather, settings gear, then the button that shows/hides Home's section titles
            addView(titlesButton.also { touchSize(it); it.background = a.bareButton(); applyTitlesButton() })
            addView(gear.also { touchSize(it) })
            // same height as the clock; temperature first at half the size (the clock is what matters), then the icon
            addView(weather.apply {
                textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
                setPadding(a.dp(16), 0, a.dp(16), 0) // no card behind it: just the text over the background
            }, LinearLayout.LayoutParams(-2, a.dp(58)).apply { marginStart = a.dp(8); marginEnd = a.dp(12) })
            opensApp(weather, "weather", "Weather", listOf("weather", "clima", "tiempo"))
            addView(corner.apply { addView(flipClock().apply { opensApp(this, "clock", "Clock", listOf("clock", "alarm", "time")) }) })
            // three steps: 0 everything, 1 just the weather and clock (tabs and buttons folded away), 2 nothing
            val folding = (indexOfChild(tabs) until childCount).map(::getChildAt)
            val keepAtStep1 = setOf<View>(weather, getChildAt(childCount - 1)) // the weather and the flip clock
            fun step() = a.hub.prefs.getInt("topbar_fold", if (a.hub.prefs.getBoolean("topbar_folded", false)) 2 else 0)
            fun applyFold() {
                val s = step()
                folding.forEach { v -> v.visibility = if (s == 0 || (s == 1 && v in keepAtStep1)) View.VISIBLE else View.INVISIBLE } // INVISIBLE keeps the bar's height
                collapse.rotation = if (s == 2) 0f else 180f // ic_chevron points right: left = "fold more", right = "unfold"
                collapse.contentDescription = when (s) { 0 -> "Fold the top bar to the clock"; 1 -> "Hide the top bar"; else -> "Show the top bar" }
            }
            collapse.setOnClickListener { a.hub.prefs.edit().putInt("topbar_fold", (step() + 1) % 3).apply(); applyFold() }
            fun applyLock() {
                val locked = a.desktopLocked()
                lock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
                lock.contentDescription = if (locked) "Unlock the desktop" else "Lock the desktop"
                applyFold()
                collapse.visibility = if (locked) View.GONE else View.VISIBLE // the bar stays folded as it was
                if (locked) titlesButton.visibility = View.GONE // titles are gone while locked anyway
            }
            lock.setOnClickListener { a.hub.prefs.edit().putBoolean("desktop_locked", !a.desktopLocked()).apply(); applyLock(); a.loadHome() }
            applyLock()
        } else {
            // TV, left to right: Joint Launcher's icon (opens the sidebar), the padlock, search (the sidebar, on its Search entry)
            val menu = a.iconButton(R.drawable.icon_bare, "Menu") { a.sidebar.focus() }.apply {
                layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginEnd = a.dp(8) }
                setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4)); background = iconBackground()
                clearColorFilter(); onFocusChangeListener = null // keeps its own colours; the white circle shows focus
                setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) { a.sidebar.focus(); true } else false }
            }
            addView(menu)
            val lock = a.iconButton(R.drawable.ic_lock_open, "Lock the desktop") {}.apply {
                layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginEnd = a.dp(8) }
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8)); background = iconBackground()
            }
            fun applyLock() {
                val locked = a.desktopLocked()
                lock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
                lock.contentDescription = if (locked) "Unlock the desktop" else "Lock the desktop"
            }
            lock.setOnClickListener { a.hub.prefs.edit().putBoolean("desktop_locked", !a.desktopLocked()).apply(); applyLock(); a.loadHome() }
            applyLock()
            addView(lock)
            search.setOnKeyListener(null) // Left now goes to the padlock and the icon
            search.setOnClickListener { a.sidebar.focus(Sidebar.SEARCH) }
            addView(search)
            addView(tabStrip)
            addView(gear)
            addView(View(a), LinearLayout.LayoutParams(0, 0, 1f))
            addView(weather)
            addView(tvClock.apply {
                format24Hour = "HH:mm"; format12Hour = "h:mm"
                textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }

    /**
     * Tablet corner clock: big hours and minutes side by side, straight on the background (no cards).
     * TextClock keeps them on time by itself.
     */
    private fun flipClock() = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        fun card(format: String) = android.widget.FrameLayout(a).apply {
            addView(android.widget.TextClock(a).apply {
                format24Hour = format; format12Hour = format
                textSize = 34f; setTextColor(0xFFF2F2F2.toInt()); typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                includeFontPadding = false; gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        addView(card("HH"), LinearLayout.LayoutParams(a.dp(58), a.dp(58)))
        addView(card("mm"), LinearLayout.LayoutParams(a.dp(58), a.dp(58)).apply { marginStart = a.dp(6) })
        clocks = (0 until childCount).map { (getChildAt(it) as ViewGroup).getChildAt(0) as android.widget.TextClock }
        applyClock()
    }


    /**
     * Tablet corner: tapping the clock or the weather opens the app you pick for it (asked the first time, then
     * remembered); press and hold to pick another. Apps whose names suggest the job (a clock app, a weather app) come first.
     */
    private fun opensApp(v: View, key: String, what: String, hints: List<String>) {
        fun choose() = a.hub.diskPool.execute {
            val all = a.apps.all().sortedWith(compareBy(java.text.Collator.getInstance()) { it.label })
            val (likely, rest) = all.partition { app -> hints.any { h -> app.label.contains(h, true) || app.key.contains(h, true) } }
            val list = likely + rest
            a.hub.main.post {
                android.app.AlertDialog.Builder(a).setTitle("Open which app for the $what?")
                    .setItems(list.map { it.label }.toTypedArray()) { _, i ->
                        a.hub.prefs.edit().putString("opens_$key", list[i].key).apply(); list[i].pkg?.let(a.apps::launch)
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        v.isClickable = true
        v.background = a.bareButton()
        v.setOnClickListener {
            val pkg = a.hub.prefs.getString("opens_$key", null)?.takeIf(a.hub::installed)
            if (pkg != null) a.apps.launch(pkg) else choose()
        }
        v.setOnLongClickListener { choose(); true }
    }

    /** Shows the corner widget instead of the built-in clock when one is set (and its app still exists). */
    fun applyCornerClock() {
        if (!a.hub.touch || corner.childCount == 0) return
        val builtIn = corner.getChildAt(0)
        while (corner.childCount > 1) corner.removeViewAt(1)
        val w = a.widgets.cornerView(a.dp(220), a.dp(64))
        builtIn.visibility = if (w == null) View.VISIBLE else View.GONE
        w?.let { corner.addView(it, android.widget.FrameLayout.LayoutParams(a.dp(220), a.dp(64))) }
    }

    /** Bright while titles show, dimmed while they're off. */
    fun applyTitlesButton() {
        val on = a.hub.prefs.getBoolean("section_titles", true)
        titlesButton.alpha = if (on) 1f else 0.4f
        titlesButton.contentDescription = if (on) "Hide section titles" else "Show section titles"
    }

    /** Settings' time format (device / 24 / 12 hour) and timezone (device's unless picked) on the flip clock. */
    fun applyClock() {
        val f = a.hub.prefs.getString("clock_format", null)
        val tz = a.hub.prefs.getString("clock_tz", null)
        clocks.firstOrNull()?.apply { format24Hour = if (f == "12") "hh" else "HH"; format12Hour = if (f == "24") "HH" else "hh" }
        clocks.forEach { it.timeZone = tz }
        if (!a.hub.touch) tvClock.apply {
            format24Hour = if (f == "12") "h:mm a" else "HH:mm"; format12Hour = if (f == "24") "HH:mm" else "h:mm a"; timeZone = tz
        }
    }

    private var pendingTab: Tab? = null
    private val tabSwitch = Runnable { pendingTab?.let(a::selectTab) }

    init { refreshWeather(); if (!a.hub.touch) applyClock() }

    /** "⛅  17°" -> "17°  ⛅" with the icon larger, for the tablet corner card. */
    private fun tabletWeather(text: String?): CharSequence {
        val icon = text?.substringBefore("  ")?.takeIf { "  " in text } ?: return text.orEmpty()
        val temp = text.substringAfter("  ")
        return android.text.SpannableString("$temp  $icon").apply {
            setSpan(android.text.style.RelativeSizeSpan(2.7f), length - icon.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

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


    /** Weather in the corner: the place set in Settings, else found once from the connection; refreshed every 30 minutes. */
    fun refreshWeather() {
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
            hub.main.post { weather.text = if (a.hub.touch) tabletWeather(text) else text.orEmpty() }
        }
        hub.main.removeCallbacks(weatherTimer) // Settings refreshes it right away; keep one timer
        hub.main.postDelayed(weatherTimer, 30 * 60_000L)
    }
}
