package com.lucasmolina.vela

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class MarkKind { EMPTY, CHIP, START, MID, END }

data class AgendaItem(
    val title: String,
    val time: String,
    val start: LocalDate,
    val end: LocalDate,
    val allDay: Boolean,
    val holiday: Boolean = false,
)

data class DayMark(
    val text: String,
    val kind: MarkKind,
    val labelColumns: Int = 1,
    val centered: Boolean = false,
    val holiday: Boolean = false,
)

data class DayColumn(
    val weekday: String,
    val number: String,
    val today: Boolean,
    val marks: List<DayMark>,
)

data class WeekBoard(val heading: String, val days: List<DayColumn>)

data class MonthCell(
    val number: String,
    val inMonth: Boolean,
    val today: Boolean,
    val marks: List<DayMark>,
    val more: String,
)

data class MonthBoard(val heading: String, val cells: List<MonthCell>)

object Agenda {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
    private val shortClock: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
    private val emptyMark = DayMark("", MarkKind.EMPTY)
    private const val WEEK_SLOTS = 16

    fun todayLabel(): String = DateTimeFormatter.ofPattern("EEE, MMM d").format(LocalDate.now(zone))

    fun permitted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun week(context: Context, offset: Int = 0): WeekBoard {
        val today = LocalDate.now(zone)
        val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong()).plusWeeks(offset.toLong())
        val saturday = sunday.plusDays(6)
        val heading = "${DateTimeFormatter.ofPattern("MMM d").format(sunday)} – ${
            if (sunday.month == saturday.month) saturday.dayOfMonth.toString()
            else DateTimeFormatter.ofPattern("MMM d").format(saturday)
        }"
        val events = if (permitted(context)) eventsBetween(context, sunday, saturday.plusDays(1)) else emptyList()
        val packed = pack(events, sunday, 7, WEEK_SLOTS, titlesOnly = false)
        val days = (0 until 7).map { day ->
            val date = sunday.plusDays(day.toLong())
            DayColumn(
                weekday = weekday.format(date),
                number = date.dayOfMonth.toString(),
                today = date == today,
                marks = packed.marks[day].toList(),
            )
        }
        return WeekBoard(heading, days)
    }

    fun month(context: Context, offset: Int = 0, lanes: Int = 2): MonthBoard {
        val today = LocalDate.now(zone)
        val first = today.withDayOfMonth(1).plusMonths(offset.toLong())
        val gridStart = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val events = if (permitted(context)) eventsBetween(context, gridStart, gridStart.plusDays(42)) else emptyList()
        val packed = pack(events, gridStart, 42, lanes.coerceIn(1, 6), titlesOnly = true)
        val cells = (0 until 42).map { day ->
            val date = gridStart.plusDays(day.toLong())
            MonthCell(
                number = date.dayOfMonth.toString(),
                inMonth = date.month == first.month && date.year == first.year,
                today = date == today,
                marks = packed.marks[day].toList(),
                more = if (packed.more[day] > 0) "+${packed.more[day]}" else "",
            )
        }
        val pattern = if (first.year == today.year) "MMMM" else "MMMM yyyy"
        return MonthBoard(DateTimeFormatter.ofPattern(pattern).format(first), cells)
    }

    private class Packed(val marks: Array<Array<DayMark>>, val more: IntArray)

    private fun pack(events: List<AgendaItem>, origin: LocalDate, days: Int, lanes: Int, titlesOnly: Boolean): Packed {
        val marks = Array(days) { Array(lanes) { emptyMark } }
        val more = IntArray(days)
        val laneEnds = ArrayList<Int>()
        val multi = events.filter { it.end.isAfter(it.start) }.sortedWith(compareBy({ it.start }, { it.title }))
        val windowEnd = origin.plusDays((days - 1).toLong())
        for (event in multi) {
            if (event.end.isBefore(origin) || event.start.isAfter(windowEnd)) continue
            val segStart = if (event.start.isBefore(origin)) origin else event.start
            val segLast = if (event.end.isAfter(windowEnd)) windowEnd else event.end
            val col = ChronoUnit.DAYS.between(origin, segStart).toInt()
            val length = ChronoUnit.DAYS.between(segStart, segLast).toInt() + 1
            if (col < 0 || length <= 0 || col >= days) continue
            var lane = laneEnds.indexOfFirst { it <= col }
            if (lane == -1) {
                if (laneEnds.size < lanes) {
                    lane = laneEnds.size
                    laneEnds.add(col + length)
                } else {
                    for (i in 0 until length) {
                        if (col + i < days) more[col + i] += 1
                    }
                    continue
                }
            } else {
                laneEnds[lane] = col + length
            }
            for (i in 0 until length) {
                val index = col + i
                if (index >= days) break
                val atStart = i == 0 && !event.start.isBefore(segStart)
                val atEnd = i == length - 1 && !event.end.isAfter(segLast)
                val kind = when {
                    atStart && atEnd -> MarkKind.CHIP
                    atStart -> MarkKind.START
                    atEnd -> MarkKind.END
                    else -> MarkKind.MID
                }
                marks[index][lane] = DayMark("", kind, holiday = event.holiday)
            }
            var cursor = 0
            while (cursor < length) {
                val index = col + cursor
                if (index >= days) break
                val weekLast = index - (index % 7) + 6
                val sliceEnd = minOf(col + length - 1, weekLast, days - 1)
                val sliceLen = sliceEnd - index + 1
                marks[sliceEnd][lane] = marks[sliceEnd][lane].copy(
                    text = event.title,
                    labelColumns = sliceLen,
                    centered = true,
                )
                cursor += sliceLen
            }
        }
        val singles = events.filter { !it.end.isAfter(it.start) }
        for (day in 0 until days) {
            val date = origin.plusDays(day.toLong())
            val todays = singles.filter { it.start == date }
            var next = 0
            for (lane in 0 until lanes) {
                if (marks[day][lane].kind != MarkKind.EMPTY) continue
                if (next >= todays.size) break
                val item = todays[next]
                next += 1
                val text = if (titlesOnly || item.allDay) item.title else "${item.time}\n${item.title}"
                marks[day][lane] = DayMark(text, MarkKind.CHIP, holiday = item.holiday)
            }
            if (todays.size > next) more[day] += todays.size - next
        }
        return Packed(marks, more)
    }

    private fun eventsBetween(context: Context, from: LocalDate, until: LocalDate): List<AgendaItem> {
        val queryStart = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val queryEnd = until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(queryStart.toString())
            .appendPath(queryEnd.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
        )
        val items = mutableListOf<AgendaItem>()
        val seen = HashMap<String, Pair<Int, Int>>()
        val calendars = PhoneCalendar.calendarFacts(context)
        try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { cursor ->
                    val calendarIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                    val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                    val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                    val statusIndex = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
                    while (cursor.moveToNext()) {
                        val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else CalendarContract.Instances.STATUS_CONFIRMED
                        if (status == CalendarContract.Instances.STATUS_CANCELED) continue
                        val allDay = allDayIndex >= 0 && cursor.getInt(allDayIndex) == 1
                        val begin = cursor.getLong(beginIndex)
                        val finish = if (endIndex >= 0) cursor.getLong(endIndex) else begin
                        val (startDate, endDate) = spanDates(allDay, begin, finish)
                        if (endDate.isBefore(from) || !startDate.isBefore(until)) continue
                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Untitled"
                        val calendarId = if (calendarIndex >= 0) cursor.getLong(calendarIndex) else -1L
                        val facts = calendars[calendarId]
                        val holiday = facts?.holiday == true
                        val rank = facts?.rank ?: 2
                        val key = "${title.trim().lowercase()}|$allDay|$startDate|$endDate"
                        val time = if (allDay) "All day" else shortClock.format(Instant.ofEpochMilli(begin).atZone(zone))
                        val item = AgendaItem(title, time, startDate, endDate, allDay, holiday)
                        val existing = seen[key]
                        if (existing == null) {
                            seen[key] = rank to items.size
                            items.add(item)
                        } else if (rank < existing.first) {
                            items[existing.second] = item
                            seen[key] = rank to existing.second
                        }
                    }
                }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return items
    }

    private fun spanDates(allDay: Boolean, begin: Long, finish: Long): Pair<LocalDate, LocalDate> {
        val startDate = if (allDay) {
            Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        }
        val endDate = if (allDay) {
            val exclusive = Instant.ofEpochMilli(finish).atZone(ZoneOffset.UTC).toLocalDate()
            val last = exclusive.minusDays(1)
            if (last.isBefore(startDate)) startDate else last
        } else {
            val endZoned = Instant.ofEpochMilli(finish).atZone(zone)
            val date = endZoned.toLocalDate()
            if (endZoned.toLocalTime() == LocalTime.MIDNIGHT && date.isAfter(startDate)) date.minusDays(1) else date
        }
        return startDate to endDate
    }
}
