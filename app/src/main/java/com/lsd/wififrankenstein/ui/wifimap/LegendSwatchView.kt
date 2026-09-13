package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class LegendSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fillColor: Int = Color.GRAY
    private var hasCrossData: Boolean = false
    private var wpasecKnown: Boolean = false
    private var isOpen: Boolean = false
    private var clusterCount: Int = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
    }
    private val crossRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CROSS_DATA_COLOR
        strokeWidth = dp(3f)
    }
    private val wpasecRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = WPASEC_COLOR
        strokeWidth = dp(3f)
    }
    private val openDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OPEN_COLOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    fun bind(
        color: Int,
        crossData: Boolean = false,
        wpasec: Boolean = false,
        open: Boolean = false,
        clusterCount: Int = 0
    ) {
        fillColor = color
        hasCrossData = crossData
        wpasecKnown = wpasec
        isOpen = open
        this.clusterCount = clusterCount
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(36f).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minDim = minOf(width, height).toFloat()
        val radius = (minDim / 2f - dp(8f)).coerceAtLeast(dp(6f))

        fillPaint.color = fillColor
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        if (hasCrossData) {
            canvas.drawCircle(cx, cy, radius + dp(3f), crossRingPaint)
        }
        if (wpasecKnown) {
            canvas.drawCircle(cx, cy, radius + if (hasCrossData) dp(6f) else dp(3f), wpasecRingPaint)
        }
        if (isOpen) {
            canvas.drawCircle(cx, cy, radius * 0.45f, openDotPaint)
        }

        if (clusterCount > 0) {
            textPaint.textSize = radius * 0.9f
            val metrics = textPaint.fontMetrics
            canvas.drawText(
                clusterCount.toString(),
                cx,
                cy - metrics.top / 2f,
                textPaint
            )
        }
    }

    private companion object {
        const val CROSS_DATA_COLOR = 0xFFFFC107.toInt()
        const val WPASEC_COLOR = 0xFF00E5FF.toInt()
        const val OPEN_COLOR = 0xFFE53935.toInt()
    }
}
