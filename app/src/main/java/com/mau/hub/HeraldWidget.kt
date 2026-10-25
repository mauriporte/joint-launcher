package com.mau.hub

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Locale

/**
 * Home Herald: the house's daily newspaper (written every morning by the PC helper) from HA's sensor.home_herald,
 * read-only, with MauHome's token. English or Spanish as the device. Small = headline; medium adds the subhead and the
 * numbers; large adds the lead, the watch line and the briefs. The last edition is kept, so a missing sensor (HA
 * restarting) or no network still shows it. A tap opens the full edition.
 */
class HeraldWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, force = true, done = goAsync())
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = draw(c)
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == FLIP) { prefs(c).edit().putBoolean(LANG, !spanish(c)).apply(); draw(c) } // EN <-> ES, for every Herald widget
    }

    /** One edition, in the device's language, with the HA page's labels (herald.py UI). kpis: (figure, label). */
    internal class Page(val no: String, val date: String, val edition: String, val masthead: String, val headline: String,
                        val subhead: String, val lead: String, val kpis: List<Pair<String, String>>, val watch: String,
                        val briefs: String, val labels: Triple<String, String, String>)

    companion object {
        private const val CACHE = "herald_cache"; private const val AT = "herald_at"

        private const val FLIP = "com.mau.hub.HERALD_FLIP"; private const val LANG = "herald_es"

        private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)
        /** Spanish: the switch's last choice, else the device's language. */
        private fun spanish(c: Context) = prefs(c).getBoolean(LANG, Locale.getDefault().language == "es")

        /** The sensor's JSON as a page; es: the *_es fields (falling back to English where one is missing). */
        internal fun page(o: JSONObject, es: Boolean): Page {
            val a = o.optJSONObject("attributes") ?: JSONObject()
            fun s(k: String) = (if (es) a.optString("${k}_es") else "").ifEmpty { a.optString(k) }
            fun lines(k: String): String {
                val v = (if (es) a.opt("${k}_es") else null) ?: a.opt(k)
                return if (v is org.json.JSONArray) (0 until v.length()).joinToString("\n") { "• " + v.optString(it) } else v?.toString().orEmpty()
            }
            val n = a.optJSONObject("numbers") ?: JSONObject()
            fun k(key: String, en: String, sp: String) = n.optString(key).takeIf { it.isNotEmpty() }?.let { it to if (es) sp else en }
            val kpis = listOfNotNull(
                k("devices_online", "devices online", "dispositivos en línea"),
                n.optString("servers_up").takeIf { it.isNotEmpty() }?.let { up ->
                    up + n.optString("servers_total").let { if (it.isEmpty()) "" else "/$it" } to if (es) "servidores en marcha" else "servers up" },
                k("monitors_up", "monitors up", "monitores activos"),
                k("entities_unavailable", "entities unavailable", "entidades no disponibles"),
                k("lights_on", "lights on", "luces encendidas"),
                n.optString("temperature").takeIf { it.isNotEmpty() }?.let { "$it°" to n.optString("weather") },
            )
            // "2026-10-24T15:53:39+02:00": the day and time as written, in the house's time zone
            val t = a.optString("published")
            val date = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(t)?.destructured?.let { (y, m, d) ->
                val cal = java.util.Calendar.getInstance().apply { set(y.toInt(), m.toInt() - 1, d.toInt()) }
                val sym = java.text.DateFormatSymbols(if (es) Locale("es") else Locale.ENGLISH)
                val day = sym.weekdays[cal.get(java.util.Calendar.DAY_OF_WEEK)]
                if (es) "$day, ${d.toInt()} de ${sym.months[m.toInt() - 1]} de $y" else "$day, $d ${sym.months[m.toInt() - 1]} $y"
            }.orEmpty()
            val time = Regex("""T(\d{2}:\d{2})""").find(t)?.groupValues?.get(1)
            return Page(
                a.optInt("edition").takeIf { it > 0 }?.let { if (es) "Nº $it" else "No. $it" }.orEmpty(), date,
                (if (es) "Edición de la mañana" else "Morning edition") + (time?.let { " · $it" } ?: ""),
                if (es) "Heraldo del Hogar" else "Home Herald",
                (if (es) a.optString("headline_es") else "").ifEmpty { o.optString("state") }, s("subhead"), s("lead"), kpis,
                lines("watch"), lines("briefs"),
                if (es) Triple("En cifras", "Hoy, atención a", "Breves") else Triple("By the numbers", "Watch today", "In brief"))
        }

        /** Fetches the edition (unless one was fetched in the last 15 minutes, or force) and redraws every Herald widget. */
        fun refresh(c: Context, force: Boolean = false, done: android.content.BroadcastReceiver.PendingResult? = null) {
            val app = c.applicationContext
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, HeraldWidget::class.java))
            val p = prefs(app)
            if (ids.isEmpty() || (!force && System.currentTimeMillis() - p.getLong(AT, 0) < 15 * 60_000)) { done?.finish(); return }
            Thread {
                try {
                    // a failure (404 while HA restarts, no network) keeps the cached edition on screen
                    runCatching { Sources.get("${HaWidget.base(app)}/api/states/sensor.home_herald", HaWidget.auth(app)) }
                        .onSuccess { p.edit().putString(CACHE, it).putLong(AT, System.currentTimeMillis()).apply() }
                    draw(app)
                } finally { done?.finish() }
            }.start()
        }

        private fun draw(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val cached = prefs(c).getString(CACHE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            m.getAppWidgetIds(ComponentName(c, HeraldWidget::class.java)).forEach { m.updateAppWidget(it, views(c, cached, m.getAppWidgetOptions(it))) }
        }

        private fun views(c: Context, o: JSONObject?, opts: Bundle) = RemoteViews(c.packageName, R.layout.herald_widget).apply {
            val es = spanish(c)
            val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO
            // the HA page's colours: --ink, --muted, --accent, --box
            val ink = if (night) 0xFFE1E1E1.toInt() else 0xFF000000.toInt()
            val muted = if (night) 0xFF9B9B9B.toInt() else 0xFF555555.toInt()
            val accent = if (night) 0xFFE07A6A.toInt() else 0xFF8B1E1E.toInt()
            val box = if (night) 0xFF1C1C1C.toInt() else 0xFFF1F1F1.toInt()
            setInt(R.id.herald, "setBackgroundResource", if (night) R.drawable.herald_bg_night else R.drawable.herald_bg_day)
            val pg = o?.let { page(it, es) }
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 400)
            val size = when { h < 170 -> 0; h < 320 -> 1; else -> 2 } // small, medium, large
            fun text(id: Int, value: CharSequence?, colour: Int, from: Int = 0) {
                setViewVisibility(id, if (size >= from && !value.isNullOrBlank()) View.VISIBLE else View.GONE)
                setTextViewText(id, value ?: ""); setTextColor(id, colour)
            }
            fun show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
            listOf(R.id.herald_rule1, R.id.herald_rule2, R.id.herald_rule3, R.id.herald_numbers_rule, R.id.herald_watch_rule, R.id.herald_briefs_rule)
                .forEach { setInt(it, "setBackgroundColor", ink) }
            text(R.id.herald_no, pg?.no, muted); text(R.id.herald_date, pg?.date, muted); text(R.id.herald_edition, pg?.edition, muted)
            text(R.id.herald_masthead, pg?.masthead ?: if (es) "Heraldo del Hogar" else "Home Herald", ink)
            text(R.id.herald_headline, pg?.headline ?: if (es) "Aún no hay edición: guarda tu token de Home Assistant en Conectar"
                else "No edition yet: save your Home Assistant token on the Connect page", ink)
            text(R.id.herald_subhead, pg?.subhead, muted, 1)
            // the lead's first letter as a drop cap in the accent colour, like the page
            text(R.id.herald_lead, pg?.lead?.takeIf { it.isNotBlank() }?.let { l ->
                android.text.SpannableString(l).apply {
                    setSpan(android.text.style.RelativeSizeSpan(2.2f), 0, 1, 0)
                    setSpan(android.text.style.ForegroundColorSpan(accent), 0, 1, 0)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 1, 0)
                }
            }, ink, 2)
            val kpis = pg?.kpis.orEmpty()
            show(R.id.herald_numbers_box, size >= 1 && kpis.isNotEmpty())
            setInt(R.id.herald_numbers_box, "setBackgroundColor", box)
            text(R.id.herald_numbers_h, pg?.labels?.first, ink)
            listOf(R.id.herald_k1, R.id.herald_k2, R.id.herald_k3, R.id.herald_k4, R.id.herald_k5, R.id.herald_k6).forEachIndexed { i, id ->
                text(id, kpis.getOrNull(i)?.let { (figure, label) ->
                    android.text.SpannableStringBuilder(figure).apply {
                        setSpan(android.text.style.RelativeSizeSpan(2.6f), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(accent), 0, length, 0)
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
                        append("\n").append(label.uppercase(if (es) Locale("es") else Locale.ENGLISH))
                    }
                }, muted)
            }
            show(R.id.herald_watch_box, size >= 2 && !pg?.watch.isNullOrBlank())
            setInt(R.id.herald_watch_box, "setBackgroundColor", box)
            setInt(R.id.herald_watch_bar, "setBackgroundColor", accent)
            text(R.id.herald_watch_h, pg?.labels?.second, ink); text(R.id.herald_watch, pg?.watch, ink)
            show(R.id.herald_briefs_box, size >= 2 && !pg?.briefs.isNullOrBlank())
            text(R.id.herald_briefs_h, pg?.labels?.third, ink); text(R.id.herald_briefs, pg?.briefs, ink)
            // the chosen language bright, the other muted (the page fills the chosen one)
            text(R.id.herald_lang, android.text.SpannableStringBuilder().apply {
                fun add(s: String, on: Boolean) { val st = length; append(s); setSpan(android.text.style.ForegroundColorSpan(if (on) ink else muted), st, length, 0) }
                add("EN", !es); add("  ·  ", false); add("ES", es)
            }, muted)
            setOnClickPendingIntent(R.id.herald_lang, PendingIntent.getBroadcast(c, 1, Intent(c, HeraldWidget::class.java).setAction(FLIP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setOnClickPendingIntent(R.id.herald, PendingIntent.getActivity(c, 0, open(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        /** The full edition: in the Home Assistant app when it's installed (no second login), else HA's page in the browser. */
        private fun open(c: Context): Intent {
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate/dashboard-projects/herald")).setPackage("io.homeassistant.companion.android")
            val web = (BuildConfig.HA_REMOTE.ifEmpty { BuildConfig.HA_LOCAL }).trimEnd('/') + "/dashboard-projects/herald" // address from local.properties
            return (if (app.resolveActivity(c.packageManager) != null) app else Intent(Intent.ACTION_VIEW, Uri.parse(web)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
