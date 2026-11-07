package com.mau.hub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The tablet's copy of the Linux desktop's second-workspace dashboard (art-tiles), itself the Windows PC's
 * Ctrl+Alt+H dashboard: the glass Home Assistant tiles (10 columns, dense, same order), the calendar tile, the side
 * column (servers, Cloudflare, Tailscale, ADB/Stream Control, Seguridad, Listas, Notificaciones, Projects) with its
 * slide-in panels, and the glass slider. A full-screen page over Home, opened by a gesture (Settings → Gestures).
 *
 * Tiles and side column: assets/overlay.json, the Windows PC's file (not in git: real HA entities). Laid out in dp, so it
 * matches the desktop's 1024x600-ish screen. Glass and hidden calendars are kept in the launcher's prefs.
 */
class Dashboard(private val a: MainActivity) {
    private val conf: JSONObject = runCatching {
        a.assets.open("overlay.json").bufferedReader().use { it.readText() }.trim().let {
            if (it.startsWith("[")) JSONObject().put("tiles", JSONArray(it)) else JSONObject(it)
        }
    }.getOrDefault(JSONObject())
    private val tiles = conf.optJSONArray("tiles").objs()
    private val servers = conf.optJSONArray("servers").objs()
    private val prefs = a.hub.prefs
    private var glass = prefs.getInt("dash_glass", conf.optInt("glass", 40))
    private var hidden = prefs.getStringSet("dash_cal_hidden", null)?.toList() ?: conf.optJSONArray("calHidden").strings()

    @Volatile private var states: Map<String, JSONObject> = emptyMap()
    @Volatile private var loaded = false
    @Volatile private var base: String? = null
    @Volatile private var cams: Map<String, Bitmap> = emptyMap()
    @Volatile private var cals: List<JSONObject>? = null
    @Volatile private var events: List<JSONObject>? = null
    @Volatile private var calFail = false
    @Volatile private var pdata: Map<String, Map<String, Any>> = emptyMap()
    @Volatile private var notes: Map<String, Int> = emptyMap() // Projects: notes per vault folder
    @Volatile private var trees: Map<String, List<Note>> = emptyMap()
    private val openFolders = mutableSetOf<String>()
    private var armed: Any? = null
    private var panel: JSONObject? = null
    private var msg = ""
    private val pool = Sources.bgPool(3)
    private val busy = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val view = DashView()
    val isOpen get() = view.visibility == View.VISIBLE

    fun open() {
        if (isOpen) return
        val w = a.window.decorView.width.toFloat()
        view.visibility = View.VISIBLE
        view.translationX = w; view.animate().translationX(0f).setDuration(220).start()
        a.page.animate().translationX(-w).setDuration(220).start()
        tick = 0; view.removeCallbacks(loop); view.post(loop)
    }

    fun close() {
        if (!isOpen) return
        val w = a.window.decorView.width.toFloat()
        panel = null; view.removeCallbacks(loop); view.post(::dropScreen)
        view.animate().translationX(w).setDuration(220).withEndAction { view.visibility = View.GONE }.start()
        a.page.animate().translationX(0f).setDuration(220).start()
    }

    /** Back: closes a panel first, then the page. */
    fun back() { if (panel != null) { panel = null; view.invalidate() } else close() }

    // ---- reading HA, only while the page is showing: states every 10 s, cameras every 5, calendar every 5 min
    private var tick = 0
    private val loop = object : Runnable {
        override fun run() {
            if (!isOpen) return
            if (tick % POLL == 0) bg("states", ::refresh)
            if (tick % CAM_POLL == 0) { bg("cams", ::cameras); if (panel?.optString("kind") == "security") bg("panel", ::loadPanel) }
            if (tick % CAL_POLL == 0 || (calFail && tick % 30 == 0)) bg("events", ::readEvents)
            if (tick % CAL_POLL == 0) bg("notes", ::readNotes)
            tick++
            view.postDelayed(this, 1000)
        }
    }

    private fun bg(key: String, fn: () -> Unit) {
        if (!busy.add(key)) return
        pool.execute { try { runCatching(fn) } finally { busy.remove(key); view.post { view.invalidate() } } }
    }

    private fun auth() = HaWidget.auth(a)
    private fun url(path: String) = (base ?: HaWidget.base(a).also { base = it }) + path

    private fun refresh() {
        base = HaWidget.base(a)
        val arr = JSONArray(Sources.get(url("/api/states"), auth()))
        states = (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.optString("entity_id") }
        loaded = true
    }

    private fun image(pic: String): Bitmap? {
        val conn = java.net.URL(url(pic)).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 12000
        (auth() + Hub.cfAccess(conn.url.toString())).forEach(conn::setRequestProperty)
        return try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
    }

    private fun picture(entity: String) = states[entity]?.optJSONObject("attributes")?.optString("entity_picture")?.takeIf { it.isNotEmpty() }

    private fun cameras() {
        val out = cams.toMutableMap()
        tiles.filter { it.optBoolean("camera") }.forEach { t ->
            val e = t.optString("entity"); val pic = picture(e) ?: return@forEach
            runCatching { image(pic) }.getOrNull()?.let { out[e] = it }
        }
        cams = out
    }

    private fun readEvents() {
        try {
            val list = cals ?: JSONArray(Sources.get(url("/api/calendars"), auth())).objs().also { cals = it }
            val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            val end = today.plusDays(14)
            val shown = list.filter { it.optString("entity_id") !in hidden }
            val evs = mutableListOf<JSONObject>(); var ok = shown.isEmpty()
            val q = "start=" + enc(today.toOffsetDateTime().toString()) + "&end=" + enc(end.toOffsetDateTime().toString())
            shown.forEach { c ->
                runCatching { JSONArray(Sources.get(url("/api/calendars/${c.optString("entity_id")}?$q"), auth())).objs() }
                    .onSuccess { r -> ok = true; r.forEach { evs.add(it.put("calendar", c.optString("entity_id"))) } } // one failing doesn't lose the others
            }
            if (ok) events = evs.filter { eventEnd(it) > today }.sortedBy { eventStart(it) }
            calFail = !ok
        } catch (e: Exception) { calFail = true }
    }

    private fun todoItems(entity: String): List<JSONObject> {
        val r = JSONObject(Sources.post(url("/api/services/todo/get_items?return_response"),
            JSONObject().put("entity_id", entity).put("status", "needs_action").toString(), auth()))
        return r.optJSONObject("service_response")?.optJSONObject(entity)?.optJSONArray("items").objs()
    }

    /** A vault entry: a folder (children) or a Markdown note; path is inside the vault, for Obsidian. */
    class Note(val name: String, val path: String, val children: List<Note>?)

    // shortcut: a fixed folder (Syncthing's copy) read with All files access; this tablet's folder picker lists nothing
    private fun vault(): java.io.File? = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Documents/$VAULT_NAME")
        .takeIf { android.os.Environment.isExternalStorageManager() }

    /** The Projects entries' folders: folders first, then Markdown notes (without .md), hidden ones skipped. */
    private fun readNotes() {
        val root = vault() ?: return
        fun walk(d: java.io.File, path: String): List<Note> = d.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".md")) }
            .map { if (it.isDirectory) Note(it.name, path + it.name, walk(it, path + it.name + "/")) else Note(it.name.removeSuffix(".md"), path + it.name.removeSuffix(".md"), null) }
            .sortedWith(compareBy({ it.children == null }, { it.name.lowercase() }))
        val t = trees.toMutableMap(); val n = notes.toMutableMap()
        servers.filter { it.optString("kind") == "notes" }.forEach { sv ->
            val folder = sv.optString("folder").trim('/')
            val dir = java.io.File(root, folder)
            if (dir.canonicalPath.startsWith(root.canonicalPath) && dir.isDirectory) walk(dir, if (folder.isEmpty()) "" else "$folder/").let { t[sv.optString("folder")] = it; n[sv.optString("folder")] = countNotes(it) }
        }
        trees = t; notes = n
    }

    // the live screen inside the ADB / Stream Control panel, as the desktop's iframe: a real WebView over the drawn panel,
    // so ws-scrcpy gets the touches (tap, swipe on the device's screen)
    private val webs = mutableMapOf<String, android.webkit.WebView>() // shown screens by address
    private val frames = mutableListOf<Pair<RectF, String>>() // where this draw put each screen (dp), and its address
    private var screenBox: RectF? = null // the panel's scrolling area: screens outside it are hidden

    /** Lays a live WebView over each screen frame drawn in the panel; frames scrolled away are clipped or hidden. */
    private fun placeScreens(frames: List<Pair<RectF, String>>, box: RectF?) = view.post {
        val wanted = if (isOpen && box != null) frames else emptyList()
        (webs.keys - wanted.map { it.second }.toSet()).forEach { src -> webs.remove(src)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() } }
        val d = view.resources.displayMetrics.density
        wanted.forEach { (r, src) ->
            val w = webs.getOrPut(src) {
                android.webkit.WebView(a).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = android.webkit.WebViewClient(); setBackgroundColor(Color.BLACK)
                    (view.parent as android.view.ViewGroup).addView(this, android.widget.FrameLayout.LayoutParams(0, 0))
                    loadUrl(src, Hub.cfAccess(src))
                }
            }
            val vis = RectF(r).apply { if (!intersect(box!!)) setEmpty() }
            if (vis.height() < 24) { w.visibility = View.GONE; return@forEach }
            (w.layoutParams as android.widget.FrameLayout.LayoutParams).let { lp ->
                val l = (r.left * d).roundToInt(); val tp = (r.top * d).roundToInt(); val ww = (r.width() * d).roundToInt(); val hh = (r.height() * d).roundToInt()
                if (lp.leftMargin != l || lp.topMargin != tp || lp.width != ww || lp.height != hh) { lp.leftMargin = l; lp.topMargin = tp; lp.width = ww; lp.height = hh; w.layoutParams = lp }
            }
            // only the part inside the panel's scrolling area shows (the frame keeps its size, so the page doesn't reflow)
            w.clipBounds = android.graphics.Rect(((vis.left - r.left) * d).roundToInt(), ((vis.top - r.top) * d).roundToInt(), ((vis.right - r.left) * d).roundToInt(), ((vis.bottom - r.top) * d).roundToInt())
            w.visibility = View.VISIBLE
        }
    }

    /** No screen any more: the pages stop streaming. */
    private fun dropScreen() = placeScreens(emptyList(), null)

    /** This device's own addresses on the network, so its screen isn't listed. */
    private fun ownIps(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())


    private fun openNote(path: String) {
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open?vault=" + enc(VAULT_NAME) + "&file=" + enc(path))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { flash("Obsidian isn't installed") }
    }

    private fun loadPanel() {
        val sv = panel ?: return
        if (sv.optString("kind") == "notes") { readNotes(); return }
        val d = mutableMapOf<String, Any>()
        runCatching {
            when (sv.optString("kind")) {
                "lists" -> sv.optJSONArray("lists").objs().forEach { l -> d[l.optString("entity")] = todoItems(l.optString("entity")) }
                "notifications" -> {
                    d["ha"] = runCatching { (wsCall(JSONObject().put("type", "persistent_notification/get")) as? JSONArray).objs() }.getOrDefault(emptyList<JSONObject>())
                    d["items"] = todoItems(sv.optString("list")).reversed().take(80)
                }
                "security" -> sv.optJSONArray("cameras").objs().forEach { c ->
                    picture(c.optString("entity"))?.let { pic -> runCatching { image(pic) }.getOrNull()?.let { d[c.optString("entity")] = it } }
                }
            }
            // ADB / Stream Control: the devices ADB Control is connected to (its /viewdata), as the desktop's Screens view lists them
            if (sv.has("screens") && BuildConfig.ADB_API.isNotEmpty()) d["devs"] = runCatching {
                JSONObject(Sources.get(BuildConfig.ADB_API.trimEnd('/') + "/viewdata" + if (sv.optString("screens") == "Stream") "?mode=stream" else ""))
                    .optJSONArray("devices").objs()
                    // not this device's own screen, nor any on the owner's list (adb.hide: their IPs)
                    .filter { dv -> (dv.optString("ip") !in BuildConfig.ADB_HIDE.split(',').map(String::trim) + ownIps()).also { if (!it) d["self"] = (d["self"] as? List<*>).orEmpty() + dv.optString("name") } }
            }.getOrElse { d["devErr"] = true; emptyList<JSONObject>() }
        }
        if (panel === sv) pdata = pdata + (sv.optString("name") to d)
    }

    /** One Home Assistant websocket command (persistent notifications have no REST API). */
    private fun wsCall(cmd: JSONObject): Any? {
        val u = java.net.URL(url("")); val tls = u.protocol == "https"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val raw = java.net.Socket().apply { connect(java.net.InetSocketAddress(u.host, port), 8000); soTimeout = 10000 }
        val s = if (!tls) raw else (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(raw, u.host, port, true).also {
            it as javax.net.ssl.SSLSocket; it.startHandshake()
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, it.session)) { it.close(); error("certificate doesn't match ${u.host}") }
        }
        s.use {
            val ins = java.io.BufferedInputStream(it.getInputStream()); val out = it.getOutputStream()
            val key = android.util.Base64.encodeToString(ByteArray(16).also(java.security.SecureRandom()::nextBytes), android.util.Base64.NO_WRAP)
            out.write("GET /api/websocket HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n${Hub.cfLines(u.toString())}\r\n".toByteArray())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val b = ins.read(); if (b < 0) error("websocket closed"); head.append(b.toChar()) }
            if (" 101 " !in head.lineSequence().first()) error("websocket refused")
            fun need(n: Int) = ByteArray(n).also { buf -> var o = 0; while (o < n) { val r = ins.read(buf, o, n - o); if (r < 0) error("websocket closed"); o += r } }
            fun recv(): JSONObject {
                val data = java.io.ByteArrayOutputStream()
                while (true) {
                    val (b0, b1) = need(2).map { b -> b.toInt() and 0xFF }
                    var n = (b1 and 0x7F).toLong()
                    if (n == 126L) n = java.math.BigInteger(1, need(2)).toLong() else if (n == 127L) n = java.math.BigInteger(1, need(8)).toLong()
                    data.write(need(n.toInt()))
                    if (b0 and 0x80 != 0) return JSONObject(data.toString("UTF-8"))
                }
            }
            fun send(o: JSONObject) {
                val p = o.toString().toByteArray(); val mask = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                val size = when { p.size < 126 -> byteArrayOf((0x80 or p.size).toByte()); p.size < 65536 -> byteArrayOf((0x80 or 126).toByte(), (p.size shr 8).toByte(), p.size.toByte())
                    else -> byteArrayOf((0x80 or 127).toByte()) + java.nio.ByteBuffer.allocate(8).putLong(p.size.toLong()).array() }
                out.write(byteArrayOf(0x81.toByte()) + size + mask + ByteArray(p.size) { i -> (p[i].toInt() xor mask[i % 4].toInt()).toByte() }); out.flush()
            }
            recv() // auth_required
            send(JSONObject().put("type", "auth").put("access_token", auth().getValue("Authorization").removePrefix("Bearer ")))
            if (recv().optString("type") != "auth_ok") error("websocket auth failed")
            send(JSONObject(cmd.toString()).put("id", 1))
            while (true) { val r = recv(); if (r.optInt("id") == 1) return r.opt("result") }
        }
    }

    // ---- actions
    private fun flash(m: String) {
        msg = m; view.invalidate()
        view.postDelayed({ if (msg == m) { msg = ""; view.invalidate() } }, 4000)
    }

    private fun arm(key: Any, label: String? = null) {
        armed = key; label?.let(::flash); view.invalidate()
        view.postDelayed({ if (armed == key) { armed = null; view.invalidate() } }, 5000)
    }

    private fun call(domain: String, service: String, data: JSONObject, after: (() -> Unit)? = null) = pool.execute {
        runCatching { Sources.post(url("/api/services/$domain/$service"), data.toString(), auth()) }
            .onFailure { e -> view.post { flash("${data.optString("entity_id", domain)}: ${e.message}") } }
        Thread.sleep(1000)
        runCatching { refresh() }; after?.invoke()
        view.post { view.invalidate() }
    }

    /** A home-network address opens as itself at home and as its outside address away ([Lan]). */
    private fun openUrl(u: String?) {
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) return
        pool.execute {
            Lan.check()
            val go = Lan.pick(u)
            view.post { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(go)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }

    private fun openPage(t: JSONObject) = openUrl(t.optString("url").ifEmpty { null }
        ?: t.optString("nav").ifEmpty { null }?.let { nav -> base?.let { it + nav } })

    private fun tapTile(i: Int) {
        val t = tiles[i]; val ent = t.optString("entity").ifEmpty { null }
        val s = ent?.let { states[it] }; val domain = (ent ?: "").substringBefore('.')
        if (SKIP.any(t::has)) return
        if (!t.has("action") && (t.has("url") || t.has("nav"))) return openPage(t)
        if (ent != null && (s == null || s.optString("state") == "unavailable")) return
        if (t.has("confirm") && armed != "tile$i") return arm("tile$i", t.optString("confirm"))
        armed = null
        t.optJSONObject("action")?.let { act ->
            val (d, service) = act.optString("service").split(".", limit = 2)
            call(d, service, JSONObject().put("entity_id", act.optString("entity"))); openPage(t)
        } ?: run {
            val service = if (domain == "lock") (if (s?.optString("state") == "locked") "unlock" else "lock") else TAP[domain]
            if (service != null) call(domain, service, JSONObject().put("entity_id", ent))
        }
        view.invalidate()
    }

    private fun tapApp(sv: JSONObject, app: JSONObject) {
        if (sv.optBoolean("readonly")) return openUrl(app.optString("url"))
        val key = "app" + app.optString("entity")
        if (app.has("confirm") && armed != key) return arm(key)
        armed = null
        call("switch", "toggle", JSONObject().put("entity_id", app.optString("entity"))); view.invalidate()
    }

    private fun todoDone(entity: String, uid: String) =
        call("todo", "update_item", JSONObject().put("entity_id", entity).put("item", uid).put("status", "completed")) { loadPanel() }

    private fun toggleCal(e: String) {
        hidden = if (e in hidden) hidden - e else hidden + e
        prefs.edit().putStringSet("dash_cal_hidden", hidden.toSet()).apply()
        events = null; bg("events", ::readEvents); view.invalidate()
    }

    private fun openPanel(sv: JSONObject) { panel = sv; scroll["panel"] = 0f; bg("panel", ::loadPanel); view.invalidate() }

    // ---- drawing (in dp, as art-tiles draws in px on the desktop)
    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val scrolls = mutableListOf<Pair<RectF, String>>()
    private val scroll = mutableMapOf<String, Float>()
    private val smax = mutableMapOf<String, Float>()
    private var slider: RectF? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val layouts = HashMap<String, StaticLayout>()

    private fun rgba(hex: String, alpha: Float = 1f) = Color.parseColor(hex).let { (it and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) }
    private fun white(alpha: Float) = rgba("#FFFFFF", alpha)

    private fun round(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fill.color = color; val rr = max(0.1f, min(r, min(w / 2, h / 2)))
        c.drawRoundRect(x, y, x + w, y + h, rr, rr, fill)
    }

    private fun ring(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, width: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = width; val i = width / 2; val rr = max(0.1f, min(r, min(w / 2, h / 2)) - i)
        c.drawRoundRect(x + i, y + i, x + w - i, y + h - i, rr, rr, stroke)
    }

    private fun lay(s: String, px: Float, bold: Boolean, width: Float?, lines: Int): StaticLayout {
        val key = "$px|$bold|$width|$lines|$s"
        layouts[key]?.let { return it }
        if (layouts.size > 800) layouts.clear()
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = max(6f, px); typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT }
        val w = (width ?: (p.measureText(s) + 1)).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(s, 0, s.length, p, w).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).build().also { layouts[key] = it }
    }

    private fun textH(s: String, px: Float, bold: Boolean = false, width: Float? = null, lines: Int = 1) = lay(s, px, bold, width, lines).height.toFloat()
    private fun textW(s: String, px: Float, bold: Boolean = false) = lay(s, px, bold, null, 1).getLineWidth(0)

    private fun text(c: Canvas, s: String, px: Float, color: Int, x: Float, y: Float, width: Float? = null, lines: Int = 1, bold: Boolean = false): Pair<Float, Float> {
        val l = lay(s, px, bold, width, lines)
        l.paint.color = color
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) }.orZero() to l.height.toFloat()
    }

    private val paths = HashMap<String, Path?>()
    /** An icon: a Material path (24x24) or an app's SVG body (its path data, 256x256). */
    private fun icon(c: Canvas, d: String?, view: Float, color: Int, x: Float, y: Float, size: Float): Boolean {
        if (d.isNullOrEmpty()) return false
        val p = paths.getOrPut(d) {
            val data = if (d.trimStart().startsWith("<")) Regex("\\bd=\"([^\"]+)\"").findAll(d).map { it.groupValues[1] }.toList() else listOf(d)
            runCatching { Path().apply { data.forEach { addPath(PathParser.createPathFromPathData(it)) } } }.getOrNull()?.takeIf { data.isNotEmpty() }
        } ?: return false
        fill.color = color
        c.save(); c.translate(x, y); c.scale(size / view, size / view); c.drawPath(p, fill); c.restore()
        return true
    }

    private fun glassTile(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, lit: Boolean, hue: String = ACCENT) {
        val gl = glass / 100f
        round(c, x, y, w, h, r, if (lit) rgba(hue, 0.2f * gl) else rgba("#3a3a3e", 0.55f * gl))
        ring(c, x, y, w, h, r, if (lit) 2f else 1.5f, if (lit) rgba(hue) else white(0.10f)) // the inset ring
    }

    private fun badge(c: Canvas, x: Float, y: Float, size: Float, lit: Boolean, hue: String, d: String?, view: Float = 24f) {
        round(c, x, y, size, size, size / 4, if (lit) rgba(hue, 0x55 / 255f) else white(0.08f))
        val col = if (lit) Color.WHITE else rgba("#8a8a8e"); val isz = max(6f, (size * 0.56f).roundToInt().toFloat())
        if (!icon(c, d, view, col, x + (size - isz) / 2, y + (size - isz) / 2, isz)) { fill.color = col; c.drawCircle(x + size / 2, y + size / 2, size / 6, fill) }
    }

    private fun hit(x: Float, y: Float, w: Float, h: Float, fn: () -> Unit) { hits.add(RectF(x, y, x + w, y + h) to fn) }

    private fun scroller(key: String, x: Float, y: Float, w: Float, h: Float, content: Float): Float {
        scrolls.add(RectF(x, y, x + w, y + h) to key)
        smax[key] = max(0f, content - h)
        return min(scroll[key] ?: 0f, smax.getValue(key)).also { scroll[key] = it }
    }

    private fun fade() = 0.1f + 0.9f * ((glass - 3) / 97f)

    private fun drawTile(c: Canvas, i: Int, t: JSONObject, x: Float, y: Float, w: Float, h: Float, k: Float) {
        val ip = 12 * k; val bsz = 32 * k; val fn = 14 * k; val fv = 12 * k; val rad = 16 * k
        val ent = t.optString("entity").ifEmpty { null }
        val s = ent?.let { states[it] }; val domain = (ent ?: "").substringBefore('.')
        val link = t.has("nav") || t.has("url") || t.has("action") || SKIP.any(t::has)
        val missing = if (ent != null) (if (s != null) s.optString("state") == "unavailable" else loaded) else !link
        val hue = if (domain == "alarm_control_panel") RED else TONES[t.optString("color")]
            ?: if (s?.optJSONObject("attributes")?.has("entity_id") == true) GROUP else ACCENT
        val lit = if (domain in TOGGLES && !t.has("action")) isOn(s) else !missing
        if (missing) c.saveLayerAlpha(x - 4, y - 4, x + w + 4, y + h + 4, 128)
        glassTile(c, x, y, w, h, rad, lit, hue)
        val cam = if (t.optBoolean("camera")) cams[ent] else null
        if (cam != null) {
            c.save(); c.clipPath(Path().apply { addRoundRect(x, y, x + w, y + h, rad, rad, Path.Direction.CW) })
            val sc = max(w / cam.width, h / cam.height)
            c.translate(x + (w - cam.width * sc) / 2, y + (h - cam.height * sc) / 2); c.scale(sc, sc)
            c.drawBitmap(cam, 0f, 0f, fill.apply { color = Color.WHITE }); c.restore()
        }
        badge(c, x + ip, y + ip, bsz, lit, hue, t.optString("icon"))
        val name = if (armed == "tile$i") "Tap again to confirm" else
            t.optString("name").ifEmpty { s?.optJSONObject("attributes")?.optString("friendly_name")?.ifEmpty { null } ?: ent ?: "" }
        val v = value(t, s, loaded); val tw = w - 2 * ip
        val vh = if (v.isNotEmpty()) textH(v, fv) else 0f
        val nh = textH(name, fn, true, tw, 2)
        val ny = y + h - ip - vh - nh
        if (cam != null) round(c, x + ip * 2 / 3, ny - 4, w - ip * 4 / 3, nh + vh + 8, rad / 2, rgba("#050505", 0.6f))
        c.save(); c.clipRect(x, y, x + w, y + h)
        text(c, name, fn, if (lit) Color.WHITE else rgba("#9e9e9e"), x + ip, ny, tw, 2, true)
        if (v.isNotEmpty()) text(c, v, fv, if (lit) rgba(hue) else rgba("#8a8a8e"), x + ip, y + h - ip - vh, tw)
        c.restore()
        if (armed == "tile$i") ring(c, x - 2, y - 2, w + 4, h + 4, rad + 2, 2f, rgba(ACCENT))
        if (missing) c.restore()
        hit(x, y, w, h) { tapTile(i) }
    }

    private fun drawCalendar(c: Canvas, x: Float, y: Float, w: Float, h: Float, k: Float) {
        val ip = 12 * k; val bsz = 32 * k; val rad = 16 * k
        glassTile(c, x, y, w, h, rad, false)
        badge(c, x + ip, y + ip, bsz, true, ACCENT, MDI.getValue("calendar"))
        text(c, "Calendar", 14 * k, Color.WHITE, x + ip + bsz + 8 * k, y + ip + (bsz - textH("Calendar", 14 * k, true)) / 2, bold = true)
        var cy = y + ip + bsz + 8 * k
        val list = cals.orEmpty()
        var cx = x + ip; val chipH = 18 * k; val fpx = 11 * k
        list.forEachIndexed { n, cal ->
            val id = cal.optString("entity_id"); val label = cal.optString("name").ifEmpty { id }
            val lw = textW(label, fpx, true) + 16 * k
            if (cx + lw > x + w - ip && cx > x + ip) { cx = x + ip; cy += chipH + 4 * k }
            val off = id in hidden; val tone = CAL_TONES[n % CAL_TONES.size]
            if (!off) round(c, cx, cy, lw, chipH, chipH / 2, rgba(tone, 0x55 / 255f))
            ring(c, cx, cy, lw, chipH, chipH / 2, 1f, if (!off) rgba(tone) else white(0.12f))
            text(c, label, fpx, if (!off) Color.WHITE else rgba("#8a8a8e"), cx + 8 * k, cy + (chipH - textH(label, fpx, true)) / 2, bold = true)
            hit(cx, cy, lw, chipH) { toggleCal(id) }
            cx += lw + 4 * k
        }
        cy += chipH + 8 * k
        val bx = x + ip; val bw = w - 2 * ip; val bh = y + h - ip - cy
        val rows = mutableListOf<Triple<String, String, Array<String>>>() // kind, text, (colour, time, summary)
        val evs = events
        when {
            evs == null -> rows.add(Triple("note", if (calFail) "Can't read the calendar, trying again" else "…", emptyArray()))
            evs.isEmpty() -> rows.add(Triple("note", "Nothing in the next two weeks.", emptyArray()))
            else -> {
                val today = LocalDate.now(); var last: String? = null
                val tone = list.withIndex().associate { (n, cal) -> cal.optString("entity_id") to CAL_TONES[n % CAL_TONES.size] }
                evs.forEach { e ->
                    val whenAt = eventStart(e); val d = dayLabel(whenAt.toLocalDate(), today)
                    if (d != last) { rows.add(Triple("head", d.uppercase(), emptyArray())); last = d }
                    val time = if (e.optJSONObject("start")?.has("dateTime") == true) whenAt.format(HM) else "All day"
                    rows.add(Triple("ev", "", arrayOf(tone[e.optString("calendar")] ?: ACCENT, time, e.optString("summary"))))
                }
            }
        }
        val lh = 16 * k; val fs = 12 * k; val tcol = 56 * k
        val content = rows.sumOf { (lh + if (it.first == "head") 4 * k else 0f).toDouble() }.toFloat()
        val off = scroller("calendar", bx, cy, bw, bh, content)
        c.save(); c.clipRect(bx, cy, bx + bw, cy + bh)
        var yy = cy - off
        rows.forEach { (kind, r, ev) ->
            when (kind) {
                "head" -> { yy += 4 * k; text(c, r, 11 * k, rgba("#8a8a8e"), bx, yy, bold = true) }
                "note" -> text(c, r, fs, rgba("#8a8a8e"), bx, yy, bw)
                else -> {
                    fill.color = rgba(ev[0]); c.drawCircle(bx + 4 * k, yy + lh / 2, 4 * k, fill)
                    text(c, ev[1], fs, rgba("#8a8a8e"), bx + 14 * k, yy + 1)
                    text(c, ev[2], fs, Color.WHITE, bx + 14 * k + tcol, yy + 1, bw - 14 * k - tcol)
                }
            }
            yy += lh
        }
        c.restore()
    }

    private fun drawSide(c: Canvas, sx: Float, sy: Float, sw: Float, sh: Float, k: Float) {
        val f1 = 12 * k + 1; val f2 = 10 * k + 1
        // the bottom row: the glass slider, and a quarter-width button back to Home
        val slh = 30 + 18 * k; val sly = sy + sh - slh; val bw = sw / 4; val bx = sx + sw - bw
        glassTile(c, bx, sly, bw, slh, 12 * k, true)
        val isz = (min(bw, slh) * 0.55f).roundToInt().toFloat()
        icon(c, MDI.getValue("back"), 24f, Color.WHITE, bx + (bw - isz) / 2, sly + (slh - isz) / 2, isz)
        hit(bx, sly, bw, slh, ::close)
        val slw = sw - bw - 5
        val n = servers.size; val gap = 5f
        val eh = (sly - gap - sy - (n - 1) * gap) / max(1, n)
        c.saveLayerAlpha(sx - 2, sy - 2, sx + sw + 2, sy + sh + 2, (fade() * 255).roundToInt())
        servers.forEachIndexed { i, sv ->
            val ey = sy + i * (eh + gap)
            val (info, lit) = serverInfo(sv, states, notes)
            glassTile(c, sx, ey, sw, eh, 12 * k, lit)
            val bs = min(eh - 8, 32 * k)
            badge(c, sx + 8, ey + (eh - bs) / 2, bs, lit, ACCENT, sv.optString("icon"))
            val tx = sx + 16 + bs; val tw = sw - (16 + bs) - 18
            val nm = sv.optString("name"); val nh = textH(nm, f1, true); val vh = textH(info, f2)
            val ty = ey + (eh - nh - vh) / 2
            text(c, nm, f1, if (lit) Color.WHITE else rgba("#9e9e9e"), tx, ty, tw, 1, true)
            text(c, info, f2, if (lit) rgba(ACCENT) else rgba("#8a8a8e"), tx, ty + nh, tw, 1)
            icon(c, MDI.getValue("chevron"), 24f, rgba("#8a8a8e"), sx + sw - 18, ey + (eh - 14) / 2, 14f)
            hit(sx, ey, sw, eh) { openPanel(sv) }
        }
        // the slider tile (outlined in orange, as on Windows)
        round(c, sx, sly, slw, slh, 12 * k, rgba("#3a3a3e", 0.55f * glass / 100))
        ring(c, sx, sly, slw, slh, 12 * k, 2f, rgba(ACCENT))
        val bs = 22 * k + 4
        round(c, sx + 8, sly + 6, bs, bs, bs / 4, rgba(ACCENT, 0.35f * glass / 100 + 0.2f))
        icon(c, MDI.getValue("drop"), 24f, Color.WHITE, sx + 8 + bs * 0.2f, sly + 6 + bs * 0.2f, (bs * 0.6f).roundToInt().toFloat())
        text(c, "Glass ↔ Solid", f1 - 1, Color.WHITE, sx + 14 + bs, sly + 6, slw - bs - 20, 1, true)
        text(c, "$glass%", f2, rgba(ACCENT), sx + 14 + bs, sly + 6 + textH("G", f1 - 1, true))
        val tx0 = sx + 12; val tx1 = sx + slw - 12; val ty = sly + slh - 10
        round(c, tx0, ty - 2, tx1 - tx0, 4f, 2f, white(0.25f))
        val px = tx0 + (tx1 - tx0) * (glass - 3) / 97f
        round(c, tx0, ty - 2, max(4f, px - tx0), 4f, 2f, rgba(ACCENT))
        c.drawCircle(px, ty, 7f, fill)
        slider = RectF(tx0, sly, tx1, sly + slh)
        c.restore()
    }

    private class Row(val h: Float, val draw: (Canvas, Float, Float, Float, Float) -> Unit, val tap: (() -> Unit)? = null, val cells: Pair<List<JSONObject>, Float>? = null)

    private fun drawPanel(c: Canvas, w: Float, h: Float) {
        val sv = panel ?: return
        frames.clear(); screenBox = null
        hit(0f, 0f, w, h) { panel = null; view.invalidate() } // a tap outside the panel closes it
        val kind = sv.optString("kind"); val screens = sv.has("screens")
        val pw = min(w * (if (screens || kind == "security") 0.62f else 0.5f), 560f)
        val px = w - pw; val pad = 16f
        fill.color = rgba("#050505", 0.9f); c.drawRect(px, 0f, w, h, fill)
        hit(px, 0f, pw, h) {}
        val data = pdata[sv.optString("name")]
        val apps = sv.optJSONArray("apps").objs()
        val count = when {
            kind.isEmpty() && !screens -> "${apps.count { states[it.optString("entity")]?.optString("state") == "on" }}/${apps.size} ${if (sv.optBoolean("readonly")) "online" else "running"}"
            kind == "notifications" && data != null -> ((data["items"] as? List<*>)?.size ?: 0).toString()
            kind == "notes" -> notes[sv.optString("folder")]?.toString() ?: ""
            else -> ""
        }
        val (tw, th) = text(c, sv.optString("name"), 20f, Color.WHITE, px + pad, pad, bold = true)
        if (count.isNotEmpty()) text(c, count, 13f, rgba("#8a8a8e"), px + pad + tw + 10, pad + th - 17)
        icon(c, MDI.getValue("close"), 24f, rgba("#8a8a8e"), w - pad - 20, pad + 2, 20f)
        hit(w - pad - 34, pad - 8, 40f, 40f) { panel = null; view.invalidate() }
        val bx = px + pad; val by = pad + th + 12; val bw = pw - 2 * pad; val bh = h - by - pad
        val rows = mutableListOf<Row>()
        fun head(label: String) = rows.add(Row(22f, { cv, x, y, _, _ -> text(cv, label.uppercase(), 11f, rgba("#8a8a8e"), x, y + 6, bold = true) }))
        fun note(label: String) = rows.add(Row(36f, { cv, x, y, ww, _ -> text(cv, label, 13f, rgba("#8a8a8e"), x, y + 2, ww, 2) }))
        when {
            kind == "security" -> {
                val al = states[sv.optString("alarm")]
                rows.add(Row(48f, { cv, x, y, ww, hh ->
                    glassTile(cv, x, y, ww, hh, 14f, true, RED)
                    icon(cv, sv.optString("icon"), 24f, Color.WHITE, x + 12, y + (hh - 22) / 2, 22f)
                    text(cv, al?.optJSONObject("attributes")?.optString("friendly_name")?.ifEmpty { null } ?: "Alarm", 14f, Color.WHITE, x + 44, y + (hh - 17) / 2, ww - 140, 1, true)
                    val v = al?.optString("state") ?: "…"
                    text(cv, v, 14f, rgba(RED), x + ww - 12 - textW(v, 14f), y + (hh - 17) / 2)
                }))
                head("Cameras")
                val cams = sv.optJSONArray("cameras").objs(); val cw = (bw - 8) / 2
                cams.chunked(2).forEach { pair ->
                    rows.add(Row(cw * 9 / 16, { cv, x, y, _, hh ->
                        pair.forEachIndexed { m, cam ->
                            val cx = x + m * (cw + 8)
                            round(cv, cx, y, cw, hh, 14f, Color.BLACK)
                            val img = data?.get(cam.optString("entity")) as? Bitmap
                            if (img != null) {
                                cv.save(); cv.clipPath(Path().apply { addRoundRect(cx, y, cx + cw, y + hh, 14f, 14f, Path.Direction.CW) })
                                val sc = max(cw / img.width, hh / img.height)
                                cv.translate(cx + (cw - img.width * sc) / 2, y + (hh - img.height * sc) / 2); cv.scale(sc, sc)
                                cv.drawBitmap(img, 0f, 0f, fill.apply { color = Color.WHITE }); cv.restore()
                            } else text(cv, "No picture", 12f, rgba("#8a8a8e"), cx + cw / 2 - 30, y + hh / 2 - 7)
                            val nm = cam.optString("name")
                            round(cv, cx + 6, y + hh - 26, textW(nm, 12f, true) + 14, 20f, 8f, rgba("#050505", 0.7f))
                            text(cv, nm, 12f, Color.WHITE, cx + 13, y + hh - 24, bold = true)
                        }
                    }))
                }
            }
            kind == "lists" -> sv.optJSONArray("lists").objs().forEach { l ->
                @Suppress("UNCHECKED_CAST") val items = data?.get(l.optString("entity")) as? List<JSONObject>
                head("${l.optString("name").ifEmpty { l.optString("entity") }} · ${items?.size ?: "…"}")
                if (items?.isEmpty() == true) note("Nothing pending.")
                items.orEmpty().take(l.optInt("max", 50)).forEach { item ->
                    rows.add(Row(38f, { cv, x, y, ww, hh ->
                        glassTile(cv, x, y, ww, hh, 14f, true)
                        ring(cv, x + 12, y + (hh - 18) / 2, 18f, 18f, 5f, 2f, rgba(ACCENT))
                        text(cv, item.optString("summary"), 13f, Color.WHITE, x + 42, y + (hh - 16) / 2, ww - 54)
                    }, { todoDone(l.optString("entity"), item.optString("uid")) }))
                }
            }
            kind == "notifications" -> {
                @Suppress("UNCHECKED_CAST") (data?.get("ha") as? List<JSONObject>).orEmpty().forEach { nt ->
                    rows.add(Row(58f, { cv, x, y, ww, hh ->
                        glassTile(cv, x, y, ww, hh, 14f, true)
                        text(cv, nt.optString("title").ifEmpty { "Home Assistant" }, 13f, Color.WHITE, x + 12, y + 7, ww - 50, 1, true)
                        text(cv, nt.optString("message"), 11f, rgba("#8a8a8e"), x + 12, y + 25, ww - 50, 2)
                        icon(cv, MDI.getValue("close"), 24f, rgba("#8a8a8e"), x + ww - 26, y + 10, 14f)
                    }, { call("persistent_notification", "dismiss", JSONObject().put("notification_id", nt.optString("notification_id"))) { loadPanel() } }))
                }
                @Suppress("UNCHECKED_CAST") val items = data?.get("items") as? List<JSONObject>
                if (items == null) note("…")
                items.orEmpty().forEach { item ->
                    val desc = cleanDesc(item.optString("description"))
                    rows.add(Row(if (desc.isNotEmpty()) 58f else 34f, { cv, x, y, ww, hh ->
                        glassTile(cv, x, y, ww, hh, 14f, false)
                        text(cv, item.optString("summary"), 13f, Color.WHITE, x + 12, y + 7, ww - 50, 1, true)
                        if (desc.isNotEmpty()) text(cv, desc, 11f, rgba("#8a8a8e"), x + 12, y + 25, ww - 50, 2)
                        icon(cv, MDI.getValue("check"), 24f, rgba("#8a8a8e"), x + ww - 26, y + 10, 14f)
                    }, { todoDone(sv.optString("list"), item.optString("uid")) }))
                }
            }
            kind == "notes" -> {
                val tree = trees[sv.optString("folder")]
                if (vault() == null) {
                    note("To list the notes in Documents/$VAULT_NAME, allow Joint Launcher All files access.")
                    rows.add(Row(42f, { cv, x, y, ww, hh ->
                        glassTile(cv, x, y, ww, hh, 14f, true)
                        text(cv, "Allow All files access", 13f, Color.WHITE, x + 12, y + (hh - 16) / 2, ww - 24, 1, true)
                    }, { runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName))) } }))
                } else if (tree == null) note("…")
                fun treeRows(nodes: List<Note>, depth: Int) { nodes.forEach { nd ->
                    val folder = nd.children != null
                    rows.add(Row(30f, { cv, x, y, ww, hh ->
                        val ix = x + 8 + depth * 16
                        if (folder) {
                            icon(cv, MDI.getValue(if (nd.path in openFolders) "down" else "chevron"), 24f, rgba(ACCENT), ix, y + (hh - 14) / 2, 14f)
                            val n = countNotes(nd.children!!).toString()
                            text(cv, n, 12f, rgba("#8a8a8e"), x + ww - 8 - textW(n, 12f), y + (hh - 15) / 2)
                        }
                        text(cv, nd.name, 13f, if (folder) Color.WHITE else rgba("#c9cbd3"), ix + 20, y + (hh - 16) / 2, ww - (ix + 20 - x) - 40, 1, folder)
                    }, if (folder) ({ if (!openFolders.remove(nd.path)) openFolders.add(nd.path); view.invalidate() }) else ({ openNote(nd.path) })))
                    if (folder && nd.path in openFolders) treeRows(nd.children!!, depth + 1)
                } }
                treeRows(tree.orEmpty(), 0)
            }
            screens -> {
                // the sliders: ADB Control (or Stream Control) itself for each device
                val cw3 = (bw - 16) / 3
                apps.chunked(3).forEach { trio ->
                    rows.add(Row(42f, { cv, x, y, _, hh ->
                        trio.forEachIndexed { m, ap ->
                            val on = states[ap.optString("entity")]?.optString("state") == "on"
                            val cx = x + m * (cw3 + 8)
                            glassTile(cv, cx, y, cw3, hh, 14f, on)
                            text(cv, ap.optString("name").ifEmpty { ap.optString("entity") }, 13f, if (on) Color.WHITE else rgba("#9e9e9e"), cx + 10, y + (hh - 16) / 2, cw3 - 62, 1, true)
                            val kx = cx + cw3 - 46; val ky = y + (hh - 20) / 2
                            round(cv, kx, ky, 36f, 20f, 10f, if (on) rgba(ACCENT) else white(0.2f))
                            fill.color = Color.WHITE; cv.drawCircle(kx + if (on) 26 else 10, ky + 10, 8f, fill)
                        }
                    }, cells = trio to cw3))
                }
                // the chips: each device's screen slider in HA (orange = on, its screen shows below); a tap flips it
                val stream = sv.optString("screens") == "Stream"
                @Suppress("UNCHECKED_CAST") val devs = data?.get("devs") as? List<JSONObject>
                fun screen(ap: JSONObject) = screenSwitch(ap.optString("entity"), stream)
                fun on(ap: JSONObject) = states[screen(ap)]?.optString("state") == "on"
                fun dev(ap: JSONObject) = devs?.firstOrNull { it.optString("name") == ap.optString("name") }
                val chips = apps.filter { states.containsKey(screen(it)) }
                fun label(ap: JSONObject) = ap.optString("name").ifEmpty { ap.optString("entity") } + (dev(ap)?.takeIf { it.has("battery") }?.let { "  ${it.optString("battery")}%" } ?: "")
                val lines = mutableListOf(mutableListOf<Pair<JSONObject, Float>>()); var lw = 0f
                chips.forEach { ap -> val cw = textW(label(ap), 13f, true) + 28
                    if (lw + cw > bw && lines.last().isNotEmpty()) { lines.add(mutableListOf()); lw = 0f }
                    lines.last().add(ap to cw); lw += cw + 8 }
                lines.forEach { line -> rows.add(Row(34f, { cv, x, y, _, hh ->
                    var cx = x
                    line.forEach { (ap, cw) ->
                        val lit = on(ap)
                        round(cv, cx, y, cw, hh, hh / 2, if (lit) rgba(ACCENT) else white(0.12f))
                        text(cv, label(ap), 13f, if (lit) Color.BLACK else rgba("#c9cbd3"), cx + 14, y + (hh - 16) / 2, bold = true)
                        hit(cx, y, cw, hh) { call("switch", "toggle", JSONObject().put("entity_id", screen(ap))) { loadPanel() } }
                        cx += cw + 8
                    }
                })) }
                when {
                    BuildConfig.ADB_API.isEmpty() -> note("No ADB Control address in this build (adb.api in local.properties).")
                    data?.get("devErr") == true -> note("ADB Control isn't reachable (only from home for now).")
                    devs == null -> note("…")
                }
                // the screens switched on (not this device's own: devs leaves it out), each framed to its picture:
                // one sideways device per row, two upright ones side by side
                val shown = chips.filter(::on).mapNotNull(::dev).mapNotNull { dv ->
                    (if (stream) dv.optString("player") else scrcpyLink(BuildConfig.ADB_SCRCPY, dv.optString("ip"))).takeIf { it.isNotEmpty() }?.let { dv to it }
                }
                val bar = if (stream) 0f else SCRCPY_BAR
                screenRows(shown.map { (dv, _) -> dv.optDouble("aspect", 16 / 9.0).toFloat() to dv.optBoolean("landscape") }, bw, bar).forEach { row ->
                    rows.add(Row(row.maxOf { it.third }, { cv, x, y, _, _ ->
                        var fx = x
                        row.forEach { (k, fw, fh) ->
                            round(cv, fx, y, fw, fh, 12f, Color.BLACK); frames.add(RectF(fx, y, fx + fw, y + fh) to shown[k].second)
                            fx += fw + 8
                        }
                    }))
                }
            }
            else -> apps.forEach { ap ->
                rows.add(Row(48f, { cv, x, y, ww, hh ->
                    val s = states[ap.optString("entity")]; val on = s?.optString("state") == "on"
                    val key = "app" + ap.optString("entity")
                    glassTile(cv, x, y, ww, hh, 14f, on)
                    if (armed == key) ring(cv, x - 2, y - 2, ww + 4, hh + 4, 16f, 2f, rgba(ACCENT))
                    if (ap.has("svg")) badge(cv, x + 10, y + (hh - 34) / 2, 34f, on, ACCENT, ap.optString("svg"), 256f)
                    else badge(cv, x + 10, y + (hh - 34) / 2, 34f, on, ACCENT, ap.optString("icon"))
                    val nm = if (armed == key) ap.optString("confirm") else ap.optString("name").ifEmpty {
                        (s?.optJSONObject("attributes")?.optString("friendly_name")?.ifEmpty { null } ?: ap.optString("entity")).removeSuffix(" Contenedor").removeSuffix(" contenedor")
                    }
                    val stt = when { s == null -> "…"; s.optString("state") == "unavailable" -> "Unavailable"
                        sv.optBoolean("readonly") -> if (on) "Online" else "Offline"; else -> if (on) "Running" else "Stopped" }
                    text(cv, nm, 13f, if (on) Color.WHITE else rgba("#9e9e9e"), x + 54, y + 8, ww - 64, 1, true)
                    text(cv, stt, 11f, if (on) rgba(ACCENT) else rgba("#8a8a8e"), x + 54, y + 26, ww - 64)
                }, { tapApp(sv, ap) }))
            }
        }
        val gap = 8f
        val off = scroller("panel", bx, by, bw, bh, rows.sumOf { (it.h + gap).toDouble() }.toFloat())
        c.save(); c.clipRect(bx, by, bx + bw, by + bh)
        var yy = by - off
        rows.forEach { r ->
            if (yy + r.h >= by && yy <= by + bh) {
                r.draw(c, bx, yy, bw, r.h)
                val top = max(yy, by); val bottom = min(yy + r.h, by + bh)
                r.cells?.let { (trio, cw) -> trio.forEachIndexed { m, ap -> hit(bx + m * (cw + 8), top, cw, bottom - top) { call("switch", "toggle", JSONObject().put("entity_id", ap.optString("entity"))) } } }
                    ?: r.tap?.let { hit(bx, top, bw, bottom - top, it) }
            }
            yy += r.h + gap
        }
        c.restore()
        // the screen sits where its row was drawn, cut to what's visible of the panel
        screenBox = RectF(bx, by, bx + bw, by + bh); placeScreens(frames.toList(), screenBox)
    }

    inner class DashView : View(a) {
        init { visibility = GONE }
        private val d get() = resources.displayMetrics.density
        private var press: Pair<Float, Float>? = null
        private var drag: Pair<String, Float>? = null
        private var sliding = false

        override fun onDraw(c: Canvas) {
            val w = width / d; val h = height / d
            hits.clear(); scrolls.clear()
            c.save(); c.scale(d, d)
            c.drawColor(rgba("#050505", glass * 0.006f)) // the dim layer the slider thickens
            if (tiles.isEmpty()) {
                text(c, "No dashboard: this build has no assets/overlay.json (the desktop dashboard's file).", 16f, rgba("#8a8a8e"), 24f, 24f, w - 48)
                hit(0f, 0f, w, h, ::close)
                c.restore(); return
            }
            val sizes = tiles.map { t -> t.optInt("w", if (t.has("calendar")) 4 else 1) to t.optInt("h", if (t.has("calendar")) 2 else 1) }
            val (boxes, side, k) = fit(sizes, w, h)
            c.saveLayerAlpha(0f, 0f, w, h, (fade() * 255).roundToInt())
            tiles.forEachIndexed { i, t ->
                val b = boxes[i]
                if (t.has("calendar")) drawCalendar(c, b[0], b[1], b[2], b[3], k) else drawTile(c, i, t, b[0], b[1], b[2], b[3], k)
            }
            c.restore()
            drawSide(c, side[0], side[1], side[2], side[3], k)
            if (msg.isNotEmpty()) { // a message (a confirmation's text, an error) floats over the top of the grid
                val mw = textW(msg, 13f, true) + 28; val mh = 28f; val mx = (side[0] - mw) / 2
                round(c, mx, PAD + 4, mw, mh, mh / 2, rgba("#050505", 0.85f))
                ring(c, mx, PAD + 4, mw, mh, mh / 2, 1.5f, rgba(ACCENT))
                text(c, msg, 13f, rgba(ACCENT), mx + 14, PAD + 4 + (mh - textH(msg, 13f, true)) / 2, bold = true)
            }
            if (panel != null) drawPanel(c, w, h) else if (webs.isNotEmpty()) dropScreen() // posted, not during the draw
            c.restore()
        }

        private fun findScroll(x: Float, y: Float) = scrolls.lastOrNull { (r, key) -> r.contains(x, y) && (key == "panel" || panel == null) }?.second
        private fun inSlider(x: Float, y: Float) = slider?.let { s -> panel == null && x >= s.left - 10 && x <= s.right + 10 && y >= s.top && y <= s.bottom } == true
        private fun setGlass(x: Float) { val s = slider ?: return; glass = (3 + (x - s.left) / s.width() * 97).toInt().coerceIn(3, 100); invalidate() }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x / d; val y = e.y / d
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { press = x to y; drag = null; if (inSlider(x, y)) { sliding = true; setGlass(x) } }
                MotionEvent.ACTION_MOVE -> {
                    val p = press ?: return true
                    if (sliding) { setGlass(x); return true }
                    if (drag == null && abs(y - p.second) > 10) drag = findScroll(p.first, p.second)?.let { it to (scroll[it] ?: 0f) } ?: ("none" to 0f)
                    drag?.takeIf { it.first != "none" }?.let { (key, b) -> scroll[key] = (b - (y - p.second)).coerceIn(0f, smax[key] ?: 0f); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    val p = press; press = null
                    if (sliding) { sliding = false; prefs.edit().putInt("dash_glass", glass).apply(); return true }
                    if (drag != null || p == null || abs(x - p.first) > 10 || abs(y - p.second) > 10) { drag = null; return true }
                    hits.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> { press = null; drag = null; sliding = false }
            }
            return true
        }
    }

    companion object {
        const val COLS = 10; const val GAP = 8f; const val PAD = 10f // the Windows grid: 10 columns, dense; cells stretch to fit
        const val SIDE = 250f / 140f // the side column's width in cells (250 px next to 140 px cells on Windows)
        const val COL_GAP = 12f
        const val POLL = 10; const val CAM_POLL = 5; const val CAL_POLL = 300 // seconds, only while the page is showing
        val TAP = mapOf("light" to "toggle", "switch" to "toggle", "input_boolean" to "toggle", "fan" to "toggle", "cover" to "toggle",
            "scene" to "turn_on", "script" to "turn_on", "button" to "press")
        val TOGGLES = setOf("light", "switch", "input_boolean", "fan", "cover", "lock")
        val TONES = mapOf("blue" to "#29B6F6", "purple" to "#AB47BC", "amber" to "#FFC107", "green" to "#66BB6A", "red" to "#f44336", "yellow" to "#FFEB3B",
            "cyan" to "#26C6DA", "indigo" to "#7E57C2", "darkblue" to "#1E66D0", "white" to "#F2F2F2", "teal" to "#26A69A")
        val CAL_TONES = listOf("#E8650A", "#29B6F6", "#AB47BC", "#66BB6A", "#FFC107", "#f44336", "#26C6DA", "#EC407A")
        const val ACCENT = "#E8650A"; const val GROUP = "#B34700"; const val RED = "#f44336"
        // tiles that drive Windows-only things (voice assistant, another Claude session, projects, dock apps) keep their
        // place and show their name and value, but a tap does nothing here
        val SKIP = listOf("assist", "relay", "projects", "app")
        val MDI = mapOf(
            "back" to "M20,11V13H8L13.5,18.5L12.08,19.92L4.16,12L12.08,4.08L13.5,5.5L8,11H20Z",
            "calendar" to "M19,19H5V8H19M16,1V3H8V1H6V3H5C3.89,3 3,3.89 3,5V19A2,2 0 0,0 5,21H19A2,2 0 0,0 21,19V5C21,3.89 20.1,3 19,3H18V1M17,12H12V17H17V12Z",
            "drop" to "M12,20A6,6 0 0,1 6,14C6,10 12,3.25 12,3.25C12,3.25 18,10 18,14A6,6 0 0,1 12,20Z",
            "chevron" to "M8.59,16.58L13.17,12L8.59,7.41L10,6L16,12L10,18L8.59,16.58Z",
            "check" to "M21,7L9,19L3.5,13.5L4.91,12.09L9,16.17L19.59,5.59L21,7Z",
            "down" to "M7.41,8.58L12,13.17L16.59,8.58L18,10L12,16L6,10L7.41,8.58Z",
            "close" to "M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12L19,6.41Z",
        )
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        const val VAULT_NAME = "SecondMemory" // the notes vault: Documents/<this>, also its name in Obsidian

        /** Places tiles of (w, h) cells left to right, top to bottom, each in the first free spot (CSS grid "dense"). */
        fun pack(sizes: List<Pair<Int, Int>>, cols: Int): List<IntArray> {
            val used = mutableSetOf<Pair<Int, Int>>()
            return sizes.map { (w0, h) ->
                val w = min(w0, cols); var r = 0; var spot: Int?
                while (true) {
                    spot = (0..cols - w).firstOrNull { c -> (0 until h).all { i -> (0 until w).all { j -> (r + i to c + j) !in used } } }
                    if (spot != null) break
                    r++
                }
                for (i in 0 until h) for (j in 0 until w) used.add(r + i to spot!! + j)
                intArrayOf(r, spot!!, w, h)
            }
        }

        /** The tile boxes, the side column's box (each x, y, w, h) and a scale for what's inside a tile (1 = ~118 px cells). */
        fun fit(sizes: List<Pair<Int, Int>>, width: Float, height: Float): Triple<List<FloatArray>, FloatArray, Float> {
            val spots = pack(sizes, COLS)
            val rows = spots.maxOfOrNull { it[0] + it[3] } ?: 1
            val cw = (width - 2 * PAD - (COLS - 1) * GAP - COL_GAP) / (COLS + SIDE)
            val ch = (height - 2 * PAD - (rows - 1) * GAP) / rows
            val boxes = spots.map { (r, c, w, h) -> floatArrayOf(PAD + c * (cw + GAP), PAD + r * (ch + GAP), w * cw + (w - 1) * GAP, h * ch + (h - 1) * GAP) }
            val sx = PAD + COLS * cw + (COLS - 1) * GAP + COL_GAP
            return Triple(boxes, floatArrayOf(sx, PAD, width - PAD - sx, height - 2 * PAD), max(0.6f, min(1f, min(cw, ch) / 118)))
        }

        fun isOn(s: JSONObject?) = s?.optString("state") in setOf("on", "open", "unlocked", "heat", "cool", "playing")

        /** The small line under the name, as the Windows tile shows it. */
        fun value(t: JSONObject, s: JSONObject?, loaded: Boolean): String {
            val ent = t.optString("entity"); val domain = ent.substringBefore('.')
            if (t.optBoolean("camera") || ent.isEmpty()) return ""
            if (s == null) return if (loaded) "Unavailable" else "…"
            val state = s.optString("state")
            return when {
                state == "unavailable" -> "Unavailable"
                domain == "todo" -> "$state pending"
                domain in setOf("sensor", "climate", "alarm_control_panel") ->
                    s.optJSONObject("attributes")?.optString("unit_of_measurement")?.ifEmpty { null }?.let { "$state $it" } ?: state
                domain in setOf("scene", "script", "button") -> ""
                else -> if (isOn(s)) "On" else "Off"
            }
        }

        /** A side-column entry's second line and whether it's lit, as Windows shows it. */
        fun serverInfo(sv: JSONObject, states: Map<String, JSONObject>, notes: Map<String, Int>?): Pair<String, Boolean> {
            fun num(e: String) = states[e]?.optString("state")?.toDoubleOrNull() ?: 0.0
            return when (sv.optString("kind")) {
                "security" -> (states[sv.optString("alarm")]?.optString("state")?.ifEmpty { null } ?: "—") to true
                "lists" -> sv.optJSONArray("lists").objs().sumOf { num(it.optString("entity")) }.toInt().let { "$it pending" to (it > 0) }
                "notifications" -> num(sv.optString("list")).toInt().let { "$it unread" to (it > 0) }
                "notes" -> notes?.get(sv.optString("folder"))?.let { "$it notes" to true } ?: ("…" to false)
                else -> {
                    val apps = sv.optJSONArray("apps").objs()
                    if (states.isEmpty()) return "…" to false
                    val n = apps.count { states[it.optString("entity")]?.optString("state") == "on" }
                    "$n/${apps.size} ${if (sv.has("screens")) "on" else if (sv.optBoolean("readonly")) "online" else "running"}" to (n > 0)
                }
            }
        }

        fun countNotes(tree: List<Note>): Int = tree.sumOf { it.children?.let(::countNotes) ?: 1 }

        fun dayLabel(d: LocalDate, today: LocalDate): String = when (java.time.temporal.ChronoUnit.DAYS.between(today, d)) {
            0L -> "Today"; 1L -> "Tomorrow"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }

        private fun whenAt(e: JSONObject, key: String): ZonedDateTime {
            val s = e.optJSONObject(key) ?: JSONObject()
            return s.optString("dateTime").ifEmpty { null }?.let { OffsetDateTime.parse(it.replace("Z", "+00:00")).atZoneSameInstant(ZoneId.systemDefault()) }
                ?: LocalDate.parse(s.optString("date")).atStartOfDay(ZoneId.systemDefault())
        }
        fun eventStart(e: JSONObject) = whenAt(e, "start")
        fun eventEnd(e: JSONObject) = whenAt(e, "end")

        /** A notification's description without its "(id: ...)" tag and bold marks. */
        fun cleanDesc(d: String): String {
            var s = d.replace("**", "")
            val a0 = s.indexOf("(id: "); val a1 = if (a0 >= 0) s.indexOf(')', a0) else -1
            if (a1 >= 0) s = s.substring(0, a0) + s.substring(a1 + 1)
            return s.trim()
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        const val SCRCPY_BAR = 52f // ws-scrcpy's button column at the frame's right (dp)

        /** A device's screen slider next to its ADB / Stream Control switch: adb_xperia → pantalla_xperia, stream_tv_control → stream_tv_video. */
        fun screenSwitch(entity: String, stream: Boolean) =
            if (stream) entity.replace(Regex("_control$"), "_video") else entity.replace("switch.adb_", "switch.pantalla_")

        /**
         * Screens laid out in a [w]-wide panel: one sideways screen per row at full width, upright ones two to a row, each
         * frame its picture's shape plus [bar] for ws-scrcpy's buttons. Rows of (index, width, height).
         */
        fun screenRows(screens: List<Pair<Float, Boolean>>, w: Float, bar: Float): List<List<Triple<Int, Float, Float>>> {
            val half = (w - 8) / 2
            fun frame(k: Int, fw: Float) = screens[k].let { (aspect, landscape) -> val a = max(1f, aspect)
                Triple(k, fw, (fw - bar) * if (landscape) 1 / a else a) }
            val out = mutableListOf<List<Triple<Int, Float, Float>>>(); var upright: Int? = null
            screens.indices.forEach { k ->
                if (screens[k].second) out.add(listOf(frame(k, w)))
                else upright?.let { out.add(listOf(frame(it, half), frame(k, half))); upright = null } ?: run { upright = k }
            }
            upright?.let { out.add(listOf(frame(it, half))) }
            return out
        }

        /** ws-scrcpy's page for one device: the same address ha-adb-control's /view and the desktop's Screens build. */
        fun scrcpyLink(scrcpy: String, ip: String): String {
            val udid = "$ip:5555"
            val ws = scrcpy.replaceFirst("http", "ws") + "?action=proxy-adb&remote=tcp%3A8886&udid=" + enc(udid)
            return scrcpy + "#!" + listOf("action" to "stream", "udid" to udid, "player" to "mse", "ws" to ws, "fitToScreen" to "true")
                .joinToString("&") { (k, v) -> "$k=" + enc(v) }
        }
        private fun Float?.orZero() = this ?: 0f
        internal fun JSONArray?.objs(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    }
}
