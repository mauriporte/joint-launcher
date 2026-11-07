package com.mau.hub

import android.content.Context
import org.json.JSONObject

/**
 * Home-network addresses that also have an outside one (a Cloudflare name, say): the home address while it answers,
 * the outside one when it doesn't (away from home). The pairs come from assets/remote_urls.json, not in git:
 * {"http://home-address:port": "https://outside.example.com", ...}. Without the file every address is left as it is.
 */
object Lan {
    @Volatile private var pairs: Map<String, String> = emptyMap()
    @Volatile private var away: Set<String> = emptySet() // home addresses that didn't answer at the last check
    private var loaded = false

    fun init(c: Context) {
        if (loaded) return
        loaded = true
        pairs = runCatching {
            val o = JSONObject(c.assets.open("remote_urls.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associate { it.trimEnd('/') to o.getString(it).trimEnd('/') }
        }.getOrDefault(emptyMap())
    }

    /** [url] as it should be opened now, from the last [check]: no network call here. */
    fun pick(url: String): String = swap(url, pairs, away)

    /** Tries each home address (a 1.5 s connection, off the main thread); [pick] follows the result. */
    fun check() { away = pairs.keys.filterNot(::answers).toSet() }

    private fun answers(home: String) = runCatching {
        val u = java.net.URL(home)
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else u.defaultPort), 1500) }
    }.isSuccess

    internal fun swap(url: String, pairs: Map<String, String>, away: Set<String>): String {
        val home = pairs.keys.firstOrNull { url == it || url.startsWith("$it/") } ?: return url
        return if (home in away) pairs.getValue(home) + url.removePrefix(home) else url
    }
}
