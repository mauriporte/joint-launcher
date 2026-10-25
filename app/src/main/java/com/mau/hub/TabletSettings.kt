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
    private val device get() = if (hub.touch) "tablet" else "TV" // "Like the tablet" / "Like the TV"
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
        a.onBackgroundPicked = { draw() }
        dialog.setOnDismissListener { saveJellyfin(); a.onBackgroundPicked = null }
        dialog.show()
    }

    private fun draw() {
        list.removeAllViews()

        heading("Accounts")
        list.addView(url); list.addView(key)
        // Jellyseerr, Stremio, Spotify and YouTube: signed in from your phone (QR code), nothing to type here
        buttons("Connect accounts" to { ConnectScreen(a).show() })

        heading("Background")
        val modes = linkedMapOf("black" to "Black", "wallpaper" to "The $device's wallpaper", "picture" to "My pictures (landscape and portrait)")
        line("Background", modes.getValue(a.bgMode())) {
            AlertDialog.Builder(a).setTitle("Background")
                .setSingleChoiceItems(modes.values.toTypedArray(), modes.keys.indexOf(a.bgMode())) { d, i ->
                    p.edit().putString("bg_mode", modes.keys.elementAt(i)).apply(); a.applyBackground(); d.dismiss(); draw()
                }.show()
        }
        if (a.bgMode() == "picture") gallery()
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
        val sideLabel = a.text(15f, TEXT_SOFT)
        fun sideText(v: Int) { sideLabel.text = "Sidebar: $v % solid" + if (v == 100) " (no see-through)" else "" }
        list.addView(sideLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
        list.addView(SeekBar(a).apply {
            max = 80; progress = p.getInt("sidebar_opacity", 100) - 20; sideText(progress + 20) // 20-100 %: below that the icons get lost
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { sideText(v + 20); p.edit().putInt("sidebar_opacity", v + 20).apply() }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        if (a.bgMode() == "wallpaper") buttons("Change wallpaper" to {
            runCatching { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose a wallpaper")) }
                .onFailure { a.toast("Couldn't open the wallpaper picker") }
        })

        heading("Apps")
        val packs = a.apps.packs.installed()
        val pack = a.apps.packs.active()
        line("Icon pack", packs.firstOrNull { it.first == pack }?.second ?: "The apps' own icons") { iconPack(packs, pack) }
        line("Favorite apps", "${a.apps.favourites().size} chosen") { favouriteApps() }
        line("Hidden apps", a.apps.hidden().size.let { if (it == 0) "None: hold an app and choose Hide" else "$it hidden" }) { hiddenApps() }
        if (!hub.touch) line("App lock", a.appLock.locked().size.let { n ->
            (if (a.appLock.hasPin()) "PIN set" else "No PIN yet") + " · " + if (n == 0) "no apps locked: hold an app and choose Lock app" else "$n locked"
        }) { a.appLock.changePin { a.toast("PIN saved"); draw() } }

        heading("Home")
        list.addView(android.widget.Switch(a).apply {
            text = "Show section titles (off: their icons instead)"; textSize = 16f; setTextColor(Color.WHITE)
            isChecked = p.getBoolean("section_titles", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("section_titles", on).apply(); a.topBar.applyTitlesButton(); a.loadHome() }
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        list.addView(a.text(15f, TEXT_SOFT).apply { text = "Sections: tap the icon to change it, the name to rename it, arrows to reorder" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
        val order = sections(hub)
        order.forEachIndexed { i, s ->
            list.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(4), 0, a.dp(4))
                addView(android.widget.ImageView(a).apply {
                    setImageDrawable(icon(a, s)); setColorFilter(Color.WHITE); contentDescription = "Change the icon of ${name(hub, s)}"
                    setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
                    background = a.bareButton()
                    setOnClickListener { pickIcon(s) }
                    if (!hub.touch) tvFocus(22)
                }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(12) })
                addView(a.text(17f, Color.WHITE).apply {
                    text = name(hub, s) + "  ✎"; setPadding(0, a.dp(10), 0, a.dp(10)); isClickable = true; setOnClickListener { rename(s) }
                    if (!hub.touch) tvFocus(8)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.button("↑") { moveSection(order, i, RowOrder.UP) }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.3f })
                addView(a.button("↓") { moveSection(order, i, RowOrder.DOWN) }.apply {
                    isEnabled = i < order.size - 1; alpha = if (i < order.size - 1) 1f else 0.3f
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
            })
        }
        // the Apps section's other name, shown while its heart is on
        line("Favorites section name", name(hub, "favorites")) { rename("favorites") }
        line(name(hub, "watch"), "Order and show/hide its rows") { a.chooseRows() }

        if (hub.touch) {
        heading("Gestures")
        listOf(Gestures.SWIPE_RIGHT, Gestures.SWIPE_LEFT, Gestures.DOUBLE_TAP).forEach { k ->
            line(Gestures.LABELS.getValue(k), a.gestures.describe(k)) { a.gestures.choose(k) { draw() } }
        }
        heading("Screen")
        fun barSwitch(label: String, key: String) = list.addView(android.widget.Switch(a).apply {
            text = label; textSize = 16f; setTextColor(Color.WHITE); isChecked = p.getBoolean(key, true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); a.applySystemBars() }
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        barSwitch("Hide the status bar (clock, Wi-Fi, battery)", "hide_status_bar")
        barSwitch("Hide the navigation bar (back, home, recents)", "hide_nav_bar")
        list.addView(a.text(14f, TEXT_MUTED).apply { text = "Hidden bars come back for a moment with a swipe in from the edge" })
        }

        heading("Backup")
        list.addView(a.text(14f, TEXT_MUTED).apply { text = "Your layout, icons, names, favorites, hidden apps and settings in one file. Not included: sign-ins, placed widgets, background pictures." })
        buttons(
            "Back up to a file" to { a.saveFile("joint-launcher-backup.json", backup(p.all)) },
            "Restore from a file" to {
                a.openFile { text ->
                    runCatching { restore(text) }.onSuccess { values ->
                        p.edit().apply {
                            values.forEach { (k, v) ->
                                @Suppress("UNCHECKED_CAST")
                                when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v)
                                    is String -> putString(k, v); is Set<*> -> putStringSet(k, v as Set<String>) }
                            }
                        }.apply()
                        a.apps.forgetIcons(); a.applyBackground(); a.loadHome(); draw(); a.toast("Restored ${values.size} settings")
                    }.onFailure { a.toast("Couldn't restore: ${it.message}") }
                }
            },
        )

        heading("Clock")
        val cornerSet = p.getInt("corner_widget", -1) >= 0
        if (hub.touch) line("Corner clock", if (cornerSet) "A widget (tap Change to pick another or go back)" else "Built-in") {
            val opts = arrayOf("Built-in clock", "Pick a widget (e.g. Flip Clock)")
            AlertDialog.Builder(a).setTitle("Corner clock").setItems(opts) { _, i -> if (i == 0) a.widgets.clearCorner() else a.widgets.pick(forCorner = true); draw() }.show()
        }
        val tz = p.getString("clock_tz", null)
        line("Timezone", tz?.replace('_', ' ') ?: "Like the $device (${TimeZone.getDefault().id.replace('_', ' ')})") { timezone(tz) }

        heading("Weather")
        line("Location", p.getString("weather_place", null) ?: "Automatic (from your connection)") { weatherPlace() }

        heading("Screensaver")
        val savers = linkedMapOf<String?, String>(null to "Android's own", ClockDream::class.java.name to "Flip clock on your background",
            PhotoDream::class.java.name to "Your Immich photos")
        val saver = p.getString("screensaver", null)
        line("Screensaver", savers[saver] ?: savers.getValue(null)) { screensaver(savers, saver) }
        if (saver == PhotoDream::class.java.name) {
            val connected = p.contains("immich_key")
            line("Photos", if (!connected) "Connect Immich first (Connect accounts → phone page)" else p.getString("immich_album_name", null) ?: "All photos") {
                if (connected) immichAlbum() else ConnectScreen(a).show()
            }
        }

        list.addView(a.text(13f, TEXT_MUTED).apply { text = a.actions.storageLine(); setPadding(0, a.dp(24), 0, 0) })
    }

    /** Your pictures side by side: tap one to use it, hold it for its portrait version or to remove it; + adds one. */
    private fun gallery() {
        val current = a.currentBackground()
        list.addView(a.text(14f, TEXT_MUTED).apply { text = "Tap a picture to use it · hold it for its portrait version or to remove it" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(4) })
        list.addView(android.widget.HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(a).apply {
                setPadding(0, a.dp(8), 0, a.dp(8))
                a.backgrounds().forEach { id ->
                    addView(android.widget.ImageView(a).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(android.graphics.BitmapFactory.decodeFile(a.bgFile(id, false).path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }))
                        setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = a.dp(10).toFloat(); setColor(Color.TRANSPARENT); if (id == current) setStroke(a.dp(3), ACCENT)
                        }
                        contentDescription = if (id == current) "Picture in use" else "Use this picture"
                        setOnClickListener { a.useBackground(id); draw() }
                        if (!hub.touch) tvFocus(10)
                        setOnLongClickListener {
                            val portrait = if (a.bgFile(id, true).exists()) "Change portrait version" else "Add portrait version"
                            AlertDialog.Builder(a).setItems(arrayOf(portrait, "Remove")) { _, w ->
                                if (w == 0) a.pickBackground(id, true) else { a.removeBackground(id); draw() }
                            }.show(); true
                        }
                    }, LinearLayout.LayoutParams(a.dp(176), a.dp(110)).apply { marginEnd = a.dp(10) })
                }
                addView(a.button("+ Add") { a.pickBackground(null, false) }, LinearLayout.LayoutParams(-2, a.dp(110)))
            })
        })
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
        // TV: the Change button takes focus, not the whole line
        isClickable = hub.touch; if (hub.touch) setOnClickListener { change() }
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

    /** Every app with a tick on the hidden ones: untick to bring one back to the Apps section, tick to hide it. */
    private fun hiddenApps() = hub.diskPool.execute {
        val apps = a.apps.all().sortedWith(compareBy(java.text.Collator.getInstance()) { it.label })
        hub.main.post {
            val hidden = a.apps.hidden()
            AlertDialog.Builder(a).setTitle("Hidden apps (ticked)")
                .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), apps.map { it.key in hidden }.toBooleanArray()) { _, i, on ->
                    a.apps.setHidden(apps[i].key, on)
                }
                .setPositiveButton("Done", null).setOnDismissListener { a.refreshApps(); draw() }.show()
        }
    }

    private fun moveSection(order: List<String>, i: Int, how: Int) {
        p.edit().putString("home_sections", RowOrder.move(order, i, how).joinToString(",")).apply()
        a.loadHome(); draw()
    }

    /** Any icon from any installed pack for a Home section; "Default icon" goes back to the selected pack's. */
    private fun pickIcon(key: String) {
        if (a.apps.packs.installed().isEmpty()) return a.toast("No icon packs installed")
        IconPicker(a, "Icon for ${name(hub, key)}", PACK_ICONS.getValue(key).substringBefore('_'), "Default icon", false) { pack, icon, _ ->
            p.edit().apply { if (pack == null || icon == null) remove("section_icon_$key") else putString("section_icon_$key", "$pack|$icon") }.apply()
            a.loadHome(); draw()
        }.show()
    }

    /** Your own name for a Home section; empty goes back to the default. */
    private fun rename(key: String) {
        val input = EditText(a).apply { hint = DEFAULT_NAMES.getValue(key); isSingleLine = true; setText(p.getString("section_name_$key", "")) }
        AlertDialog.Builder(a).setTitle("Rename “${name(hub, key)}”")
            .setView(LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                p.edit().apply { if (n.isEmpty()) remove("section_name_$key") else putString("section_name_$key", n) }.apply()
                a.loadHome(); draw()
            }
            .setNeutralButton("Default") { _, _ -> p.edit().remove("section_name_$key").apply(); a.loadHome(); draw() }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Makes one of ours (or Android's own again) the screensaver. Writing the system's screensaver setting needs the
     * WRITE_SECURE_SETTINGS permission (granted once over adb, as for the Home button); without it, Android's
     * screensaver settings open so you can pick it there.
     */
    private fun screensaver(savers: Map<String?, String>, current: String?) {
        val keys = savers.keys.toList()
        AlertDialog.Builder(a).setTitle("Screensaver")
            .setSingleChoiceItems(savers.values.toTypedArray(), keys.indexOf(current)) { d, i ->
                d.dismiss()
                val pick = keys[i]
                val cr = a.contentResolver
                val permitted = a.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!permitted) {
                    runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }
                    return@setSingleChoiceItems
                }
                // remember Android's own choice once, to put it back when you pick "Android's own" again
                if (!p.contains("screensaver_android")) p.edit().putString("screensaver_android",
                    android.provider.Settings.Secure.getString(cr, "screensaver_components").orEmpty()).apply()
                val component = pick?.let { android.content.ComponentName(a, it).flattenToString() } ?: p.getString("screensaver_android", "")
                runCatching {
                    android.provider.Settings.Secure.putString(cr, "screensaver_components", component)
                    android.provider.Settings.Secure.putInt(cr, "screensaver_enabled", 1)
                }.onFailure { a.toast("Couldn't set the screensaver: ${it.message}") }
                p.edit().apply { if (pick == null) remove("screensaver") else putString("screensaver", pick) }.apply()
                draw()
            }.show()
    }

    /** All photos (random) or one of your Immich albums. */
    private fun immichAlbum() = hub.pool.execute {
        val albums = runCatching { Immich.albums(p.getString("immich_url", "")!!, p.getString("immich_key", "")!!) }
        hub.main.post {
            albums.onSuccess { list ->
                val names = listOf("All photos (random)") + list.map { it.second }
                AlertDialog.Builder(a).setTitle("Photos to show").setItems(names.toTypedArray()) { _, i ->
                    p.edit().apply {
                        if (i == 0) { remove("immich_album"); remove("immich_album_name") }
                        else { putString("immich_album", list[i - 1].first); putString("immich_album_name", list[i - 1].second.substringBefore(" · ")) }
                    }.apply()
                    draw()
                }.show()
            }.onFailure { a.toast("Couldn't reach Immich: ${Sources.explain(it)}") }
        }
    }

    private fun timezone(current: String?) {
        // region/city zones only (skips the Etc/GMT+3 style aliases), alphabetical
        val zones = TimeZone.getAvailableIDs().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted()
        val values = listOf(null) + zones
        AlertDialog.Builder(a).setTitle("Timezone")
            .setSingleChoiceItems((listOf("Like the $device") + zones.map { it.replace('_', ' ') }).toTypedArray(), values.indexOf(current)) { d, i ->
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
        // backups leave out sign-ins (secrets don't belong in a file on the tablet's storage) and what only means
        // something on this install (placed widgets, picture files, the widget being added)
        private val NOT_BACKED_UP = setOf("key", "uid", "stremio", "seerr_key", "immich_key", "screensaver_android", AppLock.PIN, "spotify_refresh", "google_client_secret",
            "google_refresh", "widgets", "widget_pending", "bg_current")
        private fun backedUp(k: String) = k !in NOT_BACKED_UP && !k.startsWith("widget_size_") && !k.startsWith("wspan_") &&
            !k.startsWith("wpos_") && !k.startsWith("logo_")

        /** Settings → Back up: every layout setting as JSON, each value tagged with its type so restore puts it back as it was. */
        fun backup(all: Map<String, *>): String = org.json.JSONObject().apply {
            put("joint_launcher_backup", 1)
            all.filterKeys(::backedUp).forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, org.json.JSONObject().put("b", v))
                    is Int -> put(k, org.json.JSONObject().put("i", v))
                    is Long -> put(k, org.json.JSONObject().put("l", v))
                    is Float -> put(k, org.json.JSONObject().put("f", v.toDouble()))
                    is String -> put(k, org.json.JSONObject().put("s", v))
                    is Set<*> -> put(k, org.json.JSONObject().put("set", org.json.JSONArray(v.map { it.toString() })))
                }
            }
        }.toString(2)

        /** A backup file back into settings values; anything that isn't one of ours (or is left out of backups) is ignored. */
        fun restore(json: String): Map<String, Any> {
            val o = org.json.JSONObject(json)
            require(o.optInt("joint_launcher_backup") == 1) { "not a Joint Launcher backup" }
            return o.keys().asSequence().filter { it != "joint_launcher_backup" && backedUp(it) }.mapNotNull { k ->
                val v = o.optJSONObject(k) ?: return@mapNotNull null
                k to when {
                    v.has("b") -> v.getBoolean("b"); v.has("i") -> v.getInt("i"); v.has("l") -> v.getLong("l")
                    v.has("f") -> v.getDouble("f").toFloat(); v.has("s") -> v.getString("s")
                    v.has("set") -> v.getJSONArray("set").let { a -> (0 until a.length()).map(a::getString).toSet() }
                    else -> return@mapNotNull null
                }
            }.toMap()
        }

        val SECTIONS = linkedMapOf("widgets" to "Widgets", "apps" to "Apps", "live" to "Live TV", "watch" to "To Watch", "ha" to "Home Assistant")
        private val DEFAULT_NAMES = SECTIONS + ("favorites" to "Favorites")

        /** A section's title on Home: your name for it, else the default. */
        fun name(hub: Hub, key: String) = hub.prefs.getString("section_name_$key", null) ?: DEFAULT_NAMES.getValue(key)

        // each section's icon in the icon pack (LineX names; most packs follow them), and a built-in one if the pack lacks it
        private val PACK_ICONS = mapOf("widgets" to "magicwidgets", "apps" to "app_drawer_1", "ha" to "home_assistant", "watch" to "mymovies", "live" to "livetv") // a clapperboard: the reel read as a wheel
        private val OWN_ICONS = mapOf("widgets" to R.drawable.ic_widgets, "apps" to R.drawable.ic_apps, "ha" to R.drawable.ic_house, "watch" to R.drawable.ic_movie, "live" to R.drawable.ic_live_tv)

        /** The icon a section shows when titles are off: the one you picked, else the selected pack's, else a built-in one. */
        fun icon(a: MainActivity, key: String): android.graphics.drawable.Drawable? {
            val k = if (key == "favorites") "apps" else key // the Apps section keeps its icon while showing favorites
            val packs = a.apps.packs
            return a.hub.prefs.getString("section_icon_$k", null)?.split('|')?.takeIf { it.size == 2 }?.let { packs.drawable(it[0], it[1]) }
                ?: packs.active()?.let { packs.drawable(it, PACK_ICONS.getValue(k)) }
                ?: a.getDrawable(OWN_ICONS.getValue(k))
        }

        /** Tablet Home's sections in your order (new ones join at the end). */
        fun sections(hub: Hub) = RowOrder.apply(SECTIONS.keys.toList(), hub.prefs.getString("home_sections", "")!!.split(",").filter(String::isNotEmpty))
            .filter { if (hub.touch) it != "live" else it == "apps" || it == "live" || it == "watch" } // the TV: your apps, Live TV and the video rows
    }
}
