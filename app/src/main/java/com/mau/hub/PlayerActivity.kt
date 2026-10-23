package com.mau.hub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView

// shortcut: platform VideoView, direct-play only (no transcoding, no progress reported back to Jellyfin);
// switch to Media3/ExoPlayer + HLS transcode URL if formats fail often.
class PlayerActivity : Activity() {
    private lateinit var video: VideoView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = VideoView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        })
        val key = intent.getStringExtra("key").orEmpty()
        video.setVideoURI(Uri.parse(intent.getStringExtra("url")), Links.jfAuth(key))
        video.setOnPreparedListener { video.seekTo(intent.getLongExtra("pos", 0).toInt()); video.start() }
        video.setOnCompletionListener { finish() }
        video.setOnErrorListener { _, _, _ ->
            // VLC decodes what the TV can't (TrueHD, DTS...). It can't send headers, so the token rides in api_key (LAN only).
            val vlc = Intent(Intent.ACTION_VIEW).setPackage(VLC)
                .setDataAndType(Uri.parse("${intent.getStringExtra("url")}&api_key=$key"), "video/*")
                .putExtra("position", intent.getLongExtra("pos", 0))
            try {
                startActivity(vlc)
                Toast.makeText(this, "Playing in VLC", Toast.LENGTH_SHORT).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Format not supported here, opening Jellyfin", Toast.LENGTH_LONG).show()
                packageManager.getLeanbackLaunchIntentForPackage(Links.JELLYFIN)?.let(::startActivity)
            }
            finish(); true
        }
    }

    companion object { private const val VLC = "org.videolan.vlc" }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (video.isPlaying) video.pause() else video.start()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> video.seekTo(video.currentPosition - 10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> video.seekTo(video.currentPosition + 30_000)
            else -> return super.onKeyDown(code, e)
        }
        return true
    }
}
