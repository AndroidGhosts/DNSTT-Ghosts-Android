package com.androidghosts.dnsttghosts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/** Subtle animated Matrix-style backdrop. Decorative only; it never intercepts touches. */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val glyphs = "01アイウエオカキクケコサシスセソタチツテトナニヌネノ<>/{}[]"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textSize = 13f * resources.displayMetrics.scaledDensity
        color = 0x6639FF14
    }
    private var drops = FloatArray(0)
    private var speeds = FloatArray(0)
    private var symbols = CharArray(0)
    private var frame = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = max(1, w / max(12, paint.textSize.toInt() + 2))
        drops = FloatArray(columns) { -Random.nextInt(1, h.coerceAtLeast(2)).toFloat() }
        speeds = FloatArray(columns) { Random.nextFloat() * 1.8f + 0.7f }
        symbols = CharArray(columns) { glyphs[Random.nextInt(glyphs.length)] }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || drops.isEmpty()) return
        val step = max(12f, paint.textSize + 2f)
        for (i in drops.indices) {
            val y = drops[i]
            paint.color = if (y in 0f..step) 0xCC39FF14.toInt() else 0x6639FF14
            paint.alpha = if (y in 0f..step) 210 else Random.nextInt(35, 100)
            canvas.drawText(symbols[i].toString(), i * step, y, paint)
            if (frame % 5 == 0) symbols[i] = glyphs[Random.nextInt(glyphs.length)]
            drops[i] += speeds[i] * step * 0.16f
            if (drops[i] > height + 80) {
                drops[i] = -Random.nextInt(20, height.coerceAtLeast(21)).toFloat()
                speeds[i] = Random.nextFloat() * 1.8f + 0.7f
            }
        }
        frame++
        postInvalidateOnAnimation()
    }
}
