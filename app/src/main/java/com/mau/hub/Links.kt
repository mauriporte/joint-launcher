package com.mau.hub

object Links {
    const val GEESPORTS = "com.live.geesports"
    const val NETFLIX = "com.netflix.ninja"
    const val SMARTTUBE = "org.smarttube.beta"
    const val STREMIO = "com.stremio.one"
    const val JELLYFIN = "org.jellyfin.androidtv"
    const val SPOTIFY = "com.spotify.tv.android"

    /**
     * Phone/tablet versions of the TV apps we open, in order of preference; the same links work in both. Apps whose
     * package is the same everywhere (Stremio, Disney+, HBO Max, GeeSports, VLC) aren't listed.
     */
    val MOBILE = mapOf(
        NETFLIX to listOf("com.netflix.mediaclient"),
        SMARTTUBE to listOf("com.android.youtube.premium", "app.revanced.android.youtube", "com.google.android.youtube"), // ReVanced first
        SPOTIFY to listOf("com.spotify.music"),
        JELLYFIN to listOf("org.jellyfin.mobile"),
        "com.amazon.amazonvideo.livingroom" to listOf("com.amazon.avod.thirdpartyclient"),
        "com.hulu.livingroomplus" to listOf("com.hulu.plus"),
        // shortcut: Filmin's mobile package is unverified (not installed anywhere here); check `pm list packages` when it is
        "com.filmin.androidtv" to listOf("es.filmin.app"),
    )

    /** The TV app a phone/tablet package stands for (com.spotify.music -> com.spotify.tv.android), or itself. */
    fun canonical(pkg: String) = MOBILE.entries.firstOrNull { pkg in it.value }?.key ?: pkg

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
