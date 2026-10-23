package com.mercureal.voice

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Application-scoped TTS. Created once, reused by the service, UI and quick actions. */
class Speaker private constructor(context: Context) {
    private var ready = false
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            synchronized(pending) {
                ready = true
                pending.forEach { speakNow(it) }
                pending.clear()
            }
        }
    }

    fun say(text: String) {
        synchronized(pending) {
            if (!ready) {
                pending.add(text)
                return
            }
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mv-${System.nanoTime()}")
    }

    companion object {
        @Volatile private var inst: Speaker? = null

        fun instance(ctx: Context): Speaker =
            inst ?: synchronized(this) {
                inst ?: Speaker(ctx.applicationContext).also { inst = it }
            }
    }
}
