package com.mau.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
