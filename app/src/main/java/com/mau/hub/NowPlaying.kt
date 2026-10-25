package com.mau.hub

import android.content.ComponentName
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Whatever another app is playing (any media session; needs notification access, see README), as a "Now playing"
 * row on top of Home. onChange gets the row to show, or null when nothing is playing.
 */
class NowPlaying(private val a: MainActivity, private val onChange: (NowRow?) -> Unit) {
    private var controller: MediaController? = null
    private val msm get() = a.getSystemService(MediaSessionManager::class.java)
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
    }

    fun start() {
        try {
            val me = ComponentName(a, NowPlayingListener::class.java)
            pick(msm.getActiveSessions(me))
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, a.hub.main)
        } catch (_: SecurityException) { /* notification access not granted: no Now Playing row */ }
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
    }

    private fun pick(list: List<MediaController>) {
        controller?.unregisterCallback(callback)
        controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        controller?.registerCallback(callback, a.hub.main)
        render()
    }

    /** The current row, or null; also called after each home load, which clears the rows. */
    fun render() {
        val c = controller
        val state = c?.playbackState?.state
        val active = c != null && c.packageName != a.packageName && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED)
        if (!active) { onChange(null); return }
        val md = c!!.metadata
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val source = when (Links.canonical(c.packageName)) {
            Links.SPOTIFY -> Source.SPOTIFY; Links.NETFLIX -> Source.NETFLIX; Links.SMARTTUBE -> Source.YOUTUBE
            Links.JELLYFIN -> Source.JELLYFIN; Links.STREMIO -> Source.STREMIO; else -> Source.APP
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString() ?: source.label
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle?.toString().orEmpty()
        val label = (if (state == PlaybackState.STATE_PAUSED) "❚❚ Paused" else "▶ Now playing") + " · " + source.label
        val item = Item(source, title, Play.App(c.packageName), subtitle = artist, wide = true,
            icon = art?.let { BitmapDrawable(a.resources, it) } ?: runCatching { a.packageManager.getApplicationIcon(c.packageName) }.getOrNull())
        // tap = back to the app that's playing; play/pause keys on the remote still work from here
        onChange(NowRow(-1, label, item) { runCatching { c.sessionActivity?.send() ?: a.apps.launch(c.packageName) } })
    }
}
