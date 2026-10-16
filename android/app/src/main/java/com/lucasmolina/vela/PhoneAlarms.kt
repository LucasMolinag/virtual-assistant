package com.lucasmolina.vela

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object PhoneAlarms {
    private const val PREFS = "vella_clock"
    private const val ITEMS = "items"
    private const val NEXT = "next"
    const val EXTRA_ID = "id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_AT = "at"

    fun schedule(context: Context, iso: String, label: String, relativeSeconds: Int = 0): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return scheduleOnMain(context, iso, label, relativeSeconds)
        val box = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            box[0] = scheduleOnMain(context, iso, label, relativeSeconds)
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return box[0] ?: JSONObject().put("error", "I couldn't open the clock.").toString()
    }

    private fun scheduleOnMain(context: Context, iso: String, label: String, relativeSeconds: Int): String {
        val title = label.ifBlank { "Alarm" }
        if (relativeSeconds > 0) {
            return if (openClock(context, timerIntent(relativeSeconds, title))) ok("timer") else clockMissing()
        }
        val at = try {
            Instant.parse(iso)
        } catch (_: Exception) {
            return JSONObject().put("error", "I couldn't read that time.").toString()
        }
        if (at.toEpochMilli() <= System.currentTimeMillis() + 1000) {
            return JSONObject().put("error", "That time has already passed.").toString()
        }
        val zoned = at.atZone(ZoneId.systemDefault())
        val next = nextClockTime(zoned.hour, zoned.minute)
        val sameMoment = abs(next.toInstant().toEpochMilli() - at.toEpochMilli()) < 90_000
        if (sameMoment) {
            return if (openClock(context, alarmIntent(zoned.hour, zoned.minute, title))) ok("alarm") else clockMissing()
        }
        val seconds = ((at.toEpochMilli() - System.currentTimeMillis()) / 1000).toInt()
        if (seconds <= 0) return JSONObject().put("error", "That time has already passed.").toString()
        return if (openClock(context, timerIntent(seconds, title))) ok("timer") else clockMissing()
    }

    private fun nextClockTime(hour: Int, minute: Int): ZonedDateTime {
        val zone = ZoneId.systemDefault()
        var next = ZonedDateTime.now(zone).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (next.toInstant().toEpochMilli() <= System.currentTimeMillis() + 1000) next = next.plusDays(1)
        return next
    }

    private fun alarmIntent(hour: Int, minute: Int, title: String): Intent {
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
    }

    private fun timerIntent(seconds: Int, title: String): Intent {
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
    }

    private fun openClock(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ok(kind: String): String = JSONObject().put("ok", true).put("kind", kind).toString()

    private fun clockMissing(): String = JSONObject().put("error", "I couldn't find the clock app.").toString()

    fun silence(context: Context, id: Int) {
        cancel(context, id)
        val kept = JSONArray()
        val current = stored(context)
        for (index in 0 until current.length()) {
            val item = current.getJSONObject(index)
            if (item.optInt("id") != id) kept.put(item)
        }
        save(context, kept)
    }

    fun snooze(context: Context, id: Int, label: String) {
        silence(context, id)
        val at = System.currentTimeMillis() + 5 * 60_000L
        val next = nextId(context)
        val title = label.ifBlank { "Alarm" }
        arm(context, next, title, at)
        val kept = stored(context)
        kept.put(JSONObject().put("id", next).put("label", title).put("at", at))
        save(context, kept)
    }

    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        val current = stored(context)
        for (index in 0 until current.length()) {
            val item = current.getJSONObject(index)
            val at = item.optLong("at")
            if (at < now - 15 * 60_000L) continue
            val whenAt = if (at < now + 1000) now + 2000 else at
            arm(context, item.optInt("id"), item.optString("label", "Alarm"), whenAt)
            kept.put(item.put("at", whenAt))
        }
        save(context, kept)
    }

    private fun arm(context: Context, id: Int, label: String, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val ring = ringIntent(context, id, label, at)
        val show = PendingIntent.getActivity(
            context,
            id + 1_000_000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val info = AlarmManager.AlarmClockInfo(at, show)
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
        } else {
            alarm.setAlarmClock(info, ring)
        }
    }

    private fun cancel(context: Context, id: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(ringIntent(context, id, "Alarm", 0L))
    }

    private fun ringIntent(context: Context, id: Int, label: String, at: Long): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_AT, at)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextId(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(NEXT, 1)
        prefs.edit().putInt(NEXT, id + 1).apply()
        return id
    }

    private fun stored(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "[]").orEmpty()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun save(context: Context, items: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ITEMS, items.toString()).apply()
    }
}
