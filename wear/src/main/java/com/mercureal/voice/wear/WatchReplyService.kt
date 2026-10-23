package com.mercureal.voice.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Shows the phone's spoken confirmation on the watch and gives a short vibration. */
class WatchReplyService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != REPLY_PATH) return
        val text = String(event.data, Charsets.UTF_8).trim().take(300)
        if (text.isEmpty()) return
        vibrate()
        post(text)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(180)
        }
    }

    private fun post(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "MercuReal replies", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MercuReal")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    companion object {
        const val REPLY_PATH = "/mercureal/reply"
        private const val CHANNEL = "mercureal_replies"
        private const val NOTIFY_ID = 4201
    }
}
