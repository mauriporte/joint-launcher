package com.mau.hub

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Icon packs in the standard ADW/Nova format (LineX and most others): their appfilter.xml maps an app's launch
 * activity to one of the pack's icons, and drawable.xml lists every icon for picking one by hand. App icons use the
 * chosen pack (LineX by default when installed); any app can get its own icon from any installed pack instead.
 */
class IconPacks(private val hub: Hub) {
    private val ctx get() = hub.ctx
    private val maps = HashMap<String, Map<String, String>>() // pack -> (component or package -> icon name)
    private val lists = HashMap<String, List<String>>()        // pack -> every icon name

    /** Installed icon packs as (package, name). */
    fun installed(): List<Pair<String, String>> = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
        .flatMap { ctx.packageManager.queryIntentActivities(Intent(it), 0) }
        .map { it.activityInfo.packageName }.distinct()
        .map { it to runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }

    /** The pack app icons come from: your choice (or none, the apps' own icons), else LineX if it's installed. */
    fun active(): String? = hub.prefs.getString("icon_pack", null).let { p ->
        if (p == NONE) null else p?.takeIf(hub::installed) ?: LINEX.takeIf(hub::installed)
    }

    /** pack|name[|auto] you picked for this app by hand, if any; "auto" = recoloured in the app's own colour. */
    private fun override(pkg: String) = hub.prefs.getString("icon_$pkg", null)?.split('|')?.takeIf { it.size in 2..3 }

    fun setOverride(pkg: String, pack: String?, name: String?, appColour: Boolean = true) = hub.prefs.edit().apply {
        if (pack == null || name == null) remove("icon_$pkg") else putString("icon_$pkg", "$pack|$name" + if (appColour) "|auto" else "")
    }.apply()

    /**
     * Which pack icon an app gets, as "pack|name" (the pack's own colours) or "pack|name|auto" (recoloured in the app's
     * colour): the one you picked; the one another device uses for an app of the same name (copied over, see
     * [exportPicks] - the TV's apps have other package names than the tablet's, so the pack doesn't know them); the
     * pack's icon for its package or its phone/TV twin; one whose name matches the app's; the pack's letter icon.
     * Null without a pack, or when nothing fits. Off the UI thread (parsing a pack takes a moment the first time).
     */
    fun pickFor(pkg: String, label: String): String? {
        val pack = active() ?: return null
        override(pkg)?.let { return it.joinToString("|") }
        if (pkg in OWN) return null
        copiedPicks()[plain(label)]?.let { return it }
        val map = map(pack)
        val component = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component
        (listOfNotNull(component?.let { "ComponentInfo{${it.packageName}/${it.className}}" }) + pkg + Links.MOBILE[pkg].orEmpty() + Links.canonical(pkg))
            .firstNotNullOfOrNull { map[it] }?.let { return "$pack|$it" }
        val names = allIcons(pack).toHashSet()
        val clean = label.lowercase().replace("+", "_plus").replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val name = listOf(clean, clean.substringBefore('_'), clean.replace("_", "")).firstOrNull { it.isNotEmpty() && it in names }
            ?: clean.firstOrNull()?.let { c -> listOf("alphabets_$c", "letter_$c", "$c").firstOrNull { it in names } }
            ?: return null
        return "$pack|$name|auto"
    }

    /** The pack icon for an app (see [pickFor]); null = use the app's own. brand: the colour for a service that isn't installed. */
    fun packIcon(pkg: String, label: String, brand: Int? = null): Drawable? {
        if (pkg in OWN && override(pkg) == null) return ownIcon(pkg)
        val pick = pickFor(pkg, label)?.split('|') ?: return null
        val d = drawable(pick[0], pick[1]) ?: return null
        // hand-picked and guessed icons take the app's colour (GeeSports' G stays yellow); a service that isn't
        // installed takes its brand colour (Filmin's F in green)
        if (pick.getOrNull(2) == "auto") (if (hub.installed(pkg)) appColour(pkg) else brand)
            ?.let { d.mutate().colorFilter = android.graphics.PorterDuffColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        return d
    }

    /**
     * One of our own apps' icon as drawn, straight from its resources: asking Android for it (getApplicationIcon) gets
     * it in HyperOS's white rounded box, because it isn't an adaptive icon.
     */
    fun ownIcon(pkg: String): Drawable? = if (pkg !in OWN) null else runCatching {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        val res = ctx.packageManager.getResourcesForApplication(info)
        // the plain drawing (drawable/icon); its adaptive app icon reads back on a black square
        @Suppress("DiscouragedApi") val plain = res.getIdentifier("icon", "drawable", pkg)
        res.getDrawable(if (plain != 0) plain else info.icon, null)
    }.getOrNull()

    /** Every app here by name, with the icon it gets: what another device (the TV) copies to show the same icons. */
    fun exportPicks(): String = org.json.JSONObject().apply {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).forEach { r ->
            val label = r.loadLabel(pm).toString()
            pickFor(r.activityInfo.packageName, label)?.let { put(plain(label), it) }
        }
    }.toString()

    /** Picks copied from another device ([exportPicks]), by app name. */
    private fun copiedPicks(): Map<String, String> = cachedCopies ?: runCatching {
        val o = org.json.JSONObject(hub.prefs.getString("icon_labels", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap()).also { cachedCopies = it }
    @Volatile private var cachedCopies: Map<String, String>? = null

    /**
     * The main colour of an app's own icon: the most common strong colour (white, black and greys don't count),
     * brightened enough to read on a dark tile. Null for black-and-white logos.
     */
    fun appColour(pkg: String): Int? = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val b = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 48, 48); d.draw(android.graphics.Canvas(b))
        val pixels = IntArray(48 * 48).also { b.getPixels(it, 0, 48, 0, 0, 48, 48) }
        dominant(pixels)
    }.getOrNull()

    fun drawable(pack: String, name: String): Drawable? = runCatching {
        val res = ctx.packageManager.getResourcesForApplication(pack)
        @Suppress("DiscouragedApi") val id = res.getIdentifier(name, "drawable", pack)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    /** Every icon in a pack, for the picker. */
    @Synchronized fun allIcons(pack: String): List<String> = lists.getOrPut(pack) {
        parse(pack, "drawable.xml") { p -> p.getAttributeValue(null, "drawable") }.distinct()
            .ifEmpty { map(pack).values.distinct().sorted() }
    }

    @Synchronized private fun map(pack: String): Map<String, String> = maps.getOrPut(pack) {
        val out = HashMap<String, String>()
        parse(pack, "appfilter.xml") { p ->
            val component = p.getAttributeValue(null, "component"); val drawable = p.getAttributeValue(null, "drawable")
            if (component != null && drawable != null) {
                out.putIfAbsent(component, drawable)
                // also by package, for apps whose launch activity has changed since the pack was made
                component.removePrefix("ComponentInfo{").substringBefore('/').takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, drawable) }
            }
            null
        }
        out
    }

    /** Reads the pack's assets/<file> (most packs ship it there too); collects what [item] returns for each <item>. */
    private fun parse(pack: String, file: String, item: (XmlPullParser) -> String?): List<String> = runCatching {
        val out = ArrayList<String>()
        ctx.packageManager.getResourcesForApplication(pack).assets.open(file).use { stream ->
            val p = Xml.newPullParser().apply { setInput(stream, null) }
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "item") item(p)?.let(out::add)
        }
        out as List<String>
    }.getOrDefault(emptyList())

    companion object {
        const val LINEX = "com.jndapp.line.x.iconpack"
        const val NONE = "none" // icon_pack value for "the apps' own icons"

        /** Our own apps keep their own icon with any pack (Joint Remote's is drawn in our style). A Change icon pick still wins. */
        private val OWN = setOf("com.mau.remote")

        /** An app name reduced to letters and digits, for matching the same app across devices (TV and tablet). */
        fun plain(label: String) = label.lowercase().filter(Char::isLetterOrDigit)

        /** Most common hue among vivid, opaque pixels (12 hue buckets), averaged and brightened; null if too few. */
        fun dominant(pixels: IntArray): Int? {
            val count = IntArray(12); val sum = Array(12) { FloatArray(3) }
            val hsv = FloatArray(3)
            for (p in pixels) {
                if (p ushr 24 < 128) continue
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                rgbToHsv(r, g, bl, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue // greys, white, black
                val k = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                count[k]++; sum[k][0] += r.toFloat(); sum[k][1] += g.toFloat(); sum[k][2] += bl.toFloat()
            }
            val best = count.indices.maxByOrNull { count[it] } ?: return null
            if (count[best] < pixels.size / 40) return null // hardly any colour: a black-and-white logo
            val n = count[best]
            rgbToHsv((sum[best][0] / n).toInt(), (sum[best][1] / n).toInt(), (sum[best][2] / n).toInt(), hsv)
            hsv[1] = maxOf(hsv[1], 0.6f); hsv[2] = maxOf(hsv[2], 0.85f) // readable on the dark tile
            return hsvToRgb(hsv)
        }

        private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
            out[0] = when { d == 0f -> 0f; max == rf -> 60f * (((gf - bf) / d) % 6f); max == gf -> 60f * ((bf - rf) / d + 2f); else -> 60f * ((rf - gf) / d + 4f) }
                .let { if (it < 0f) it + 360f else it }
            out[1] = if (max == 0f) 0f else d / max
            out[2] = max
        }

        private fun hsvToRgb(hsv: FloatArray): Int {
            val c = hsv[2] * hsv[1]; val x = c * (1 - kotlin.math.abs((hsv[0] / 60f) % 2f - 1)); val m = hsv[2] - c
            val (r, g, b) = when ((hsv[0] / 60f).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
            return (0xFF shl 24) or (((r + m) * 255).toInt() shl 16) or (((g + m) * 255).toInt() shl 8) or ((b + m) * 255).toInt()
        }
    }
}
