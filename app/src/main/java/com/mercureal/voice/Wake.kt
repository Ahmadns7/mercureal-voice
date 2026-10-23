package com.mercureal.voice

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.speech.SpeechRecognizer

/**
 * Wake word helpers: phrase matching on partial results, the battery and power-save guards,
 * and a check for on-device recognition. Pure logic lives here so it can be read and tested alone.
 */
object Wake {
    const val DEFAULT_PHRASE = "mercureal"
    const val WINDOW_MS = 6_000L          // one listen window
    const val PAUSE_MS = 2_500L           // rest between windows
    const val SILENCE_STOP_MS = 300_000L  // stop the loop after 5 minutes with no speech heard
    const val RECHECK_MS = 60_000L        // how often a suspended loop re-checks battery and saver
    const val MAX_ERRORS = 5              // consecutive speech service failures before the loop gives up
    const val LOW_BATTERY_PCT = 15

    /**
     * Returns the words spoken after the wake phrase (possibly empty) when the text contains it,
     * or null when it does not. Matching is approximate: words are compared with a small edit
     * tolerance, and adjacent words are joined so "mercury al" still counts as the phrase.
     */
    fun match(ctx: Context, text: String): String? = matchPhrase(text, Store.wakePhrase(ctx))

    internal fun matchPhrase(text: String, phrase: String): String? {
        val target = phrase.lowercase().filter { it.isLetterOrDigit() }
        if (target.isEmpty()) return null
        val tokens = text.lowercase().split(Regex("\\s+")).map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val tol = if (target.length <= 4) 1 else maxOf(1, target.length / 4)
        val maxWords = phrase.trim().split(Regex("\\s+")).size + 1
        for (i in tokens.indices) {
            var joined = ""
            for (k in 1..maxWords) {
                val j = i + k - 1
                if (j >= tokens.size) break
                joined += tokens[j]
                if (distance(joined, target) <= tol) {
                    val rest = tokens.drop(j + 1).joinToString(" ")
                    return rest
                }
            }
        }
        return null
    }

    /** Levenshtein distance, two rows, small inputs only. */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** A user-facing reason the loop should not listen right now, or null if it may run. */
    fun suspendReason(ctx: Context): String? {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return "battery saver is on"
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging && pct in 0 until LOW_BATTERY_PCT) return "battery under $LOW_BATTERY_PCT%"
        return null
    }

    /**
     * Honest support check. On Android 12+ the platform can tell us whether an on-device
     * recognizer exists. Partial-result quality is not exposed, so this is a best-effort signal.
     * Returns a warning string to show in the app, or null when nothing is known to be wrong.
     */
    fun speechWarning(ctx: Context): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            return "This phone has no speech recognition service, so the wake word can't run."
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                return "This phone's speech service reports no on-device recognizer. Wake word may need the network, and partial results may be missing. Install an offline speech pack in Google's voice settings and test it."
            }
        }
        return null
    }
}
