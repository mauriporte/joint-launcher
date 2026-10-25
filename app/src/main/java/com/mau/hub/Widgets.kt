package com.mau.hub

import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Home-screen widgets on the tablet, hosted like any launcher does: pick one from every widget your apps offer,
 * Android asks once for permission to show it, the widget's own setup screen runs if it has one, and it appears in
 * the Widgets section. The chosen widgets are remembered (by id) across restarts.
 */
class Widgets(private val a: MainActivity) {
    private val manager = AppWidgetManager.getInstance(a)
    private val host = AppWidgetHost(a, HOST_ID)
    // the widget being added, saved rather than in memory: Android may close Joint Launcher while the widget's own
    // setup screen is open, and the widget then got set up but never placed (it "didn't stick")
    private var pending: Int
        get() = a.hub.prefs.getInt("widget_pending", AppWidgetManager.INVALID_APPWIDGET_ID)
        set(v) = a.hub.prefs.edit().putInt("widget_pending", v).apply()
    var editing = false // shows a remove button on each widget

    fun ids(): List<Int> = a.hub.prefs.getString("widgets", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    private fun save(ids: List<Int>) = a.hub.prefs.edit().putString("widgets", ids.joinToString(",")).apply()

    fun start() { runCatching { host.startListening() } }
    fun stop() { runCatching { host.stopListening() } }

    /** Every widget your apps offer, by app; tapping one starts adding it. */
    /** forCorner: the widget replaces the top bar's clock (Settings → Clock) instead of going on the grid. */
    fun pick(forCorner: Boolean = false) {
        val pm = a.packageManager
        val all = manager.installedProviders.sortedWith(compareBy({ it.loadLabel(pm).lowercase() }))
        if (all.isEmpty()) return a.toast("None of your apps offer widgets")
        lateinit var dialog: AlertDialog
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)) }
        // there are hundreds: type part of the widget's or the app's name to narrow the list
        val search = android.widget.EditText(a).apply {
            hint = "Search widgets or apps"; isSingleLine = true; textSize = 16f
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = a.dp(10)
            compoundDrawablesRelative[0]?.setTint(TEXT_MUTED)
        }
        all.forEach { p ->
            list.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10))
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)
                addView(ImageView(a).apply { setImageDrawable(runCatching { p.loadIcon(a, 0) }.getOrNull()) }, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault("")
                val name = "${p.loadLabel(pm)}" + if (app.isNotEmpty()) "  ·  $app" else ""
                tag = name.lowercase()
                addView(a.text(16f, Color.WHITE).apply { text = name },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = a.dp(14) })
                setOnClickListener { dialog.dismiss(); a.hub.prefs.edit().putBoolean("widget_pending_corner", forCorner).apply(); add(p) }
            })
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(t: android.text.Editable?) {
                val q = t.toString().trim().lowercase()
                for (i in 0 until list.childCount) list.getChildAt(i).let { it.visibility = if (q.isEmpty() || (it.tag as String).contains(q)) View.VISIBLE else View.GONE }
            }
            override fun beforeTextChanged(t: CharSequence?, s: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(t: CharSequence?, s: Int, b: Int, c: Int) = Unit
        })
        dialog = AlertDialog.Builder(a).setTitle("Add a widget").setView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(a.dp(24), a.dp(8), a.dp(24), 0) })
            addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
            .setNegativeButton("Cancel", null).show()
    }

    private fun add(p: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId().also { pending = it }
        if (manager.bindAppWidgetIdIfAllowed(id, p.profile, p.provider, null)) configure(id)
        else @Suppress("DEPRECATION") a.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, p.profile), REQ_BIND) // Android asks you once
    }

    /** The widget's own setup screen first, if it has one (e.g. which city a weather widget shows). */
    private fun configure(id: Int) {
        if (manager.getAppWidgetInfo(id)?.configure != null) runCatching { host.startAppWidgetConfigureActivityForResult(a, id, 0, REQ_CONFIGURE, null) }
            .onFailure { added(id) } // some widgets' setup can't be opened by other apps; add it as it is
        else added(id)
    }

    /** From MainActivity.onActivityResult. */
    fun onResult(request: Int, ok: Boolean) {
        val id = pending.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID } ?: return
        when (request) {
            REQ_BIND -> if (ok) configure(id) else { host.deleteAppWidgetId(id); pending = AppWidgetManager.INVALID_APPWIDGET_ID }
            REQ_CONFIGURE -> if (ok) added(id) else { host.deleteAppWidgetId(id); pending = AppWidgetManager.INVALID_APPWIDGET_ID }
            else -> return
        }
    }

    private fun added(id: Int) {
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        if (a.hub.prefs.getBoolean("widget_pending_corner", false)) {
            a.hub.prefs.getInt("corner_widget", -1).takeIf { it >= 0 }?.let(host::deleteAppWidgetId) // the one it replaces
            a.hub.prefs.edit().putInt("corner_widget", id).putBoolean("widget_pending_corner", false).apply()
            a.topBar.applyCornerClock(); return
        }
        save(ids() + id); a.rows.refreshWidgets()
        askSize(id)
    }

    /** Right after adding: how many columns and rows of the grid it should take; starts at the size the widget suggests. */
    private fun askSize(id: Int) {
        manager.getAppWidgetInfo(id) ?: return
        val (gc, gr) = gridSize(gridWidth())
        val (sc, sr) = span(id, cellPx(gridWidth()))
        fun picker(label: String, max: Int, start: Int) = android.widget.NumberPicker(a).apply {
            minValue = 1; maxValue = max; value = start.coerceIn(1, max); wrapSelectorWheel = false; contentDescription = label
        }
        val cols = picker("Columns", gc, sc)
        val rows = picker("Rows", gr, sr)
        fun column(label: String, p: View) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(24), 0, a.dp(24), 0)
            addView(a.text(15f, TEXT_SOFT).apply { text = label }); addView(p)
        }
        AlertDialog.Builder(a).setTitle("How big should it be?")
            .setView(LinearLayout(a).apply {
                gravity = Gravity.CENTER; setPadding(0, a.dp(12), 0, 0)
                addView(column("Columns", cols)); addView(a.text(22f, Color.WHITE).apply { text = "×" }); addView(column("Rows", rows))
            })
            .setPositiveButton("OK") { _, _ ->
                a.hub.prefs.edit().putString("wspan_$id", "${cols.value},${rows.value}").apply()
                a.rows.refreshWidgets()
            }
            .setNegativeButton("Keep its size", null).show()
    }

    fun remove(id: Int) {
        host.deleteAppWidgetId(id); save(ids() - id)
        a.hub.prefs.edit().remove("widget_size_$id").remove("wspan_$id").remove("wpos_l_$id").remove("wpos_p_$id").apply()
        a.rows.refreshWidgets()
    }

    /** The top bar's corner widget (e.g. Flip Clock's), w x h px; null if none is set or its app is gone. */
    fun cornerView(w: Int, h: Int): View? {
        val id = a.hub.prefs.getInt("corner_widget", -1).takeIf { it >= 0 } ?: return null
        val info = manager.getAppWidgetInfo(id) ?: return null
        return host.createView(a, id, info).also { tellSize(it, w, h) }
    }

    /** Back to the built-in clock. */
    fun clearCorner() {
        a.hub.prefs.getInt("corner_widget", -1).takeIf { it >= 0 }?.let(host::deleteAppWidgetId)
        a.hub.prefs.edit().remove("corner_widget").apply(); a.topBar.applyCornerClock()
    }

    /** Tells the widget its size, so it picks the layout that fits (a chat list rather than one line, say). */
    private fun tellSize(view: android.appwidget.AppWidgetHostView, w: Int, h: Int) {
        val d = a.resources.displayMetrics.density
        view.updateAppWidgetOptions(Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (w / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (w / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (h / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (h / d).toInt())
        })
    }

    // ---- the grid: a launcher-style home page for widgets ----
    // Cells of about 120 dp, so the full width holds as many columns as a tablet launcher's home page. The grid
    // itself is resizable (columns x rows, its own corner handle while editing; up to three screens tall); by default it's as wide as the
    // screen and as tall as the screen below the top bar. Each widget has a span (columns x rows) and a spot per
    // orientation, so turning the tablet doesn't scramble the landscape layout.

    private val orient get() = if (a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "p" else "l"
    // the grid's real width, for sizing before layout: the screen minus Home's side margins (page 20, rows 8 + 12,
    // the grid's own 8 + 8); must match, or the saved width gets cut a column short
    private fun gridWidth() = a.resources.displayMetrics.widthPixels - a.dp(if (a.hub.touch) 56 else 104)
    private fun maxCols(width: Int) = maxOf(4, Math.round(width / a.dp(120).toFloat()))
    private fun cellPx(width: Int) = width / maxCols(width)
    private fun ints(key: String) = a.hub.prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }

    /** Rows that fit one screen below the top bar; the grid can grow to ten of those (the widget page scrolls). */
    private fun screenRows(width: Int) = maxOf(2, (a.resources.displayMetrics.heightPixels - a.dp(160)) / cellPx(width))
    private fun maxRows(width: Int) = screenRows(width) * 10

    /** Settings → Widget page height: how many screens tall the grid is (1-10). Setting it replaces a height dragged by hand. */
    var screens: Int
        get() = a.hub.prefs.getInt("widget_screens", 1)
        set(v) = a.hub.prefs.edit().apply {
            putInt("widget_screens", v.coerceIn(1, 10))
            listOf("p", "l").forEach { o -> ints("wgrid_$o")?.let { (c, _) -> putString("wgrid_$o", "$c,0") } } // 0 rows = follow Settings
        }.apply()

    /** Columns x rows of the grid in this orientation: yours (rows dragged by hand, or Settings' screens), else the whole width. */
    private fun gridSize(width: Int): Pair<Int, Int> {
        val fromSettings = screenRows(width) * screens
        return ints("wgrid_$orient")?.let { (c, r) -> c.coerceIn(1, maxCols(width)) to (if (r <= 0) fromSettings else r.coerceIn(1, maxRows(width))) }
            ?: (maxCols(width) to fromSettings)
    }

    /** A widget's columns x rows: yours, else from the size it was dragged to before the grid, else what it asks for. */
    private fun span(id: Int, cell: Int): Pair<Int, Int> {
        ints("wspan_$id")?.let { return it[0] to it[1] }
        ints("widget_size_$id")?.let { (w, h) -> return maxOf(1, Math.round(a.dp(w) / cell.toFloat())) to maxOf(1, Math.round(a.dp(h) / cell.toFloat())) }
        val info = manager.getAppWidgetInfo(id) ?: return 2 to 2
        fun cells(target: Int, min: Int) = if (target > 0) target else maxOf(1, (min + cell - 1) / cell)
        val new = android.os.Build.VERSION.SDK_INT >= 31
        return cells(if (new) info.targetCellWidth else 0, info.minWidth) to cells(if (new) info.targetCellHeight else 0, info.minHeight)
    }

    private class Spot(var c: Int, var r: Int, var cw: Int, var ch: Int) : ViewGroup.LayoutParams(0, 0)

    /** The Widgets section's body: every widget on its spot, plus (while editing) the grid's own resize handle. */
    fun grid(ids: List<Int>): View = Grid().apply {
        val (gc, gr) = gridSize(gridWidth()); cols = gc; rows = gr
        val cell = cellPx(gridWidth())
        val taken = HashSet<Pair<Int, Int>>()
        fun free(c: Int, r: Int, w: Int, h: Int) = (c until c + w).all { x -> (r until r + h).all { y -> (x to y) !in taken } }
        ids.forEach { id ->
            val info = manager.getAppWidgetInfo(id) ?: return@forEach
            val (sw, sh) = span(id, cell).let { (w, h) -> w.coerceIn(1, cols) to maxOf(1, h) }
            // its spot in this orientation, else the first free one (reading order), else a new row under the rest:
            // the grid grows downwards for as many widgets as you add (the page scrolls), like Pro Launcher's widget page
            val saved = ints("wpos_${orient}_$id")
            val (pc, pr) = if (saved != null) saved[0] to maxOf(0, saved[1])
                else (0..rows - sh).asSequence().flatMap { r -> (0..cols - sw).asSequence().map { c -> c to r } }.firstOrNull { (c, r) -> free(c, r, sw, sh) } ?: (0 to rows)
            rows = maxOf(rows, pr + sh)
            val spot = Spot(pc.coerceIn(0, cols - sw), pr, sw, sh)
            (spot.c until spot.c + sw).forEach { x -> (spot.r until spot.r + sh).forEach { y -> taken += x to y } }
            addView(tile(id, info, this, spot), spot)
        }
        if (editing) addView(gridHandle(this), Spot(-1, -1, 0, 0))
    }

    private inner class Grid : ViewGroup(a) {
        var cols = 4
        var rows = 4
        var cell = 0
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init { setWillNotDraw(false); clipChildren = false }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            cell = cellPx(w)
            if (cols > maxCols(w)) cols = maxCols(w) // the space it really has (the screen minus Home's margins)
            fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val ch = getChildAt(i); val s = ch.layoutParams as Spot
                if (s.c < 0) ch.measure(exact(a.dp(44)), exact(a.dp(44))) else ch.measure(exact(s.cw * cell), exact(s.ch * cell))
            }
            setMeasuredDimension(w, rows * cell + a.dp(24))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val ch = getChildAt(i); val s = ch.layoutParams as Spot
                if (s.c < 0) {
                    val x = cols * cell - ch.measuredWidth / 2; val y = rows * cell - ch.measuredHeight / 2
                    ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
                } else ch.layout(s.c * cell, s.r * cell, (s.c + s.cw) * cell, (s.r + s.ch) * cell)
            }
        }

        // while editing: the grid's outline and a faint dot at each cell corner, so you can see where things snap
        override fun onDraw(c: android.graphics.Canvas) {
            if (!editing || cell == 0) return
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = a.dp(1).toFloat(); paint.color = 0x66FFFFFF
            c.drawRoundRect(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), a.dp(10).toFloat(), a.dp(10).toFloat(), paint)
            paint.style = android.graphics.Paint.Style.FILL; paint.color = 0x55FFFFFF
            for (x in 1 until cols) for (y in 1 until rows) c.drawCircle((x * cell).toFloat(), (y * cell).toFloat(), a.dp(2).toFloat(), paint)
        }
    }

    /** One widget on the grid; while editing it moves by dragging it and resizes by its corner, snapping to cells. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun tile(id: Int, info: AppWidgetProviderInfo, grid: Grid, spot: Spot): View {
        val view = host.createView(a, id, info)
        val cell = cellPx(gridWidth())
        tellSize(view, spot.cw * cell, spot.ch * cell)
        return FrameLayout(a).apply {
            setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
            addView(view, FrameLayout.LayoutParams(-1, -1))
            if (!editing) return@apply
            // outline shows the widget's full area (many widgets are partly transparent)
            foreground = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(2), ACCENT) }
            val box = this
            // a cover over the widget: dragging it moves the widget (the widget itself gets no touches while editing)
            addView(View(a).apply {
                var sx = 0f; var sy = 0f
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            v.parent.requestDisallowInterceptTouchEvent(true); sx = e.rawX; sy = e.rawY
                            box.bringToFront(); box.elevation = a.dp(8).toFloat()
                        }
                        android.view.MotionEvent.ACTION_MOVE -> { box.translationX = e.rawX - sx; box.translationY = e.rawY - sy }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            // the cell you dropped it on, or the nearest free one if another widget is there (or stay put)
                            val wc = Math.round((spot.c * grid.cell + box.translationX) / grid.cell).coerceIn(0, grid.cols - spot.cw)
                            val wr = Math.round((spot.r * grid.cell + box.translationY) / grid.cell).coerceIn(0, grid.rows - spot.ch)
                            nearestFree(wc, wr, spot.cw, spot.ch, grid.cols, grid.rows, others(grid, box))?.let { (c, r) -> spot.c = c; spot.r = r }
                            box.translationX = 0f; box.translationY = 0f; box.elevation = 0f
                            a.hub.prefs.edit().putString("wpos_${orient}_$id", "${spot.c},${spot.r}").apply()
                            box.layoutParams = spot
                        }
                    }
                    true
                }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(Color.WHITE); contentDescription = "Remove widget"
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6C62828.toInt()) }
                setOnClickListener { remove(id) }
            }, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.TOP or Gravity.END))
            var w0 = 0; var h0 = 0
            addView(handle("Resize widget", ACCENT, { w0 = spot.cw; h0 = spot.ch }) { dx, dy, done ->
                val w = Math.round((w0 * grid.cell + dx) / grid.cell).coerceIn(1, grid.cols - spot.c)
                val h = Math.round((h0 * grid.cell + dy) / grid.cell).coerceIn(1, grid.rows - spot.r)
                if (w != spot.cw || h != spot.ch) { spot.cw = w; spot.ch = h; box.layoutParams = spot }
                if (done) {
                    // growing over another widget isn't allowed: back to the size it had
                    if (others(grid, box).any { o -> overlaps(spot.c, spot.r, w, h, o) }) { spot.cw = w0; spot.ch = h0; box.layoutParams = spot }
                    a.hub.prefs.edit().putString("wspan_$id", "${spot.cw},${spot.ch}").apply(); tellSize(view, spot.cw * grid.cell, spot.ch * grid.cell)
                }
            }, FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.BOTTOM or Gravity.END))
        }
    }

    /** The other widgets' spots on the grid (as c, r, w, h), for keeping them from overlapping. */
    private fun others(grid: ViewGroup, self: View) = (0 until grid.childCount).map(grid::getChildAt).filter { it !== self }
        .map { it.layoutParams as Spot }.filter { it.c >= 0 }.map { intArrayOf(it.c, it.r, it.cw, it.ch) }

    /** The grid's own corner: drag to make the widget area wider/narrower, taller/shorter (whole cells). */
    private fun gridHandle(grid: Grid): View {
        var c0 = 0; var r0 = 0
        return handle("Resize the widget area", 0xFF546E7A.toInt(), { c0 = grid.cols; r0 = grid.rows }) { dx, dy, done -> gridDrag(grid, c0, r0, dx, dy, done) }
    }

    private fun gridDrag(grid: Grid, c0: Int, r0: Int, dx: Float, dy: Float, done: Boolean) {
        val c = Math.round((c0 * grid.cell + dx) / grid.cell).coerceIn(1, maxCols(grid.width))
        val r = Math.round((r0 * grid.cell + dy) / grid.cell).coerceIn(1, maxRows(grid.width))
        if (c != grid.cols || r != grid.rows) { grid.cols = c; grid.rows = r; grid.requestLayout(); grid.invalidate() }
        if (done) { a.hub.prefs.edit().putString("wgrid_$orient", "$c,$r").apply(); a.rows.refreshWidgets() } // widgets re-fit the new grid
    }

    /** A round drag handle; onStart runs on touch, onDrag gets the distance from where the drag started (and true on release). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun handle(label: String, color: Int, onStart: () -> Unit, onDrag: (Float, Float, Boolean) -> Unit) = ImageView(a).apply {
        setImageResource(R.drawable.ic_resize); setColorFilter(Color.WHITE); contentDescription = label
        setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11)); rotation = 90f // arrows point to the corners it drags
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        var sx = 0f; var sy = 0f
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); sx = e.rawX; sy = e.rawY; onStart() }
                android.view.MotionEvent.ACTION_MOVE -> onDrag(e.rawX - sx, e.rawY - sy, false)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> onDrag(e.rawX - sx, e.rawY - sy, true)
            }
            true
        }
    }

    companion object {
        fun overlaps(c: Int, r: Int, w: Int, h: Int, o: IntArray) = c < o[0] + o[2] && o[0] < c + w && r < o[1] + o[3] && o[1] < r + h

        /** The free spot (c, r) closest to where a w x h widget was dropped; null when the grid has no room for it. */
        fun nearestFree(c: Int, r: Int, w: Int, h: Int, cols: Int, rows: Int, others: List<IntArray>): Pair<Int, Int>? =
            (0..rows - h).flatMap { y -> (0..cols - w).map { x -> x to y } }
                .filter { (x, y) -> others.none { overlaps(x, y, w, h, it) } }
                .minByOrNull { (x, y) -> (x - c) * (x - c) + (y - r) * (y - r) }

        private const val HOST_ID = 1024
        private const val REQ_BIND = 41
        private const val REQ_CONFIGURE = 42
    }
}
