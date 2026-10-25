package com.mau.hub

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Home Herald: the house's daily newspaper (written every morning by the PC helper) from HA's sensor.home_herald,
 * read-only, with MauHome's token, laid out like its page in HA (homelab-pc-helper/herald.py) in the same fonts. EN/ES
 * and night/day switches like the page's (remembered; they start from the device's language and dark mode). Small =
 * masthead and headline; medium adds the switches, logo, subhead and numbers; large adds the lead, the watch box and the
 * briefs. The last edition is kept, so a missing sensor (HA restarting) or no network still shows it. A tap opens it.
 */
class HeraldWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, force = true, done = goAsync())
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = draw(c)
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        when (i.action) { // for every Herald widget
            FLIP_LANG -> { prefs(c).edit().putBoolean(LANG, !spanish(c)).apply(); draw(c) }
            FLIP_MODE -> { prefs(c).edit().putBoolean(NIGHT, !night(c)).apply(); draw(c) }
        }
    }

    /** One edition, in one language, with the HA page's labels (herald.py UI). kpis: (figure, label). */
    internal class Page(val no: String, val date: String, val edition: String, val masthead: String, val tags: Pair<String, String>,
                        val headline: String, val subhead: String, val lead: String, val kpis: List<Pair<String, String>>,
                        val watch: String, val briefs: String, val labels: Triple<String, String, String>)

    companion object {
        private const val CACHE = "herald_cache"; private const val AT = "herald_at"
        private const val FLIP_LANG = "com.mau.hub.HERALD_FLIP"; private const val LANG = "herald_es"
        private const val FLIP_MODE = "com.mau.hub.HERALD_MODE"; private const val NIGHT = "herald_night"
        private const val LOGO_ENTITY = "person.maucasaos" // herald.py's logo_entity: its picture is the masthead logo

        private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)
        /** Spanish: the switch's last choice, else the device's language. */
        private fun spanish(c: Context) = prefs(c).getBoolean(LANG, Locale.getDefault().language == "es")
        /** Night: the switch's last choice, else the device's dark mode. */
        private fun night(c: Context) = prefs(c).getBoolean(NIGHT,
            c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO)
        private fun logoFile(c: Context) = File(c.filesDir, "herald_logo.png")

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
                n.optString("temperature").takeIf { it.isNotEmpty() }?.let { "$it°C" to n.optString("weather") },
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
                if (es) "Red · Servidores · Dispositivos" to "Todas las noticias que caben en la LAN"
                else "Network · Servers · Devices" to "All the news that fits the LAN",
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
                    if (System.currentTimeMillis() - logoFile(app).lastModified() > 24 * 3600_000) runCatching { fetchLogo(app) }
                    draw(app)
                } finally { done?.finish() }
            }.start()
        }

        /** The masthead logo, as herald.py takes it: the logo person's picture, cut round, kept for a day. */
        private fun fetchLogo(c: Context) {
            val base = HaWidget.base(c)
            val pic = JSONObject(Sources.get("$base/api/states/$LOGO_ENTITY", HaWidget.auth(c))).optJSONObject("attributes")
                ?.optString("entity_picture")?.takeIf { it.isNotEmpty() } ?: return
            val bytes = (java.net.URL(base + pic).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 15000
                HaWidget.auth(c).forEach(::setRequestProperty)
                try { inputStream.readBytes() } finally { disconnect() }
            }
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val side = minOf(src.width, src.height); val out = 192
            val round = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(round).apply {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                drawCircle(out / 2f, out / 2f, out / 2f, paint)
                paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                drawBitmap(src, android.graphics.Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2),
                    android.graphics.Rect(0, 0, out, out), paint)
            }
            logoFile(c).outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        private fun draw(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val cached = prefs(c).getString(CACHE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            m.getAppWidgetIds(ComponentName(c, HeraldWidget::class.java)).forEach { m.updateAppWidget(it, views(c, cached, m.getAppWidgetOptions(it))) }
        }

        private fun views(c: Context, o: JSONObject?, opts: Bundle) = RemoteViews(c.packageName, R.layout.herald_widget).apply {
            val es = spanish(c); val night = night(c)
            // the HA page's colours: --paper, --ink, --muted, --accent, --box
            val paper = if (night) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
            val ink = if (night) 0xFFE1E1E1.toInt() else 0xFF000000.toInt()
            val muted = if (night) 0xFF9B9B9B.toInt() else 0xFF555555.toInt()
            val accent = if (night) 0xFFE07A6A.toInt() else 0xFF8B1E1E.toInt()
            val box = if (night) 0xFF1C1C1C.toInt() else 0xFFF1F1F1.toInt()
            val locale = if (es) Locale("es") else Locale.ENGLISH
            setInt(R.id.herald, "setBackgroundResource", if (night) R.drawable.herald_bg_night else R.drawable.herald_bg_day)
            val pg = o?.let { page(it, es) }
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 400)
            val size = when { h < 190 -> 0; h < 360 -> 1; else -> 2 } // small, medium, large
            fun text(id: Int, value: CharSequence?, colour: Int, from: Int = 0) {
                setViewVisibility(id, if (size >= from && !value.isNullOrBlank()) View.VISIBLE else View.GONE)
                setTextViewText(id, value ?: ""); setTextColor(id, colour)
            }
            fun show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
            fun bold(s: String, rel: Float, colour: Int) = SpannableStringBuilder(s).apply {
                setSpan(RelativeSizeSpan(rel), 0, length, 0); setSpan(ForegroundColorSpan(colour), 0, length, 0)
                setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            }
            listOf(R.id.herald_rule1, R.id.herald_rule2, R.id.herald_rule3, R.id.herald_numbers_rule, R.id.herald_watch_rule, R.id.herald_briefs_rule)
                .forEach { setInt(it, "setBackgroundColor", ink) }
            text(R.id.herald_no, pg?.no, muted); text(R.id.herald_date, pg?.date, muted); text(R.id.herald_edition, pg?.edition, muted)

            // the switches (the chosen half filled with ink, like the page's pressed button) round the logo
            show(R.id.herald_top, size >= 1)
            fun half(id: Int, on: Boolean, action: String) {
                setInt(id, "setBackgroundResource", if (!on) 0 else if (night) R.drawable.herald_pill_on_night else R.drawable.herald_pill_on_day)
                setTextColor(id, if (on) paper else ink)
                setOnClickPendingIntent(id, PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, HeraldWidget::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            half(R.id.herald_night, night, FLIP_MODE); half(R.id.herald_day, !night, FLIP_MODE)
            half(R.id.herald_en, !es, FLIP_LANG); half(R.id.herald_es, es, FLIP_LANG)
            val logo = logoFile(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            if (logo != null) setImageViewBitmap(R.id.herald_logo, logo)
            show(R.id.herald_logo, logo != null)

            text(R.id.herald_masthead, pg?.masthead ?: if (es) "Heraldo del Hogar" else "Home Herald", ink)
            show(R.id.herald_tags, size >= 1 && pg != null)
            text(R.id.herald_tag1, pg?.tags?.first, muted); text(R.id.herald_tag2, pg?.tags?.second, muted)
            text(R.id.herald_headline, pg?.headline ?: if (es) "Aún no hay edición: guarda tu token de Home Assistant en Conectar"
                else "No edition yet: save your Home Assistant token on the Connect page", ink)
            text(R.id.herald_subhead, pg?.subhead, muted, 1)
            // the lead's first letter as a drop cap in the accent colour, like the page
            text(R.id.herald_lead, pg?.lead?.takeIf { it.isNotBlank() }?.let { l -> bold(l.take(1), 2.2f, accent).append(l.drop(1)) }, ink, 2)

            val kpis = pg?.kpis.orEmpty()
            show(R.id.herald_numbers_box, size >= 1 && kpis.isNotEmpty())
            setInt(R.id.herald_numbers_box, "setBackgroundColor", box)
            text(R.id.herald_numbers_h, pg?.labels?.first, ink)
            show(R.id.herald_krow2, kpis.size > 4)
            listOf(R.id.herald_k1, R.id.herald_k2, R.id.herald_k3, R.id.herald_k4, R.id.herald_k5, R.id.herald_k6, R.id.herald_k7, R.id.herald_k8)
                .forEachIndexed { i, id ->
                    val kpi = kpis.getOrNull(i)
                    setTextViewText(id, kpi?.let { (figure, label) -> bold(figure, 2.3f, accent).append("\n").append(label.uppercase(locale)) } ?: "")
                    setTextColor(id, muted)
                    setViewVisibility(id, if (kpi != null) View.VISIBLE else View.INVISIBLE) // empty cells keep the 4-across grid
                }
            show(R.id.herald_watch_box, size >= 2 && !pg?.watch.isNullOrBlank())
            setInt(R.id.herald_watch_box, "setBackgroundColor", box)
            setInt(R.id.herald_watch_bar, "setBackgroundColor", accent)
            text(R.id.herald_watch_h, pg?.labels?.second, ink); text(R.id.herald_watch, pg?.watch, ink)
            show(R.id.herald_briefs_box, size >= 2 && !pg?.briefs.isNullOrBlank())
            text(R.id.herald_briefs_h, pg?.labels?.third, ink); text(R.id.herald_briefs, pg?.briefs, ink)
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
