package com.mercureal.voice

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Last-resort reporter. Anything that fails during startup is logged under the
 * "MercuReal" tag and shown as a toast, so the user sees a reason instead of an instant close.
 */
object CrashReport {
    private const val TAG = "MercuReal"

    fun report(ctx: Context?, where: String, e: Throwable) {
        Log.e(TAG, "failed in $where", e)
        if (ctx == null) return
        try {
            Toast.makeText(ctx.applicationContext, "MercuReal Voice: $where failed (${e.javaClass.simpleName}: ${e.message})", Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // Toast can fail on a dead context; the log line above is enough.
        }
    }
}
