package com.mercureal.voice

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Runs non-screenshot commands on the device. Every result carries text for TTS and history. */
object Actions {
    class Result(val ok: Boolean, val speech: String)

    private var torchOn = false

    fun run(ctx: Context, cmd: Command): Result = when (cmd) {
        is Command.OpenApp -> openApp(ctx, cmd.query)
        is Command.Call -> callTarget(ctx, cmd.target)
        is Command.Dial -> dial(ctx, cmd.number)
        is Command.Sms -> Sms.begin(ctx, cmd.recipient, cmd.body)
        is Command.Flashlight -> flashlight(ctx, cmd.on)
        is Command.Timer -> timer(ctx, cmd.seconds)
        is Command.Alarm -> alarm(ctx, cmd.hour, cmd.minute)
        Command.Time -> time()
        Command.Battery -> battery(ctx)
        is Command.SettingsPanel -> settingsPanel(ctx, cmd.panel)
        is Command.Volume -> volume(ctx, cmd)
        Command.Screenshot -> Result(false, "screenshots are handled by the service")
        is Command.Unknown -> Result(false, "sorry, i didn't catch a command for that")
        is Command.RunRoutine -> Result(true, "running ${cmd.name}")
        is Command.Remind -> remind(ctx, cmd.raw)
        is Command.Media -> MediaControl.run(ctx, cmd.op)
        is Command.Emergency -> Result(true, "emergency")
    }

    fun remind(ctx: Context, raw: String): Result {
        val p = Reminders.parse(raw)
            ?: return Result(false, "i need a what and a time, like remind me to call ada at 6pm")
        val r = Reminders.add(ctx, p)
        val whenText = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(r.atMillis))
        return Result(true, "ok, i'll remind you to ${r.text} on $whenText")
    }

    private fun start(ctx: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun isTorchOn(): Boolean = torchOn

    /** Opens the best fuzzy match among all launchable apps. See [AppIndex.rank] for the matching tiers. */
    fun openApp(ctx: Context, query: String): Result {
        val ranked = AppIndex.rank(AppIndex.all(ctx), query)
        val best = ranked.firstOrNull() ?: return Result(false, "no app named $query")
        return launchPackage(ctx, best.first)
    }

    /** Launches one app from the index (used by the Apps screen and by open-app commands). */
    fun launchPackage(ctx: Context, app: AppEntry): Result {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(app.pkg)
            ?: return Result(false, "${app.label} can't be launched")
        return if (start(ctx, intent)) Result(true, "opening ${app.label}")
        else Result(false, "couldn't open ${app.label}")
    }

    /** Looks up the first contact whose display name contains [name]. Needs READ_CONTACTS. */
    fun findContact(ctx: Context, name: String): Pair<String, String>? {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    fun callTarget(ctx: Context, target: String): Result {
        if (!granted(ctx, Manifest.permission.CALL_PHONE)) {
            return Result(false, "i need phone permission to call")
        }
        val looksLikeNumber = target.all { it.isDigit() || it in " +-()" } && target.count { it.isDigit() } >= 3
        if (looksLikeNumber) {
            val digits = target.filter { it.isDigit() || it == '+' }
            return if (start(ctx, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", digits, null))))
                Result(true, "calling $digits") else Result(false, "no dialer available")
        }
        val contact = findContact(ctx, target)
            ?: return Result(false, "no contact named $target, or contacts permission is off")
        val ok = start(ctx, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contact.second, null)))
        return if (ok) Result(true, "calling ${contact.first}") else Result(false, "no dialer available")
    }

    fun dial(ctx: Context, number: String): Result {
        val ok = start(ctx, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        return if (ok) Result(true, "dialer open with $number") else Result(false, "no dialer available")
    }

    /** Opens the default SMS app composer. Does not auto-send (no SEND_SMS permission). */
    fun sms(ctx: Context, recipient: String, body: String): Result {
        val contact = findContact(ctx, recipient)
        val number = contact?.second
            ?: recipient.filter { it.isDigit() || it == '+' }.takeIf { it.count { c -> c.isDigit() } >= 3 }
            ?: return Result(false, "no contact named $recipient")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
        val who = contact?.first ?: number
        return if (start(ctx, intent)) Result(true, "message to $who ready, tap send")
        else Result(false, "no messaging app available")
    }

    fun flashlight(ctx: Context, on: Boolean?): Result {
        val cm = ctx.getSystemService(CameraManager::class.java)
            ?: return Result(false, "no camera service")
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result(false, "this phone has no flashlight")
            val target = on ?: !torchOn
            cm.setTorchMode(id, target)
            torchOn = target
            Result(true, if (target) "flashlight on" else "flashlight off")
        } catch (e: Exception) {
            Result(false, "couldn't change the flashlight")
        }
    }

    fun timer(ctx: Context, seconds: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "MercuReal timer")
        val label = when {
            seconds % 3600 == 0 -> "${seconds / 3600} hour${if (seconds / 3600 == 1) "" else "s"}"
            seconds % 60 == 0 -> "${seconds / 60} minute${if (seconds / 60 == 1) "" else "s"}"
            else -> "$seconds seconds"
        }
        return if (start(ctx, intent)) Result(true, "timer set for $label")
        else Result(false, "no clock app can set timers")
    }

    fun alarm(ctx: Context, hour: Int, minute: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "MercuReal alarm")
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
        }
        val label = SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
        return if (start(ctx, intent)) Result(true, "alarm set for $label")
        else Result(false, "no clock app can set alarms")
    }

    fun time(): Result {
        val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        return Result(true, "it's $now")
    }

    fun battery(ctx: Context): Result {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val tail = if (charging) ", charging" else ""
        val low = if (!charging && level in 0..14) ". low battery, consider charging or turning off continuous listening" else ""
        return Result(true, "battery is at $level percent$tail$low")
    }

    fun settingsPanel(ctx: Context, panel: String): Result {
        val action = if (panel == "bluetooth") Settings.ACTION_BLUETOOTH_SETTINGS else Settings.ACTION_WIFI_SETTINGS
        val name = if (panel == "bluetooth") "bluetooth" else "wi-fi"
        return if (start(ctx, Intent(action))) Result(true, "opening $name settings")
        else Result(false, "can't open $name settings")
    }

    fun volume(ctx: Context, cmd: Command.Volume): Result {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return Result(false, "no audio service")
        val stream = AudioManager.STREAM_MUSIC
        return try {
            when (cmd.op) {
                "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                "set" -> {
                    val max = am.getStreamMaxVolume(stream)
                    val level = ((cmd.level ?: 0) / 100.0 * max).roundToInt()
                    am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
                }
            }
            val word = when (cmd.op) {
                "up" -> "volume up"
                "down" -> "volume down"
                "mute" -> "muted"
                "unmute" -> "unmuted"
                else -> "volume set to ${cmd.level}"
            }
            Result(true, word)
        } catch (e: SecurityException) {
            Result(false, "this phone blocked the volume change")
        }
    }
}
