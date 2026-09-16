package com.ghost.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ReactorState {
        ONLINE,
        ACTIVE,
        PROCESSING,
        ALERT
    }

    private var currentState: ReactorState = ReactorState.ONLINE
    private var rotationAngle = 0f
    private var pulseFactor = 0.5f

    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF")
    }

    private val coilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#54EFFF")
    }

    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3300E5FF")
    }

    private val centerNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val arcBounds = RectF()

    init {
        startAnimations()
    }

    private fun startAnimations() {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0.35f, 0.85f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseFactor = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setReactorState(state: ReactorState) {
        currentState = state
        when (state) {
            ReactorState.ONLINE -> {
                outerRingPaint.color = Color.parseColor("#00E5FF")
                coilPaint.color = Color.parseColor("#54EFFF")
                coreGlowPaint.color = Color.parseColor("#3300E5FF")
                centerNodePaint.color = Color.parseColor("#00E5FF")
            }
            ReactorState.ACTIVE -> {
                outerRingPaint.color = Color.parseColor("#00B0FF")
                coilPaint.color = Color.parseColor("#80D8FF")
                coreGlowPaint.color = Color.parseColor("#5500B0FF")
                centerNodePaint.color = Color.parseColor("#40C4FF")
            }
            ReactorState.PROCESSING -> {
                outerRingPaint.color = Color.parseColor("#00E676")
                coilPaint.color = Color.parseColor("#69F0AE")
                coreGlowPaint.color = Color.parseColor("#4400E676")
                centerNodePaint.color = Color.parseColor("#00E676")
            }
            ReactorState.ALERT -> {
                outerRingPaint.color = Color.parseColor("#FF1744")
                coilPaint.color = Color.parseColor("#FF5252")
                coreGlowPaint.color = Color.parseColor("#55FF1744")
                centerNodePaint.color = Color.parseColor("#FF1744")
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (min(width, height) / 2f) - 16f
        if (maxRadius <= 0) return

        // 1. Core ambient energy glow
        canvas.drawCircle(cx, cy, maxRadius * (0.6f * pulseFactor), coreGlowPaint)

        // 2. Outer containment ring
        canvas.drawCircle(cx, cy, maxRadius, outerRingPaint)

        // 3. Middle segmented ring
        val middleRadius = maxRadius * 0.72f
        arcBounds.set(cx - middleRadius, cy - middleRadius, cx + middleRadius, cy + middleRadius)
        canvas.drawArc(arcBounds, rotationAngle, 45f, false, outerRingPaint)
        canvas.drawArc(arcBounds, rotationAngle + 90f, 45f, false, outerRingPaint)
        canvas.drawArc(arcBounds, rotationAngle + 180f, 45f, false, outerRingPaint)
        canvas.drawArc(arcBounds, rotationAngle + 270f, 45f, false, outerRingPaint)

        // 4. Arc Reactor 10 Electromagnetic Coils
        val numCoils = 10
        val coilInner = maxRadius * 0.76f
        val coilOuter = maxRadius * 0.94f
        for (i in 0 until numCoils) {
            val angleDeg = (i * (360f / numCoils)) + (rotationAngle * 0.5f)
            val rad = Math.toRadians(angleDeg.toDouble())
            val x1 = cx + (coilInner * cos(rad)).toFloat()
            val y1 = cy + (coilInner * sin(rad)).toFloat()
            val x2 = cx + (coilOuter * cos(rad)).toFloat()
            val y2 = cy + (coilOuter * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, coilPaint)
        }

        // 5. Inner Core Ring
        val innerRadius = maxRadius * 0.40f
        canvas.drawCircle(cx, cy, innerRadius, outerRingPaint)

        // 6. Central Repulsor Node
        val centerNodeRadius = maxRadius * 0.20f * (0.8f + (pulseFactor * 0.3f))
        canvas.drawCircle(cx, cy, centerNodeRadius, centerNodePaint)
    }

    override fun onDetachedFromWindow() {
        rotationAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
