package com.mercureal.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Named phrases that run a chain of commands in order, speaking between steps. */
data class Routine(val name: String, val steps: List<String>)

/** A reminder stored locally; fires an exact alarm that announces it and posts a notification. */
data class Reminder(val id: Int, val text: String, val atMillis: Long)

object Routines {
    private const val PREF = "mv_routines"
    private const val KEY = "list"

    val examples = listOf(
        Routine("good morning", listOf("time", "battery")),
        Routine("wind down", listOf("volume down", "flashlight off")),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Routine> {
        val raw = prefs(ctx).getString(KEY, null) ?: return examples
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val steps = o.getJSONArray("steps")
                Routine(o.getString("name"), (0 until steps.length()).map { steps.getString(it) })
            }
        } catch (_: Throwable) { examples }
    }

    fun save(ctx: Context, list: List<Routine>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("name", r.name).put("steps", JSONArray(r.steps)))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, routine: Routine, oldName: String? = null) {
        val list = all(ctx).filter { it.name != oldName && it.name != routine.name } + routine
        save(ctx, list)
    }

    fun delete(ctx: Context, name: String) = save(ctx, all(ctx).filter { it.name != name })

    fun find(ctx: Context, phrase: String): Routine? {
        val p = phrase.lowercase().trim()
        return all(ctx).firstOrNull { it.name == p }
    }
}

/**
 * Reminder parsing and storage. Accepts "remind me to X at 6pm", "remind me to X tomorrow at 9",
 * "remind me to X in 20 minutes". Returns null if no usable time was found.
 */
object Reminders {
    private const val PREF = "mv_reminders"
    private const val KEY = "list"

    private val timeRx = Regex("""\bat (\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""")
    private val inRx = Regex("""\bin (\d+) (minute|minutes|min|mins|hour|hours|hr|hrs)\b""")
    private val tomorrowRx = Regex("""\btomorrow\b""")

    data class Parsed(val text: String, val atMillis: Long)

    fun parse(raw: String, now: Long = System.currentTimeMillis()): Parsed? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        val body = t.removePrefix("remind me to ").removePrefix("remind me ").removePrefix("reminder to ")
        if (body == t && !t.startsWith("remind")) return null

        inRx.find(body)?.let { m ->
            val n = m.groupValues[1].toLong()
            val ms = if (m.groupValues[2].startsWith("h")) n * 3_600_000 else n * 60_000
            val text = body.replace(m.value, "").trim().trimEnd(' ')
            return if (text.isEmpty()) null else Parsed(text, now + ms)
        }

        timeRx.find(body)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h !in 0..23 || min !in 0..59) return null
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, min)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (tomorrowRx.containsMatchIn(body)) cal.add(Calendar.DAY_OF_MONTH, 1)
            else if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
            var text = body.replace(m.value, "").replace(tomorrowRx, "").trim()
            text = text.replace(Regex("\\s+"), " ").trim()
            return if (text.isEmpty()) null else Parsed(text, cal.timeInMillis)
        }
        return null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"))
            }.sortedBy { it.atMillis }
        } catch (_: Throwable) { emptyList() }
    }

    private fun saveAll(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.atMillis)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Stores and schedules a reminder. Returns the stored item. */
    fun add(ctx: Context, parsed: Parsed): Reminder {
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val r = Reminder(id, parsed.text, parsed.atMillis)
        saveAll(ctx, all(ctx) + r)
        schedule(ctx, r)
        return r
    }

    fun remove(ctx: Context, id: Int) {
        cancel(ctx, id)
        saveAll(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun pendingFor(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", r.id).putExtra("text", r.text)
        return PendingIntent.getBroadcast(
            ctx, r.id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Uses an exact alarm when allowed; falls back to an inexact alarm otherwise (reported in UI). */
    fun schedule(ctx: Context, r: Reminder): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFor(ctx, r)
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
                false
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
                true
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
            false
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, ReminderReceiver::class.java)
        am.cancel(PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE))
    }

    /** Re-arms all reminders still in the future, after reboot. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        all(ctx).forEach { if (it.atMillis > now) schedule(ctx, it) }
    }
}

/** Fires a reminder: notification plus TTS announcement, then drops it from storage. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val text = intent.getStringExtra("text") ?: return
        Speaker.instance(context).say("reminder: $text")
        Notifier.post(context, "reminder", text, 1000 + id)
        if (id >= 0) Reminders.remove(context, id)
    }
}

/** Shared notification helper (channel created once). */
object Notifier {
    const val CHANNEL = "mv_alerts"
    fun post(ctx: Context, title: String, body: String, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "MercuReal alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL)
        else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

/** Re-arms saved reminders after a reboot (the system clears alarms on boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(context)
    }
}
