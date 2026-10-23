package com.mau.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val pool = Sources.bgPool(8) // network-bound row loads, background priority so scrolling stays smooth
    private val imagePool = Sources.bgPool(4) // separate, so posters never queue behind slow row loads
    private val diskPool = Executors.newSingleThreadExecutor() // saved rows: quick sequential reads at normal priority
    private val main = Handler(Looper.getMainLooper())
    // sized by bytes, not count: a few 1440p backdrops used to fill the heap and stall the UI with GC
    private val images = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }
    private val server get() = prefs.getString("server", BuildConfig.JELLYFIN_SERVER)!!.trimEnd('/')
    private val jfKey get() = prefs.getString("key", "")!!
    private val stremioKey get() = prefs.getString("stremio", "")!!
    private val ytTopic get() = prefs.getString("yt", "sports highlights")!!
    private val country = Locale.getDefault().country.ifEmpty { "US" }
    @Volatile private var userId: String? = null
    private var gen = 0 // bumps on reload/search so stale async rows are dropped
    private var inSearch = false
    private var lastFocused: View? = null

    private lateinit var rows: LinearLayout
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroKicker: TextView
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: ScrollView
    private lateinit var railDim: View
    private var lastContentFocus: View? = null
    private lateinit var heroMeta: LinearLayout
    private lateinit var heroDesc: TextView
    private lateinit var detail: FrameLayout
    private lateinit var page: LinearLayout // top bar + rows: blocked from focus while the detail screen covers it

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        heroImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroTitle = text(26f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.01f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
        }
        heroKicker = text(11f, ACCENT).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = 0.18f }
        heroMeta = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heroDesc = text(13f, TEXT_SOFT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }

        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(4), dp(36), 0)
            addView(heroKicker)
            addView(heroTitle, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.6).toInt(), -2))
            addView(heroMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(heroDesc, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.5).toInt(), -2).apply { topMargin = dp(8) })
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, dp(36), dp(40))
            clipChildren = false
        }
        // Layout budget (960x540dp): 46dp top bar + 136dp header + two ~178dp rows still fit without scrolling.
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildTopBar(), LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(10) })
            addView(heroText, LinearLayout.LayoutParams(-1, dp(136)))
            addView(ScrollView(this@MainActivity).apply {
                clipChildren = false; isVerticalScrollBarEnabled = false; addView(rows)
                rowsScroll = this
                setOnScrollChangeListener { _, _, _, _, _ -> flushRenders() }
                viewTreeObserver.addOnGlobalLayoutListener { flushRenders() }
            },
                LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // Netflix-style icon rail on the left: search, home, one icon per app, rows, settings
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        // scrolls when there are more apps than fit; fillViewport keeps Rows/Settings pinned to the bottom otherwise
        railScroll = ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            visibility = View.GONE // only slides in on Left from a row's first tile
            setBackgroundColor(0xF2050505.toInt()) // solid panel; the content behind is dimmed while it is open
            addView(rail)
        }
        detail = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(BG) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(heroImg, FrameLayout.LayoutParams(-1, dp(320), Gravity.END).apply { leftMargin = dp(300) })
            addView(View(this@MainActivity).apply { background = fade() }, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@MainActivity).apply { background = fadeDown() }, FrameLayout.LayoutParams(-1, dp(320)))
            addView(page, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(20) })
            railDim = View(this@MainActivity).apply { setBackgroundColor(0xB3000000.toInt()); visibility = View.GONE }
            addView(railDim, FrameLayout.LayoutParams(-1, -1))
            addView(railScroll, FrameLayout.LayoutParams(dp(RAIL_DP + 8), -1))
            addView(detail)
        })
        // focus leaving the sidebar any other way (e.g. Up past its top) closes it too
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (moving == null && railScroll.visibility == View.VISIBLE && now != null && !isInside(now, rail)) hideRail()
        }
        // disk cache for every HTTP GET that allows caching (all the poster/backdrop/logo CDNs do)
        runCatching { android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 150L * 1024 * 1024) }
        importTokens()
        loadHome()
        watchSessions()
    }

    // ---- home / search ----

    // ---- top bar (like Google TV's): search, content-type tabs, weather, clock ----

    private enum class Tab(val label: String) {
        HOME("Home"), MOVIES("Movies"), SERIES("Series"), LIVE("Live TV"), SPORTS("Sports"), MUSIC("Music"), VIDEOS("Videos"), APPS("Apps")
    }
    private var tab = Tab.HOME
    private lateinit var tabStrip: LinearLayout
    private lateinit var weatherView: TextView
    private lateinit var topSearch: View
    private lateinit var topGear: View

    private fun buildTopBar(): View {
        tabStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        weatherView = text(14f, TEXT_SOFT).apply { setPadding(0, 0, dp(16), 0) }
        val clock = android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        refreshWeather()
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), 0, dp(36), 0)
            addView(iconButton(R.drawable.ic_search, "Search") { askSearch() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                id = View.generateViewId(); topSearch = this
                background = barIconBackground()
                // leftmost thing on screen, like a row's first tile: Left opens the sidebar
                setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) { focusRail(); true } else false }
            })
            addView(tabStrip)
            // little gear right after the tabs: the TV's own Settings app
            addView(iconButton(R.drawable.ic_settings, "TV settings") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }.onFailure { toast("Couldn't open Settings") }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                id = View.generateViewId(); topGear = this
                background = barIconBackground() // just the icon at rest; white circle only when focused
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(weatherView)
            addView(clock)
        }
    }

    /** Top-bar icons: nothing behind them at rest, a white circle when focused (same as the tabs' pill). */
    private fun barIconBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
    }

    /** Tabs only for content you have (no Music tab without Spotify, and so on). Selected tab = white bold text. */
    private fun buildTabs() {
        tabStrip.removeAllViews()
        val shown = Tab.entries.filter {
            when (it) {
                Tab.LIVE -> jfKey.isNotEmpty()
                Tab.SPORTS -> installed(Links.GEESPORTS)
                Tab.MUSIC -> connected("spotify")
                Tab.VIDEOS -> connected("google")
                else -> true
            }
        }
        shown.forEach { t ->
            val selected = t == tab && appFilter == null && !inSearch
            tabStrip.addView(text(14f, if (selected) Color.WHITE else TEXT_MUTED).apply {
                text = t.label
                typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) })
                }
                isFocusable = true; isClickable = true; tag = t
                id = View.generateViewId()
                setOnFocusChangeListener { _, f ->
                    setTextColor(if (f) Color.BLACK else if (selected) Color.WHITE else TEXT_MUTED)
                    // like Google TV: resting on a tab switches to it
                    main.removeCallbacks(tabSwitch)
                    if (f && t != tab) { pendingTab = t; main.postDelayed(tabSwitch, 350) }
                }
                setOnClickListener { selectTab(t) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
        }
        // the default focus search jumped from the search icon down into the rows; chain the bar explicitly
        val first = tabStrip.getChildAt(0) ?: return
        topSearch.nextFocusRightId = first.id
        first.nextFocusLeftId = topSearch.id
        val last = tabStrip.getChildAt(tabStrip.childCount - 1)
        last.nextFocusRightId = topGear.id
        topGear.nextFocusLeftId = last.id
    }

    private var pendingTab: Tab? = null
    private val tabSwitch = Runnable { pendingTab?.let(::selectTab) }

    private fun selectTab(t: Tab) {
        if (t == tab && appFilter == null && !inSearch) return
        tab = t
        appFilter = null
        pendingHero = null; showHero(null, t.label) // header shows the tab until a title is focused
        loadHome()
        tabStrip.findViewWithTag<View>(t)?.requestFocus() // stay on the tabs while browsing them, like Google TV
    }

    /** Weather in the corner; location found once from the connection, temperature refreshed every 30 minutes. */
    private fun refreshWeather() {
        pool.execute {
            val text = runCatching {
                val lat = prefs.getString("lat", null)?.toDouble()
                val lon = prefs.getString("lon", null)?.toDouble()
                val (la, lo) = if (lat != null && lon != null) lat to lon else Sources.locate().also { (a, b) ->
                    prefs.edit().putString("lat", a.toString()).putString("lon", b.toString()).apply()
                }
                Sources.weather(la, lo)
            }.getOrNull()
            main.post { weatherView.text = text.orEmpty() }
        }
        main.postDelayed({ refreshWeather() }, 30 * 60_000L)
    }

    /** Rows for one content type, gathered from every source you have. */
    private fun tabRows(t: Tab): List<RowDef> = buildList {
        fun r(title: String, top10: Boolean = false, fetch: () -> List<Item>) = add(RowDef(title, true, listOf(fetch), top10))
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val jfFields = "fields=Overview,ProviderIds,CriticRating"
        val jellyfin = jfKey.isNotEmpty()
        val providers = PROVIDERS.filter { installed(it.pkg) || "Popular on ${it.source.label}" in extraRows() }
        when (t) {
            Tab.MOVIES, Tab.SERIES -> {
                val movie = t == Tab.MOVIES
                val word = if (movie) "Movies" else "Series"
                providers.forEachIndexed { i, p ->
                    r(if (i == 0) "Top 10 ${word.lowercase()} on ${p.source.label}" else "$word on ${p.source.label}", top10 = i == 0) {
                        Sources.streaming(p, country, objectType = if (movie) "MOVIE" else "SHOW")
                    }
                }
                if (installed(Links.STREMIO)) {
                    r("Popular ${word.lowercase()} on Stremio") { Sources.cinemeta(if (movie) "movie" else "series") }
                    r("New ${word.lowercase()} this year · Stremio") { Sources.cinemetaYear(if (movie) "movie" else "series", year) }
                }
                if (jellyfin) r("$word · Jellyfin") {
                    jf("/Items?userId=%s&recursive=true&includeItemTypes=${if (movie) "Movie" else "Series"}&sortBy=DateCreated&sortOrder=Descending&limit=60&$jfFields")
                }
                // more of this type: one row per genre, across all your services at once
                if (providers.isNotEmpty()) Sources.GENRES.forEach { (code, name) ->
                    r(if (code == "doc") name else "$name ${word.lowercase()}") {
                        Sources.streamingMix(providers, country, if (movie) "MOVIE" else "SHOW", code)
                    }
                }
                providers.forEach { p -> r("Top rated $word on ${p.source.label}".replace("Top rated Series", "Top rated series").replace("Top rated Movies", "Top rated movies")) {
                    Sources.streaming(p, country, objectType = if (movie) "MOVIE" else "SHOW", sortBy = "IMDB_SCORE")
                } }
            }
            Tab.LIVE -> {
                r("Live TV favourites · Jellyfin") { Sources.jellyfinLive(server, jfKey, jfUser(), 100, favouritesOnly = true) }
                r("Live TV · Jellyfin") { Sources.jellyfinLive(server, jfKey, jfUser(), 200) }
            }
            Tab.SPORTS -> Sources.SPORT_ROWS.forEach { (name, leagues) -> r("$name · next 7 days · GeeSports") { Sources.sports(leagues, days = 7) } }
            Tab.MUSIC -> {
                r("New releases from artists you follow · Spotify") { Sources.spotifyNewReleases(token("spotify")!!) }
                r("Your playlists · Spotify") { Sources.spotifyPlaylists(token("spotify")!!) }
                r("Liked albums · Spotify") { Sources.spotifySavedAlbums(token("spotify")!!) }
                r("Your top artists · Spotify") { Sources.spotifyTopArtists(token("spotify")!!) }
                r("Artists you follow · Spotify") { Sources.spotifyArtists(token("spotify")!!) }
            }
            Tab.VIDEOS -> {
                r("New from your subscriptions · YouTube") { youtubeSubs() }
                r("More to explore · YouTube") {
                    Sources.youtubeDiscover(youtubeSubs().take(10).map { (it.play as Play.Link).uri.substringAfter("v=") }, subChannelIds().toSet(), country)
                }
            }
            Tab.HOME, Tab.APPS -> Unit
        }
    }

    private fun loadHome() {
        gen++; inSearch = false; rows.removeAllViews(); pendingRenders.clear(); failedRows.clear()
        railDirty = true // built when the sidebar is first opened, not at startup
        val f = appFilter
        val appView = f != null && sidebarApps().any { it.filter == f }
        if (appView) tab = Tab.HOME
        buildTabs()
        nowSection()
        if (appView) {
            // one app only, with more of its content; stays until changed in the sidebar
            focusRows(f!!).forEach { row(it.title, it.fetchers, it.top10) }
            return
        }
        if (tab != Tab.HOME) { // a content-type tab: that type from every source
            tabRows(tab).forEach { row(it.title, it.fetchers, it.top10) }
            if (tab == Tab.APPS) appRow()
            return
        }
        val connect = section("Connect")
        if (jfKey.isEmpty() && installed(Links.JELLYFIN)) connect.addView(connectCard(Source.JELLYFIN, Links.JELLYFIN) { quickConnect() })
        if (!connected("google") && installed(Links.SMARTTUBE)) connect.addView(connectCard(Source.YOUTUBE, Links.SMARTTUBE) { connectHelp("YouTube") })
        if (!connected("spotify") && installed(Links.SPOTIFY)) connect.addView(connectCard(Source.SPOTIFY, Links.SPOTIFY) { connectHelp("Spotify") })
        if (connect.childCount == 0) { rows.removeViewAt(rows.childCount - 1); rows.removeViewAt(rows.childCount - 1) }
        else connect.getChildAt(0).post { connect.getChildAt(0)?.requestFocus() }

        orderedRows().filter(::rowVisible).forEach { row(it.title, it.fetchers, it.top10) }
        appRow()
    }

    // ---- single-app view (picked in the sidebar) ----

    /** Selected app key (see [sidebarApps]), or null for everything. Persisted until changed. */
    private var appFilter: String?
        get() = prefs.getString("app_filter", null)
        set(v) = prefs.edit().putString("app_filter", v).apply()

    /**
     * One sidebar entry. key = stable id for ordering (package name, or "jw:HULU" for catalogue-only services);
     * filter = the single-app view it selects, or null when it simply opens the app (VPN, file manager...).
     */
    private class SideApp(val key: String, val label: String, val icon: Drawable?, val filter: String?, val pkg: String?, val logo: Provider? = null)

    private fun appIcon(pkg: String) = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Which single-app view an installed app maps to (null = no content here, just launch it). */
    private fun filterFor(pkg: String): String? = when (pkg) {
        Links.JELLYFIN -> "jellyfin".takeIf { jfKey.isNotEmpty() }
        Links.STREMIO -> "stremio"
        Links.SMARTTUBE -> "youtube".takeIf { connected("google") }
        Links.SPOTIFY -> "spotify".takeIf { connected("spotify") }
        Links.GEESPORTS -> "geesports"
        else -> PROVIDERS.firstOrNull { it.pkg == pkg }?.source?.name
    }

    /** Every launchable TV app (what Google TV lists under "Your apps"), in your saved order. */
    @Volatile private var appsCache: Pair<Long, List<SideApp>>? = null

    private fun allApps(): List<SideApp> {
        // scanning packages and loading ~25 icons is the slow part; reuse it for 30s (rail, rows and focus all ask)
        val apps = appsCache?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second
            ?: packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != packageName && installed(it) }
                .map { pkg ->
                    val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    SideApp(pkg, label, null, filterFor(pkg), pkg) // icon loads in the background (see appIconInto)
                }
                .sortedWith(compareBy({ it.filter == null }, { it.label.lowercase() })) // default before any saved/Google TV order
                .also { appsCache = System.currentTimeMillis() to it }
        val byKey = apps.associateBy { it.key }
        return RowOrder.apply(apps.map { it.key }, savedAppOrder()).map(byKey::getValue)
    }

    /** Sidebar: apps with content here (they filter the home screen) plus every streaming service's catalogue, installed or not. */
    private fun sidebarApps(): List<SideApp> {
        val catalogues = PROVIDERS.filter { !installed(it.pkg) }
            .map { SideApp("jw:${it.source.name}", it.source.label, null, it.source.name, null, logo = it) }
        val all = allApps().filter { it.filter != null } + catalogues
        val byKey = all.associateBy { it.key }
        return RowOrder.apply(all.map { it.key }, savedAppOrder()).map(byKey::getValue)
    }

    private fun savedAppOrder() = prefs.getString("app_order", null)?.split("\n")?.filter { it.isNotEmpty() } ?: GOOGLE_TV_ORDER

    // ---- move mode (hold OK on an app in the sidebar or the apps row), like Google TV ----

    /** The app being moved, the list it lives in, its axis, and the arrow views drawn on it. */
    private class MoveState(val view: View, val container: LinearLayout, val horizontal: Boolean, val arrows: Map<Int, View>) {
        var armed = false // the OK release that ends the long-press must not also drop the app
    }
    private var moving: MoveState? = null

    /** App entries are the children tagged with an app key (sidebar also holds logo, search, home, rows, settings). */
    private fun appChildren(c: LinearLayout) = (0 until c.childCount).map(c::getChildAt).filter { (it.tag as? String)?.startsWith("rail:") == false }

    /** host = the FrameLayout the arrows are drawn on (rail item itself, or the icon box of an app tile). */
    /**
     * Google TV's way: the app lifts (grows, casts a shadow), the others dim, and thin white chevrons sit just
     * outside the icon on the sides it can move to. armed = started from the hold menu (no key still held down).
     */
    private fun startMove(v: View, container: LinearLayout, horizontal: Boolean, host: FrameLayout, armed: Boolean = true) {
        if (moving != null) return
        val reach = (if (horizontal) host.width else host.height) / 2f + dp(12)
        fun chevron(dir: Int, rotation: Float) = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron); setColorFilter(Color.WHITE); this.rotation = rotation
            if (horizontal) translationX = dir * reach else translationY = dir * reach
        }.also { host.addView(it, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)) }
        val arrows = if (horizontal) mapOf(-1 to chevron(-1, 180f), 1 to chevron(1, 0f)) else mapOf(-1 to chevron(-1, 270f), 1 to chevron(1, 90f))
        appChildren(container).forEach { if (it !== v) it.animate().alpha(0.35f).setDuration(150).start() } // dim the others
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start() // lift
        v.elevation = dp(16).toFloat()
        moving = MoveState(v, container, horizontal, arrows).also { it.armed = armed }
        updateArrows()
    }

    /** Hold OK on an app: a small Google-TV-style menu next to it. */
    private fun holdMenu(anchor: View, beside: Boolean, options: List<Pair<String, () -> Unit>>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xF2202127.toInt()) }
            elevation = dp(12).toFloat()
        }
        val popup = android.widget.PopupWindow(box, -2, -2, true)
        options.forEach { (label, act) ->
            box.addView(button(label) { popup.dismiss(); act() }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            })
        }
        box.measure(0, 0)
        if (beside) popup.showAsDropDown(anchor, anchor.width, -(anchor.height + box.measuredHeight) / 2)
        else popup.showAsDropDown(anchor, (anchor.width - box.measuredWidth) / 2, -(anchor.height + box.measuredHeight + dp(8)))
        box.getChildAt(0)?.requestFocus()
    }

    /** Arrows only on sides the app can still move to. */
    private fun updateArrows() {
        val m = moving ?: return
        val apps = appChildren(m.container)
        val pos = apps.indexOf(m.view)
        m.arrows[-1]?.visibility = if (pos > 0) View.VISIBLE else View.INVISIBLE
        m.arrows[1]?.visibility = if (pos < apps.size - 1) View.VISIBLE else View.INVISIBLE
    }

    private fun shiftMoving(d: Int) {
        val m = moving ?: return
        val apps = appChildren(m.container)
        val target = apps.getOrNull(apps.indexOf(m.view) + d) ?: return
        val to = m.container.indexOfChild(target)
        // removing the focused view would hand focus to a card behind (closing the sidebar / scrolling the page);
        // block focus everywhere for the swap, then give it straight back to the moved app
        val root = window.decorView as ViewGroup
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        m.container.removeView(m.view)
        m.container.addView(m.view, to)
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        m.view.requestFocus()
        updateArrows()
    }

    /** Drop the app: undo the dimming and arrows, save the shared order (sidebar and apps row use the same one). */
    private fun endMove() {
        val m = moving ?: return
        moving = null
        m.arrows.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val apps = appChildren(m.container)
        apps.forEach { it.animate().alpha(1f).setDuration(150).start() }
        m.view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        m.view.elevation = 0f
        val moved = apps.mapNotNull { it.tag as? String }
        prefs.edit().putString("app_order", (moved + savedAppOrder().filter { it !in moved }).joinToString("\n")).apply()
        railDirty = m.container !== rail // the other list picks the new order up next time it's built
        if (m.container === rail) rowsDirty = true // home rows follow the sidebar: reorder them when it closes
        m.view.requestFocus()
    }

    /** While moving, the remote drives the app: arrows on its axis move it, OK or Back drops it, everything else is ignored. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val m = moving ?: return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_UP) { m.armed = true; return true }
        if (e.repeatCount > 0 && !m.armed) return true // still holding OK from the long-press
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (m.horizontal) shiftMoving(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (m.horizontal) shiftMoving(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (!m.horizontal) shiftMoving(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!m.horizontal) shiftMoving(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (m.armed) endMove()
            KeyEvent.KEYCODE_BACK -> endMove()
        }
        return true
    }

    // ---- sidebar (hidden until Left on a row's first tile) ----

    /** Rebuilt on every home load (installed apps / connections may have changed). */
    private fun buildRail() {
        rail.removeAllViews()
        rail.clipChildren = false // move-mode chevrons sit just outside the icon
        val apps = sidebarApps()
        val current = appFilter?.takeIf { f -> apps.any { it.filter == f } }
        // the app's own icon at the top IS the Home button (everything view), then Search, then the apps
        railItem(getDrawable(R.drawable.icon), "Home", HOME_TAG, selected = current == null && !inSearch) { pickApp(null) }
        railItem(getDrawable(R.drawable.ic_search), "Search", HOME_TAG + "search", tint = true) { askSearch() }
        apps.forEach { a ->
            lateinit var v: FrameLayout
            v = railItem(a.icon, a.label, a.key, selected = a.filter == current, onLongClick = {
                holdMenu(v, beside = true, listOf("Open" to { pickApp(a.filter) }, "Move" to { startMove(v, rail, horizontal = false, host = v) }))
            }) { pickApp(a.filter) }
            a.logo?.let { p -> loadProviderLogo(p, v.getChildAt(1) as ImageView) }
            a.pkg?.let { appIconInto(it, v.getChildAt(1) as ImageView) }
        }
        rail.addView(View(this), LinearLayout.LayoutParams(0, dp(8), 1f))
        railItem(getDrawable(R.drawable.ic_rows), "Rows", HOME_TAG + "rows", tint = true) { hideRail(); chooseRows() }
        railItem(getDrawable(R.drawable.ic_settings), "Settings", HOME_TAG + "settings", tint = true) { hideRail(); settings() }
    }

    private fun showRail() {
        if (railScroll.visibility == View.VISIBLE) return
        railScroll.visibility = View.VISIBLE
        railDim.visibility = View.VISIBLE
        railScroll.translationX = -dp(RAIL_DP).toFloat()
        railScroll.animate().translationX(0f).setDuration(160).start()
    }

    private fun hideRail() { railScroll.visibility = View.GONE; railDim.visibility = View.GONE }

    /**
     * Icon-only entry. Apps get Google-TV-style rounded square icons; tinted vector icons for Search/Home/Rows/Settings.
     * Orange marker on the view that's on screen; focused icon grows. Long-press = reorder (apps only).
     */
    private fun railItem(
        icon: Drawable?, label: String, key: String, tint: Boolean = false, selected: Boolean = false,
        onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
    ): FrameLayout {
        val marker = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ACCENT) }
            visibility = View.INVISIBLE // follows focus (see the focus listener below)
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (tint) setColorFilter(if (selected) Color.WHITE else 0xFFB3B3B3.toInt())
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF2A2B30.toInt()) }
            if (tint) background = null
            alpha = if (selected) 1f else 0.75f
        }
        val item = FrameLayout(this).apply {
            contentDescription = label // icon-only, so screen readers still get a name
            tag = key
            isFocusable = true; isClickable = true
            onLongClick?.let { cb -> setOnLongClickListener { cb(); true } }
            background = StateListDrawable().apply {
                // full-width square band, edge to edge of the panel (no rounded corners)
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(0x33FFFFFF))
            }
            addView(marker, FrameLayout.LayoutParams(dp(4), -1, Gravity.START)) // full height of the item
            addView(img, FrameLayout.LayoutParams(dp(if (tint) 28 else 40), dp(if (tint) 28 else 40), Gravity.CENTER))
            setOnFocusChangeListener { _, f ->
                marker.visibility = if (f) View.VISIBLE else View.INVISIBLE
                img.animate().scaleX(if (f) 1.18f else 1f).scaleY(if (f) 1.18f else 1f).alpha(if (f || selected) 1f else 0.75f).setDuration(120).start()
                if (tint) img.setColorFilter(if (f || selected) Color.WHITE else 0xFFB3B3B3.toInt())
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN) { backToContent(); true } else false
            }
        }
        rail.addView(item, LinearLayout.LayoutParams(-1, dp(if (tint) 46 else 52)).apply { bottomMargin = dp(2) })
        return item
    }

    private fun loadProviderLogo(p: Provider, into: ImageView) {
        customIcon(p.pkg)?.let { into.setImageDrawable(it); return }
        prefs.getString("logo_${p.source.name}", null)?.let { loadImage(it, into); return }
        pool.execute {
            val url = runCatching { Sources.providerLogo(p, country) }.getOrNull() ?: return@execute
            prefs.edit().putString("logo_${p.source.name}", url).apply()
            main.post { loadImage(url, into) }
        }
    }

    private fun pickApp(filter: String?) {
        appFilter = filter
        lastContentFocus = null
        hideRail()
        loadHome() // first card of the new view takes focus once it loads
    }

    /** Left on the first tile of a row: slide the sidebar in, focused on the entry for what's on screen. */
    private fun focusRail(on: String? = null) {
        if (railDirty) { buildRail(); railDirty = false }
        showRail()
        val key = on ?: sidebarApps().firstOrNull { it.filter != null && it.filter == appFilter }?.key ?: HOME_TAG
        rail.post { (rail.findViewWithTag<View>(key) ?: rail.findViewWithTag(HOME_TAG))?.requestFocus() }
    }

    private var rowsDirty = false

    private fun backToContent() {
        hideRail()
        if (rowsDirty) { rowsDirty = false; if (appFilter == null && tab == Tab.HOME && !inSearch) { loadHome(); return } }
        (lastContentFocus?.takeIf { it.isAttachedToWindow } ?: rows.getFocusables(View.FOCUS_DOWN).firstOrNull())?.requestFocus()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    private fun isFirstInRow(v: View): Boolean {
        var child = v
        var parent = v.parent as? ViewGroup
        while (parent != null && parent.tag != STRIP) { child = parent; parent = parent.parent as? ViewGroup }
        return parent != null && parent.getChildAt(0) === child
    }

    private fun focusRows(f: String): List<RowDef> = buildList {
        fun r(title: String, top10: Boolean = false, fetch: () -> List<Item>) = add(RowDef(title, true, listOf(fetch), top10))
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val jfFields = "fields=Overview,ProviderIds,CriticRating"
        when (f) {
            "jellyfin" -> {
                r("Continue watching · Jellyfin") { jf("/UserItems/Resume?userId=%s&limit=30&mediaTypes=Video&$jfFields") }
                r("Next up · Jellyfin") { jf("/Shows/NextUp?userId=%s&limit=30&$jfFields") }
                r("Live TV favourites · Jellyfin") { Sources.jellyfinLive(server, jfKey, jfUser(), 100, favouritesOnly = true) }
                r("Live TV · Jellyfin") { Sources.jellyfinLive(server, jfKey, jfUser(), 200) }
                r("Recently added · Jellyfin") {
                    jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie,Series&sortBy=DateLastContentAdded,DateCreated&sortOrder=Descending&limit=40&$jfFields")
                }
                r("Movies · Jellyfin") { jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie&sortBy=SortName&limit=80&$jfFields") }
                r("Series · Jellyfin") { jf("/Items?userId=%s&recursive=true&includeItemTypes=Series&sortBy=SortName&limit=80&$jfFields") }
                r("Favourites · Jellyfin") { jf("/Items?userId=%s&recursive=true&filters=IsFavorite&limit=60&$jfFields") }
            }
            "stremio" -> {
                if (stremioKey.isNotEmpty()) r("Continue watching · Stremio") { Sources.stremioContinue(stremioKey) }
                r("Popular movies · Stremio") { Sources.cinemeta("movie") }
                r("Popular series · Stremio") { Sources.cinemeta("series") }
                r("New movies this year · Stremio") { Sources.cinemetaYear("movie", year) }
                r("New series this year · Stremio") { Sources.cinemetaYear("series", year) }
            }
            "youtube" -> {
                r("New from your subscriptions · YouTube") { youtubeSubs() }
                r("More to explore · YouTube") {
                    Sources.youtubeDiscover(youtubeSubs().take(10).map { (it.play as Play.Link).uri.substringAfter("v=") }, subChannelIds().toSet(), country)
                }
            }
            "spotify" -> {
                r("New releases from artists you follow · Spotify") { Sources.spotifyNewReleases(token("spotify")!!) }
                r("Your playlists · Spotify") { Sources.spotifyPlaylists(token("spotify")!!) }
                r("Liked albums · Spotify") { Sources.spotifySavedAlbums(token("spotify")!!) }
                r("Your top artists · Spotify") { Sources.spotifyTopArtists(token("spotify")!!) }
                r("Artists you follow · Spotify") { Sources.spotifyArtists(token("spotify")!!) }
            }
            "geesports" -> Sources.SPORT_ROWS.forEach { (name, leagues) -> r("$name · next 7 days · GeeSports") { Sources.sports(leagues, days = 7) } }
            else -> PROVIDERS.firstOrNull { it.source.name == f }?.let { p ->
                val l = p.source.label
                r("Top 10 on $l today", top10 = true) { Sources.streaming(p, country) }
                r("Movies on $l") { Sources.streaming(p, country, objectType = "MOVIE") }
                r("Series on $l") { Sources.streaming(p, country, objectType = "SHOW") }
                r("New releases on $l") { Sources.streaming(p, country, minYear = year - 1) }
                r("Top rated on IMDb · $l") { Sources.streaming(p, country, sortBy = "IMDB_SCORE") }
            }
        }
    }

    /** `installed` = the app the row opens is on the TV; that decides whether the row is on by default. */
    private class RowDef(
        val title: String, val installed: Boolean, val fetchers: List<() -> List<Item>>, val top10: Boolean = false,
        val pkg: String? = null, // the app the row belongs to: home rows follow the sidebar's app order
    )

    /** Every row we can fetch. Rows of apps that aren't installed start hidden but can be switched on in the Rows picker. */
    private fun availableRows(): List<RowDef> = buildList {
        fun opt(title: String, pkg: String, fetch: () -> List<Item>) = add(RowDef(title, installed(pkg), listOf(fetch), pkg = pkg))
        val jellyfin = jfKey.isNotEmpty()
        val cw = buildList<() -> List<Item>> {
            if (jellyfin) add { jf("/UserItems/Resume?userId=%s&limit=20&mediaTypes=Video&fields=Overview,ProviderIds,CriticRating") }
            if (jellyfin) add { jf("/Shows/NextUp?userId=%s&limit=20&fields=Overview,ProviderIds,CriticRating") }
            if (stremioKey.isNotEmpty()) add { Sources.stremioContinue(stremioKey) }
        }
        if (cw.isNotEmpty()) add(RowDef("Continue watching", true, cw))
        if (jellyfin) {
            opt("Live TV favourites · Jellyfin", Links.JELLYFIN) { Sources.jellyfinLive(server, jfKey, jfUser(), 50, favouritesOnly = true) }
            opt("Live TV · Jellyfin", Links.JELLYFIN) { Sources.jellyfinLive(server, jfKey, jfUser(), 40) }
        }
        // the first service you have gets a Netflix-style Top 10 row; the others a normal row
        val topPick = PROVIDERS.firstOrNull { installed(it.pkg) }
        PROVIDERS.forEach { p ->
            if (p == topPick) add(RowDef("Top 10 on ${p.source.label} today", true, listOf { Sources.streaming(p, country) }, top10 = true, pkg = p.pkg))
            else opt("Popular on ${p.source.label}", p.pkg) { Sources.streaming(p, country) }
        }
        if (connected("google")) {
            opt("New from your subscriptions · YouTube", Links.SMARTTUBE) { youtubeSubs() }
            opt("More to explore · YouTube", Links.SMARTTUBE) {
                Sources.youtubeDiscover(youtubeSubs().take(6).map { (it.play as Play.Link).uri.substringAfter("v=") }, subChannelIds().toSet(), country)
            }
        }
        // /Items/Latest ignores includeItemTypes on this server (returns music), so sort the library instead
        if (jellyfin) opt("Recently added · Jellyfin", Links.JELLYFIN) {
            jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie,Series&sortBy=DateLastContentAdded,DateCreated&sortOrder=Descending&limit=20&fields=Overview,ProviderIds,CriticRating")
        }
        opt("Popular movies on Stremio", Links.STREMIO) { Sources.cinemeta("movie") }
        opt("Popular series on Stremio", Links.STREMIO) { Sources.cinemeta("series") }
        if (connected("spotify")) {
            opt("New releases from artists you follow · Spotify", Links.SPOTIFY) { Sources.spotifyNewReleases(token("spotify")!!) }
            opt("Your playlists · Spotify", Links.SPOTIFY) { Sources.spotifyPlaylists(token("spotify")!!) }
            opt("Liked albums · Spotify", Links.SPOTIFY) { Sources.spotifySavedAlbums(token("spotify")!!) }
            opt("Your favourite artists · Spotify", Links.SPOTIFY) {
                val t = token("spotify")!!
                (Sources.spotifyTopArtists(t) + Sources.spotifyArtists(t)).distinctBy { it.play }
            }
        }
        // GeeSports last by default
        Sources.SPORT_ROWS.forEach { (name, leagues) -> opt("$name on GeeSports", Links.GEESPORTS) { Sources.sports(leagues) } }
    }

    private fun savedOrder() = prefs.getString("row_order", "")!!.split("\n").filter { it.isNotEmpty() }

    private fun orderedRows(): List<RowDef> {
        val rows = availableRows().associateBy { it.title }
        // grouped by app in the sidebar's order (Continue watching and Live TV stay on top); the saved order applies within an app
        val side = sidebarApps().map { it.pkg ?: it.logo?.pkg }
        fun rank(r: RowDef) = if (r.pkg == null || r.title.startsWith("Live TV")) -1 else side.indexOf(r.pkg).let { if (it < 0) side.size else it }
        return RowOrder.apply(rows.keys.toList(), savedOrder()).map(rows::getValue).sortedBy(::rank)
    }

    private fun saveOrder(titles: List<String>) {
        // rows not offered right now (e.g. account disconnected) keep their saved place at the end
        prefs.edit().putString("row_order", (titles + savedOrder().filter { it !in titles }).joinToString("\n")).apply()
    }

    private fun setRowVisible(r: RowDef, on: Boolean) {
        val key = if (r.installed) "hidden_rows" else "extra_rows"
        val set = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (r.installed != on) set += r.title else set -= r.title // hidden_rows lists "off" rows, extra_rows lists "on" rows
        prefs.edit().putStringSet(key, set).apply()
    }

    // Only deviations from the default are stored, so installing/uninstalling an app flips its rows' default.
    private fun hiddenRows(): Set<String> = prefs.getStringSet("hidden_rows", emptySet())!!  // installed but switched off
    private fun extraRows(): Set<String> = prefs.getStringSet("extra_rows", emptySet())!!    // not installed but switched on
    private fun rowVisible(r: RowDef) = if (r.installed) r.title !in hiddenRows() else r.title in extraRows()

    /** List of rows in home order; pick one to show/hide or move it. The home screen reloads on Done or Back. */
    private fun chooseRows(select: Int = 0) {
        val all = orderedRows()
        val labels = all.map { r ->
            (if (rowVisible(r)) "✓  " else "✗  ") + r.title + if (r.installed) "" else "  (app not installed)"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle("Rows · pick one to show/hide or move it")
            .setItems(labels) { _, i -> rowActions(all, i) }
            .setPositiveButton("Done") { _, _ -> loadHome() }
            .setOnCancelListener { loadHome() }
            .show()
        dialog.listView.setSelection(select) // keep your place after moving a row
    }

    private fun rowActions(all: List<RowDef>, i: Int) {
        val r = all[i]
        val opts = arrayOf(if (rowVisible(r)) "Hide" else "Show", "Move up", "Move down", "Move to top", "Move to bottom")
        AlertDialog.Builder(this).setTitle(r.title)
            .setItems(opts) { _, o ->
                if (o == 0) { setRowVisible(r, !rowVisible(r)); chooseRows(i); return@setItems }
                val how = intArrayOf(RowOrder.UP, RowOrder.DOWN, RowOrder.TOP, RowOrder.BOTTOM)[o - 1]
                val moved = RowOrder.move(all.map { it.title }, i, how)
                saveOrder(moved)
                chooseRows(moved.indexOf(r.title))
            }
            .setOnCancelListener { chooseRows(i) }
            .show()
    }

    // ---- accounts (tokens arrive via tools/connect.ps1, which drops a JSON file into our private storage) ----

    private fun connected(svc: String) = prefs.contains("${svc}_refresh")

    private fun importTokens() {
        // Jellyseerr: address + API key, dropped in by tools/connect.ps1 -Service jellyseerr
        java.io.File(filesDir, "jellyseerr.json").takeIf { it.exists() }?.let { f ->
            runCatching {
                val j = org.json.JSONObject(f.readText())
                prefs.edit().putString("seerr_url", j.getString("url").trimEnd('/')).putString("seerr_key", j.getString("api_key")).apply()
            }.onFailure { toast("Couldn't import Jellyseerr settings: ${it.message}") }
            f.delete()
        }
        listOf("google", "spotify").forEach { svc ->
            val f = java.io.File(filesDir, "$svc.json")
            if (!f.exists()) return@forEach
            runCatching {
                val j = org.json.JSONObject(f.readText())
                prefs.edit().apply {
                    putString("${svc}_client_id", j.getString("client_id"))
                    j.optString("client_secret").takeIf { it.isNotEmpty() }?.let { putString("${svc}_client_secret", it) }
                    putString("${svc}_refresh", j.getString("refresh_token"))
                    remove("yt_subs")
                }.apply()
            }.onFailure { toast("Couldn't import $svc login: ${it.message}") }
            f.delete()
        }
    }

    private val accessTokens = HashMap<String, Pair<String, Long>>()

    /** Cached access token, refreshed ~10 min before Google/Spotify's 1-hour expiry. Called off the main thread. */
    @Synchronized private fun token(svc: String): String? {
        accessTokens[svc]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val refresh = prefs.getString("${svc}_refresh", null) ?: return null
        val form = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to prefs.getString("${svc}_client_id", "")!!)
        prefs.getString("${svc}_client_secret", null)?.let { form["client_secret"] = it }
        val url = if (svc == "google") "https://oauth2.googleapis.com/token" else "https://accounts.spotify.com/api/token"
        val (access, rotated) = Sources.refresh(url, form)
        rotated?.let { prefs.edit().putString("${svc}_refresh", it).apply() }
        accessTokens[svc] = access to System.currentTimeMillis() + 50 * 60_000
        return access
    }

    /** Channel ids you subscribe to; cached a day because the list costs API quota (the RSS feeds don't). */
    @Synchronized private fun subChannelIds(): List<String> {
        val fresh = System.currentTimeMillis() - prefs.getLong("yt_subs_at", 0) < 24 * 3600_000
        return (prefs.getString("yt_subs", null)?.takeIf { fresh }?.split(",")
            ?: Sources.youtubeSubscriptions(token("google")!!).map { it.first }.also {
                prefs.edit().putString("yt_subs", it.joinToString(",")).putLong("yt_subs_at", System.currentTimeMillis()).apply()
            }).filter { it.isNotEmpty() }
    }

    private var subsVideos: Pair<Int, List<Item>>? = null

    /** Latest uploads from your subscriptions, fetched once per home load and shared by both YouTube rows. */
    private fun youtubeSubs(): List<Item> = synchronized(this) {
        subsVideos?.takeIf { it.first == gen }?.second
            ?: Sources.latestFromChannels(subChannelIds()).also { subsVideos = gen to it }
    }

    private fun connectCard(source: Source, pkg: String, onClick: () -> Unit) =
        card(Item(source, "Connect ${source.label}", Play.App(pkg), wide = true,
            icon = runCatching { packageManager.getApplicationBanner(pkg) ?: packageManager.getApplicationIcon(pkg) }.getOrNull()))
            .apply { setOnClickListener { onClick() } }

    private fun connectHelp(name: String) {
        AlertDialog.Builder(this).setTitle("Connect $name")
            .setMessage("On your PC, run tools\\connect.ps1 from the joint-launcher folder and sign in to $name in the browser it opens.\n\nThe script sends the login straight to this TV; then reopen Joint Launcher.")
            .setPositiveButton("OK", null).show()
    }

    // ---- now playing (any app's media session; needs notification access, see README) ----

    private var nowStrip: LinearLayout? = null
    private var nowViews: List<View> = emptyList()
    private var controller: android.media.session.MediaController? = null
    private val sessionsListener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { pickSession(it.orEmpty()) }
    private val mediaCallback = object : android.media.session.MediaController.Callback() {
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = renderNowPlaying()
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = renderNowPlaying()
    }

    private fun watchSessions() {
        try {
            val msm = getSystemService(android.media.session.MediaSessionManager::class.java)
            val me = android.content.ComponentName(this, NowPlayingListener::class.java)
            pickSession(msm.getActiveSessions(me))
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, main)
        } catch (_: SecurityException) { /* notification access not granted: no Now Playing tile */ }
    }

    private fun pickSession(list: List<android.media.session.MediaController>) {
        controller?.unregisterCallback(mediaCallback)
        controller = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        controller?.registerCallback(mediaCallback, main)
        renderNowPlaying()
    }

    private fun renderNowPlaying() {
        val strip = nowStrip ?: return
        strip.removeAllViews()
        val c = controller
        val state = c?.playbackState?.state
        val active = c != null && c.packageName != packageName &&
            (state == android.media.session.PlaybackState.STATE_PLAYING || state == android.media.session.PlaybackState.STATE_PAUSED)
        nowViews.forEach { it.visibility = if (active) View.VISIBLE else View.GONE }
        if (!active) return
        val md = c!!.metadata
        val art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val source = when (c.packageName) {
            Links.SPOTIFY -> Source.SPOTIFY; Links.NETFLIX -> Source.NETFLIX; Links.SMARTTUBE -> Source.YOUTUBE
            Links.JELLYFIN -> Source.JELLYFIN; Links.STREMIO -> Source.STREMIO; else -> Source.APP
        }
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString() ?: source.label
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle?.toString().orEmpty()
        val paused = state == android.media.session.PlaybackState.STATE_PAUSED
        (nowViews.first() as TextView).text = (if (paused) "❚❚ Paused" else "▶ Now playing") + " · " + source.label
        strip.addView(card(Item(source, title, Play.App(c.packageName), subtitle = artist, wide = true,
            icon = art?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                ?: runCatching { packageManager.getApplicationIcon(c.packageName) }.getOrNull())).apply {
            // tap = back to the app that's playing; play/pause keys on the remote still work from here
            setOnClickListener { runCatching { c.sessionActivity?.send() ?: launch(c.packageName) } }
        })
    }

    private fun nowSection() {
        nowStrip = section("Now playing")
        nowViews = listOf(rows.getChildAt(rows.childCount - 2), rows.getChildAt(rows.childCount - 1))
        renderNowPlaying()
    }

    private fun search(q: String) {
        gen++; inSearch = true; rows.removeAllViews(); nowStrip = null; pendingRenders.clear(); failedRows.clear()
        railDirty = true
        buildTabs() // no tab is selected while showing results
        if (jfKey.isNotEmpty() && installed(Links.JELLYFIN)) row("In your library") {
            jf("/Items?userId=%s&searchTerm=${Uri.encode(q)}&recursive=true&includeItemTypes=Movie,Series,Episode&limit=20&fields=Overview,ProviderIds,CriticRating")
        }
        providers().forEach { p -> row("On ${p.source.label}") { Sources.streaming(p, country, q) } }
        if (installed(Links.STREMIO)) {
            row("Movies on Stremio") { Sources.cinemeta("movie", q) }
            row("Series on Stremio") { Sources.cinemeta("series", q) }
        }
        if (installed(Links.SMARTTUBE)) row("YouTube") { Sources.youtube(q, country) }
        showHero(null, "Results for “$q”")
    }

    private var searchBar: android.widget.PopupWindow? = null

    /**
     * Slim search bar that slides out beside the sidebar's magnifying glass, floating over the screen (nothing behind
     * it is hidden). Type and press Search on the keyboard, or press Right to reach the mic and say it.
     */
    private fun askSearch() {
        if (railDirty) { buildRail(); railDirty = false }
        showRail()
        val anchor = rail.findViewWithTag<View>(HOME_TAG + "search") ?: return
        val box = EditText(this).apply {
            id = View.generateViewId()
            hint = "Search movies, series, channels…"; isSingleLine = true; textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(dp(4), 0, dp(8), 0)
        }
        val mic = iconButton(R.drawable.ic_mic, "Voice search") { voiceSearch() }.apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        box.nextFocusRightId = mic.id // Right from the text goes to the microphone
        mic.nextFocusLeftId = box.id
        val glass = ImageView(this).apply { setImageResource(R.drawable.ic_search); setColorFilter(TEXT_MUTED) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xEB1A1B21.toInt()); setStroke(dp(1), 0x40FFFFFF) }
            elevation = dp(10).toFloat()
            addView(glass, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(box, LinearLayout.LayoutParams(dp(360), dp(40)))
            addView(mic)
            // the phone-remote bridge only types from a phone; offer Android's keyboard chooser to pick the TV keyboard
            val currentIme = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            if ("ImeBridge" in currentIme) addView(iconButton(R.drawable.ic_keyboard, "Choose keyboard") {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        val popup = android.widget.PopupWindow(bar, -2, -2, true).apply {
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
            setOnDismissListener { searchBar = null }
        }
        fun go(q: String) { q.trim().takeIf { it.isNotEmpty() }?.let { popup.dismiss(); hideRail(); search(it) } }
        box.setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        searchBar = popup
        bar.measure(0, 0)
        popup.showAsDropDown(anchor, anchor.width + dp(8), -(anchor.height + bar.measuredHeight) / 2)
        // slide out from the sidebar
        bar.translationX = -dp(24).toFloat(); bar.alpha = 0f
        bar.animate().translationX(0f).alpha(1f).setDuration(180).start()
        box.requestFocus()
        // open the TV's own on-screen keyboard (remote-driven) right away, instead of waiting for OK on the field
        box.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }, 200)
    }

    /** Google TV's own voice input (what Stremio uses too); the spoken words come back in onActivityResult. */
    private fun voiceSearch() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say what you want to watch")
        try { @Suppress("DEPRECATION") startActivityForResult(i, VOICE_REQUEST) }
        catch (_: ActivityNotFoundException) { toast("Voice search isn't available on this TV") }
    }

    @Deprecated("Activity result API needs androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE_REQUEST || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        searchBar?.dismiss()
        hideRail()
        search(said)
    }

    private fun jfUser() = userId ?: prefs.getString("uid", null) ?: Sources.jellyfinUser(server, jfKey).also { userId = it }

    private fun jf(path: String): List<Item> = Sources.jellyfin(server, jfKey, path.format(jfUser()))

    // disabled counts as not installed: TVs ship apps like Prime Video preinstalled but switched off
    private fun installed(pkg: String) = runCatching { packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

    private fun providers() = PROVIDERS.filter { installed(it.pkg) }

    /** "Your apps": every installed TV app, laid out like Google TV's apps row. */
    private fun appRow() {
        val strip = section("Your apps")
        scheduleRender(rows.getChildAt(rows.childCount - 2)) { fillAppRow(strip) } // far down: build when scrolled near
    }

    private var railDirty = true
    private val iconCache = HashMap<String, Drawable>()

    /** Other apps' icons come from their APKs, which is slow on this TV; load them off the UI thread, once. */
    /** Our own icons for a few apps whose TV icons are banners or hard to read at sidebar size. */
    private fun customIcon(pkg: String?): Drawable? = when (pkg) {
        Links.NETFLIX -> getDrawable(R.drawable.ic_netflix)
        Links.SMARTTUBE -> getDrawable(R.drawable.ic_youtube)
        PROVIDERS.first { it.source == Source.PRIME }.pkg -> primeIcon()
        else -> null
    }

    /** "prime video" in white on Prime's light blue (a wordmark, so drawn as text rather than a vector). */
    private fun primeIcon(): Drawable {
        val s = 192
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(Source.PRIME.color)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = android.graphics.Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        p.textSize = 56f; c.drawText("prime", s / 2f, s / 2f, p)
        p.textSize = 40f; p.typeface = Typeface.create("sans-serif", Typeface.NORMAL); c.drawText("video", s / 2f, s / 2f + 46f, p)
        return android.graphics.drawable.BitmapDrawable(resources, b)
    }

    private fun appIconInto(pkg: String, into: ImageView) {
        customIcon(pkg)?.let { into.setImageDrawable(it); return }
        iconCache[pkg]?.let { into.setImageDrawable(it); return }
        into.tag = "icon:$pkg"
        imagePool.execute {
            val d = appIcon(pkg) ?: return@execute
            main.post { iconCache[pkg] = d; if (into.tag == "icon:$pkg") into.setImageDrawable(d) }
        }
    }

    private fun fillAppRow(strip: LinearLayout) {
        // reading ~25 app names from their packages took 1.6s on the UI thread; do it on the disk thread
        diskPool.execute { val apps = allApps(); main.post { drawAppRow(strip, apps) } }
    }

    private fun drawAppRow(strip: LinearLayout, apps: List<SideApp>) {
        strip.removeAllViews()
        apps.forEach { a ->
            lateinit var tile: View
            tile = appTile(a) {
                holdMenu(tile, beside = false, listOf(
                    "Open" to { a.pkg?.let(::launch) ?: Unit },
                    "Move" to { startMove(tile, strip, horizontal = true, host = (tile as ViewGroup).getChildAt(0) as FrameLayout) },
                    "App info" to {
                        a.pkg?.let { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it"))) } ?: Unit
                    },
                ))
            }
            strip.addView(tile)
        }
    }

    /**
     * Google TV look (measured from its "Your apps" row: 76dp tiles ~15dp apart): rounded square app icon,
     * name shown under the focused one only. OK opens the app, long-press reorders.
     */
    private fun appTile(a: SideApp, onLongClick: () -> Unit): View {
        val size = dp(76)
        val icon = ImageView(this).apply {
            a.pkg?.let { appIconInto(it, this) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF2A2B30.toInt()) }
        }
        val ring = View(this).apply {
            background = null // no ring: focus = slightly bigger + name, like Stremio
            visibility = View.INVISIBLE
        }
        val label = text(12f, Color.WHITE).apply {
            text = a.label; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
        }
        val box = FrameLayout(this).apply {
            clipChildren = false
            addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(ring, FrameLayout.LayoutParams(size + dp(6), size + dp(6), Gravity.CENTER))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            tag = a.key; contentDescription = a.label
            isFocusable = true; isClickable = true
            addView(box, LinearLayout.LayoutParams(size + dp(6), size + dp(6)))
            addView(label, LinearLayout.LayoutParams(size + dp(30), -2).apply { topMargin = dp(4) })
            setOnFocusChangeListener { v, f ->
                box.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start() // same focus look as cards
                ring.visibility = if (f) View.VISIBLE else View.INVISIBLE
                label.visibility = if (f) View.VISIBLE else View.INVISIBLE
                if (f) lastContentFocus = v
            }
            setOnClickListener { a.pkg?.let(::launch) }
            setOnLongClickListener { onLongClick(); true }
            setOnKeyListener { v, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN && isFirstInRow(v)) { focusRail(); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(size + dp(30), -2).apply { marginEnd = dp(0) }
        }
    }

    /** A titled row filled in the background; it disappears if every source comes back empty. */
    private fun row(title: String, fetch: () -> List<Item>) = row(title, listOf(fetch))

    /** top10 = Netflix-style: first ten items with a big outlined rank number beside each poster. */
    /**
     * Stale-while-revalidate, like Google TV: the row's last saved results are drawn immediately, then refreshed in
     * the background and redrawn only if they changed. Search rows aren't cached (titles repeat across queries).
     */
    private fun row(title: String, fetchers: List<() -> List<Item>>, top10: Boolean = false) {
        val strip = section(title)
        val header = rows.getChildAt(rows.childCount - 2)
        val scroller = rows.getChildAt(rows.childCount - 1)
        val myGen = gen
        val cacheFile = if (inSearch) null else java.io.File(cacheDir, "rows/" + title.hashCode().toString(16) + (appFilter ?: "") + ".json")
        var shown: String? = null // what's on screen; only touched on the UI thread
        val cap = if (appFilter == null && tab == Tab.HOME) 20 else 40 // app views and category tabs go deeper; Home stays light
        fun draw(items: List<Item>) {
            val focusAt = (0 until strip.childCount).indexOfFirst { strip.getChildAt(it).hasFocus() }
            strip.removeAllViews()
            if (top10) items.take(10).forEachIndexed { i, it -> strip.addView(ranked(i + 1, card(it))) }
            else items.take(cap).forEach { strip.addView(card(it)) }
            if (focusAt >= 0) strip.getChildAt(minOf(focusAt, strip.childCount - 1))?.requestFocus() // keep your place
            // first row to appear takes focus; posted, because a request made mid-layout can be dropped
            if (strip.childCount > 0) strip.post {
                if (currentFocus == null && !detail.isShown) strip.getChildAt(0)?.requestFocus()
            }
        }
        fun render(items: List<Item>) = scheduleRender(header) { draw(items) }
        // saved results: read + decoded on the disk thread (doing ~25 of these on the UI thread cost ~1.5s at startup)
        if (cacheFile != null) diskPool.execute {
            val json = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return@execute
            val items = runCatching { ItemJson.decode(json) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@execute
            main.post { if (myGen == gen && shown == null) { shown = json; render(items) } }
        }
        var loading = false
        var attempt = 0
        lateinit var retry: Runnable
        fun load() {
            loading = true
            pool.execute {
                val results = fetchers.map { runCatching(it) }
                val items = results.flatMap { it.getOrNull().orEmpty() }
                val errors = results.mapNotNull { it.exceptionOrNull() }
                val fresh = ItemJson.encode(items)
                if (items.isNotEmpty() && cacheFile != null) runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(fresh) }
                main.post {
                    loading = false
                    if (myGen != gen) return@post
                    if (items.isNotEmpty()) { failedRows.remove(header); if (fresh != shown) render(items) }
                    else if (shown == null) { // nothing cached and nothing fetched: hide the row, or say why
                        if (errors.isEmpty()) { failedRows.remove(header); rows.removeView(header); rows.removeView(scroller); return@post }
                        // e.g. Jellyfin answers 503 for minutes while it starts: retry with backoff, and on every return to Home
                        val wait = 15_000L shl attempt++.coerceAtMost(4) // 15s, 30s, 1m, 2m, then every 4m
                        strip.removeAllViews() // the previous attempt's error
                        errors.map(Sources::explain).distinct().forEach {
                            strip.addView(text(14f, 0xFFFF8080.toInt()).apply {
                                text = "$it. Trying again in ${if (wait < 60_000) "${wait / 1000}s" else "${wait / 60_000} min"}."
                            })
                        }
                        failedRows[header] = retry
                        main.removeCallbacks(retry)
                        main.postDelayed(retry, wait)
                    }
                }
            }
        }
        retry = Runnable { if (myGen == gen && shown == null && !loading) load() }
        load()
    }

    /** Rows showing "Couldn't load" (nothing cached), by header -> their retry. */
    private val failedRows = LinkedHashMap<View, Runnable>()

    override fun onResume() {
        super.onResume()
        failedRows.values.toList().forEach { main.removeCallbacks(it); it.run() }
    }

    // ---- lazy row building: only rows near the screen get their cards built (Google TV does the same) ----

    private lateinit var rowsScroll: ScrollView
    private val pendingRenders = LinkedHashMap<View, () -> Unit>() // row header -> latest draw for that row

    private fun scheduleRender(header: View, draw: () -> Unit) {
        pendingRenders[header] = draw
        main.post { flushRenders() } // after layout, so header positions are known
    }

    /** Build rows whose header is within two screens below the top of the visible area. */
    private fun flushRenders() {
        if (pendingRenders.isEmpty()) return
        if (rowsScroll.height == 0) return // not laid out yet; the layout listener calls back
        pendingRenders.keys.removeAll { it.parent == null } // rows removed by a reload
        val limit = rowsScroll.scrollY + rowsScroll.height * 5 / 4
        // only laid-out headers have a real position (fresh ones all report top = 0)
        val next = pendingRenders.entries.firstOrNull { (h, _) -> h.isLaidOut && h.top <= limit } ?: return
        pendingRenders.remove(next.key)
        next.value()
        // one row per frame: building several at once is what stalled the screen
        if (pendingRenders.isNotEmpty()) main.post { flushRenders() }
    }

    /** Big outlined rank number tucked behind the left edge of a card. */
    private fun ranked(n: Int, card: View) = LinearLayout(this).apply {
        clipChildren = false; gravity = Gravity.BOTTOM
        addView(text(84f, 0xFF7A7D86.toInt()).apply {
            text = n.toString(); typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
            includeFontPadding = false; letterSpacing = -0.05f
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = -dp(16); bottomMargin = -dp(6) })
        addView(card)
    }

    private fun section(title: String): LinearLayout {
        rows.addView(text(14f, 0xFFE4E5EA.toInt()).apply {
            text = title; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.02f
            setPadding(dp(8), dp(4), 0, 0)
        })
        val strip = LinearLayout(this).apply { clipChildren = false; setPadding(dp(8), dp(8), dp(8), dp(10)); tag = STRIP }
        rows.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip)
        })
        return strip
    }

    // ---- hero / detail ----

    private var pendingHero: Item? = null
    private val heroUpdate = Runnable { showHero(pendingHero) }
    /** Netflix-style label over the title, e.g. "NETFLIX · SERIES". */
    private fun kicker(item: Item): String {
        val uri = ((item.alt ?: item.play) as? Play.Link)?.uri.orEmpty()
        val kind = when {
            "/series/" in uri -> "SERIES"
            "/movie/" in uri -> "MOVIE"
            item.play is Play.JellyLive -> "LIVE TV"
            item.source == Source.JELLYFIN && item.subtitle.startsWith("S") && "E" in item.subtitle.take(6) -> "EPISODE"
            item.source == Source.LIVE -> if (item.subtitle.startsWith("●")) "LIVE NOW" else "UPCOMING"
            else -> null
        }
        return listOfNotNull(item.source.label.uppercase(), kind).joinToString("  ·  ")
    }

    private fun showHero(item: Item?, fallbackTitle: String = "") {
        heroKicker.text = item?.let(::kicker).orEmpty()
        heroTitle.text = item?.title ?: fallbackTitle
        heroDesc.text = item?.description.orEmpty()
        fillMeta(heroMeta, item)
        item?.let { withRatings(it) { if (pendingHero === it) fillMeta(heroMeta, it) } }
        val img = item?.backdrop ?: item?.poster.takeIf { item?.wide == true }
        if (img == null) { heroImg.tag = null; heroImg.setImageDrawable(null) } else loadImage(img, heroImg)
    }

    // ---- ratings: from the source when it has them, otherwise looked up once per IMDb id ----

    private val ratingsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Double?, Int?>>()

    private fun ratingsOf(item: Item): Pair<Double?, Int?> {
        val cached = item.imdbId?.let { ratingsCache[it] }
        return (item.imdb ?: cached?.first) to (item.rt ?: cached?.second)
    }

    private fun withRatings(item: Item, then: () -> Unit) {
        val id = item.imdbId ?: return
        if ((item.imdb != null && item.rt != null) || ratingsCache.containsKey(id)) return
        pool.execute {
            ratingsCache[id] = runCatching { Sources.ratings(item.title, id, country) }.getOrNull() ?: (null to null)
            main.post(then)
        }
    }

    /** Source badge, IMDb and Rotten Tomatoes chips, then the subtitle. */
    private fun fillMeta(row: LinearLayout, item: Item?, big: Boolean = false) {
        row.removeAllViews()
        item ?: return
        val size = if (big) 14f else 12f
        fun add(v: View) = row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        add(badge(size).apply { text = item.source.label; paint(this, item.source) })
        val (imdb, rt) = ratingsOf(item)
        imdb?.let { add(chip("IMDb  ${"%.1f".format(Locale.US, it)}", IMDB_YELLOW, Color.BLACK, size)) }
        rt?.let { add(chip(if (it >= 60) "🍅  $it%" else "RT  $it%", if (it >= 60) RT_FRESH else RT_ROTTEN, Color.WHITE, size)) }
        if (item.subtitle.isNotEmpty()) add(text(size + 1, TEXT_MUTED).apply { text = item.subtitle; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    private var detailItem: Item? = null

    /** Where Play goes: the service's own app when installed (and enabled), otherwise the same title in Stremio. */
    private fun playTarget(item: Item): Pair<String, Play> {
        val p = item.play
        val appInstalled = (p as? Play.Link)?.pkg?.let(::installed) ?: true
        val alt = item.alt
        return when {
            !appInstalled && alt != null -> "Play in Stremio" to alt
            item.source == Source.LIVE -> "Watch on GeeSports" to p
            p is Play.JellyLive -> "Watch live" to p
            item.source == Source.YOUTUBE -> "Play in SmartTube" to p
            else -> "Play on ${item.source.label}" to p
        }
    }

    /**
     * Netflix-style info screen shown before handing off to any app: logo/title, ratings, seasons or runtime,
     * the full description, cast/creators, awards, Play and (when there is one) Trailer.
     */
    private fun showDetail(item: Item) {
        lastFocused = currentFocus
        detailItem = item
        detail.removeAllViews()
        val width = resources.displayMetrics.widthPixels
        val bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        (item.backdrop ?: item.poster)?.let { loadImage(it, bg) }

        val actions = LinearLayout(this).apply { setPadding(0, dp(22), 0, 0) }
        when (val p = item.play) {
            is Play.Jelly -> if (p.folder) actions.addView(button("Open in Jellyfin") { open(p) }) else {
                actions.addView(button(if (p.posMs > 0) "▶  Resume" else "▶  Play") { open(p) })
                if (p.posMs > 0) actions.addView(button("From start") { open(p.copy(posMs = 0)) })
            }
            else -> playTarget(item).let { (label, target) -> actions.addView(button("▶  $label") { open(target) }) }
        }
        seerrButton(item)?.let(actions::addView)
        (item.play as? Play.JellyLive)?.let { live ->
            var fav = item.fav
            actions.addView(button(if (fav) "★  Favourite" else "☆  Add to favourites") {}.apply {
                setOnClickListener {
                    val want = !fav
                    pool.execute {
                        val r = runCatching { Sources.jellyfinFavourite(server, jfKey, jfUser(), live.channelId, want) }
                        main.post {
                            r.onSuccess { fav = want; text = if (fav) "★  Favourite" else "☆  Add to favourites" }
                                .onFailure { toast("Couldn't update favourites: ${it.message}") }
                        }
                    }
                }
            })
        }

        val kicker = text(12f, ACCENT).apply { text = kicker(item); typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = 0.18f }
        val title = text(36f, Color.WHITE).apply {
            text = item.title; typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.01f
            setShadowLayer(16f, 0f, 2f, 0x99000000.toInt())
        }
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        val meta = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fillMeta(meta, item, big = true)
        withRatings(item) { if (detailItem === item) fillMeta(meta, item, big = true) }
        val facts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val desc = text(15f, TEXT_SOFT).apply { text = item.description; maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }
        val credits = text(13f, TEXT_MUTED).apply { visibility = View.GONE; maxLines = 2 }
        val awards = text(12f, ACCENT).apply { visibility = View.GONE; maxLines = 1 }

        detail.addView(bg, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(this).apply { background = fade() }, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(BG, 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        detail.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(56), 0, 0, dp(48))
            addView(kicker)
            addView(title, LinearLayout.LayoutParams((width * 0.6).toInt(), -2))
            addView(logo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(desc, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = dp(12) })
            addView(credits, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = dp(8) })
            addView(awards, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = dp(4) })
            addView(actions)
        }, FrameLayout.LayoutParams(-1, -1))
        detail.visibility = View.VISIBLE
        actions.getChildAt(0).requestFocus()
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS // the rows behind stay focusable otherwise, and D-pad wanders onto them

        // the extended info comes from Stremio's catalogue, by IMDb id
        val id = item.imdbId?.takeIf { it.startsWith("tt") } ?: return
        val type = item.type ?: return
        pool.execute {
            val m = runCatching { Sources.meta(type, id) }.getOrNull() ?: return@execute
            main.post {
                if (detailItem !== item) return@post
                if (m.seasons > 0) facts.addView(pill(
                    "${m.seasons} Season${if (m.seasons > 1) "s" else ""}" + if (m.episodes > 0) "  ·  ${m.episodes} Episodes" else ""
                ))
                else m.runtime?.let { facts.addView(pill(prettyRuntime(it))) }
                m.genres.takeIf { it.isNotEmpty() }?.let { facts.addView(pill(it.joinToString("  ·  "))) }
                m.description?.takeIf { it.length > desc.text.length }?.let { desc.text = it }
                val castLine = m.cast.takeIf { it.isNotEmpty() }?.let { "Starring ${it.joinToString(", ")}" }
                val byLine = m.creators.takeIf { it.isNotEmpty() }?.let { (if (type == "series") "Created by " else "Directed by ") + it.joinToString(", ") }
                listOfNotNull(castLine, byLine).takeIf { it.isNotEmpty() }?.let { credits.text = it.joinToString("     "); credits.visibility = View.VISIBLE }
                m.awards?.let { awards.text = "🏆  $it"; awards.visibility = View.VISIBLE }
                m.logo?.let { url -> loadImage(url, logo); logo.maxHeight = dp(90); logo.maxWidth = (width * 0.4).toInt(); logo.visibility = View.VISIBLE; title.visibility = View.GONE }
                m.trailerYt?.let { yt -> actions.addView(button("Trailer") { open(Play.Link(Links.youtube(yt), Links.SMARTTUBE)) }) }
            }
        }
    }

    /**
     * "Request in Jellyseerr" for movies, series and albums that aren't already in your library. Starts as "Checking…",
     * then shows the real state (Request / Requested / Request the rest), or disappears if it's available.
     * Albums are found on MusicBrainz first (exact title + artist), since that's what this Jellyseerr requests music by.
     */
    private fun seerrButton(item: Item): View? {
        val base = prefs.getString("seerr_url", null) ?: return null
        val key = prefs.getString("seerr_key", null) ?: return null
        val type = item.type ?: return null
        if (item.source == Source.JELLYFIN) return null // already in your library
        val album = type == "album"
        if (album && item.artist == null) return null
        if (!album && item.tmdbId == null) return null
        var mediaId: String? = item.tmdbId
        var status: Int? = -1
        lateinit var b: TextView
        b = button("Checking Jellyseerr…") {
            val id = mediaId ?: return@button
            if (status == 2 || status == 3 || status == -1) return@button
            b.text = "Requesting…"
            pool.execute {
                val r = runCatching { Sources.seerrRequest(base, key, type, id) }
                main.post {
                    r.onSuccess { status = 2; b.text = Sources.seerrLabel(2) }
                        .onFailure { b.text = Sources.seerrLabel(status); toast("Jellyseerr request failed: ${it.message}") }
                }
            }
        }
        pool.execute {
            val s = runCatching {
                val id = mediaId ?: Sources.mbAlbumId(item.artist!!, item.title)?.also { mediaId = it }
                    ?: return@runCatching -2 // album not on MusicBrainz: nothing Jellyseerr could request
                Sources.seerrStatus(base, key, type, id)
            }
            main.post {
                if (detailItem !== item) return@post
                s.onSuccess { st ->
                    status = st
                    if (st == -2) b.visibility = View.GONE
                    else Sources.seerrLabel(st)?.let { b.text = it } ?: run { b.visibility = View.GONE }
                }.onFailure { b.text = "Jellyseerr unavailable"; status = 3 }
            }
        }
        return b
    }

    /** "167 min" -> "2h 47m". */
    private fun prettyRuntime(r: String): String {
        val min = r.filter(Char::isDigit).toIntOrNull() ?: return r
        return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    }

    /** Thin outlined label for facts like "2 Seasons · 16 Episodes" or "2h 47m". */
    private fun pill(label: String) = text(13f, Color.WHITE).apply {
        text = label; setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), 0x80FFFFFF.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }

    private fun closeDetail() {
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        detail.visibility = View.GONE; detailItem = null; lastFocused?.requestFocus()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        when {
            detail.isShown -> closeDetail()
            rail.hasFocus() -> backToContent()
            inSearch -> loadHome()
            appFilter != null -> pickApp(null) // single-app view -> back to everything
            tab != Tab.HOME -> selectTab(Tab.HOME) // content-type tab -> back to Home
            // a home screen doesn't exit to nowhere: Back goes to the top, Back again opens the sidebar on Search
            android.os.SystemClock.uptimeMillis() - lastBackAt < 2000 -> { lastBackAt = 0; focusRail(on = HOME_TAG + "search") }
            else -> {
                lastBackAt = android.os.SystemClock.uptimeMillis()
                rowsScroll.smoothScrollTo(0, 0)
                rows.getFocusables(View.FOCUS_DOWN).firstOrNull()?.requestFocus()
            }
        }
    }

    private var lastBackAt = 0L

    /** Home pressed while we're the home screen: close whatever is open and go back to the top of the home view. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            searchBar?.dismiss()
            if (moving != null) endMove()
            if (detail.isShown) closeDetail()
            hideRail()
            if (inSearch || appFilter != null || tab != Tab.HOME) { tab = Tab.HOME; pickApp(null) } else rowsScroll.smoothScrollTo(0, 0)
        }
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_SEARCH) { askSearch(); return true }
        return super.onKeyDown(code, e)
    }

    // ---- actions ----

    // every title gets the info screen first; only app tiles open straight away
    private fun click(item: Item) = if (item.source == Source.APP) open(item.play) else showDetail(item)

    private fun open(p: Play) {
        when (p) {
            is Play.App -> launch(p.pkg)
            is Play.Jelly -> if (p.folder) launch(Links.JELLYFIN) else startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("url", Links.jfStream(server, p.id)).putExtra("key", jfKey).putExtra("pos", p.posMs)
            )
            is Play.Link -> try {
                val i = if (p.uri.startsWith("intent:")) Intent.parseUri(p.uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null; selector = null // remote data: only ever let it reach the target app
                } else Intent(Intent.ACTION_VIEW, Uri.parse(p.uri))
                startActivity(i.setPackage(p.pkg))
            } catch (_: ActivityNotFoundException) { launch(p.pkg) }
            is Play.JellyLive -> {
                toast("Tuning in…")
                pool.execute {
                    val url = runCatching { Sources.jellyfinLiveUrl(server, jfKey, jfUser(), p.channelId) }
                    main.post {
                        url.onSuccess { startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", it).putExtra("key", jfKey)) }
                            .onFailure { toast("Couldn't start this channel: ${it.message}") }
                    }
                }
            }
            is Play.StremioSearch -> {
                toast("Looking for “${p.title}” in Stremio…")
                pool.execute {
                    val id = runCatching { Sources.stremioFind(p.title, p.year, p.type) }.getOrNull()
                    main.post {
                        if (id != null) open(Play.Link(Links.stremioDetail(p.type, id), Links.STREMIO))
                        else toast("Couldn't find “${p.title}” in Stremio")
                    }
                }
            }
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else toast("$pkg is not installed")
    }

    private fun quickConnect() {
        val device = "tvhub-" + android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val msg = text(20f, Color.WHITE).apply { text = "Contacting $server…"; setPadding(dp(24), dp(16), dp(24), 0) }
        var waiting = true
        val dialog = AlertDialog.Builder(this).setTitle("Connect Jellyfin").setView(msg)
            .setNegativeButton("Cancel", null).setOnDismissListener { waiting = false }.show()
        pool.execute {
            val r = runCatching {
                val (secret, code) = Sources.quickConnectStart(server, device)
                main.post { msg.text = "On your phone or the Jellyfin website go to\nProfile → Quick Connect and enter:\n\n$code" }
                while (waiting && !Sources.quickConnectApproved(server, device, secret)) Thread.sleep(3000)
                if (waiting) Sources.quickConnectFinish(server, device, secret) else null
            }
            main.post {
                r.onSuccess { auth ->
                    auth ?: return@onSuccess
                    prefs.edit().putString("key", auth.first).putString("uid", auth.second).apply()
                    userId = auth.second; dialog.dismiss(); toast("Jellyfin connected"); loadHome()
                }.onFailure { msg.text = "Quick Connect failed: ${it.message}\n(Is Quick Connect enabled in the Jellyfin dashboard?)" }
            }
        }
    }

    private fun settings() {
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val url = field("Jellyfin URL", server)
        val key = field("Jellyfin API key (Dashboard → API Keys)", jfKey)
        val email = field(if (stremioKey.isEmpty()) "Stremio email" else "Stremio: signed in (enter to switch account)", "")
        val pw = field("Stremio password (not stored)", "", password = true)
        val yt = field("YouTube row topic", ytTopic)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
            listOf(url, key, email, pw, yt).forEach(::addView)
        }
        AlertDialog.Builder(this).setTitle("Settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (key.text.toString().trim() != jfKey) prefs.edit().remove("uid").apply() // manual key: look the user up again
                prefs.edit().putString("server", url.text.toString().trim()).putString("key", key.text.toString().trim())
                    .putString("yt", yt.text.toString().trim().ifEmpty { "sports highlights" }).apply()
                userId = null
                val e = email.text.toString().trim(); val p = pw.text.toString()
                if (e.isEmpty() || p.isEmpty()) loadHome() else pool.execute {
                    val r = runCatching { Sources.stremioLogin(e, p) }
                    main.post {
                        r.onSuccess { prefs.edit().putString("stremio", it).apply(); toast("Stremio connected") }
                            .onFailure { toast("Stremio login failed: ${it.message}") }
                        loadHome()
                    }
                }
            }
            .setNeutralButton("Sign out of Stremio") { _, _ -> prefs.edit().remove("stremio").apply(); loadHome() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- views ----

    private fun text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

    /** Badge in the service colour; dark text on light colours (GeeSports yellow) so it stays readable. */
    private fun paint(badge: TextView, s: Source) {
        (badge.background as GradientDrawable).setColor(s.color)
        badge.setTextColor(if (Color.luminance(s.color) > 0.5f) Color.BLACK else Color.WHITE)
    }

    private fun badge(size: Float = 12f) = text(size, Color.WHITE).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
    }

    private fun chip(label: String, bg: Int, fg: Int, size: Float) = badge(size).apply {
        text = label; setTextColor(fg); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        (background as GradientDrawable).setColor(bg)
    }

    private fun button(label: String, onClick: () -> Unit) = text(15f, Color.WHITE).apply {
        text = label; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
        setPadding(dp(24), dp(10), dp(24), dp(10))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x26FFFFFF) })
        }
        isFocusable = true; isClickable = true
        setOnFocusChangeListener { _, f -> setTextColor(if (f) Color.BLACK else Color.WHITE) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) }
    }

    /** Round icon button, same look as [button]: translucent idle, white with a dark icon when focused. */
    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        contentDescription = label // read out by TalkBack
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) })
        }
        isFocusable = true; isClickable = true
        setColorFilter(Color.WHITE)
        setOnFocusChangeListener { _, f -> setColorFilter(if (f) Color.BLACK else Color.WHITE) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) }
    }

    private fun fade() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BG, 0xEB050505.toInt(), 0x00050505))
    private fun fadeDown() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00050505, 0x00050505, BG))

    private fun card(item: Item): View {
        val (w, h) = when {
            item.wide -> dp(176) to dp(99)
            item.square -> dp(118) to dp(118)
            else -> dp(92) to dp(138)
        }
        val img = ImageView(this).apply {
            scaleType = if (item.icon != null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            item.icon?.let(::setImageDrawable)
        }
        // posters carry their own title art (the header shows the name on focus); wide/square cards and missing art get a caption
        val caption = (item.wide || item.square || item.poster == null) && item.source != Source.APP
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = dp(12) }
            addView(img, FrameLayout.LayoutParams(-1, -1))
            if (caption) addView(text(11f, Color.WHITE).apply {
                text = item.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), dp(14), dp(8), dp(5))
                background = tpl.scrim.newDrawable()
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            if (item.source != Source.APP) addView(View(this@MainActivity).apply { background = tpl.dot(item.source.color) },
                FrameLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(6), dp(6), 0, 0) })
            // dim overlay when unfocused, white ring when focused (view-wide alpha forced an offscreen redraw per card)
            foreground = tpl.cardForeground.newDrawable(); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = tpl.cardBg.newDrawable()
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start()
                v.elevation = if (f) dp(12).toFloat() else 0f
                if (f) { lastContentFocus = v; pendingHero = item; main.removeCallbacks(heroUpdate); main.postDelayed(heroUpdate, 200) }
            }
            setOnClickListener { click(item) }
            setOnKeyListener { v, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN && isFirstInRow(v)) { focusRail(); true } else false
            }
            if (item.icon == null) item.poster?.let { loadImage(it, img) }
        }
    }

    /** Drawable templates shared by every card; each card takes a cheap copy via newDrawable(). */
    private inner class Templates {
        private val radius = dp(10).toFloat()
        val cardBg: Drawable.ConstantState = GradientDrawable().apply { cornerRadius = radius; setColor(0xFF1C1D25.toInt()) }.constantState!!
        val scrim: Drawable.ConstantState =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xD9000000.toInt())).constantState!!
        val cardForeground: Drawable.ConstantState = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(0)) // Stremio-style: focus = slightly bigger + shadow, no frame or colour
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0x40000000)) // unfocused cards sit back
        }.constantState!!
        private val dots = HashMap<Int, Drawable.ConstantState>()
        fun dot(color: Int): Drawable = dots.getOrPut(color) {
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(1), 0x66000000) }.constantState!!
        }.newDrawable()
    }
    private val tpl by lazy { Templates() }

    /** big = full-screen/backdrop use (decoded up to 1280px); tiles and logos stay small (~2x their 92-176dp size). */
    private fun loadImage(url: String, into: ImageView, big: Boolean = into === heroImg || detail.isShown) {
        val maxPx = if (big) 1280 else 360
        val key = "$url@$maxPx"
        into.tag = key
        images.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        val headers = if (url.startsWith(server)) Links.jfAuth(jfKey) else emptyMap()
        imagePool.execute {
            val bmp = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 6000; readTimeout = 12000
                    headers.forEach(::setRequestProperty)
                    try { decodeScaled(inputStream.use { it.readBytes() }, maxPx) } finally { disconnect() }
                }
            }.getOrNull() ?: return@execute
            images.put(key, bmp)
            main.post { if (into.tag == key) into.setImageBitmap(bmp) }
        }
    }

    /** Decode at the smallest power-of-two scale that still covers maxPx on the longer side. */
    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        runCatching { getSystemService(android.media.session.MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(mediaCallback)
        pool.shutdownNow(); imagePool.shutdownNow(); super.onDestroy()
    }

    companion object {
        private const val BG = 0xFF050505.toInt()           // near-black, Netflix TV style
        private const val ACCENT = 0xFFE8650A.toInt()       // dark orange (Netflix's red, in orange)
        private const val RAIL_DP = 64
        private const val VOICE_REQUEST = 7
        private const val HOME_TAG = "rail:home"
        /** Your Google TV "Your apps" order, read from its screen on 2026-10-23; the starting order until you reorder. */
        private val GOOGLE_TV_ORDER = listOf(
            "com.spotify.tv.android", "ch.protonvpn.android", "com.stremio.one", "com.live.geesports", "org.smarttube.beta",
            "com.netflix.ninja", "org.jellyfin.androidtv", "com.xiaomi.mitv.tvmanager", "com.yablio.sendfilestotv", "com.filmin.androidtv",
        )
        private val STRIP = Any()                            // tag on row strips, to spot a row's first tile
        private const val TEXT_SOFT = 0xFFC9CBD3.toInt()  // descriptions
        private const val TEXT_MUTED = 0xFF9A9DA8.toInt() // meta lines
        private const val IMDB_YELLOW = 0xFFF5C518.toInt()
        private const val RT_FRESH = 0xFFFA320A.toInt()
        private const val RT_ROTTEN = 0xFF4E8A2E.toInt()
    }
}
