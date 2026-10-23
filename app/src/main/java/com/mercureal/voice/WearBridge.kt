package com.mercureal.voice

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Paths and capability shared with the Wear OS companion (wear/ module). */
object WearBridge {
    const val COMMAND_PATH = "/mercureal/command"
    const val REPLY_PATH = "/mercureal/reply"
    const val CAPABILITY = "mercureal_voice_phone"
    private const val MAX_CHARS = 300

    @Volatile private var replyNode: String? = null

    /** The next phone speech (the confirmation) goes back to this watch node. */
    fun arm(nodeId: String) {
        replyNode = nodeId
    }

    /** Called by Speaker.say for every spoken line. Sends it to the watch only when armed. */
    fun offer(ctx: Context, text: String) {
        val node = replyNode ?: return
        replyNode = null
        val clipped = text.take(MAX_CHARS)
        Wearable.getMessageClient(ctx).sendMessage(node, REPLY_PATH, clipped.toByteArray(Charsets.UTF_8))
    }

    fun cleanCommand(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).trim().take(MAX_CHARS)
}

/** Receives a voice query from the watch and runs it through the same command engine as the phone. */
class WearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearBridge.COMMAND_PATH) return
        val text = WearBridge.cleanCommand(event.data)
        if (text.isEmpty()) return
        WearBridge.arm(event.sourceNodeId)
        VoiceService.execute(applicationContext, text, "watch")
    }
}
