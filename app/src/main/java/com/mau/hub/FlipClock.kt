package com.mau.hub

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar
import java.util.TimeZone

/**
 * The corner clock, in the look of the Xperia's flip clock: hours and minutes each on a dark rounded card, light grey
 * condensed digits, a thin black fold across the middle. Nothing is drawn around the cards, so the background shows
 * through. Always 24-hour; when a card changes its top leaf folds down onto the new number.
 */
internal class FlipClock(c: Context, cardDp: Int) : LinearLayout(c) {
    private val hours = FlipCard(c)
    private val minutes = FlipCard(c)
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            show(animate = true)
            val now = System.currentTimeMillis()
            ticker.postDelayed(this, 60_000 - now % 60_000 + 50) // just after the next minute starts
        }
    }
    var timeZone: String? = null
        set(v) { field = v; show(animate = false) }

    init {
        val size = c.dp(cardDp)
        addView(hours, LayoutParams(size, size))
        addView(minutes, LayoutParams(size, size).apply { marginStart = size / 11 })
        show(animate = false)
    }

    private fun show(animate: Boolean) {
        val cal = Calendar.getInstance(timeZone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault())
        hours.set("%02d".format(cal.get(Calendar.HOUR_OF_DAY)), animate)
        minutes.set("%02d".format(cal.get(Calendar.MINUTE)), animate)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.removeCallbacks(tick); tick.run() }
    override fun onDetachedFromWindow() { ticker.removeCallbacks(tick); super.onDetachedFromWindow() }
}

/** One card: two digits; set() with animate flips from the old number to the new one. */
internal class FlipCard(c: Context) : View(c) {
    private var now = ""
    private var before = ""
    private var progress = 1f // 0..1 through a flip
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8B8B8.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val fold = Paint().apply { color = Color.BLACK }
    private val rect = RectF()

    fun set(text: String, animate: Boolean) {
        if (text == now) return
        before = if (animate && now.isNotEmpty()) now else text
        now = text
        if (before == now) { progress = 1f; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        digits.textSize = h * 0.7f
        val baseline = mid - (digits.descent() + digits.ascent()) / 2
        rect.set(0f, 0f, w, h)
        fun half(text: String, top: Boolean, squash: Float = 1f) {
            canvas.save()
            if (top) canvas.clipRect(0f, 0f, w, mid) else canvas.clipRect(0f, mid, w, h)
            canvas.scale(1f, squash, w / 2, mid) // a leaf turning about the fold
            canvas.drawRoundRect(rect, h * 0.09f, h * 0.09f, card)
            canvas.drawText(text, w / 2, baseline, digits)
            canvas.restore()
        }
        // behind: the new top and, until the leaf has passed the middle, the old bottom
        half(now, top = true)
        half(if (progress < 0.5f) before else now, top = false)
        if (progress < 1f) {
            if (progress < 0.5f) half(before, top = true, squash = Math.cos(progress * Math.PI).toFloat())     // old top folding down
            else half(now, top = false, squash = -Math.cos(progress * Math.PI).toFloat())                       // new bottom landing
        }
        canvas.drawRect(0f, mid - h * 0.006f, w, mid + h * 0.006f, fold)
    }
}
