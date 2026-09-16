package com.ghost.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 18
    private val barPaints = Array(barCount) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val barHeights = FloatArray(barCount) { 0.15f }
    private val targetHeights = FloatArray(barCount) { 0.15f }
    private val barRect = RectF()

    private var isPlaying = false
    private var animator: ValueAnimator? = null

    init {
        for (i in 0 until barCount) {
            barPaints[i].style = Paint.Style.FILL
            barPaints[i].color = if (i % 2 == 0) Color.parseColor("#00E5FF") else Color.parseColor("#0091EA")
        }
        setupAnimator()
    }

    private fun setupAnimator() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (isPlaying) {
                    for (i in 0 until barCount) {
                        targetHeights[i] = 0.15f + Random.nextFloat() * 0.80f
                        barHeights[i] = barHeights[i] + (targetHeights[i] - barHeights[i]) * 0.4f
                    }
                } else {
                    for (i in 0 until barCount) {
                        barHeights[i] = barHeights[i] + (0.12f - barHeights[i]) * 0.2f
                    }
                }
                invalidate()
            }
            start()
        }
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val totalSpacing = (barCount + 1) * 6f
        val barWidth = (w - totalSpacing) / barCount

        for (i in 0 until barCount) {
            val left = 6f + i * (barWidth + 6f)
            val barH = h * barHeights[i]
            val top = h - barH
            val right = left + barWidth
            val bottom = h - 4f

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, 4f, 4f, barPaints[i])
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
