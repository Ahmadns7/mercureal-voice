package com.mercureal.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Application-scoped TTS. Created once, reused by the service, UI, routines and reminders.
 * [say] takes an optional completion callback (main thread); routines use it to wait between steps.
 */
class Speaker private constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private val callbacks = HashMap<String, () -> Unit>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            synchronized(pending) {
                ready = true
                pending.forEach { (t, d) -> speakNow(t, d) }
                pending.clear()
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = fire(utteranceId)
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) = fire(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = fire(utteranceId)
        })
    }

    private fun fire(id: String?) {
        val cb = synchronized(callbacks) { id?.let { callbacks.remove(it) } } ?: return
        main.post { cb() }
    }

    fun say(text: String, done: (() -> Unit)? = null) {
        synchronized(pending) {
            if (!ready) {
                pending.add(text to done)
                return
            }
        }
        speakNow(text, done)
    }

    /** Stops current speech and drops pending completion callbacks. */
    fun stop() {
        try { tts.stop() } catch (_: Throwable) {}
        synchronized(callbacks) { callbacks.clear() }
    }

    private fun speakNow(text: String, done: (() -> Unit)?) {
        val id = "mv-${System.nanoTime()}"
        if (done != null) synchronized(callbacks) { callbacks[id] = done }
        val clipped = if (text.length > 3500) text.take(3500) else text
        tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    companion object {
        @Volatile private var inst: Speaker? = null

        fun instance(ctx: Context): Speaker =
            inst ?: synchronized(this) {
                inst ?: Speaker(ctx.applicationContext).also { inst = it }
            }
    }
}
