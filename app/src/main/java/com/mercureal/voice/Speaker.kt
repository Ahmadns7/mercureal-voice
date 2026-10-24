package com.mercureal.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private val appCtx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    @Volatile private var speaking = false

    /** True while our own TTS is speaking. Auto listen ignores this playback when checking the speaker. */
    val isSpeaking: Boolean get() = speaking
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

    private var focus: AudioFocusRequest? = null

    /** Brief transient focus while our reply plays: other audio ducks, nothing is stopped for good. */
    private fun takeFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            appCtx.getSystemService(AudioManager::class.java).requestAudioFocus(req)
            focus = req
        } catch (_: Throwable) {}
    }

    /** Gives audio focus back as soon as our reply finishes or is stopped. */
    private fun dropFocus() {
        val req = focus ?: return
        focus = null
        try { appCtx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req) } catch (_: Throwable) {}
    }

    private fun fire(id: String?) {
        speaking = false
        dropFocus()
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
        speaking = false
        dropFocus()
        try { tts.stop() } catch (_: Throwable) {}
        synchronized(callbacks) { callbacks.clear() }
    }

    private fun speakNow(text: String, done: (() -> Unit)?) {
        val id = "mv-${System.nanoTime()}"
        if (done != null) synchronized(callbacks) { callbacks[id] = done }
        val clipped = if (text.length > 3500) text.take(3500) else text
        WearBridge.offer(appCtx, clipped)
        speaking = true
        takeFocus()
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
