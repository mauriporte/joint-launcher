package com.mau.hub

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * MauHome's tile editor (the widget's pencil, or Settings): add a tile by picking any Home Assistant device from a
 * searchable list, rename it, change its icon and what a tap does, ask before turning it off, reorder, remove, or go
 * back to the original tiles. Every change is saved at once and the widget redraws.
 */
class HaTiles : Activity() {
    private lateinit var list: LinearLayout
    private var tiles = mutableListOf<HaTile>()
    @Volatile private var entities: List<JSONObject>? = null // HA's devices, read once when the editor opens

    private val kinds = linkedMapOf(
        HaTile.Kind.SWITCH to "On / off", HaTile.Kind.LIGHT to "Light", HaTile.Kind.TEMP to "Temperature",
        HaTile.Kind.IR to "Remote button", HaTile.Kind.TV to "TV (on / off)", HaTile.Kind.SPEAKER to "Speaker (play / pause)",
        HaTile.Kind.CAMERA to "Camera", HaTile.Kind.RUN to "Scene / script",
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tiles = HaWidget.tiles(this).toMutableList()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(list) })
        Thread {
            entities = runCatching {
                val a = JSONArray(Sources.get("${HaWidget.base(this)}/api/states", HaWidget.auth(this)))
                (0 until a.length()).map(a::getJSONObject).sortedBy { name(it).lowercase() }
            }.getOrNull()
        }.start()
        draw()
    }

    /** Lower case without accents: "lampara" finds "Lámpara". */
    private fun plain(s: String) = java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

    private fun name(o: JSONObject) = o.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifEmpty { o.optString("entity_id") }

    // the status and navigation bars stay hidden here as on Home (the owner: hidden in all his apps); a swipe shows them for a moment
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun commit() { HaWidget.save(this, tiles); draw() }

    private fun draw() {
        list.removeAllViews()
        list.addView(text(24f, Color.WHITE).apply { text = "MauHome tiles"; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        list.addView(text(14f, TEXT_MUTED).apply {
            text = "In the widget's order, ${tiles.size} tiles. Changes show in the widget right away. Resize the widget for more or fewer per row."
            setPadding(0, dp(4), 0, dp(12))
        })
        list.addView(LinearLayout(this).apply {
            addView(button("+  Add a tile") { edit(null) })
            addView(View(this@HaTiles), LinearLayout.LayoutParams(dp(12), 1))
            addView(button("Back to the original tiles") {
                AlertDialog.Builder(this@HaTiles).setMessage("Replace your tiles with the original ${HaWidget.defaults(this@HaTiles).size}?")
                    .setPositiveButton("Replace") { _, _ -> HaWidget.save(this@HaTiles, null); tiles = HaWidget.defaults(this@HaTiles).toMutableList(); draw() }
                    .setNegativeButton("Cancel", null).show()
            })
        })
        tiles.forEachIndexed { i, t -> list.addView(row(i, t)) }
    }

    private fun row(i: Int, t: HaTile) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(ImageView(this@HaTiles).apply { setImageResource(t.icon); setColorFilter(ACCENT) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(LinearLayout(this@HaTiles).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(text(17f, Color.WHITE).apply { text = t.name + if (t.guard != null) "  🔒" else "" })
            addView(text(13f, TEXT_MUTED).apply {
                text = "${kinds[t.kind]} · " + (t.entities.firstOrNull() ?: "not set") + if (t.entities.size > 1) " +${t.entities.size - 1}" else ""
            })
            setOnClickListener { edit(i) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun small(label: String, on: () -> Unit) = addView(text(20f, TEXT_SOFT).apply {
            text = label; gravity = Gravity.CENTER; background = bareButton(); setOnClickListener { on() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        small("▲") { if (i > 0) { tiles.add(i - 1, tiles.removeAt(i)); commit() } }
        small("▼") { if (i < tiles.size - 1) { tiles.add(i + 1, tiles.removeAt(i)); commit() } }
        small("✎") { edit(i) }
        small("✕") {
            AlertDialog.Builder(this@HaTiles).setMessage("Remove ${t.name}?")
                .setPositiveButton("Remove") { _, _ -> tiles.removeAt(i); commit() }.setNegativeButton("Cancel", null).show()
        }
    }

    /** Adds (index null) or changes a tile. */
    private fun edit(index: Int?) {
        val old = index?.let { tiles[it] }
        var entity = old?.entities?.firstOrNull()
        var kind = old?.kind ?: HaTile.Kind.SWITCH
        var icon = old?.icon ?: R.drawable.ic_ha_plug
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val nameField = EditText(this).apply { hint = "Name on the tile"; setText(old?.name.orEmpty()); isSingleLine = true }
        val device = text(16f, Color.WHITE); val type = text(16f, Color.WHITE)
        val iconView = ImageView(this).apply { setColorFilter(ACCENT) }
        val ask = android.widget.Switch(this).apply { text = "Ask before turning it off"; textSize = 16f; isChecked = old?.guard != null }
        fun show() {
            device.text = "Device: " + (entity?.let { e -> entities?.firstOrNull { it.optString("entity_id") == e }?.let { "${name(it)}  ($e)" } ?: e } ?: "choose…")
            type.text = "Tap does: ${kinds[kind]}"
            iconView.setImageResource(icon)
        }
        show()
        box.addView(nameField)
        box.addView(device.apply { setPadding(0, dp(14), 0, dp(14)); setOnClickListener {
            pickEntity { o ->
                entity = o.optString("entity_id")
                HaWidget.guess(entity!!, o.optJSONObject("attributes")).let { (k, ic) -> kind = k; icon = ic }
                if (nameField.text.isBlank()) nameField.setText(name(o))
                show()
            }
        } })
        box.addView(type.apply { setPadding(0, dp(14), 0, dp(14)); setOnClickListener {
            AlertDialog.Builder(this@HaTiles).setTitle("Tap does").setItems(kinds.values.toTypedArray()) { _, w -> kind = kinds.keys.elementAt(w); show() }.show()
        } })
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(16f, Color.WHITE).apply { text = "Icon" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconView, LinearLayout.LayoutParams(dp(32), dp(32)))
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { pickIcon { icon = it; show() } }
        })
        box.addView(ask, LinearLayout.LayoutParams(-1, dp(52)))
        AlertDialog.Builder(this).setTitle(if (old == null) "New tile" else "Edit tile").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val e = entity ?: return@setPositiveButton toast("Choose the device first")
                val n = nameField.text.toString().trim().ifEmpty { e }
                // keep a strip's other outlets, and the existing warning text, when only the first changes
                val all = listOf(e) + old?.entities?.drop(1).orEmpty().takeIf { old?.entities?.firstOrNull() == e }.orEmpty()
                val guard = if (!ask.isChecked) null else old?.guard ?: "Turn off $n?"
                val t = HaTile(n, icon, kind, all, guard)
                if (index == null) tiles.add(t) else tiles[index] = t
                commit()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** HA's devices, searchable by name or id. */
    private fun pickEntity(chosen: (JSONObject) -> Unit) {
        val all = entities ?: return toast("Still reading Home Assistant's devices… try again in a moment (and check the token on the Connect page)")
        val search = EditText(this).apply { hint = "Search (name or id)"; isSingleLine = true }
        var shown = all
        val listView = ListView(this).apply { this.adapter = object : ArrayAdapter<String>(this@HaTiles, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = name(shown[p])
            override fun getView(p: Int, v: View?, parent: android.view.ViewGroup): View = super.getView(p, v, parent).also {
                it.findViewById<TextView>(android.R.id.text1).text = name(shown[p])
                it.findViewById<TextView>(android.R.id.text2).text = shown[p].optString("entity_id") + "  ·  " + shown[p].optString("state")
            }
        } }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val q = plain(s.toString())
                shown = all.filter { q.isEmpty() || q in plain(name(it)) || q in it.optString("entity_id") }
                (listView.adapter as ArrayAdapter<*>).notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(search); addView(listView, LinearLayout.LayoutParams(-1, dp(420)))
        }
        val d = AlertDialog.Builder(this).setTitle("Choose a device").setView(box).setNegativeButton("Cancel", null).show()
        listView.setOnItemClickListener { _, _, p, _ -> chosen(shown[p]); d.dismiss() }
    }

    private fun pickIcon(chosen: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = 5; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        lateinit var d: AlertDialog
        HaWidget.ICONS.values.forEach { res ->
            grid.addView(ImageView(this).apply {
                setImageResource(res); setColorFilter(ACCENT); setPadding(dp(12), dp(12), dp(12), dp(12)); background = bareButton()
                setOnClickListener { chosen(res); d.dismiss() }
            }, GridLayout.LayoutParams().apply { width = dp(56); height = dp(56) })
        }
        d = AlertDialog.Builder(this).setTitle("Icon").setView(grid).setNegativeButton("Cancel", null).show()
    }
}
