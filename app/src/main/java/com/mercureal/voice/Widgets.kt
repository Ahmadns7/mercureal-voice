package com.mercureal.voice

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews



/** Runtime resource lookup: the build gate compiles without the generated R class. */
internal fun res(ctx: Context, type: String, name: String): Int =
    ctx.resources.getIdentifier(name, type, ctx.packageName)

/** Launch intent extra: open the app and start listening. */
const val EXTRA_LISTEN = "mv_listen"
/** Launch intent extra: run this saved routine name. */
const val EXTRA_ROUTINE = "mv_routine"

private fun activityIntent(ctx: Context, requestCode: Int, listen: Boolean, routine: String?): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (listen) putExtra(EXTRA_LISTEN, true)
        if (routine != null) putExtra(EXTRA_ROUTINE, routine)
    }
    return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Mic widget: tap opens the app and starts listening. */
class MicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, res(ctx, "layout", "widget_mic"))
            v.setOnClickPendingIntent(res(ctx, "id", "widget_root"), activityIntent(ctx, id, listen = true, routine = null))
            mgr.updateAppWidget(id, v)
        }
    }
}

/** Routine shortcut: long-press configures which saved routine it runs; tap runs it. */
class RoutineWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val name = RoutineWidgetStore.get(ctx, id)
            val v = RemoteViews(ctx.packageName, res(ctx, "layout", "widget_routine"))
            v.setTextViewText(res(ctx, "id", "widget_label"), name ?: "tap to set up")
            v.setOnClickPendingIntent(res(ctx, "id", "widget_root"),
                if (name == null) Intent(ctx, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .let { PendingIntent.getActivity(ctx, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
                else activityIntent(ctx, id, listen = false, routine = name))
            mgr.updateAppWidget(id, v)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { RoutineWidgetStore.clear(ctx, it) }
    }
}

/** Compact status: battery percent plus the next reminder. */
class StatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val battery = batteryPercent(ctx)
        val next = nextReminder(ctx)?.let { "${it.text}, ${clock(it.atMillis)}" } ?: "no reminders"
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, res(ctx, "layout", "widget_status"))
            v.setTextViewText(res(ctx, "id", "status_battery"), "battery ${battery?.let { "$it%" } ?: "unknown"}")
            v.setTextViewText(res(ctx, "id", "status_next"), "next: $next")
            v.setOnClickPendingIntent(res(ctx, "id", "widget_root"), activityIntent(ctx, id, listen = false, routine = null))
            mgr.updateAppWidget(id, v)
        }
    }

    private fun batteryPercent(ctx: Context): Int? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }
}

/** Soonest reminder that has not passed yet. */
internal fun nextReminder(ctx: Context): Reminder? {
    val now = System.currentTimeMillis()
    return Reminders.all(ctx).filter { it.atMillis > now }.minByOrNull { it.atMillis }
}

internal fun clock(millis: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))

/** Stores which routine each routine-widget instance runs. */
object RoutineWidgetStore {
    private const val FILE = "routine_widgets"
    fun get(ctx: Context, id: Int): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("w$id", null)
    fun set(ctx: Context, id: Int, name: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("w$id", name).apply()
    fun clear(ctx: Context, id: Int) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("w$id").apply()
}

/** Refreshes the status widget on battery and time changes. Declared in the manifest. */
class RoutineRunReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, StatusWidgetProvider::class.java))
        StatusWidgetProvider().onUpdate(ctx, mgr, ids)
    }
}
