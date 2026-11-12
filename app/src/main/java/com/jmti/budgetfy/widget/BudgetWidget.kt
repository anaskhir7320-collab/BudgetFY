package com.jmti.budgetfy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.TaskStackBuilder
import com.jmti.budgetfy.CaptureReceiptActivity
import com.jmti.budgetfy.MainActivity
import com.jmti.budgetfy.R
import com.jmti.budgetfy.utils.ThemePrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home screen widget: this month's spending against the budget, the last
 * receipt, and a Scan receipt button.
 *
 * The app pushes the numbers ([save]) whenever Home loads, so the widget
 * never reads Firestore itself. The background is the same picture the user
 * chose for the Home spending card, with the same dark tint.
 */
class BudgetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id) // resized: redraw background and bar at the new size
    }

    companion object {
        private const val PREFS = "budgetfy_widget"

        /** Called by Home after it loads; [lastStore] is null when there are no receipts. */
        fun save(context: Context, spent: Double, budget: Double, lastStore: String?, lastAmount: Double?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("month", monthKey())
                .putString("spent", spent.toString())
                .putString("budget", budget.toString())
                .putString("lastStore", lastStore)
                .putString("lastAmount", lastAmount?.toString())
                .apply()
            refresh(context)
        }

        /** After logging out, the widget shows nothing personal. */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            refresh(context)
        }

        /** Redraws every BudgetFY widget (e.g. after Day / Night or a new card picture). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
            ids.forEach { render(context, manager, it) }
        }

        private fun monthKey() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sameMonth = data.getString("month", null) == monthKey()
            // A new month started and the app hasn't been opened yet: nothing spent so far.
            val spent = if (sameMonth) data.getString("spent", null)?.toDoubleOrNull() ?: 0.0 else 0.0
            val budget = data.getString("budget", null)?.toDoubleOrNull() ?: 0.0
            val lastStore = data.getString("lastStore", null)
            val lastAmount = data.getString("lastAmount", null)?.toDoubleOrNull()

            // Size of this widget in pixels (portrait: min width, max height).
            val options = manager.getAppWidgetOptions(id)
            val density = context.resources.displayMetrics.density
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 150
            // Keep bitmaps small: widgets have a memory limit for images.
            val scale = minOf(density, 900f / wDp)
            val wPx = (wDp * scale).toInt()
            val hPx = (hDp * scale).toInt()

            val night = ThemePrefs.isNight(context)
            val picture = loadCardPicture(context, wPx, hPx)
            val onImage = picture != null

            val colTitle: Int; val colMain: Int; val colSub: Int; val colAccent: Int
            val btnBg: Int; val chipBg: Int; val colChip: Int; val track: Int
            when {
                onImage -> {
                    colTitle = 0xE6FFFFFF.toInt(); colMain = Color.WHITE; colSub = 0xE6FFFFFF.toInt()
                    colAccent = Color.WHITE; btnBg = R.drawable.bg_widget_btn_image
                    chipBg = R.drawable.bg_widget_chip_image; colChip = Color.WHITE; track = 0x40FFFFFF
                }
                night -> {
                    colTitle = 0xFFA3A9B3.toInt(); colMain = 0xFFECEEF2.toInt(); colSub = 0xFFA3A9B3.toInt()
                    colAccent = 0xFF5AB4FF.toInt(); btnBg = R.drawable.bg_widget_btn_night
                    chipBg = R.drawable.bg_widget_chip_night; colChip = 0xFFA3A9B3.toInt(); track = 0xFF2A2F38.toInt()
                }
                else -> {
                    colTitle = 0xFF6B6B6B.toInt(); colMain = 0xFF1A1A1A.toInt(); colSub = 0xFF6B6B6B.toInt()
                    colAccent = 0xFF0078CC.toInt(); btnBg = R.drawable.bg_widget_btn_day
                    chipBg = R.drawable.bg_widget_chip_day; colChip = 0xFF555555.toInt(); track = 0xFFEEF0F4.toInt()
                }
            }

            val pct = if (budget > 0) (spent / budget * 100).toInt() else 0
            val barColor = when {
                pct >= 100 -> if (night && !onImage) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt()
                pct >= 80 -> if (night && !onImage) 0xFFFFB74D.toInt() else 0xFFE65100.toInt()
                onImage -> Color.WHITE
                else -> if (night) 0xFF2AA3FF.toInt() else 0xFF0096FF.toInt()
            }

            val cal = Calendar.getInstance()
            val daysLeft = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH)
            val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
            fun rm(v: Double) = String.format("%.2f", v)

            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            views.setImageViewBitmap(R.id.widgetBackground, drawBackground(picture, wPx, hPx, scale, night))
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title, month))
            views.setTextViewText(
                R.id.widgetDaysLeft,
                if (daysLeft == 1) context.getString(R.string.widget_one_day_left)
                else context.getString(R.string.widget_days_left, daysLeft)
            )
            views.setTextViewText(R.id.widgetSpent, "RM ${rm(spent)}")
            views.setTextViewText(
                R.id.widgetBudgetLine,
                if (budget > 0) {
                    val left = budget - spent
                    context.getString(R.string.of_budget, rm(budget)) + " · " +
                            if (left >= 0) context.getString(R.string.rm_left, rm(left))
                            else context.getString(R.string.rm_over, rm(-left))
                } else context.getString(R.string.this_month)
            )
            views.setImageViewBitmap(
                R.id.widgetBar,
                drawBar((wPx - 32 * scale).toInt().coerceAtLeast(10), (6 * scale).toInt(), pct, barColor, track)
            )

            views.setTextColor(R.id.widgetTitle, colTitle)
            views.setTextColor(R.id.widgetDaysLeft, colTitle)
            views.setTextColor(R.id.widgetSpent, colMain)
            views.setTextColor(R.id.widgetBudgetLine, colSub)
            views.setTextColor(R.id.widgetScanText, colAccent)
            views.setInt(R.id.widgetScanIcon, "setColorFilter", colAccent)
            views.setInt(R.id.widgetScanButton, "setBackgroundResource", btnBg)
            views.setInt(R.id.widgetLast, "setBackgroundResource", chipBg)
            views.setTextColor(R.id.widgetLast, colChip)

            if (lastStore != null && lastAmount != null) {
                views.setViewVisibility(R.id.widgetLast, View.VISIBLE)
                views.setTextViewText(R.id.widgetLast, context.getString(R.string.widget_last, lastStore, rm(lastAmount)))
            } else {
                views.setViewVisibility(R.id.widgetLast, View.GONE)
            }
            // Short widgets (resized to 1 row high) hide the buttons.
            views.setViewVisibility(R.id.widgetBottomRow, if (hDp >= 100) View.VISIBLE else View.GONE)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Tap anywhere: open the app.
            val openApp = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                android.R.id.background, PendingIntent.getActivity(context, 0, openApp, flags)
            )
            // Scan receipt: open the scan page, with Home behind it for Back.
            val scan = TaskStackBuilder.create(context)
                .addNextIntent(Intent(context, MainActivity::class.java))
                .addNextIntent(Intent(context, CaptureReceiptActivity::class.java))
                .getPendingIntent(1, flags)
            views.setOnClickPendingIntent(R.id.widgetScanButton, scan)

            manager.updateAppWidget(id, views)
        }

        /** The Home card picture, decoded at about the widget's size; null if none. */
        private fun loadCardPicture(context: Context, w: Int, h: Int): Bitmap? {
            val uri = context.getSharedPreferences("budgetfy_prefs", Context.MODE_PRIVATE)
                .getString("card_bg_uri", null) ?: return null
            return try {
                val parsed = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
                context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Rounded card: the picture (centre-cropped) under a 60% dark tint, or the plain card colour. */
        private fun drawBackground(picture: Bitmap?, w: Int, h: Int, scale: Float, night: Boolean): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val radius = 24 * scale
            val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
            canvas.clipPath(clip)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (picture != null) {
                val s = maxOf(w.toFloat() / picture.width, h.toFloat() / picture.height)
                val srcW = (w / s).toInt(); val srcH = (h / s).toInt()
                val left = (picture.width - srcW) / 2; val top = (picture.height - srcH) / 2
                canvas.drawBitmap(picture, Rect(left, top, left + srcW, top + srcH), Rect(0, 0, w, h), paint)
                canvas.drawColor(0x99000000.toInt()) // same tint as the Home card
            } else {
                canvas.drawColor(if (night) 0xFF181B21.toInt() else Color.WHITE)
            }
            return out
        }

        private fun drawBar(w: Int, h: Int, pct: Int, color: Int, track: Int): Bitmap {
            val out = Bitmap.createBitmap(w, maxOf(h, 2), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = out.height / 2f
            paint.color = track
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), out.height.toFloat()), r, r, paint)
            val filled = w * (pct.coerceIn(0, 100) / 100f)
            if (filled > 0f) {
                paint.color = color
                canvas.drawRoundRect(RectF(0f, 0f, maxOf(filled, out.height.toFloat()), out.height.toFloat()), r, r, paint)
            }
            return out
        }
    }
}
