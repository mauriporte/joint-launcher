package com.mau.hub

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Profiles: one per Jellyfin user, with that user's picture and name (the avatar in the top bar). Switching changes the
 * Jellyfin user (Continue watching, Next up, channel favourites, watched marks) and swaps in that profile's own favourite
 * and hidden apps, background, visible rows and services, section order and locked apps. A profile used for the first
 * time starts as a copy of the one you were on.
 */
class Profiles(private val a: MainActivity) {
    class Profile(val id: String, val name: String)

    private val hub get() = a.hub

    /** The profile on screen: the chosen Jellyfin user, else the server's first (Hub.jfUser). Called off the main thread. */
    fun currentId(): String? = runCatching { hub.jfUser() }.getOrNull()

    /** The server's users that can sign in. Called off the main thread. */
    fun list(): List<Profile> = parse(Sources.get("${hub.server}/Users", Links.jfAuth(hub.jfKey)))

    fun avatarUrl(id: String) = "${hub.server}/Users/$id/Images/Primary?maxHeight=120&quality=90"

    /** Who's watching? The profiles to pick from, the current one ticked. */
    fun pick(done: () -> Unit) = hub.pool.execute {
        val all = runCatching { list() }.getOrDefault(emptyList())
        val now = currentId()
        hub.main.post {
            if (all.isEmpty()) { a.toast("No profiles: connect Jellyfin in Settings first"); return@post }
            AlertDialog.Builder(a).setTitle("Who's watching?")
                .setSingleChoiceItems(all.map { it.name }.toTypedArray(), all.indexOfFirst { it.id == now }) { d, i ->
                    d.dismiss()
                    if (all[i].id != now) { switchTo(now, all[i].id); done() }
                }.show()
        }
    }

    private fun switchTo(from: String?, to: String) {
        if (from != null) copy(hub.prefs, store(from), clearFirst = true)
        val saved = store(to)
        if (saved.all.isNotEmpty()) { hub.prefs.edit().apply { KEYS.forEach(::remove) }.commit(); copy(saved, hub.prefs, clearFirst = false) }
        hub.prefs.edit().putString("uid", to).commit()
        hub.userId = to
        a.applyBackground(); a.loadHome()
    }

    private fun store(id: String) = a.getSharedPreferences("profile_$id", Context.MODE_PRIVATE)

    companion object {
        /** What each profile keeps for itself (everything else, like accounts and the weather, is the TV's). */
        val KEYS = setOf("fav_apps", "fav_order", "hidden_apps", "apps_favs_only", "bg_mode", "bg_current", "bg_dim",
            "hidden_rows", "extra_rows", "row_order", "home_sections", "locked_apps")

        fun parse(json: String): List<Profile> = JSONArray(json).let { arr ->
            (0 until arr.length()).map(arr::getJSONObject)
                .filter { it.optJSONObject("Policy")?.optBoolean("IsDisabled") != true }
                .map { Profile(it.getString("Id"), it.optString("Name")) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun copy(from: SharedPreferences, to: SharedPreferences, clearFirst: Boolean) = to.edit().apply {
            if (clearFirst) clear()
            from.all.filterKeys { it in KEYS }.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v)
                    is Long -> putLong(k, v); is Float -> putFloat(k, v); is Set<*> -> putStringSet(k, v as Set<String>)
                }
            }
        }.commit()
    }
}
