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
    private val dock = LinearLayout(a).apply { gravity = Gravity.CENTER; setPadding(a.dp(12), 0, a.dp(12), a.dp(10)) }
    private val date = a.text(15f, TEXT_SOFT).apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt()) }
    private val clock = FlipClock(a, 72)
    val drawer = AllApps(a)

    val view: View = FrameLayout(a).apply {
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            // top: weather on the right, the flip clock in the middle, the date under it; each opens its app (hold: pick another)
            addView(FrameLayout(a).apply {
                addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                a.topBar.takeWeather()?.let { addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(18) })
            addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            addView(android.widget.ScrollView(a).apply {
                isVerticalScrollBarEnabled = false; isFillViewport = true
                addView(FrameLayout(a).apply {
                    addView(list, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    setOnLongClickListener { a.actions.settings(); true } // hold empty space: Settings, as in Pro Launcher
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dock, LinearLayout.LayoutParams(-1, a.dp(64)))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(drawer.view, FrameLayout.LayoutParams(-1, -1))
    }

    init {
        a.topBar.opensApp(clock, "clock", "clock", listOf("clock", "reloj"))
        a.topBar.opensApp(date, "calendar", "date", listOf("calendar", "calendario", "agenda"))
    }

    var icons: Boolean
        get() = p.getBoolean("phone_icons", false)
        set(v) = p.edit().putBoolean("phone_icons", v).apply()

    /** Home: "pkg" or "cat:<category>", one per line. */
    fun home(): List<String> = p.getString("phone_home", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun setHome(keys: List<String>) = p.edit().putString("phone_home", keys.distinct().joinToString("\n")).apply()
    fun dockApps(): List<String> = p.getString("phone_dock", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun setDock(keys: List<String>) = p.edit().putString("phone_dock", keys.distinct().joinToString("\n")).apply()

    fun render() {
        val cal = java.util.Calendar.getInstance()
        date.text = android.text.format.DateFormat.format("EEE d MMM", cal).toString().replaceFirstChar { it.uppercase() }
        clock.timeZone = p.getString("clock_tz", null)
        a.hub.diskPool.execute {
            val byKey = a.apps.all().associateBy { it.key }
            // A to Z by the name shown, as in Pro Launcher (the # category comes first by itself)
            val entries = home().mapNotNull { k ->
                if (k.startsWith("cat:")) k to k.removePrefix("cat:") else byKey[k]?.let { k to it.label }
            }.sortedWith(compareBy(Collator.getInstance()) { it.second })
            val shortcuts = dockApps().filter { it in byKey }
            a.hub.main.post { fill(entries, byKey); fillDock(shortcuts) }
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

    /** One line on Home: the name, with its LineX icon before it in icon mode. */
    private fun entry(label: String, iconPkg: String?, category: Boolean) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8))
        background = a.bareButton(); isClickable = true
        if (iconPkg != null) addView(ImageView(a).apply { a.apps.iconInto(iconPkg, this) },
            LinearLayout.LayoutParams(a.dp(30), a.dp(30)).apply { marginEnd = a.dp(12) })
        addView(a.text(if (category) 22f else 21f, Color.WHITE).apply {
            text = label; letterSpacing = 0.03f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(10f, 0f, 1f, 0xCC000000.toInt())
        })
    }

    private fun fillDock(keys: List<String>) {
        dock.removeAllViews()
        keys.forEach { pkg ->
            dock.addView(ImageView(a).apply {
                a.apps.iconInto(pkg, this)
                setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
                background = a.bareButton()
                setOnClickListener { a.apps.launch(pkg) }
                setOnLongClickListener {
                    MoveMode.holdMenu(a, this, beside = false, listOf(
                        "Move left" to { setDock(dockApps().toMutableList().apply { val i = indexOf(pkg); if (i > 0) { removeAt(i); add(i - 1, pkg) } }); render() },
                        "Move right" to { setDock(dockApps().toMutableList().apply { val i = indexOf(pkg); if (i in 0 until size - 1) { removeAt(i); add(i + 1, pkg) } }); render() },
                        "Remove from the shortcuts" to { setDock(dockApps() - pkg); render() },
                    )); true
                }
            }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
        }
    }

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
        setBackgroundColor(0xF0050505.toInt())
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
        view.visibility = View.VISIBLE
        view.translationY = a.window.decorView.height * 0.3f; view.alpha = 0f
        view.animate().translationY(0f).alpha(1f).setDuration(200).start()
        recycler.scrollToPosition(0)
        if (typing || a.hub.prefs.getBoolean("phone_keyboard", false)) { search.requestFocus(); keyboard(true) }
    }

    fun close() {
        if (!isOpen) return
        keyboard(false); search.clearFocus()
        view.animate().translationY(a.window.decorView.height * 0.3f).alpha(0f).setDuration(180).withEndAction { view.visibility = View.GONE }.start()
    }

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
                home.setDock(home.dockApps().let { if (pkg in it) it - pkg else it + pkg }); home.render()
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
            val label = a.text(19f, Color.WHITE).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); letterSpacing = 0.02f }
            val row = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(28), a.dp(10), a.dp(20), a.dp(10))
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
            h.icon.visibility = if (home.icons) View.VISIBLE else View.GONE
            h.icon.setImageDrawable(null)
            if (home.icons) app.pkg?.let { a.apps.iconInto(it, h.icon) }
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
