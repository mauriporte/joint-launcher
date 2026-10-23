package com.mau.hub

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Network fetchers plus pure parsers (parsers are unit-tested). */
object Sources {
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private const val STREMIO_API = "https://api.strem.io/api"

    /**
     * Worker pool at background priority: on the TV's modest CPU, dozens of normal-priority network/JSON threads
     * starved the UI thread (5s of dropped frames while rows loaded).
     */
    fun bgPool(threads: Int): java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()) = http(url, null, headers)
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()) =
        http(url, body, headers + ("Content-Type" to "application/json"))

    /** A request that failed: which server, and either its HTTP status or the network error (no answer, timeout...). */
    class FetchError(val host: String, val code: Int?, cause: Throwable? = null) :
        Exception(code?.let { "HTTP $it from $host" } ?: "$host: ${cause?.message}", cause)

    private fun http(url: String, body: String?, headers: Map<String, String>): String {
        val u = URL(url)
        val host = if (u.port > 0) "${u.host}:${u.port}" else u.host
        return try {
            (u.openConnection() as HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 12000
                headers.forEach(::setRequestProperty)
                if (body != null) { requestMethod = "POST"; doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
                try {
                    if (responseCode >= 400) throw FetchError(host, responseCode)
                    inputStream.bufferedReader().readText()
                } finally { disconnect() }
            }
        } catch (e: java.io.IOException) { throw FetchError(host, null, e) }
    }

    /** What failed and why, in words, for a row that couldn't load. */
    fun explain(e: Throwable): String {
        if (e !is FetchError) return e.message ?: e.javaClass.simpleName
        val why = when (e.code) {
            null -> when (e.cause) {
                is java.net.UnknownHostException -> "can't find it (no internet, or the address is wrong)"
                is java.net.SocketTimeoutException -> "it took too long to answer"
                is java.net.ConnectException -> "nothing answered (server off, or the address is wrong)"
                else -> "network error: ${e.cause?.message}"
            }
            401, 403 -> "it rejected the sign-in (reconnect the account)"
            404 -> "it doesn't have this anymore"
            429 -> "too many requests, it asked to slow down"
            502, 503, 504 -> "it's down or still starting up"
            in 500..599 -> "it hit an internal error"
            else -> "it refused the request"
        }
        return e.host + (e.code?.let { " answered $it" } ?: "") + ": " + why
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONObject.str(k: String) = optString(k).takeUnless { it.isEmpty() || it == "null" }
    private fun JSONArray?.objs() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    // ---- Stremio (Cinemeta catalogs + account library) ----

    fun cinemeta(type: String, query: String? = null) =
        parseCinemeta(get("$CINEMETA/catalog/$type/top" + (query?.let { "/search=${enc(it)}" } ?: "") + ".json"), type)

    fun meta(type: String, imdbId: String) = parseMeta(get("$CINEMETA/meta/$type/$imdbId.json"))

    fun parseMeta(json: String): MetaInfo? {
        val m = JSONObject(json).optJSONObject("meta") ?: return null
        fun list(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()
        val eps = m.optJSONArray("videos").objs().filter { it.optInt("season") > 0 }
        return MetaInfo(
            description = m.str("description"),
            seasons = eps.map { it.optInt("season") }.distinct().size,
            episodes = eps.size,
            runtime = m.str("runtime"),
            genres = list("genres").take(3),
            cast = list("cast").take(4),
            creators = list("director").ifEmpty { list("writer") }.take(2), // series list creators under writer
            trailerYt = m.optJSONArray("trailerStreams").objs().firstNotNullOfOrNull { it.str("ytId") }
                ?: m.optJSONArray("trailers").objs().firstNotNullOfOrNull { it.str("source") },
            logo = m.str("logo"),
            awards = m.str("awards"),
        )
    }

    /** Cinemeta's "by year" catalogue, e.g. new movies this year. */
    fun cinemetaYear(type: String, year: Int) = parseCinemeta(get("$CINEMETA/catalog/$type/year/genre=$year.json"), type)

    fun parseCinemeta(json: String, type: String) = JSONObject(json).optJSONArray("metas").objs().take(30).map { m ->
        val id = m.getString("id")
        val genres = m.optJSONArray("genres")?.let { g -> (0 until minOf(2, g.length())).map(g::getString) }.orEmpty()
        Item(
            Source.STREMIO, m.str("name") ?: id, Play.Link(Links.stremioDetail(type, id), Links.STREMIO),
            poster = m.str("poster"), backdrop = m.str("background"),
            subtitle = (listOfNotNull(m.str("releaseInfo")) + genres).joinToString(" · "),
            description = m.str("description").orEmpty(),
            imdb = m.str("imdbRating")?.toDoubleOrNull(), imdbId = id.takeIf { it.startsWith("tt") }, type = type,
            tmdbId = m.optLong("moviedb_id").takeIf { it > 0 }?.toString(),
        )
    }

    /** Stremio id of the title with exactly this name (see [pickCinemetaMatch]), or null if there's no exact match. */
    fun stremioFind(title: String, year: Int?, type: String): String? =
        pickCinemetaMatch(get("$CINEMETA/catalog/$type/top/search=${enc(title)}.json"), title, year)

    /** Same words, ignoring case, accents and punctuation: "Amélie!" == "amelie". */
    fun normTitle(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Exact-name candidates only (no fuzzy guesses). Prefer the one released within a year of [year]
     * (remakes share names); with no year to compare, take it only if it's the single exact match.
     */
    fun pickCinemetaMatch(json: String, title: String, year: Int?): String? {
        val want = normTitle(title)
        val exact = JSONObject(json).optJSONArray("metas").objs().filter { normTitle(it.optString("name")) == want }
        fun yearOf(m: JSONObject) = (m.str("releaseInfo") ?: m.str("year"))?.take(4)?.toIntOrNull()
        val byYear = year?.let { y -> exact.firstOrNull { m -> yearOf(m)?.let { kotlin.math.abs(it - y) <= 1 } == true } }
        return (byYear ?: exact.singleOrNull()?.takeIf { year == null || yearOf(it) == null })?.str("id")
    }

    /** Returns the authKey; the password itself is never stored. */
    fun stremioLogin(email: String, password: String): String {
        val r = JSONObject(post("$STREMIO_API/login", JSONObject(mapOf("type" to "Login", "email" to email, "password" to password, "facebook" to false)).toString()))
        r.optJSONObject("error")?.let { error(it.optString("message", "login failed")) }
        return r.getJSONObject("result").getString("authKey")
    }

    fun stremioContinue(authKey: String) = parseStremioLibrary(
        post("$STREMIO_API/datastoreGet", JSONObject(mapOf("authKey" to authKey, "collection" to "libraryItem", "all" to true)).toString())
    )

    fun parseStremioLibrary(json: String) = JSONObject(json).optJSONArray("result").objs()
        .filter { !it.optBoolean("removed") && (it.optJSONObject("state")?.optLong("timeOffset") ?: 0) > 0 }
        .sortedByDescending { it.getJSONObject("state").optString("lastWatched") }
        .take(20)
        .map { o ->
            val id = o.getString("_id"); val type = o.optString("type", "movie")
            val video = o.getJSONObject("state").str("video_id")
            Item(
                Source.STREMIO, o.str("name") ?: id, Play.Link(Links.stremioDetail(type, id, video), Links.STREMIO),
                poster = o.str("poster"), backdrop = o.str("background"),
                subtitle = video?.split(":")?.takeIf { it.size == 3 }?.let { "S${it[1]}E${it[2]}" } ?: "Resume",
                imdbId = id.takeIf { it.startsWith("tt") }, type = type,
            )
        }

    // ---- Jellyfin ----

    // Quick Connect: TV shows a code, user approves it in another Jellyfin client; no password typed on the TV
    private fun jfClient(deviceId: String) =
        mapOf("Authorization" to "MediaBrowser Client=\"Joint Launcher\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"1.0\"")

    /** Returns (secret, code). */
    fun quickConnectStart(server: String, deviceId: String) =
        JSONObject(post("$server/QuickConnect/Initiate", "", jfClient(deviceId))).let { it.getString("Secret") to it.getString("Code") }

    fun quickConnectApproved(server: String, deviceId: String, secret: String) =
        JSONObject(get("$server/QuickConnect/Connect?secret=$secret", jfClient(deviceId))).optBoolean("Authenticated")

    /** Returns (accessToken, userId). */
    fun quickConnectFinish(server: String, deviceId: String, secret: String) =
        JSONObject(post("$server/Users/AuthenticateWithQuickConnect", JSONObject(mapOf("Secret" to secret)).toString(), jfClient(deviceId)))
            .let { it.getString("AccessToken") to it.getJSONObject("User").getString("Id") }

    fun jellyfinUser(server: String, key: String): String =
        // shortcut: first server user; add a user picker if the server has several profiles
        JSONArray(get("$server/Users", Links.jfAuth(key))).getJSONObject(0).getString("Id")

    fun jellyfin(server: String, key: String, pathAndQuery: String) =
        parseJellyfin(get(server + pathAndQuery, Links.jfAuth(key)), server)

    fun parseJellyfin(json: String, server: String): List<Item> {
        val arr = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json).getJSONArray("Items")
        return arr.objs().map { o ->
            val id = o.getString("Id")
            val series = o.str("SeriesName")
            val backdropId = when {
                (o.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0 -> id
                else -> o.str("ParentBackdropItemId")
            }
            Item(
                Source.JELLYFIN, series ?: o.getString("Name"),
                Play.Jelly(id, Links.ticksToMs(o.optJSONObject("UserData")?.optLong("PlaybackPositionTicks") ?: 0), o.optBoolean("IsFolder")),
                poster = Links.jfImage(server, o.str("SeriesId") ?: id),
                backdrop = backdropId?.let { Links.jfImage(server, it, "Backdrop") },
                subtitle = if (series != null) "S${o.optInt("ParentIndexNumber")}E${o.optInt("IndexNumber")} · ${o.getString("Name")}"
                else listOfNotNull(o.optInt("ProductionYear").takeIf { it > 0 }?.toString(), o.str("OfficialRating")).joinToString(" · "),
                description = o.str("Overview").orEmpty(),
                rt = o.optInt("CriticRating", -1).takeIf { it >= 0 }, // Jellyfin's critic rating is the Tomatometer
                imdbId = o.optJSONObject("ProviderIds")?.str("Imdb"),
                tmdbId = o.optJSONObject("ProviderIds")?.str("Tmdb"),
                type = when (o.optString("Type")) { "Movie" -> "movie"; "Series" -> "series"; else -> null }, // episodes: their IMDb id is the episode's, not the show's
            )
        }
    }

    // ---- Jellyseerr requests (movies and series; documentaries are filed under those) ----

    /** movie / tv / music path segment and request mediaType for one of our item types. */
    private fun seerrKind(type: String) = when (type) { "series" -> "tv"; "album" -> "music"; else -> "movie" }

    private fun seerrPath(type: String, id: String) = seerrKind(type) + "/" + id

    /** MusicBrainz release-group id of an album (what this Jellyseerr build requests music by), exact title + artist only. */
    fun mbAlbumId(artist: String, album: String): String? {
        val q = enc("releasegroup:\"${album.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\"")
        // MusicBrainz asks clients to identify themselves
        return parseMbAlbum(get("https://musicbrainz.org/ws/2/release-group/?query=$q&fmt=json&limit=10", mapOf("User-Agent" to "JointLauncher/1.0 (Android TV launcher)")), artist, album)
    }

    fun parseMbAlbum(json: String, artist: String, album: String): String? {
        val wantTitle = normTitle(album); val wantArtist = normTitle(artist)
        return JSONObject(json).optJSONArray("release-groups").objs().firstOrNull { g ->
            normTitle(g.optString("title")) == wantTitle &&
                g.optJSONArray("artist-credit").objs().any { normTitle(it.optString("name")) == wantArtist }
        }?.str("id")
    }

    /** Jellyseerr's media status for a title: 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available. null = not known to it. */
    fun seerrStatus(base: String, key: String, type: String, tmdbId: String): Int? =
        JSONObject(get("${base.trimEnd('/')}/api/v1/${seerrPath(type, tmdbId)}", mapOf("X-Api-Key" to key)))
            .optJSONObject("mediaInfo")?.optInt("status")?.takeIf { it > 0 }

    /** id = TMDB id for movies/series, MusicBrainz release-group id for albums. */
    fun seerrRequest(base: String, key: String, type: String, tmdbId: String) {
        val body = JSONObject(mapOf("mediaType" to seerrKind(type), "mediaId" to if (type == "album") tmdbId else tmdbId.toInt()))
        if (type == "series") body.put("seasons", "all")
        post("${base.trimEnd('/')}/api/v1/request", body.toString(), mapOf("X-Api-Key" to key))
    }

    /** Button label for a status, or null when there's nothing to request (already in your library). */
    fun seerrLabel(status: Int?): String? = when (status) {
        null, 1 -> "＋  Request in Jellyseerr"
        2, 3 -> "✓  Requested"
        4 -> "＋  Request the rest"
        else -> null // 5 = available in Jellyfin
    }

    // ---- Weather for the top bar (no API keys: location from the connection's IP, forecast from Open-Meteo) ----

    /** Returns (lat, lon). */
    fun locate(): Pair<Double, Double> {
        val j = JSONObject(get("http://ip-api.com/json/?fields=status,lat,lon"))
        if (j.optString("status") != "success") error("location unavailable")
        return j.getDouble("lat") to j.getDouble("lon")
    }

    /** Returns e.g. "⛅  12°". */
    fun weather(lat: Double, lon: Double) =
        parseWeather(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day"))

    fun parseWeather(json: String): String {
        val c = JSONObject(json).getJSONObject("current")
        return "${weatherIcon(c.getInt("weather_code"), c.optInt("is_day", 1) == 1)}  ${Math.round(c.getDouble("temperature_2m"))}°"
    }

    /** WMO weather codes (what Open-Meteo returns) -> a small symbol. */
    fun weatherIcon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    // ---- Jellyfin Live TV ----

    fun jellyfinLive(server: String, key: String, userId: String, limit: Int, favouritesOnly: Boolean = false) = parseLiveChannels(
        get("$server/LiveTv/Channels?userId=$userId&addCurrentProgram=true&enableImages=true&limit=$limit" +
            if (favouritesOnly) "&isFavorite=true" else "", Links.jfAuth(key)),
        server,
    )

    /** The channels whose Number is in [numbers], in that list's order (see LIVE_CATEGORIES). */
    fun jellyfinLiveByNumber(server: String, key: String, userId: String, numbers: List<String>): List<Item> =
        pickChannels(get("$server/LiveTv/Channels?userId=$userId&addCurrentProgram=true&enableImages=true", Links.jfAuth(key)), numbers, server)

    fun pickChannels(json: String, numbers: List<String>, server: String): List<Item> {
        val byNumber = JSONObject(json).optJSONArray("Items").objs().associateBy { it.optString("Number") }
        return parseLiveChannels(JSONObject().put("Items", JSONArray(numbers.mapNotNull(byNumber::get))).toString(), server)
    }

    /** Mark / unmark a channel (or any item) as favourite on the Jellyfin account. */
    fun jellyfinFavourite(server: String, key: String, userId: String, itemId: String, on: Boolean) {
        (URL("$server/UserFavoriteItems/$itemId?userId=$userId").openConnection() as HttpURLConnection).run {
            requestMethod = if (on) "POST" else "DELETE"
            Links.jfAuth(key).forEach(::setRequestProperty)
            try { if (responseCode >= 400) error("HTTP $responseCode") } finally { disconnect() }
        }
    }

    fun parseLiveChannels(json: String, server: String): List<Item> {
        val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return JSONObject(json).optJSONArray("Items").objs().map { c ->
            val now = c.optJSONObject("CurrentProgram")
            val until = now?.str("EndDate")?.let { runCatching { hhmm.format(iso.parse(it.take(19))!!) }.getOrNull() }
            val programImg = now?.takeIf { it.optJSONObject("ImageTags")?.has("Primary") == true }?.str("Id")
            Item(
                Source.JELLYFIN, listOfNotNull(c.str("Number"), c.optString("Name")).joinToString("  "), Play.JellyLive(c.getString("Id")),
                poster = Links.jfImage(server, programImg ?: c.getString("Id")),
                backdrop = programImg?.let { Links.jfImage(server, it, "Primary") },
                subtitle = now?.str("Name")?.let { "Now: $it" + (until?.let { u -> " · until $u" } ?: "") } ?: "Live",
                description = now?.str("Overview").orEmpty(),
                wide = true,
                fav = c.optJSONObject("UserData")?.optBoolean("IsFavorite") == true,
            )
        }
    }

    /** url = absolute stream URL; id = live stream to close afterwards; videoCodec = the channel's original codec. */
    class LiveStream(val url: String, val id: String?, val videoCodec: String?)

    /**
     * Asks the server how to play a live channel. Always a stream served by Jellyfin itself: direct play would hand us
     * the tuner backend's own address (with its login), and Jellyfin's static stream URL refuses live channels. A plain
     * MPEG-TS stream lets it copy MPEG-2/H.264/HEVC video untouched (MPEG-2 then plays in VLC, see PlayerActivity);
     * HLS would force a re-encode, since Jellyfin won't put MPEG-2 in HLS.
     */
    fun jellyfinLiveUrl(server: String, key: String, userId: String, channelId: String): LiveStream {
        val profile = JSONObject(mapOf(
            "MaxStreamingBitrate" to 20_000_000,
            "DirectPlayProfiles" to JSONArray(),
            "TranscodingProfiles" to JSONArray(listOf(JSONObject(mapOf(
                // h264 first: it's what a channel in any other codec gets converted to
                "Container" to "ts", "Type" to "Video", "VideoCodec" to "h264,mpeg2video,hevc", "AudioCodec" to "aac,mp2,ac3",
                "Protocol" to "http", "Context" to "Streaming",
            )))),
        ))
        val r = JSONObject(post(
            "$server/Items/$channelId/PlaybackInfo?userId=$userId&autoOpenLiveStream=true&isPlayback=true&enableDirectPlay=false&enableDirectStream=false",
            JSONObject(mapOf("DeviceProfile" to profile)).toString(), Links.jfAuth(key),
        ))
        val src = r.optJSONArray("MediaSources").objs().firstOrNull() ?: error("channel unavailable")
        val path = src.str("TranscodingUrl") ?: error("server offered no stream for this channel")
        val codec = src.optJSONArray("MediaStreams").objs().firstOrNull { it.optString("Type") == "Video" }?.str("Codec")
        return LiveStream(server + path, src.str("LiveStreamId"), codec)
    }

    /** Frees the tuner a live channel held (Tvheadend only has a few). */
    /**
     * Tells Jellyfin what's playing so it can resume later and show it on the dashboard.
     * event = "" (started), "Progress" or "Stopped".
     */
    fun jellyfinReport(server: String, key: String, event: String, itemId: String, posMs: Long, paused: Boolean) {
        val body = JSONObject(mapOf("ItemId" to itemId, "PositionTicks" to posMs * 10_000, "IsPaused" to paused, "CanSeek" to true, "PlayMethod" to "DirectPlay"))
        post("$server/Sessions/Playing" + if (event.isEmpty()) "" else "/$event", body.toString(), Links.jfAuth(key))
    }

    fun jellyfinCloseLive(server: String, key: String, liveStreamId: String) {
        post("$server/LiveStreams/Close?liveStreamId=${enc(liveStreamId)}", "", Links.jfAuth(key))
    }

    // ---- Streaming providers via JustWatch (see PROVIDERS) ----

    private const val JW_QUERY = "query P(\$c:Country!,\$l:Language!,\$f:TitleFilter,\$s:PopularTitlesSorting!){popularTitles(country:\$c,first:40,filter:\$f,sortBy:\$s){edges{node{objectType " +
        "content(country:\$c,language:\$l){title shortDescription originalReleaseYear posterUrl backdrops{backdropUrl} externalIds{imdbId tmdbId} scoring{imdbScore tomatoMeter}}" +
        "offers(country:\$c,platform:WEB){package{technicalName} deeplinkURL(platform:ANDROID_TV) standardWebURL}}}}}"

    /** objectType "MOVIE"/"SHOW" narrows it; sortBy "POPULAR" or "IMDB_SCORE"; minYear keeps only recent releases. */
    fun streaming(
        p: Provider, country: String, query: String? = null,
        objectType: String? = null, sortBy: String = "POPULAR", minYear: Int? = null,
    ): List<Item> = jw(listOf(p), p.country ?: country, query, objectType, sortBy, minYear, genre = null)

    /** JustWatch genre codes for the "more of this type" rows (Movies / Series tabs). */
    val GENRES = listOf(
        "act" to "Action & adventure", "cmy" to "Comedy", "drm" to "Drama", "trl" to "Mystery & thriller", "crm" to "Crime",
        "scf" to "Sci-fi", "hrr" to "Horror", "rma" to "Romance", "ani" to "Animation", "fml" to "Kids & family", "doc" to "Documentaries",
    )

    /**
     * One row across several services at once (e.g. Action on Netflix + Filmin + HBO Max). Services in other countries'
     * catalogues (Hulu, Stan...) are fetched per country and interleaved, so no single catalogue swamps the row.
     */
    fun streamingMix(providers: List<Provider>, country: String, objectType: String, genre: String): List<Item> {
        val perCountry = providers.groupBy { it.country ?: country }.map { (c, ps) ->
            runCatching { jw(ps, c, null, objectType, "POPULAR", null, genre) }.getOrDefault(emptyList())
        }
        return (0 until (perCountry.maxOfOrNull { it.size } ?: 0)).flatMap { i -> perCountry.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.imdbId ?: it.title }
    }

    private fun jw(
        providers: List<Provider>, country: String, query: String?, objectType: String?, sortBy: String, minYear: Int?, genre: String?,
    ): List<Item> {
        val filter = JSONObject(mapOf("packages" to JSONArray(providers.flatMap { it.jwShortNames }))).apply {
            query?.let { put("searchQuery", it) }
            objectType?.let { put("objectTypes", JSONArray(listOf(it))) }
            minYear?.let { put("releaseYear", JSONObject(mapOf("min" to it))) }
            genre?.let { put("genres", JSONArray(listOf(it))) }
        }
        val vars = JSONObject(mapOf("c" to country, "l" to "en", "f" to filter, "s" to sortBy))
        return parseJustWatch(post("https://apis.justwatch.com/graphql", JSONObject(mapOf("query" to JW_QUERY, "variables" to vars)).toString()), providers)
    }

    /** The service's logo from JustWatch (for the sidebar when its app isn't installed). */
    fun providerLogo(p: Provider, country: String): String? {
        val q = "query L(\$c:Country!){packages(country:\$c,platform:WEB){shortName icon(profile:S100)}}"
        val r = JSONObject(post("https://apis.justwatch.com/graphql", JSONObject(mapOf("query" to q, "variables" to JSONObject(mapOf("c" to (p.country ?: country))))).toString()))
        return r.getJSONObject("data").getJSONArray("packages").objs().firstOrNull { it.optString("shortName") in p.jwShortNames }
            ?.str("icon")?.let { "https://images.justwatch.com" + it.replace("{format}", "png") }
    }

    /** IMDb score + Tomatometer for one title, via JustWatch search matched on IMDb id. */
    fun ratings(title: String, imdbId: String, country: String): Pair<Double?, Int?>? {
        val q = "query R(\$c:Country!,\$l:Language!,\$f:TitleFilter){popularTitles(country:\$c,first:8,filter:\$f){edges{node{" +
            "content(country:\$c,language:\$l){externalIds{imdbId} scoring{imdbScore tomatoMeter}}}}}}"
        val vars = JSONObject(mapOf("c" to country, "l" to "en", "f" to JSONObject(mapOf("searchQuery" to title))))
        return parseRatings(post("https://apis.justwatch.com/graphql", JSONObject(mapOf("query" to q, "variables" to vars)).toString()), imdbId)
    }

    fun parseRatings(json: String, imdbId: String): Pair<Double?, Int?>? =
        JSONObject(json).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges").objs()
            .map { it.getJSONObject("node").getJSONObject("content") }
            .firstOrNull { it.optJSONObject("externalIds")?.str("imdbId") == imdbId }
            ?.optJSONObject("scoring")?.let { s ->
                s.optDouble("imdbScore").takeIf { !it.isNaN() } to s.optInt("tomatoMeter", -1).takeIf { it >= 0 }
            }

    private fun jwImage(path: String?, profile: String) =
        path?.let { "https://images.justwatch.com" + it.replace("{profile}", profile).replace("{format}", "jpg") }

    fun parseJustWatch(json: String, p: Provider) = parseJustWatch(json, listOf(p))

    /** Each title is credited to the first of [providers] that offers it (so mixed rows keep the right badge and app). */
    fun parseJustWatch(json: String, providers: List<Provider>) = JSONObject(json).getJSONObject("data").getJSONObject("popularTitles")
        .getJSONArray("edges").objs().mapNotNull { e ->
            val n = e.getJSONObject("node"); val c = n.getJSONObject("content")
            val offers = n.optJSONArray("offers").objs()
            val (p, offer) = providers.firstNotNullOfOrNull { pr ->
                offers.firstOrNull { it.getJSONObject("package").optString("technicalName") in pr.jwTechNames }?.let { pr to it }
            } ?: return@mapNotNull null
            Item(
                p.source, c.getString("title"),
                Play.Link(offer.str("deeplinkURL") ?: offer.getString("standardWebURL"), p.pkg),
                poster = jwImage(c.str("posterUrl"), "s332"),
                backdrop = jwImage(c.optJSONArray("backdrops").objs().firstOrNull()?.str("backdropUrl"), "s1440"),
                subtitle = c.optInt("originalReleaseYear").takeIf { it > 0 }?.toString().orEmpty(),
                description = c.str("shortDescription").orEmpty(),
                imdb = c.optJSONObject("scoring")?.optDouble("imdbScore")?.takeIf { !it.isNaN() },
                rt = c.optJSONObject("scoring")?.optInt("tomatoMeter", -1)?.takeIf { it >= 0 },
                imdbId = c.optJSONObject("externalIds")?.str("imdbId"),
                tmdbId = c.optJSONObject("externalIds")?.str("tmdbId"),
                type = if (n.optString("objectType") == "SHOW") "series" else "movie",
                // same title in Stremio for services you don't have: by IMDb id (Stremio's own id), else by exact title
                alt = (if (n.optString("objectType") == "SHOW") "series" else "movie").let { type ->
                    c.optJSONObject("externalIds")?.str("imdbId")?.let { Play.Link(Links.stremioDetail(type, it), Links.STREMIO) }
                        ?: Play.StremioSearch(c.getString("title"), c.optInt("originalReleaseYear").takeIf { it > 0 }, type)
                },
            )
        }

    // ---- YouTube (InnerTube search, same endpoint SmartTube uses; playback opens SmartTube) ----

    fun youtube(query: String, country: String): List<Item> {
        val client = JSONObject(mapOf("clientName" to "WEB", "clientVersion" to "2.20250101.00.00", "hl" to "en", "gl" to country))
        val body = JSONObject(mapOf("context" to JSONObject(mapOf("client" to client)), "query" to query, "params" to "EgIQAQ%3D%3D"))
        return parseYouTube(post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString()))
    }

    fun parseYouTube(json: String): List<Item> {
        val found = mutableListOf<JSONObject>()
        collect(JSONObject(json), "videoRenderer", found)
        fun JSONObject.runs(k: String) = optJSONObject(k)?.optJSONArray("runs")?.optJSONObject(0)?.str("text")
        return found.take(25).map { v ->
            val id = v.getString("videoId")
            Item(
                Source.YOUTUBE, v.runs("title") ?: id, Play.Link(Links.youtube(id), Links.SMARTTUBE),
                poster = "https://i.ytimg.com/vi/$id/hqdefault.jpg", backdrop = "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                subtitle = listOfNotNull(v.runs("ownerText"), v.optJSONObject("lengthText")?.str("simpleText")).joinToString(" · "),
                wide = true,
            )
        }
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val v = node.get(k)
                if (k == key && v is JSONObject) out += v else collect(v, key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.get(i), key, out)
        }
    }

    // ---- OAuth refresh (tokens arrive from tools/connect.ps1; see README) ----

    /** Returns (accessToken, newRefreshTokenOrNull). Spotify may rotate the refresh token; Google doesn't. */
    fun refresh(tokenUrl: String, form: Map<String, String>): Pair<String, String?> {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val r = JSONObject(http(tokenUrl, body, mapOf("Content-Type" to "application/x-www-form-urlencoded")))
        return r.getString("access_token") to r.str("refresh_token")
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    // ---- YouTube: your subscriptions (Data API, ~1 quota unit per 50 channels) + per-channel RSS (no quota) ----

    fun youtubeSubscriptions(token: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var page: String? = ""
        while (page != null) {
            val j = JSONObject(get("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50&pageToken=$page", bearer(token)))
            j.optJSONArray("items").objs().forEach { s ->
                val sn = s.getJSONObject("snippet")
                out += sn.getJSONObject("resourceId").getString("channelId") to sn.optString("title")
            }
            page = j.str("nextPageToken")
        }
        return out
    }

    /** Latest 3 uploads per channel, all channels merged newest-first. */
    fun latestFromChannels(channelIds: List<String>): List<Item> {
        val pool = bgPool(4)
        try {
            return channelIds.map { id -> pool.submit<List<Item>> { runCatching { channelFeed(id).take(3) }.getOrDefault(emptyList()) } }
                .flatMap { it.get() }.sortedByDescending { it.sortKey }.take(40)
        } finally { pool.shutdown() }
    }

    /**
     * "Up next" suggestions for each seed video (InnerTube /next, no key), minus channels you follow,
     * interleaved so no single seed dominates. Stands in for the personalised home feed, which only YouTube's own clients get.
     */
    fun youtubeDiscover(seedVideoIds: List<String>, followed: Set<String>, country: String): List<Item> {
        val client = JSONObject(mapOf("clientName" to "WEB", "clientVersion" to "2.20250101.00.00", "hl" to "en", "gl" to country))
        val pool = bgPool(4)
        try {
            val perSeed = seedVideoIds.map { id ->
                pool.submit<List<Pair<Item, String?>>> {
                    runCatching {
                        val body = JSONObject(mapOf("context" to JSONObject(mapOf("client" to client)), "videoId" to id))
                        parseYouTubeNext(post("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", body.toString()))
                    }.getOrDefault(emptyList())
                }
            }.map { f -> f.get().filter { it.second !in followed }.map { it.first } }
            val mixed = (0 until (perSeed.maxOfOrNull { it.size } ?: 0)).flatMap { i -> perSeed.mapNotNull { it.getOrNull(i) } }
            return mixed.distinctBy { it.play }.filter { it.play !in seedVideoIds.map { s -> Play.Link(Links.youtube(s), Links.SMARTTUBE) } }.take(30)
        } finally { pool.shutdown() }
    }

    private val CHANNEL_ID = Regex("\"browseId\":\"(UC[\\w-]{22})\"")

    /** Videos from a /next response, each with its channel id (null if YouTube didn't include one). */
    fun parseYouTubeNext(json: String): List<Pair<Item, String?>> {
        val found = mutableListOf<JSONObject>()
        collect(JSONObject(json), "lockupViewModel", found)
        return found.filter { it.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" }.mapNotNull { v ->
            val id = v.str("contentId") ?: return@mapNotNull null
            val md = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val rows = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows").objs()
                .map { r -> r.optJSONArray("metadataParts").objs().mapNotNull { it.optJSONObject("text")?.str("content") } }
            val channel = rows.getOrNull(0)?.firstOrNull()
            val age = rows.getOrNull(1)?.lastOrNull()
            Item(
                Source.YOUTUBE, md?.optJSONObject("title")?.str("content") ?: id, Play.Link(Links.youtube(id), Links.SMARTTUBE),
                poster = "https://i.ytimg.com/vi/$id/hqdefault.jpg", backdrop = "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                subtitle = listOfNotNull(channel, age).joinToString(" · "), wide = true,
            ) to CHANNEL_ID.find(v.toString())?.groupValues?.get(1)
        }
    }

    fun channelFeed(channelId: String) = parseRss(get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"))

    private val RSS_ENTRY = Regex("<entry>([\\s\\S]*?)</entry>")
    private fun tag(s: String, t: String) = Regex("<$t[^>]*>([\\s\\S]*?)</$t>").find(s)?.groupValues?.get(1)
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Newest-first videos of one channel, Shorts dropped. */
    fun parseRss(xml: String): List<Item> {
        val author = tag(tag(xml, "author") ?: "", "name")?.let(::unescape).orEmpty()
        return RSS_ENTRY.findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            if (e.contains("/shorts/")) return@mapNotNull null
            val id = tag(e, "yt:videoId") ?: return@mapNotNull null
            val published = tag(e, "published").orEmpty()
            Item(
                Source.YOUTUBE, unescape(tag(e, "title").orEmpty()), Play.Link(Links.youtube(id), Links.SMARTTUBE),
                poster = "https://i.ytimg.com/vi/$id/hqdefault.jpg", backdrop = "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                subtitle = "$author · ${published.take(10)}", sortKey = published, wide = true,
                description = unescape(tag(e, "media:description").orEmpty()).take(500),
            )
        }.toList()
    }

    // ---- Spotify (Web API after the Feb 2026 changes: no browse/new-releases, so we derive it from followed artists) ----

    private const val SPOTIFY_API = "https://api.spotify.com/v1"
    private fun sp(token: String, path: String) = JSONObject(get(SPOTIFY_API + path, bearer(token)))
    private fun JSONObject.image() = optJSONArray("images").objs().firstOrNull()?.str("url")
    private fun spotifyItem(o: JSONObject, kind: String, subtitle: String) = Item(
        Source.SPOTIFY, o.optString("name"), Play.Link("spotify:$kind:${o.getString("id")}", Links.SPOTIFY),
        poster = o.image(), backdrop = o.image(), subtitle = subtitle, square = true,
    )

    fun parseSpotifyAlbum(a: JSONObject) = spotifyItem(
        a, "album",
        (listOf(a.optJSONArray("artists").objs().joinToString(", ") { it.optString("name") }) + listOfNotNull(a.str("release_date"))).joinToString(" · "),
    ).copy(type = "album", artist = a.optJSONArray("artists").objs().firstOrNull()?.str("name")) // for Jellyseerr music requests

    fun spotifyFollowedArtists(token: String) =
        sp(token, "/me/following?type=artist&limit=50").getJSONObject("artists").optJSONArray("items").objs()

    fun spotifyArtists(token: String) = spotifyFollowedArtists(token).map { spotifyItem(it, "artist", "Artist") }

    fun spotifyTopArtists(token: String) =
        sp(token, "/me/top/artists?limit=30&time_range=medium_term").optJSONArray("items").objs().map { spotifyItem(it, "artist", "Top artist") }

    fun spotifyPlaylists(token: String) = sp(token, "/me/playlists?limit=50").optJSONArray("items").objs().map { p ->
        // Feb 2026 renamed playlist.tracks -> playlist.items
        val count = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total")
        spotifyItem(p, "playlist", listOfNotNull(p.optJSONObject("owner")?.str("display_name"), count?.let { "$it songs" }).joinToString(" · "))
    }

    fun spotifySavedAlbums(token: String) =
        sp(token, "/me/albums?limit=50").optJSONArray("items").objs().map { parseSpotifyAlbum(it.getJSONObject("album")) }

    // shortcut: one call per followed artist (max 50, run sequentially); cache or parallelise if it feels slow
    fun spotifyNewReleases(token: String, nowMs: Long = System.currentTimeMillis()): List<Item> {
        val albums = spotifyFollowedArtists(token).flatMap { a ->
            runCatching { sp(token, "/artists/${a.getString("id")}/albums?include_groups=album,single&limit=5").optJSONArray("items").objs() }
                .getOrDefault(emptyList())
        }
        return recentReleases(albums, nowMs).map(::parseSpotifyAlbum)
    }

    /** Albums released in the last 90 days, newest first, de-duplicated (collabs show up under several artists). */
    fun recentReleases(albums: List<JSONObject>, nowMs: Long): List<JSONObject> {
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs - 90L * 24 * 3600_000))
        fun fullDate(d: String) = when (d.length) { 4 -> "$d-01-01"; 7 -> "$d-01"; else -> d } // year/month precision -> earliest day
        return albums.filter { fullDate(it.str("release_date").orEmpty()) >= cutoff }
            .distinctBy { it.getString("id") }
            .sortedByDescending { it.optString("release_date") }
            .take(30)
    }

    // ---- Sports schedule (ESPN public scoreboards; picking an event opens GeeSports) ----

    /** One GeeSports row per entry: row name -> ESPN league paths. No NFL / MLB on purpose. */
    val SPORT_ROWS = listOf(
        "Football" to listOf("soccer/arg.1", "soccer/esp.1", "soccer/ita.1"), // Liga Argentina, LaLiga, Serie A
        "Basketball" to listOf("basketball/nba"),
        "Rugby" to listOf(
            "rugby/267979", "rugby/270559", "rugby/270557", "rugby/180659", // Premiership, Top 14, URC, Six Nations
            "rugby/244293", "rugby/242041", "rugby/289234", "rugby/164205", // Rugby Championship, Super Rugby, tests, World Cup
        ),
        "F1" to listOf("racing/f1"),
        "Others" to listOf("hockey/nhl"),
    )
    private val BROWSER_UA = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android TV)") // ESPN rejects the default Dalvik UA

    /** Today and the following days (default 3: rugby/football are mostly weekends), soonest first. */
    fun sports(leagues: List<String>, days: Int = 3, nowMs: Long = System.currentTimeMillis()): List<Item> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dates = (0 until days).map { fmt.format(Date(nowMs + it * 24 * 3600_000L)) }
        val pool = bgPool(4)
        try {
            return leagues.flatMap { l -> dates.map { d -> l to d } }.map { (l, d) ->
                pool.submit<List<Item>> {
                    runCatching { parseEspn(get("https://site.api.espn.com/apis/site/v2/sports/$l/scoreboard?dates=$d", BROWSER_UA), nowMs) }
                        .getOrDefault(emptyList())
                }
            }.flatMap { it.get() }.distinctBy { it.title + it.sortKey }.sortedBy { it.sortKey }.take(40)
        } finally { pool.shutdown() }
    }

    /** "Rayo Vallecano (2-3-3 · form DDWLW) host Elche (1-4-4 · form DDWLD) at Estadio de Vallecas, Madrid. On TV: ESPN+." */
    private fun describeEvent(comp: JSONObject?, teams: List<JSONObject>): String {
        fun side(t: JSONObject) = t.optJSONObject("team")?.str("displayName")?.let { name ->
            val extra = listOfNotNull(t.optJSONArray("records").objs().firstOrNull()?.str("summary"), t.str("form")?.let { "form $it" })
            if (extra.isEmpty()) name else "$name (${extra.joinToString(" · ")})"
        }
        val home = teams.firstOrNull { it.optString("homeAway") == "home" }?.let(::side)
        val away = teams.firstOrNull { it.optString("homeAway") == "away" }?.let(::side)
        val venue = comp?.optJSONObject("venue")?.let { v ->
            listOfNotNull(v.str("fullName"), v.optJSONObject("address")?.str("city")).joinToString(", ").takeIf { it.isNotEmpty() }
        }
        val tv = comp?.optJSONArray("broadcasts").objs().flatMap { b ->
            b.optJSONArray("names")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        }.distinct()
        return listOfNotNull(
            if (home != null && away != null) "$home host $away" + (venue?.let { " at $it" } ?: "") + "." else venue?.let { "At $it." },
            tv.takeIf { it.isNotEmpty() }?.let { "On TV: ${it.joinToString(", ")}." },
        ).joinToString(" ")
    }

    /** Skips finished events; live ones get a "LIVE" subtitle. */
    fun parseEspn(json: String, nowMs: Long): List<Item> {
        val j = JSONObject(json)
        val league = j.optJSONArray("leagues").objs().firstOrNull()
        val leagueName = league?.str("name").orEmpty()
        val leagueLogo = league?.optJSONArray("logos").objs().firstOrNull()?.str("href")
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val local = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        return j.optJSONArray("events").objs().mapNotNull { e ->
            val state = e.optJSONObject("status")?.optJSONObject("type")?.optString("state")
            if (state == "post") return@mapNotNull null
            val iso = e.str("date") ?: return@mapNotNull null
            val start = runCatching { utc.parse(iso.take(16)) }.getOrNull() ?: return@mapNotNull null
            if (state != "in" && start.time < nowMs - 3 * 3600_000) return@mapNotNull null
            val comp = e.optJSONArray("competitions").objs().firstOrNull()
            val teams = comp?.optJSONArray("competitors").objs()
            val logo = teams.firstNotNullOfOrNull { it.optJSONObject("team")?.str("logo") } ?: leagueLogo
            val score = teams.takeIf { state == "in" && it.size == 2 }?.let { t ->
                val home = t.firstOrNull { it.optString("homeAway") == "home" } ?: t[0]
                val away = t.firstOrNull { it !== home } ?: t[1]
                " ${home.optString("score")}–${away.optString("score")}"
            }.orEmpty()
            Item(
                Source.LIVE, e.str("name") ?: e.str("shortName") ?: return@mapNotNull null, Play.App(Links.GEESPORTS),
                poster = logo,
                subtitle = (if (state == "in") "● LIVE$score" else local.format(start)) + " · $leagueName",
                description = describeEvent(comp, teams),
                sortKey = iso, wide = true,
            )
        }
    }
}
