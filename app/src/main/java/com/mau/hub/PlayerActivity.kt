package com.mau.hub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Jellyfin videos and live TV, on Media3/ExoPlayer: the remote's OK shows the controls, which include audio and
 * subtitle track choice. Progress goes back to Jellyfin so it can resume. What the TV can't decode (MPEG-2 live
 * channels, TrueHD/DTS audio...) is handed to the VLC app, which has to be installed for those.
 *
 * Extras: url, key (Jellyfin token), pos (ms), itemId (to report progress), server + liveStreamId (live TV, closed on
 * exit), vlc (true = straight to VLC).
 */
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val key get() = intent.getStringExtra("key").orEmpty()
    private val server get() = intent.getStringExtra("server")
    private val itemId get() = intent.getStringExtra("itemId")
    private val progress = object : Runnable {
        override fun run() { report("Progress"); main.postDelayed(this, 10_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (b != null) return // coming back from VLC after a restart: onActivityResult finishes us
        if (intent.getBooleanExtra("vlc", false)) { playInVlc(); return }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(Links.jfAuth(key))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build()
        player = p
        setContentView(PlayerView(this).apply { player = p; keepScreenOn = true })
        p.setMediaItem(MediaItem.fromUri(intent.getStringExtra("url")!!), intent.getLongExtra("pos", 0))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
            override fun onPlayerError(error: PlaybackException) { release(); playInVlc() }
        })
        p.playWhenReady = true
        p.prepare()
        report("")
        main.postDelayed(progress, 10_000)
    }

    /** Play/pause from the remote's media keys even while the controls are hidden. */
    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(code, e)
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { p.playWhenReady = !p.playWhenReady; return true }
        return super.onKeyDown(code, e)
    }

    /** Jellyfin library items only (live channels have nothing to resume). Network, so off the UI thread. */
    private fun report(event: String) {
        val id = itemId ?: return
        val server = intent.getStringExtra("server") ?: return
        val pos = player?.currentPosition ?: return
        val paused = player?.playWhenReady == false
        Thread { runCatching { Sources.jellyfinReport(server, key, event, id, pos, paused) } }.start()
    }

    private fun release() {
        main.removeCallbacks(progress)
        report("Stopped")
        player?.release(); player = null
    }

    /**
     * VLC decodes what the TV can't. It can't send headers, so the token rides in api_key (LAN only).
     * Started for a result so we're told when it's closed, and can free the live stream then.
     */
    private fun playInVlc() {
        val vlc = Intent(Intent.ACTION_VIEW).setPackage(VLC)
            .setDataAndType(Uri.parse("${intent.getStringExtra("url")}&api_key=$key"), "video/*")
            .putExtra("position", intent.getLongExtra("pos", 0))
        try {
            @Suppress("DEPRECATION") startActivityForResult(vlc, 1)
            Toast.makeText(this, "Playing in VLC", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "This video needs the VLC app: install VLC from the Play Store", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = finish() // VLC closed

    override fun onStop() {
        super.onStop()
        if (player != null) { release(); finish() } // Home or another app: stop, don't keep playing in the background
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
        if (isChangingConfigurations) return
        // live TV: each open stream holds one of the tuner backend's few slots until closed
        val live = intent.getStringExtra("liveStreamId") ?: return
        val server = server ?: return
        Thread { runCatching { Sources.jellyfinCloseLive(server, key, live) } }.start()
    }

    companion object { private const val VLC = "org.videolan.vlc" }
}
