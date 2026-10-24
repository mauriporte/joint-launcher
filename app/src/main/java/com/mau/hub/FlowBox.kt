package com.mau.hub

import android.content.Context
import android.view.ViewGroup

/**
 * Children left to right, wrapping onto new lines: the tablet's app grid, which reflows by itself when the tablet turns.
 * Columns are spread across the full width (first one at the left edge, last one at the right), so the grid lines up
 * with the section headers above it; a short last line keeps the same columns.
 */
class FlowBox(c: Context) : ViewGroup(c) {
    private var gap = 0
    private var cols = 1

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val limit = width - paddingLeft - paddingRight
        val kids = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        kids.forEach { measureChild(it, widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
        // app tiles are all the same width: as many columns as fit, the spare width shared out between them
        val w = kids.maxOfOrNull { it.measuredWidth } ?: 0
        cols = if (w == 0) 1 else maxOf(1, limit / w)
        gap = if (cols > 1 && kids.size >= cols) (limit - cols * w) / (cols - 1) else 0
        var y = 0
        kids.chunked(cols).forEach { line -> y += line.maxOf { it.measuredHeight } }
        setMeasuredDimension(width, y + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val kids = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        var y = paddingTop
        kids.chunked(cols).forEach { line ->
            var x = paddingLeft
            line.forEach { ch -> ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight); x += ch.measuredWidth + gap }
            y += line.maxOf { it.measuredHeight }
        }
    }
}
