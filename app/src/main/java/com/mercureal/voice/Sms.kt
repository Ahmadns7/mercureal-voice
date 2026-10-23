package com.mercureal.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicBoolean

/** Emergency contact settings and the one pending text waiting for a "yes". */
object SmsPrefs {
    private const val FILE = "mercureal_sms"
    private const val CONFIRM_WINDOW_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun emergencyName(ctx: Context): String? = prefs(ctx).getString("e_name", null)
    fun emergencyNumber(ctx: Context): String? = prefs(ctx).getString("e_number", null)
    fun emergencyMessage(ctx: Context): String = prefs(ctx).getString("e_msg", "") ?: ""
    fun panic(ctx: Context): Boolean = prefs(ctx).getBoolean("e_panic", false)

    fun saveEmergency(ctx: Context, name: String, number: String, message: String, panic: Boolean) {
        prefs(ctx).edit()
            .putString("e_name", name)
            .putString("e_number", number)
            .putString("e_msg", message)
            .putBoolean("e_panic", panic)
            .apply()
    }

    class Pending(val name: String, val number: String, val body: String, val emergency: Boolean)

    @Synchronized
    fun setPending(ctx: Context, p: Pending) {
        prefs(ctx).edit()
            .putString("p_name", p.name)
            .putString("p_number", p.number)
            .putString("p_body", p.body)
            .putBoolean("p_emerg", p.emergency)
            .putLong("p_at", System.currentTimeMillis())
            .apply()
    }

    /** The waiting text, or null if none or it expired (60 seconds). */
    @Synchronized
    fun pending(ctx: Context): Pending? {
        val p = prefs(ctx)
        val at = p.getLong("p_at", 0L)
        val name = p.getString("p_name", null)
        val number = p.getString("p_number", null)
        val body = p.getString("p_body", null)
        if (at == 0L || name == null || number == null || body == null) return null
        if (System.currentTimeMillis() - at > CONFIRM_WINDOW_MS) {
            clearPending(ctx)
            return null
        }
        return Pending(name, number, body, p.getBoolean("p_emerg", false))
    }

    @Synchronized
    fun clearPending(ctx: Context) {
        prefs(ctx).edit().remove("p_name").remove("p_number").remove("p_body")
            .remove("p_emerg").remove("p_at").apply()
    }
}

/** Sends a text through SmsManager and reports the carrier result by voice. */
object Sms {
    private val YES = setOf("yes", "yeah", "yep", "yup", "send", "send it", "confirm", "do it", "go ahead")
    private val NO = setOf("no", "nope", "cancel", "don't", "dont", "stop", "don't send", "never mind", "nevermind")
    private const val ACTION_SENT = "com.mercureal.voice.SMS_SENT"

    fun hasSendPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * "text <contact> <message>": resolves the contact, stores the text as pending, and returns the
     * read-back. Nothing is sent until the user says yes. Without SEND_SMS (or with no message body)
     * it opens the messaging app composer instead.
     */
    fun begin(ctx: Context, recipient: String, body: String): Actions.Result {
        if (!hasSendPermission(ctx) || body.isBlank()) return Actions.sms(ctx, recipient, body)
        val contact = Actions.findContact(ctx, recipient)
        val number = contact?.second
            ?: recipient.filter { it.isDigit() || it == '+' }.takeIf { it.count { c -> c.isDigit() } >= 3 }
            ?: return Actions.Result(false, "no contact named $recipient")
        val name = contact?.first ?: number
        SmsPrefs.setPending(ctx, SmsPrefs.Pending(name, number, body, false))
        return Actions.Result(true, "text to $name: $body. say yes to send, or no to cancel")
    }

    /**
     * Called before every command. If a text is waiting, "yes" sends it, "no" cancels it, and
     * anything else drops it and lets the new command run. Returns true when the input was consumed.
     */
    fun answerPending(ctx: Context, raw: String, speaker: Speaker): Boolean {
        val pend = SmsPrefs.pending(ctx) ?: return false
        SmsPrefs.clearPending(ctx)
        val t = raw.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
        return when {
            t in YES -> {
                if (deliver(ctx, pend.name, pend.number, pend.body)) {
                    speaker.say("sending text to ${pend.name}")
                } else {
                    Actions.sms(ctx, pend.number, pend.body)
                    speaker.say("couldn't send from here, opened your messages app")
                }
                true
            }
            t in NO -> {
                Store.addHistory(ctx, "text ${pend.name}", "text cancelled", false)
                speaker.say("cancelled")
                true
            }
            else -> false
        }
    }

    /** Hands the text to SmsManager. The result arrives later through [SmsSentReceiver]. */
    @SuppressLint("MissingPermission")
    fun deliver(ctx: Context, name: String, number: String, body: String): Boolean {
        if (!hasSendPermission(ctx)) return false
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val sent = PendingIntent.getBroadcast(
                ctx,
                System.nanoTime().toInt(),
                Intent(ACTION_SENT).setClass(ctx, SmsSentReceiver::class.java).putExtra("name", name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val parts = sm.divideMessage(body)
            if (parts.size <= 1) {
                sm.sendTextMessage(number, null, body, sent, null)
            } else {
                // Only the last part reports, so the user hears one result.
                val sentList = ArrayList<PendingIntent?>()
                repeat(parts.size - 1) { sentList.add(null) }
                sentList.add(sent)
                sm.sendMultipartTextMessage(number, null, parts, sentList, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Emergency mode: one location fix, a maps link, then a text to the saved contact. */
object EmergencyMode {
    fun run(app: Context, speaker: Speaker) {
        val name = SmsPrefs.emergencyName(app)
        val number = SmsPrefs.emergencyNumber(app)
        if (name.isNullOrBlank() || number.isNullOrBlank()) {
            Store.addHistory(app, "emergency", "no emergency contact saved", false)
            speaker.say("no emergency contact saved. add one on the home tab first.")
            return
        }
        speaker.say("getting your location")
        OneShotLocation.get(app, 15_000L) { loc ->
            val base = SmsPrefs.emergencyMessage(app).ifBlank { "emergency, i need help" }
            val body = if (loc != null) {
                "$base my location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            } else {
                "$base (location unavailable)"
            }
            if (!Sms.hasSendPermission(app)) {
                Actions.sms(app, number, body)
                Store.addHistory(app, "emergency", "opened messages to $name", true)
                speaker.say("texting permission is off, opened your messages app to $name")
                return@get
            }
            if (SmsPrefs.panic(app)) {
                if (Sms.deliver(app, name, number, body)) {
                    speaker.say("emergency text sent to $name")
                } else {
                    Actions.sms(app, number, body)
                    speaker.say("couldn't send, opened your messages app to $name")
                }
            } else {
                SmsPrefs.setPending(app, SmsPrefs.Pending(name, number, body, true))
                speaker.say("emergency text to $name: $body. say yes to send, or no to cancel")
            }
        }
    }
}

/** One-shot location: last fix if fresh, else a single fresh fix with a timeout. */
object OneShotLocation {
    private const val FRESH_MS = 10 * 60 * 1000L

    @SuppressLint("MissingPermission")
    fun get(ctx: Context, timeoutMs: Long, cb: (Location?) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cb(null)
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            cb(null)
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val fallback = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
        }.maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time < FRESH_MS }

        val provider = providers.firstOrNull { p ->
            try { lm.isProviderEnabled(p) } catch (e: Exception) { false }
        }
        if (provider == null) {
            cb(fallback)
            return
        }

        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        var listener: LocationListener? = null
        var timeout: Runnable? = null
        fun finish(loc: Location?) {
            if (!done.compareAndSet(false, true)) return
            timeout?.let { main.removeCallbacks(it) }
            listener?.let { try { lm.removeUpdates(it) } catch (e: Exception) { } }
            cb(loc ?: fallback)
        }
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        listener = l
        timeout = Runnable { finish(null) }
        main.postDelayed(timeout, timeoutMs)
        try {
            lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
        } catch (e: SecurityException) {
            finish(null)
        }
    }
}

/** Reports whether the carrier accepted the text. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "contact"
        val ok = resultCode == Activity.RESULT_OK
        val msg = if (ok) "text sent to $name" else "text to $name failed"
        Store.addHistory(context, "sms $name", msg, ok)
        Speaker.instance(context).say(msg)
    }
}
