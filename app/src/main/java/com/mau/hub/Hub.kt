package com.mau.hub

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

/** Shared state and services for every part of the home screen: settings, thread pools, accounts, Jellyfin access. */
class Hub(val ctx: Context) {
    val pool = Sources.bgPool(8) // network-bound row loads, background priority so scrolling stays smooth
    val imagePool = Sources.bgPool(4) // separate, so posters never queue behind slow row loads
    val diskPool = Executors.newSingleThreadExecutor() // saved rows: quick sequential reads at normal priority
    val main = Handler(Looper.getMainLooper())
    val prefs: SharedPreferences = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)

    val server get() = prefs.getString("server", BuildConfig.JELLYFIN_SERVER)!!.trimEnd('/')
    val jfKey get() = prefs.getString("key", "")!!
    val stremioKey get() = prefs.getString("stremio", "")!!
    val country = Locale.getDefault().country.ifEmpty { "US" }
    @Volatile var userId: String? = null

    /** Home Assistant for the tablet's section: the home address when it answers (on the home Wi-Fi), else the public one. Off the UI thread. */
    fun haUrl(): String? {
        val local = BuildConfig.HA_LOCAL.takeIf { it.isNotEmpty() }
        val answers = local != null && runCatching {
            (java.net.URL(local).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 1500; readTimeout = 1500; requestMethod = "HEAD"
                try { responseCode in 200..499 } finally { disconnect() }
            }
        }.getOrDefault(false)
        return if (answers) local else BuildConfig.HA_REMOTE.takeIf { it.isNotEmpty() } ?: local
    }

    fun jfUser() = userId ?: prefs.getString("uid", null) ?: Sources.jellyfinUser(server, jfKey).also { userId = it }

    // replace, not format(): search terms arrive URL-encoded ("%20h" would be read as a format specifier)
    fun jf(path: String): List<Item> = Sources.jellyfin(server, jfKey, path.replace("%s", jfUser()))

    /** Phone or tablet (touchscreen, no TV remote UI): different app versions, links and layout. */
    val touch = !ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    /** The phone app (its own flavor): Pro Launcher-style Home instead of the tablet's sections (see PhoneHome). */
    val phone = BuildConfig.FLAVOR == "phone"

    init { Sources.jwPlatform = if (touch) "ANDROID" else "ANDROID_TV" } // catalogue links that open the right app version

    // disabled counts as not installed: TVs ship apps like Prime Video preinstalled but switched off
    private fun enabled(pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

    /** The installed version of an app: the TV package, or its phone/tablet version ([Links.MOBILE]); null if none. */
    fun pkgFor(pkg: String) = (listOf(pkg) + Links.MOBILE[pkg].orEmpty()).firstOrNull(::enabled)

    fun installed(pkg: String) = pkgFor(pkg) != null

    fun providers() = PROVIDERS.filter { installed(it.pkg) }

    // ---- accounts ----

    fun connected(svc: String) = prefs.contains("${svc}_refresh")

    private val accessTokens = HashMap<String, Pair<String, Long>>()

    /** Cached access token, refreshed ~10 min before Google/Spotify's 1-hour expiry. Called off the main thread. */
    @Synchronized fun token(svc: String): String? {
        accessTokens[svc]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val refresh = prefs.getString("${svc}_refresh", null) ?: return null
        val form = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to prefs.getString("${svc}_client_id", "")!!)
        prefs.getString("${svc}_client_secret", null)?.let { form["client_secret"] = it }
        val url = if (svc == "google") "https://oauth2.googleapis.com/token" else "https://accounts.spotify.com/api/token"
        val (access, rotated) = Sources.refresh(url, form)
        rotated?.let { prefs.edit().putString("${svc}_refresh", it).apply() }
        accessTokens[svc] = access to System.currentTimeMillis() + 50 * 60_000
        return access
    }

    /** Channel ids you subscribe to; cached a day because the list costs API quota (the RSS feeds don't). */
    @Synchronized fun subChannelIds(): List<String> {
        val fresh = System.currentTimeMillis() - prefs.getLong("yt_subs_at", 0) < 24 * 3600_000
        return (prefs.getString("yt_subs", null)?.takeIf { fresh }?.split(",")
            ?: Sources.youtubeSubscriptions(token("google")!!).map { it.first }.also {
                prefs.edit().putString("yt_subs", it.joinToString(",")).putLong("yt_subs_at", System.currentTimeMillis()).apply()
            }).filter { it.isNotEmpty() }
    }

    private var subsVideos: Pair<Int, List<Item>>? = null

    /** Latest uploads from your subscriptions, fetched once per home load (gen) and shared by both YouTube rows. */
    fun youtubeSubs(gen: Int): List<Item> = synchronized(this) {
        subsVideos?.takeIf { it.first == gen }?.second
            ?: Sources.latestFromChannels(subChannelIds()).also { subsVideos = gen to it }
    }

    fun youtubeDiscover(gen: Int, seeds: Int) =
        Sources.youtubeDiscover(youtubeSubs(gen).take(seeds).map { (it.play as Play.Link).uri.substringAfter("v=") }, subChannelIds().toSet(), country)

    fun shutdown() { pool.shutdownNow(); imagePool.shutdownNow(); diskPool.shutdownNow() }
}
