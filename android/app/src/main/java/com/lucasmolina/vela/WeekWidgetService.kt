package com.lucasmolina.vela

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class WeekWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val segments = intent.data?.pathSegments.orEmpty()
        val widgetId = segments.getOrNull(0)?.toIntOrNull()
            ?: intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return Factory(applicationContext, widgetId)
    }

    private class Factory(
        private val context: Context,
        private val widgetId: Int,
    ) : RemoteViewsFactory {
        private var rows: List<List<DayMark>> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val board = Agenda.week(context, VelaWidgetProvider.weekOffset(context, widgetId))
            val count = board.days.maxOfOrNull { day ->
                day.marks.indexOfLast { it.kind != MarkKind.EMPTY } + 1
            } ?: 0
            rows = (0 until count.coerceAtLeast(0)).map { slot ->
                board.days.map { day -> day.marks.getOrElse(slot) { DayMark("", MarkKind.EMPTY) } }
            }.filter { row ->
                row.none { mark ->
                    mark.kind == MarkKind.START || mark.kind == MarkKind.MID || mark.kind == MarkKind.END
                } && row.any { it.kind != MarkKind.EMPTY }
            }
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount() = rows.size

        override fun getViewTypeCount() = 1

        override fun getItemId(position: Int) = position.toLong()

        override fun hasStableIds() = true

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val marks = rows.getOrElse(position) { emptyList() }
            val row = RemoteViews(context.packageName, R.layout.widget_week_lane)
            if (isSpan(marks)) {
                row.setViewVisibility(R.id.span_row, View.VISIBLE)
                row.setViewVisibility(R.id.chip_row, View.GONE)
                bindSpan(row, marks)
            } else {
                row.setViewVisibility(R.id.span_row, View.GONE)
                row.setViewVisibility(R.id.chip_row, View.VISIBLE)
                bindChips(row, marks, position)
            }
            row.setOnClickFillInIntent(R.id.lane_root, Intent())
            return row
        }

        private fun isSpan(marks: List<DayMark>): Boolean {
            return marks.any { it.kind == MarkKind.START || it.kind == MarkKind.MID || it.kind == MarkKind.END }
        }

        private fun bindChips(row: RemoteViews, marks: List<DayMark>, position: Int) {
            laneIds.forEachIndexed { index, id ->
                val mark = marks.getOrElse(index) { DayMark("", MarkKind.EMPTY) }
                if (mark.kind == MarkKind.EMPTY) {
                    row.setTextViewText(id, "")
                    row.setViewVisibility(id, View.INVISIBLE)
                } else {
                    row.setViewVisibility(id, View.VISIBLE)
                    row.setTextViewText(id, mark.text)
                    row.setTextColor(id, context.getColor(if (mark.holiday) R.color.holiday_ink else R.color.chip_ink))
                    val background = when {
                        mark.holiday -> R.drawable.widget_holiday_chip
                        position % 2 == 0 -> R.drawable.widget_event_chip
                        else -> R.drawable.widget_event_chip_alt
                    }
                    row.setInt(id, "setBackgroundResource", background)
                }
            }
        }

        private fun bindSpan(row: RemoteViews, marks: List<DayMark>) {
            val span = marks.indices.filter {
                val kind = marks[it].kind
                kind == MarkKind.START || kind == MarkKind.MID || kind == MarkKind.END
            }
            val start = span.firstOrNull() ?: 0
            val end = span.lastOrNull() ?: start
            spanIds.forEachIndexed { index, id ->
                if (index !in start..end) {
                    row.setViewVisibility(id, View.INVISIBLE)
                    return@forEachIndexed
                }
                val kind = marks[index].kind
                val holiday = marks[index].holiday
                val background = when {
                    holiday && index == start && index == end -> R.drawable.widget_holiday_chip
                    holiday && index == start -> R.drawable.widget_holiday_bar_start
                    holiday && index == end -> R.drawable.widget_holiday_bar_end
                    holiday -> R.drawable.widget_holiday_bar_mid
                    index == start && index == end -> R.drawable.widget_event_chip
                    index == start && (kind == MarkKind.START || kind == MarkKind.CHIP) -> R.drawable.widget_bar_start
                    index == end && (kind == MarkKind.END || kind == MarkKind.CHIP) -> R.drawable.widget_bar_end
                    else -> R.drawable.widget_bar_mid
                }
                row.setViewVisibility(id, View.VISIBLE)
                row.setInt(id, "setBackgroundResource", background)
                if (Build.VERSION.SDK_INT >= 31) {
                    val startMargin = if (index == start) 1f else -1f
                    val endMargin = if (index == end) 1f else -1f
                    row.setViewLayoutMargin(id, RemoteViews.MARGIN_START, startMargin, TypedValue.COMPLEX_UNIT_DIP)
                    row.setViewLayoutMargin(id, RemoteViews.MARGIN_END, endMargin, TypedValue.COMPLEX_UNIT_DIP)
                }
            }
            val title = marks.firstOrNull { it.text.isNotEmpty() }?.text.orEmpty()
            val holiday = marks.any { it.holiday }
            row.setTextViewText(R.id.span_title, title)
            row.setTextColor(R.id.span_title, context.getColor(if (holiday) R.color.holiday_ink else R.color.chip_ink))
            if (Build.VERSION.SDK_INT >= 31 && span.isNotEmpty()) {
                val column = columnWidthDp()
                val length = end - start + 1
                row.setViewLayoutWidth(R.id.span_title, column * length, TypedValue.COMPLEX_UNIT_DIP)
                row.setViewLayoutMargin(
                    R.id.span_title,
                    RemoteViews.MARGIN_START,
                    column * start,
                    TypedValue.COMPLEX_UNIT_DIP,
                )
                row.setInt(R.id.span_title, "setGravity", Gravity.CENTER)
            }
        }

        private fun columnWidthDp(): Float {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val reported = when {
                min > 0 && max > 0 -> (min + max) / 2
                min > 0 -> min
                max > 0 -> max
                else -> 250
            }
            return ((reported - 16f) / 7f).coerceAtLeast(24f)
        }
    }

    companion object {
        private val laneIds = intArrayOf(
            R.id.lane0, R.id.lane1, R.id.lane2, R.id.lane3, R.id.lane4, R.id.lane5, R.id.lane6,
        )
        private val spanIds = intArrayOf(
            R.id.span0, R.id.span1, R.id.span2, R.id.span3, R.id.span4, R.id.span5, R.id.span6,
        )
    }
}
