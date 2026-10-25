package com.mau.hub

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Collator

/**
 * The phone's Home, in the way of Pro Launcher: the flip clock and weather on top, your Home apps as a centred list
 * (A to Z, text or LineX icons), a row of shortcuts at the bottom. Swipe up for every app, sorted into your categories;
 * the widget page and Beeper are a swipe right and left away (see [Gestures]). Hold empty space for Settings.
 */
class PhoneHome(private val a: MainActivity) {
    private val p get() = a.hub.prefs
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    // the shortcuts: up to 5 pages side by side; swipe the row sideways for the next one (it snaps to a page)
    private val dock = LinearLayout(a)
    private val dockScroll = object : HorizontalScrollView(a) {
        private var flung = false
        override fun fling(velocityX: Int) { flung = true; snap(page() + if (velocityX > 0) 1 else -1) }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            flung = false
            val handled = super.onTouchEvent(e) // a quick swipe calls fling() from in here
            if ((e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) && !flung) snap(page())
            return handled
        }
        fun page() = (scrollX + width / 2) / maxOf(1, width)
        fun snap(p: Int) {
            val target = p.coerceIn(0, maxOf(0, dock.childCount - 1))
            smoothScrollTo(target * width, 0)
        }
    }.apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(dock) }
    private val date = a.text(15f, TEXT_SOFT).apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt()) }
    private val clock = FlipClock(a, 144) // big: two cards fill most of the phone's width
    private val iconsButton = a.iconButton(R.drawable.ic_apps, "Icons on Home") { icons = !icons; render() }.apply {
        setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11)); background = a.bareButton()
    }
    val drawer = AllApps(a)
    val nowPanel = NowPanel(a)
    val weatherPanel = WeatherPanel(a)
    private val musicButton: ImageView = a.iconButton(R.drawable.ic_ha_music, "Now playing") { nowPanel.open(from = musicButton) }.apply {
        setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11)); background = a.bareButton()
    }

    val view: View = FrameLayout(a).apply {
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            // top: the big flip clock in the middle, the date and weather under it; each opens its app (hold: pick another)
            addView(FrameLayout(a).apply {
                addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                // top left, in the clock's margin: names/icons (like the tablet's and TV's button), and under it the music
                // note that pops out Now Playing
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(iconsButton, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                    addView(musicButton, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = a.dp(2) })
                // right, in the clock's other margin: the weather; a tap pops out the forecast (hold still picks the weather app)
                a.topBar.takeWeather()?.let { w ->
                    (w as? TextView)?.apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) } // icon over the degrees
                    addView(w, FrameLayout.LayoutParams(a.dp(52), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = a.dp(2) })
                    w.setOnClickListener { weatherPanel.open(from = w) }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(18) })
            addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            addView(android.widget.ScrollView(a).apply {
                isVerticalScrollBarEnabled = false; isFillViewport = true
                addView(FrameLayout(a).apply {
                    addView(list, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    setOnLongClickListener { a.actions.settings(); true } // hold empty space: Settings, as in Pro Launcher
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dockScroll, LinearLayout.LayoutParams(-1, a.dp(56)).apply { bottomMargin = a.dp(10) })
        }, FrameLayout.LayoutParams(-1, -1))
        addView(nowPanel.view, FrameLayout.LayoutParams(-1, -1))
        addView(weatherPanel.view, FrameLayout.LayoutParams(-1, -1))
        addView(drawer.view, FrameLayout.LayoutParams(-1, -1))
    }

    init {
        nowPanel.coverTo = date; weatherPanel.coverTo = date
        a.topBar.opensApp(clock, "clock", "clock", listOf("clock", "reloj"))
        a.topBar.opensApp(date, "calendar", "date", listOf("calendar", "calendario", "agenda"))
    }

    var icons: Boolean
        get() = p.getBoolean("phone_icons", false)
        set(v) = p.edit().putBoolean("phone_icons", v).apply()

    /** Home: "pkg" or "cat:<category>", one per line. */
    fun home(): List<String> = p.getString("phone_home", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun setHome(keys: List<String>) = p.edit().putString("phone_home", keys.distinct().joinToString("\n")).apply()
    fun dockApps(): List<String> = (0 until DOCK_PAGES).flatMap(::dockPage)
    /** Shortcut page i (0-4), left to right. Page 0 keeps the name the Pro Launcher import writes. */
    fun dockPage(i: Int): List<String> = p.getString(dockKey(i), "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun setDockPage(i: Int, keys: List<String>) = p.edit().putString(dockKey(i), keys.distinct().joinToString("\n")).apply()
    private fun dockKey(i: Int) = if (i == 0) "phone_dock" else "phone_dock_$i"
    fun removeFromDock(pkg: String) = (0 until DOCK_PAGES).forEach { i -> dockPage(i).let { if (pkg in it) setDockPage(i, it - pkg) } }

    /** Asks which shortcut page to put an app on (the ones in use, plus the next empty one). */
    fun addToDock(pkg: String) {
        val used = (0 until DOCK_PAGES).count { dockPage(it).isNotEmpty() }
        val pages = (0 until minOf(DOCK_PAGES, used + 1)).toList()
        android.app.AlertDialog.Builder(a).setTitle("Add to which shortcut page?")
            .setItems(pages.map { i -> "Page ${i + 1}" + dockPage(i).size.let { if (it == 0) " (new)" else " · $it apps" } }.toTypedArray()) { _, w ->
                removeFromDock(pkg); setDockPage(pages[w], dockPage(pages[w]) + pkg); render()
            }.show()
    }

    fun render() {
        // the button shows what a tap switches to
        iconsButton.setImageResource(if (icons) R.drawable.ic_text_fields else R.drawable.ic_apps)
        iconsButton.contentDescription = if (icons) "Names only on Home" else "Icons on Home"
        val cal = java.util.Calendar.getInstance()
        date.text = android.text.format.DateFormat.format("EEE d MMM", cal).toString().replaceFirstChar { it.uppercase() }
        clock.timeZone = p.getString("clock_tz", null)
        a.hub.diskPool.execute {
            val byKey = a.apps.all().associateBy { it.key }
            // A to Z by the name shown, as in Pro Launcher (the # category comes first by itself)
            val entries = home().mapNotNull { k ->
                if (k.startsWith("cat:")) k to k.removePrefix("cat:") else byKey[k]?.let { k to it.label }
            }.sortedWith(compareBy(Collator.getInstance()) { it.second })
            // pages up to the last one with apps (at least one); an emptied page in between stays, so the others don't move
            // the pages in use, plus one empty page to swipe to (5 at most): the row always slides sideways, so a swipe on it
            // changes shortcut page and never Home's (widgets, Beeper)
            val pages = (0 until DOCK_PAGES).map { i -> dockPage(i).filter { it in byKey } }.let { all -> all.take(minOf(DOCK_PAGES, all.indexOfLast { it.isNotEmpty() } + 2)) }
            a.hub.main.post { fill(entries, byKey); fillDock(pages) }
        }
    }

    private fun fill(entries: List<Pair<String, String>>, byKey: Map<String, SideApp>) {
        list.removeAllViews()
        if (entries.isEmpty()) list.addView(a.text(15f, TEXT_SOFT).apply {
            gravity = Gravity.CENTER; setPadding(a.dp(32), a.dp(24), a.dp(32), 0)
            text = "Swipe up for your apps; hold one and choose “Add to Home”. Or Settings (hold empty space) → Import from Pro Launcher."
        })
        entries.forEach { (key, label) ->
            val app = byKey[key]
            list.addView(entry(label, if (icons) app?.pkg else null, category = key.startsWith("cat:")).apply {
                setOnClickListener { if (app != null) a.apps.launch(key) else drawer.open(key.removePrefix("cat:")) }
                setOnLongClickListener {
                    MoveMode.holdMenu(a, this, beside = false, listOfNotNull(
                        app?.let { "Rename" to { a.apps.rename(a, it) { render() } } },
                        app?.let { "Change icon" to { IconPicker.forApp(a, it) { render(); drawer.refresh() } ?: Unit } },
                        "Remove from Home" to { setHome(home() - key); render() },
                        app?.pkg?.let { pkg -> "App info" to { appInfo(pkg) } },
                    )); true
                }
            })
        }
    }

    /** One line on Home: the name, or in icon mode the app's LineX icon instead of it (categories keep their name). */
    private fun entry(label: String, iconPkg: String?, category: Boolean) = LinearLayout(a).apply {
        gravity = Gravity.CENTER // the row is as wide as the screen: centre the name (or icon) in it
        setPadding(a.dp(20), a.dp(6), a.dp(20), a.dp(6))
        background = a.bareButton(); isClickable = true; contentDescription = label // TalkBack still says the name
        if (iconPkg != null) { addView(ImageView(a).apply { a.apps.iconInto(iconPkg, this) }, LinearLayout.LayoutParams(a.dp(40), a.dp(40))); return@apply }
        addView(a.text(if (category) 22f else 21f, Color.WHITE).apply {
            text = label; letterSpacing = 0.03f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(10f, 0f, 1f, 0xCC000000.toInt())
        })
    }

    private fun fillDock(pages: List<List<String>>) {
        dock.removeAllViews()
        val w = dockScroll.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
        pages.forEachIndexed { i, keys ->
            dock.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER; setPadding(a.dp(12), 0, a.dp(12), 0)
                keys.forEach { pkg -> addView(dockIcon(i, pkg), LinearLayout.LayoutParams(0, a.dp(52), 1f)) }
                // an empty page: + opens the app list, where holding an app offers "Add to the shortcuts"
                if (keys.isEmpty()) addView(a.text(26f, TEXT_SOFT).apply {
                    text = "+"; gravity = Gravity.CENTER; background = a.bareButton(); contentDescription = "Add shortcuts to page ${i + 1}"
                    setOnClickListener { drawer.open(); a.toast("Hold an app → Add to the shortcuts → Page ${i + 1}") }
                }, LinearLayout.LayoutParams(a.dp(52), a.dp(52)))
            }, LinearLayout.LayoutParams(w, -1))
        }
        val now = dockScroll.page().coerceIn(0, pages.size - 1)
        dockScroll.post { dockScroll.scrollTo(now * dockScroll.width, 0) }
    }

    private fun dockIcon(page: Int, pkg: String) = ImageView(a).apply {
        a.apps.iconInto(pkg, this)
        setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        background = a.bareButton()
        setOnClickListener { a.apps.launch(pkg) }
        setOnLongClickListener {
            fun move(by: Int) = setDockPage(page, dockPage(page).toMutableList().apply {
                val i = indexOf(pkg); if (i + by in indices) { removeAt(i); add(i + by, pkg) }
            })
            MoveMode.holdMenu(a, this, beside = false, listOf(
                "Move left" to { move(-1); render() },
                "Move right" to { move(1); render() },
                "Move to another page" to { addToDock(pkg) },
                "Remove from the shortcuts" to { removeFromDock(pkg); render() },
            )); true
        }
    }

    companion object { const val DOCK_PAGES = 5 }

    fun appInfo(pkg: String) {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
    }

    // ---- categories (from Pro Launcher; yours to change from an app's menu) ----

    fun categories(): List<String> = p.getString("phone_categories", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun category(pkg: String): String? = p.getString("app_cat_$pkg", null)
    fun setCategory(pkg: String, cat: String?) = p.edit().apply {
        if (cat == null) remove("app_cat_$pkg") else putString("app_cat_$pkg", cat)
        if (cat != null && cat !in categories()) putString("phone_categories", (categories() + cat).joinToString("\n"))
    }.apply()
}

/** Every app, slid up from Home: your categories as icons across the top, a search box, the list A to Z. */
class AllApps(private val a: MainActivity) {
    private val home get() = a.phoneHome
    private var category: String? = null // null = all
    private var all: List<SideApp> = emptyList()
    private val chips = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(12), 0, a.dp(12), 0) }
    private val adapter = AppList()
    private val search = EditText(a).apply {
        hint = "Search apps"; isSingleLine = true; textSize = 17f
        setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED)
        background = GradientDrawable().apply { cornerRadius = a.dp(24).toFloat(); setColor(0x26FFFFFF) }
        setPadding(a.dp(20), 0, a.dp(20), 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        // Go opens the first match
        setOnEditorActionListener { _, _, _ -> adapter.shown.firstOrNull()?.let(::launch); true }
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(t: Editable?) {
                show()
                // only one app left: open it straight away, like Olauncher / Pro Launcher
                if (!t.isNullOrBlank() && adapter.shown.size == 1) launch(adapter.shown[0])
            }
            override fun beforeTextChanged(t: CharSequence?, s: Int, c: Int, n: Int) = Unit
            override fun onTextChanged(t: CharSequence?, s: Int, b: Int, c: Int) = Unit
        })
    }
    private val recycler = RecyclerView(a).apply {
        layoutManager = LinearLayoutManager(a); adapter = this@AllApps.adapter
        isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, a.dp(4), 0, a.dp(24))
    }

    val view: View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true // touches stay in the drawer, not on Home under it
        addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, a.dp(60)).apply { topMargin = a.dp(12) })
        addView(search, LinearLayout.LayoutParams(-1, a.dp(48)).apply { setMargins(a.dp(20), a.dp(8), a.dp(20), a.dp(8)) })
        addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        visibility = View.GONE
    }
    val isOpen get() = view.visibility == View.VISIBLE

    fun open(cat: String? = null, typing: Boolean = false) {
        category = cat
        search.setText("")
        refresh()
        if (isOpen) return
        // see-through as much as Settings says (0 = clear, the wallpaper shows; 100 = black)
        view.setBackgroundColor(android.graphics.Color.argb(a.hub.prefs.getInt("drawer_dim", 40) * 255 / 100, 5, 5, 5))
        view.visibility = View.VISIBLE
        view.translationY = a.window.decorView.height * 0.3f; view.alpha = 0f
        view.animate().translationY(0f).alpha(1f).setDuration(200).start()
        homeContent.animate().alpha(0f).setDuration(200).start() // only the wallpaper behind the list, not Home's names too
        recycler.scrollToPosition(0)
        if (typing || a.hub.prefs.getBoolean("phone_keyboard", false)) { search.requestFocus(); keyboard(true) }
    }

    fun close() {
        if (!isOpen) return
        keyboard(false); search.clearFocus()
        view.animate().translationY(a.window.decorView.height * 0.3f).alpha(0f).setDuration(180).withEndAction { view.visibility = View.GONE }.start()
        homeContent.animate().alpha(1f).setDuration(180).start()
    }

    private val homeContent get() = (home.view as ViewGroup).getChildAt(0) // Home's clock, list and shortcuts, under this list

    /** Is the list at its top (so a swipe down closes the drawer instead of scrolling)? */
    fun atTop() = !recycler.canScrollVertically(-1)

    fun refresh() = a.hub.diskPool.execute {
        val hidden = a.apps.hidden()
        val apps = a.apps.all().filter { it.key !in hidden }.sortedWith(compareBy(Collator.getInstance()) { it.label })
        a.hub.main.post { all = apps; drawChips(); show() }
    }

    private fun drawChips() {
        chips.removeAllViews()
        val used = all.mapNotNull { home.category(it.key) }.toSet()
        fun chip(cat: String?, v: View) = chips.addView(v.apply {
            setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6)); isClickable = true
            background = if (cat == category) GradientDrawable().apply { cornerRadius = a.dp(20).toFloat(); setStroke(a.dp(2), ACCENT); setColor(0x1AFFFFFF) } else a.bareButton()
            contentDescription = cat ?: "All apps"
            setOnClickListener { category = cat; search.setText(""); drawChips(); show(); recycler.scrollToPosition(0) }
        }, LinearLayout.LayoutParams(-2, a.dp(48)).apply { marginEnd = a.dp(4) })
        chip(null, ImageView(a).apply { setImageResource(R.drawable.ic_apps); setColorFilter(Color.WHITE); minimumWidth = a.dp(48) })
        home.categories().filter { it in used }.forEach { c ->
            chip(c, a.text(24f, Color.WHITE).apply { text = c.trim(); gravity = Gravity.CENTER; minWidth = a.dp(48) })
        }
    }

    private fun show() {
        val q = search.text.toString()
        adapter.shown = if (q.isNotBlank()) Apps.matches(all, q) // searching looks through every app, whatever the category
            else category?.let { c -> all.filter { home.category(it.key) == c } } ?: all
        adapter.notifyDataSetChanged()
    }

    private fun launch(app: SideApp) { app.pkg?.let(a.apps::launch); close() }

    private fun keyboard(on: Boolean) {
        val imm = a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) search.post { imm.showSoftInput(search, 0) } else imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun menu(anchor: View, app: SideApp) {
        val pkg = app.pkg ?: return
        val onHome = pkg in home.home()
        MoveMode.holdMenu(a, anchor, beside = false, listOf(
            (if (onHome) "Remove from Home" else "Add to Home") to { home.setHome(if (onHome) home.home() - pkg else home.home() + pkg); home.render() },
            (if (pkg in home.dockApps()) "Remove from the shortcuts" else "Add to the shortcuts") to {
                if (pkg in home.dockApps()) { home.removeFromDock(pkg); home.render() } else home.addToDock(pkg)
            },
            "Category" to { pickCategory(app) },
            "Rename" to { a.apps.rename(a, app) { refresh(); home.render() } },
            "Change icon" to { IconPicker.forApp(a, app) { refresh(); home.render() } ?: Unit },
            "Hide" to { a.apps.setHidden(pkg, true); refresh(); a.toast("${app.label} hidden · Settings → Hidden apps to bring it back") },
            "App info" to { home.appInfo(pkg) },
            "Uninstall" to { runCatching { a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }; Unit },
        ))
    }

    private fun pickCategory(app: SideApp) {
        val cats = home.categories()
        val now = home.category(app.key)
        android.app.AlertDialog.Builder(a).setTitle("Category of ${app.label}")
            .setItems((cats.map { if (it == now) "$it  ✓" else it } + "New category…" + "None").toTypedArray()) { _, i ->
                when (i) {
                    in cats.indices -> { home.setCategory(app.key, cats[i]); refresh() }
                    cats.size -> {
                        val input = EditText(a).apply { hint = "An emoji or a word"; isSingleLine = true }
                        android.app.AlertDialog.Builder(a).setTitle("New category").setView(input)
                            .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { home.setCategory(app.key, it); refresh() } }
                            .setNegativeButton("Cancel", null).show()
                    }
                    else -> { home.setCategory(app.key, null); refresh() }
                }
            }.show()
    }

    private inner class Holder(v: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(v)

    private inner class AppList : RecyclerView.Adapter<Holder>() {
        var shown: List<SideApp> = emptyList()
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val icon = ImageView(a)
            val label = a.text(19f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL); letterSpacing = 0.02f
                setShadowLayer(10f, 0f, 1f, 0xCC000000.toInt()) // readable over the wallpaper when the list is see-through
            }
            val row = LinearLayout(a).apply {
                gravity = Gravity.CENTER; setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(10)) // centred, like Home
                background = a.bareButton(); isClickable = true; isLongClickable = true
                addView(icon, LinearLayout.LayoutParams(a.dp(30), a.dp(30)).apply { marginEnd = a.dp(16) })
                addView(label)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            return Holder(row, icon, label)
        }
        override fun onBindViewHolder(h: Holder, i: Int) {
            val app = shown[i]
            h.label.text = app.label
            val icons = a.hub.prefs.getBoolean("drawer_icons", false) // the app list's own choice; Home's button doesn't change it
            h.icon.visibility = if (icons) View.VISIBLE else View.GONE
            h.icon.setImageDrawable(null)
            if (icons) app.pkg?.let { a.apps.iconInto(it, h.icon) }
            h.itemView.setOnClickListener { launch(app) }
            h.itemView.setOnLongClickListener { menu(it, app); true }
        }
    }
}

/**
 * Settings → Import from Pro Launcher: its backup (the .zip from Pro Launcher's Settings → Backup) brings your Home
 * list, the names you gave apps, their categories, hidden apps, the bottom shortcuts and the swipe-left app.
 * Only these are read; nothing else in the backup (purchases, usage) is kept.
 */
object ProLauncherImport {
    /** What the backup says, ready to save; null if it isn't a Pro Launcher backup. */
    class Result(
        val home: List<String>, val names: Map<String, String>, val cats: Map<String, String>, val catOrder: List<String>,
        val hidden: Set<String>, val dock: List<String>, val swipeLeft: String?,
    )

    // Pro Launcher's own word categories from older versions, folded into your emoji ones
    private val OLD_CATEGORIES = mapOf("Productivity" to "⚙️", "Social" to "🍥", "Music" to "🎵", "Game" to "🎮")

    fun read(zip: ByteArray): Result? {
        val files = HashMap<String, String>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name in setOf("apps.json", "shared_prefs.json", "datastore.json")) files[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        val apps = files["apps.json"]?.let { org.json.JSONArray(it) } ?: return null
        val prefs = files["shared_prefs.json"]?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
        val store = files["datastore.json"]?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
        fun arr(o: org.json.JSONObject, k: String) = o.opt(k).let { v -> (v as? org.json.JSONArray) ?: (v as? String)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } }
        fun obj(o: org.json.JSONObject, k: String) = o.opt(k).let { v -> (v as? org.json.JSONObject) ?: (v as? String)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } }

        val names = HashMap<String, String>(); val cats = HashMap<String, String>(); val hidden = HashSet<String>()
        fun takeApp(m: org.json.JSONObject) {
            if (m.optString("appType", "app_model") != "app_model") return // web shortcuts: not apps
            val pkg = m.optString("packageName").takeIf { it.isNotEmpty() } ?: return
            (m.optString("homeRename").takeIf { it.isNotBlank() } ?: m.optString("labelRename").takeIf { it.isNotBlank() })?.let { names[pkg] = it.trim() }
            m.optString("appCategory").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { cats[pkg] = OLD_CATEGORIES[it] ?: it }
            if (m.optBoolean("hidden")) hidden += pkg
        }
        for (i in 0 until apps.length()) apps.optJSONObject(i)?.let(::takeApp)

        // Home: apps and categories, in Pro Launcher's order (Joint Launcher shows them A to Z anyway)
        val home = arr(prefs, "HOME_ITEMS")?.let { h ->
            (0 until h.length()).mapNotNull { h.optJSONObject(it) }.sortedBy { it.optInt("homePos") }.mapNotNull { item ->
                item.optJSONObject("appModel")?.let { m -> takeApp(m); m.optString("packageName").takeIf { it.isNotEmpty() } }
                    ?: item.optJSONObject("categoryInfoModel")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { "cat:$it" }
            }
        }.orEmpty()
        // its own category order, without its built-in "All apps" and "Uncategorized"
        val catOrder = arr(prefs, "APP_CATEGORIES")?.let { c -> (0 until c.length()).map { c.optString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() && it != "All apps" && it != "Uncategorized" }
        // the bottom shortcuts it shows, left to right (its list runs right to left)
        val dock = arr(store, "HOME_ACTIONS")?.let { d ->
            (0 until d.length()).mapNotNull { d.optJSONObject(it) }.filter { it.optBoolean("show") }
                .mapNotNull { it.optJSONObject("appModel")?.optString("packageName")?.takeIf(String::isNotEmpty) }.reversed()
        }.orEmpty()
        val swipeLeft = obj(store, "SWIPE_LEFT_APP")?.optString("packageName")?.takeIf { it.isNotEmpty() && store.optString("SWIPE_LEFT") == "OPEN_APP" }
        return Result(home, names, cats.mapValues { it.value }, catOrder, hidden, dock, swipeLeft)
    }

    /** Saves it: Home, names, categories, hidden apps and shortcuts are replaced by the backup's. */
    fun apply(a: MainActivity, r: Result) {
        val e = a.hub.prefs.edit()
        r.names.forEach { (pkg, name) -> e.putString("label_$pkg", name) }
        a.hub.prefs.all.keys.filter { it.startsWith("app_cat_") }.forEach(e::remove)
        r.cats.forEach { (pkg, c) -> e.putString("app_cat_$pkg", c) }
        // categories it uses that aren't in its list (left over from older versions) go at the end
        e.putString("phone_categories", (r.catOrder + r.cats.values.distinct().filter { it !in r.catOrder }).joinToString("\n"))
        e.putStringSet("hidden_apps", a.apps.hidden() + r.hidden)
        e.putString("phone_home", r.home.joinToString("\n"))
        e.putString("phone_dock", r.dock.joinToString("\n"))
        r.swipeLeft?.let { e.putString(Gestures.SWIPE_LEFT, "app:$it") }
        e.apply()
        a.apps.forgetList()
    }
}

/**
 * A card over Home's whole top (clock, date, weather, the buttons) that pops out of the button you pressed and shrinks
 * back into it: tap beside it, swipe it away or Back. Now Playing comes out of the music note, the forecast out of the
 * weather.
 */
open class PopPanel(protected val a: MainActivity) {
    protected val panel = LinearLayout(a).apply {
        setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        background = GradientDrawable().apply { setColor(0xE6101012.toInt()); cornerRadius = a.dp(24).toFloat() }
        isClickable = true // taps on it stay in it
    }
    val view: View = FrameLayout(a).apply {
        setOnClickListener { close() } // a tap beside the panel puts it away
        addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = a.dp(8); rightMargin = a.dp(8); topMargin = a.dp(8) })
        visibility = View.GONE
    }
    val isOpen get() = view.visibility == View.VISIBLE
    /** The card reaches at least down to this view's bottom (the phone: Home's date), so the whole top is covered. */
    var coverTo: View? = null

    protected open fun update() = Unit

    /** Opens it growing out of [from]'s centre. */
    fun open(from: View? = null) {
        update()
        if (isOpen) return
        val inView = IntArray(2).also(view::getLocationOnScreen)
        val btn = IntArray(2).also { from?.getLocationOnScreen(it) }
        // as see-through as Settings says (the same for both cards)
        (panel.background as GradientDrawable).setColor(Color.argb(a.hub.prefs.getInt("card_dim", 90) * 255 / 100, 16, 16, 18))
        coverTo?.let { c -> panel.minimumHeight = IntArray(2).also(c::getLocationOnScreen)[1] + c.height - inView[1] }
        view.visibility = View.VISIBLE
        panel.pivotX = btn[0] - inView[0] - a.dp(8) + (from?.width ?: 0) / 2f
        panel.pivotY = btn[1] - inView[1] - a.dp(8) + (from?.height ?: 0) / 2f
        panel.scaleX = 0.1f; panel.scaleY = 0.1f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun close() {
        if (!isOpen) return
        panel.animate().scaleX(0.1f).scaleY(0.1f).alpha(0f).setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { view.visibility = View.GONE }.start()
    }
}

/**
 * The phone's Now Playing, out of the music note: what any app is playing (cover, title, artist, which app), with
 * previous / play-pause / next. Tap the cover to open the app. Reads media sessions, which Android only allows with
 * notification access.
 */
class NowPanel(a: MainActivity) : PopPanel(a) {
    private val art = ImageView(a).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = a.dp(16).toFloat(); setColor(0x22FFFFFF) }
    }
    private val title = a.text(18f, Color.WHITE).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val artist = a.text(14f, TEXT_SOFT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val source = a.text(12f, TEXT_MUTED)
    private val playPause = control("▶", "Play or pause") { c ->
        if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    private val controls = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        addView(control("⏮", "Previous") { it.transportControls.skipToPrevious() }, LinearLayout.LayoutParams(0, a.dp(48), 1f))
        addView(playPause, LinearLayout.LayoutParams(0, a.dp(48), 1f))
        addView(control("⏭", "Next") { it.transportControls.skipToNext() }, LinearLayout.LayoutParams(0, a.dp(48), 1f))
    }
    private val access = a.button("Allow notification access") {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    init {
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.addView(art, LinearLayout.LayoutParams(a.dp(132), a.dp(132)).apply { marginEnd = a.dp(16) })
        panel.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(2) })
            addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(2) })
            addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) })
            addView(access, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.setOnClickListener { a.nowPlaying.current?.let { c -> close(); a.nowPlaying.openPlayer(c) } } // the whole card opens what's playing
    }

    /** Fills it from the playing session; called on open and whenever playback changes. */
    public override fun update() {
        val c = a.nowPlaying.current
        val allowed = a.nowPlaying.allowed
        access.visibility = if (allowed) View.GONE else View.VISIBLE
        controls.visibility = if (c != null) View.VISIBLE else View.GONE
        val md = c?.metadata
        title.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title
            ?: if (allowed) "Nothing is playing" else "Joint Launcher needs notification access to see what's playing"
        artist.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle ?: ""
        source.text = c?.packageName?.let { pkg -> runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() } ?: ""
        val cover = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
        if (cover != null) art.setImageBitmap(cover)
        else art.setImageDrawable(c?.packageName?.let { runCatching { a.packageManager.getApplicationIcon(it) }.getOrNull() })
        playPause.text = if (c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) "⏸" else "▶"
    }

    private fun control(glyph: String, label: String, act: (android.media.session.MediaController) -> Unit) = a.text(30f, Color.WHITE).apply {
        text = glyph; gravity = Gravity.CENTER; contentDescription = label; isClickable = true
        setOnClickListener { a.nowPlaying.current?.let(act) }
    }
}

/**
 * The forecast, out of the weather on Home: now (feels like, humidity, wind, rain), today's high and low, sunrise and
 * sunset, the next 12 hours and the next 5 days. Open-Meteo, the place set in Settings (else found from the connection).
 * "Weather app" opens the app picked for the weather.
 */
class WeatherPanel(a: MainActivity) : PopPanel(a) {
    private val now = a.text(44f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val what = a.text(15f, Color.WHITE)
    private val facts = a.text(13f, TEXT_SOFT).apply { setLineSpacing(0f, 1.2f) }
    private val hours = LinearLayout(a)
    private val days = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val more: TextView = a.text(20f, TEXT_SOFT).apply {
        text = "▾"; gravity = Gravity.CENTER; isClickable = true; contentDescription = "Next days"
        setOnClickListener { showDays(days.visibility != View.VISIBLE) }
    }

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(now, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(16) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(what); addView(facts)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        panel.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(hours) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        panel.addView(days, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        // two steps: the card opens with now and the hours; the arrow drops the next days down (and folds them back)
        panel.addView(FrameLayout(a).apply {
            addView(more, FrameLayout.LayoutParams(a.dp(48), a.dp(36), Gravity.CENTER))
            addView(a.text(13f, TEXT_MUTED).apply {
                text = "Weather app ›"; isClickable = true
                setOnClickListener { close(); a.topBar.openApp("weather", "weather", listOf("weather", "clima", "tiempo")) } // asks which the first time
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4) })
    }

    private fun showDays(on: Boolean) {
        days.visibility = if (on) View.VISIBLE else View.GONE
        more.text = if (on) "▴" else "▾"; more.contentDescription = if (on) "Hide the next days" else "Next days"
    }

    override fun update() {
        showDays(false) // every open starts folded
        if (now.text.isNullOrEmpty()) what.text = "Loading the forecast…"
        val hub = a.hub
        hub.pool.execute {
            val w = runCatching {
                val lat = hub.prefs.getString("lat", null)?.toDouble(); val lon = hub.prefs.getString("lon", null)?.toDouble()
                val (la, lo) = if (lat != null && lon != null) lat to lon else Sources.locate()
                Sources.forecast(la, lo)
            }.getOrNull()
            hub.main.post { if (w != null) show(w) else what.text = "Can't get the forecast right now" }
        }
    }

    private fun show(w: Sources.Forecast) {
        now.text = "${w.temp}°"
        what.text = "${w.icon}  ${w.summary}"
        facts.text = "Feels ${w.feels}° · ↑${w.high}° ↓${w.low}°\nHumidity ${w.humidity}% · Wind ${w.wind} km/h · Rain ${w.rain}%\nSunrise ${w.sunrise} · Sunset ${w.sunset}"
        hours.removeAllViews()
        w.hours.forEach { h ->
            hours.addView(a.text(13f, Color.WHITE).apply {
                gravity = Gravity.CENTER; text = "${h.time}\n${h.icon}\n${h.temp}°" + if (h.rain > 0) "\n${h.rain}%" else ""
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(a.dp(52), -2))
        }
        days.removeAllViews()
        w.days.forEach { d ->
            days.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(3), 0, a.dp(3))
                addView(a.text(14f, Color.WHITE).apply { text = d.name }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.text(16f, Color.WHITE).apply { text = d.icon }, LinearLayout.LayoutParams(a.dp(36), -2))
                addView(a.text(13f, TEXT_SOFT).apply { text = if (d.rain > 0) "${d.rain}%" else ""; gravity = Gravity.END }, LinearLayout.LayoutParams(a.dp(44), -2))
                addView(a.text(14f, Color.WHITE).apply { text = "${d.high}° / ${d.low}°"; gravity = Gravity.END }, LinearLayout.LayoutParams(a.dp(84), -2))
            })
        }
    }
}
