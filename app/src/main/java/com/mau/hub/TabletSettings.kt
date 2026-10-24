package com.mau.hub

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import java.util.TimeZone

/**
 * Tablet Settings: everything Joint Launcher lets you change, on a panel down the right side so Home stays in view and
 * shows each change as you make it. The TV keeps its short dialog (Actions.settings).
 */
class TabletSettings(private val a: MainActivity) {
    private val hub get() = a.hub
    private val p get() = hub.prefs
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), 0, a.dp(24), a.dp(24)) }
    // made once so a redraw keeps what you've typed
    private val url = field("Jellyfin URL", hub.server)
    private val key = field("Jellyfin API key (Dashboard → API Keys)", hub.jfKey)

    fun show() {
        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        draw()
        dialog.setContentView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(24), a.dp(20), a.dp(16), a.dp(8))
                addView(a.text(24f, Color.WHITE).apply { text = "Settings"; typeface = Typeface.create("sans-serif", Typeface.BOLD) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.button("Done") { dialog.dismiss() })
            })
            addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dialog.window?.apply {
            setLayout(minOf(a.resources.displayMetrics.widthPixels * 11 / 20, a.dp(760)), -1)
            setGravity(Gravity.END)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setOnDismissListener { saveJellyfin() }
        dialog.show()
    }

    private fun draw() {
        list.removeAllViews()

        heading("Accounts")
        list.addView(url); list.addView(key)
        // Jellyseerr, Stremio, Spotify and YouTube: signed in from your phone (QR code), nothing to type here
        buttons("Connect accounts" to { ConnectScreen(a).show() })

        heading("Background")
        list.addView(android.widget.Switch(a).apply {
            text = "Show my wallpaper"; textSize = 16f; setTextColor(Color.WHITE)
            isChecked = p.getBoolean("bg_wallpaper", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("bg_wallpaper", on).apply(); a.applyBackground() }
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        val dimLabel = a.text(15f, TEXT_SOFT)
        fun dimText(v: Int) { dimLabel.text = "Transparency: darken $v %" + when (v) { 0 -> " (wallpaper as it is)"; 100 -> " (black)"; else -> "" } }
        list.addView(dimLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
        list.addView(SeekBar(a).apply {
            max = 100; progress = p.getInt("bg_dim", 60); dimText(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { dimText(v); p.edit().putInt("bg_dim", v).apply(); a.applyBackground() }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        buttons("Change wallpaper" to {
            runCatching { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose a wallpaper")) }
                .onFailure { a.toast("Couldn't open the wallpaper picker") }
        })

        heading("Apps")
        val packs = a.apps.packs.installed()
        val pack = a.apps.packs.active()
        line("Icon pack", packs.firstOrNull { it.first == pack }?.second ?: "The apps' own icons") { iconPack(packs, pack) }
        line("Favorite apps", "${a.apps.favourites().size} chosen") { favouriteApps() }

        heading("Home")
        list.addView(a.text(15f, TEXT_SOFT).apply { text = "Order of the sections" })
        val order = sections(hub)
        order.forEachIndexed { i, s ->
            list.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(4), 0, a.dp(4))
                addView(a.text(17f, Color.WHITE).apply { text = SECTIONS.getValue(s) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.button("↑") { moveSection(order, i, RowOrder.UP) }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.3f })
                addView(a.button("↓") { moveSection(order, i, RowOrder.DOWN) }.apply {
                    isEnabled = i < order.size - 1; alpha = if (i < order.size - 1) 1f else 0.3f
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
            })
        }
        line("To Watch", "Order and show/hide its rows") { a.chooseRows() }

        heading("Clock")
        val format = p.getString("clock_format", null)
        line("Time format", when (format) { "24" -> "24-hour (17:30)"; "12" -> "12-hour (05:30)"; else -> "Like the tablet" }) { timeFormat(format) }
        val tz = p.getString("clock_tz", null)
        line("Timezone", tz?.replace('_', ' ') ?: "Like the tablet (${TimeZone.getDefault().id.replace('_', ' ')})") { timezone(tz) }

        heading("Weather")
        line("Location", p.getString("weather_place", null) ?: "Automatic (from your connection)") { weatherPlace() }

        list.addView(a.text(13f, TEXT_MUTED).apply { text = a.actions.storageLine(); setPadding(0, a.dp(24), 0, 0) })
    }

    // ---- pieces ----

    private fun field(hint: String, value: String) = EditText(a).apply {
        this.hint = hint; setText(value); isSingleLine = true; textSize = 16f; setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED)
    }

    private fun heading(t: String) = list.addView(a.text(19f, Color.WHITE).apply {
        text = t; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(28); bottomMargin = a.dp(8) })

    private fun buttons(vararg b: Pair<String, () -> Unit>) = list.addView(LinearLayout(a).apply {
        b.forEach { (label, go) -> addView(a.button(label) { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(8) }) }
    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })

    /** "Label / current value" on the left, "Change" on the right; the whole line is the button. */
    private fun line(label: String, value: String, change: () -> Unit) = list.addView(LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(6), 0, a.dp(6))
        isClickable = true; setOnClickListener { change() }
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.text(17f, Color.WHITE).apply { text = label })
            addView(a.text(14f, TEXT_MUTED).apply { text = value })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(a.button("Change") { change() })
    })

    // ---- choices ----

    private fun iconPack(packs: List<Pair<String, String>>, current: String?) {
        val names = (listOf("The apps' own icons") + packs.map { it.second }).toTypedArray()
        AlertDialog.Builder(a).setTitle("Icon pack")
            .setSingleChoiceItems(names, packs.indexOfFirst { it.first == current } + 1) { d, i ->
                p.edit().putString("icon_pack", if (i == 0) IconPacks.NONE else packs[i - 1].first).apply()
                a.apps.forgetIcons(); a.loadHome(); d.dismiss(); draw()
            }.show()
    }

    private fun favouriteApps() = hub.diskPool.execute {
        val apps = a.apps.all()
        hub.main.post {
            val favs = a.apps.favourites()
            AlertDialog.Builder(a).setTitle("Favorite apps")
                .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), apps.map { it.key in favs }.toBooleanArray()) { _, i, on ->
                    a.apps.setFavourite(apps[i].key, on)
                }
                .setPositiveButton("Done", null).setOnDismissListener { a.refreshApps(); draw() }.show()
        }
    }

    private fun moveSection(order: List<String>, i: Int, how: Int) {
        p.edit().putString("home_sections", RowOrder.move(order, i, how).joinToString(",")).apply()
        a.loadHome(); draw()
    }

    private fun timeFormat(current: String?) {
        val values = listOf(null, "24", "12")
        AlertDialog.Builder(a).setTitle("Time format")
            .setSingleChoiceItems(arrayOf("Like the tablet", "24-hour (17:30)", "12-hour (05:30)"), values.indexOf(current)) { d, i ->
                p.edit().putString("clock_format", values[i]).apply(); a.topBar.applyClock(); d.dismiss(); draw()
            }.show()
    }

    private fun timezone(current: String?) {
        // region/city zones only (skips the Etc/GMT+3 style aliases), alphabetical
        val zones = TimeZone.getAvailableIDs().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted()
        val values = listOf(null) + zones
        AlertDialog.Builder(a).setTitle("Timezone")
            .setSingleChoiceItems((listOf("Like the tablet") + zones.map { it.replace('_', ' ') }).toTypedArray(), values.indexOf(current)) { d, i ->
                p.edit().putString("clock_tz", values[i]).apply(); a.topBar.applyClock(); d.dismiss(); draw()
            }.show()
            .listView.setSelection(maxOf(0, values.indexOf(current) - 3))
    }

    private fun weatherPlace() {
        val input = EditText(a).apply { hint = "Town or city, e.g. Valencia"; isSingleLine = true; setText(p.getString("weather_place", "")) }
        AlertDialog.Builder(a).setTitle("Weather location")
            .setView(LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Use this place") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                hub.pool.execute {
                    val found = runCatching { Sources.geocode(q) }
                    hub.main.post {
                        found.onSuccess { r ->
                            if (r == null) return@onSuccess a.toast("Couldn't find “$q”")
                            p.edit().putString("weather_place", r.first).putString("lat", r.second.toString()).putString("lon", r.third.toString()).apply()
                            a.topBar.refreshWeather(); draw()
                        }.onFailure { a.toast("Couldn't look up “$q”: ${it.message}") }
                    }
                }
            }
            // forget the place: the corner finds you from your connection again
            .setNeutralButton("Automatic") { _, _ ->
                p.edit().remove("weather_place").remove("lat").remove("lon").apply(); a.topBar.refreshWeather(); draw()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveJellyfin() {
        val u = url.text.toString().trim(); val k = key.text.toString().trim()
        if (u == hub.server && k == hub.jfKey) return
        if (k != hub.jfKey) p.edit().remove("uid").apply() // manual key: look the user up again
        p.edit().putString("server", u).putString("key", k).apply()
        hub.userId = null
        a.loadHome()
    }

    companion object {
        val SECTIONS = linkedMapOf("widgets" to "Widgets", "apps" to "Apps", "ha" to "Home Assistant", "watch" to "To Watch")

        /** Tablet Home's sections in your order (new ones join at the end). */
        fun sections(hub: Hub) = RowOrder.apply(SECTIONS.keys.toList(), hub.prefs.getString("home_sections", "")!!.split(",").filter(String::isNotEmpty))
    }
}
