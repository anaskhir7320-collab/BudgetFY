package com.jmti.budgetfy.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.jmti.budgetfy.R
import kotlin.math.max

/**
 * Slim spending chart for the current month.
 *
 * - CUMULATIVE: running total as a line with a fading blue fill, an end dot,
 *   a dashed budget line and a faint "even pace" guide (0 → budget by month end).
 * - DAILY: one rounded bar per day; today's bar is solid blue.
 */
class SpendingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { CUMULATIVE, DAILY }

    var mode: Mode = Mode.CUMULATIVE
        set(value) {
            field = value
            animateIn()
        }

    private var daily = DoubleArray(0)
    private var daysInMonth = 30
    private var today = 1
    private var budget = 0.0
    private var progress = 1f

    private val density = resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.brand_blue)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = blue
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val budgetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    }
    private val pacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.stroke_strong)
        pathEffect = DashPathEffect(floatArrayOf(2 * density, 4 * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_tertiary)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textAlign = Paint.Align.CENTER
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.stroke)
    }

    private val path = Path()
    private val fillPath = Path()
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    /**
     * @param dailyTotals spending per day, index 0 = day 1
     * @param today day of month (1-based); later days are not drawn
     */
    fun setData(dailyTotals: DoubleArray, daysInMonth: Int, today: Int, budget: Double) {
        this.daily = dailyTotals
        this.daysInMonth = max(daysInMonth, 1)
        this.today = today.coerceIn(1, this.daysInMonth)
        this.budget = budget
        animateIn()
    }

    private fun animateIn() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + 14 * density          // room for the budget label
        val bottom = (height - paddingBottom).toFloat() - 4 * density
        if (right <= left || bottom <= top) return

        canvas.drawLine(left, bottom, right, bottom, baselinePaint)

        val shown = daily.take(today)
        if (shown.sum() <= 0.0) {
            canvas.drawText("No spending yet this month", (left + right) / 2, (top + bottom) / 2, emptyPaint)
            return
        }

        when (mode) {
            Mode.CUMULATIVE -> drawCumulative(canvas, left, top, right, bottom)
            Mode.DAILY -> drawDaily(canvas, left, top, right, bottom)
        }
    }

    private fun xFor(dayIndex: Int, left: Float, right: Float): Float {
        // dayIndex 0..daysInMonth-1 mapped across the full month width
        val steps = max(daysInMonth - 1, 1)
        return left + (right - left) * dayIndex / steps
    }

    private fun drawCumulative(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val running = DoubleArray(today)
        var sum = 0.0
        for (i in 0 until today) {
            sum += daily.getOrElse(i) { 0.0 }
            running[i] = sum
        }
        val maxValue = max(running.last(), budget).coerceAtLeast(1.0) * 1.08
        fun yFor(v: Double) = (bottom - (bottom - top) * (v / maxValue)).toFloat()

        // Even-pace guide: 0 on day 1 → budget on the last day
        if (budget > 0) {
            canvas.drawLine(left, bottom, right, yFor(budget), pacePaint)
            val by = yFor(budget)
            canvas.drawLine(left, by, right, by, budgetPaint)
            val label = "Budget RM %.0f".format(budget)
            canvas.drawText(label, right - labelPaint.measureText(label), by - 5 * density, labelPaint)
        }

        // Reveal left → right while animating
        val lastIndex = today - 1
        val revealX = left + (xFor(lastIndex, left, right) - left) * progress

        path.reset()
        fillPath.reset()
        for (i in 0..lastIndex) {
            val x = xFor(i, left, right)
            val y = yFor(running[i])
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val endX = xFor(lastIndex, left, right)
        fillPath.lineTo(endX, bottom)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            ContextCompat.getColor(context, R.color.chart_fill_top),
            ContextCompat.getColor(context, R.color.chart_fill_bottom),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipRect(left - 8 * density, 0f, revealX + 8 * density, height.toFloat())
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
        canvas.restore()

        if (progress >= 0.98f) {
            val ey = yFor(running[lastIndex])
            canvas.drawCircle(endX, ey, 5.5f * density, dotRingPaint)
            canvas.drawCircle(endX, ey, 3.8f * density, dotPaint)
        }
    }

    private fun drawDaily(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val maxValue = (daily.take(today).maxOrNull() ?: 0.0).coerceAtLeast(1.0) * 1.1
        val slot = (right - left) / daysInMonth
        val barWidth = (slot * 0.62f).coerceAtMost(10 * density)
        val radius = barWidth / 2

        for (i in 0 until today) {
            val value = daily.getOrElse(i) { 0.0 }
            if (value <= 0.0) continue
            val cx = left + slot * i + slot / 2
            val h = ((bottom - top) * (value / maxValue)).toFloat() * progress
            rect.set(cx - barWidth / 2, bottom - max(h, barWidth), cx + barWidth / 2, bottom)
            barPaint.color = if (i == today - 1) blue else (blue and 0x00FFFFFF) or 0x66000000
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }
}
