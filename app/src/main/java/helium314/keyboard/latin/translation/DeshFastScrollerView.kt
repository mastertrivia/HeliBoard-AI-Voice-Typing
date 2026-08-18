// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/translation/FastScrollerView.kt
// (smali: base_smali/smali/com/deshkeyboard/translation/FastScrollerView.smali + $a.smali).
//
// Reproduced exactly from the smali:
//  - sizes: I=4dp thumb inset, J=40dp thumb height, K=48dp bubble size,
//           L=4dp track inset, M=2dp corner radius (dp->px via density)
//  - onDraw: track (25% alpha), thumb (full alpha), bubble + letter while dragging
//  - b(F): clamp thumb, scroll the RecyclerView to the position under the finger,
//          bubble letter from the list adapter (only for rows in the recent region)
//  - c():  thumb follows RecyclerView scroll
//  - onTouchEvent: DOWN -> drag; MOVE -> follow; UP/CANCEL -> hide bubble
//  - attached as RecyclerView.OnScrollListener (Desh FastScrollerView$a)
//
// Colors: Desh uses input_method_picker_text_color / input_method_picker_background;
// we tint with HeliBoard's theme (KEY_TEXT = track/thumb/bubble, STRIP_BACKGROUND = letter)
// so it follows the active theme exactly like the rest of the translation block.
package helium314.keyboard.latin.translation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/** Alphabet fast-scroll bar of the Desh language dialog. */
class DeshFastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Desh x — the RecyclerView this scroller drives. */
    var targetRecyclerView: RecyclerView? = null
        set(value) {
            field?.removeOnScrollListener(scrollListener)
            field = value
            value?.addOnScrollListener(scrollListener)
        }

    /**
     * Desh b(): the bubble letter for a list position, or null when the row is
     * outside the "recent" region (Desh checks viewType==0 and pos < k+1).
     */
    var letterProvider: ((position: Int) -> String?)? = null

    /** Desh F — thumb top position. */
    private var thumbTop = 0f

    /** Desh H — dragging state. */
    private var dragging = false

    /** Desh y — the bubble letter. */
    private var bubbleLetter = ""

    // ---- Desh I/J/K/L/M (dp converted via a(F)) ----
    private val thumbInset = dp(4f)      // I
    private val thumbHeight = dp(40f)    // J
    private val bubbleSize = dp(48f)     // K
    private val trackInset = dp(4f)      // L
    private val cornerRadius = dp(2f)    // M

    // ---- Desh N/O/P/Q paints + R rect ----
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = dp(24f)
    }
    private val bubbleRect = RectF()

    /** Desh FastScrollerView$a — RecyclerView.OnScrollListener. */
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!dragging) updateThumbFromScroll()
        }
    }

    init {
        applyThemeColors()
    }

    /** Desh constructor colors (input_method_picker_text_color / _background), themed. */
    fun applyThemeColors() {
        val colors = Settings.getValues()?.mColors ?: return
        val text = colors.get(ColorType.KEY_TEXT)
        val background = colors.get(ColorType.STRIP_BACKGROUND)
        trackPaint.color = text
        trackPaint.alpha = 0x19 // Desh: setAlpha(0x19) = 25%
        thumbPaint.color = text
        thumbPaint.alpha = 0xff
        bubblePaint.color = text
        letterPaint.color = background
        invalidate()
    }

    /** Desh a(F) — dp to px. */
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** Desh c() — thumb follows RecyclerView scroll. */
    private fun updateThumbFromScroll() {
        val rv = targetRecyclerView ?: return
        val range = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent()
        if (range <= 0) return
        val max = height - thumbHeight
        if (max <= 0f) return
        thumbTop = (rv.computeVerticalScrollOffset().toFloat() / range) * max
        invalidate()
    }

    /** Desh b(F) — thumb from touch + scroll the list + bubble letter. */
    private fun updateFromTouch(y: Float) {
        val max = height - thumbHeight
        if (max <= 0f) return
        thumbTop = y.coerceIn(0f, max)
        val ratio = thumbTop / max
        val rv = targetRecyclerView ?: return
        val adapter = rv.adapter ?: return
        val count = adapter.itemCount
        if (count <= 0) return
        val position = ((ratio * (count - 1)).toInt()).coerceIn(0, count - 1)
        (rv.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, 0)
        bubbleLetter = letterProvider?.invoke(position)?.takeIf { it.isNotEmpty() } ?: ""
        invalidate()
    }

    /** Desh onTouchEvent. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                updateFromTouch(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFromTouch(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    bubbleLetter = ""
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return false
    }

    /** Desh onDraw. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        // track: roundRect(width - L, 0, width, height, M, M)
        canvas.drawRoundRect(width - trackInset, 0f, width, height, cornerRadius, cornerRadius, trackPaint)
        // thumb: roundRect(width - I, F, width, F + J, M, M)
        canvas.drawRoundRect(width - thumbInset, thumbTop, width, thumbTop + thumbHeight, cornerRadius, cornerRadius, thumbPaint)
        // bubble (Desh: drawn only while dragging and letter non-empty)
        if (dragging && bubbleLetter.isNotEmpty()) {
            val centerY = thumbTop + thumbHeight / 2f
            val right = width - thumbInset - dp(8f)
            val left = right - bubbleSize
            val half = bubbleSize / 2f
            bubbleRect.set(left, centerY - half, right, centerY + half)
            canvas.drawRoundRect(bubbleRect, half, half, bubblePaint)
            val cx = bubbleRect.centerX()
            val cy = bubbleRect.centerY() - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText(bubbleLetter, cx, cy, letterPaint)
        }
    }
}
