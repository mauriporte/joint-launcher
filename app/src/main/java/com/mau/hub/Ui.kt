package com.mau.hub

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// ---- colours ----

internal const val BG = 0xFF050505.toInt()           // near-black, Netflix TV style
internal const val ACCENT = 0xFFE8650A.toInt()       // dark orange (Netflix's red, in orange)
internal const val TEXT_SOFT = 0xFFC9CBD3.toInt()    // descriptions
internal const val TEXT_MUTED = 0xFF9A9DA8.toInt()   // meta lines
internal const val IMDB_YELLOW = 0xFFF5C518.toInt()
internal const val RT_FRESH = 0xFFFA320A.toInt()
internal const val RT_ROTTEN = 0xFF4E8A2E.toInt()
internal const val RAIL_DP = 64

// ---- view helpers, shared by every part of the screen ----

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Context.text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

internal fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** Badge in the service colour; dark text on light colours (GeeSports yellow) so it stays readable. */
internal fun paintBadge(badge: TextView, s: Source) {
    (badge.background as GradientDrawable).setColor(s.color)
    badge.setTextColor(if (Color.luminance(s.color) > 0.5f) Color.BLACK else Color.WHITE)
}

internal fun Context.badge(size: Float = 12f) = text(size, Color.WHITE).apply {
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(8), dp(2), dp(8), dp(2))
    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
}

internal fun Context.chip(label: String, bg: Int, fg: Int, size: Float) = badge(size).apply {
    text = label; setTextColor(fg); typeface = Typeface.create("sans-serif", Typeface.BOLD)
    (background as GradientDrawable).setColor(bg)
}

/** Pill button: translucent at rest, white with dark text when focused. */
internal fun Context.button(label: String, onClick: () -> Unit) = text(15f, Color.WHITE).apply {
    text = label; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
    setPadding(dp(24), dp(10), dp(24), dp(10))
    background = StateListDrawable().apply {
        // selected = the main action on touchscreens, where nothing has focus to light it up
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x26FFFFFF) })
    }
    isFocusable = true; isClickable = true
    setOnFocusChangeListener { _, f -> setTextColor(if (f || isSelected) Color.BLACK else Color.WHITE) }
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) }
}

/** Round icon button, same look as [button]: translucent idle, white with a dark icon when focused. */
/** TV: a white rounded outline while the view has focus (the remote has to see where it is); nothing at rest. */
internal fun View.tvFocus(radiusDp: Int = 10) {
    isFocusable = true
    foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = context.dp(radiusDp).toFloat(); setColor(Color.TRANSPARENT); setStroke(context.dp(3), Color.WHITE)
        })
    }
}

/** Tablet buttons: just the icon's lines, nothing around them; a soft round ripple shows the touch. */
// the phone: no touch ripple at all (Mau: a big circle on every press, not clean)
internal fun Context.bareButton(): android.graphics.drawable.Drawable? =
    if (BuildConfig.FLAVOR == "phone") null else android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)

internal fun Context.iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    contentDescription = label // read out by TalkBack
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    setPadding(dp(10), dp(10), dp(10), dp(10))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) })
    }
    isFocusable = true; isClickable = true
    setColorFilter(Color.WHITE)
    setOnFocusChangeListener { _, f -> setColorFilter(if (f) Color.BLACK else Color.WHITE) }
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) }
}

/** Thin outlined label for facts like "2 Seasons · 16 Episodes" or "2h 47m". */
internal fun Context.pill(label: String) = text(13f, Color.WHITE).apply {
    text = label; setPadding(dp(10), dp(3), dp(10), dp(3))
    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), 0x80FFFFFF.toInt()) }
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
}

/** An image that fades to transparent towards its left and bottom edges (the TV's title art behind the rows). */
internal class FadedImage(c: Context) : ImageView(c) {
    private val paint = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        // keep the picture where both masks are opaque: fully from 45% across and down to 35% from the bottom, gone at the edges
        paint.shader = android.graphics.LinearGradient(0f, 0f, w * 0.45f, 0f, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = android.graphics.LinearGradient(0f, h * 0.35f, 0f, h, 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restoreToCount(layer)
    }
}

internal fun fade() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BG, 0xEB050505.toInt(), 0x00050505))
