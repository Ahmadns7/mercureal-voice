package com.mercureal.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Foreground service: listens for voice commands, shows the floating mic overlay,
 * and runs commands through the shared dispatcher.
 */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var overlay: TextView? = null
    private var listening = false
    private var continuous = true
    private var wm: WindowManager? = null
    private var batteryWarned = false
    private var batteryRegistered = false
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent == null) return
            val lvl = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            if (lvl < 0 || scale <= 0) return
            val pct = lvl * 100 / scale
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (charging || pct >= 20) { batteryWarned = false; return }
            // Low battery while the listener is running: warn once per discharge.
            if (pct < 15 && !batteryWarned) {
                batteryWarned = true
                notifyLowBattery(pct)
            }
        }
    }

    private fun notifyLowBattery(pct: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(BATTERY_CHANNEL, "Battery warning", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, BATTERY_CHANNEL) else Notification.Builder(this)
            val n = b.setContentTitle("Battery low ($pct%)")
                .setContentText("MercuReal is still listening. Charge soon, or turn off Continuous listening to save battery.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(BATTERY_NOTIF_ID, n)
        } catch (e: Throwable) {
            android.util.Log.w("MercuReal", "battery warning failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        continuous = Store.continuous(this)
        try {
            createChannel()
            val notif = buildNotification("Ready. Tap the mic or say a command.")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            // Android refused the foreground start (missing mic permission or background start limits).
            CrashReport.report(this, "voice service start", e)
            stopSelf()
            return
        }
        try {
            registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
        } catch (e: Throwable) {
            android.util.Log.w("MercuReal", "battery receiver not registered", e)
        }
        try {
            if (Settings.canDrawOverlays(this)) showOverlay()
        } catch (e: SecurityException) {
            android.util.Log.w("MercuReal", "overlay permission refused", e)
        }
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION -> {
                // Android 14+: MediaProjection needs a running mediaProjection-type foreground service.
                val notif = buildNotification("Screen capture ready.")
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIF_ID, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                }
            }
        }
        return START_STICKY
    }


    override fun onDestroy() {
        instance = null
        listening = false
        if (batteryRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
            batteryRegistered = false
        }
        recognizer?.destroy()
        recognizer = null
        overlay?.let { wm?.removeView(it) }
        overlay = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MercuReal Voice", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).putExtra(EXTRA_ACTION, ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("MercuReal Voice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    // ---------- speech ----------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        return recognizer
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        // Offline-first: prefer on-device recognition when the language pack is installed.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** Starts one recognition pass. Continuous mode re-arms after each result. */
    fun startListening() {
        if (!hasMic()) {
            Speaker.instance(this).say("microphone permission is off")
            return
        }
        val rec = ensureRecognizer() ?: run {
            Speaker.instance(this).say("speech recognition isn't available on this phone")
            return
        }
        if (listening) return
        listening = true
        setOverlayState(true)
        main.post { rec.startListening(recognizerIntent()) }
    }

    private fun stopListeningPass() {
        listening = false
        setOverlayState(false)
        recognizer?.cancel()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

        override fun onResults(results: android.os.Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) execute(this@VoiceService, text, "voice")
            rearmIfContinuous()
        }

        override fun onError(error: Int) {
            listening = false
            setOverlayState(false)
            // 7 = no match, 6 = no speech: normal when continuous, so re-arm quietly.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    recognizer?.cancel()
                }
            }
            rearmIfContinuous()
        }
    }

    private fun rearmIfContinuous() {
        if (continuous) main.postDelayed({ startListening() }, 600)
    }

    // ---------- overlay ----------

    private fun showOverlay() {
        if (overlay != null) return
        wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val btn = TextView(this).apply {
            text = "MIC"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EMERALD) }
            elevation = 8 * dp
            setOnClickListener { if (listening) stopListeningPass() else startListening() }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = (16 * dp).toInt()
            y = (120 * dp).toInt()
        }
        try {
            wm?.addView(btn, lp)
            overlay = btn
        } catch (e: SecurityException) {
            // SYSTEM_ALERT_WINDOW was revoked or never granted. Voice still works without the bubble.
            android.util.Log.w("MercuReal", "overlay permission refused", e)
        } catch (e: android.view.WindowManager.BadTokenException) {
            android.util.Log.w("MercuReal", "overlay window token rejected", e)
        } catch (e: RuntimeException) {
            android.util.Log.w("MercuReal", "overlay could not be added", e)
        }
    }

    private fun setOverlayState(active: Boolean) {
        main.post {
            overlay?.text = if (active) "..." else "MIC"
            overlay?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) Color.parseColor("#34D399") else EMERALD)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "mercureal_voice"
        const val NOTIF_ID = 1001
        const val BATTERY_CHANNEL = "mercureal_battery"
        const val BATTERY_NOTIF_ID = 1002
        const val EXTRA_ACTION = "action"
        const val ACTION_STOP = "stop"
        const val ACTION_PROJECTION = "projection"
        const val EMERALD = 0xFF10B981.toInt()

        @Volatile var instance: VoiceService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceService::class.java))
        }

        fun isRunning() = instance != null

        /**
         * Runs any command text (spoken, typed, or a quick action) and speaks + logs the result.
         * Safe to call from any thread the caller is on; work is posted to the main thread.
         */
        fun execute(ctx: Context, raw: String, source: String = "typed") {
            val app = ctx.applicationContext
            val aliases = Store.aliases(app)
            val cmd = CommandParser.parse(raw, aliases)
            val speaker = Speaker.instance(app)
            if (cmd is Command.Screenshot) {
                ScreenshotCapture.capture(app) { ok, msg ->
                    Store.addHistory(app, raw, msg, ok)
                    speaker.say(msg)
                    if (!ok && !ScreenshotCapture.hasGrant()) {
                        ProjectionRequestActivity.launch(app)
                    }
                }
                return
            }
            if (cmd is Command.RunRoutine) {
                runRoutine(app, cmd.name, raw, speaker)
                return
            }
            val result = Actions.run(app, cmd)
            Store.addHistory(app, raw, result.speech, result.ok)
            speaker.say(result.speech)
        }

        /** Runs routine steps in order, waiting for each step's speech to finish before the next. */
        fun runRoutine(app: Context, name: String, raw: String, speaker: Speaker) {
            val routine = Routines.find(app, name)
            if (routine == null) {
                speaker.say("no routine called $name")
                return
            }
            val steps = routine.steps
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            fun next(i: Int) {
                if (i >= steps.size) {
                    Store.addHistory(app, raw, "ran ${routine.name}: ${steps.size} steps", true)
                    return
                }
                val cmd = CommandParser.parse(steps[i], Store.aliases(app))
                val result = Actions.run(app, cmd)
                speaker.say(result.speech) { handler.postDelayed({ next(i + 1) }, 250) }
            }
            next(0)
        }

        /** Speaks a short confirmation from anywhere in the app. */
        fun say(ctx: Context, text: String) = Speaker.instance(ctx).say(text)
    }
}
