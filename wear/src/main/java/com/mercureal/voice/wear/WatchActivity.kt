package com.mercureal.voice.wear

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import com.google.android.gms.wearable.Wearable

/**
 * Single screen: a big mic button. Tap, say a command, the text goes to the phone
 * (MercuReal Voice) through the Wearable MessageClient. The phone's spoken reply comes
 * back to this watch via WatchReplyService (notification + vibration).
 */
class WatchActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var mic: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1410"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        mic = Button(this).apply {
            text = "MIC"
            setTextColor(Color.parseColor("#06150E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            setOnClickListener { startListening() }
        }
        status = TextView(this).apply {
            text = "tap, then say a command"
            setTextColor(Color.parseColor("#D1FAE5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(mic)
        root.addView(status)
        return root
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "say a command")
        }
        try {
            startActivityForResult(intent, REQ_SPEECH)
            status.text = "listening"
        } catch (e: Exception) {
            status.text = "speech input not available on this watch"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) {
            status.text = "nothing heard, tap to try again"
            return
        }
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) {
            status.text = "nothing heard, tap to try again"
            return
        }
        sendToPhone(text)
    }

    private fun sendToPhone(text: String) {
        status.text = "sending: $text"
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (phone == null) {
                status.text = "phone not connected. open MercuReal on the phone"
                return@addOnSuccessListener
            }
            messageClient.sendMessage(phone.id, COMMAND_PATH, text.toByteArray(Charsets.UTF_8))
                .addOnSuccessListener { status.text = "sent to ${phone.displayName}. waiting for reply" }
                .addOnFailureListener { status.text = "could not reach the phone" }
        }.addOnFailureListener { status.text = "could not find the phone" }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val COMMAND_PATH = "/mercureal/command"
        private const val REQ_SPEECH = 41
    }
}
