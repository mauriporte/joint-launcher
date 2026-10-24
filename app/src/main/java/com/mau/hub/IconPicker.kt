package com.mau.hub

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Pick an app's icon from any installed icon pack: a searchable grid of the pack's icons, a button per pack, and
 * "App's own icon" to undo. onDone runs after a choice so the screen can show it.
 */
class IconPicker(private val a: MainActivity, private val app: SideApp, private val onDone: () -> Unit) {
    private val packs = a.apps.packs
    private var pack: String? = null
    private var names: List<String> = emptyList()
    private var filter = ""
    private val cache = object : LruCache<String, Drawable>(300) {}

    fun show() {
        val pkg = app.pkg ?: return
        val installed = packs.installed()
        if (installed.isEmpty()) return a.toast("No icon packs installed")
        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val grid = RecyclerView(a).apply {
            setPadding(a.dp(12), 0, a.dp(12), a.dp(12)); clipToPadding = false
            layoutManager = GridLayoutManager(a, maxOf(3, (a.resources.displayMetrics.widthPixels - a.dp(24)) / a.dp(96)))
        }
        val count = a.text(13f, TEXT_MUTED)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            val shown get() = if (filter.isEmpty()) names else names.filter { filter in it }
            override fun getItemCount() = shown.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(10))
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)
                addView(ImageView(a), LinearLayout.LayoutParams(a.dp(56), a.dp(56)))
                addView(a.text(10f, TEXT_MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(a.dp(84), -2).apply { topMargin = a.dp(4) })
            }) {}

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val name = shown[position]; val p = pack ?: return
                val box = h.itemView as LinearLayout
                val img = box.getChildAt(0) as ImageView
                (box.getChildAt(1) as TextView).text = name.replace('_', ' ')
                img.tag = name
                cache.get("$p|$name")?.let { img.setImageDrawable(it) } ?: run {
                    img.setImageDrawable(null)
                    a.hub.imagePool.execute {
                        val d = packs.drawable(p, name) ?: return@execute
                        a.hub.main.post { cache.put("$p|$name", d); if (img.tag == name) img.setImageDrawable(d) }
                    }
                }
                box.setOnClickListener { packs.setOverride(pkg, p, name); done(dialog, pkg) }
            }
        }
        grid.adapter = adapter
        fun load(p: String) {
            pack = p; names = emptyList(); adapter.notifyDataSetChanged(); count.text = "Loading…"
            a.hub.pool.execute {
                val all = packs.allIcons(p)
                a.hub.main.post { if (pack == p) { names = all; adapter.notifyDataSetChanged(); count.text = "${all.size} icons" } }
            }
        }
        val search = EditText(a).apply {
            hint = "Search icons (e.g. ${app.label.lowercase().substringBefore(' ')})"; isSingleLine = true; textSize = 16f
            setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter = s.toString().trim().lowercase().replace(' ', '_'); adapter.notifyDataSetChanged() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            })
        }
        dialog.setContentView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(a.dp(16), a.dp(16), a.dp(16), 0)
            addView(a.text(24f, Color.WHITE).apply { text = "Icon for ${app.label}"; typeface = Typeface.create("sans-serif", Typeface.BOLD) })
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(12), 0, a.dp(8))
                installed.forEach { (p, label) -> addView(a.button(label) { load(p) }) }
                addView(a.button("App's own icon") { packs.setOverride(pkg, null, null); done(dialog, pkg) })
            })
            addView(search, LinearLayout.LayoutParams(-1, a.dp(52)))
            addView(count, LinearLayout.LayoutParams(-2, -2).apply { setMargins(a.dp(4), a.dp(4), 0, a.dp(8)) })
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dialog.show()
        load(packs.active()?.takeIf { p -> installed.any { it.first == p } } ?: installed.first().first)
    }

    private fun done(d: Dialog, pkg: String) {
        a.apps.forgetIcon(pkg)
        d.dismiss(); onDone()
    }
}
