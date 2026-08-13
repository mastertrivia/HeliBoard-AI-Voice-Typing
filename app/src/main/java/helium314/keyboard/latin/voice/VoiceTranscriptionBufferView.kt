/*
 * Copyright (C) 2026 HeliBoard voice integration work.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Live dictation buffer used while the normal Voice input is active.
 * It intentionally lives inside HeliBoard's existing suggestion-strip area;
 * no overlay window or floating view is created outside the IME.
 */
package helium314.keyboard.latin.voice

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.max
import kotlin.math.min

class VoiceTranscriptionBufferView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    private val lineHeightPx = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)

    private val textView = TextView(context).apply {
        setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = true
        ellipsize = null
        setPadding(dp(10), 0, dp(10), 0)
        isSingleLine = false
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = true
        addView(textView)
        updateHeight("")
    }

    fun setTranscription(text: String) {
        textView.text = text
        textView.post {
            // Keep the newest part visible once the five-line viewport is full.
            fullScroll(FOCUS_DOWN)
            updateHeight(text)
        }
        updateHeight(text)
    }

    fun clearTranscription() {
        textView.text = ""
        updateHeight("")
        scrollTo(0, 0)
    }

    private fun updateHeight(text: String) {
        val lineCount = if (text.isBlank()) 1 else max(1, textView.lineCount)
        val visibleLines = min(MAX_LINES, lineCount)
        layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
        )).also {
            it.height = visibleLines * lineHeightPx
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        requestLayout()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_LINES = 5
    }
}
