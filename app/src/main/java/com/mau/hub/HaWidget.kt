package com.mau.hub

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One MauHome button: its name, icon, what it controls and how. entities: one, or a strip's outlets (any on = on). */
internal data class HaTile(val name: String, val icon: Int, val kind: Kind, val entities: List<String>, val guard: String? = null) {
    enum class Kind { SWITCH, LIGHT, TEMP, IR, TV, SPEAKER, CAMERA }
}

/**
 * MauHome: Mau's Google Home widget, button for button, wired to Home Assistant instead. See-through tiles with a
 * coloured outline (blue on, amber lights, green temperature, violet remote, grey off). Talks to HA's REST API with the
 * long-lived token entered on the Connect page; states refresh when the launcher comes back, after a tap, every 30
 * minutes, and when you tap the title. Android 12+ (both the tablet and the phone are).
 */
class HaWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, done = goAsync())
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = refresh(c, done = goAsync())
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == REFRESH) refresh(c, done = goAsync())
    }

    internal class Look(val on: Boolean, val colour: Int, val status: String)

    companion object {
        private const val REFRESH = "com.mau.hub.HA_REFRESH"
        private const val BLUE = 0xFF29B6F6.toInt(); private const val AMBER = 0xFFFFC107.toInt()
        private const val GREEN = 0xFF66BB6A.toInt(); private const val VIOLET = 0xFFAB47BC.toInt(); private const val GREY = 0xFF8A8A8A.toInt()

        private const val TV_STRIP = "switch.smart_switch_24080577293446540301c4e7ae08a829_outlet"
        private const val OFFICE_STRIP = "switch.smart_switch_24080534592012540301c4e7ae08a7e5_outlet"
        private fun s(name: String, icon: Int, vararg e: String, guard: String? = null) = HaTile(name, icon, HaTile.Kind.SWITCH, e.toList(), guard)

        private fun l(name: String, e: String) = HaTile(name, R.drawable.ic_ha_bulb, HaTile.Kind.LIGHT, listOf(e))
        private fun cam(name: String, e: String) = HaTile(name, R.drawable.ic_ha_camera, HaTile.Kind.CAMERA, listOf(e))
        private const val STRIP_GUARD = "This strip also powers the servers plugged into it (Home Assistant among them). Turn the whole strip off?"

        /**
         * Mau's Google Home favourites first, in their order (4 across; the three without a sure HA match are left out),
         * then his other everyday lights, plugs and modes, then the cameras. List from HA admin, 25/10/2026.
         */
        internal val TILES = listOf(
            s("Regleta TV", R.drawable.ic_ha_plug, TV_STRIP, guard = STRIP_GUARD),
            s("Regleta Oficina", R.drawable.ic_ha_plug, OFFICE_STRIP, guard = STRIP_GUARD.replace("Home Assistant", "the PC")),
            s("Escritorio", R.drawable.ic_ha_plug, "${OFFICE_STRIP}_3"),
            s("TV", R.drawable.ic_ha_tv, "${TV_STRIP}_2"),
            HaTile("Audio", R.drawable.ic_ha_music, HaTile.Kind.IR, listOf("button.audio_on_off")),
            s("Server Mini", R.drawable.ic_ha_server, "${TV_STRIP}_3", guard = "Server Mini runs Home Assistant and OMV. Turning it off takes both down, " +
                "and this widget can't turn it back on: the plug has to be switched on by hand."),
            HaTile("Termostato", R.drawable.ic_ha_thermo, HaTile.Kind.TEMP, listOf("sensor.termostato_temperatura")),
            s("Cargador", R.drawable.ic_ha_charger, "switch.regleta_multimedia_enchufe_1"),
            s("Monitor 1", R.drawable.ic_ha_monitor, "switch.enchufe_inteligente_2"), // HA's numbers are the other way round
            s("Monitor 2", R.drawable.ic_ha_monitor, "switch.enchufe_inteligente_1"),
            s("Luces Multimedia", R.drawable.ic_ha_bulb, "switch.regleta_multimedia_enchufe_2"),
            HaTile("Luces de Multimedia", R.drawable.ic_ha_bulb, HaTile.Kind.LIGHT, listOf("light.lucesmultimedia")),
            s("Cargador PC", R.drawable.ic_ha_charger, "${OFFICE_STRIP}_2"),
            s("PC Escritorio", R.drawable.ic_ha_server, "${OFFICE_STRIP}_4", guard = "PC Escritorio is the ServerStation. Turn it off?"),
            s("Regleta Monitor", R.drawable.ic_ha_plug, "${OFFICE_STRIP}_1", guard = "Regleta Monitor is on HA admin's do-not-switch list. Turn it off?"),
            HaTile("MauTV", R.drawable.ic_ha_tv, HaTile.Kind.TV, listOf("media_player.mautv_2")),
            HaTile("GoogleMini", R.drawable.ic_ha_speaker, HaTile.Kind.SPEAKER, listOf("media_player.googlemini")),
            s("Enchufe Comedor", R.drawable.ic_ha_plug, "switch.smart_switch_19123114000010251h3048e1e9159739_outlet"),
            s("Enchufe Habitación", R.drawable.ic_ha_plug, "switch.smart_switch_20061255443881251h4648e1e91f084e_outlet"),
            s("Enchufe Sofá", R.drawable.ic_ha_plug, "${TV_STRIP}_4"),
            // everyday, not in Google Home
            l("Luces Comedor", "light.lucescomedor"),
            l("Luces Dormitorio", "light.lucesdormitorio"),
            l("Lámpara Vestidor", "light.salon_lampara_vestidor"),
            l("Lámpara de Pie", "light.lampara_de_pie"),
            l("Lámpara de Lectura", "light.lampara_de_lectura"),
            l("Luz Habitación", "light.dormitorio_luz_habitacion_interruptor_1"),
            l("Luz Invitados", "light.luzinvitados"),
            s("Monitores", R.drawable.ic_ha_monitor, "switch.monitores"),
            s("Ambiente", R.drawable.ic_ha_plug, "switch.regleta_multimedia_enchufe_3"),
            s("Modo Noche", R.drawable.ic_ha_night, "switch.modo_noche"),
            s("Modo Película", R.drawable.ic_ha_movie, "switch.modo_peli"),
            s("Modo Ausente", R.drawable.ic_ha_away, "switch.modo_ausente"),
            // cameras: Entrada and Vestidor are Verisure (their still changes when it's captured in HA/Verisure)
            cam("Entrada", "camera.entrada"),
            cam("Vestidor", "camera.vestidor"),
            cam("Cámara Ziicam", "camera.camara_ziicam"),
            cam("Webcam MauStation", "camera.webcam_maustation"),
        )

        @Volatile private var states: Map<String, JSONObject> = emptyMap()
        @Volatile private var shots: Map<String, android.graphics.Bitmap> = emptyMap() // camera tiles: their latest still, small
        @Volatile private var problem: String? = null

        /** What a tile shows for these HA states. */
        internal fun look(t: HaTile, st: Map<String, JSONObject>): Look {
            if (t.entities.isEmpty()) return Look(false, GREY, "Sin asignar")
            val all = t.entities.mapNotNull { st[it] }
            val state = all.firstOrNull()?.optString("state")
            if (all.isEmpty() || all.all { it.optString("state") == "unavailable" }) return Look(false, GREY, if (st.isEmpty()) "—" else "No disponible")
            return when (t.kind) {
                HaTile.Kind.SWITCH -> all.any { it.optString("state") == "on" }.let { Look(it, if (it) BLUE else GREY, if (it) "Encendido" else "Apagado") }
                HaTile.Kind.LIGHT -> {
                    val members = all[0].optJSONObject("attributes")?.optJSONArray("entity_id")
                    val n = (0 until (members?.length() ?: 0)).count { st[members!!.getString(it)]?.optString("state") == "on" }
                    val group = members != null
                    if (state == "on") Look(true, AMBER, if (n > 0) "$n encendida${if (n > 1) "s" else ""}" else if (group) "Encendidas" else "Encendida")
                    else Look(false, GREY, if (group) "Apagadas" else "Apagada")
                }
                HaTile.Kind.TEMP -> {
                    val v = state?.toDoubleOrNull() ?: return Look(true, GREEN, "—")
                    val f = all[0].optJSONObject("attributes")?.optString("unit_of_measurement") == "°F"
                    Look(true, GREEN, String.format(Locale.ROOT, "%.1f °C", if (f) (v - 32) * 5 / 9 else v))
                }
                HaTile.Kind.IR -> Look(true, VIOLET, "Mando IR")
                HaTile.Kind.TV -> (state !in setOf("off", "standby", "unknown")).let { Look(it, if (it) BLUE else GREY, if (it) "Encendido" else "Apagado") }
                HaTile.Kind.CAMERA -> Look(true, BLUE, java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(java.util.Date())) // when the still was taken
                HaTile.Kind.SPEAKER -> when (state) {
                    "playing" -> Look(true, BLUE, "Reproduciendo")
                    "paused" -> Look(true, BLUE, "En pausa")
                    else -> Look(false, GREY, if (state == "off") "Apagado" else "Inactivo")
                }
            }
        }

        /** Reads every state from HA, then redraws every MauHome widget. delayMs: wait first (a device's cloud lags a tap). */
        fun refresh(c: Context, delayMs: Long = 0, done: android.content.BroadcastReceiver.PendingResult? = null) {
            val app = c.applicationContext
            val m = AppWidgetManager.getInstance(app)
            val ids = m.getAppWidgetIds(ComponentName(app, HaWidget::class.java))
            if (ids.isEmpty()) { done?.finish(); return }
            Thread {
                try {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    runCatching {
                        val arr = JSONArray(Sources.get("${base(app)}/api/states", auth(app)))
                        (0 until arr.length()).map(arr::getJSONObject).associateBy { it.getString("entity_id") }
                    }.onSuccess { states = it; problem = null }.onFailure { problem = msg(app, it) }
                    // cameras: a small still each (a widget's pictures all travel in one bundle, which has a size limit)
                    if (problem == null) shots = TILES.filter { it.kind == HaTile.Kind.CAMERA }.mapNotNull { t ->
                        snapshot(app, t.entities[0], 240)?.let { t.entities[0] to it }
                    }.toMap()
                    ids.forEach { m.updateAppWidget(it, views(app, m.getAppWidgetOptions(it))) }
                } finally { done?.finish() }
            }.start()
        }

        /** What a tap does; then the widget refreshes twice, as cloud devices report back a moment later. */
        internal fun tap(c: Context, t: HaTile) {
            val app = c.applicationContext
            val on = look(t, states).on
            val service = when (t.kind) {
                HaTile.Kind.SWITCH -> if (on) "switch/turn_off" else "switch/turn_on" // a strip: any on turns them all off
                HaTile.Kind.LIGHT -> "light/toggle"
                HaTile.Kind.TEMP -> null
                HaTile.Kind.IR -> "button/press"
                HaTile.Kind.TV -> "media_player/toggle"
                HaTile.Kind.SPEAKER -> "media_player/media_play_pause"
                HaTile.Kind.CAMERA -> null // HaTap shows it big instead
            }
            if (service == null || t.entities.isEmpty()) return refresh(app)
            Thread {
                runCatching { Sources.post("${base(app)}/api/services/$service", JSONObject().put("entity_id", JSONArray(t.entities)).toString(), auth(app)) }
                    .onFailure { problem = msg(app, it) }
                refresh(app, 1500); refresh(app, 6000)
            }.start()
        }

        /** Whether this tap needs a yes first: turning off a plug that powers a server. */
        internal fun guardFor(t: HaTile) = t.guard?.takeIf { look(t, states).on }

        /** A camera's still from HA, scaled down to about [width] px wide; null if it can't be had. Off the UI thread. */
        internal fun snapshot(c: Context, entity: String, width: Int): android.graphics.Bitmap? = runCatching {
            val conn = java.net.URL("${base(c)}/api/camera_proxy/$entity").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 12000
            auth(c).forEach(conn::setRequestProperty)
            val bytes = try { if (conn.responseCode >= 400) null else conn.inputStream.readBytes() } finally { conn.disconnect() } ?: return null
            val size = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit(maxOf(1, size.outWidth / width)); inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
        }.getOrNull()

        private fun token(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("ha_token", null)?.takeIf { it.isNotBlank() }
        private fun auth(c: Context) = mapOf("Authorization" to "Bearer ${token(c) ?: error("no token")}")
        private fun base(c: Context) = (Hub.haUrl() ?: error("no address")).trimEnd('/')
        private fun msg(c: Context, e: Throwable) = when {
            token(c) == null -> "Add your Home Assistant token on the Connect page"
            e is Sources.FetchError && e.code == 401 -> "Home Assistant refused the token"
            else -> "Can't reach Home Assistant"
        }

        private fun views(c: Context, opts: Bundle): RemoteViews = RemoteViews(c.packageName, R.layout.ha_widget).apply {
            setOnClickPendingIntent(R.id.ha_title, PendingIntent.getBroadcast(c, 0,
                Intent(c, HaWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setTextViewText(R.id.ha_msg, problem.orEmpty())
            setViewVisibility(R.id.ha_msg, if (problem == null) View.GONE else View.VISIBLE)
            if (Build.VERSION.SDK_INT < 31) return@apply
            // columns follow the widget's width: resize it for more or fewer per row
            setInt(R.id.ha_grid, "setNumColumns", (opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360) / 90).coerceIn(2, 8))
            setPendingIntentTemplate(R.id.ha_grid, PendingIntent.getActivity(c, 0, Intent(c, HaTap::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            val st = states
            setRemoteAdapter(R.id.ha_grid, RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1).apply {
                TILES.forEachIndexed { i, t -> addItem(i.toLong(), tile(c, t, look(t, st), i)) }
            }.build())
        }

        private fun tile(c: Context, t: HaTile, l: Look, i: Int) = RemoteViews(c.packageName, R.layout.ha_tile).apply {
            setInt(R.id.ha_tile, "setBackgroundResource", when (l.colour) {
                BLUE -> R.drawable.ha_tile_blue; AMBER -> R.drawable.ha_tile_amber; GREEN -> R.drawable.ha_tile_green
                VIOLET -> R.drawable.ha_tile_violet; else -> R.drawable.ha_tile_off
            })
            val shot = if (t.kind == HaTile.Kind.CAMERA) t.entities.firstOrNull()?.let { shots[it] } else null
            if (shot != null) setImageViewBitmap(R.id.ha_shot, shot)
            setViewVisibility(R.id.ha_shot, if (shot != null) View.VISIBLE else View.GONE)
            setInt(R.id.ha_badge, "setColorFilter", l.colour)
            setImageViewResource(R.id.ha_icon, t.icon)
            setInt(R.id.ha_icon, "setColorFilter", l.colour)
            setViewVisibility(R.id.ha_lock, if (t.guard != null) View.VISIBLE else View.GONE)
            setTextViewText(R.id.ha_name, t.name)
            setTextColor(R.id.ha_name, if (l.colour == GREY) GREY else 0xFFF2F2F2.toInt())
            setTextViewText(R.id.ha_status, l.status)
            setTextColor(R.id.ha_status, l.colour)
            setOnClickFillInIntent(R.id.ha_tile, Intent().putExtra("tile", i))
        }
    }
}

/** A tap on a MauHome tile: runs it, after a yes for the server plugs. No screen of its own otherwise. */
class HaTap : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val t = HaWidget.TILES.getOrNull(intent.getIntExtra("tile", -1)) ?: return finish()
        if (t.kind == HaTile.Kind.CAMERA) return camera(t)
        val guard = HaWidget.guardFor(t) ?: run { HaWidget.tap(this, t); return finish() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Turn off ${t.name}?").setMessage(guard)
            .setPositiveButton("Turn off") { _, _ -> HaWidget.tap(this, t) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .show()
    }

    /** A camera tile: the camera big, a new still every 2 seconds while it's open; tap or Back closes it. */
    private fun camera(t: HaTile) {
        val img = android.widget.ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(android.graphics.Color.BLACK) }
        var open = true
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(t.name).setView(img)
            .setOnDismissListener { open = false; finish() }.show()
        img.setOnClickListener { d.dismiss() }
        Thread {
            while (open) {
                HaWidget.snapshot(this, t.entities[0], resources.displayMetrics.widthPixels)?.let { b -> runOnUiThread { if (open) img.setImageBitmap(b) } }
                Thread.sleep(2000)
            }
        }.start()
    }
}
