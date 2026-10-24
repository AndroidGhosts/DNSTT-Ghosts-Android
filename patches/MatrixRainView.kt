package com.androidghosts.dnsttghosts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Low-cost Matrix rain background for Android_Ghosts.
 * Decorative only: it never intercepts touch events and pauses when detached.
 */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val glyphs = "01アイウエオカキクケコサシスセソタチツテトナニヌネノ<>/{}[]"
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textSize = 13f * density
        color = 0x6639FF14
    }

    private var heads = FloatArray(0)
    private var speeds = FloatArray(0)
    private var tails = IntArray(0)
    private var columnsGlyphs = Array(0) { CharArray(0) }
    private var frame = 0
    private val step get() = max(12f * density, paint.textSize + 2f * density)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val count = max(1, (w / step).toInt() + 1)
        heads = FloatArray(count) { -Random.nextInt(1, max(2, h)).toFloat() }
        speeds = FloatArray(count) { Random.nextFloat() * 1.5f + 0.55f }
        tails = IntArray(count) { Random.nextInt(5, 15) }
        columnsGlyphs = Array(count) { CharArray(tails[it] + 2) { glyphs[Random.nextInt(glyphs.length)] } }
        frame = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || heads.isEmpty()) return
        val baselineStep = step
        for (column in heads.indices) {
            val x = column * baselineStep
            val headY = heads[column]
            val chars = columnsGlyphs[column]
            for (tailIndex in 0 until chars.size) {
                val y = headY - tailIndex * baselineStep
                if (y < -baselineStep || y > height + baselineStep) continue
                paint.color = when {
                    tailIndex == 0 -> 0xFFE3FFE8.toInt()
                    tailIndex < 3 -> 0xCC39FF14.toInt()
                    else -> 0x6639FF14
                }
                paint.alpha = when {
                    tailIndex == 0 -> 235
                    tailIndex < 3 -> 150
                    else -> max(12, 78 - tailIndex * 5)
                }
                canvas.drawText(chars[tailIndex].toString(), x, y, paint)
            }
            if (frame % 7 == 0) {
                val index = Random.nextInt(chars.size)
                chars[index] = glyphs[Random.nextInt(glyphs.length)]
            }
            heads[column] += speeds[column] * baselineStep * 0.18f
            if (heads[column] - chars.size * baselineStep > height) {
                heads[column] = -Random.nextInt(1, max(2, height)).toFloat()
                speeds[column] = Random.nextFloat() * 1.5f + 0.55f
                tails[column] = Random.nextInt(5, 15)
                columnsGlyphs[column] = CharArray(tails[column] + 2) {
                    glyphs[Random.nextInt(glyphs.length)]
                }
            }
        }
        frame++
        // 30 FPS is sufficient for a background and avoids burning battery at 60+ FPS.
        postInvalidateDelayed(33L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(null)
        super.onDetachedFromWindow()
    }
}
