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
    fun filterFor(pkg: String): String? = when (Links.canonical(pkg)) {
        Links.JELLYFIN -> "jellyfin".takeIf { hub.jfKey.isNotEmpty() }
        Links.STREMIO -> "stremio"
        Links.SMARTTUBE -> "youtube".takeIf { hub.connected("google") }
        Links.SPOTIFY -> "spotify".takeIf { hub.connected("spotify") }
        Links.GEESPORTS -> "geesports"
        else -> PROVIDERS.firstOrNull { it.pkg == Links.canonical(pkg) }?.source?.name
    }

    @Volatile private var cache: Pair<Long, List<SideApp>>? = null
    fun forgetList() { cache = null }

    /** Every launchable TV app (what Google TV lists under "Your apps"), in your saved order. */
    fun all(): List<SideApp> {
        // scanning packages and reading ~25 labels is the slow part; reuse it for 30s (rail, rows and focus all ask)
        val apps = cache?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(if (hub.touch) Intent.CATEGORY_LAUNCHER else Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != hub.ctx.packageName && hub.installed(it) }
                .map { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    SideApp(pkg, label, filterFor(pkg), pkg) // icon loads in the background (see iconInto)
                }
                .sortedWith(compareBy({ it.filter == null }, { it.label.lowercase() })) // default before any saved/Google TV order
                .also { cache = System.currentTimeMillis() to it }
        // your own display names (Rename in the hold menu) replace the apps' labels everywhere
        val byKey = apps.associateBy { it.key }.mapValues { (k, app) -> displayName(k)?.let { SideApp(app.key, it, app.filter, app.pkg, app.logo) } ?: app }
        return RowOrder.apply(apps.map { it.key }, savedOrder()).map(byKey::getValue)
    }

    fun displayName(key: String): String? = hub.prefs.getString("label_$key", null)
    fun setDisplayName(key: String, name: String?) =
        hub.prefs.edit().apply { if (name.isNullOrBlank()) remove("label_$key") else putString("label_$key", name.trim()) }.apply()

    /** Hold menu → Rename: your name for the app on screen (empty, or "Original name", goes back to the app's own). */
    fun rename(a: MainActivity, app: SideApp, onDone: () -> Unit) {
        val input = android.widget.EditText(a).apply { setText(app.label); isSingleLine = true; selectAll() }
        android.app.AlertDialog.Builder(a).setTitle("Rename")
            .setView(android.widget.LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input, android.widget.LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> setDisplayName(app.key, input.text.toString()); onDone() }
            .setNeutralButton("Original name") { _, _ -> setDisplayName(app.key, null); onDone() }
            .setNegativeButton("Cancel", null).show()
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

    /** Tablet: the apps the Apps section shows when its heart button is on. */
    fun favourites(): Set<String> = hub.prefs.getStringSet("fav_apps", null) ?: emptySet()

    fun setFavourite(pkg: String, on: Boolean) =
        hub.prefs.edit().putStringSet("fav_apps", favourites().let { if (on) it + pkg else it - pkg }).apply()

    /** Tablet: apps you hid from the Apps section (Settings → Hidden apps lists them to bring back). */
    fun hidden(): Set<String> = hub.prefs.getStringSet("hidden_apps", null) ?: emptySet()

    fun setHidden(pkg: String, on: Boolean) =
        hub.prefs.edit().putStringSet("hidden_apps", hidden().let { if (on) it + pkg else it - pkg }).apply()

    /** Favorites' own order (you move them while the heart is on); new favorites join at the end. */
    fun favOrder(): List<String> = hub.prefs.getString("fav_order", null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    fun saveFavOrder(moved: List<String>) =
        hub.prefs.edit().putString("fav_order", (moved + favOrder().filter { it !in moved }).joinToString("\n")).apply()

    var favouritesOnly: Boolean
        get() = hub.prefs.getBoolean("apps_favs_only", false)
        set(v) = hub.prefs.edit().putBoolean("apps_favs_only", v).apply()

    /** Opens the app (its TV or phone/tablet version, whichever is installed). */
    fun launch(pkg: String) {
        val p = hub.pkgFor(pkg) ?: pkg
        val i = (if (hub.touch) null else pm.getLeanbackLaunchIntentForPackage(p)) ?: pm.getLaunchIntentForPackage(p)
        if (i != null) hub.ctx.startActivity(i) else hub.ctx.toast("$pkg is not installed")
    }

    // ---- icons ----

    private val iconCache = HashMap<String, Drawable>() // UI thread only

    /** Our own icons for a few apps whose TV icons are banners or hard to read at sidebar size. */
    private fun customIcon(pkg: String?): Drawable? = when (pkg?.let(Links::canonical)) {
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

    val packs = IconPacks(hub)

    /**
     * An app's icon: the one you picked, else the icon pack's (LineX on the tablet), else our own for a few apps, else
     * the app's. Icons come from APKs, which is slow on this TV; loaded off the UI thread, once.
     */
    fun iconInto(pkg: String, into: ImageView) {
        iconCache[pkg]?.let { into.setImageDrawable(it); return }
        into.tag = "icon:$pkg"
        hub.imagePool.execute {
            // a pack on: only pack icons (guessing the missing ones); no pack: only the apps' own (and ours for banner-only TV apps)
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(hub.pkgFor(pkg) ?: pkg, 0)).toString() }.getOrDefault(pkg)
            val d = if (packs.active() != null) packs.packIcon(pkg, label) ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                else customIcon(pkg) ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            d ?: return@execute
            hub.main.post { iconCache[pkg] = d; if (into.tag == "icon:$pkg") into.setImageDrawable(d) }
        }
    }

    /** After you pick a different icon. */
    fun forgetIcon(pkg: String) { iconCache.remove(pkg) }
    fun forgetIcons() = iconCache.clear() // icon pack changed

    /** Catalogue-only services (app not installed): their logo from JustWatch, looked up once. */
    fun logoInto(p: Provider, into: ImageView) {
        // with an icon pack, the service's pack icon (found by package even though it isn't installed)
        if (packs.active() != null) {
            iconCache["logo:${p.pkg}"]?.let { into.setImageDrawable(it); return }
            into.tag = "logo:${p.pkg}"
            hub.imagePool.execute {
                val d = packs.packIcon(p.pkg, p.source.label) ?: return@execute
                hub.main.post { iconCache["logo:${p.pkg}"] = d; if (into.tag == "logo:${p.pkg}") into.setImageDrawable(d) }
            }
            return
        }
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
