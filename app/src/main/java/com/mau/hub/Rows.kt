package com.mau.hub

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** What the home screen shows, top to bottom. */
sealed class RowModel(val id: Long)

/** A titled row filled in the background; disappears if every source comes back empty. */
class ContentRow(id: Long, val title: String, val top10: Boolean, val cap: Int) : RowModel(id) {
    var items: List<Item> = emptyList()
    var error: String? = null
    var scroll: Parcelable? = null // how far you'd scrolled this row, kept while its view is reused by another row
}

class NowRow(id: Long, val label: String, val item: Item, val onClick: () -> Unit) : RowModel(id)
class ConnectRow(id: Long, val entries: List<Pair<Item, () -> Unit>>) : RowModel(id)
class AppsRow(id: Long) : RowModel(id)
/** Tablet Home: the one button that folds all the video rows below it away. */
class FoldRow(id: Long) : RowModel(id)
/** Tablet Home: your widgets, foldable, with add and remove buttons in the header. */
class WidgetsRow(id: Long) : RowModel(id)
/** Tablet: your Home Assistant dashboard, foldable like the other sections. */
class HaRow(id: Long) : RowModel(id)

/**
 * The home screen's rows, on RecyclerView: only rows near the screen are built, and every content row shares one
 * pool of tiles. Rows load in the background; see [content] for caching and retries.
 */
class Rows(private val a: MainActivity) {
    private val models = mutableListOf<RowModel>()
    private val failed = LinkedHashMap<Long, Runnable>() // rows showing an error (nothing cached) -> their retry
    private var nextId = 0L
    private val tilePool = RecyclerView.RecycledViewPool().apply {
        CardKind.entries.forEach { setMaxRecycledViews(it.ordinal, 40); setMaxRecycledViews(it.ordinal + RANKED, 12) }
    }

    val view = RecyclerView(a).apply {
        layoutManager = LinearLayoutManager(a)
        adapter = RowsAdapter()
        itemAnimator = null // rows update in place; animations would rebuild them and drop focus
        clipChildren = false; clipToPadding = false
        setPadding(a.dp(28), 0, a.dp(36), a.dp(40))
        isVerticalScrollBarEnabled = false
    }

    private val adapter get() = view.adapter as RowsAdapter

    // What's on screen: every row, or without the video rows while they're folded away (tablet Home). Folded rows keep
    // loading in the background, so unfolding is instant. Positions below are positions in this list.
    private var shown: List<RowModel> = emptyList()
    private val folded get() = models.any { it is FoldRow } && a.hub.prefs.getBoolean("videos_folded", false)
    private fun sync() { shown = if (folded) models.filter { it !is ContentRow } else models.toList() }
    private fun indexOf(id: Long) = shown.indexOfFirst { it.id == id }

    fun clear() {
        models.clear(); failed.clear(); sync()
        adapter.notifyDataSetChanged()
    }

    private fun add(m: RowModel, at: Int = models.size) {
        models.add(at, m); sync()
        indexOf(m.id).takeIf { it >= 0 }?.let(adapter::notifyItemInserted)
    }

    private fun remove(m: RowModel) {
        val i = indexOf(m.id)
        models.remove(m); sync()
        if (i >= 0) adapter.notifyItemRemoved(i)
    }

    /** Fold or unfold all the video rows (remembered). */
    private fun toggleFold() {
        a.hub.prefs.edit().putBoolean("videos_folded", !folded).apply()
        sync(); adapter.notifyDataSetChanged()
    }

    /** Rows showing "couldn't load" try again now (the app came back to the front, or Home was pressed). */
    fun retryFailed() = failed.values.toList().forEach { a.hub.main.removeCallbacks(it); it.run() }

    fun top() {
        view.scrollToPosition(0)
        view.post { firstFocusable()?.requestFocus() }
    }

    fun firstFocusable(): View? = view.getFocusables(View.FOCUS_DOWN).firstOrNull()

    // ---- rows ----

    /**
     * Stale-while-revalidate, like Google TV: the row's last saved results show immediately, then refresh in the
     * background and redraw only if they changed. Rows with nothing to show retry with backoff, saying why.
     * cacheKey = null for rows that shouldn't be cached (search results).
     */
    fun content(title: String, fetchers: List<() -> List<Item>>, top10: Boolean, cap: Int, cacheKey: String?) {
        val row = ContentRow(nextId++, title, top10, if (top10) 10 else cap)
        add(row)
        val myGen = a.gen
        val hub = a.hub
        val cacheFile = cacheKey?.let { java.io.File(a.cacheDir, "rows/" + title.hashCode().toString(16) + it + ".json") }
        var cached: String? = null // the JSON on screen; only touched on the UI thread
        fun show(items: List<Item>) {
            row.items = items.take(row.cap); row.error = null
            indexOf(row.id).takeIf { it >= 0 }?.let(adapter::notifyItemChanged) // folded away: just keep the data
        }
        // saved results: read + decoded on the disk thread (doing ~25 of these on the UI thread cost ~1.5s at startup)
        if (cacheFile != null) hub.diskPool.execute {
            val json = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return@execute
            val items = runCatching { ItemJson.decode(json) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@execute
            hub.main.post { if (myGen == a.gen && cached == null) { cached = json; show(items) } }
        }
        var loading = false
        var attempt = 0
        lateinit var retry: Runnable
        fun load() {
            loading = true
            hub.pool.execute {
                val results = fetchers.map { runCatching(it) }
                val items = results.flatMap { it.getOrNull().orEmpty() }
                val errors = results.mapNotNull { it.exceptionOrNull() }
                val fresh = ItemJson.encode(items)
                if (items.isNotEmpty() && cacheFile != null) runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(fresh) }
                hub.main.post {
                    loading = false
                    if (myGen != a.gen || row !in models) return@post
                    if (items.isNotEmpty()) { failed.remove(row.id); if (fresh != cached) { cached = fresh; show(items) } }
                    else if (cached == null) { // nothing cached and nothing fetched: hide the row, or say why
                        if (errors.isEmpty()) { failed.remove(row.id); remove(row); return@post }
                        // e.g. Jellyfin answers 503 for minutes while it starts: retry with backoff, and on every return to Home
                        val wait = 15_000L shl attempt++.coerceAtMost(4) // 15s, 30s, 1m, 2m, then every 4m
                        val again = if (wait < 60_000) "${wait / 1000}s" else "${wait / 60_000} min"
                        row.error = errors.map(Sources::explain).distinct().joinToString("\n") { "$it. Trying again in $again." }
                        indexOf(row.id).takeIf { it >= 0 }?.let(adapter::notifyItemChanged)
                        failed[row.id] = retry
                        hub.main.removeCallbacks(retry)
                        hub.main.postDelayed(retry, wait)
                    }
                }
            }
        }
        retry = Runnable { if (myGen == a.gen && cached == null && !loading) load() }
        load()
    }

    /** "Now playing" sits on top while some app is playing; null removes it. */
    fun nowPlaying(row: NowRow?) {
        val old = models.firstOrNull { it is NowRow }
        when {
            row == null && old != null -> remove(old)
            row != null && old != null -> { models[models.indexOf(old)] = row; sync(); adapter.notifyItemChanged(indexOf(row.id)) }
            row != null -> add(row, 0)
        }
    }

    fun connect(entries: List<Pair<Item, () -> Unit>>) { if (entries.isNotEmpty()) add(ConnectRow(nextId++, entries)) }
    fun apps() = add(AppsRow(nextId++))
    fun fold() = add(FoldRow(nextId++))
    fun widgets() = add(WidgetsRow(nextId++))
    fun homeAssistant() = add(HaRow(nextId++))

    // one dashboard for the whole session: rebinding the section must not reload it (or lose your place in it)
    @delegate:android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private val haView by lazy {
        android.webkit.WebView(a).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true // HA's frontend needs both; stays signed in
            webViewClient = android.webkit.WebViewClient() // links open inside the section
            setBackgroundColor(Color.TRANSPARENT)
            background = GradientDrawable().apply { cornerRadius = a.dp(12).toFloat(); setColor(0xFF1C1C1F.toInt()) }
            clipToOutline = true
            // a finger on the dashboard scrolls the dashboard, not Home
            setOnTouchListener { v, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true); false }
            a.hub.pool.execute { val url = a.hub.haUrl(); a.hub.main.post { url?.let(::loadUrl) } }
        }
    }

    /** A widget was added or removed, or edit mode switched: redraw the Widgets section. */
    fun refreshWidgets() { shown.indexOfFirst { it is WidgetsRow }.takeIf { it >= 0 }?.let(adapter::notifyItemChanged) }

    // ---- views ----

    private fun header(title: String) = a.text(14f, 0xFFE4E5EA.toInt()).apply {
        text = title; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.02f
        setPadding(a.dp(8), a.dp(4), 0, 0)
    }

    /**
     * Tablet section header (Apps, To Watch): title on the left, a round dropdown arrow on the right, like a Home
     * Assistant collapsible card. The whole header is the tap target; the arrow points down when folded, up when open.
     */
    private fun foldHeader(title: String, folded: Boolean, extra: List<View> = emptyList(), toggle: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(8), a.dp(16), a.dp(4), a.dp(8))
        isClickable = true; setOnClickListener { toggle() }
        contentDescription = (if (folded) "Show " else "Hide ") + title
        addView(a.text(22f, Color.WHITE).apply { text = title; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, -2, 1f))
        extra.forEach { addView(it, LinearLayout.LayoutParams(a.dp(48), a.dp(48)).apply { marginEnd = a.dp(10) }) }
        addView(roundButton(R.drawable.ic_chevron, if (folded) "Show $title" else "Hide $title") { toggle() }.apply {
            rotation = if (folded) 90f else 270f // ic_chevron points right
        }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
    }

    /** Round 48 dp icon button, the touch target used in the tablet's section headers. */
    private fun roundButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(a).apply {
        setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = label
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun strip() = LinearLayout(a).apply { clipChildren = false; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(10)) }

    /** Left on a row's first tile opens the sidebar. */
    private fun leftOpensSidebar(v: View, isFirst: () -> Boolean) = v.setOnKeyListener { _, code, e ->
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN && isFirst()) { a.sidebar.focus(); true } else false
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class ContentHolder(val box: LinearLayout, val header: TextView, val error: TextView, val strip: RecyclerView) :
        RecyclerView.ViewHolder(box) {
        var row: ContentRow? = null
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = shown.size
        override fun getItemId(position: Int) = shown[position].id
        override fun getItemViewType(position: Int) = when (shown[position]) {
            is ContentRow -> 0; is NowRow -> 1; is ConnectRow -> 2; is AppsRow -> 3; is FoldRow -> 4; is WidgetsRow -> 5; is HaRow -> 6
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType != 0) return Holder(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            })
            val strip = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false).apply { initialPrefetchItemCount = 6 }
                setRecycledViewPool(tilePool)
                itemAnimator = null
                clipChildren = false; clipToPadding = false
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(10))
                isHorizontalScrollBarEnabled = false
            }
            val header = header("")
            val error = a.text(14f, 0xFFFF8080.toInt()).apply { setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(10)); visibility = View.GONE }
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(header); addView(error); addView(strip, LinearLayout.LayoutParams(-1, -2))
            }
            return ContentHolder(box, header, error, strip).also { h -> h.strip.adapter = TilesAdapter(h) }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val m = shown[position]) {
                is ContentRow -> bindContent(holder as ContentHolder, m)
                is NowRow -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    addView(header(m.label))
                    addView(strip().apply {
                        val c = a.cards.create(CardKind.WIDE)
                        a.cards.bind(c, m.item, { v -> a.onTileFocused(v, m.item) }) { m.onClick() }
                        leftOpensSidebar(c.root) { true }
                        addView(c.root)
                    })
                }
                is ConnectRow -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    addView(header("Connect"))
                    addView(HorizontalScrollView(a).apply {
                        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
                        addView(strip().apply {
                            m.entries.forEachIndexed { i, (item, onClick) ->
                                val c = a.cards.create(CardKind.WIDE)
                                a.cards.bind(c, item, { v -> a.onTileFocused(v, item) }) { onClick() }
                                leftOpensSidebar(c.root) { i == 0 }
                                addView(c.root)
                            }
                        })
                    })
                }
                is AppsRow -> (holder.itemView as LinearLayout).apply {
                    if (tag == m.id) return // already built; rebuilding would undo a move in progress
                    tag = m.id
                    removeAllViews()
                    // TV: one sideways row like Google TV's; tablet: a launcher-style grid that wraps to the screen width,
                    // under a foldable "Apps" header like To Watch's (remembered)
                    val strip: ViewGroup = if (a.hub.touch) FlowBox(a).apply { setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(10)) } else strip()
                    if (a.hub.touch) {
                        fun folded() = a.hub.prefs.getBoolean("apps_folded", false)
                        lateinit var head: View
                        fun draw() {
                            strip.visibility = if (folded()) View.GONE else View.VISIBLE
                            val i = indexOfChild(head); removeView(head)
                            // heart: all apps / favourites only (a red heart, and the section is called Favorites)
                            val favsOnly = a.apps.favouritesOnly
                            val heart = roundButton(if (favsOnly) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                                if (favsOnly) "Show all apps" else "Show favorite apps") { a.apps.favouritesOnly = !favsOnly; draw(); a.refreshApps() }
                                .apply { if (favsOnly) setColorFilter(0xFFE53935.toInt()) }
                            head = foldHeader(if (favsOnly) "Favorites" else "Apps", folded(), listOf(heart)) { a.hub.prefs.edit().putBoolean("apps_folded", !folded()).apply(); draw() }
                            addView(head, i, LinearLayout.LayoutParams(-1, -2))
                        }
                        head = View(a); addView(head); draw()
                    } else addView(header("Your apps"))
                    if (a.hub.touch) addView(strip) else addView(HorizontalScrollView(a).apply {
                        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip)
                    })
                    // reading ~25 app names from their packages took 1.6s on the UI thread; do it on the disk thread
                    a.refreshApps = { a.hub.diskPool.execute { val apps = a.apps.all(); a.hub.main.post { a.fillAppsRow(strip, apps) } } }
                    a.refreshApps()
                }
                is HaRow -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    val folded = a.hub.prefs.getBoolean("ha_folded", false)
                    addView(foldHeader("Home Assistant", folded) {
                        a.hub.prefs.edit().putBoolean("ha_folded", !folded).apply()
                        indexOf(m.id).takeIf { it >= 0 }?.let(adapter::notifyItemChanged)
                    }, LinearLayout.LayoutParams(-1, -2))
                    if (folded) return
                    (haView.parent as? ViewGroup)?.removeView(haView)
                    addView(haView, LinearLayout.LayoutParams(-1, a.dp(560)).apply { setMargins(a.dp(8), a.dp(4), a.dp(8), a.dp(12)) })
                }
                is FoldRow -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    addView(foldHeader("To Watch", folded) { toggleFold() }, LinearLayout.LayoutParams(-1, -2))
                }
                is WidgetsRow -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    val w = a.widgets
                    val ids = w.ids()
                    val folded = a.hub.prefs.getBoolean("widgets_folded", false)
                    val add = roundButton(R.drawable.ic_add, "Add widget") { w.pick() }
                    val edit = roundButton(R.drawable.ic_edit, if (w.editing) "Done removing" else "Remove widgets") { w.editing = !w.editing; refreshWidgets() }
                        .apply { if (w.editing) (background as GradientDrawable).setColor(ACCENT) }
                    addView(foldHeader("Widgets", folded, listOfNotNull(add, edit.takeIf { ids.isNotEmpty() })) {
                        a.hub.prefs.edit().putBoolean("widgets_folded", !folded).apply(); refreshWidgets()
                    }, LinearLayout.LayoutParams(-1, -2))
                    if (folded) return
                    if (ids.isEmpty()) addView(a.text(14f, TEXT_MUTED).apply {
                        text = "Tap + to add a widget (clock, weather, calendar…)"; setPadding(a.dp(8), 0, 0, a.dp(12))
                    })
                    else addView(FlowBox(a).apply { ids.forEach { id -> w.viewFor(id)?.let(::addView) } })
                }
            }
        }

        private fun bindContent(h: ContentHolder, row: ContentRow) {
            h.row = row
            h.header.text = row.title
            h.error.visibility = if (row.error != null) View.VISIBLE else View.GONE
            h.error.text = row.error
            h.strip.visibility = if (row.error == null) View.VISIBLE else View.GONE
            val tiles = h.strip.adapter as TilesAdapter
            val restore = row.scroll
            tiles.submitList(row.items) {
                if (restore != null) h.strip.layoutManager?.onRestoreInstanceState(restore) else h.strip.scrollToPosition(0)
                if (row.top10) tiles.notifyItemRangeChanged(0, tiles.itemCount) // rank numbers follow position, not the item
                // first row to appear takes focus; posted, because a request made mid-layout can be dropped
                if (row.items.isNotEmpty()) h.strip.post { if (a.currentFocus == null && !a.detail.isShown) h.strip.getChildAt(0)?.requestFocus() }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is ContentHolder) holder.row?.scroll = holder.strip.layoutManager?.onSaveInstanceState()
        }
    }

    private class TileHolder(v: View, val card: Cards.Card, val number: TextView?) : RecyclerView.ViewHolder(v)

    private inner class TilesAdapter(private val owner: ContentHolder) : ListAdapter<Item, TileHolder>(SAME) {
        private val top10 get() = owner.row?.top10 == true
        override fun getItemViewType(position: Int) = kindOf(getItem(position)).ordinal + if (top10) RANKED else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
            val c = a.cards.create(CardKind.entries[viewType % RANKED])
            if (viewType < RANKED) return TileHolder(c.root, c, null)
            val (box, number) = a.cards.ranked(c.root)
            return TileHolder(box, c, number)
        }

        override fun onBindViewHolder(h: TileHolder, position: Int) {
            val item = getItem(position)
            h.number?.text = (position + 1).toString()
            a.cards.bind(h.card, item, { v -> a.onTileFocused(v, item) }) { a.actions.click(item) }
            leftOpensSidebar(h.card.root) { h.bindingAdapterPosition == 0 }
        }
    }

    companion object {
        private const val RANKED = 100 // view-type offset for Top 10 tiles (rank number + card)
        private val SAME = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item) = a.play == b.play && a.title == b.title
            override fun areContentsTheSame(a: Item, b: Item) = a == b
        }
    }
}
