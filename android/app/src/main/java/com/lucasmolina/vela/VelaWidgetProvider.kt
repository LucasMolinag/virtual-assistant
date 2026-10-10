package com.lucasmolina.vela

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

class VelaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) publish(context, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        publish(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (id in ids) {
            prefs.remove(spanKey(id))
            prefs.remove(offsetKey(id, SPAN_WEEK))
            prefs.remove(offsetKey(id, SPAN_MONTH))
        }
        prefs.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (intent.action == ACTION_SET_SPAN) {
            val span = intent.getStringExtra(EXTRA_SPAN)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && (span == SPAN_WEEK || span == SPAN_MONTH)) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(spanKey(id), span).apply()
                refresh(context, id)
            }
            return
        }
        if (intent.action == ACTION_SHIFT && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val current = span(context, id)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = offsetKey(id, current)
            val next = if (intent.getBooleanExtra(EXTRA_TODAY, false)) 0 else prefs.getInt(key, 0) + intent.getIntExtra(EXTRA_DELTA, 0)
            prefs.edit().putInt(key, next).apply()
            refresh(context, id)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_SET_SPAN = "com.lucasmolina.vela.SET_SPAN"
        const val ACTION_SHIFT = "com.lucasmolina.vela.SHIFT"
        const val EXTRA_SPAN = "span"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_TODAY = "today"
        const val SPAN_WEEK = "week"
        const val SPAN_MONTH = "month"
        private const val PREFS = "vella_widget"
        private const val WEEK_SPAN_ROWS = 4
        private const val MONTH_LANE_CAP = 6
        private val monthLaneIds = listOf("a", "b", "e", "f", "g", "h")
        private val monthLabelIds = listOf("la", "lb", "le", "lf", "lg", "lh")

        private val weekNames = intArrayOf(
            R.id.col0_name, R.id.col1_name, R.id.col2_name, R.id.col3_name,
            R.id.col4_name, R.id.col5_name, R.id.col6_name,
        )
        private val weekNumbers = intArrayOf(
            R.id.col0_num, R.id.col1_num, R.id.col2_num, R.id.col3_num,
            R.id.col4_num, R.id.col5_num, R.id.col6_num,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VelaWidgetProvider::class.java))
            for (id in ids) publish(context, id)
        }

        fun weekOffset(context: Context, widgetId: Int): Int = offset(context, widgetId, SPAN_WEEK)

        private fun publish(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(id, render(context, id))
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_week_list)
        }

        private fun refresh(context: Context, id: Int) {
            publish(context, id)
        }

        private fun spanKey(id: Int) = "span_$id"

        private fun offsetKey(id: Int, span: String) = "offset_${span}_$id"

        private fun offset(context: Context, widgetId: Int, span: String): Int {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(offsetKey(widgetId, span), 0)
        }

        private fun span(context: Context, widgetId: Int): String {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(spanKey(widgetId), SPAN_MONTH) ?: SPAN_MONTH
        }

        private fun render(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_vela)
            val monthMode = span(context, widgetId) == SPAN_MONTH
            val open = PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_month, open)
            views.setOnClickPendingIntent(R.id.widget_week, open)
            views.setOnClickPendingIntent(R.id.toggle_week, spanIntent(context, widgetId, SPAN_WEEK))
            views.setOnClickPendingIntent(R.id.toggle_month, spanIntent(context, widgetId, SPAN_MONTH))
            views.setOnClickPendingIntent(R.id.widget_prev, shiftIntent(context, widgetId, -1))
            views.setOnClickPendingIntent(R.id.widget_next, shiftIntent(context, widgetId, 1))
            views.setOnClickPendingIntent(R.id.widget_today, shiftIntent(context, widgetId, 0))
            views.setTextViewText(R.id.widget_today, LocalDate.now().dayOfMonth.toString())
            views.setOnClickPendingIntent(R.id.widget_add, addIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_date, shiftIntent(context, widgetId, 0))
            styleToggle(context, views, R.id.toggle_week, !monthMode)
            styleToggle(context, views, R.id.toggle_month, monthMode)

            val allowed = Agenda.permitted(context)
            views.setViewVisibility(R.id.widget_status, if (allowed) View.GONE else View.VISIBLE)
            if (!allowed) views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_needs_permission))
            views.setViewVisibility(R.id.widget_weekdays, if (monthMode) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_month, if (monthMode) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_week, if (monthMode) View.GONE else View.VISIBLE)

            val ink = context.getColor(R.color.paper)
            val faint = context.getColor(R.color.faint)
            val accent = context.getColor(R.color.accent)
            var allowWeekEmpty = true
            if (monthMode) {
                val monthOffset = offset(context, widgetId, SPAN_MONTH)
                val weeks = visibleMonthWeeks(monthOffset)
                val board = Agenda.month(context, monthOffset, monthLaneCount(context, widgetId, weeks))
                views.setTextViewText(R.id.widget_date, board.heading)
                val chipInk = context.getColor(R.color.chip_ink)
                val columnDp = columnWidthDp(context, widgetId)
                val emptyMark = DayMark("", MarkKind.EMPTY)
                for (week in 0 until 6) {
                    val rowId = context.resources.getIdentifier("month_week$week", "id", context.packageName)
                    val weekUsed = (0 until 7).any { day -> board.cells[week * 7 + day].inMonth }
                    views.setViewVisibility(rowId, if (weekUsed) View.VISIBLE else View.GONE)
                    val laneUsed = BooleanArray(monthLaneIds.size)
                    for (day in 0 until 7) {
                        board.cells[week * 7 + day].marks.forEachIndexed { lane, mark ->
                            if (lane < laneUsed.size && mark.kind != MarkKind.EMPTY) laneUsed[lane] = true
                        }
                    }
                    for (day in 0 until 7) {
                        val index = week * 7 + day
                        val cell = board.cells[index]
                        val numberId = context.resources.getIdentifier("d$index", "id", context.packageName)
                        views.setTextViewText(numberId, cell.number)
                        views.setTextColor(numberId, if (cell.today) accent else if (cell.inMonth) ink else faint)
                        val dayId = context.resources.getIdentifier("cell$index", "id", context.packageName)
                        if (dayId != 0) {
                            views.setInt(
                                dayId,
                                "setBackgroundResource",
                                if (cell.today) R.drawable.widget_today else R.drawable.widget_clear,
                            )
                        }
                        monthLaneIds.forEachIndexed { lane, prefix ->
                            val id = context.resources.getIdentifier("$prefix$index", "id", context.packageName)
                            if (id != 0) {
                                bindBar(
                                    context,
                                    views,
                                    id,
                                    cell.marks.getOrElse(lane) { emptyMark },
                                    laneUsed[lane],
                                    if (cell.inMonth) chipInk else faint,
                                )
                            }
                        }
                        val moreId = context.resources.getIdentifier("c$index", "id", context.packageName)
                        if (cell.more.isEmpty()) {
                            views.setViewVisibility(moreId, View.GONE)
                        } else {
                            views.setViewVisibility(moreId, View.VISIBLE)
                            views.setTextViewText(moreId, cell.more)
                        }
                    }
                    monthLabelIds.forEachIndexed { lane, prefix ->
                        val marks = if (lane < board.cells[week * 7].marks.size) {
                            (0 until 7).map { day -> board.cells[week * 7 + day].marks[lane] }
                        } else {
                            emptyList()
                        }
                        placeSpanLabel(context, views, "$prefix$week", marks, columnDp, chipInk)
                    }
                }
            } else {
                val board = Agenda.week(context, offset(context, widgetId, SPAN_WEEK))
                views.setTextViewText(R.id.widget_date, board.heading)
                board.days.forEachIndexed { index, day ->
                    views.setTextViewText(weekNames[index], day.weekday)
                    views.setTextViewText(weekNumbers[index], day.number)
                    views.setTextColor(weekNames[index], if (day.today) accent else context.getColor(R.color.muted))
                    views.setTextColor(weekNumbers[index], if (day.today) accent else ink)
                }
                val hasSpan = bindWeekSpans(context, views, widgetId, board.days, context.getColor(R.color.chip_ink))
                val hasChip = board.days.any { day -> day.marks.any { it.kind == MarkKind.CHIP } }
                allowWeekEmpty = !hasSpan || hasChip
            }
            val weekList = Intent(context, WeekWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("vella://week-list/$widgetId/${offset(context, widgetId, SPAN_WEEK)}")
            }
            views.setRemoteAdapter(R.id.widget_week_list, weekList)
            views.setPendingIntentTemplate(R.id.widget_week_list, open)
            if (allowWeekEmpty) {
                views.setEmptyView(R.id.widget_week_list, R.id.widget_week_empty)
            } else {
                views.setViewVisibility(R.id.widget_week_empty, View.GONE)
            }
            return views
        }

        private fun bindWeekSpans(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            days: List<DayColumn>,
            color: Int,
        ): Boolean {
            val lanes = ArrayList<List<DayMark>>()
            val slots = days.maxOfOrNull { it.marks.size } ?: 0
            for (slot in 0 until slots) {
                val lane = days.map { day -> day.marks.getOrElse(slot) { DayMark("", MarkKind.EMPTY) } }
                if (lane.any { it.kind == MarkKind.START || it.kind == MarkKind.MID || it.kind == MarkKind.END }) {
                    lanes.add(lane)
                }
            }
            val columnDp = columnWidthDp(context, widgetId)
            for (row in 0 until WEEK_SPAN_ROWS) {
                val rowId = context.resources.getIdentifier("wspan_row$row", "id", context.packageName)
                if (row >= lanes.size) {
                    views.setViewVisibility(rowId, View.GONE)
                    continue
                }
                views.setViewVisibility(rowId, View.VISIBLE)
                val marks = lanes[row]
                for (day in 0 until 7) {
                    val id = context.resources.getIdentifier("wspan${row}_$day", "id", context.packageName)
                    val mark = marks[day]
                    bindBar(context, views, id, mark, true, color)
                }
                val titleId = "wspan${row}_title"
                placeSpanLabel(context, views, titleId, marks, columnDp, color)
            }
            return lanes.isNotEmpty()
        }

        private fun visibleMonthWeeks(offset: Int): Int {
            val first = LocalDate.now().withDayOfMonth(1).plusMonths(offset.toLong())
            var cursor = first.minusDays((first.dayOfWeek.value % 7).toLong())
            var weeks = 0
            repeat(6) {
                if ((0 until 7).any { day -> cursor.plusDays(day.toLong()).month == first.month }) weeks++
                cursor = cursor.plusWeeks(1)
            }
            return weeks.coerceAtLeast(1)
        }

        private fun monthLaneCount(context: Context, widgetId: Int, weeks: Int): Int {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val height = when {
                min > 0 && max > 0 -> maxOf(min, max)
                min > 0 -> min
                max > 0 -> max
                else -> return 2
            }
            val cell = (height - 108f) / weeks.coerceAtLeast(1)
            val lanes = ((cell - 24f - 12f) / 15f).toInt()
            return lanes.coerceIn(2, MONTH_LANE_CAP)
        }

        private fun columnWidthDp(context: Context, widgetId: Int): Float {
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

        private fun bindBar(
            context: Context,
            views: RemoteViews,
            id: Int,
            mark: DayMark,
            laneUsed: Boolean,
            color: Int,
        ) {
            if (mark.kind == MarkKind.EMPTY) {
                views.setTextViewText(id, "")
                views.setViewVisibility(id, if (laneUsed) View.INVISIBLE else View.GONE)
                views.setInt(id, "setBackgroundResource", R.drawable.widget_clear)
                return
            }
            views.setViewVisibility(id, View.VISIBLE)
            val shown = if (mark.centered && mark.labelColumns > 1) "" else mark.text
            views.setTextViewText(id, shown)
            views.setTextColor(id, if (mark.holiday) context.getColor(R.color.holiday_ink) else color)
            views.setInt(id, "setBackgroundResource", barBackground(mark))
            if (Build.VERSION.SDK_INT >= 31) {
                val start = when (mark.kind) {
                    MarkKind.MID, MarkKind.END -> -3f
                    else -> 0f
                }
                val end = when (mark.kind) {
                    MarkKind.MID, MarkKind.START -> -3f
                    else -> 0f
                }
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_START, start, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_END, end, TypedValue.COMPLEX_UNIT_DIP)
                if (shown.isNotEmpty()) views.setInt(id, "setGravity", Gravity.CENTER)
            } else if (shown.isNotEmpty()) {
                views.setInt(id, "setGravity", Gravity.CENTER)
            }
        }

        private fun barBackground(mark: DayMark): Int {
            if (mark.holiday) {
                return when (mark.kind) {
                    MarkKind.START -> R.drawable.widget_holiday_bar_start
                    MarkKind.END -> R.drawable.widget_holiday_bar_end
                    MarkKind.MID -> R.drawable.widget_holiday_bar_mid
                    else -> R.drawable.widget_holiday_chip
                }
            }
            return when (mark.kind) {
                MarkKind.START -> R.drawable.widget_bar_start
                MarkKind.END -> R.drawable.widget_bar_end
                MarkKind.MID -> R.drawable.widget_bar_mid
                else -> R.drawable.widget_event_chip
            }
        }

        private fun placeSpanLabel(
            context: Context,
            views: RemoteViews,
            idName: String,
            marks: List<DayMark>,
            columnDp: Float,
            color: Int,
        ) {
            val id = context.resources.getIdentifier(idName, "id", context.packageName)
            if (id == 0) return
            val group = spanGroups(marks).singleOrNull()
            if (group == null || group.title.isEmpty()) {
                views.setViewVisibility(id, View.GONE)
                return
            }
            views.setViewVisibility(id, View.VISIBLE)
            views.setTextViewText(id, group.title)
            val holiday = (group.start..group.end).any { marks[it].holiday }
            views.setTextColor(id, if (holiday) context.getColor(R.color.holiday_ink) else color)
            if (Build.VERSION.SDK_INT >= 31) {
                views.setViewLayoutWidth(id, columnDp * group.length, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_START, columnDp * group.start, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_END, 0f, TypedValue.COMPLEX_UNIT_DIP)
                views.setInt(id, "setGravity", Gravity.CENTER)
            }
        }

        private data class SpanGroup(val start: Int, val end: Int, val title: String, val length: Int)

        private fun spanGroups(marks: List<DayMark>): List<SpanGroup> {
            val groups = ArrayList<SpanGroup>()
            var day = 0
            while (day < marks.size) {
                val kind = marks[day].kind
                val spanned = kind == MarkKind.START || kind == MarkKind.MID || kind == MarkKind.END
                if (!spanned) {
                    day += 1
                    continue
                }
                val start = day
                var end = day
                while (end + 1 < marks.size) {
                    val next = marks[end + 1].kind
                    if (next != MarkKind.START && next != MarkKind.MID && next != MarkKind.END) break
                    end += 1
                }
                val title = (start..end).firstNotNullOfOrNull { index -> marks[index].text.takeIf { it.isNotEmpty() } }.orEmpty()
                groups.add(SpanGroup(start, end, title, end - start + 1))
                day = end + 1
            }
            return groups
        }

        private fun styleToggle(context: Context, views: RemoteViews, id: Int, selected: Boolean) {
            views.setInt(id, "setBackgroundResource", if (selected) R.drawable.widget_talk else R.drawable.widget_chip_idle)
            views.setTextColor(id, context.getColor(if (selected) R.color.accent_ink else R.color.muted))
        }

        private fun spanIntent(context: Context, widgetId: Int, span: String): PendingIntent {
            val intent = Intent(context, VelaWidgetProvider::class.java).apply {
                action = ACTION_SET_SPAN
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_SPAN, span)
                data = Uri.parse("vella://widget/$widgetId/$span")
            }
            val request = widgetId * 10 + if (span == SPAN_WEEK) 1 else 2
            return PendingIntent.getBroadcast(
                context,
                request,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun shiftIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
            val intent = Intent(context, VelaWidgetProvider::class.java).apply {
                action = ACTION_SHIFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                if (delta == 0) putExtra(EXTRA_TODAY, true) else putExtra(EXTRA_DELTA, delta)
                data = Uri.parse("vella://widget/$widgetId/shift/$delta")
            }
            val code = when (delta) {
                -1 -> 3
                1 -> 4
                else -> 5
            }
            return PendingIntent.getBroadcast(
                context,
                widgetId * 10 + code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun addIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_ADD, true)
                data = Uri.parse("vella://widget/$widgetId/add")
            }
            return PendingIntent.getActivity(
                context,
                widgetId * 10 + 6,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
