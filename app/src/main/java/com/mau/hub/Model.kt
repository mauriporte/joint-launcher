package com.mau.hub

import android.graphics.drawable.Drawable

enum class Source(val label: String, val color: Int) {
    NETFLIX("Netflix", 0xFFE50914.toInt()),
    PRIME("Prime Video", 0xFF1A98FF.toInt()),
    DISNEY("Disney+", 0xFF01B4C9.toInt()), // teal, so it doesn't clash with Jellyfin's navy
    FILMIN("Filmin", 0xFFEC1C5B.toInt()),
    HULU("Hulu", 0xFFB4E61E.toInt()), // lime: Hulu's own green is too close to Spotify's
    PEACOCK("Peacock", 0xFFFF8A00.toInt()), // orange, one of the logo's feather colours and still unused
    STAN("Stan", 0xFFFFFFFF.toInt()), // white (Stan's logo is a white wordmark on blue)
    HBO("HBO Max", 0xFFD12AE0.toInt()), // violet-pink: HBO Max's purple, kept clear of Stremio's blue-purple
    STREMIO("Stremio", 0xFF7B5BF5.toInt()),
    JELLYFIN("Jellyfin", 0xFF1A2B8C.toInt()), // navy, distinct from Prime's light blue
    YOUTUBE("YouTube", 0xFFFF0033.toInt()),
    SPOTIFY("Spotify", 0xFF1DB954.toInt()),
    LIVE("GeeSports", 0xFFFFC400.toInt()),
    APP("App", 0xFF555560.toInt()),
}

sealed interface Play {
    data class App(val pkg: String) : Play
    data class Link(val uri: String, val pkg: String) : Play
    data class Jelly(val id: String, val posMs: Long, val folder: Boolean) : Play
    /** Jellyfin Live TV channel: the stream URL is negotiated with the server when you press play. */
    data class JellyLive(val channelId: String) : Play
    /** No IMDb id: look the exact title up in Stremio's catalogue when pressed. type = "movie" | "series". */
    data class StremioSearch(val title: String, val year: Int?, val type: String) : Play
}

/**
 * Catalog-only services, looked up through JustWatch; playback always opens the provider's own app.
 * A provider's rows appear automatically once its app is installed on the TV. New service = one line here
 * (+ its package in the manifest <queries>). JustWatch codes differ per country, hence the lists.
 */
data class Provider(
    val source: Source, val jwShortNames: List<String>, val jwTechNames: Set<String>, val pkg: String,
    val country: String? = null, // catalogue country when the service isn't in yours (Hulu is US-only)
)

val PROVIDERS = listOf(
    Provider(Source.NETFLIX, listOf("nfx"), setOf("netflix"), Links.NETFLIX),
    Provider(Source.FILMIN, listOf("fil", "fip"), setOf("filmin", "filminplus"), "com.filmin.androidtv"),
    // shortcut: these two package names are unchecked (apps not installed); verify with `pm list packages` when adding
    Provider(Source.PRIME, listOf("prv", "amp"), setOf("amazonprimevideo", "amazonprime"), "com.amazon.amazonvideo.livingroom"),
    Provider(Source.DISNEY, listOf("dnp"), setOf("disneyplus"), "com.disney.disneyplus"),
    Provider(Source.HULU, listOf("hlu"), setOf("hulu"), "com.hulu.livingroomplus", country = "US"),
    Provider(Source.PEACOCK, listOf("pct", "pcp"), setOf("peacocktv", "peacocktvpremium"), "com.peacocktv.peacockandroid", country = "US"),
    Provider(Source.STAN, listOf("stn"), setOf("stan"), "au.com.stan.and", country = "AU"),
    // shortcut: package name unchecked (app not installed); HBO Max's TV app has shipped as com.wbd.stream
    Provider(Source.HBO, listOf("mxx"), setOf("max"), "com.wbd.stream"),
)

/** Extra detail-screen info for a movie/series, from Stremio's catalogue (Cinemeta). */
data class MetaInfo(
    val description: String?, val seasons: Int, val episodes: Int, val runtime: String?,
    val genres: List<String>, val cast: List<String>, val creators: List<String>,
    val trailerYt: String?, val logo: String?, val awards: String?,
)

/** Items <-> JSON, so each row's last results can be saved and shown instantly on the next launch. */
object ItemJson {
    private fun play(p: Play?): org.json.JSONObject? = when (p) {
        null -> null
        is Play.App -> org.json.JSONObject(mapOf("k" to "app", "pkg" to p.pkg))
        is Play.Link -> org.json.JSONObject(mapOf("k" to "link", "uri" to p.uri, "pkg" to p.pkg))
        is Play.Jelly -> org.json.JSONObject(mapOf("k" to "jelly", "id" to p.id, "pos" to p.posMs, "folder" to p.folder))
        is Play.JellyLive -> org.json.JSONObject(mapOf("k" to "live", "id" to p.channelId))
        is Play.StremioSearch -> org.json.JSONObject(mapOf("k" to "search", "title" to p.title, "type" to p.type)).apply { p.year?.let { put("year", it) } }
    }

    private fun play(o: org.json.JSONObject?): Play? = when (o?.optString("k")) {
        "app" -> Play.App(o.getString("pkg"))
        "link" -> Play.Link(o.getString("uri"), o.getString("pkg"))
        "jelly" -> Play.Jelly(o.getString("id"), o.getLong("pos"), o.getBoolean("folder"))
        "live" -> Play.JellyLive(o.getString("id"))
        "search" -> Play.StremioSearch(o.getString("title"), if (o.has("year")) o.getInt("year") else null, o.getString("type"))
        else -> null
    }

    fun encode(items: List<Item>): String = org.json.JSONArray(items.map { i ->
        org.json.JSONObject().apply {
            put("source", i.source.name); put("title", i.title); put("play", play(i.play))
            put("subtitle", i.subtitle); put("description", i.description); put("wide", i.wide); put("square", i.square); put("sortKey", i.sortKey)
            i.poster?.let { put("poster", it) }; i.backdrop?.let { put("backdrop", it) }; play(i.alt)?.let { put("alt", it) }
            i.imdb?.let { put("imdb", it) }; i.rt?.let { put("rt", it) }; i.imdbId?.let { put("imdbId", it) }; i.type?.let { put("type", it) }
            if (i.fav) put("fav", true)
            i.tmdbId?.let { put("tmdbId", it) }
            i.artist?.let { put("artist", it) }
        }
    }).toString()

    fun decode(json: String): List<Item> {
        val a = org.json.JSONArray(json)
        return (0 until a.length()).mapNotNull { n ->
            val o = a.getJSONObject(n)
            fun s(k: String) = if (o.has(k)) o.getString(k) else null
            Item(
                Source.valueOf(o.getString("source")), o.getString("title"), play(o.getJSONObject("play")) ?: return@mapNotNull null,
                poster = s("poster"), backdrop = s("backdrop"), subtitle = o.optString("subtitle"), description = o.optString("description"),
                wide = o.optBoolean("wide"), square = o.optBoolean("square"), sortKey = o.optString("sortKey"), alt = play(o.optJSONObject("alt")),
                imdb = if (o.has("imdb")) o.getDouble("imdb") else null, rt = if (o.has("rt")) o.getInt("rt") else null,
                imdbId = s("imdbId"), type = s("type"), fav = o.optBoolean("fav"), tmdbId = s("tmdbId"), artist = s("artist"),
            )
        }
    }
}

/** Home row ordering, by row title. Pure so it can be unit-tested. */
object RowOrder {
    const val UP = 0
    const val DOWN = 1
    const val TOP = 2
    const val BOTTOM = 3

    /** Saved order first (dropping rows that no longer exist), then any rows not in it yet, in default order. */
    fun apply(defaults: List<String>, saved: List<String>) = saved.filter { it in defaults } + defaults.filter { it !in saved }

    fun move(titles: List<String>, i: Int, how: Int): List<String> {
        val out = titles.toMutableList()
        val t = out.removeAt(i)
        out.add(when (how) { UP -> (i - 1).coerceAtLeast(0); DOWN -> (i + 1).coerceAtMost(out.size); TOP -> 0; else -> out.size }, t)
        return out
    }
}

data class Item(
    val source: Source,
    val title: String,
    val play: Play,
    val poster: String? = null,
    val backdrop: String? = null,
    val subtitle: String = "",
    val description: String = "",
    val wide: Boolean = false,
    val square: Boolean = false,
    val sortKey: String = "", // ISO time, for merging rows from several feeds
    val alt: Play? = null, // where else to watch it (Stremio) when the service's own app isn't there
    val imdb: Double? = null, // IMDb score 0-10
    val rt: Int? = null, // Rotten Tomatoes Tomatometer %
    val imdbId: String? = null, // to look the scores up later when the source didn't include them
    val type: String? = null, // "movie" | "series", for the detail-screen lookup
    val fav: Boolean = false, // marked favourite in Jellyfin (Live TV channels)
    val tmdbId: String? = null, // what Jellyseerr requests are keyed on
    val artist: String? = null, // albums: main artist, to find the album on MusicBrainz for music requests

    val icon: Drawable? = null,
)
