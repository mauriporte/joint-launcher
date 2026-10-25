package com.mau.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Your Immich server, for the photo screensaver: sign-in (an API key, made once from the phone page), your albums,
 * and the photos to show (random ones from the whole library, or one album's). Parsers are pure, for the tests.
 */
object Immich {
    private fun auth(key: String) = mapOf("x-api-key" to key, "Accept" to "application/json")

    /** Email and password -> a "Joint Launcher" API key; the password is only used for this and never stored. */
    fun createKey(base: String, email: String, password: String): String {
        val token = JSONObject(Sources.post("$base/api/auth/login", JSONObject().put("email", email).put("password", password).toString()))
            .getString("accessToken")
        val body = JSONObject().put("name", "Joint Launcher").put("permissions", JSONArray().put("all"))
        return JSONObject(Sources.post("$base/api/api-keys", body.toString(), mapOf("Authorization" to "Bearer $token"))).getString("secret")
    }

    /** Whose key it is (checks the key works). */
    fun userName(base: String, key: String) = JSONObject(Sources.get("$base/api/users/me", auth(key))).optString("name")

    /** Your albums as (id, "name · N photos"). */
    fun albums(base: String, key: String) = parseAlbums(Sources.get("$base/api/albums", auth(key)))

    fun parseAlbums(json: String): List<Pair<String, String>> = JSONArray(json).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("id") to "${it.optString("albumName")} · ${it.optInt("assetCount")} photos" }
    }

    /** Photo ids to show: [count] random ones from the library, or an album's photos (shuffled). */
    fun photos(base: String, key: String, album: String?, count: Int = 60): List<String> =
        if (album == null) parseIds(Sources.post("$base/api/search/random", JSONObject().put("size", count).put("type", "IMAGE").toString(), auth(key)))
        else parseAlbumPhotos(Sources.get("$base/api/albums/$album", auth(key))).shuffled()

    /** Ids of the images in a search result (videos skipped). */
    fun parseIds(json: String): List<String> = JSONArray(json).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type", "IMAGE") == "IMAGE" }.map { it.getString("id") }
    }

    fun parseAlbumPhotos(json: String) = parseIds(JSONObject(json).optJSONArray("assets")?.toString() ?: "[]")

    /** One photo, at the server's "preview" size (about 1440 px: right for a TV, far lighter than the original). */
    fun photo(base: String, key: String, id: String): Bitmap? = runCatching {
        (URL("$base/api/assets/$id/thumbnail?size=preview").openConnection() as HttpURLConnection).run {
            connectTimeout = 6000; readTimeout = 15000; setRequestProperty("x-api-key", key)
            try { if (responseCode == 200) inputStream.use(BitmapFactory::decodeStream) else null } finally { disconnect() }
        }
    }.getOrNull()
}
