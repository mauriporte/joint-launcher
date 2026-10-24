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

    /** The pack app icons come from: your choice, else LineX if it's installed, else none. */
    fun active(): String? = hub.prefs.getString("icon_pack", null)?.takeIf(hub::installed)
        ?: LINEX.takeIf(hub::installed)

    /** pack|name you picked for this app by hand, if any. */
    private fun override(pkg: String) = hub.prefs.getString("icon_$pkg", null)?.split('|')?.takeIf { it.size == 2 }

    fun setOverride(pkg: String, pack: String?, name: String?) =
        hub.prefs.edit().apply { if (pack == null || name == null) remove("icon_$pkg") else putString("icon_$pkg", "$pack|$name") }.apply()

    /** Off the UI thread (parsing a pack takes a moment the first time). Null = no pack icon: use the app's own. */
    fun iconFor(pkg: String): Drawable? {
        override(pkg)?.let { (pack, name) -> drawable(pack, name)?.let { return it } }
        val pack = active() ?: return null
        val map = map(pack)
        val component = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component
        val name = component?.let { map["ComponentInfo{${it.packageName}/${it.className}}"] } ?: map[pkg] ?: return null
        return drawable(pack, name)
    }

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

    companion object { const val LINEX = "com.jndapp.line.x.iconpack" }
}
