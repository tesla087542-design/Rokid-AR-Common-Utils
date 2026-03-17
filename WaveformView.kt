package com.museum.guide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 正弦波形动画 View，用于唤醒词激活后的 UI 反馈。
 * 调用 start() / stop() 控制动画。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF66")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private var phase = 0f
    private var running = false

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.12f
            invalidate()
            postDelayed(this, 16)   // ~60fps
        }
    }

    fun start() {
        if (running) return
        running = true
        post(animator)
    }

    fun stop() {
        running = false
        removeCallbacks(animator)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = h / 2f
        val amplitude = cx * 0.6f
        val step = 4f
        val path = android.graphics.Path()
        var first = true
        var x = 0f
        while (x <= w) {
            val y = cx + amplitude * sin((x / w * 4 * Math.PI + phase).toFloat())
            if (first) { path.moveTo(x, y); first = false }
            else path.lineTo(x, y)
            x += step
        }
        canvas.drawPath(path, paint)
    }
}
