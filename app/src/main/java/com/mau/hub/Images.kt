package com.mau.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/** Posters, backdrops and logos: downloaded off the UI thread, decoded small, kept in a byte-sized memory cache. */
class Images(private val hub: Hub) {
    // sized by bytes, not count: a few 1440p backdrops used to fill the heap and stall the UI with GC
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** big = full-screen/backdrop use (decoded up to 1280px); tiles and logos stay small (~2x their 92-176dp size). */
    fun load(url: String, into: ImageView, big: Boolean = false) {
        val maxPx = if (big) 1280 else 360
        val key = "$url@$maxPx"
        into.tag = key
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        val headers = if (url.startsWith(hub.server)) Links.jfAuth(hub.jfKey) else emptyMap()
        hub.imagePool.execute {
            val bmp = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 6000; readTimeout = 12000
                    headers.forEach(::setRequestProperty)
                    try { decodeScaled(inputStream.use { it.readBytes() }, maxPx) } finally { disconnect() }
                }
            }.getOrNull() ?: return@execute
            cache.put(key, bmp)
            hub.main.post { if (into.tag == key) into.setImageBitmap(bmp) }
        }
    }

    /** Decode at the smallest power-of-two scale that still covers maxPx on the longer side. */
    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
