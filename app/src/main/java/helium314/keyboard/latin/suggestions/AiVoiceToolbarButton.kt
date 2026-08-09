// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.ColorInt
import helium314.keyboard.latin.R
import java.util.Locale

/** Toolbar key with a fixed AI microphone icon and a compact state label. */
class AiVoiceToolbarButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.suggestionWordStyle,
) : ImageButton(context, attrs, defStyleAttr) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = resources.getDimension(R.dimen.config_ai_voice_toolbar_label_text_size)
    }
    private val labelAreaHeight = resources.getDimensionPixelSize(R.dimen.config_ai_voice_toolbar_label_area_height)
    private val labelBottomPadding = resources.getDimensionPixelSize(R.dimen.config_ai_voice_toolbar_label_bottom_padding)

    private val loaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private var visualState = VisualState.IDLE
    private var elapsedMillis = 0L
    @ColorInt private var labelColor = 0
    private var loaderRotation = 0f
    private val loaderAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = LOADER_ROTATION_DURATION_MILLIS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            loaderRotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        scaleType = ImageView.ScaleType.CENTER
        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + labelAreaHeight)
        contentDescription = context.getString(R.string.ai_voice_toolbar_start)
    }

    fun setLabelColor(@ColorInt color: Int) {
        labelColor = color
        invalidate()
    }

    fun setVisualState(recording: Boolean, processing: Boolean, elapsedMillis: Long) {
        val nextState = when {
            processing -> VisualState.PROCESSING
            recording -> VisualState.RECORDING
            else -> VisualState.IDLE
        }
        val stateChanged = visualState != nextState
        visualState = nextState
        this.elapsedMillis = elapsedMillis.coerceAtLeast(0L)
        if (stateChanged) {
            when (nextState) {
                VisualState.IDLE -> {
                    loaderAnimator.cancel()
                    setImageResource(R.drawable.sym_keyboard_ai_voice)
                    isEnabled = true
                    contentDescription = context.getString(R.string.ai_voice_toolbar_start)
                }
                VisualState.RECORDING -> {
                    loaderAnimator.cancel()
                    setImageResource(R.drawable.sym_keyboard_ai_voice_recording)
                    drawable?.setTint(RECORDING_TINT)
                    isEnabled = true
                    contentDescription = context.getString(R.string.ai_voice_toolbar_stop)
                }
                VisualState.PROCESSING -> {
                    setImageDrawable(null)
                    isEnabled = false
                    contentDescription = context.getString(R.string.ai_voice_toolbar_processing)
                    loaderAnimator.start()
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        labelPaint.color = labelColor
        when (visualState) {
            VisualState.IDLE, VisualState.RECORDING -> {
                val label = if (visualState == VisualState.RECORDING) {
                    formatElapsed(elapsedMillis)
                } else {
                    context.getString(R.string.ai_voice_toolbar_label)
                }
                val baseline = height - labelBottomPadding - labelPaint.descent()
                canvas.drawText(label, width / 2f, baseline, labelPaint)
            }
            VisualState.PROCESSING -> drawLoader(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        loaderAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visualState == VisualState.PROCESSING && !loaderAnimator.isStarted) loaderAnimator.start()
    }

    private fun drawLoader(canvas: Canvas) {
        loaderPaint.color = labelColor
        val drawableAreaHeight = height - labelAreaHeight
        val radius = minOf(width, drawableAreaHeight).toFloat() * LOADER_RADIUS_FRACTION
        val centerX = width / 2f
        val centerY = drawableAreaHeight / 2f
        canvas.drawArc(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
            loaderRotation,
            LOADER_SWEEP_DEGREES,
            false,
            loaderPaint,
        )
    }

    private fun formatElapsed(millis: Long): String {
        val seconds = millis / 1_000L
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private enum class VisualState { IDLE, RECORDING, PROCESSING }

    private companion object {
        const val LOADER_ROTATION_DURATION_MILLIS = 900L
        const val LOADER_RADIUS_FRACTION = 0.22f
        const val LOADER_SWEEP_DEGREES = 210f
        const val RECORDING_TINT = 0xFFF44336
    }
}
