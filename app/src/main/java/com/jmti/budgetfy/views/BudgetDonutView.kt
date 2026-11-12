package com.jmti.budgetfy.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Donut for "Budget by category": one arc per category sized by what was
 * spent against the total of all category budgets; the grey track is the
 * budget still left. Centre shows "RM spent" and "of RM budget · %".
 */
class BudgetDonutView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = 13f * density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = androidx.core.content.ContextCompat.getColor(context, com.jmti.budgetfy.R.color.donut_track)
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * density
        color = androidx.core.content.ContextCompat.getColor(context, com.jmti.budgetfy.R.color.text_primary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * density
        color = androidx.core.content.ContextCompat.getColor(context, com.jmti.budgetfy.R.color.text_secondary)
    }
    private val oval = RectF()

    private var segments: List<Pair<Int, Double>> = emptyList()
    private var base = 0.0
    private var centerTop = ""
    private var centerBottom = ""

    /** [segments] = (colour, amount spent); [budgetTotal] = sum of the category limits. */
    fun setData(segments: List<Pair<Int, Double>>, budgetTotal: Double, top: String, bottom: String) {
        this.segments = segments.filter { it.second > 0.0 }
        // Over budget overall: the ring is simply full, split by spend.
        base = maxOf(budgetTotal, this.segments.sumOf { it.second })
        centerTop = top
        centerBottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val inset = stroke / 2f + 1f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        oval.set(left + inset, top + inset, left + size - inset, top + size - inset)

        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        if (base > 0.0) {
            val gap = if (segments.size > 1) 1.5f else 0f
            var start = -90f
            segments.forEach { (color, amount) ->
                // Keep tiny categories visible as a sliver.
                val sweep = (360.0 * amount / base).toFloat().coerceAtLeast(2.5f)
                arcPaint.color = color
                canvas.drawArc(oval, start, (sweep - gap).coerceAtLeast(1f), false, arcPaint)
                start += sweep
            }
        }

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(centerTop, cx, cy, bigText)
        canvas.drawText(centerBottom, cx, cy + 18f * density, smallText)
    }
}
