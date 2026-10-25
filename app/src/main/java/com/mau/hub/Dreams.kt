package com.mau.hub

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Settings both screensavers read (the launcher's own settings file). */
private fun Context.hubPrefs() = getSharedPreferences("hub", Context.MODE_PRIVATE)

/** The picture in use as Joint Launcher's background (Settings → Background → My pictures), if any. */
private fun Context.backgroundPicture(): java.io.File? {
    val p = hubPrefs()
    if (p.getString("bg_mode", null) != "picture") return null
    val dir = java.io.File(filesDir, "backgrounds")
    val id = p.getString("bg_current", null)?.takeIf { java.io.File(dir, it).exists() }
        ?: dir.list().orEmpty().filter { !it.endsWith(".portrait") }.minByOrNull { it.toLongOrNull() ?: 0L } ?: return null
    return java.io.File(dir, id)
}

/**
 * Screensaver: your background, the big flip clock and the weather. Everything drifts to a new spot every minute so
 * nothing stays burnt into the screen.
 */
class ClockDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var block: LinearLayout
    private lateinit var weather: android.widget.TextView
    private val drift = object : Runnable {
        override fun run() {
            val room = block.parent as? FrameLayout ?: return
            block.translationX = (Math.random().toFloat() - 0.5f) * (room.width - block.width).coerceAtLeast(0) * 0.8f
            block.translationY = (Math.random().toFloat() - 0.5f) * (room.height - block.height).coerceAtLeast(0) * 0.8f
            refreshWeather()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true
        val clock = FlipClock(this, 170).apply { timeZone = hubPrefs().getString("clock_tz", null) }
        weather = text(34f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER }
        block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(clock, LinearLayout.LayoutParams(-2, -2)); addView(weather, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            backgroundPicture()?.let { f ->
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(f.path))
                    foreground = android.graphics.drawable.ColorDrawable(0x66000000) // a little darker than Home: it's on for hours
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(block, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        main.postDelayed(drift, 60_000)
        refreshWeather()
    }

    private fun refreshWeather() = pool.execute {
        val p = hubPrefs()
        val text = runCatching { Sources.weather(p.getString("lat", null)!!.toDouble(), p.getString("lon", null)!!.toDouble()) }.getOrNull()
        main.post { weather.text = text.orEmpty() }
    }

    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); pool.shutdownNow(); super.onDetachedFromWindow() }
}

/**
 * Screensaver: your Immich photos, all of them (random) or one album (Settings → Screensaver), one every 15 seconds
 * with a cross-fade, and a small flip clock in the corner.
 */
class PhotoDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val shown = arrayOfNulls<ImageView>(2)
    private var front = 0
    @Volatile private var ids: List<String> = emptyList()
    private var next = 0
    // no photos yet (Immich stopped or unreachable): ask again every minute, the clock keeps going meanwhile
    private val step = object : Runnable {
        override fun run() {
            if (ids.isEmpty()) { load(); main.postDelayed(this, 60_000) } else { showNext(); main.postDelayed(this, 15_000) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true
        val room = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        repeat(2) { i -> shown[i] = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }.also { room.addView(it, FrameLayout.LayoutParams(-1, -1)) } }
        room.addView(FlipClock(this, 56).apply { timeZone = hubPrefs().getString("clock_tz", null) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(40)) })
        setContentView(room)
        val p = hubPrefs()
        val base = p.getString("immich_url", null); val key = p.getString("immich_key", null)
        if (base == null || key == null) return // not connected: a dark screen with the clock (connect Immich from the phone page)
        main.post(step)
    }

    private fun load(): Unit = pool.execute {
        val p = hubPrefs()
        val base = p.getString("immich_url", null) ?: return@execute; val key = p.getString("immich_key", null) ?: return@execute
        ids = runCatching { Immich.photos(base, key, p.getString("immich_album", null)) }.getOrDefault(emptyList())
        if (ids.isNotEmpty()) main.post { main.removeCallbacks(step); step.run() }
    }

    private fun showNext() {
        if (ids.isEmpty()) return
        val p = hubPrefs()
        val base = p.getString("immich_url", null) ?: return; val key = p.getString("immich_key", null) ?: return
        val id = ids[next % ids.size]; next++
        pool.execute {
            val bmp = Immich.photo(base, key, id) ?: return@execute
            main.post {
                val incoming = shown[1 - front]!!; val outgoing = shown[front]!!
                incoming.setImageBitmap(bmp)
                incoming.animate().alpha(1f).setDuration(1500).start()
                outgoing.animate().alpha(0f).setDuration(1500).start()
                front = 1 - front
            }
        }
    }

    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); pool.shutdownNow(); super.onDetachedFromWindow() }
}
