package com.mau.hub

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Account sign-in done on the TV itself (no PC): Google's TV-code flow for YouTube, Spotify's PKCE flow (its login
 * page sends you back through a relay page, see docs/spotify.html), and Jellyseerr from your Jellyfin login.
 */
object SignIn {
    /** Spotify sends the login back here; the page forwards it to the TV on your network. Must be in the Spotify app's Redirect URIs. */
    const val SPOTIFY_REDIRECT = "https://mauriporte.github.io/joint-launcher/spotify.html"
    private const val SPOTIFY_SCOPE = "user-follow-read user-library-read playlist-read-private user-top-read"
    private const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun random(bytes: Int) = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    /** Form POST that also reads error answers (Google says "still waiting" with an HTTP 428). */
    private fun form(url: String, form: Map<String, String>): Pair<Int, JSONObject> =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 15000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            outputStream.use { it.write(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()) }
            try {
                val code = responseCode
                val text = (if (code >= 400) errorStream else inputStream)?.bufferedReader()?.readText().orEmpty()
                code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            } finally { disconnect() }
        }

    // ---- YouTube: Google's TV-code sign-in (needs an OAuth client of type "TVs and Limited Input devices") ----

    class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalS: Int, val expiresAt: Long)

    fun googleStart(clientId: String): DeviceCode {
        val (code, j) = form("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to YOUTUBE_SCOPE))
        if (code >= 400) error(j.optString("error_description", j.optString("error", "HTTP $code")))
        return DeviceCode(j.getString("device_code"), j.getString("user_code"), j.optString("verification_url", "google.com/device"),
            j.optInt("interval", 5), System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000)
    }

    /** The refresh token once you've approved on your phone, null while still waiting; throws if denied or expired. */
    fun googlePoll(clientId: String, secret: String, d: DeviceCode): String? {
        val (code, j) = form("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to clientId, "client_secret" to secret, "device_code" to d.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        ))
        if (code < 400) return j.getString("refresh_token")
        return when (j.optString("error")) {
            "authorization_pending", "slow_down" -> null
            "access_denied" -> error("sign-in was declined")
            "expired_token" -> error("the code expired, start again")
            else -> error(j.optString("error_description", j.optString("error", "HTTP $code")))
        }
    }

    // ---- Spotify: PKCE, back through the relay page ----

    fun challenge(verifier: String) = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** state carries the TV's address so the relay page knows where to send you back. */
    fun spotifyAuthorizeUrl(clientId: String, verifier: String, state: String) =
        "https://accounts.spotify.com/authorize?response_type=code&client_id=${enc(clientId)}&scope=${enc(SPOTIFY_SCOPE)}" +
            "&redirect_uri=${enc(SPOTIFY_REDIRECT)}&code_challenge_method=S256&code_challenge=${challenge(verifier)}&state=${enc(state)}"

    fun spotifyExchange(clientId: String, code: String, verifier: String): String {
        val (status, j) = form("https://accounts.spotify.com/api/token", mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to SPOTIFY_REDIRECT,
            "client_id" to clientId, "code_verifier" to verifier,
        ))
        if (status >= 400) error(j.optString("error_description", j.optString("error", "HTTP $status")))
        return j.getString("refresh_token")
    }

    // ---- Jellyseerr: sign in with your Jellyfin account, read the API key, sign out (the password isn't kept) ----

    fun seerrApiKey(base: String, username: String, password: String): String {
        val url = base.trimEnd('/')
        val login = (URL("$url/api/v1/auth/jellyfin").openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 15000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // same body Jellyseerr's own login page sends (it repeats the username as email)
            outputStream.use { it.write(JSONObject(mapOf("username" to username, "password" to password, "email" to username)).toString().toByteArray()) }
            try {
                if (responseCode >= 400) throw Sources.FetchError(URL(url).host, responseCode)
                headerFields["Set-Cookie"].orEmpty().joinToString("; ") { it.substringBefore(';') }
            } finally { disconnect() }
        }
        val cookie = mapOf("Cookie" to login)
        val key = JSONObject(Sources.get("$url/api/v1/settings/main", cookie)).optString("apiKey")
        runCatching { Sources.post("$url/api/v1/auth/logout", "{}", cookie) }
        if (key.isEmpty()) error("signed in, but this account can't see the API key (it must be a Jellyseerr admin)")
        return key
    }

    /** Fails loudly if the address or key is wrong; returns the account's display name. */
    fun seerrCheck(base: String, key: String): String =
        JSONObject(Sources.get("${base.trimEnd('/')}/api/v1/auth/me", mapOf("X-Api-Key" to key))).optString("displayName")
}
