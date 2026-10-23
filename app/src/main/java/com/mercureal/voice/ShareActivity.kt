package com.mercureal.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Share target: "Share" -> MercuReal Voice on any text. The first non-blank line runs as a
 * command through the same assistant the chat tab uses, and the exchange is saved to the chat.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val text = shared?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(200)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "share some text to run it", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Assistant.respond(this, text) { reply ->
            Store.addChat(applicationContext, "u", text)
            Store.addChat(applicationContext, "a", reply.text)
            Toast.makeText(applicationContext, reply.text, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
