package com.mau.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test fun stremioLinks() {
        assertEquals("stremio:///detail/movie/tt1/tt1", Links.stremioDetail("movie", "tt1"))
        assertEquals("stremio:///detail/series/tt2", Links.stremioDetail("series", "tt2"))
        assertEquals("stremio:///detail/series/tt2/tt2:1:3", Links.stremioDetail("series", "tt2", "tt2:1:3"))
    }

    @Test fun jellyfinUrls() {
        assertEquals("http://h:8096/Videos/x/stream?static=true", Links.jfStream("http://h:8096/", "x"))
        assertEquals("MediaBrowser Token=\"k\"", Links.jfAuth("k")["Authorization"])
        assertEquals(1500L, Links.ticksToMs(15_000_000))
    }
}

class SourcesTest {
    @Test fun cinemeta() {
        val items = Sources.parseCinemeta(
            """{"metas":[{"id":"tt9","name":"Dune","poster":"p","background":"b","releaseInfo":"2021","imdbRating":"8.0","genres":["Sci-Fi","Drama","X"],"description":"d"}]}""",
            "movie",
        )
        val i = items.single()
        assertEquals("2021 · Sci-Fi · Drama", i.subtitle)
        assertEquals(8.0, i.imdb!!, 0.0)
        assertEquals("tt9", i.imdbId)
        assertEquals(Play.Link("stremio:///detail/movie/tt9/tt9", Links.STREMIO), i.play)
    }

    @Test fun stremioLibraryKeepsOnlyStartedAndNotRemoved() {
        val json = """{"result":[
            {"_id":"tt1","name":"A","type":"series","removed":false,"state":{"timeOffset":10,"lastWatched":"2026-01-02","video_id":"tt1:2:5"}},
            {"_id":"tt2","name":"B","type":"movie","removed":true,"state":{"timeOffset":10}},
            {"_id":"tt3","name":"C","type":"movie","removed":false,"state":{"timeOffset":0}}]}"""
        val i = Sources.parseStremioLibrary(json).single()
        assertEquals("S2E5", i.subtitle)
        assertEquals(Play.Link("stremio:///detail/series/tt1/tt1:2:5", Links.STREMIO), i.play)
    }

    @Test fun jellyfinEpisodeUsesSeriesPosterAndResume() {
        val json = """{"Items":[{"Id":"e1","Name":"Pilot","SeriesName":"Show","SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":2,
            "ParentBackdropItemId":"s1","UserData":{"PlaybackPositionTicks":600000000},"IsFolder":false}]}"""
        val i = Sources.parseJellyfin(json, "http://h").single()
        assertEquals("Show", i.title)
        assertEquals("S1E2 · Pilot", i.subtitle)
        assertEquals(Play.Jelly("e1", 60_000, false), i.play)
        assertEquals(Links.jfImage("http://h", "s1"), i.poster)
        assertEquals(Links.jfImage("http://h", "s1", "Backdrop"), i.backdrop)
    }

    @Test fun justWatchKeepsNetflixOffersOnly() {
        val json = """{"data":{"popularTitles":{"edges":[
            {"node":{"objectType":"SHOW","content":{"title":"T","posterUrl":"/poster/1/{profile}/t.{format}","backdrops":[],"originalReleaseYear":2020,"externalIds":{"imdbId":"tt42"}},
             "offers":[{"package":{"technicalName":"hulu"},"standardWebURL":"h"},{"package":{"technicalName":"netflix"},"deeplinkURL":null,"standardWebURL":"https://www.netflix.com/title/5"}]}},
            {"node":{"content":{"title":"NoNetflix"},"offers":[]}}]}}}"""
        val i = Sources.parseJustWatch(json, PROVIDERS.first { it.source == Source.NETFLIX }).single()
        assertEquals(Source.NETFLIX, i.source)
        assertEquals("https://images.justwatch.com/poster/1/s332/t.jpg", i.poster)
        assertNull(i.backdrop)
        assertEquals(Play.Link("https://www.netflix.com/title/5", Links.NETFLIX), i.play)
        assertEquals(Play.Link("stremio:///detail/series/tt42", Links.STREMIO), i.alt)
    }

    @Test fun youtubeFindsNestedVideos() {
        val json = """{"a":[{"b":{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Goal"}]},
            "ownerText":{"runs":[{"text":"Chan"}]},"lengthText":{"simpleText":"3:01"}}}}]}"""
        val i = Sources.parseYouTube(json).single()
        assertEquals("Goal", i.title)
        assertEquals("Chan · 3:01", i.subtitle)
        assertEquals(Play.Link("https://www.youtube.com/watch?v=abcdefghijk", Links.SMARTTUBE), i.play)
    }

    @Test fun rssDropsShortsAndUnescapes() {
        val xml = """<feed><author><name>Chan &amp; Co</name></author>
            <entry><yt:videoId>aaaaaaaaaaa</yt:videoId><title>Rock &amp; Roll</title><link rel="alternate" href="https://www.youtube.com/watch?v=aaaaaaaaaaa"/><published>2026-10-21T14:00:33+00:00</published></entry>
            <entry><yt:videoId>bbbbbbbbbbb</yt:videoId><title>Short</title><link rel="alternate" href="https://www.youtube.com/shorts/bbbbbbbbbbb"/><published>2026-10-22T14:00:00+00:00</published></entry>
            </feed>"""
        val i = Sources.parseRss(xml).single()
        assertEquals("Rock & Roll", i.title)
        assertEquals("Chan & Co · 2026-10-21", i.subtitle)
        assertEquals(Play.Link("https://www.youtube.com/watch?v=aaaaaaaaaaa", Links.SMARTTUBE), i.play)
    }

    @Test fun mixedRowCreditsTheRightService() {
        val json = """{"data":{"popularTitles":{"edges":[
            {"node":{"objectType":"MOVIE","content":{"title":"A","externalIds":{"imdbId":"tt1","tmdbId":"11"}},"offers":[{"package":{"technicalName":"filmin"},"standardWebURL":"f"}]}},
            {"node":{"objectType":"MOVIE","content":{"title":"B","externalIds":{"imdbId":"tt2"}},"offers":[{"package":{"technicalName":"netflix"},"standardWebURL":"n"}]}},
            {"node":{"objectType":"MOVIE","content":{"title":"C"},"offers":[{"package":{"technicalName":"hulu"},"standardWebURL":"h"}]}}]}}}"""
        val ps = PROVIDERS.filter { it.source == Source.NETFLIX || it.source == Source.FILMIN }
        val items = Sources.parseJustWatch(json, ps)
        assertEquals(listOf("A" to Source.FILMIN, "B" to Source.NETFLIX), items.map { it.title to it.source }) // C: not on those services
        assertEquals("11", items[0].tmdbId)
    }

    @Test fun musicBrainzAlbumIsExact() {
        val json = """{"release-groups":[
            {"id":"live","title":"OK Computer (Live)","artist-credit":[{"name":"Radiohead"}]},
            {"id":"cover","title":"OK Computer","artist-credit":[{"name":"Some Tribute Band"}]},
            {"id":"b1392450","title":"OK Computer","artist-credit":[{"name":"Radiohead"}]}]}"""
        assertEquals("b1392450", Sources.parseMbAlbum(json, "Radiohead", "ok computer"))
        assertNull(Sources.parseMbAlbum(json, "Radiohead", "Kid A"))
    }

    @Test fun spotifyAlbumCarriesArtistForRequests() {
        val a = org.json.JSONObject("""{"id":"x","name":"OK Computer","artists":[{"name":"Radiohead"}],"release_date":"1997-05-21","images":[]}""")
        val i = Sources.parseSpotifyAlbum(a)
        assertEquals("album", i.type)
        assertEquals("Radiohead", i.artist)
    }

    @Test fun seerrLabels() {
        assertEquals("＋  Request in Jellyseerr", Sources.seerrLabel(null))
        assertEquals("＋  Request in Jellyseerr", Sources.seerrLabel(1))
        assertEquals("✓  Requested", Sources.seerrLabel(2))
        assertEquals("✓  Requested", Sources.seerrLabel(3))
        assertEquals("＋  Request the rest", Sources.seerrLabel(4))
        assertNull(Sources.seerrLabel(5)) // already in Jellyfin: no button
    }

    @Test fun weatherReadsOpenMeteo() {
        val json = """{"current":{"time":"2026-10-23T00:45","temperature_2m":12.6,"weather_code":3,"is_day":0}}"""
        assertEquals("☁️  13°", Sources.parseWeather(json))
        assertEquals("☀️", Sources.weatherIcon(0, day = true))
        assertEquals("🌙", Sources.weatherIcon(0, day = false))
        assertEquals("🌧️", Sources.weatherIcon(61, day = true))
        assertEquals("⛈️", Sources.weatherIcon(95, day = false))
    }

    @Test fun forecastReadsOpenMeteo() {
        val json = """{"current":{"time":"2026-10-25T14:15","temperature_2m":20.4,"apparent_temperature":19.6,"relative_humidity_2m":61,"wind_speed_10m":11.7,"weather_code":2,"is_day":1},
            "hourly":{"time":["2026-10-25T13:00","2026-10-25T14:00","2026-10-25T15:00","2026-10-25T16:00"],"temperature_2m":[20,20.4,21.2,20],"weather_code":[2,2,61,3],"precipitation_probability":[0,0,40,10],"is_day":[1,1,1,1]},
            "daily":{"time":["2026-10-25","2026-10-26","2026-10-27"],"weather_code":[2,61,0],"temperature_2m_max":[22.4,19,23],"temperature_2m_min":[14.6,13,12],"precipitation_probability_max":[40,80,0],"sunrise":["2026-10-25T07:12"],"sunset":["2026-10-25T17:49"]}}"""
        val f = Sources.parseForecast(json, java.util.Locale.ENGLISH)
        assertEquals(20, f.temp); assertEquals(20, f.feels); assertEquals(61, f.humidity); assertEquals(12, f.wind)
        assertEquals("Partly cloudy", f.summary); assertEquals(22, f.high); assertEquals(15, f.low); assertEquals(40, f.rain)
        assertEquals("07:12", f.sunrise); assertEquals("17:49", f.sunset)
        // hours start after now (14:15): 15h and 16h
        assertEquals(listOf("15h", "16h"), f.hours.map { it.time }); assertEquals(40, f.hours[0].rain); assertEquals("🌧️", f.hours[0].icon)
        // days start tomorrow, by name
        assertEquals(listOf("Monday", "Tuesday"), f.days.map { it.name }); assertEquals(80, f.days[0].rain); assertEquals(23, f.days[1].high)
        // in Spanish: the summary and the day names
        val es = Sources.parseForecast(json, java.util.Locale("es"))
        assertEquals("Parcialmente nublado", es.summary); assertEquals(listOf("Lunes", "Martes"), es.days.map { it.name })
    }

    @Test fun weatherPlaceFromOpenMeteoGeocoder() {
        val json = """{"results":[{"name":"Valencia","latitude":39.47,"longitude":-0.38,"country":"Spain"}]}"""
        assertEquals(Triple("Valencia, Spain", 39.47, -0.38), Sources.parseGeocode(json))
        assertEquals(null, Sources.parseGeocode("""{"generationtime_ms":0.5}""")) // nothing matched
    }

    @Test fun liveChannelShowsCurrentProgram() {
        val json = """{"Items":[{"Id":"ch1","Number":"100","Name":"12tv","UserData":{"IsFavorite":true},
            "CurrentProgram":{"Id":"pr1","Name":"News","Overview":"Daily news","EndDate":"2026-10-23T00:00:00.0000000Z","ImageTags":{"Primary":"x"}}},
            {"Id":"ch2","Number":"101","Name":"24 Horas"}]}"""
        val (a, b) = Sources.parseLiveChannels(json, "http://h")
        assertEquals("100  12tv", a.title)
        assertEquals(Play.JellyLive("ch1"), a.play)
        assertEquals("Daily news", a.description)
        assertEquals(Links.jfImage("http://h", "pr1"), a.poster) // programme art when there is some
        assertEquals("Live", b.subtitle)
        assertEquals(true, a.fav)
        assertEquals(false, b.fav)
        assertEquals(Links.jfImage("http://h", "ch2"), b.poster)  // otherwise the channel logo
    }

    @Test fun metaCountsSeasonsAndFindsTrailer() {
        val json = """{"meta":{"description":"d","runtime":"53 min","genres":["Crime","Drama"],"cast":["A","B"],"director":[],"writer":["Guy"],
            "logo":"L","awards":"Won 1","trailerStreams":[{"ytId":"yt1"}],
            "videos":[{"season":0},{"season":1},{"season":1},{"season":2}]}}"""
        val m = Sources.parseMeta(json)!!
        assertEquals(2, m.seasons)
        assertEquals(3, m.episodes) // season 0 = specials, not counted
        assertEquals("yt1", m.trailerYt)
        assertEquals(listOf("Guy"), m.creators) // series: creators come from writer when director is empty
    }

    @Test fun ratingsMatchOnImdbId() {
        val json = """{"data":{"popularTitles":{"edges":[
            {"node":{"content":{"externalIds":{"imdbId":"tt1"},"scoring":{"imdbScore":8.0,"tomatoMeter":null}}}},
            {"node":{"content":{"externalIds":{"imdbId":"tt2"},"scoring":{"imdbScore":8.4,"tomatoMeter":92}}}}]}}}"""
        assertEquals(8.4 to 92, Sources.parseRatings(json, "tt2"))
        assertEquals(8.0 to null, Sources.parseRatings(json, "tt1"))
        assertNull(Sources.parseRatings(json, "tt3"))
    }

    @Test fun cinemetaMatchIsExactAndUsesYear() {
        val json = """{"metas":[
            {"id":"tt1","name":"The Office","releaseInfo":"2001-2003"},
            {"id":"tt2","name":"The Office","releaseInfo":"2005-2013"},
            {"id":"tt3","name":"The Office Ladies","releaseInfo":"2019"}]}"""
        assertEquals("tt2", Sources.pickCinemetaMatch(json, "The Office", 2005))
        assertEquals("tt1", Sources.pickCinemetaMatch(json, "the office!", 2001))
        assertNull(Sources.pickCinemetaMatch(json, "The Office", null)) // two exact matches, no year: don't guess
        assertNull(Sources.pickCinemetaMatch(json, "Office", 2005))     // not verbatim
        assertEquals("tt3", Sources.pickCinemetaMatch(json, "The Office Ladies", null))
        assertEquals("amelie", Sources.normTitle("Amélie!"))
    }

    @Test fun justWatchWithoutImdbFallsBackToTitleSearch() {
        val json = """{"data":{"popularTitles":{"edges":[{"node":{"objectType":"MOVIE",
            "content":{"title":"Hokum","originalReleaseYear":2026,"externalIds":{"imdbId":null}},
            "offers":[{"package":{"technicalName":"hulu"},"standardWebURL":"https://hulu.com/x"}]}}]}}}"""
        val i = Sources.parseJustWatch(json, PROVIDERS.first { it.source == Source.HULU }).single()
        assertEquals(Play.StremioSearch("Hokum", 2026, "movie"), i.alt)
    }

    @Test fun youtubeNextKeepsVideosWithChannel() {
        val json = """{"x":[{"lockupViewModel":{"contentId":"vid00000001","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Cool video"},"metadata":{"contentMetadataViewModel":{"metadataRows":[
              {"metadataParts":[{"text":{"content":"Some Channel"}}]},{"metadataParts":[{"text":{"content":"5.8M"}},{"text":{"content":"1d ago"}}]}]}}}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCMiJRAwDNSNzuYeN2uWa0pA"}}}}}}},
            {"lockupViewModel":{"contentId":"PLlist","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST"}}]}"""
        val (item, channel) = Sources.parseYouTubeNext(json).single()
        assertEquals("Cool video", item.title)
        assertEquals("Some Channel · 1d ago", item.subtitle)
        assertEquals("UCMiJRAwDNSNzuYeN2uWa0pA", channel)
    }

    @Test fun spotifyRecentReleasesFiltersSortsDedupes() {
        fun album(id: String, date: String) = org.json.JSONObject(mapOf("id" to id, "name" to id, "release_date" to date))
        val now = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse("2026-10-22")!!.time
        val out = Sources.recentReleases(listOf(album("old", "2026-01-01"), album("a", "2026-10-01"), album("b", "2026-10-20"), album("a", "2026-10-01"), album("yearOnly", "2026")), now)
        assertEquals(listOf("b", "a"), out.map { it.getString("id") })
        assertEquals(Play.Link("spotify:album:b", Links.SPOTIFY), Sources.parseSpotifyAlbum(out.first()).play)
    }

    @Test fun espnSkipsFinishedAndMarksLive() {
        val json = """{"leagues":[{"name":"LaLiga","logos":[{"href":"L"}]}],"events":[
            {"name":"A at B","date":"2026-10-22T19:00Z","status":{"type":{"state":"in"}},"competitions":[{
              "venue":{"fullName":"Stadium","address":{"city":"Madrid"}},"broadcasts":[{"names":["ESPN+"]}],
              "competitors":[{"homeAway":"home","score":"2","form":"WWD","records":[{"summary":"3-1-0"}],"team":{"logo":"T","displayName":"B"}},
                             {"homeAway":"away","score":"1","team":{"displayName":"A"}}]}]},
            {"name":"C at D","date":"2026-10-22T12:00Z","status":{"type":{"state":"post"}}},
            {"name":"E at F","date":"2026-10-23T19:00Z","status":{"type":{"state":"pre"}}}]}"""
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse("2026-10-22T19:30")!!.time
        val items = Sources.parseEspn(json, now)
        assertEquals(listOf("A at B", "E at F"), items.map { it.title })
        assertEquals("● LIVE 2–1 · LaLiga", items[0].subtitle)
        assertEquals("B (3-1-0 · form WWD) host A at Stadium, Madrid. On TV: ESPN+.", items[0].description)
        assertEquals("T", items[0].poster)
        assertEquals("L", items[1].poster)
    }

}

class ItemJsonTest {
    @Test fun roundTripsEveryKindOfItem() {
        val items = listOf(
            Item(Source.NETFLIX, "T", Play.Link("u", "p"), poster = "a", alt = Play.StremioSearch("T", 2020, "movie"), imdb = 8.1, rt = 80, imdbId = "tt1", type = "series"),
            Item(Source.JELLYFIN, "J", Play.Jelly("id", 5000, false), subtitle = "S1E2", description = "d"),
            Item(Source.JELLYFIN, "L", Play.JellyLive("ch"), wide = true),
            Item(Source.SPOTIFY, "S", Play.Link("spotify:album:x", "pkg"), square = true, sortKey = "2026"),
            Item(Source.LIVE, "G", Play.App("geesports"), alt = Play.StremioSearch("X", null, "series")),
        )
        assertEquals(items, ItemJson.decode(ItemJson.encode(items)))
    }
}

class RowOrderTest {
    @Test fun savedOrderFirstThenNewRowsInDefaultOrder() {
        assertEquals(listOf("C", "A", "B", "D"), RowOrder.apply(listOf("A", "B", "C", "D"), listOf("C", "gone", "A")))
        assertEquals(listOf("A", "B"), RowOrder.apply(listOf("A", "B"), emptyList()))
    }

    @Test fun moves() {
        val l = listOf("A", "B", "C")
        assertEquals(listOf("B", "A", "C"), RowOrder.move(l, 1, RowOrder.UP))
        assertEquals(l, RowOrder.move(l, 0, RowOrder.UP))
        assertEquals(listOf("A", "C", "B"), RowOrder.move(l, 1, RowOrder.DOWN))
        assertEquals(l, RowOrder.move(l, 2, RowOrder.DOWN))
        assertEquals(listOf("C", "A", "B"), RowOrder.move(l, 2, RowOrder.TOP))
        assertEquals(listOf("B", "C", "A"), RowOrder.move(l, 0, RowOrder.BOTTOM))
    }

    @Test fun failedRowsSayWhichServerAndWhy() {
        assertEquals("jf.local:8096 answered 503: it's down or still starting up", Sources.explain(Sources.FetchError("jf.local:8096", 503)))
        assertEquals("api.example.com answered 401: it rejected the sign-in (reconnect the account)", Sources.explain(Sources.FetchError("api.example.com", 401)))
        assertEquals("jf.local:8096: nothing answered (server off, or the address is wrong)",
            Sources.explain(Sources.FetchError("jf.local:8096", null, java.net.ConnectException("refused"))))
        assertEquals("location unavailable", Sources.explain(IllegalStateException("location unavailable")))
    }

    @Test fun iconColourComesFromTheAppsLogo() {
        val black = 0xFF000000.toInt(); val yellow = 0xFFFFC400.toInt(); val grey = 0xFF808080.toInt()
        val geesports = IntArray(48 * 48) { if (it % 3 == 0) yellow else black } // yellow G on a black tile
        val c = IconPacks.dominant(geesports)!!
        assertTrue("red ${c shr 16 and 0xFF}", (c shr 16 and 0xFF) > 200); assertTrue((c shr 8 and 0xFF) > 150); assertTrue((c and 0xFF) < 100)
        assertEquals(null, IconPacks.dominant(IntArray(48 * 48) { if (it % 2 == 0) grey else black })) // no colour to match
    }

    @Test fun mobileAppsStandForTheirTvApps() {
        assertEquals(Links.SPOTIFY, Links.canonical("com.spotify.music"))
        assertEquals(Links.SMARTTUBE, Links.canonical("com.android.youtube.premium")) // ReVanced counts as YouTube
        assertEquals("com.stremio.one", Links.canonical("com.stremio.one")) // same package everywhere
        assertEquals("com.android.youtube.premium", Links.MOBILE.getValue(Links.SMARTTUBE).first()) // ReVanced preferred
    }

    @Test fun continueWatchingOneTilePerShow() {
        fun ep(n: Int, pos: Long) = Item(Source.JELLYFIN, "Pluribus", Play.Jelly("e$n", pos, false))
        val resume = Result.success(listOf(ep(1, 1_800_000)))
        val nextUp = Result.success(listOf(ep(1, 0), Item(Source.JELLYFIN, "Slow Horses", Play.Jelly("s1", 0, false))))
        val row = Catalog.oneEach(listOf(resume, nextUp))
        assertEquals(listOf("Pluribus", "Slow Horses"), row.map { it.title })
        assertEquals(1_800_000L, (row.first().play as Play.Jelly).posMs) // the one you're partway through
        assertEquals(listOf("Pluribus"), Catalog.oneEach(listOf(Result.failure(RuntimeException("503")), resume)).map { it.title })
        assertEquals("503", runCatching { Catalog.oneEach(listOf(Result.failure(RuntimeException("503")))) }.exceptionOrNull()?.message)
    }

    @Test fun subtitleFilesOnlyTextAndExternal() {
        val json = """{"MediaSources":[{"Id":"src1","MediaStreams":[
            {"Type":"Video","Index":0},
            {"Type":"Subtitle","Index":2,"IsExternal":false,"Language":"eng"},
            {"Type":"Subtitle","Index":3,"IsExternal":true,"IsTextSubtitleStream":true,"Language":"spa","DisplayTitle":"Spanish - SRT"},
            {"Type":"Subtitle","Index":4,"IsExternal":true,"IsTextSubtitleStream":false,"Language":"fre"}]}]}"""
        val subs = Sources.parseSubtitles(json, "http://jf", "item9")
        assertEquals(listOf("http://jf/Videos/item9/src1/Subtitles/3/0/Stream.vtt"), subs.map { it.url })
        assertEquals("Spanish - SRT", subs.single().label)
        assertEquals("spa", subs.single().language)
    }

    @Test fun categoryChannelsFollowTheListOrderAndSkipMissing() {
        val json = """{"Items":[{"Id":"a","Name":"MTV Live","Number":"707"},{"Id":"b","Name":"MTV2","Number":"708"},{"Id":"c","Name":"News","Number":"1"}]}"""
        assertEquals(listOf("708  MTV2", "707  MTV Live"), Sources.pickChannels(json, listOf("708", "999", "707"), "http://jf").map { it.title })
        assertEquals(listOf("707", "708", "784"), LIVE_CATEGORIES.getValue("Music").take(3))
        assertEquals(listOf(43, 34, 15), listOf("Music", "Movies", "Series").map { LIVE_CATEGORIES.getValue(it).size })
    }
}

class BackupTest {
    @org.junit.Test fun roundTripsLayoutButLeavesOutSecretsAndWidgets() {
        val prefs = mapOf<String, Any>("apps_favs_only" to true, "bg_dim" to 25, "section_name_ha" to "Casa",
            "fav_apps" to setOf("a", "b"), "key" to "secret", "widgets" to "3,4", "wpos_l_3" to "0,0")
        val back = TabletSettings.restore(TabletSettings.backup(prefs))
        org.junit.Assert.assertEquals(mapOf("apps_favs_only" to true, "bg_dim" to 25, "section_name_ha" to "Casa", "fav_apps" to setOf("a", "b")), back)
    }

    @org.junit.Test(expected = IllegalArgumentException::class) fun refusesOtherFiles() { TabletSettings.restore("{\"x\":1}") }
}

class AppSearchTest {
    private fun app(label: String) = SideApp(label, label, null, label)

    @org.junit.Test fun findsByPartOfTheNameIgnoringAccentsAndCase() {
        val apps = listOf(app("Cámara"), app("Spotify"), app("Google Calendar"), app("Calendario"))
        org.junit.Assert.assertEquals(listOf("Calendario", "Google Calendar"), Apps.matches(apps, "CAL").map { it.label }) // starts-with first
        org.junit.Assert.assertEquals(listOf("Cámara"), Apps.matches(apps, "camara").map { it.label })
        org.junit.Assert.assertEquals(emptyList<String>(), Apps.matches(apps, "  ").map { it.label })
    }
}

class WidgetGridTest {
    @org.junit.Test fun dropOnAWidgetGoesToTheNearestFreeSpot() {
        val beeper = intArrayOf(0, 0, 4, 3) // columns 0-3, rows 0-2
        org.junit.Assert.assertEquals(5 to 1, Widgets.nearestFree(5, 1, 2, 2, 8, 4, listOf(beeper))) // free: stays
        org.junit.Assert.assertEquals(4 to 1, Widgets.nearestFree(3, 1, 2, 2, 8, 4, listOf(beeper))) // on Beeper: next to it
        org.junit.Assert.assertEquals(null, Widgets.nearestFree(0, 0, 8, 4, 8, 4, listOf(beeper)))   // no room anywhere
    }
}

class HaWidgetTest {
    private fun st(id: String, state: String, attrs: String = "{}") = id to org.json.JSONObject("""{"entity_id":"$id","state":"$state","attributes":$attrs}""")
    private fun tile(name: String) = HaWidget.TILES.first { it.name == name }

    @Test fun tilesShowHomeAssistantStates() {
        assertTrue(HaWidget.look(tile("Desk Plug"), mapOf(st("switch.desk_plug", "on"))).on)
        assertEquals("Apagado", HaWidget.look(tile("Desk Plug"), mapOf(st("switch.desk_plug", "off"))).status)
        // a thermostat reporting °F; the tile shows °C
        assertEquals("25.7 °C", HaWidget.look(tile("Thermostat"), mapOf(st("sensor.thermostat_temperature", "78.26", """{"unit_of_measurement":"°F"}"""))).status)
        // a lights group counts its members that are on
        val lights = mapOf(st("light.living_room", "on", """{"entity_id":["light.a","light.b"]}"""), st("light.a", "on"), st("light.b", "on"))
        assertEquals("2 encendidas", HaWidget.look(tile("Living Room Lights"), lights).status)
        // not in HA, or unreachable: grey, never "on"
        assertEquals("No disponible", HaWidget.look(tile("TV Plug"), mapOf(st("switch.other", "on"))).status)
        assertTrue(!HaWidget.look(tile("Living Room TV"), mapOf(st("media_player.living_room_tv", "unavailable"))).on)
        // a single lamp, not a group
        assertEquals("Encendida", HaWidget.look(tile("Reading Lamp"), mapOf(st("light.reading_lamp", "on"))).status)
        // only the strip that feeds a server asks before turning off
        assertEquals(listOf("Power Strip"), HaWidget.TILES.filter { it.guard != null }.map { it.name })
    }

    @Test fun exampleTilesFileReadsBack() {
        val list = HaWidget.fromJson(java.io.File("ha_tiles.example.json").readText())
        assertEquals(HaTile.Kind.values().toSet(), list.map { it.kind }.toSet()) // one of every kind
        assertEquals(list, HaWidget.fromJson(HaWidget.toJson(list)))
        assertEquals("This strip also powers the home server. Turn the whole strip off?", list[0].guard)
    }
}

class ImmichTest {
    @org.junit.Test fun readsAlbumsAndPhotos() {
        org.junit.Assert.assertEquals(listOf("a1" to "Family · 12 photos"),
            Immich.parseAlbums("""[{"id":"a1","albumName":"Family","assetCount":12}]"""))
        org.junit.Assert.assertEquals(listOf("p1", "p3"),
            Immich.parseIds("""[{"id":"p1","type":"IMAGE"},{"id":"p2","type":"VIDEO"},{"id":"p3","type":"IMAGE"}]"""))
        org.junit.Assert.assertEquals(listOf("p9"), Immich.parseAlbumPhotos("""{"id":"a1","assets":[{"id":"p9","type":"IMAGE"}]}"""))
    }
}

class ProfilesTest {
    @org.junit.Test fun readsJellyfinUsersSkippingDisabledOnes() {
        val p = Profiles.parse("""[{"Id":"u1","Name":"Alex","Policy":{"IsDisabled":false}},{"Id":"u2","Name":"Old","Policy":{"IsDisabled":true}},{"Id":"u3","Name":"Guest"}]""")
        org.junit.Assert.assertEquals(listOf("u1" to "Alex", "u3" to "Guest"), p.map { it.id to it.name })
    }
}

class HaTilesTest {
    @Test fun tilesSurviveSavingAndLoading() {
        val back = HaWidget.fromJson(HaWidget.toJson(HaWidget.TILES))
        assertEquals(HaWidget.TILES, back) // names, icons, kinds, strips' outlets and warnings all come back
    }

    @Test fun aNewTileGuessesFromTheDevice() {
        assertEquals(HaTile.Kind.LIGHT, HaWidget.guess("light.salon").first)
        assertEquals(HaTile.Kind.CAMERA, HaWidget.guess("camera.entrada").first)
        assertEquals(HaTile.Kind.RUN, HaWidget.guess("scene.cine").first)
        assertEquals(HaTile.Kind.SWITCH, HaWidget.guess("input_boolean.guests").first)
    }
}

class HeraldWidgetTest {
    private val sensor = org.json.JSONObject("""{"state":"Quiet night at home","attributes":{"edition":12,"published":"2026-10-24T15:53:39+02:00",
        "subhead":"All servers up","headline_es":"Noche tranquila en casa","briefs":["One","Two"],"watch":"Nothing urgent.",
        "numbers":{"devices_online":41,"servers_up":3,"servers_total":3,"lights_on":2,"weather":"Cloudy","temperature":17.5}}}""")

    @Test fun editionInBothLanguages() {
        val en = HeraldWidget.page(sensor, es = false)
        assertEquals("Quiet night at home", en.headline)
        assertEquals("No. 12", en.no)
        assertEquals("Saturday, 24 October 2026", en.date)
        assertEquals("Morning edition · 15:53", en.edition)
        assertEquals(listOf("41" to "devices online", "3/3" to "servers up", "2" to "lights on", "17.5°C" to "Cloudy"), en.kpis)
        assertEquals("• One\n• Two", en.briefs)
        val es = HeraldWidget.page(sensor, es = true)
        assertEquals("Noche tranquila en casa", es.headline)
        assertEquals("All servers up", es.subhead) // no subhead_es: the English one
        assertEquals("sábado, 24 de octubre de 2026", es.date)
        assertEquals("Nº 12", es.no)
        assertEquals("Heraldo del Hogar", es.masthead)
    }
}

class MiniSocketTest {
    @Test fun clientFramesAreFinalAndMasked() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val f = MiniSocket.frame(0x2, byteArrayOf(9, 0, 0), mask)
        assertEquals(listOf(0x82, 0x83, 1, 2, 3, 4, 9 xor 1, 0 xor 2, 0 xor 3), f.map { it.toInt() and 0xFF })
        // 200 bytes: the 16-bit length form
        val g = MiniSocket.frame(0x1, ByteArray(200), mask)
        assertEquals(listOf(0x81, 0xFE, 0, 200), g.take(4).map { it.toInt() and 0xFF })
        assertEquals(4 + 4 + 200, g.size)
    }
}

class JarvisLanguageTest {
    @Test fun answersAreReadInTheirOwnLanguage() {
        assertTrue(Jarvis.spanish("Las luces del salón están encendidas."))
        assertTrue(Jarvis.spanish("¿Quieres que apague la tele?"))
        assertTrue(!Jarvis.spanish("The living room lights are on."))
        assertTrue(!Jarvis.spanish("Done: I turned off the TV for you."))
    }
}

class IconColourTest {
    @Test fun aPickCanCarryItsOwnColour() {
        assertEquals(0xFF9E9E9E.toInt(), IconPacks.fixedColour("#9E9E9E")) // Lords Mobile's grey target
        assertEquals(null, IconPacks.fixedColour("auto"))
        assertEquals(null, IconPacks.fixedColour(null))
        assertEquals(null, IconPacks.fixedColour("#12345")) // not a colour: shown in the pack's own
    }
}
