package com.lucasmolina.vela

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object PhoneCalendar {
    private val zone: ZoneId = ZoneId.systemDefault()

    internal data class CalendarFacts(val rank: Int, val holiday: Boolean)

    internal fun calendarFacts(context: Context): Map<Long, CalendarFacts> {
        val facts = HashMap<Long, CalendarFacts>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.NAME,
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val ownerIndex = cursor.getColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
            val accountIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val displayIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val nameIndex = cursor.getColumnIndex(CalendarContract.Calendars.NAME)
            while (cursor.moveToNext()) {
                val access = if (accessIndex >= 0) cursor.getInt(accessIndex) else 0
                val primary = primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1
                val writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                val rank = when {
                    primary && writable -> 0
                    writable -> 1
                    else -> 2
                }
                val holiday = listOf(ownerIndex, accountIndex, displayIndex, nameIndex).any { index ->
                    index >= 0 && cursor.getString(index).orEmpty().contains("holiday", ignoreCase = true)
                }
                facts[cursor.getLong(idIndex)] = CalendarFacts(rank, holiday)
            }
        }
        return facts
    }

    fun list(context: Context, startIso: String, endIso: String): String {
        return guard {
            val start = Instant.parse(startIso).toEpochMilli()
            val end = Instant.parse(endIso).toEpochMilli()
            val events = JSONArray()
            queryInstances(context, start, end).forEach { events.put(it) }
            JSONObject().put("events", events)
        }
    }

    fun create(context: Context, json: String): String {
        return guard {
            val input = JSONObject(json)
            val values = ContentValues()
            values.put(CalendarContract.Events.CALENDAR_ID, writableCalendarId(context))
            values.put(CalendarContract.Events.TITLE, input.optString("title", "Appointment"))
            values.put(CalendarContract.Events.DTSTART, Instant.parse(input.getString("start")).toEpochMilli())
            values.put(CalendarContract.Events.DTEND, Instant.parse(input.getString("end")).toEpochMilli())
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            values.put(CalendarContract.Events.EVENT_LOCATION, input.optString("location"))
            values.put(CalendarContract.Events.DESCRIPTION, input.optString("notes"))
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IllegalStateException("The phone calendar did not save that appointment.")
            val id = ContentUris.parseId(uri)
            VelaWidgetProvider.updateAll(context)
            JSONObject().put("event", readEvent(context, id))
        }
    }

    fun update(context: Context, id: String, json: String): String {
        return guard {
            val eventId = id.toLong()
            val patch = JSONObject(json)
            val values = ContentValues()
            if (patch.has("title") && !patch.isNull("title")) values.put(CalendarContract.Events.TITLE, patch.getString("title"))
            if (patch.has("location") && !patch.isNull("location")) values.put(CalendarContract.Events.EVENT_LOCATION, patch.getString("location"))
            if (patch.has("notes") && !patch.isNull("notes")) values.put(CalendarContract.Events.DESCRIPTION, patch.getString("notes"))
            if (patch.has("start") && !patch.isNull("start")) {
                values.put(CalendarContract.Events.DTSTART, Instant.parse(patch.getString("start")).toEpochMilli())
                values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            if (patch.has("end") && !patch.isNull("end")) {
                values.put(CalendarContract.Events.DTEND, Instant.parse(patch.getString("end")).toEpochMilli())
            }
            if (values.size() > 0) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.update(uri, values, null, null)
            }
            VelaWidgetProvider.updateAll(context)
            JSONObject().put("event", readEvent(context, eventId))
        }
    }

    fun remove(context: Context, id: String): String {
        return guard {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong())
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) throw IllegalStateException("That appointment could not be deleted from this calendar.")
            VelaWidgetProvider.updateAll(context)
            JSONObject()
        }
    }

    private fun queryInstances(context: Context, start: Long, end: Long): List<JSONObject> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.STATUS,
        )
        val events = linkedMapOf<String, Pair<Int, JSONObject>>()
        val calendars = calendarFacts(context)
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val calendarIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val notesIndex = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val statusIndex = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
                while (cursor.moveToNext()) {
                    val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else CalendarContract.Instances.STATUS_CONFIRMED
                    if (status == CalendarContract.Instances.STATUS_CANCELED) continue
                    val calendarId = if (calendarIndex >= 0) cursor.getLong(calendarIndex) else -1L
                    val facts = calendars[calendarId]
                    val event = toJson(
                        cursor.getLong(idIndex),
                        cursor.getString(titleIndex) ?: "Untitled",
                        cursor.getLong(beginIndex),
                        cursor.getLong(endIndex),
                        allDayIndex >= 0 && cursor.getInt(allDayIndex) == 1,
                        if (locationIndex >= 0) cursor.getString(locationIndex) else "",
                        if (notesIndex >= 0) cursor.getString(notesIndex) else "",
                        facts?.holiday == true,
                    )
                    val key = duplicateKey(event)
                    val rank = facts?.rank ?: 2
                    val existing = events[key]
                    if (existing == null && events.size >= 400) continue
                    if (existing == null || rank < existing.first) events[key] = rank to event
                }
            }
        return events.values.map { it.second }
    }

    private fun readEvent(context: Context, id: Long): JSONObject {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("That appointment is no longer on this phone.")
            val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val start = cursor.getLong(cursor.getColumnIndex(CalendarContract.Events.DTSTART))
            val end = if (endIndex >= 0 && !cursor.isNull(endIndex)) cursor.getLong(endIndex) else start + 60 * 60 * 1000
            return toJson(
                id,
                cursor.getString(cursor.getColumnIndex(CalendarContract.Events.TITLE)) ?: "Untitled",
                start,
                end,
                cursor.getInt(cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)) == 1,
                cursor.getString(cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)),
                cursor.getString(cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)),
            )
        }
        throw IllegalStateException("That appointment is no longer on this phone.")
    }

    private fun toJson(
        id: Long,
        title: String,
        start: Long,
        end: Long,
        allDay: Boolean,
        location: String?,
        notes: String?,
        holiday: Boolean = false,
    ): JSONObject {
        val event = JSONObject()
        event.put("id", id.toString())
        event.put("title", title.ifBlank { "Untitled" })
        event.put("allDay", allDay)
        event.put("holiday", holiday)
        event.put("location", location ?: "")
        event.put("notes", notes ?: "")
        event.put("htmlLink", "")
        if (allDay) {
            event.put("start", startOfUtcDate(start))
            event.put("end", startOfUtcDate(end))
        } else {
            event.put("start", Instant.ofEpochMilli(start).toString())
            event.put("end", Instant.ofEpochMilli(end).toString())
        }
        return event
    }

    private fun startOfUtcDate(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toString()
    }

    private fun duplicateKey(event: JSONObject): String {
        val title = event.getString("title").trim().lowercase()
        return "$title|${event.getBoolean("allDay")}|${event.getString("start")}|${event.getString("end")}"
    }

    private fun writableCalendarId(context: Context): Long {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        var fallback = -1L
        var primary = -1L
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                val access = cursor.getInt(accessIndex)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = cursor.getLong(idIndex)
                if (fallback < 0) fallback = id
                if (primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1) primary = id
            }
        }
        if (primary >= 0) return primary
        if (fallback >= 0) return fallback
        throw IllegalStateException("No calendar on this phone can be edited.")
    }

    private fun guard(block: () -> JSONObject): String {
        return try {
            block().toString()
        } catch (error: SecurityException) {
            JSONObject().put("error", "Allow calendar access for Vella.").toString()
        } catch (error: Exception) {
            JSONObject().put("error", error.message ?: "The phone calendar could not be updated.").toString()
        }
    }
}
