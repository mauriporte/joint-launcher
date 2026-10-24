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
    private var pending = AppWidgetManager.INVALID_APPWIDGET_ID
    var editing = false // shows a remove button on each widget

    fun ids(): List<Int> = a.hub.prefs.getString("widgets", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    private fun save(ids: List<Int>) = a.hub.prefs.edit().putString("widgets", ids.joinToString(",")).apply()

    fun start() { runCatching { host.startListening() } }
    fun stop() { runCatching { host.stopListening() } }

    /** Every widget your apps offer, by app; tapping one starts adding it. */
    fun pick() {
        val pm = a.packageManager
        val all = manager.installedProviders.sortedWith(compareBy({ it.loadLabel(pm).lowercase() }))
        if (all.isEmpty()) return a.toast("None of your apps offer widgets")
        lateinit var dialog: AlertDialog
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)) }
        all.forEach { p ->
            list.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10))
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)
                addView(ImageView(a).apply { setImageDrawable(runCatching { p.loadIcon(a, 0) }.getOrNull()) }, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault("")
                addView(a.text(16f, Color.WHITE).apply { text = "${p.loadLabel(pm)}" + if (app.isNotEmpty()) "  ·  $app" else "" },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = a.dp(14) })
                setOnClickListener { dialog.dismiss(); add(p) }
            })
        }
        dialog = AlertDialog.Builder(a).setTitle("Add a widget").setView(ScrollView(a).apply { addView(list) })
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
            REQ_BIND -> if (ok) configure(id) else host.deleteAppWidgetId(id)
            REQ_CONFIGURE -> if (ok) added(id) else host.deleteAppWidgetId(id)
            else -> return
        }
    }

    private fun added(id: Int) {
        pending = AppWidgetManager.INVALID_APPWIDGET_ID; save(ids() + id); a.rows.refreshWidgets()
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
    // Home's rows sit inside side margins: the grid's real width (screen minus about 100 dp), for sizing before layout
    private fun gridWidth() = a.resources.displayMetrics.widthPixels - a.dp(104)
    private fun maxCols(width: Int) = maxOf(4, Math.round(width / a.dp(120).toFloat()))
    private fun cellPx(width: Int) = width / maxCols(width)
    private fun ints(key: String) = a.hub.prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }

    /** Rows that fit one screen below the top bar; the grid can grow to three of those (three tablets stacked). */
    private fun screenRows(width: Int) = maxOf(2, (a.resources.displayMetrics.heightPixels - a.dp(160)) / cellPx(width))
    private fun maxRows(width: Int) = screenRows(width) * 3

    /** Columns x rows of the grid in this orientation: yours, else the whole screen below the top bar. */
    private fun gridSize(width: Int): Pair<Int, Int> {
        return ints("wgrid_$orient")?.let { (c, r) -> c.coerceIn(1, maxCols(width)) to r.coerceIn(1, maxRows(width)) } ?: (maxCols(width) to screenRows(width))
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
            val (sw, sh) = span(id, cell).let { (w, h) -> w.coerceIn(1, cols) to h.coerceIn(1, rows) }
            // its spot in this orientation, else the first free one (reading order), else the top-left corner
            val saved = ints("wpos_${orient}_$id")
            val (pc, pr) = if (saved != null) saved[0] to saved[1]
                else (0..rows - sh).asSequence().flatMap { r -> (0..cols - sw).asSequence().map { c -> c to r } }.firstOrNull { (c, r) -> free(c, r, sw, sh) } ?: (0 to 0)
            val spot = Spot(pc.coerceIn(0, cols - sw), pr.coerceIn(0, rows - sh), sw, sh)
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
                            spot.c = Math.round((spot.c * grid.cell + box.translationX) / grid.cell).coerceIn(0, grid.cols - spot.cw)
                            spot.r = Math.round((spot.r * grid.cell + box.translationY) / grid.cell).coerceIn(0, grid.rows - spot.ch)
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
                if (done) { a.hub.prefs.edit().putString("wspan_$id", "$w,$h").apply(); tellSize(view, w * grid.cell, h * grid.cell) }
            }, FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.BOTTOM or Gravity.END))
        }
    }

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
        private const val HOST_ID = 1024
        private const val REQ_BIND = 41
        private const val REQ_CONFIGURE = 42
    }
}
