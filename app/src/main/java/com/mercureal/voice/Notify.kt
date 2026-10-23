package com.mercureal.voice

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Notification-listener access. Shared by notification reading and media control. */
object NotifyAccess {
    fun component(ctx: Context) = ComponentName(ctx, NotifyListenerService::class.java)

    fun granted(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            nm.isNotificationListenerAccessGranted(component(ctx))
        } catch (e: SecurityException) {
            false
        }
    }

    fun openSettings(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }
}

/** Preferences for notification reading. Opt-in: off until the user turns it on. */
object NotifyPrefs {
    private const val FILE = "mercureal_notify"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUTED = "muted"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    @Synchronized
    fun muted(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()

    @Synchronized
    fun setMuted(ctx: Context, pkg: String, mute: Boolean) {
        val set = muted(ctx).toMutableSet()
        if (mute) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_MUTED, set).apply()
    }
}

/**
 * Reads incoming notifications aloud as "app. title. preview".
 * Skips: master toggle off, muted apps, our own notifications, ongoing/progress/transport
 * notifications, group summaries, repeats within 10 seconds, and any time the phone is in
 * do-not-disturb or on silent/vibrate.
 */
class NotifyListenerService : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            announce(n)
        } catch (e: Exception) {
            // a bad notification must never take the listener down
        }
    }

    private fun announce(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (!NotifyPrefs.enabled(ctx)) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName in NotifyPrefs.muted(ctx)) return
        val groupSummary = (sbn.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY != 0
        if (groupSummary) return

        val notif: Notification = sbn.notification ?: return
        if (notif.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val cat = notif.category
        if (cat == Notification.CATEGORY_TRANSPORT || cat == Notification.CATEGORY_PROGRESS) return

        // Do-not-disturb: anything other than "all" means DND or priority-only.
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        // Silent or vibrate-only ringer.
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        // Same app, title and text within 10 seconds: skip (apps often re-post the same notification).
        val signature = "${sbn.packageName}|$title|$body"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 10_000 }
            if (recent.containsKey(signature)) return
            recent[signature] = now
            while (recent.size > 30) recent.remove(recent.keys.first())
        }

        val appLabel = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val preview = if (body.length > 160) body.take(160) + "..." else body
        val speech = listOf(appLabel, title, preview).filter { it.isNotEmpty() }.joinToString(". ")
        Speaker.instance(ctx).say(speech)
    }
}
