package com.lsd.wififrankenstein.util

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

class AnimatedLoadingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var barX = 0f
    private var barWidth = 0f
    private var isVisible = false
    private var isCompleting = false
    private var completionProgress = 0f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var animationRunnable: Runnable? = null
    private var startTime = 0L

    private val completionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { animation: ValueAnimator ->
            if (isCompleting) {
                completionProgress = animation.animatedValue as Float
                invalidate()
            }
        }
        addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                handler?.postDelayed({
                    isVisible = false
                    isCompleting = false
                    visibility = GONE
                }, 1000)
            }
            override fun onAnimationCancel(animation: Animator) {
                isVisible = false
                isCompleting = false
                visibility = GONE
            }
            override fun onAnimationRepeat(animation: Animator) {}
        })
    }

    init {
        setupPaint()
        barWidth = dpToPx(100f)
        visibility = GONE
    }

    private fun setupPaint() {
        val typedValue = TypedValue()
        val theme = context.theme
        theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val primaryColor = if (typedValue.data != 0) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, android.R.color.holo_blue_bright)
        }
        paint.color = primaryColor
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    private fun startAnimationLoop() {
        animationRunnable?.let { handler?.removeCallbacks(it) }

        startTime = System.currentTimeMillis()
        animationRunnable = object : Runnable {
            override fun run() {
                if (isVisible && !isCompleting) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val cycle = (elapsed % 3000L).toFloat() / 3000f
                    val progress = cycle * 2f

                    val maxX = if (width > barWidth.toInt()) {
                        (width.toFloat() - barWidth).coerceAtLeast(0f)
                    } else {
                        0f
                    }

                    barX = if (progress <= 1f) {
                        maxX * progress
                    } else {
                        maxX * (2f - progress)
                    }

                    invalidate()
                    handler?.postDelayed(this, 16)
                }
            }
        }
        animationRunnable?.let { handler?.post(it) }
    }

    fun startAnimation() {
        if (!isVisible) {
            isVisible = true
            isCompleting = false
            completionProgress = 0f
            barX = 0f
            visibility = VISIBLE
            if (completionAnimator.isRunning) {
                completionAnimator.cancel()
            }
            post {
                if (barWidth <= 0f) {
                    barWidth = dpToPx(100f)
                }
                startAnimationLoop()
            }
        }
    }

    fun stopAnimation() {
        if (isVisible && !isCompleting) {
            isCompleting = true
            animationRunnable?.let { handler?.removeCallbacks(it) }
            completionAnimator.start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isVisible && !isCompleting) {
            startAnimationLoop()
        } else if (isCompleting && !completionAnimator.isRunning) {
            completionAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animationRunnable?.let { handler?.removeCallbacks(it) }
        handler?.removeCallbacksAndMessages(null)
        if (completionAnimator.isRunning) {
            completionAnimator.cancel()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            barWidth = (w.toFloat() * 0.2f).coerceAtLeast(dpToPx(60f)).coerceAtMost(dpToPx(150f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isVisible && width > 0) {
            val barHeight = height.toFloat()

            if (isCompleting) {
                val completionWidth = width.toFloat() * completionProgress
                canvas.drawRect(0f, 0f, completionWidth, barHeight, paint)
            } else if (barWidth > 0) {
                val endX = (barX + barWidth).coerceAtMost(width.toFloat())
                canvas.drawRect(barX, 0f, endX, barHeight, paint)
            }
        }
    }
}