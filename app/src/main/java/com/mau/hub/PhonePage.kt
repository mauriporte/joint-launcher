package com.mau.hub

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * The page you open on your phone (QR code on the TV) to sign in without typing on the TV. Served by the TV itself on
 * your home network, only while the Connect screen is open, at a random path so nothing else on the network can
 * guess it. Passwords pass through once to sign in and are never stored.
 *
 * onGoogleCode shows Google's TV code on the TV too; onChange refreshes the TV's list of connected accounts.
 */
class PhonePage(
    private val hub: Hub,
    private val host: String, // the TV's address on the network, e.g. 192.168.1.50
    private val onGoogleCode: (SignIn.DeviceCode?) -> Unit,
    private val onChange: () -> Unit,
) {
    val token = SignIn.random(6)
    private var server: ServerSocket? = null
    private var spotifyVerifier: String? = null
    private val nonce = SignIn.random(8)
    @Volatile private var googleMsg: String? = null
    val url get() = "http://$host:${server?.localPort}/$token"

    fun start() {
        val s = ServerSocket(PORT).also { server = it }
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { c.use(::handle) } }.start()
            }
        }.start()
    }

    fun stop() { runCatching { server?.close() }; googleRunning = false }

    // ---- HTTP ----

    private fun handle(c: Socket) {
        c.soTimeout = 15000
        val input = BufferedReader(InputStreamReader(c.getInputStream()))
        val (method, target) = (input.readLine() ?: return).split(" ").let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (method == "POST" && length in 1..20_000) CharArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }.concatToString() else ""
        val path = target.substringBefore('?')
        val params = parse(target.substringAfter('?', "")) + parse(body)
        val out = c.getOutputStream()
        if (!path.startsWith("/$token")) return send(out, 404, page("Not found", "<p>Open the address shown on the TV.</p>"))
        when (path.removePrefix("/$token")) {
            "", "/" -> send(out, 200, home())
            "/jellyfin" -> {
                params["server"]?.trim()?.takeIf { it.startsWith("http") }?.let { hub.prefs.edit().putString("server", it.trimEnd('/')).remove("uid").apply() }
                send(out, 200, done("Jellyfin server saved. Now press <b>Connect Jellyfin</b> on the TV to sign in with Quick Connect."))
            }
            "/jellyseerr" -> send(out, 200, result {
                val base = params["url"].orEmpty().trim().trimEnd('/').also { require(it.startsWith("http")) { "the address must start with http:// or https://" } }
                val key = params["apikey"].orEmpty().trim().ifEmpty { SignIn.seerrApiKey(base, params["user"].orEmpty(), params["password"].orEmpty()) }
                val name = SignIn.seerrCheck(base, key)
                hub.prefs.edit().putString("seerr_url", base).putString("seerr_key", key).apply()
                "Jellyseerr connected as $name."
            })
            "/stremio" -> send(out, 200, result {
                hub.prefs.edit().putString("stremio", Sources.stremioLogin(params["email"].orEmpty().trim(), params["password"].orEmpty())).apply()
                "Stremio connected."
            })
            "/spotify" -> {
                val id = params["client_id"].orEmpty().trim()
                if (id.isEmpty()) return send(out, 200, done("Enter your Spotify app's Client ID first.", ok = false))
                hub.prefs.edit().putString("spotify_client_id", id).apply()
                val verifier = SignIn.random(48).also { spotifyVerifier = it }
                // the relay page reads the TV's address from state to send the login back here
                redirect(out, SignIn.spotifyAuthorizeUrl(id, verifier, "$host:${server?.localPort}/$token/$nonce"))
            }
            "/spotify-back" -> send(out, 200, result {
                require(params["state"]?.endsWith("/$nonce") == true) { "this sign-in didn't start from this TV page; start again" }
                params["error"]?.let { error("Spotify said: $it") }
                val refresh = SignIn.spotifyExchange(hub.prefs.getString("spotify_client_id", "")!!, params["code"].orEmpty(), spotifyVerifier ?: error("start again from the TV page"))
                hub.prefs.edit().putString("spotify_refresh", refresh).apply()
                "Spotify connected."
            })
            "/youtube" -> {
                val id = params["client_id"].orEmpty().trim(); val secret = params["secret"].orEmpty().trim()
                if (id.isEmpty() || secret.isEmpty()) return send(out, 200, done("Enter the TV client's ID and secret first.", ok = false))
                hub.prefs.edit().putString("google_client_id", id).putString("google_client_secret", secret).apply()
                send(out, 200, result {
                    val d = SignIn.googleStart(id)
                    pollGoogle(id, secret, d)
                    "Now open <a href=\"https://${d.url.removePrefix("https://")}\">${d.url.removePrefix("https://")}</a> and enter <b style=\"font-size:1.6em\">${d.userCode}</b><br>(it's on the TV too). Come back here or look at the TV once you've approved."
                })
            }
            "/homeassistant" -> {
                val t = params["token"].orEmpty().trim()
                if (t.isEmpty()) return send(out, 200, done("Paste the token first.", ok = false))
                hub.prefs.edit().putString("ha_token", t).apply()
                HaWidget.refresh(hub.ctx)
                send(out, 200, done("Home Assistant token saved. The MauHome widget refreshes now."))
            }
            // the same accounts, for setting up another device (tablet) from this one; same random path, only while the screen is open
            "/export" -> {
                val form = (ACCOUNT_KEYS.mapNotNull { k -> hub.prefs.getString(k, null)?.let { k to it } } +
                    IMPORT_SETS.mapNotNull { k -> hub.prefs.getStringSet(k, null)?.let { k to it.joinToString("\n") } } +
                    ("icon_labels" to IconPacks(hub).exportPicks())) // this device's app icons by app name, for the other device
                    .joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }.toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(form); out.flush()
            }
            "/import" -> send(out, 200, result {
                // moving from the old Joint Launcher: its logins and orders, sent once from the PC (README); only these keys
                val e = hub.prefs.edit()
                IMPORT_KEYS.forEach { k -> params[k]?.takeIf { it.isNotEmpty() }?.let { e.putString(k, it) } }
                IMPORT_SETS.forEach { k -> params[k]?.let { v -> e.putStringSet(k, v.split('\n').filter(String::isNotEmpty).toSet()) } }
                // app icons picked from an icon pack ("icon_<package>" = "<pack>|<icon>"), the same thing Change icon saves
                params.filter { (k, v) -> k.startsWith("icon_") && k != "icon_labels" && ICON_PICK.matches(v) }.forEach { (k, v) -> e.putString(k, v) }
                // the other device's icons by app name (IconPacks.exportPicks): kept only if every entry is a pack|icon pick
                params["icon_labels"]?.let { v ->
                    runCatching { org.json.JSONObject(v) }.getOrNull()
                        ?.takeIf { o -> o.keys().asSequence().all { ICON_PICK.matches(o.optString(it)) } }
                        ?.let { e.putString("icon_labels", v) }
                }
                e.apply()
                hub.userId = null
                "Imported ${params.keys.count { it in IMPORT_KEYS || it in IMPORT_SETS || it.startsWith("icon_") }} settings."
            })
            else -> send(out, 404, page("Not found", ""))
        }
    }

    @Volatile private var googleRunning = false

    /** Waits for you to approve the code on google.com/device, then saves the login. */
    private fun pollGoogle(id: String, secret: String, d: SignIn.DeviceCode) {
        googleRunning = false
        hub.main.post { onGoogleCode(d) }
        Thread {
            googleRunning = true
            val r = runCatching {
                var refresh: String? = null
                while (googleRunning && refresh == null && System.currentTimeMillis() < d.expiresAt) {
                    Thread.sleep(d.intervalS * 1000L)
                    refresh = SignIn.googlePoll(id, secret, d)
                }
                refresh
            }
            googleRunning = false
            r.onSuccess { refresh -> refresh?.let { hub.prefs.edit().putString("google_refresh", it).remove("yt_subs").apply(); googleMsg = "YouTube connected." } }
                .onFailure { googleMsg = "YouTube sign-in failed: ${it.message}" }
            hub.main.post { onGoogleCode(null); onChange() }
        }.start()
    }

    private fun result(action: () -> String) = runCatching(action).fold(
        onSuccess = { hub.main.post(onChange); done(it) },
        onFailure = { done("Didn't work: ${esc(Sources.explain(it))}", ok = false) },
    )

    private fun parse(q: String) = q.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun send(out: OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray()
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    // ---- the page ----

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun pref(k: String) = esc(hub.prefs.getString(k, "").orEmpty())
    private fun mark(on: Boolean) = if (on) " <span class=ok>✓ connected</span>" else ""

    private fun home() = page("Joint Launcher", """
        ${googleMsg?.let { "<p class=msg>${esc(it)}</p>" }.orEmpty()}
        <h2>Jellyfin${mark(hub.jfKey.isNotEmpty())}</h2>
        <form method=post action="/$token/jellyfin"><input name=server placeholder="http://192.168.x.x:8096" value="${esc(hub.server)}"><button>Save server</button></form>
        <p class=hint>Then sign in on the TV with Quick Connect.</p>
        <h2>Jellyseerr${mark(hub.prefs.contains("seerr_key"))}</h2>
        <form method=post action="/$token/jellyseerr">
          <input name=url placeholder="https://jellyseerr.example.com" value="${pref("seerr_url")}">
          <input name=user placeholder="Jellyfin username (a Jellyseerr admin)" autocomplete=username>
          <input name=password type=password placeholder="Jellyfin password (not stored)" autocomplete=current-password>
          <p class=hint>…or paste the API key instead (Jellyseerr → Settings → General):</p>
          <input name=apikey placeholder="API key (optional)">
          <button>Connect Jellyseerr</button></form>
        <h2>Stremio${mark(hub.stremioKey.isNotEmpty())}</h2>
        <form method=post action="/$token/stremio"><input name=email type=email placeholder="Email" autocomplete=username><input name=password type=password placeholder="Password (not stored)" autocomplete=current-password><button>Connect Stremio</button></form>
        <h2>Spotify${mark(hub.connected("spotify"))}</h2>
        <form method=post action="/$token/spotify"><input name=client_id placeholder="Client ID of your Spotify app" value="${pref("spotify_client_id")}"><button>Sign in with Spotify</button></form>
        <p class=hint>In your app on developer.spotify.com, add this Redirect URI once: <code>${SignIn.SPOTIFY_REDIRECT}</code></p>
        <h2>YouTube${mark(hub.connected("google"))}</h2>
        <form method=post action="/$token/youtube"><input name=client_id placeholder="Client ID (TVs and Limited Input devices)" value="${pref("google_client_id")}"><input name=secret placeholder="Client secret" value="${pref("google_client_secret")}"><button>Get a sign-in code</button></form>
        <p class=hint>Google Cloud Console → APIs &amp; Services → Credentials → Create OAuth client ID → type <b>TVs and Limited Input devices</b>.</p>
        <h2>Home Assistant${mark(hub.prefs.contains("ha_token"))}</h2>
        <form method=post action="/$token/homeassistant"><input name=token type=password placeholder="Long-lived access token" autocomplete=off><button>Save token</button></form>
        <p class=hint>For the MauHome widget. In Home Assistant: your profile → Security → Long-lived access tokens → Create token. Stays on this device only.</p>
    """)

    private fun done(msg: String, ok: Boolean = true) = page("Joint Launcher", "<p class=${if (ok) "msg" else "err"}>$msg</p><p><a href=\"/$token\">← Back</a></p>")

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1"><title>$title</title><style>
        body{font-family:system-ui,sans-serif;background:#0b0b0d;color:#eee;margin:0 auto;max-width:520px;padding:16px}
        h1{font-size:1.3em}h2{font-size:1.05em;margin:28px 0 8px;border-top:1px solid #2a2b30;padding-top:18px}
        input,button{display:block;width:100%;box-sizing:border-box;margin:6px 0;padding:12px;border-radius:10px;border:1px solid #333;background:#1a1b21;color:#eee;font-size:16px}
        button{background:#e8650a;border:0;color:#fff;font-weight:600}a{color:#f39a52}.ok{color:#5fd068;font-size:.85em}
        .hint{color:#9a9da8;font-size:.85em}.msg{color:#5fd068}.err{color:#ff8080}code{word-break:break-all}
        </style></head><body><h1>Joint Launcher · Connect accounts</h1>$body</body></html>"""

    companion object {
        const val PORT = 8732
        private val IMPORT_KEYS = listOf(
            "server", "key", "uid", "stremio", "seerr_url", "seerr_key",
            "spotify_client_id", "spotify_refresh", "google_client_id", "google_client_secret", "google_refresh",
            "app_order", "row_order", "app_filter",
        )
        private val IMPORT_SETS = listOf("hidden_rows", "extra_rows")
        private val ICON_PICK = Regex("[A-Za-z0-9_.]+\\|[A-Za-z0-9_]+(\\|auto)?") // package|icon name[|auto], nothing else
        /** What /export hands to another device: the accounts that aren't tied to this device (Jellyfin signs in per device). */
        private val ACCOUNT_KEYS = listOf(
            "stremio", "seerr_url", "seerr_key",
            "spotify_client_id", "spotify_refresh", "google_client_id", "google_client_secret", "google_refresh",
        )
    }
}
