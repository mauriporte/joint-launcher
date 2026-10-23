package com.mau.hub

import android.graphics.Typeface
import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
    private fun indexOf(id: Long) = models.indexOfFirst { it.id == id }

    fun clear() {
        models.clear(); failed.clear()
        adapter.notifyDataSetChanged()
    }

    private fun add(m: RowModel, at: Int = models.size) { models.add(at, m); adapter.notifyItemInserted(at) }

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
        var shown: String? = null // the JSON on screen; only touched on the UI thread
        fun show(items: List<Item>) {
            val i = indexOf(row.id).takeIf { it >= 0 } ?: return
            row.items = items.take(row.cap); row.error = null
            adapter.notifyItemChanged(i)
        }
        // saved results: read + decoded on the disk thread (doing ~25 of these on the UI thread cost ~1.5s at startup)
        if (cacheFile != null) hub.diskPool.execute {
            val json = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return@execute
            val items = runCatching { ItemJson.decode(json) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@execute
            hub.main.post { if (myGen == a.gen && shown == null) { shown = json; show(items) } }
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
                    if (myGen != a.gen) return@post
                    val i = indexOf(row.id).takeIf { it >= 0 } ?: return@post
                    if (items.isNotEmpty()) { failed.remove(row.id); if (fresh != shown) { shown = fresh; show(items) } }
                    else if (shown == null) { // nothing cached and nothing fetched: hide the row, or say why
                        if (errors.isEmpty()) { failed.remove(row.id); models.removeAt(i); adapter.notifyItemRemoved(i); return@post }
                        // e.g. Jellyfin answers 503 for minutes while it starts: retry with backoff, and on every return to Home
                        val wait = 15_000L shl attempt++.coerceAtMost(4) // 15s, 30s, 1m, 2m, then every 4m
                        val again = if (wait < 60_000) "${wait / 1000}s" else "${wait / 60_000} min"
                        row.error = errors.map(Sources::explain).distinct().joinToString("\n") { "$it. Trying again in $again." }
                        adapter.notifyItemChanged(i)
                        failed[row.id] = retry
                        hub.main.removeCallbacks(retry)
                        hub.main.postDelayed(retry, wait)
                    }
                }
            }
        }
        retry = Runnable { if (myGen == a.gen && shown == null && !loading) load() }
        load()
    }

    /** "Now playing" sits on top while some app is playing; null removes it. */
    fun nowPlaying(row: NowRow?) {
        val i = models.indexOfFirst { it is NowRow }
        when {
            row == null && i >= 0 -> { models.removeAt(i); adapter.notifyItemRemoved(i) }
            row != null && i >= 0 -> { models[i] = row; adapter.notifyItemChanged(i) }
            row != null -> add(row, 0)
        }
    }

    fun connect(entries: List<Pair<Item, () -> Unit>>) { if (entries.isNotEmpty()) add(ConnectRow(nextId++, entries)) }
    fun apps() = add(AppsRow(nextId++))

    // ---- views ----

    private fun header(title: String) = a.text(14f, 0xFFE4E5EA.toInt()).apply {
        text = title; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.02f
        setPadding(a.dp(8), a.dp(4), 0, 0)
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
        override fun getItemCount() = models.size
        override fun getItemId(position: Int) = models[position].id
        override fun getItemViewType(position: Int) = when (models[position]) {
            is ContentRow -> 0; is NowRow -> 1; is ConnectRow -> 2; is AppsRow -> 3
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
            when (val m = models[position]) {
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
                    addView(header("Your apps"))
                    val strip = strip()
                    addView(HorizontalScrollView(a).apply {
                        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip)
                    })
                    // reading ~25 app names from their packages took 1.6s on the UI thread; do it on the disk thread
                    a.hub.diskPool.execute { val apps = a.apps.all(); a.hub.main.post { a.fillAppsRow(strip, apps) } }
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
