package com.mau.hub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.widget.ImageView

/**
 * One sidebar entry. key = stable id for ordering (package name, or "jw:HULU" for catalogue-only services);
 * filter = the single-app view it selects, or null when it simply opens the app (VPN, file manager...).
 */
class SideApp(val key: String, val label: String, val filter: String?, val pkg: String?, val logo: Provider? = null)

/** The TV's apps: which ones have content here, the shared sidebar / "Your apps" order, and their icons. */
class Apps(private val hub: Hub, private val images: Images) {
    private val pm get() = hub.ctx.packageManager

    /** Which single-app view an installed app maps to (null = no content here, just launch it). */
    fun filterFor(pkg: String): String? = when (pkg) {
        Links.JELLYFIN -> "jellyfin".takeIf { hub.jfKey.isNotEmpty() }
        Links.STREMIO -> "stremio"
        Links.SMARTTUBE -> "youtube".takeIf { hub.connected("google") }
        Links.SPOTIFY -> "spotify".takeIf { hub.connected("spotify") }
        Links.GEESPORTS -> "geesports"
        else -> PROVIDERS.firstOrNull { it.pkg == pkg }?.source?.name
    }

    @Volatile private var cache: Pair<Long, List<SideApp>>? = null

    /** Every launchable TV app (what Google TV lists under "Your apps"), in your saved order. */
    fun all(): List<SideApp> {
        // scanning packages and reading ~25 labels is the slow part; reuse it for 30s (rail, rows and focus all ask)
        val apps = cache?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != hub.ctx.packageName && hub.installed(it) }
                .map { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    SideApp(pkg, label, filterFor(pkg), pkg) // icon loads in the background (see iconInto)
                }
                .sortedWith(compareBy({ it.filter == null }, { it.label.lowercase() })) // default before any saved/Google TV order
                .also { cache = System.currentTimeMillis() to it }
        val byKey = apps.associateBy { it.key }
        return RowOrder.apply(apps.map { it.key }, savedOrder()).map(byKey::getValue)
    }

    /** Sidebar: apps with content here (they filter the home screen) plus every streaming service's catalogue, installed or not. */
    fun sidebar(): List<SideApp> {
        val catalogues = PROVIDERS.filter { !hub.installed(it.pkg) }
            .map { SideApp("jw:${it.source.name}", it.source.label, it.source.name, null, logo = it) }
        val all = all().filter { it.filter != null } + catalogues
        val byKey = all.associateBy { it.key }
        return RowOrder.apply(all.map { it.key }, savedOrder()).map(byKey::getValue)
    }

    fun savedOrder() = hub.prefs.getString("app_order", null)?.split("\n")?.filter { it.isNotEmpty() } ?: GOOGLE_TV_ORDER

    /** One list's new order; apps it doesn't show keep their place after it (sidebar and apps row share the order). */
    fun saveOrder(moved: List<String>) =
        hub.prefs.edit().putString("app_order", (moved + savedOrder().filter { it !in moved }).joinToString("\n")).apply()

    fun launch(pkg: String) {
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i != null) hub.ctx.startActivity(i) else hub.ctx.toast("$pkg is not installed")
    }

    // ---- icons ----

    private val iconCache = HashMap<String, Drawable>() // UI thread only

    /** Our own icons for a few apps whose TV icons are banners or hard to read at sidebar size. */
    private fun customIcon(pkg: String?): Drawable? = when (pkg) {
        Links.NETFLIX -> hub.ctx.getDrawable(R.drawable.ic_netflix)
        Links.SMARTTUBE -> hub.ctx.getDrawable(R.drawable.ic_youtube)
        PROVIDERS.first { it.source == Source.PRIME }.pkg -> primeIcon()
        else -> null
    }

    /** "prime video" in white on Prime's light blue (a wordmark, so drawn as text rather than a vector). */
    private fun primeIcon(): Drawable {
        val s = 192
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(Source.PRIME.color)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = android.graphics.Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        p.textSize = 56f; c.drawText("prime", s / 2f, s / 2f, p)
        p.textSize = 40f; p.typeface = Typeface.create("sans-serif", Typeface.NORMAL); c.drawText("video", s / 2f, s / 2f + 46f, p)
        return android.graphics.drawable.BitmapDrawable(hub.ctx.resources, b)
    }

    /** Other apps' icons come from their APKs, which is slow on this TV; load them off the UI thread, once. */
    fun iconInto(pkg: String, into: ImageView) {
        customIcon(pkg)?.let { into.setImageDrawable(it); return }
        iconCache[pkg]?.let { into.setImageDrawable(it); return }
        into.tag = "icon:$pkg"
        hub.imagePool.execute {
            val d = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return@execute
            hub.main.post { iconCache[pkg] = d; if (into.tag == "icon:$pkg") into.setImageDrawable(d) }
        }
    }

    /** Catalogue-only services (app not installed): their logo from JustWatch, looked up once. */
    fun logoInto(p: Provider, into: ImageView) {
        customIcon(p.pkg)?.let { into.setImageDrawable(it); return }
        hub.prefs.getString("logo_${p.source.name}", null)?.let { images.load(it, into); return }
        hub.pool.execute {
            val url = runCatching { Sources.providerLogo(p, hub.country) }.getOrNull() ?: return@execute
            hub.prefs.edit().putString("logo_${p.source.name}", url).apply()
            hub.main.post { images.load(url, into) }
        }
    }

    companion object {
        /** Your Google TV "Your apps" order, read from its screen on 2026-10-23; the starting order until you reorder. */
        private val GOOGLE_TV_ORDER = listOf(
            "com.spotify.tv.android", "ch.protonvpn.android", "com.stremio.one", "com.live.geesports", "org.smarttube.beta",
            "com.netflix.ninja", "org.jellyfin.androidtv", "com.xiaomi.mitv.tvmanager", "com.yablio.sendfilestotv", "com.filmin.androidtv",
        )
    }
}
