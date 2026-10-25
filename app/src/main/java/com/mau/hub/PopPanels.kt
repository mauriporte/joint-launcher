package com.mau.hub

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * A card that pops out of the button you pressed and shrinks back into it: tap beside it, swipe it away or Back. On the
 * phone it covers Home's whole top (clock, date, weather, the buttons); on the TV it's a narrower card at the top right
 * ([width], [gravity]). Now Playing comes out of the music note, the forecast out of the weather. How solid it is and
 * its language are Settings (Cards, Language).
 */
open class PopPanel(protected val a: MainActivity) {
    protected val panel = LinearLayout(a).apply {
        setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        background = GradientDrawable().apply { setColor(0xE6101012.toInt()); cornerRadius = a.dp(24).toFloat() }
        isClickable = true // taps on it stay in it
    }
    val view: View = FrameLayout(a).apply {
        setOnClickListener { close() } // a tap beside the panel puts it away
        addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = a.dp(8); rightMargin = a.dp(8); topMargin = a.dp(8) })
        visibility = View.GONE
    }
    val isOpen get() = view.visibility == View.VISIBLE
    /** The card reaches at least down to this view's bottom (the phone: Home's date), so the whole top is covered. */
    var coverTo: View? = null
    /** The card's width in px; -1 = the whole width (the phone). The TV sets a fixed width. */
    var width = -1
    /** Where the card sits: the top (the phone), or e.g. top right on the TV. */
    var gravity = Gravity.TOP

    protected open fun update() = Unit

    /** Opens it growing out of [from]'s centre. */
    fun open(from: View? = null) {
        update()
        if (isOpen) return
        (panel.layoutParams as FrameLayout.LayoutParams).let { it.width = width; it.gravity = gravity }
        panel.requestLayout()
        val inView = IntArray(2).also(view::getLocationOnScreen)
        val btn = IntArray(2).also { from?.getLocationOnScreen(it) }
        (panel.background as GradientDrawable).setColor(Color.argb(solid(a.hub) * 255 / 100, 16, 16, 18))
        coverTo?.let { c -> panel.minimumHeight = IntArray(2).also(c::getLocationOnScreen)[1] + c.height - inView[1] }
        view.visibility = View.VISIBLE
        // where the card will be: its left edge is the margin, or (anchored right) the far edge minus its width
        val left = if (width < 0 || gravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.END) a.dp(8) else view.width - a.dp(8) - width
        panel.pivotX = btn[0] - inView[0] - left + (from?.width ?: 0) / 2f
        panel.pivotY = btn[1] - inView[1] - a.dp(8) + (from?.height ?: 0) / 2f
        panel.scaleX = 0.1f; panel.scaleY = 0.1f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun close() {
        if (!isOpen) return
        panel.animate().scaleX(0.1f).scaleY(0.1f).alpha(0f).setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { view.visibility = View.GONE }.start()
    }

    companion object {
        /** Settings → "Cards: N % solid" (40-100). The phone's older "darken" slider carries over. */
        fun solid(hub: Hub) = hub.prefs.getInt("pop_opacity", hub.prefs.getInt("card_dim", 90)).coerceIn(40, 100)
    }
}

/** The cards' words in the launcher's language (Settings → Language, see [Hub.lang]); never chosen on the cards. */
object CardText {
    fun es(hub: Hub) = hub.lang() == "es"
    fun locale(hub: Hub): Locale = if (es(hub)) Locale("es") else Locale.ENGLISH
    fun t(hub: Hub, en: String, es: String) = if (es(hub)) es else en
}

/**
 * Now Playing, out of the music note: what any app is playing (cover, title, artist, which app), with previous /
 * play-pause / next. A tap on the card opens the player. Reads media sessions, which Android only allows with
 * notification access.
 */
class NowPanel(a: MainActivity) : PopPanel(a) {
    private val art = ImageView(a).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = a.dp(16).toFloat(); setColor(0x22FFFFFF) }
    }
    private val title = a.text(18f, Color.WHITE).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val artist = a.text(14f, TEXT_SOFT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val source = a.text(12f, TEXT_MUTED)
    private val prev = control("⏮") { it.transportControls.skipToPrevious() }
    private val playPause = control("▶") { c ->
        if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    private val next = control("⏭") { it.transportControls.skipToNext() }
    private val controls = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        listOf(prev, playPause, next).forEach { addView(it, LinearLayout.LayoutParams(0, a.dp(48), 1f)) }
    }
    private val access = a.button("Allow notification access") {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    init {
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.addView(art, LinearLayout.LayoutParams(a.dp(132), a.dp(132)).apply { marginEnd = a.dp(16) })
        panel.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(2) })
            addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(2) })
            addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) })
            addView(access, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.setOnClickListener { a.nowPlaying.current?.let { c -> close(); a.nowPlaying.openPlayer(c) } } // the whole card opens what's playing
    }

    /** Fills it from the playing session; called on open and whenever playback changes. */
    public override fun update() {
        val hub = a.hub
        fun t(en: String, es: String) = CardText.t(hub, en, es)
        val c = a.nowPlaying.current
        val allowed = a.nowPlaying.allowed
        access.text = t("Allow notification access", "Permitir acceso a las notificaciones")
        access.visibility = if (allowed) View.GONE else View.VISIBLE
        controls.visibility = if (c != null) View.VISIBLE else View.GONE
        prev.contentDescription = t("Previous", "Anterior"); next.contentDescription = t("Next", "Siguiente")
        playPause.contentDescription = t("Play or pause", "Reproducir o pausar")
        val md = c?.metadata
        title.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title
            ?: if (allowed) t("Nothing is playing", "No se está reproduciendo nada")
            else t("Joint Launcher needs notification access to see what's playing", "Joint Launcher necesita acceso a las notificaciones para ver qué suena")
        artist.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle ?: ""
        source.text = c?.packageName?.let { pkg -> runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() } ?: ""
        val cover = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
        if (cover != null) art.setImageBitmap(cover)
        else art.setImageDrawable(c?.packageName?.let { runCatching { a.packageManager.getApplicationIcon(it) }.getOrNull() })
        playPause.text = if (c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) "⏸" else "▶"
    }

    private fun control(glyph: String, act: (android.media.session.MediaController) -> Unit) = a.text(30f, Color.WHITE).apply {
        text = glyph; gravity = Gravity.CENTER; isClickable = true
        setOnClickListener { a.nowPlaying.current?.let(act) }
    }
}

/**
 * The forecast, out of the weather: now (feels like, humidity, wind, rain), today's high and low, sunrise and sunset and
 * the next 12 hours; the arrow drops the next 5 days down. Open-Meteo, the place set in Settings (else found from the
 * connection). "Weather app" opens the app picked for the weather.
 */
class WeatherPanel(a: MainActivity) : PopPanel(a) {
    private val now = a.text(44f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val what = a.text(15f, Color.WHITE)
    private val facts = a.text(13f, TEXT_SOFT).apply { setLineSpacing(0f, 1.2f) }
    private val hours = LinearLayout(a)
    private val days = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val more: TextView = a.text(20f, TEXT_SOFT).apply {
        text = "▾"; gravity = Gravity.CENTER; isClickable = true
        setOnClickListener { showDays(days.visibility != View.VISIBLE) }
    }
    private val appLink = a.text(13f, TEXT_MUTED).apply {
        isClickable = true
        setOnClickListener { close(); a.topBar.openApp("weather", "weather", listOf("weather", "clima", "tiempo")) } // asks which the first time
    }

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(now, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(16) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(what); addView(facts)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        panel.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(hours) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        panel.addView(days, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        // two steps: the card opens with now and the hours; the arrow drops the next days down (and folds them back)
        panel.addView(FrameLayout(a).apply {
            addView(more, FrameLayout.LayoutParams(a.dp(48), a.dp(36), Gravity.CENTER))
            addView(appLink, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4) })
    }

    private fun t(en: String, es: String) = CardText.t(a.hub, en, es)

    private fun showDays(on: Boolean) {
        days.visibility = if (on) View.VISIBLE else View.GONE
        more.text = if (on) "▴" else "▾"; more.contentDescription = if (on) t("Hide the next days", "Ocultar los próximos días") else t("Next days", "Próximos días")
    }

    override fun update() {
        showDays(false) // every open starts folded
        appLink.text = t("Weather app ›", "App del tiempo ›")
        if (now.text.isNullOrEmpty()) what.text = t("Loading the forecast…", "Cargando el pronóstico…")
        val hub = a.hub
        val locale = CardText.locale(hub)
        hub.pool.execute {
            val w = runCatching {
                val lat = hub.prefs.getString("lat", null)?.toDouble(); val lon = hub.prefs.getString("lon", null)?.toDouble()
                val (la, lo) = if (lat != null && lon != null) lat to lon else Sources.locate()
                Sources.forecast(la, lo, locale)
            }.getOrNull()
            hub.main.post { if (w != null) show(w) else what.text = t("Can't get the forecast right now", "No se puede obtener el pronóstico ahora") }
        }
    }

    private fun show(w: Sources.Forecast) {
        now.text = "${w.temp}°"
        what.text = "${w.icon}  ${w.summary}"
        facts.text = t("Feels", "Sensación") + " ${w.feels}° · ↑${w.high}° ↓${w.low}°\n" +
            t("Humidity", "Humedad") + " ${w.humidity}% · " + t("Wind", "Viento") + " ${w.wind} km/h · " + t("Rain", "Lluvia") + " ${w.rain}%\n" +
            t("Sunrise", "Amanecer") + " ${w.sunrise} · " + t("Sunset", "Atardecer") + " ${w.sunset}"
        hours.removeAllViews()
        w.hours.forEach { h ->
            hours.addView(a.text(13f, Color.WHITE).apply {
                gravity = Gravity.CENTER; text = "${h.time}\n${h.icon}\n${h.temp}°" + if (h.rain > 0) "\n${h.rain}%" else ""
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(a.dp(52), -2))
        }
        days.removeAllViews()
        w.days.forEach { d ->
            days.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(3), 0, a.dp(3))
                addView(a.text(14f, Color.WHITE).apply { text = d.name }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.text(16f, Color.WHITE).apply { text = d.icon }, LinearLayout.LayoutParams(a.dp(36), -2))
                addView(a.text(13f, TEXT_SOFT).apply { text = if (d.rain > 0) "${d.rain}%" else ""; gravity = Gravity.END }, LinearLayout.LayoutParams(a.dp(44), -2))
                addView(a.text(14f, Color.WHITE).apply { text = "${d.high}° / ${d.low}°"; gravity = Gravity.END }, LinearLayout.LayoutParams(a.dp(84), -2))
            })
        }
    }
}
