package com.mau.hub

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** What pressing things does: open a title in its app or our player, connect Jellyfin, the Settings dialog. */
class Actions(private val a: MainActivity) {
    private val hub get() = a.hub

    // every title gets the info screen first; only app tiles open straight away
    fun click(item: Item) = if (item.source == Source.APP) open(item.play) else a.detail.show(item)

    fun open(p: Play) {
        when (p) {
            is Play.App -> a.apps.launch(p.pkg)
            is Play.Jelly -> if (p.folder) a.apps.launch(Links.JELLYFIN) else a.startActivity(
                Intent(a, PlayerActivity::class.java)
                    .putExtra("url", Links.jfStream(hub.server, p.id)).putExtra("key", hub.jfKey).putExtra("pos", p.posMs)
                    .putExtra("server", hub.server).putExtra("itemId", p.id) // so the player can report progress for resuming
            )
            is Play.Link -> try {
                val i = if (p.uri.startsWith("intent:")) Intent.parseUri(p.uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null; selector = null // remote data: only ever let it reach the target app
                } else Intent(Intent.ACTION_VIEW, Uri.parse(p.uri))
                a.startActivity(i.setPackage(p.pkg))
            } catch (_: ActivityNotFoundException) { a.apps.launch(p.pkg) }
            is Play.JellyLive -> {
                a.toast("Tuning in…")
                hub.pool.execute {
                    val s = runCatching { Sources.jellyfinLiveUrl(hub.server, hub.jfKey, hub.jfUser(), p.channelId) }
                    hub.main.post {
                        s.onSuccess {
                            a.startActivity(Intent(a, PlayerActivity::class.java).putExtra("url", it.url).putExtra("key", hub.jfKey)
                                .putExtra("server", hub.server).putExtra("liveStreamId", it.id)
                                // this TV's MPEG-2 hardware decoder is unusable (MediaCodec can't create it): VLC decodes those in software
                                .putExtra("vlc", it.videoCodec in setOf("mpeg2video", "mpeg1video")))
                        }.onFailure { a.toast("Couldn't start this channel: ${it.message}") }
                    }
                }
            }
            is Play.StremioSearch -> {
                a.toast("Looking for “${p.title}” in Stremio…")
                hub.pool.execute {
                    val id = runCatching { Sources.stremioFind(p.title, p.year, p.type) }.getOrNull()
                    hub.main.post {
                        if (id != null) open(Play.Link(Links.stremioDetail(p.type, id), Links.STREMIO))
                        else a.toast("Couldn't find “${p.title}” in Stremio")
                    }
                }
            }
        }
    }

    /** "Connect" tiles on Home for services that are installed but not signed in yet. */
    fun connectEntries(): List<Pair<Item, () -> Unit>> = buildList {
        fun tile(source: Source, pkg: String) = Item(source, "Connect ${source.label}", Play.App(pkg), wide = true,
            icon = runCatching { a.packageManager.getApplicationBanner(pkg) ?: a.packageManager.getApplicationIcon(pkg) }.getOrNull())
        if (hub.jfKey.isEmpty() && hub.installed(Links.JELLYFIN)) add(tile(Source.JELLYFIN, Links.JELLYFIN) to ::quickConnect)
        if (!hub.connected("google") && hub.installed(Links.SMARTTUBE)) add(tile(Source.YOUTUBE, Links.SMARTTUBE) to { ConnectScreen(a).show() })
        if (!hub.connected("spotify") && hub.installed(Links.SPOTIFY)) add(tile(Source.SPOTIFY, Links.SPOTIFY) to { ConnectScreen(a).show() })
    }

    fun quickConnect() {
        val device = "tvhub-" + android.provider.Settings.Secure.getString(a.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val msg = a.text(20f, Color.WHITE).apply { text = "Contacting ${hub.server}…"; setPadding(a.dp(24), a.dp(16), a.dp(24), 0) }
        var waiting = true
        val dialog = AlertDialog.Builder(a).setTitle("Connect Jellyfin").setView(msg)
            .setNegativeButton("Cancel", null).setOnDismissListener { waiting = false }.show()
        hub.pool.execute {
            val r = runCatching {
                val (secret, code) = Sources.quickConnectStart(hub.server, device)
                hub.main.post { msg.text = "On your phone or the Jellyfin website go to\nProfile → Quick Connect and enter:\n\n$code" }
                while (waiting && !Sources.quickConnectApproved(hub.server, device, secret)) Thread.sleep(3000)
                if (waiting) Sources.quickConnectFinish(hub.server, device, secret) else null
            }
            hub.main.post {
                r.onSuccess { auth ->
                    auth ?: return@onSuccess
                    hub.prefs.edit().putString("key", auth.first).putString("uid", auth.second).apply()
                    hub.userId = auth.second; dialog.dismiss(); a.toast("Jellyfin connected"); a.loadHome()
                }.onFailure { msg.text = "Quick Connect failed: ${it.message}\n(Is Quick Connect enabled in the Jellyfin dashboard?)" }
            }
        }
    }

    /** "TV storage: 1.4 GB free of 4.0 GB · Joint Launcher uses 38 MB", so a full TV isn't a surprise. */
    private fun storageLine(): String {
        val s = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        fun size(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b shr 20} MB"
        val ours = a.cacheDir.walkTopDown().sumOf { it.length() } + a.filesDir.walkTopDown().sumOf { it.length() }
        return "TV storage: ${size(s.availableBytes)} free of ${size(s.totalBytes)} · Joint Launcher uses ${size(ours)}"
    }

    fun settings() {
        fun field(hint: String, value: String, password: Boolean = false) = EditText(a).apply {
            this.hint = hint; setText(value); isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val url = field("Jellyfin URL", hub.server)
        val key = field("Jellyfin API key (Dashboard → API Keys)", hub.jfKey)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            listOf(url, key).forEach(::addView)
            addView(a.text(13f, TEXT_MUTED).apply { text = storageLine(); setPadding(0, a.dp(12), 0, 0) })
        }
        AlertDialog.Builder(a).setTitle("Settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (key.text.toString().trim() != hub.jfKey) hub.prefs.edit().remove("uid").apply() // manual key: look the user up again
                hub.prefs.edit().putString("server", url.text.toString().trim()).putString("key", key.text.toString().trim()).apply()
                hub.userId = null
                a.loadHome()
            }
            // Jellyseerr, Stremio, Spotify and YouTube: signed in from your phone (QR code), nothing to type here
            .setNeutralButton("Connect accounts") { _, _ -> ConnectScreen(a).show() }
            .setNegativeButton("Cancel", null).show()
    }
}
