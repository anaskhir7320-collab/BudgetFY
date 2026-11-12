package com.jmti.budgetfy.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.jmti.budgetfy.utils.CategoryStyle

/**
 * Thin bar split into one rounded segment per category, each as wide as
 * that category's share of the spend.
 */
class CategorySpendBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val gap = 2f * resources.displayMetrics.density
    private var segments: List<Pair<String, Double>> = emptyList()

    fun setSpend(spend: List<Pair<String, Double>>) {
        segments = spend
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = segments.sumOf { it.second }
        if (total <= 0.0) return
        val h = height.toFloat()
        val radius = h / 2f
        val usable = width - gap * (segments.size - 1)
        var x = 0f
        segments.forEachIndexed { i, (category, amount) ->
            // Keep tiny categories visible as at least a dot.
            val w = (usable * (amount / total)).toFloat().coerceAtLeast(h)
            val right = if (i == segments.lastIndex) width.toFloat() else (x + w).coerceAtMost(width.toFloat())
            if (right > x) {
                paint.color = CategoryStyle.color(context, category)
                rect.set(x, 0f, right, h)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            x = right + gap
        }
    }
}
