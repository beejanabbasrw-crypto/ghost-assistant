package com.ghost.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ghost.assistant.theme.ThemeManager

class GhostOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var hudView: TacticalReticleView
    private lateinit var brain: GhostBrain
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()

        brain = GhostBrain(this) { state ->
            hudView.post {
                hudView.setState(state)
            }
        }

        setupOverlayWindow()

        if (ThemeManager.isWakeWordEnabled(this)) {
            hudView.postDelayed({
                brain.startListening(isWakeWordLoop = true)
            }, 800)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "ghost_tactical_hud"
        val channelName = "G.H.O.S.T. Tactical Overlay"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("G.H.O.S.T. HUD Engaged")
            .setContentText("Autonomous System Assistant active on screen.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1337, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayWindow() {
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        layoutParams = WindowManager.LayoutParams(
            180,
            180,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        hudView = TacticalReticleView(this).apply {
            setOnClickListener {
                brain.startListening(isWakeWordLoop = false)
            }
        }

        hudView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = true

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10) {
                            isClick = false
                        }
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(hudView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(hudView, layoutParams)
    }

    override fun onDestroy() {
        brain.destroy()
        if (::hudView.isInitialized) {
            windowManager.removeView(hudView)
        }
        super.onDestroy()
    }

    /**
     * Tactical custom vector view representing the G.H.O.S.T. reticle.
     */
    inner class TacticalReticleView(context: Context) : View(context) {

        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3300E5FF")
            style = Paint.Style.FILL
        }

        private var pulseRadiusRatio = 0.5f
        private var pulseAnimator: ValueAnimator? = null

        fun setState(state: GhostBrain.BrainState) {
            pulseAnimator?.cancel()
            when (state) {
                GhostBrain.BrainState.IDLE -> {
                    outerPaint.color = Color.parseColor("#00E5FF")
                    corePaint.color = Color.parseColor("#3300E5FF")
                    pulseRadiusRatio = 0.5f
                    invalidate()
                }
                GhostBrain.BrainState.LISTENING -> {
                    outerPaint.color = Color.parseColor("#FFB300")
                    corePaint.color = Color.parseColor("#55FFB300")
                    pulseAnimator = ValueAnimator.ofFloat(0.3f, 0.85f).apply {
                        duration = 700
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener {
                            pulseRadiusRatio = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
                GhostBrain.BrainState.PROCESSING -> {
                    outerPaint.color = Color.parseColor("#00E676")
                    corePaint.color = Color.parseColor("#4400E676")
                    pulseRadiusRatio = 0.7f
                    invalidate()
                }
                GhostBrain.BrainState.SPEAKING -> {
                    outerPaint.color = Color.parseColor("#00B0FF")
                    corePaint.color = Color.parseColor("#6600B0FF")
                    pulseRadiusRatio = 0.6f
                    invalidate()
                }
                GhostBrain.BrainState.ERROR -> {
                    outerPaint.color = Color.parseColor("#FF1744")
                    corePaint.color = Color.parseColor("#55FF1744")
                    pulseRadiusRatio = 0.4f
                    invalidate()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val baseRadius = (Math.min(width, height) / 2f) - 10f

            // Inner core pulse
            canvas.drawCircle(cx, cy, baseRadius * pulseRadiusRatio, corePaint)

            // Outer ring
            canvas.drawCircle(cx, cy, baseRadius, outerPaint)

            // Crosshair markers
            val crosshairLen = 12f
            canvas.drawLine(cx - baseRadius, cy, cx - baseRadius + crosshairLen, cy, outerPaint)
            canvas.drawLine(cx + baseRadius - crosshairLen, cy, cx + baseRadius, cy, outerPaint)
            canvas.drawLine(cx, cy - baseRadius, cx, cy - baseRadius + crosshairLen, outerPaint)
            canvas.drawLine(cx, cy + baseRadius - crosshairLen, cx, cy + baseRadius, outerPaint)
        }
    }
}
