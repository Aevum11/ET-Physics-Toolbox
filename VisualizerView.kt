package com.et.physics.toolbox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class VisualizerView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER }

    var pitch: Double = 0.0
    var roll: Double = 0.0
    var vibration: Double = 0.0
    var spl: Double = 0.0
    var lux: Double = 0.0
    
    private val splHistory = FloatArray(100)
    private var historyIdx = 0

    fun update(p: Double, r: Double, v: Double, s: Double, l: Double) {
        pitch = p; roll = r; vibration = v; spl = s; lux = l
        splHistory[historyIdx] = s.toFloat()
        historyIdx = (historyIdx + 1) % 100
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#111111"))

        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2

        // 1. Bubble Level (Center)
        paint.color = Color.DKGRAY
        canvas.drawCircle(cx, cy, 100f, paint)
        paint.style = Paint.Style.STROKE; paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 100f, paint)
        
        val maxTilt = 45.0
        val bx = cx + (roll.coerceIn(-maxTilt, maxTilt) / maxTilt * 80).toFloat()
        val by = cy + (pitch.coerceIn(-maxTilt, maxTilt) / maxTilt * 80).toFloat()
        
        paint.style = Paint.Style.FILL
        paint.color = if (Math.abs(roll) < 1 && Math.abs(pitch) < 1) Color.GREEN else Color.YELLOW
        canvas.drawCircle(bx, by, 15f, paint)
        canvas.drawText("LEVEL", cx, cy + 140f, textPaint)

        // 2. Lux Bar (Left)
        val barW = 40f; val barH = 200f
        val lx = 50f; val ly = cy - 100f
        paint.color = Color.DKGRAY
        canvas.drawRect(lx, ly, lx + barW, ly + barH, paint)
        
        // Logarithmic scaling for Lux (0-1000+)
        val normLux = min(1.0, kotlin.math.log10(lux + 1) / 4.0).toFloat() // 10000 lux = full bar
        paint.color = Color.WHITE
        canvas.drawRect(lx, ly + barH * (1 - normLux), lx + barW, ly + barH, paint)
        canvas.drawText("${lux.toInt()} LX", lx + 20, ly + barH + 30, textPaint)

        // 3. SPL Graph (Right)
        val graphLeft = w - 150f; val graphRight = w - 20f
        val graphTop = cy - 100f; val graphBottom = cy + 100f
        
        paint.color = Color.DKGRAY
        canvas.drawRect(graphLeft, graphTop, graphRight, graphBottom, paint)
        paint.color = Color.CYAN; paint.strokeWidth = 3f
        
        var prevX = graphLeft; var prevY = graphBottom
        for (i in 0 until 100) {
            val idx = (historyIdx + i) % 100
            val x = graphLeft + (i.toFloat() / 100) * (graphRight - graphLeft)
            val valDb = splHistory[idx].coerceIn(30f, 100f)
            val y = graphBottom - ((valDb - 30) / 70) * (graphBottom - graphTop)
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x; prevY = y
        }
        canvas.drawText("${spl.toInt()} dB", (graphLeft+graphRight)/2, cy + 140f, textPaint)
    }
}