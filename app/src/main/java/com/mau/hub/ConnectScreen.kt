package com.mau.hub

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address

/**
 * "Connect accounts": a QR code and short link to the phone page ([PhonePage]) where you sign in to Jellyseerr,
 * Stremio, Spotify and YouTube; Jellyfin's Quick Connect runs here on the TV. Closing it reloads Home.
 */
class ConnectScreen(private val a: MainActivity) {
    private val hub get() = a.hub

    /** The TV's IPv4 address on the network (Wi-Fi or Ethernet), for the phone to reach it. */
    private fun tvAddress(): String? {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    }

    private fun qr(text: String, px: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
        return Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565).apply {
            for (x in 0 until px) for (y in 0 until px) setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    fun show() {
        val host = tvAddress() ?: return a.toast("The TV isn't on a network")
        val status = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val googleCode = a.text(22f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        fun refresh() {
            status.removeAllViews()
            listOf(
                "Jellyfin" to hub.jfKey.isNotEmpty(), "Jellyseerr" to hub.prefs.contains("seerr_key"), "Stremio" to hub.stremioKey.isNotEmpty(),
                "Spotify" to hub.connected("spotify"), "YouTube" to hub.connected("google"),
            ).forEach { (name, on) ->
                status.addView(a.text(17f, if (on) 0xFF5FD068.toInt() else TEXT_MUTED).apply { text = (if (on) "✓  " else "–  ") + name; setPadding(0, a.dp(4), 0, a.dp(4)) })
            }
        }
        val page = PhonePage(hub, host,
            onGoogleCode = { d -> googleCode.text = d?.let { "YouTube: go to ${it.url.removePrefix("https://")} and enter  ${it.userCode}" }.orEmpty() },
            onChange = ::refresh)
        runCatching { page.start() }.onFailure { return a.toast("Couldn't open the sign-in page: ${it.message}") }
        refresh()

        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG); setPadding(a.dp(64), a.dp(40), a.dp(64), a.dp(40))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(a).apply { setImageBitmap(qr(page.url, 480)) }, LinearLayout.LayoutParams(a.dp(220), a.dp(220)))
                // wider than the QR code: wrap_content so the column grows instead of cutting the link's code off
                addView(a.text(15f, TEXT_SOFT).apply { text = page.url; isSingleLine = true; setPadding(0, a.dp(12), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(a.dp(56), 0, 0, 0)
                addView(a.text(30f, Color.WHITE).apply { text = "Connect accounts"; typeface = Typeface.create("sans-serif", Typeface.BOLD) })
                addView(a.text(15f, TEXT_SOFT).apply {
                    text = "Scan the code with your phone (same Wi-Fi) and sign in there. Nothing to type on the TV."
                    setPadding(0, a.dp(8), 0, a.dp(16))
                })
                addView(status)
                addView(googleCode, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(12) })
                addView(LinearLayout(a).apply {
                    setPadding(0, a.dp(24), 0, 0)
                    addView(a.button(if (hub.jfKey.isEmpty()) "Connect Jellyfin" else "Reconnect Jellyfin") { a.actions.quickConnect() })
                    addView(a.button("Done") { dialog.dismiss() })
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        dialog.setOnDismissListener { page.stop(); a.loadHome() }
        dialog.show()
    }
}
