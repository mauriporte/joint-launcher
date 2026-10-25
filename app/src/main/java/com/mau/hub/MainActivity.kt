package com.mau.hub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The home screen. Its parts live in their own classes (top bar, sidebar, rows, detail screen, search, now playing,
 * move mode); this class lays them out, decides which rows to show, and handles Back and Home.
 */
class MainActivity : Activity() {
    val hub by lazy { Hub(this) }
    val images by lazy { Images(hub) }
    val apps by lazy { Apps(hub, images) }
    val catalog by lazy { Catalog(hub, apps) }
    val meta by lazy { Meta(hub) }
    val cards by lazy { Cards(this, images, apps, hub.touch) }
    val actions by lazy { Actions(this) }
    lateinit var rows: Rows
    lateinit var sidebar: Sidebar
    lateinit var topBar: TopBar
    lateinit var detail: DetailScreen
    lateinit var searchBar: SearchBar
    lateinit var moveMode: MoveMode
    private lateinit var nowPlaying: NowPlaying
    lateinit var page: LinearLayout // top bar + header + rows: blocked from focus while the detail screen covers it

    var gen = 0 // bumps on reload/search so stale async rows are dropped
    var inSearch = false
    var searchQuery = "" // search results start with the apps whose names match (a launcher's first job)
    var tab = Tab.HOME
    private var lastContentFocus: View? = null
    private var rowsDirty = false // the sidebar's app order changed: reorder the Home rows when it closes
    private var lastBackAt = 0L

    /** Selected single-app view (see [Apps.sidebar]), or null for everything. Persisted until changed. */
    var appFilter: String?
        get() = hub.prefs.getString("app_filter", null)
        set(v) = hub.prefs.edit().putString("app_filter", v).apply()

    // ---- the header above the rows: label, title, ratings and description of the focused title ----
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: android.widget.TextView
    private lateinit var heroKicker: android.widget.TextView
    private lateinit var heroMeta: LinearLayout
    private lateinit var heroDesc: android.widget.TextView
    private var pendingHero: Item? = null
    private val heroUpdate = Runnable { showHero(pendingHero) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        HomeButtonService.restore(this)
        heroImg = FadedImage(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroTitle = text(26f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.01f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
        }
        heroKicker = text(11f, ACCENT).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = 0.18f }
        heroMeta = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heroDesc = text(13f, TEXT_SOFT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        val width = resources.displayMetrics.widthPixels
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(4), dp(36), 0)
            addView(heroKicker)
            addView(heroTitle, LinearLayout.LayoutParams((width * 0.6).toInt(), -2))
            addView(heroMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(heroDesc, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = dp(8) })
        }

        searchBar = SearchBar(this)
        sidebar = Sidebar(this)
        topBar = TopBar(this)
        detail = DetailScreen(this)
        rows = Rows(this)
        moveMode = MoveMode(this, hub.touch) { container, order ->
            // the tablet's Apps are A to Z; only Favorites (heart on) keep an order of their own
            if (container !== sidebar.rail && apps.favouritesOnly) { apps.saveFavOrder(order); return@MoveMode } // Apps is A to Z; Favorites keep their order
            apps.saveOrder(order)
            if (container === sidebar.rail) rowsDirty = true // Home rows follow the sidebar: reorder them when it closes
            else sidebar.dirty = true // the sidebar picks the new order up next time it opens
        }
        nowPlaying = NowPlaying(this) { rows.nowPlaying(it) }

        // Layout budget (960x540dp): 46dp top bar + 136dp header + two ~178dp rows still fit without scrolling.
        // Touchscreens: no header (it follows the remote's focus, and a tap opens the title straight away), so more rows fit.
        val touch = hub.touch
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar.view, LinearLayout.LayoutParams(-1, dp(if (touch) 72 else 36)).apply { topMargin = dp(if (touch) 6 else 10) })
            if (!touch) addView(heroText, LinearLayout.LayoutParams(-1, dp(136)))
            addView(rows.view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(FrameLayout(this).apply {
            root = this
            setBackgroundColor(BG)
            if (!touch) {
                // the picture fades out at its left and bottom edges by itself (FadedImage), so it melts into whatever is
                // behind it (black or your background) instead of ending in a hard line
                addView(heroImg, FrameLayout.LayoutParams(-1, dp(360), Gravity.END).apply { leftMargin = dp(300) })
            }
            // Android 15 draws apps under the status and navigation bars (tablets, phones); keep clear of them. TVs have none.
            if (android.os.Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(page, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(20) })
            // tablets: touching or swiping from the left edge slides the sidebar in (no Left key on a touchscreen)
            if (touch) addView(View(this@MainActivity).apply { setOnTouchListener(edgeOpensSidebar()) }, FrameLayout.LayoutParams(dp(24), -1, Gravity.START))
            addView(sidebar.dim, FrameLayout.LayoutParams(-1, -1))
            // tablet: wide enough to cover the section icons on Home when it slides in
            addView(sidebar.panel, FrameLayout.LayoutParams(dp(if (touch) 104 else RAIL_DP + 8), -1))
            addView(detail.view)
        })
        // focus leaving the sidebar any other way (e.g. Up past its top) closes it too
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (!moveMode.active && sidebar.isOpen && now != null && !isInside(now, sidebar.rail)) sidebar.hide()
        }
        // disk cache for every HTTP GET that allows caching (all the poster/backdrop/logo CDNs do);
        // small, because this TV has 4 GB in total and other apps' caches already fill it up
        runCatching { android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 40L * 1024 * 1024) }
        applyBackground()
        loadHome()
        nowPlaying.start()
    }

    /** Tablet home-screen widgets (see [Widgets]). */
    val widgets by lazy { Widgets(this) }

    // widgets only update while the screen is showing
    override fun onStart() { super.onStart(); if (hub.touch) { widgets.start(); topBar.applyCornerClock() } }
    override fun onStop() { super.onStop(); if (hub.touch) widgets.stop() }

    /** A tap on the edge strip, or a swipe from it to the right, opens the sidebar; anything else passes. */
    private fun edgeOpensSidebar(): View.OnTouchListener {
        var downX = 0f
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; true }
                android.view.MotionEvent.ACTION_MOVE -> { if (e.rawX - downX > dp(16)) { sidebar.show(); v.cancelLongPress() }; true }
                android.view.MotionEvent.ACTION_UP -> { if (!sidebar.isOpen) sidebar.show(); v.performClick(); true }
                else -> true
            }
        }
    }

    private lateinit var root: FrameLayout

    // your own background pictures (Settings → Background): a gallery to switch between; each can have a portrait
    // version so a corner logo stays in its corner when the tablet turns
    private val bgImage by lazy { ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
    private val bgDir get() = java.io.File(filesDir, "backgrounds").apply { mkdirs() }
    fun bgFile(id: String, portrait: Boolean) = java.io.File(bgDir, if (portrait) "$id.portrait" else id)

    /** The gallery, oldest first (ids are when they were added). */
    fun backgrounds(): List<String> {
        // the first version kept one landscape + portrait pair: it becomes the first picture of the gallery
        java.io.File(filesDir, "bg_landscape").takeIf { it.exists() }?.let { it.renameTo(bgFile("1", false)); java.io.File(filesDir, "bg_portrait").renameTo(bgFile("1", true)) }
        // inbox (Android/data/<app>/files/inbox, reachable over USB/adb without any permission): pictures dropped there
        // join the gallery; "name.portrait.png" becomes the portrait version of "name.png"
        getExternalFilesDir("inbox")?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.takeIf { it.isNotEmpty() }?.let { files ->
            var next = System.currentTimeMillis()
            val ids = HashMap<String, String>()
            val (portraits, mains) = files.partition { it.nameWithoutExtension.endsWith(".portrait") }
            mains.forEach { f -> val id = (next++).toString(); if (runCatching { f.copyTo(bgFile(id, false), true) }.isSuccess) ids[f.nameWithoutExtension] = id; f.delete() }
            portraits.forEach { f -> ids[f.nameWithoutExtension.removeSuffix(".portrait")]?.let { runCatching { f.copyTo(bgFile(it, true), true) } }; f.delete() }
        }
        return bgDir.list().orEmpty().filter { !it.endsWith(".portrait") }.sortedBy { it.toLongOrNull() ?: 0L }
    }

    fun currentBackground(): String? = hub.prefs.getString("bg_current", null)?.takeIf { bgFile(it, false).exists() } ?: backgrounds().firstOrNull()
    fun useBackground(id: String) { hub.prefs.edit().putString("bg_current", id).putString("bg_mode", "picture").apply(); applyBackground() }
    fun removeBackground(id: String) { bgFile(id, false).delete(); bgFile(id, true).delete(); applyBackground() }

    /** "black", "wallpaper" (the tablet's) or "picture" (yours); older versions only had the wallpaper switch. */
    fun bgMode(): String = hub.prefs.getString("bg_mode", null) ?: if (hub.prefs.getBoolean("bg_wallpaper", false)) "wallpaper" else "black"

    /**
     * Tablet background: black (as on the TV), your wallpaper, or your own picture for the way the tablet is turned,
     * under a dark layer you can make lighter or darker (bg_dim 0-100 %). Set in Settings → Background.
     */
    fun applyBackground() {
        val mode = bgMode()
        val dim = Color.argb(hub.prefs.getInt("bg_dim", 60).coerceIn(0, 100) * 255 / 100, 5, 5, 5)
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val pic = if (mode == "picture") currentBackground()?.let { id -> bgFile(id, true).takeIf { portrait && it.exists() } ?: bgFile(id, false) } else null
        if (pic != null) {
            if (bgImage.parent == null) root.addView(bgImage, 0, FrameLayout.LayoutParams(-1, -1))
            bgImage.setImageBitmap(android.graphics.BitmapFactory.decodeFile(pic.path))
            bgImage.foreground = android.graphics.drawable.ColorDrawable(dim)
            bgImage.visibility = View.VISIBLE
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
            root.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        if (bgImage.parent != null) { bgImage.setImageDrawable(null); bgImage.visibility = View.GONE }
        if (mode == "wallpaper") {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            root.setBackgroundColor(dim)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
            root.setBackgroundColor(BG)
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    // ---- what's on screen ----

    fun loadHome() {
        gen++; inSearch = false
        rows.clear()
        sidebar.dirty = true // rebuilt when the sidebar is next opened, not at startup
        val f = appFilter
        val appView = f != null && apps.sidebar().any { it.filter == f }
        if (appView) tab = Tab.HOME
        topBar.tabs(catalog.tabs(), tab.takeIf { !appView })
        nowPlaying.render()
        val cap = if (appView || tab != Tab.HOME) 40 else 20 // app views and category tabs go deeper; Home stays light
        val cacheKey = f.takeIf { appView }.orEmpty()
        when {
            // one app only, with more of its content; stays until changed in the sidebar
            appView -> catalog.appRows(f!!, gen).forEach { rows.content(it.title, it.fetchers, it.top10, cap, cacheKey) }
            // a content-type tab: that type from every source
            tab != Tab.HOME -> {
                catalog.tabRows(tab, gen).forEach { rows.content(it.title, it.fetchers, it.top10, cap, cacheKey) }
                if (tab == Tab.APPS) rows.apps()
            }
            // Home, in the order picked in Settings: the tablet has widgets, your apps, To Watch (one button folds all the
            // video rows away) and Home Assistant; the TV has just your apps and To Watch (see TabletSettings.sections)
            else -> TabletSettings.sections(hub).forEach { s ->
                when (s) {
                    "widgets" -> rows.widgets()
                    "apps" -> rows.apps()
                    "ha" -> if (BuildConfig.HA_LOCAL.isNotEmpty() || BuildConfig.HA_REMOTE.isNotEmpty()) rows.homeAssistant()
                    else -> {
                        rows.connect(actions.connectEntries())
                        rows.fold()
                        catalog.homeRows(gen).filter(catalog::visible).forEach { rows.content(it.title, it.fetchers, it.top10, cap, cacheKey) }
                    }
                }
            }
        }
    }

    fun search(q: String) {
        gen++; inSearch = true; searchQuery = q
        rows.clear()
        rows.apps() // matching apps first; the row shows nothing when none match
        sidebar.dirty = true
        topBar.tabs(catalog.tabs(), null) // no tab is lit while showing results
        if (hub.jfKey.isNotEmpty() && hub.installed(Links.JELLYFIN)) rows.content("In your library", listOf {
            hub.jf("/Items?userId=%s&searchTerm=${Uri.encode(q)}&recursive=true&includeItemTypes=Movie,Series,Episode&limit=20&fields=Overview,ProviderIds,CriticRating")
        }, false, 40, null)
        hub.providers().forEach { p -> rows.content("On ${p.source.label}", listOf { Sources.streaming(p, hub.country, q) }, false, 40, null) }
        if (hub.installed(Links.STREMIO)) {
            rows.content("Movies on Stremio", listOf { Sources.cinemeta("movie", q) }, false, 40, null)
            rows.content("Series on Stremio", listOf { Sources.cinemeta("series", q) }, false, 40, null)
        }
        if (hub.installed(Links.SMARTTUBE)) rows.content("YouTube", listOf { Sources.youtube(q, hub.country) }, false, 40, null)
        showHero(null, "Results for “$q”")
    }

    fun selectTab(t: Tab) {
        if (t == tab && appFilter == null && !inSearch) return
        tab = t
        appFilter = null
        pendingHero = null; showHero(null, t.label) // header shows the tab until a title is focused
        loadHome()
        topBar.focusTab(t) // stay on the tabs while browsing them, like Google TV
    }

    fun pickApp(filter: String?) {
        appFilter = filter
        lastContentFocus = null
        sidebar.hide()
        loadHome() // first card of the new view takes focus once it loads
    }

    fun chooseRows() = catalog.pick(this, gen, ::loadHome)

    /** Tablet sidebar's Apps icon: back to Home, scrolled to the top where your app icons are. */
    fun showApps() {
        sidebar.hide()
        if (inSearch || appFilter != null || tab != Tab.HOME) { tab = Tab.HOME; pickApp(null) } else rows.top()
    }

    /** Back from the sidebar to where you were in the rows. */
    fun backToContent() {
        sidebar.hide()
        if (rowsDirty) { rowsDirty = false; if (appFilter == null && tab == Tab.HOME && !inSearch) { loadHome(); return } }
        (lastContentFocus?.takeIf { it.isAttachedToWindow } ?: rows.firstFocusable())?.requestFocus()
    }

    /**
     * A tile got focus: remember it for coming back from the sidebar, and show its title in the header after a short
     * pause (so fast scrolling doesn't reload the header for every tile it passes). App tiles have no item.
     */
    fun onTileFocused(v: View, item: Item? = null) {
        lastContentFocus = v
        item ?: return
        pendingHero = item
        hub.main.removeCallbacks(heroUpdate)
        hub.main.postDelayed(heroUpdate, 200)
    }

    private fun showHero(item: Item?, fallbackTitle: String = "") {
        heroKicker.text = item?.let(Meta::kicker).orEmpty()
        heroTitle.text = item?.title ?: fallbackTitle
        heroDesc.text = item?.description.orEmpty()
        meta.fill(heroMeta, item)
        item?.let { meta.withRatings(it) { if (pendingHero === it) meta.fill(heroMeta, it) } }
        val img = item?.backdrop ?: item?.poster.takeIf { item?.wide == true }
        if (img == null) { heroImg.tag = null; heroImg.setImageDrawable(null) } else images.load(img, heroImg, big = true)
    }

    /** Redraws the Apps section on Home (after favourites change); set by the row while it's shown. */
    var refreshApps: () -> Unit = {}

    /** "Your apps": every installed TV app, laid out like Google TV's apps row. OK opens, a long press offers Move. */
    fun fillAppsRow(strip: ViewGroup, all: List<SideApp>) {
        strip.removeAllViews()
        val favs = apps.favourites()
        // tablet: A to Z like a launcher's app drawer (Collator, so "Cámara" sorts with the C's); the TV keeps your order
        val hidden = apps.hidden()
        val searching = inSearch
        val favsOnly = apps.favouritesOnly && !searching
        val list = when {
            searching -> Apps.matches(all.filter { it.key !in hidden }, searchQuery) // nothing when none match
            else -> (if (favsOnly) all.filter { it.key in favs } else all)
                .filter { it.key !in hidden }
                .sortedWith(compareBy(java.text.Collator.getInstance()) { it.label })
                .let { az -> if (!favsOnly) az else az.associateBy { it.key }.let { by -> RowOrder.apply(az.map { it.key }, apps.favOrder()).map(by::getValue) } }
        }
        if (list.isEmpty() && all.isNotEmpty() && !searching) strip.addView(text(15f, TEXT_MUTED).apply {
            text = "No favorites yet: press and hold an app and choose “Mark as favorite”, or pick them in Settings → Favorite apps"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        val canDrag = hub.touch && favsOnly && !desktopLocked() // the TV moves favorites with Move and the remote's arrows
        if (canDrag) dragToReorder(strip) else strip.setOnDragListener(null)
        list.forEachIndexed { i, app ->
            lateinit var tile: LinearLayout
            fun menu() =
                MoveMode.holdMenu(this, tile, beside = false,
                    listOf("Open" to { app.pkg?.let(apps::launch) ?: Unit }) +
                        (if (hub.touch || !favsOnly || desktopLocked()) emptyList() else moveMode.options(tile, strip, horizontal = true, host = tile.getChildAt(0) as FrameLayout)) + // Apps is A to Z; TV favorites: Move
                        (app.pkg?.let { pkg ->
                            listOf(
                                (if (pkg in favs) "Remove from favorites" else "Mark as favorite") to { apps.setFavourite(pkg, pkg !in favs); refreshApps() },
                                // back from Settings → Hidden apps
                                "Hide app" to { apps.setHidden(pkg, true); refreshApps(); toast("${app.label} hidden · Settings → Hidden apps to bring it back") },
                                // Android asks you to confirm; the Apps section updates when you come back
                                "Uninstall" to { runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }.onFailure { toast("Couldn't uninstall ${app.label}") }; Unit },
                            )
                        } ?: emptyList()) +
                        ("Change icon" to { IconPicker.forApp(this, app) { sidebar.dirty = true; loadHome() } ?: Unit }) +
                        ("Rename" to { apps.rename(this, app) { sidebar.dirty = true; refreshApps() } }) +
                        ("App info" to { app.pkg?.let { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it"))) } ?: Unit }),
                )
            tile = cards.appTile(app, { onTileFocused(it) }, onClick = { app.pkg?.let(apps::launch) }) {
                // Favorites: hold and drag to put it somewhere else; hold and let go without moving for the menu
                if (canDrag) { draggedMenu = ::menu; tile.startDragAndDrop(null, View.DragShadowBuilder(tile), tile, 0); tile.alpha = 0.3f } else menu()
            }
            tile.setOnKeyListener { v, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN && strip.indexOfChild(v) == 0) { sidebar.focus(); true } else false
            }
            strip.addView(tile)
        }
    }

    private var draggedMenu: (() -> Unit)? = null
    private var dragStart = -1

    /**
     * Favorites drag and drop: the dragged app slides into the spot under your finger as you go (the others make room),
     * and the new order is saved when you let go. A drag that never left its own spot opens the app's menu instead.
     */
    private fun dragToReorder(strip: ViewGroup) = strip.setOnDragListener { _, e ->
        val tile = e.localState as? View ?: return@setOnDragListener false
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> { dragStart = strip.indexOfChild(tile); true }
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                val target = (0 until strip.childCount).firstOrNull { i ->
                    val c = strip.getChildAt(i); e.x >= c.left && e.x < c.right && e.y >= c.top && e.y < c.bottom
                }
                val from = strip.indexOfChild(tile)
                if (target != null && target != from) { strip.removeView(tile); strip.addView(tile, target) }
                true
            }
            android.view.DragEvent.ACTION_DROP -> true
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                tile.alpha = 1f
                val moved = strip.indexOfChild(tile) != dragStart
                if (moved) apps.saveFavOrder((0 until strip.childCount).mapNotNull { strip.getChildAt(it).tag as? String })
                else draggedMenu?.invoke()
                draggedMenu = null
                true
            }
            else -> true
        }
    }

    // ---- keys ----

    /** While moving an app, the remote drives it (see [MoveMode]). */
    override fun dispatchKeyEvent(e: KeyEvent) = moveMode.onKey(e) || super.dispatchKeyEvent(e)

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        when {
            detail.isShown -> detail.close()
            sidebar.rail.hasFocus() -> backToContent()
            inSearch -> loadHome()
            appFilter != null -> pickApp(null) // single-app view -> back to everything
            tab != Tab.HOME -> selectTab(Tab.HOME) // content-type tab -> back to Home
            // a home screen doesn't exit to nowhere: Back goes to the top, Back again opens the sidebar on Search
            android.os.SystemClock.uptimeMillis() - lastBackAt < 2000 -> { lastBackAt = 0; sidebar.focus(on = Sidebar.SEARCH) }
            else -> { lastBackAt = android.os.SystemClock.uptimeMillis(); rows.top() }
        }
    }

    /** Home pressed while we're the home screen: close whatever is open and go back to the top of the home view. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        searchBar.dismiss()
        if (moveMode.active) moveMode.end()
        if (detail.isShown) detail.close()
        sidebar.hide()
        if (inSearch || appFilter != null || tab != Tab.HOME) { tab = Tab.HOME; pickApp(null) } else rows.top()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_SEARCH) { searchBar.open(); return true }
        return super.onKeyDown(code, e)
    }

    @Deprecated("Activity result API needs androidx activity")
    /** Tablet desktop lock (padlock next to search): sections can't be folded, moved or edited, and their headers go. */
    fun desktopLocked() = hub.prefs.getBoolean("desktop_locked", false)

    private var fileToSave: String? = null
    private var fileOpened: ((String) -> Unit)? = null

    /** Settings → Back up: you choose where the file goes (Downloads, Drive...). */
    fun saveFile(name: String, text: String) = runCatching {
        fileToSave = text
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE_FILE)
    }.onFailure { toast("Couldn't open the file picker") }

    /** Settings → Restore: pick a backup file; onText gets its contents. */
    fun openFile(onText: (String) -> Unit) = runCatching {
        fileOpened = onText
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"), REQ_OPEN_FILE)
    }.onFailure { toast("Couldn't open the file picker") }

    var onBackgroundPicked: (() -> Unit)? = null // an open Settings panel redraws with the new picture
    private var pickingFor: String? = null

    /** Settings → Background: add a picture to the gallery (id null), or give one its portrait version. Any image the tablet can open. */
    fun pickBackground(id: String?, portrait: Boolean) = runCatching {
        pickingFor = id ?: System.currentTimeMillis().toString()
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, id == null), // adding: pick several at once
            if (portrait) REQ_BG_PORTRAIT else REQ_BG_LANDSCAPE)
    }.onFailure { toast("Couldn't open the picture picker") }

    // tablet: Android's status bar (clock, Wi-Fi, battery) and navigation bar (back, home, recents) stay hidden while
    // Joint Launcher is on screen; a swipe in from the top or bottom edge shows them for a moment. Re-applied on focus
    // because dialogs and other apps bring them back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /** Which bars are hidden comes from Settings (both by default). */
    fun applySystemBars() {
        if (!hub.touch) return
        val status = hub.prefs.getBoolean("hide_status_bar", true)
        val nav = hub.prefs.getBoolean("hide_nav_bar", true)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            val s = android.view.WindowInsets.Type.statusBars(); val n = android.view.WindowInsets.Type.navigationBars()
            if (status) hide(s) else show(s)
            if (nav) hide(n) else show(n)
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                (if (status) View.SYSTEM_UI_FLAG_FULLSCREEN else 0) or (if (nav) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else 0)
        }
    }

    // the turn itself doesn't rebuild the screen (configChanges); only the background picture follows it
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBackground()
        if (hub.touch) rows.refreshWidgets() // the widget grid has its own size and spots per orientation
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_FILE || requestCode == REQ_OPEN_FILE) {
            val uri = data?.data.takeIf { resultCode == RESULT_OK } ?: return
            if (requestCode == REQ_SAVE_FILE) runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(fileToSave!!.toByteArray()) } }
                .onSuccess { toast("Backup saved") }.onFailure { toast("Couldn't save: ${it.message}") }
            else runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                .onSuccess { fileOpened?.invoke(it) }.onFailure { toast("Couldn't read that file: ${it.message}") }
            return
        }
        if (requestCode == REQ_BG_LANDSCAPE || requestCode == REQ_BG_PORTRAIT) {
            if (resultCode != RESULT_OK || data == null) return
            val first = pickingFor ?: return
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            val portrait = requestCode == REQ_BG_PORTRAIT
            // private copies: the pictures keep working even if the originals are moved or deleted
            val ids = uris.mapIndexedNotNull { i, uri ->
                val id = if (portrait || i == 0) first else (first.toLong() + i).toString()
                runCatching { contentResolver.openInputStream(uri)!!.use { s -> bgFile(id, portrait).outputStream().use { s.copyTo(it) } }; id }
                    .onFailure { toast("Couldn't use a picture: ${it.message}") }.getOrNull()
            }
            ids.firstOrNull()?.let { useBackground(it); onBackgroundPicked?.invoke() }
            toast(when { ids.isEmpty() -> "No picture added"; portrait -> "Portrait version set"; ids.size == 1 -> "Picture added"; else -> "${ids.size} pictures added" })
            return
        }
        if (requestCode == SearchBar.VOICE_REQUEST && resultCode == RESULT_OK) searchBar.onVoiceResult(data)
        else widgets.onResult(requestCode, resultCode == RESULT_OK)
    }

    override fun onResume() {
        super.onResume()
        rows.retryFailed()
        if (!hub.touch) HomeButtonService.restore(this) // the TV's Home button service, restarted if Android stopped it
        if (hub.touch) {
            apps.forgetList(); refreshApps() // back from uninstalling (or installing) something
            if (backgrounds().isNotEmpty()) applyBackground() // takes in pictures dropped in the inbox
        }
    }

    override fun onDestroy() {
        nowPlaying.stop()
        hub.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_BG_LANDSCAPE = 61
        private const val REQ_BG_PORTRAIT = 62
        private const val REQ_SAVE_FILE = 63
        private const val REQ_OPEN_FILE = 64
    }
}
