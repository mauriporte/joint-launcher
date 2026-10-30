package com.mau.hub

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * Talking to Jarvis (the owner's assistant in Home Assistant) from two home-screen widgets: "Chat with Jarvis" opens a small
 * text chat, "Voice to Jarvis" records a voice message and sends it. HA's standard APIs only, with the device's own
 * token and address (like MauHome); the interface is HA admin's (art-coordinator/shared/jarvis-widgets-interface.md).
 * Sends only what the owner types or says; keeps nothing but the visible chat and the conversation id.
 */
object Jarvis {
    private const val AGENT = "conversation.jarvis_router"
    private const val PIPELINE = "01m5021wbw8h0c3fx1tb120d2m"

    /** The answer and, for the voice, what Whisper heard and the reply's audio (null when the Google Mini speaks it). */
    class Reply(val text: String, val heard: String? = null, val audio: String? = null)

    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** The device's conversation with Jarvis, kept like the chat on screen until New chat (HA starts a fresh one if it has forgotten it). */
    private fun conversation(c: Context): String? = prefs(c).getString("jarvis_conv", null)
    private fun keep(c: Context, id: String?) {
        if (!id.isNullOrEmpty()) prefs(c).edit().putString("jarvis_conv", id).apply()
    }
    fun newChat(c: Context) = prefs(c).edit().remove("jarvis_conv").remove("jarvis_log").apply()

    /** The chat as it was on screen (yours: true), kept on this device only, the last 200 messages; New chat empties it. */
    fun history(c: Context): List<Pair<Boolean, String>> = runCatching {
        val a = org.json.JSONArray(prefs(c).getString("jarvis_log", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getBoolean("m") to o.getString("t") } }
    }.getOrDefault(emptyList())

    fun remember(c: Context, mine: Boolean, text: String) {
        val kept = (history(c) + (mine to text)).takeLast(200)
        prefs(c).edit().putString("jarvis_log", org.json.JSONArray(kept.map { JSONObject().put("m", it.first).put("t", it.second) }).toString()).apply()
    }

    /** HA call with Jarvis's long timeout (the bigger brains take up to 20 s; HA admin: 150 s). */
    private fun http(c: Context, path: String, body: String? = null): JSONObject {
        val conn = URL(HaWidget.base(c) + path).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 150_000
            HaWidget.auth(c).forEach(conn::setRequestProperty)
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode >= 400) error(if (conn.responseCode == 401) "Home Assistant refused the token" else "Home Assistant said ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }

    /** Jarvis's language selector: "Español" → es, else en. */
    private fun language(c: Context) = runCatching { http(c, "/api/states/select.jarvis_idioma").optString("state") }.getOrDefault("")
        .let { if (it.startsWith("Espa")) "es" else "en" }

    /** Sends a text to Jarvis and returns its answer. Off the UI thread. */
    fun say(c: Context, text: String): Reply {
        val req = JSONObject().put("text", text).put("agent_id", AGENT).put("language", language(c))
        conversation(c)?.let { req.put("conversation_id", it) }
        val r = http(c, "/api/conversation/process", req.toString())
        keep(c, r.optString("conversation_id"))
        return Reply(speech(r.optJSONObject("response")))
    }

    /**
     * The answer's audio for the device to play, or null when the Google Mini says it (switch.jarvis_habla_por_google_mini
     * on). Piper, in the answer's own language: Assistant Me answers in the language the owner used, whatever the selector says.
     */
    private fun speech(c: Context, answer: String): String? {
        if (answer.isBlank() || http(c, "/api/states/switch.jarvis_habla_por_google_mini").optString("state") == "on") return null
        val es = spanish(answer)
        val req = JSONObject().put("engine_id", "tts.piper").put("message", answer).put("language", if (es) "es_ES" else "en_GB")
            .put("options", JSONObject().put("voice", if (es) "es_ES-davefx-medium" else "en_GB-alan-medium"))
        return http(c, "/api/tts_get_url", req.toString()).optString("url").takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("http")) it else HaWidget.base(c) + it }
    }

    /** Spanish or English, by a word check (HA admin: enough): Spanish marks and common words against English ones. */
    internal fun spanish(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}]+"))
        val es = words.count { it in setOf("el", "la", "los", "las", "de", "que", "y", "en", "es", "un", "una", "por", "para", "con", "no", "está", "son") }
        val en = words.count { it in setOf("the", "and", "is", "are", "you", "to", "of", "in", "it", "that", "for", "with", "not", "on", "a") }
        return es + (if (Regex("[áéíóúñ¿¡]").containsMatchIn(text.lowercase())) 2 else 0) > en
    }

    private fun speech(response: JSONObject?) = response?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()

    /**
     * A voice message through HA's assist pipeline (what the companion app does): the microphone's 16 kHz mono PCM goes
     * up the websocket as it's recorded ([audio] gives chunks, null at the end), Whisper turns it into text, Jarvis answers.
     * [heard] is called with Whisper's text as soon as it's known. Off the UI thread.
     */
    fun voice(c: Context, audio: () -> ByteArray?, heard: (String) -> Unit): Reply {
        val base = HaWidget.base(c)
        val token = HaWidget.auth(c).getValue("Authorization").removePrefix("Bearer ")
        MiniSocket(base.replaceFirst("http", "ws") + "/api/websocket").use { ws ->
            require(JSONObject(ws.readText()).optString("type") == "auth_required") { "Home Assistant didn't ask to sign in" }
            ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
            require(JSONObject(ws.readText()).optString("type") == "auth_ok") { "Home Assistant refused the token" }
            val run = JSONObject().put("id", 1).put("type", "assist_pipeline/run").put("start_stage", "stt").put("end_stage", "intent") // never "tts": with the Google Mini on, the pipeline has none and fails
                .put("pipeline", PIPELINE).put("input", JSONObject().put("sample_rate", 16000))
            conversation(c)?.let { run.put("conversation_id", it) }
            ws.sendText(run.toString())
            var handler = -1
            while (handler < 0) {
                val m = JSONObject(ws.readText())
                if (m.optString("type") == "result" && !m.optBoolean("success", true)) error(m.optJSONObject("error")?.optString("message") ?: "Jarvis's voice pipeline failed")
                m.optJSONObject("event")?.takeIf { it.optString("type") == "run-start" }?.let {
                    handler = it.getJSONObject("data").getJSONObject("runner_data").getInt("stt_binary_handler_id")
                }
            }
            while (true) { val chunk = audio() ?: break; ws.sendBinary(byteArrayOf(handler.toByte()) + chunk) }
            ws.sendBinary(byteArrayOf(handler.toByte())) // the end of the audio
            var said: String? = null; var answer = ""
            while (true) {
                val e = JSONObject(ws.readText()).optJSONObject("event") ?: continue
                val d = e.optJSONObject("data")
                when (e.optString("type")) {
                    "stt-end" -> said = d?.optJSONObject("stt_output")?.optString("text")?.also(heard)
                    "intent-end" -> d?.optJSONObject("intent_output")?.let { answer = speech(it.optJSONObject("response")); keep(c, it.optString("conversation_id")) }
                    "error" -> error(d?.optString("message") ?: "Jarvis's voice pipeline failed")
                    "run-end" -> return Reply(answer, said, runCatching { speech(c, answer) }.getOrNull())
                }
            }
        }
    }

    internal fun views(c: Context, label: String, icon: Int, open: Class<out Activity>) = RemoteViews(c.packageName, R.layout.jarvis_widget).apply {
        setTextViewText(R.id.jarvis_label, label)
        setImageViewResource(R.id.jarvis_icon, icon)
        setInt(R.id.jarvis_icon, "setColorFilter", 0xFFAB47BC.toInt()) // MauHome's violet: the assistant's colour
        setOnClickPendingIntent(R.id.jarvis_tile, PendingIntent.getActivity(c, open.name.hashCode(),
            Intent(c, open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}

/**
 * Just enough of a websocket client (RFC 6455) for HA's assist pipeline: the handshake, masked text and binary frames
 * out, text frames in (with pings answered). ws:// at home, wss:// outside.
 */
internal class MiniSocket(url: String) : Closeable {
    private val socket: Socket
    private val input: DataInputStream
    private val output get() = socket.getOutputStream()

    init {
        val u = URI(url)
        val tls = u.scheme == "wss"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        socket = if (tls) javax.net.ssl.SSLSocketFactory.getDefault().createSocket(u.host, port) else Socket(u.host, port)
        socket.soTimeout = 150_000 // Jarvis's bigger brains take a while
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val key = android.util.Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
        output.write(("GET ${u.rawPath} HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
        require(head.startsWith("HTTP/1.1 101")) { "Home Assistant didn't open the websocket: ${head.lineSequence().first()}" }
    }

    fun sendText(t: String) = send(0x1, t.toByteArray())
    fun sendBinary(b: ByteArray) = send(0x2, b)
    @Synchronized private fun send(op: Int, data: ByteArray) {
        output.write(frame(op, data, ByteArray(4).also { java.security.SecureRandom().nextBytes(it) })); output.flush()
    }

    /** The next whole text message; pings get their pong, a close ends it. */
    fun readText(): String {
        val msg = ByteArrayOutputStream()
        while (true) {
            val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            val data = ByteArray(len.toInt()).also(input::readFully) // the server never masks
            when (b0 and 0x0F) {
                0x9 -> send(0xA, data)
                0x8 -> error("Home Assistant closed the connection")
                0x0, 0x1 -> { msg.write(data); if (b0 and 0x80 != 0) return msg.toString("UTF-8") }
            }
        }
    }

    override fun close() { runCatching { send(0x8, ByteArray(0)) }; runCatching { socket.close() } }

    companion object {
        /** One final, masked client frame. */
        fun frame(op: Int, data: ByteArray, mask: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(0x80 or op)
            when {
                data.size < 126 -> write(0x80 or data.size)
                data.size < 65536 -> { write(0x80 or 126); write(data.size shr 8); write(data.size and 0xFF) }
                else -> { write(0x80 or 127); for (i in 7 downTo 0) write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            write(mask)
            data.forEachIndexed { i, b -> write(b.toInt() xor mask[i % 4].toInt()) }
        }.toByteArray()
    }
}

/**
 * The Jarvis screens' frame: the status and navigation bars hidden as everywhere else in the launcher (a swipe shows them
 * for a moment), and the content kept clear of the keyboard (and of the bars while they show).
 */
open class JarvisScreen : Activity() {
    override fun setContentView(v: android.view.View) {
        super.setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(BG); addView(v)
            if (android.os.Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { f, ins ->
                val i = ins.getInsets(android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.systemBars())
                f.setPadding(i.left, i.top, i.right, i.bottom); ins
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** "Chat with Jarvis": a tap opens [JarvisChat]. */
class JarvisChatWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) =
        ids.forEach { m.updateAppWidget(it, Jarvis.views(c, "Chat with Jarvis", R.drawable.ic_chat, JarvisChat::class.java)) }
}

/** "Voice to Jarvis": a tap starts recording ([JarvisVoice]). */
class JarvisVoiceWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) =
        ids.forEach { m.updateAppWidget(it, Jarvis.views(c, "Voice to Jarvis", R.drawable.ic_mic, JarvisVoice::class.java)) }
}

/** A small chat with Jarvis: your messages on the right, its answers on the left; "New chat" starts over. */
class JarvisChat : JarvisScreen() {
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    @Volatile private var busy = false // one question at a time

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        scroll = ScrollView(this).apply { addView(log) }
        val field = EditText(this).apply {
            hint = "Message Jarvis"; setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED); imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
        }
        fun send() {
            if (busy) return
            val t = field.text.toString().trim().takeIf { it.isNotEmpty() } ?: return
            field.setText(""); bubble(t, mine = true); Jarvis.remember(this, true, t); busy = true
            val thinking = bubble("Thinking…", mine = false)
            Thread {
                val r = runCatching { Jarvis.say(this, t) }
                runOnUiThread {
                    busy = false; log.removeView(thinking)
                    r.fold({ bubble(it.text, false); Jarvis.remember(this, false, it.text) }, { bubble(it.message ?: "Didn't work", false, error = true) })
                }
            }.start()
        }
        field.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            addView(LinearLayout(this@JarvisChat).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), 0)
                addView(text(20f, Color.WHITE).apply { text = "Jarvis" }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("New chat") { Jarvis.newChat(this@JarvisChat); log.removeAllViews() })
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@JarvisChat).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), dp(8))
                addView(field, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(this@JarvisChat).apply {
                    setImageResource(R.drawable.ic_chevron); setColorFilter(ACCENT); setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = bareButton(); contentDescription = "Send"; setOnClickListener { send() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
        })
        Jarvis.history(this).forEach { (mine, t) -> bubble(t, mine) } // the chat as you left it
        field.requestFocus()
    }

    private fun bubble(t: String, mine: Boolean, error: Boolean = false) = text(16f, if (error) 0xFFFF8A80.toInt() else Color.WHITE).apply {
        text = t; setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (mine) 0xFF3A2410.toInt() else 0xFF1C1C22.toInt()) }
        log.addView(this, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (mine) Gravity.END else Gravity.START; topMargin = dp(8) })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

/**
 * "Voice to Jarvis": records straight away (asks for the microphone the first time) and streams it to Jarvis as you speak;
 * "Stop and send" (or 30 seconds) ends it. Then it shows what you said and the answer, and plays the answer's audio unless
 * the Google Mini is speaking it.
 */
class JarvisVoice : JarvisScreen() {
    private lateinit var status: TextView
    private lateinit var said: TextView
    private lateinit var answer: TextView
    private lateinit var action: TextView
    @Volatile private var recording = false
    private var started = 0L
    private var player: MediaPlayer? = null
    private val ticker = object : Runnable {
        override fun run() {
            val s = (SystemClock.uptimeMillis() - started) / 1000
            if (recording) { status.text = "Listening… %d:%02d".format(s / 60, s % 60); status.postDelayed(this, 500) }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        status = text(18f, Color.WHITE).apply { gravity = Gravity.CENTER }
        said = text(15f, TEXT_SOFT).apply { gravity = Gravity.CENTER }
        answer = text(17f, Color.WHITE).apply { gravity = Gravity.CENTER }
        action = button("Stop and send") { if (recording) recording = false else finish() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(BG); setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ImageView(this@JarvisVoice).apply {
                setImageResource(R.drawable.ic_mic); setColorFilter(Color.WHITE); setPadding(dp(28), dp(28), dp(28), dp(28))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFC62828.toInt()) } // red: it's listening
            }, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
            addView(said, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(LinearLayout(this@JarvisVoice).apply {
                gravity = Gravity.CENTER
                addView(button("Cancel") { recording = false; finish() })
                addView(android.view.View(this@JarvisVoice), LinearLayout.LayoutParams(dp(16), 1))
                addView(action)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else { toast("Jarvis needs the microphone for voice messages"); finish() }
    }

    @Suppress("MissingPermission") // asked for above
    private fun start() {
        val rate = 16000
        val chunk = rate * 2 / 20 // 50 ms of 16-bit mono
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4))
                .also { it.startRecording() }
        }.getOrElse { toast("Couldn't start the microphone: ${it.message}"); finish(); return }
        recording = true; started = SystemClock.uptimeMillis(); ticker.run()
        Thread {
            val r = runCatching {
                Jarvis.voice(this, audio = {
                    // the next 50 ms while recording (HA admin: about 30 s at most per message); null ends it
                    if (recording && SystemClock.uptimeMillis() - started < 30_000) ByteArray(chunk).let { b -> b.copyOf(maxOf(0, rec.read(b, 0, chunk))) }
                    else { if (recording) recording = false; runOnUiThread { status.text = "Jarvis is thinking…" }; null }
                }, heard = { t -> runOnUiThread { said.text = "You said: “$t”" } })
            }
            recording = false
            runCatching { rec.stop() }; rec.release()
            runOnUiThread {
                status.removeCallbacks(ticker)
                r.fold({ reply ->
                    status.text = "Jarvis"; answer.text = reply.text.ifEmpty { "(no answer)" }
                    reply.audio?.let { play(it) }
                }, { status.text = "Didn't work"; answer.text = it.message ?: "" })
                action.text = "Close"
            }
        }.start()
    }

    /** The answer's audio, once (only sent when the Google Mini isn't speaking it). */
    private fun play(url: String) {
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(this@JarvisVoice, android.net.Uri.parse(url), HaWidget.auth(this@JarvisVoice))
                setOnPreparedListener { it.start() }; prepareAsync()
            }
        }.getOrNull()
    }

    override fun onDestroy() { recording = false; player?.release(); super.onDestroy() }
}
