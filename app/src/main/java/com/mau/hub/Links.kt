package com.mau.hub

object Links {
    const val GEESPORTS = "com.live.geesports"
    const val NETFLIX = "com.netflix.ninja"
    const val SMARTTUBE = "org.smarttube.beta"
    const val STREMIO = "com.stremio.one"
    const val JELLYFIN = "org.jellyfin.androidtv"
    const val SPOTIFY = "com.spotify.tv.android"

    // Stremio wants meta id + video id for movies; series resume passes "tt..:season:episode"
    fun stremioDetail(type: String, id: String, videoId: String? = null) =
        "stremio:///detail/$type/$id" + when {
            videoId != null -> "/$videoId"
            type == "movie" -> "/$id"
            else -> ""
        }

    fun jfImage(server: String, id: String, kind: String = "Primary") =
        "${server.trimEnd('/')}/Items/$id/Images/$kind?fillHeight=${if (kind == "Primary") 360 else 720}&quality=90"

    fun jfStream(server: String, id: String) = "${server.trimEnd('/')}/Videos/$id/stream?static=true"

    fun jfAuth(key: String) = mapOf("Authorization" to "MediaBrowser Token=\"$key\"")

    fun youtube(id: String) = "https://www.youtube.com/watch?v=$id"

    // Jellyfin ticks are 100ns
    fun ticksToMs(ticks: Long) = ticks / 10_000
}
