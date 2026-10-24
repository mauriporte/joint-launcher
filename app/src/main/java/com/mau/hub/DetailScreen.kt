package com.mau.hub

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Netflix-style info screen shown before handing off to any app: logo/title, ratings, seasons or runtime,
 * the full description, cast/creators, awards, Play, Request in Jellyseerr and (when there is one) Trailer.
 */
class DetailScreen(private val a: MainActivity) {
    val view = FrameLayout(a).apply { visibility = View.GONE; setBackgroundColor(BG) }
    val isShown get() = view.isShown
    private var item: Item? = null
    private var returnFocus: View? = null

    /** Where Play goes: the service's own app when installed (and enabled), otherwise the same title in Stremio. */
    private fun playTarget(item: Item): Pair<String, Play> {
        val p = item.play
        val appInstalled = (p as? Play.Link)?.pkg?.let(a.hub::installed) ?: true
        val alt = item.alt
        return when {
            !appInstalled && alt != null -> "Play in Stremio" to alt
            item.source == Source.LIVE -> "Watch on GeeSports" to p
            p is Play.JellyLive -> "Watch live" to p
            item.source == Source.YOUTUBE -> (if (a.hub.touch) "Play in YouTube" else "Play in SmartTube") to p
            else -> "Play on ${item.source.label}" to p
        }
    }

    fun show(item: Item) {
        returnFocus = a.currentFocus
        this.item = item
        view.removeAllViews()
        val hub = a.hub
        val width = a.resources.displayMetrics.widthPixels
        val bg = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        (item.backdrop ?: item.poster)?.let { a.images.load(it, bg, big = true) }

        val actions = LinearLayout(a).apply { setPadding(0, a.dp(22), 0, 0) }
        when (val p = item.play) {
            is Play.Jelly -> if (p.folder) {
                actions.addView(a.button("▶  Play") { a.actions.playSeries(p.id) })
                actions.addView(a.button("Open in Jellyfin") { a.actions.open(p) })
            } else {
                // the row may be older than your last watch: ask Jellyfin where you stopped, and offer Resume from there
                var pos = p.posMs
                val fromStart = a.button("From start") { a.actions.open(p.copy(posMs = 0)) }
                val play = a.button("") { a.actions.open(p.copy(posMs = pos)) }
                fun label() {
                    play.text = if (pos > 0) "▶  Resume" else "▶  Play"
                    if (pos > 0 && fromStart.parent == null) actions.addView(fromStart, 1)
                    if (pos == 0L && fromStart.parent != null) actions.removeView(fromStart)
                }
                actions.addView(play); label()
                hub.pool.execute {
                    val fresh = runCatching { Sources.jellyfinPosition(hub.server, hub.jfKey, hub.jfUser(), p.id) }.getOrNull() ?: return@execute
                    hub.main.post { if (this.item === item && fresh != pos) { pos = fresh; label() } }
                }
            }
            else -> playTarget(item).let { (label, target) -> actions.addView(a.button("▶  $label") { a.actions.open(target) }) }
        }
        seerrButton(item)?.let(actions::addView)
        (item.play as? Play.JellyLive)?.let { live ->
            var fav = item.fav
            actions.addView(a.button(if (fav) "★  Favourite" else "☆  Add to favourites") {}.apply {
                setOnClickListener {
                    val want = !fav
                    hub.pool.execute {
                        val r = runCatching { Sources.jellyfinFavourite(hub.server, hub.jfKey, hub.jfUser(), live.channelId, want) }
                        hub.main.post {
                            r.onSuccess { fav = want; text = if (fav) "★  Favourite" else "☆  Add to favourites" }
                                .onFailure { a.toast("Couldn't update favourites: ${it.message}") }
                        }
                    }
                }
            })
        }

        val kicker = a.text(12f, ACCENT).apply { text = Meta.kicker(item); typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = 0.18f }
        val title = a.text(36f, Color.WHITE).apply {
            text = item.title; typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.01f
            setShadowLayer(16f, 0f, 2f, 0x99000000.toInt())
        }
        val logo = ImageView(a).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        val meta = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        a.meta.fill(meta, item, big = true)
        a.meta.withRatings(item) { if (this.item === item) a.meta.fill(meta, item, big = true) }
        val facts = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val desc = a.text(15f, TEXT_SOFT).apply { text = item.description; maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }
        val credits = a.text(13f, TEXT_MUTED).apply { visibility = View.GONE; maxLines = 2 }
        val awards = a.text(12f, ACCENT).apply { visibility = View.GONE; maxLines = 1 }

        view.addView(bg, FrameLayout.LayoutParams(-1, -1))
        view.addView(View(a).apply { background = fade() }, FrameLayout.LayoutParams(-1, -1))
        view.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(BG, 0x00000000))
        }, FrameLayout.LayoutParams(-1, a.dp(260), Gravity.BOTTOM))
        view.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(a.dp(56), 0, 0, a.dp(48))
            addView(kicker)
            addView(title, LinearLayout.LayoutParams((width * 0.6).toInt(), -2))
            addView(logo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(4) })
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) })
            addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) })
            addView(desc, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = a.dp(12) })
            addView(credits, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = a.dp(8) })
            addView(awards, LinearLayout.LayoutParams((width * 0.5).toInt(), -2).apply { topMargin = a.dp(4) })
            addView(actions)
        }, FrameLayout.LayoutParams(-1, -1))
        view.visibility = View.VISIBLE
        actions.getChildAt(0).requestFocus()
        // touchscreens: no focus to light up Play, so mark it as the main action instead
        if (hub.touch) (actions.getChildAt(0) as? TextView)?.apply { isSelected = true; setTextColor(Color.BLACK) }
        a.page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS // the rows behind stay focusable otherwise, and D-pad wanders onto them

        // the extended info comes from Stremio's catalogue, by IMDb id
        val id = item.imdbId?.takeIf { it.startsWith("tt") } ?: return
        val type = item.type ?: return
        hub.pool.execute {
            val m = runCatching { Sources.meta(type, id) }.getOrNull() ?: return@execute
            hub.main.post {
                if (this.item !== item) return@post
                if (m.seasons > 0) facts.addView(a.pill(
                    "${m.seasons} Season${if (m.seasons > 1) "s" else ""}" + if (m.episodes > 0) "  ·  ${m.episodes} Episodes" else ""
                ))
                else m.runtime?.let { facts.addView(a.pill(Meta.prettyRuntime(it))) }
                m.genres.takeIf { it.isNotEmpty() }?.let { facts.addView(a.pill(it.joinToString("  ·  "))) }
                m.description?.takeIf { it.length > desc.text.length }?.let { desc.text = it }
                val castLine = m.cast.takeIf { it.isNotEmpty() }?.let { "Starring ${it.joinToString(", ")}" }
                val byLine = m.creators.takeIf { it.isNotEmpty() }?.let { (if (type == "series") "Created by " else "Directed by ") + it.joinToString(", ") }
                listOfNotNull(castLine, byLine).takeIf { it.isNotEmpty() }?.let { credits.text = it.joinToString("     "); credits.visibility = View.VISIBLE }
                m.awards?.let { awards.text = "🏆  $it"; awards.visibility = View.VISIBLE }
                m.logo?.let { url -> a.images.load(url, logo, big = true); logo.maxHeight = a.dp(90); logo.maxWidth = (width * 0.4).toInt(); logo.visibility = View.VISIBLE; title.visibility = View.GONE }
                m.trailerYt?.let { yt -> actions.addView(a.button("Trailer") { a.actions.open(Play.Link(Links.youtube(yt), Links.SMARTTUBE)) }) }
            }
        }
    }

    fun close() {
        a.page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        view.visibility = View.GONE; item = null
        returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
    }

    /**
     * "Request in Jellyseerr" for movies, series and albums that aren't already in your library. Starts as "Checking…",
     * then shows the real state (Request / Requested / Request the rest), or disappears if it's available.
     * Albums are found on MusicBrainz first (exact title + artist), since that's what this Jellyseerr requests music by.
     */
    private fun seerrButton(item: Item): View? {
        val hub = a.hub
        val base = hub.prefs.getString("seerr_url", null) ?: return null
        val key = hub.prefs.getString("seerr_key", null) ?: return null
        val type = item.type ?: return null
        if (item.source == Source.JELLYFIN) return null // already in your library
        val album = type == "album"
        if (album && item.artist == null) return null
        if (!album && item.tmdbId == null) return null
        var mediaId: String? = item.tmdbId
        var status: Int? = -1
        lateinit var b: TextView
        b = a.button("Checking Jellyseerr…") {
            val id = mediaId ?: return@button
            if (status == 2 || status == 3 || status == -1) return@button
            b.text = "Requesting…"
            hub.pool.execute {
                val r = runCatching { Sources.seerrRequest(base, key, type, id) }
                hub.main.post {
                    r.onSuccess { status = 2; b.text = Sources.seerrLabel(2) }
                        .onFailure { b.text = Sources.seerrLabel(status); a.toast("Jellyseerr request failed: ${it.message}") }
                }
            }
        }
        hub.pool.execute {
            val s = runCatching {
                val id = mediaId ?: Sources.mbAlbumId(item.artist!!, item.title)?.also { mediaId = it }
                    ?: return@runCatching -2 // album not on MusicBrainz: nothing Jellyseerr could request
                Sources.seerrStatus(base, key, type, id)
            }
            hub.main.post {
                if (this.item !== item) return@post
                s.onSuccess { st ->
                    status = st
                    if (st == -2) b.visibility = View.GONE
                    else Sources.seerrLabel(st)?.let { b.text = it } ?: run { b.visibility = View.GONE }
                }.onFailure { b.text = "Jellyseerr unavailable"; status = 3 }
            }
        }
        return b
    }
}
