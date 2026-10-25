package com.mau.hub

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri

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
                    .putExtra("server", hub.server).putExtra("itemId", p.id) // so the player can report progress and fetch subtitles
                    .putExtra("userId", hub.userId ?: hub.prefs.getString("uid", null))
            )
            is Play.Link -> try {
                val i = if (p.uri.startsWith("intent:")) Intent.parseUri(p.uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null; selector = null // remote data: only ever let it reach the target app
                } else Intent(Intent.ACTION_VIEW, Uri.parse(p.uri))
                a.startActivity(i.setPackage(hub.pkgFor(p.pkg) ?: p.pkg)) // the TV or phone/tablet version, whichever is installed
            } catch (_: ActivityNotFoundException) { a.apps.launch(p.pkg) }
            is Play.JellyLive -> {
                a.toast("Tuning in…")
                hub.pool.execute {
                    val s = runCatching { Sources.jellyfinLiveUrl(hub.server, hub.jfKey, hub.jfUser(), p.channelId) }
                    hub.main.post {
                        s.onSuccess {
                            a.startActivity(Intent(a, PlayerActivity::class.java).putExtra("url", it.url).putExtra("key", hub.jfKey)
                                .putExtra("server", hub.server).putExtra("liveStreamId", it.id)
                                // this TV's MPEG-2 hardware decoder is unusable (MediaCodec can't create it): VLC decodes those in software.
                                // Phones and tablets have Android's own software MPEG-2 decoder, so they keep our player (VLC if it errors).
                                .putExtra("vlc", !hub.touch && it.videoCodec in setOf("mpeg2video", "mpeg1video")))
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

    /** A series' Play: your next episode (Jellyfin's "Next up"), or the first one if you haven't started it. */
    fun playSeries(seriesId: String) {
        hub.pool.execute {
            val ep = runCatching {
                hub.jf("/Shows/NextUp?userId=%s&seriesId=$seriesId&limit=1").firstOrNull()
                    ?: hub.jf("/Shows/$seriesId/Episodes?userId=%s&limit=1").firstOrNull()
            }
            hub.main.post {
                ep.onSuccess { it?.let { e -> open(e.play) } ?: a.toast("No episodes found") }
                    .onFailure { a.toast("Couldn't find the next episode: ${Sources.explain(it)}") }
            }
        }
    }

    /** "Connect" tiles on Home for services that are installed but not signed in yet. */
    fun connectEntries(): List<Pair<Item, () -> Unit>> = buildList {
        fun tile(source: Source, pkg: String) = Item(source, "Connect ${source.label}", Play.App(pkg), wide = true,
            icon = runCatching { (hub.pkgFor(pkg) ?: pkg).let { a.packageManager.getApplicationBanner(it) ?: a.packageManager.getApplicationIcon(it) } }.getOrNull())
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
    fun storageLine(): String {
        val s = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        fun size(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b shr 20} MB"
        val ours = a.cacheDir.walkTopDown().sumOf { it.length() } + a.filesDir.walkTopDown().sumOf { it.length() }
        return (if (hub.touch) "Storage" else "TV storage") + ": ${size(s.availableBytes)} free of ${size(s.totalBytes)} · Joint Launcher uses ${size(ours)}"
    }

    fun systemSettings() {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }.onFailure { a.toast("Couldn't open Settings") }
    }

    /** Settings: one panel with everything (TabletSettings), on the TV too. */
    fun settings() = TabletSettings(a).show()
}
