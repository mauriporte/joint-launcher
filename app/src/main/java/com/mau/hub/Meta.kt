package com.mau.hub

import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import java.util.Locale

/** The line under a title (service badge, IMDb and Rotten Tomatoes chips, subtitle) and the label above it. */
class Meta(private val hub: Hub) {
    // ratings: from the source when it has them, otherwise looked up once per IMDb id
    private val ratingsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Double?, Int?>>()

    private fun ratingsOf(item: Item): Pair<Double?, Int?> {
        val cached = item.imdbId?.let { ratingsCache[it] }
        return (item.imdb ?: cached?.first) to (item.rt ?: cached?.second)
    }

    /** Looks the ratings up if the source didn't include them; then() runs on the UI thread once they're in. */
    fun withRatings(item: Item, then: () -> Unit) {
        val id = item.imdbId ?: return
        if ((item.imdb != null && item.rt != null) || ratingsCache.containsKey(id)) return
        hub.pool.execute {
            ratingsCache[id] = runCatching { Sources.ratings(item.title, id, hub.country) }.getOrNull() ?: (null to null)
            hub.main.post(then)
        }
    }

    /** Source badge, IMDb and Rotten Tomatoes chips, then the subtitle. */
    fun fill(row: LinearLayout, item: Item?, big: Boolean = false) {
        row.removeAllViews()
        item ?: return
        val c = row.context
        val size = if (big) 14f else 12f
        fun add(v: View) = row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = c.dp(8) })
        add(c.badge(size).apply { text = item.source.label; paintBadge(this, item.source) })
        val (imdb, rt) = ratingsOf(item)
        imdb?.let { add(c.chip("IMDb  ${"%.1f".format(Locale.US, it)}", IMDB_YELLOW, Color.BLACK, size)) }
        rt?.let { add(c.chip(if (it >= 60) "🍅  $it%" else "RT  $it%", if (it >= 60) RT_FRESH else RT_ROTTEN, Color.WHITE, size)) }
        if (item.subtitle.isNotEmpty()) add(c.text(size + 1, TEXT_MUTED).apply { text = item.subtitle; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    companion object {
        /** Netflix-style label over the title, e.g. "NETFLIX · SERIES". */
        fun kicker(item: Item): String {
            val uri = ((item.alt ?: item.play) as? Play.Link)?.uri.orEmpty()
            val kind = when {
                "/series/" in uri -> "SERIES"
                "/movie/" in uri -> "MOVIE"
                item.play is Play.JellyLive -> "LIVE TV"
                item.source == Source.JELLYFIN && item.subtitle.startsWith("S") && "E" in item.subtitle.take(6) -> "EPISODE"
                item.source == Source.LIVE -> if (item.subtitle.startsWith("●")) "LIVE NOW" else "UPCOMING"
                else -> null
            }
            return listOfNotNull(item.source.label.uppercase(), kind).joinToString("  ·  ")
        }

        /** "167 min" -> "2h 47m". */
        fun prettyRuntime(r: String): String {
            val min = r.filter(Char::isDigit).toIntOrNull() ?: return r
            return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
        }
    }
}
