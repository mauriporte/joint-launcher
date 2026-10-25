package com.mau.hub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow

/**
 * Slim search bar that slides out beside the sidebar's magnifying glass, floating over the screen (nothing behind
 * it is hidden). Type and press Search on the TV keyboard, or press Right to reach the mic and say it.
 */
class SearchBar(private val a: MainActivity) {
    private var popup: PopupWindow? = null

    fun dismiss() { popup?.dismiss() }

    fun open() {
        a.sidebar.show()
        val anchor = a.sidebar.anchor(Sidebar.SEARCH) ?: return
        val box = EditText(a).apply {
            id = View.generateViewId()
            hint = "Search movies, series, channels…"; isSingleLine = true; textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(TEXT_MUTED)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(a.dp(4), 0, a.dp(8), 0)
        }
        val mic = a.iconButton(R.drawable.ic_mic, "Voice search") { voice() }.apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(a.dp(36), a.dp(36)).apply { marginStart = a.dp(4) }
            setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
        }
        box.nextFocusRightId = mic.id // Right from the text goes to the microphone
        mic.nextFocusLeftId = box.id
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(14), a.dp(4), a.dp(6), a.dp(4))
            background = GradientDrawable().apply { cornerRadius = a.dp(24).toFloat(); setColor(0xEB1A1B21.toInt()); setStroke(a.dp(1), 0x40FFFFFF) }
            elevation = a.dp(10).toFloat()
            addView(ImageView(a).apply { setImageResource(R.drawable.ic_search); setColorFilter(TEXT_MUTED) }, LinearLayout.LayoutParams(a.dp(18), a.dp(18)))
            addView(box, LinearLayout.LayoutParams(a.dp(360), a.dp(40)))
            addView(mic)
            // the phone-remote bridge only types from a phone; offer Android's keyboard chooser to pick the TV keyboard
            val currentIme = android.provider.Settings.Secure.getString(a.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            if ("ImeBridge" in currentIme) addView(a.iconButton(R.drawable.ic_keyboard, "Choose keyboard") { imm.showInputMethodPicker() }.apply {
                layoutParams = LinearLayout.LayoutParams(a.dp(36), a.dp(36)).apply { marginStart = a.dp(4) }
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
            })
        }
        val p = PopupWindow(bar, -2, -2, true).apply {
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            setOnDismissListener { popup = null }
        }
        fun go(q: String) { q.trim().takeIf { it.isNotEmpty() }?.let { p.dismiss(); a.sidebar.hide(); a.search(it) } }
        box.setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        popup = p
        bar.measure(0, 0)
        p.showAsDropDown(anchor, anchor.width + a.dp(8), -(anchor.height + bar.measuredHeight) / 2)
        // slide out from the sidebar
        bar.translationX = -a.dp(24).toFloat(); bar.alpha = 0f
        bar.animate().translationX(0f).alpha(1f).setDuration(180).start()
        box.requestFocus()
        // open the TV's own on-screen keyboard (remote-driven) right away, instead of waiting for OK on the field
        box.postDelayed({ imm.showSoftInput(box, InputMethodManager.SHOW_FORCED) }, 200)
    }

    /** Google TV's own voice input (what Stremio uses too); the spoken words come back through [onVoiceResult]. */
    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you want to watch")
        try { @Suppress("DEPRECATION") a.startActivityForResult(i, VOICE_REQUEST) }
        catch (_: ActivityNotFoundException) { a.toast("Voice search isn't available on this TV") }
    }

    fun onVoiceResult(data: Intent?) {
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        dismiss()
        a.sidebar.hide()
        a.search(said)
    }

    companion object { const val VOICE_REQUEST = 7 }
}
