package com.mau.hub

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Tile shapes; a row can mix them, and RecyclerView reuses tiles of the same shape. */
enum class CardKind { POSTER, WIDE, SQUARE }

fun kindOf(item: Item) = when { item.wide -> CardKind.WIDE; item.square -> CardKind.SQUARE; else -> CardKind.POSTER }

/** Builds tiles once (create) and fills them with a title (bind), so rows can recycle them as you scroll. */
class Cards(private val ctx: Context, private val images: Images, private val apps: Apps) {
    class Card(val root: FrameLayout, val img: ImageView, val caption: TextView, val dot: View)

    // drawable templates shared by every card; each card takes a cheap copy via newDrawable()
    private val radius = ctx.dp(10).toFloat()
    private val cardBg = GradientDrawable().apply { cornerRadius = radius; setColor(0xFF1C1D25.toInt()) }.constantState!!
    private val scrim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xD9000000.toInt())).constantState!!
    private val cardForeground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(0)) // Stremio-style: focus = slightly bigger + shadow, no frame or colour
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0x40000000)) // unfocused cards sit back
    }.constantState!!
    private val dots = HashMap<Int, Drawable.ConstantState>()
    private fun dot(color: Int): Drawable = dots.getOrPut(color) {
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(ctx.dp(1), 0x66000000) }.constantState!!
    }.newDrawable()

    fun size(kind: CardKind) = when (kind) {
        CardKind.WIDE -> ctx.dp(176) to ctx.dp(99)
        CardKind.SQUARE -> ctx.dp(118) to ctx.dp(118)
        CardKind.POSTER -> ctx.dp(92) to ctx.dp(138)
    }

    fun create(kind: CardKind): Card {
        val (w, h) = size(kind)
        val img = ImageView(ctx)
        val caption = ctx.text(11f, Color.WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dp(8), ctx.dp(14), ctx.dp(8), ctx.dp(5))
            background = scrim.newDrawable()
        }
        val dot = View(ctx)
        val root = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = ctx.dp(12) }
            addView(img, FrameLayout.LayoutParams(-1, -1))
            addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(dot, FrameLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { setMargins(ctx.dp(6), ctx.dp(6), 0, 0) })
            // dim overlay when unfocused (view-wide alpha forced an offscreen redraw per card)
            foreground = cardForeground.newDrawable(); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = cardBg.newDrawable()
            isFocusable = true; isClickable = true
        }
        return Card(root, img, caption, dot)
    }

    /** onFocus runs when the tile gains focus (hero update); the grow-on-focus look is built in. */
    fun bind(c: Card, item: Item, onFocus: (View) -> Unit, onClick: () -> Unit) {
        c.img.scaleType = if (item.icon != null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (item.icon != null) { c.img.tag = null; c.img.setImageDrawable(item.icon) }
        else item.poster?.let { images.load(it, c.img) } ?: run { c.img.tag = null; c.img.setImageDrawable(null) }
        // posters carry their own title art (the header shows the name on focus); wide/square cards and missing art get a caption
        val caption = (item.wide || item.square || item.poster == null) && item.source != Source.APP
        c.caption.visibility = if (caption) View.VISIBLE else View.GONE
        c.caption.text = item.title
        c.dot.visibility = if (item.source != Source.APP) View.VISIBLE else View.GONE
        if (item.source != Source.APP) c.dot.background = dot(item.source.color)
        c.root.contentDescription = item.title
        c.root.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start()
            v.elevation = if (f) ctx.dp(12).toFloat() else 0f
            if (f) onFocus(v)
        }
        c.root.setOnClickListener { onClick() }
    }

    /** Top 10 rows: big outlined rank number tucked behind the left edge of the card. */
    fun ranked(card: View): Pair<LinearLayout, TextView> {
        val number = ctx.text(84f, 0xFF7A7D86.toInt()).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = ctx.dp(2).toFloat()
            includeFontPadding = false; letterSpacing = -0.05f
        }
        val box = LinearLayout(ctx).apply {
            clipChildren = false; gravity = Gravity.BOTTOM
            addView(number, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = -ctx.dp(16); bottomMargin = -ctx.dp(6) })
            addView(card)
        }
        return box to number
    }

    /**
     * Google TV look for "Your apps" (76dp tiles ~15dp apart): rounded square app icon, name shown under the focused
     * one only. Its first child is the icon box, where move mode draws its arrows.
     */
    fun appTile(a: SideApp, onFocus: (View) -> Unit, onClick: () -> Unit, onLongClick: () -> Unit): LinearLayout {
        val size = ctx.dp(76)
        val icon = ImageView(ctx).apply {
            a.pkg?.let { apps.iconInto(it, this) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = ctx.dp(18).toFloat(); setColor(0xFF2A2B30.toInt()) }
        }
        val label = ctx.text(12f, Color.WHITE).apply {
            text = a.label; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
        }
        val box = FrameLayout(ctx).apply {
            clipChildren = false
            addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            tag = a.key; contentDescription = a.label
            isFocusable = true; isClickable = true
            addView(box, LinearLayout.LayoutParams(size + ctx.dp(6), size + ctx.dp(6)))
            addView(label, LinearLayout.LayoutParams(size + ctx.dp(30), -2).apply { topMargin = ctx.dp(4) })
            setOnFocusChangeListener { v, f ->
                box.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start() // same focus look as cards
                label.visibility = if (f) View.VISIBLE else View.INVISIBLE
                if (f) onFocus(v)
            }
            setOnClickListener { onClick() }
            setOnLongClickListener { onLongClick(); true }
            layoutParams = LinearLayout.LayoutParams(size + ctx.dp(30), -2)
        }
    }
}
