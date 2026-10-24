package com.mau.hub

import android.app.Activity
import android.app.AlertDialog

enum class Tab(val label: String) {
    HOME("Home"), MOVIES("Movies"), SERIES("Series"), LIVE("Live TV"), SPORTS("Sports"), MUSIC("Music"), VIDEOS("Videos"), APPS("Apps")
}

/** `installed` = the app the row opens is on the TV; that decides whether the row is on by default. */
class RowDef(
    val title: String, val installed: Boolean, val fetchers: List<() -> List<Item>>, val top10: Boolean = false,
    val pkg: String? = null, // the app the row belongs to: home rows follow the sidebar's app order
)

/** Which rows exist for Home, each content-type tab and each single-app view, and in what order. */
class Catalog(private val hub: Hub, private val apps: Apps) {
    private val jfFields = "fields=Overview,ProviderIds,CriticRating"
    private val year get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    /** Tabs only for content you have (no Music tab without Spotify or Jellyfin, and so on). */
    fun tabs() = Tab.entries.filter {
        when (it) {
            Tab.LIVE -> hub.jfKey.isNotEmpty()
            Tab.SPORTS -> hub.installed(Links.GEESPORTS)
            Tab.MUSIC -> hub.connected("spotify") || hub.jfKey.isNotEmpty()
            Tab.VIDEOS -> hub.connected("google")
            else -> true
        }
    }

    private fun MutableList<RowDef>.r(title: String, top10: Boolean = false, fetch: () -> List<Item>) = add(RowDef(title, true, listOf(fetch), top10))

    private fun MutableList<RowDef>.spotifyRows() {
        r("New releases from artists you follow · Spotify") { Sources.spotifyNewReleases(hub.token("spotify")!!) }
        r("Your playlists · Spotify") { Sources.spotifyPlaylists(hub.token("spotify")!!) }
        r("Liked albums · Spotify") { Sources.spotifySavedAlbums(hub.token("spotify")!!) }
        r("Your top artists · Spotify") { Sources.spotifyTopArtists(hub.token("spotify")!!) }
        r("Artists you follow · Spotify") { Sources.spotifyArtists(hub.token("spotify")!!) }
    }

    /** Rows for one content type, gathered from every source you have. */
    fun tabRows(t: Tab, gen: Int): List<RowDef> = buildList {
        val jellyfin = hub.jfKey.isNotEmpty()
        val country = hub.country
        val providers = PROVIDERS.filter { hub.installed(it.pkg) || "Popular on ${it.source.label}" in extraRows() }
        when (t) {
            Tab.MOVIES, Tab.SERIES -> {
                val movie = t == Tab.MOVIES
                val word = if (movie) "Movies" else "Series"
                val kind = if (movie) "MOVIE" else "SHOW"
                providers.forEachIndexed { i, p ->
                    r(if (i == 0) "Top 10 ${word.lowercase()} on ${p.source.label}" else "$word on ${p.source.label}", top10 = i == 0) {
                        Sources.streaming(p, country, objectType = kind)
                    }
                }
                if (hub.installed(Links.STREMIO)) {
                    r("Popular ${word.lowercase()} on Stremio") { Sources.cinemeta(if (movie) "movie" else "series") }
                    r("New ${word.lowercase()} this year · Stremio") { Sources.cinemetaYear(if (movie) "movie" else "series", year) }
                }
                if (jellyfin) r("$word · Jellyfin") {
                    hub.jf("/Items?userId=%s&recursive=true&includeItemTypes=${if (movie) "Movie" else "Series"}&sortBy=DateCreated&sortOrder=Descending&limit=60&$jfFields")
                }
                if (jellyfin) r("${if (movie) "Movie" else "Series"} channels · Jellyfin") {
                    Sources.jellyfinLiveByNumber(hub.server, hub.jfKey, hub.jfUser(), LIVE_CATEGORIES.getValue(word))
                }
                // more of this type: one row per genre, across all your services at once
                if (providers.isNotEmpty()) Sources.GENRES.forEach { (code, name) ->
                    r(if (code == "doc") name else "$name ${word.lowercase()}") { Sources.streamingMix(providers, country, kind, code) }
                }
                providers.forEach { p ->
                    r("Top rated ${word.lowercase()} on ${p.source.label}") { Sources.streaming(p, country, objectType = kind, sortBy = "IMDB_SCORE") }
                }
            }
            Tab.LIVE -> {
                r("Live TV favourites · Jellyfin") { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 100, favouritesOnly = true) }
                r("Live TV · Jellyfin") { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 200) }
            }
            Tab.SPORTS -> Sources.SPORT_ROWS.forEach { (name, leagues) -> r("$name · next 7 days · GeeSports") { Sources.sports(leagues, days = 7) } }
            Tab.MUSIC -> {
                if (jellyfin) r("Music channels · Jellyfin") { Sources.jellyfinLiveByNumber(hub.server, hub.jfKey, hub.jfUser(), LIVE_CATEGORIES.getValue("Music")) }
                if (hub.connected("spotify")) spotifyRows()
            }
            Tab.VIDEOS -> {
                r("New from your subscriptions · YouTube") { hub.youtubeSubs(gen) }
                r("More to explore · YouTube") { hub.youtubeDiscover(gen, 10) }
            }
            Tab.HOME, Tab.APPS -> Unit
        }
    }

    /** One app only, with more of its content (picked in the sidebar). */
    fun appRows(f: String, gen: Int): List<RowDef> = buildList {
        val country = hub.country
        when (f) {
            "jellyfin" -> {
                r("Continue watching · Jellyfin") { hub.jf("/UserItems/Resume?userId=%s&limit=30&mediaTypes=Video&$jfFields") }
                r("Next up · Jellyfin") { hub.jf("/Shows/NextUp?userId=%s&limit=30&$jfFields") }
                r("Live TV favourites · Jellyfin") { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 100, favouritesOnly = true) }
                r("Live TV · Jellyfin") { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 200) }
                r("Recently added · Jellyfin") {
                    hub.jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie,Series&sortBy=DateLastContentAdded,DateCreated&sortOrder=Descending&limit=40&$jfFields")
                }
                r("Movies · Jellyfin") { hub.jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie&sortBy=SortName&limit=80&$jfFields") }
                r("Series · Jellyfin") { hub.jf("/Items?userId=%s&recursive=true&includeItemTypes=Series&sortBy=SortName&limit=80&$jfFields") }
                r("Favourites · Jellyfin") { hub.jf("/Items?userId=%s&recursive=true&filters=IsFavorite&limit=60&$jfFields") }
            }
            "stremio" -> {
                if (hub.stremioKey.isNotEmpty()) r("Continue watching · Stremio") { Sources.stremioContinue(hub.stremioKey) }
                r("Popular movies · Stremio") { Sources.cinemeta("movie") }
                r("Popular series · Stremio") { Sources.cinemeta("series") }
                r("New movies this year · Stremio") { Sources.cinemetaYear("movie", year) }
                r("New series this year · Stremio") { Sources.cinemetaYear("series", year) }
            }
            "youtube" -> {
                r("New from your subscriptions · YouTube") { hub.youtubeSubs(gen) }
                r("More to explore · YouTube") { hub.youtubeDiscover(gen, 10) }
            }
            "spotify" -> spotifyRows()
            "geesports" -> Sources.SPORT_ROWS.forEach { (name, leagues) -> r("$name · next 7 days · GeeSports") { Sources.sports(leagues, days = 7) } }
            else -> PROVIDERS.firstOrNull { it.source.name == f }?.let { p ->
                val l = p.source.label
                r("Top 10 on $l today", top10 = true) { Sources.streaming(p, country) }
                r("Movies on $l") { Sources.streaming(p, country, objectType = "MOVIE") }
                r("Series on $l") { Sources.streaming(p, country, objectType = "SHOW") }
                r("New releases on $l") { Sources.streaming(p, country, minYear = year - 1) }
                r("Top rated on IMDb · $l") { Sources.streaming(p, country, sortBy = "IMDB_SCORE") }
            }
        }
    }

    /** Every Home row we can fetch. Rows of apps that aren't installed start hidden but can be switched on in the Rows picker. */
    private fun available(gen: Int): List<RowDef> = buildList {
        fun opt(title: String, pkg: String, fetch: () -> List<Item>) = add(RowDef(title, hub.installed(pkg), listOf(fetch), pkg = pkg))
        val jellyfin = hub.jfKey.isNotEmpty()
        val country = hub.country
        val cw = buildList<() -> List<Item>> {
            if (jellyfin) add { hub.jf("/UserItems/Resume?userId=%s&limit=20&mediaTypes=Video&$jfFields") }
            if (jellyfin) add { hub.jf("/Shows/NextUp?userId=%s&limit=20&$jfFields") }
            if (hub.stremioKey.isNotEmpty()) add { Sources.stremioContinue(hub.stremioKey) }
        }
        // one tile per show: an episode you're partway through is on Jellyfin's Resume and Next up lists both (Resume comes first and wins)
        if (cw.isNotEmpty()) add(RowDef("Continue watching", true, listOf { Catalog.oneEach(cw.map { runCatching(it) }) }))
        if (jellyfin) {
            opt("Live TV favourites · Jellyfin", Links.JELLYFIN) { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 50, favouritesOnly = true) }
            opt("Live TV · Jellyfin", Links.JELLYFIN) { Sources.jellyfinLive(hub.server, hub.jfKey, hub.jfUser(), 40) }
        }
        // the first service you have gets a Netflix-style Top 10 row; the others a normal row
        val topPick = PROVIDERS.firstOrNull { hub.installed(it.pkg) }
        PROVIDERS.forEach { p ->
            if (p == topPick) add(RowDef("Top 10 on ${p.source.label} today", true, listOf { Sources.streaming(p, country) }, top10 = true, pkg = p.pkg))
            else opt("Popular on ${p.source.label}", p.pkg) { Sources.streaming(p, country) }
        }
        if (hub.connected("google")) {
            opt("New from your subscriptions · YouTube", Links.SMARTTUBE) { hub.youtubeSubs(gen) }
            opt("More to explore · YouTube", Links.SMARTTUBE) { hub.youtubeDiscover(gen, 6) }
        }
        // /Items/Latest ignores includeItemTypes on this server (returns music), so sort the library instead
        if (jellyfin) opt("Recently added · Jellyfin", Links.JELLYFIN) {
            hub.jf("/Items?userId=%s&recursive=true&includeItemTypes=Movie,Series&sortBy=DateLastContentAdded,DateCreated&sortOrder=Descending&limit=20&$jfFields")
        }
        opt("Popular movies on Stremio", Links.STREMIO) { Sources.cinemeta("movie") }
        opt("Popular series on Stremio", Links.STREMIO) { Sources.cinemeta("series") }
        if (hub.connected("spotify")) {
            opt("New releases from artists you follow · Spotify", Links.SPOTIFY) { Sources.spotifyNewReleases(hub.token("spotify")!!) }
            opt("Your playlists · Spotify", Links.SPOTIFY) { Sources.spotifyPlaylists(hub.token("spotify")!!) }
            opt("Liked albums · Spotify", Links.SPOTIFY) { Sources.spotifySavedAlbums(hub.token("spotify")!!) }
            opt("Your favourite artists · Spotify", Links.SPOTIFY) {
                val t = hub.token("spotify")!!
                (Sources.spotifyTopArtists(t) + Sources.spotifyArtists(t)).distinctBy { it.play }
            }
        }
        Sources.SPORT_ROWS.forEach { (name, leagues) -> opt("$name on GeeSports", Links.GEESPORTS) { Sources.sports(leagues) } }
    }

    // ---- Home row order and visibility ----

    private fun savedOrder() = hub.prefs.getString("row_order", "")!!.split("\n").filter { it.isNotEmpty() }

    /** Home rows grouped by app in the sidebar's order (Continue watching and Live TV stay on top); the saved order applies within an app. */
    fun homeRows(gen: Int): List<RowDef> {
        val rows = available(gen).associateBy { it.title }
        val side = apps.sidebar().map { it.pkg ?: it.logo?.pkg }
        fun rank(r: RowDef) = if (r.pkg == null || r.title.startsWith("Live TV")) -1 else side.indexOf(r.pkg).let { if (it < 0) side.size else it }
        return RowOrder.apply(rows.keys.toList(), savedOrder()).map(rows::getValue).sortedBy(::rank)
    }

    private fun saveOrder(titles: List<String>) {
        // rows not offered right now (e.g. account disconnected) keep their saved place at the end
        hub.prefs.edit().putString("row_order", (titles + savedOrder().filter { it !in titles }).joinToString("\n")).apply()
    }

    // Only deviations from the default are stored, so installing/uninstalling an app flips its rows' default.
    private fun hiddenRows(): Set<String> = hub.prefs.getStringSet("hidden_rows", emptySet())!!  // installed but switched off
    private fun extraRows(): Set<String> = hub.prefs.getStringSet("extra_rows", emptySet())!!    // not installed but switched on
    fun visible(r: RowDef) = if (r.installed) r.title !in hiddenRows() else r.title in extraRows()

    private fun setVisible(r: RowDef, on: Boolean) {
        val key = if (r.installed) "hidden_rows" else "extra_rows"
        val set = hub.prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (r.installed != on) set += r.title else set -= r.title // hidden_rows lists "off" rows, extra_rows lists "on" rows
        hub.prefs.edit().putStringSet(key, set).apply()
    }

    companion object {
        /** Several lists as one row without repeats (episodes carry their show's name as title); fails only if every list failed. */
        fun oneEach(results: List<Result<List<Item>>>): List<Item> {
            if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
            return results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.source to it.title }
        }
    }

    /** List of rows in Home order; pick one to show/hide or move it. onDone reloads Home (Done or Back). */
    fun pick(a: Activity, gen: Int, onDone: () -> Unit, select: Int = 0) {
        val all = homeRows(gen)
        val labels = all.map { r -> (if (visible(r)) "✓  " else "✗  ") + r.title + if (r.installed) "" else "  (app not installed)" }.toTypedArray()
        val dialog = AlertDialog.Builder(a).setTitle("Rows · pick one to show/hide or move it")
            .setItems(labels) { _, i -> rowActions(a, gen, onDone, all, i) }
            .setPositiveButton("Done") { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
        dialog.listView.setSelection(select) // keep your place after moving a row
    }

    private fun rowActions(a: Activity, gen: Int, onDone: () -> Unit, all: List<RowDef>, i: Int) {
        val r = all[i]
        val opts = arrayOf(if (visible(r)) "Hide" else "Show", "Move up", "Move down", "Move to top", "Move to bottom")
        AlertDialog.Builder(a).setTitle(r.title)
            .setItems(opts) { _, o ->
                if (o == 0) { setVisible(r, !visible(r)); pick(a, gen, onDone, i); return@setItems }
                val moved = RowOrder.move(all.map { it.title }, i, intArrayOf(RowOrder.UP, RowOrder.DOWN, RowOrder.TOP, RowOrder.BOTTOM)[o - 1])
                saveOrder(moved)
                pick(a, gen, onDone, moved.indexOf(r.title))
            }
            .setOnCancelListener { pick(a, gen, onDone, i) }
            .show()
    }
}
