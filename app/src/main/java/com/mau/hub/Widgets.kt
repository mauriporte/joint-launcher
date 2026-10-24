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

    private fun added(id: Int) { pending = AppWidgetManager.INVALID_APPWIDGET_ID; save(ids() + id); a.rows.refreshWidgets() }

    fun remove(id: Int) { host.deleteAppWidgetId(id); save(ids() - id); a.rows.refreshWidgets() }

    /** The live widget, sized from what it asks for (cells of ~90 dp), or null if its app was uninstalled. */
    fun viewFor(id: Int): View? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        val cell = a.dp(90)
        val w = if (android.os.Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) info.targetCellWidth * cell else maxOf(info.minWidth, cell * 2)
        val h = if (android.os.Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) info.targetCellHeight * cell else maxOf(info.minHeight, cell)
        val view = host.createView(a, id, info)
        // tell the widget its size, so it picks the right layout
        val d = a.resources.displayMetrics.density
        view.updateAppWidgetOptions(Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (w / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (w / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (h / d).toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (h / d).toInt())
        })
        return FrameLayout(a).apply {
            setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
            addView(view, FrameLayout.LayoutParams(w, h))
            if (editing) addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(Color.WHITE); contentDescription = "Remove widget"
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6C62828.toInt()) }
                setOnClickListener { remove(id) }
            }, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.TOP or Gravity.END))
        }
    }

    companion object {
        private const val HOST_ID = 1024
        private const val REQ_BIND = 41
        private const val REQ_CONFIGURE = 42
    }
}
