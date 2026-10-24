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

    /** Right after adding: how many columns and rows (of ~90 dp) it should take; starts at the size the widget suggests. */
    private fun askSize(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return
        val dm = a.resources.displayMetrics
        val cell = a.dp(CELL_DP)
        fun suggested(target: Int, min: Int, fallback: Int) = if (android.os.Build.VERSION.SDK_INT >= 31 && target > 0) target else maxOf(fallback, (min + cell - 1) / cell)
        fun picker(label: String, max: Int, start: Int) = android.widget.NumberPicker(a).apply {
            minValue = 1; maxValue = max; value = start.coerceIn(1, max); wrapSelectorWheel = false; contentDescription = label
        }
        val cols = picker("Columns", (dm.widthPixels - a.dp(64)) / cell, suggested(if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellWidth else 0, info.minWidth, 2))
        val rows = picker("Rows", (dm.heightPixels - a.dp(120)) / cell, suggested(if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0, info.minHeight, 1))
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
                a.hub.prefs.edit().putString("widget_size_$id", "${cols.value * CELL_DP},${rows.value * CELL_DP}").apply()
                a.rows.refreshWidgets()
            }
            .setNegativeButton("Keep its size", null).show()
    }

    fun remove(id: Int) {
        host.deleteAppWidgetId(id); save(ids() - id)
        a.hub.prefs.edit().remove("widget_size_$id").apply()
        a.rows.refreshWidgets()
    }

    /** The size you dragged it to (px), if you resized it. Stored in dp so it survives a density change. */
    private fun savedSize(id: Int): Pair<Int, Int>? = a.hub.prefs.getString("widget_size_$id", null)?.split(',')
        ?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { (w, h) -> a.dp(w) to a.dp(h) }

    /** Tells the widget its size, so it picks the layout that fits (a chat list rather than one line, say). */
    private fun tellSize(view: android.appwidget.AppWidgetHostView, w: Int, h: Int) {
        val d = a.resources.displayMetrics.density
        view.updateAppWidgetOptions(Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (w / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (w / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (h / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (h / d).toInt())
        })
    }

    /**
     * The live widget at the size you gave it, else the size it asks for (cells of ~90 dp); null if its app was
     * uninstalled. While editing, a red button removes it and the handle in its corner resizes it freely.
     */
    fun viewFor(id: Int): View? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        val cell = a.dp(CELL_DP)
        val (w, h) = savedSize(id) ?: (
            (if (android.os.Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) info.targetCellWidth * cell else maxOf(info.minWidth, cell * 2)) to
            (if (android.os.Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) info.targetCellHeight * cell else maxOf(info.minHeight, cell)))
        val view = host.createView(a, id, info)
        tellSize(view, w, h)
        return FrameLayout(a).apply {
            setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
            addView(view, FrameLayout.LayoutParams(w, h))
            if (!editing) return@apply
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(Color.WHITE); contentDescription = "Remove widget"
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6C62828.toInt()) }
                setOnClickListener { remove(id) }
            }, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.TOP or Gravity.END))
            // outline shows the widget's full area while you resize (many widgets are partly transparent)
            view.foreground = GradientDrawable().apply { cornerRadius = a.dp(8).toFloat(); setStroke(a.dp(2), ACCENT) }
            addView(resizeHandle(id, view), FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.BOTTOM or Gravity.END))
        }
    }

    /** Drag to resize: the widget follows your finger (down to 60 dp, up to the screen's width); saved when you let go. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun resizeHandle(id: Int, view: android.appwidget.AppWidgetHostView) = ImageView(a).apply {
        setImageResource(R.drawable.ic_resize); setColorFilter(Color.WHITE); contentDescription = "Resize widget"
        setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11)); rotation = 90f // arrows point to the corners it drags
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
        var startX = 0f; var startY = 0f; var startW = 0; var startH = 0
        setOnTouchListener { v, e ->
            val lp = view.layoutParams
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true) // Home mustn't scroll while you drag
                    startX = e.rawX; startY = e.rawY; startW = lp.width; startH = lp.height
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dm = a.resources.displayMetrics
                    lp.width = (startW + (e.rawX - startX).toInt()).coerceIn(a.dp(60), dm.widthPixels - a.dp(64))
                    lp.height = (startH + (e.rawY - startY).toInt()).coerceIn(a.dp(60), dm.heightPixels - a.dp(120))
                    view.layoutParams = lp
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val d = a.resources.displayMetrics.density
                    a.hub.prefs.edit().putString("widget_size_$id", "${(lp.width / d).toInt()},${(lp.height / d).toInt()}").apply()
                    tellSize(view, lp.width, lp.height)
                }
            }
            true
        }
    }

    companion object {
        private const val HOST_ID = 1024
        private const val CELL_DP = 90 // one column/row of the widget grid
        private const val REQ_BIND = 41
        private const val REQ_CONFIGURE = 42
    }
}
