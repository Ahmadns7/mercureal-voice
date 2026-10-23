package com.mercureal.voice

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Screen time from UsageStatsManager. Needs PACKAGE_USAGE_STATS, a special permission the user
 * grants in Usage access settings (it is not a runtime prompt). Without it, queries return nothing.
 */
object UsageStatsReader {
    @Suppress("DEPRECATION")
    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } catch (e: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** [start, end) in epoch millis for today (so far) or yesterday, in the phone's local time. */
    fun dayRange(yesterday: Boolean, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        if (!yesterday) return todayStart to now
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis to todayStart
    }

    /** Foreground time in ms per package for the day. Empty when access is off or nothing is recorded. */
    fun totals(ctx: Context, yesterday: Boolean): Map<String, Long> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val (start, end) = dayRange(yesterday)
        val raw = try {
            usm.queryAndAggregateUsageStats(start, end)
        } catch (e: SecurityException) {
            return emptyMap()
        }
        return raw.mapValues { it.value.totalTimeInForeground }.filterValues { it > 0L }
    }

    /** "1 hour 12 minutes", "45 minutes", "under a minute". */
    fun formatDuration(ms: Long): String {
        val mins = ms / 60_000L
        if (mins < 1L) return "under a minute"
        val h = mins / 60L
        val m = mins % 60L
        val parts = ArrayList<String>()
        if (h > 0L) parts.add("$h hour" + if (h == 1L) "" else "s")
        if (m > 0L) parts.add("$m minute" + if (m == 1L) "" else "s")
        return parts.joinToString(" ")
    }
}
