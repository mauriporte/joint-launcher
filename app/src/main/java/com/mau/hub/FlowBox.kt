package com.mau.hub

import android.content.Context
import android.view.ViewGroup

/**
 * Children left to right, wrapping onto new lines: the tablet's app grid, which reflows by itself when the tablet turns.
 * The grid is centred: every line starts at the same offset (from the widest line), so a short last line stays aligned.
 */
class FlowBox(c: Context) : ViewGroup(c) {
    private var offset = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val limit = width - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0; var widest = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i).takeIf { it.visibility != GONE } ?: continue
            measureChild(ch, widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > limit) { x = 0; y += line; line = 0 }
            x += ch.measuredWidth; line = maxOf(line, ch.measuredHeight); widest = maxOf(widest, x)
        }
        offset = maxOf(0, (limit - widest) / 2)
        setMeasuredDimension(width, y + line + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val limit = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i).takeIf { it.visibility != GONE } ?: continue
            if (x > 0 && x + ch.measuredWidth > limit) { x = 0; y += line; line = 0 }
            val left = paddingLeft + offset + x
            ch.layout(left, paddingTop + y, left + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth; line = maxOf(line, ch.measuredHeight)
        }
    }
}
