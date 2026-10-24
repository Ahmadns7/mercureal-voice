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
import android.media.AudioManager
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
import android.view.WindowManager
import android.widget.TextView

/**
 * Foreground service: listens for voice commands, shows the floating mic overlay,
 * and runs commands through the shared dispatcher.
 *
 * Two modes. Continuous (default): one recognition pass after another, no partials.
 * Wake word (opt-in, 1.6.0): short listen windows with partial results. When the wake
 * phrase shows up, the next utterance runs as a command. Windows are separated by rests,
 * the loop stops after a long silence, and it pauses on battery saver or under 15% battery.
 */
class VoiceService : Service() {
    private enum class WakeState { OFF, WINDOW, PAUSE, WAKE_HEARD, COMMAND }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var overlay: TextView? = null
    private var listening = false
    private var continuous = true
    private var wm: WindowManager? = null
    private var batteryWarned = false
    private var batteryRegistered = false

    private var wakeMode = false
    private var wakeState = WakeState.OFF
    private var lastSpeechAt = System.currentTimeMillis()
    private var recognizerErrors = 0
    private var statusText = READY_TEXT

    // Auto listen silence tracking. idleSince = last speech (or the tap that started listening).
    private var idleSince = System.currentTimeMillis()
    private var speechActive = false
    // True for a pass the user started by tapping the mic. Passes re-armed by the loop are auto listen.
    private var tapPass = false

    private val windowEnd = Runnable { endWindow() }

    /** Auto listen: stops the loop when no speech has started for [AUTO_SILENCE_STOP_MS]. */
    private val silenceStop = Runnable {
        if (!listening || speechActive) return@Runnable
        val idle = System.currentTimeMillis() - idleSince
        if (idle >= AUTO_SILENCE_STOP_MS) stopAutoListen(AUTO_STOPPED_TEXT) else scheduleSilenceCheck(AUTO_SILENCE_STOP_MS - idle)
    }
    private val nextWindow = Runnable { wakeTick() }
    private val wakeFallback = Runnable {
        if (wakeState == WakeState.WAKE_HEARD) {
            recognizer?.cancel()
            startCommandPass()
        }
    }
    private val commandFallback = Runnable {
        if (wakeState == WakeState.COMMAND) {
            recognizer?.cancel()
            afterCommand()
        }
    }

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
        wakeMode = Store.wakeEnabled(this)
        lastSpeechAt = System.currentTimeMillis()
        statusText = if (wakeMode) wakeOnText() else READY_TEXT
        try {
            createChannel()
            val notif = buildNotification(statusText)
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
        if (wakeMode) wakeTick() else startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> reloadMode()
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

    /** Re-reads the wake word setting and restarts the loop. Called when the user changes it. */
    private fun reloadMode() {
        wakeMode = Store.wakeEnabled(this)
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        listening = false
        wakeState = WakeState.OFF
        recognizerErrors = 0
        lastSpeechAt = System.currentTimeMillis()
        setStatus(if (wakeMode) wakeOnText() else READY_TEXT)
        if (wakeMode) wakeTick() else startListening()
    }

    override fun onDestroy() {
        instance = null
        listening = false
        wakeState = WakeState.OFF
        main.removeCallbacksAndMessages(null)
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

    /** Updates the foreground notification text only when it changes. */
    private fun setStatus(text: String) {
        if (text == statusText) return
        statusText = text
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Throwable) {
            android.util.Log.w("MercuReal", "status update failed", e)
        }
    }

    private fun wakeOnText() = "Wake word on: say \"${Store.wakePhrase(this)}\""

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

    private fun recognizerIntent(partial: Boolean = false) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
        // Offline-first: prefer on-device recognition when the language pack is installed.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /**
     * Starts one recognition pass. Continuous mode re-arms after each result.
     * In wake word mode a tap on the mic runs a command right away.
     */
    fun startListening() {
        if (!hasMic()) {
            Speaker.instance(this).say("microphone permission is off")
            return
        }
        val rec = ensureRecognizer() ?: run {
            Speaker.instance(this).say("speech recognition isn't available on this phone")
            return
        }
        if (wakeMode) {
            commandNow()
            return
        }
        if (listening) return
        idleSince = System.currentTimeMillis()
        tapPass = true
        beginPass()
    }

    /**
     * Starts one auto listen pass. Yields to our own TTS, releases the loop when another app is
     * recording, and stops after [AUTO_SILENCE_STOP_MS] with no speech.
     */
    private fun beginPass() {
        if (listening) return
        val rec = recognizer ?: return
        if (continuous && Speaker.instance(this).isSpeaking) {
            // Our own reply is playing: don't listen to it, and don't count it as silence.
            idleSince = System.currentTimeMillis()
            main.postDelayed({ beginPass() }, 500)
            return
        }
        if (foreignMicActive()) {
            // Another app holds the mic: stay idle and check again shortly. Our own recognizer is idle here.
            idleSince = System.currentTimeMillis()
            setStatus(AUTO_FOREIGN_MIC_TEXT)
            main.postDelayed({ beginPass() }, FOREIGN_MIC_RECHECK_MS)
            return
        }
        val remaining = AUTO_SILENCE_STOP_MS - (System.currentTimeMillis() - idleSince)
        if (remaining <= 0) {
            stopAutoListen(AUTO_STOPPED_TEXT)
            return
        }
        listening = true
        speechActive = false
        setOverlayState(true)
        scheduleSilenceCheck(remaining)
        main.post { rec.startListening(recognizerIntent()) }
    }

    private fun scheduleSilenceCheck(delayMs: Long) {
        main.removeCallbacks(silenceStop)
        main.postDelayed(silenceStop, delayMs.coerceAtLeast(0L))
    }

    /** Ends the auto listen loop without re-arming. A tap on the mic starts it again. */
    private fun stopAutoListen(status: String) {
        main.removeCallbacks(silenceStop)
        listening = false
        speechActive = false
        recognizer?.cancel()
        setOverlayState(false)
        setStatus(status)
    }

    /**
     * True when any app has an active microphone session. Only call this while our recognizer is
     * idle, because the speech service records on our behalf while a pass runs. Android 10 (API 29)
     * and up only; older phones can't report it, so auto listen behaves as before there.
     */
    private fun foreignMicActive(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            getSystemService(AudioManager::class.java).activeRecordingConfigurations.isNotEmpty()
        } catch (e: SecurityException) {
            false
        }
    }

    private fun stopListeningPass() {
        listening = false
        main.removeCallbacks(silenceStop)
        setOverlayState(false)
        recognizer?.cancel()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {
            speechActive = true
            idleSince = System.currentTimeMillis()
            main.removeCallbacks(silenceStop)
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            speechActive = false
            idleSince = System.currentTimeMillis()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!wakeMode || wakeState != WakeState.WINDOW) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) return
            lastSpeechAt = System.currentTimeMillis()
            recognizerErrors = 0
            if (Wake.match(this@VoiceService, text) != null) {
                // Wake phrase heard. Stop the window and wait for the final text so the words after it count.
                main.removeCallbacks(windowEnd)
                wakeState = WakeState.WAKE_HEARD
                setStatus("Heard wake word. Say a command.")
                main.removeCallbacks(wakeFallback)
                main.postDelayed(wakeFallback, 4000)
                recognizer?.stopListening()
            }
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

        override fun onResults(results: android.os.Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (wakeMode) {
                recognizerErrors = 0
                onWakeResults(text)
                return
            }
            listening = false
            speechActive = false
            main.removeCallbacks(silenceStop)
            if (!text.isNullOrBlank()) {
                idleSince = System.currentTimeMillis()
                // Auto listen never brings MercuReal's own screens forward. Only the wake word can.
                execute(this@VoiceService, text, if (tapPass) "voice" else SOURCE_AUTO)
            }
            rearmIfContinuous()
        }

        override fun onError(error: Int) {
            if (wakeMode) {
                onWakeError(error)
                return
            }
            listening = false
            speechActive = false
            main.removeCallbacks(silenceStop)
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
        if (continuous) main.postDelayed({ tapPass = false; beginPass() }, 600)
    }

    // ---------- wake word duty cycle ----------

    /** One listen window, if the battery and silence guards allow it. Otherwise waits or stops. */
    private fun wakeTick() {
        if (!wakeMode) return
        if (wakeState == WakeState.WINDOW || wakeState == WakeState.WAKE_HEARD || wakeState == WakeState.COMMAND) return
        if (!hasMic()) {
            wakeState = WakeState.PAUSE
            setStatus("Wake word off: microphone permission is off")
            return
        }
        val rec = ensureRecognizer() ?: run {
            wakeState = WakeState.PAUSE
            setStatus("Wake word can't run: this phone has no speech recognition")
            return
        }
        if (foreignMicActive()) {
            // Another app is recording: release the mic and check again later.
            wakeState = WakeState.PAUSE
            setStatus("Wake word paused: another app is using the microphone")
            scheduleNext(Wake.RECHECK_MS)
            return
        }
        Wake.suspendReason(this)?.let { reason ->
            wakeState = WakeState.PAUSE
            setStatus("Wake word paused: $reason")
            scheduleNext(Wake.RECHECK_MS)
            return
        }
        if (System.currentTimeMillis() - lastSpeechAt > Wake.SILENCE_STOP_MS) {
            wakeState = WakeState.PAUSE
            setStatus("Wake word paused after 5 minutes of silence. Tap the mic to resume.")
            return
        }
        if (recognizerErrors >= Wake.MAX_ERRORS) {
            wakeState = WakeState.PAUSE
            setStatus("Wake word stopped: the speech service keeps failing. Tap the mic to retry.")
            return
        }
        wakeState = WakeState.WINDOW
        setStatus(wakeOnText())
        setOverlayState(true)
        main.postDelayed(windowEnd, Wake.windowMs(this))
        main.post {
            rec.cancel()
            rec.startListening(recognizerIntent(partial = true))
        }
    }

    private fun endWindow() {
        if (wakeState != WakeState.WINDOW) return
        main.removeCallbacks(windowEnd)
        recognizer?.cancel()
        wakeState = WakeState.PAUSE
        setOverlayState(false)
        scheduleNext(Wake.pauseMs(this))
    }

    private fun scheduleNext(delayMs: Long) {
        main.removeCallbacks(nextWindow)
        main.postDelayed(nextWindow, delayMs)
    }

    private fun clearWakeCallbacks() {
        main.removeCallbacks(windowEnd)
        main.removeCallbacks(wakeFallback)
        main.removeCallbacks(commandFallback)
    }

    /** Tap on the mic in wake mode: run one command now, and reset the silence stop. */
    private fun commandNow() {
        if (wakeState == WakeState.COMMAND || wakeState == WakeState.WAKE_HEARD) return
        lastSpeechAt = System.currentTimeMillis()
        recognizerErrors = 0
        main.removeCallbacks(nextWindow)
        startCommandPass()
    }

    /** Listens for one full utterance after the wake phrase (or a tap). */
    private fun startCommandPass() {
        val rec = recognizer ?: return
        clearWakeCallbacks()
        wakeState = WakeState.COMMAND
        setStatus("Heard wake word. Say a command.")
        setOverlayState(true)
        main.postDelayed(commandFallback, 10_000)
        main.post {
            rec.cancel()
            rec.startListening(recognizerIntent(partial = false))
        }
    }

    private fun afterCommand() {
        clearWakeCallbacks()
        wakeState = WakeState.PAUSE
        setOverlayState(false)
        // Short rest so the spoken reply finishes before the next window opens.
        scheduleNext(1500)
    }

    private fun onWakeResults(text: String?) {
        clearWakeCallbacks()
        when (wakeState) {
            WakeState.WINDOW -> {
                // Final text from a window that did not catch the phrase on the way.
                if (!text.isNullOrBlank()) lastSpeechAt = System.currentTimeMillis()
                val rest = text?.let { Wake.match(this, it) }
                when {
                    rest == null -> endWindow()
                    rest.isNotBlank() -> {
                        execute(this, rest, "wake")
                        afterCommand()
                    }
                    else -> startCommandPass()
                }
            }
            WakeState.WAKE_HEARD -> {
                val rest = text?.let { Wake.match(this, it) }.orEmpty()
                if (rest.isNotBlank()) {
                    execute(this, rest, "wake")
                    afterCommand()
                } else {
                    startCommandPass()
                }
            }
            WakeState.COMMAND -> {
                if (!text.isNullOrBlank()) execute(this, text, "voice")
                afterCommand()
            }
            else -> afterCommand()
        }
    }

    private fun onWakeError(error: Int) {
        when (wakeState) {
            WakeState.WAKE_HEARD -> {
                clearWakeCallbacks()
                startCommandPass()
            }
            WakeState.COMMAND -> afterCommand()
            WakeState.WINDOW -> {
                clearWakeCallbacks()
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (quiet) recognizerErrors = 0 else recognizerErrors++
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) recognizer?.cancel()
                wakeState = WakeState.PAUSE
                setOverlayState(false)
                scheduleNext(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 3000 else Wake.pauseMs(this))
            }
            else -> Unit
        }
    }

    // ---------- overlay ----------

    private fun showOverlay() {
        if (overlay != null) return
        wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val accent = Theme.palette(this).accent
        val btn = TextView(this).apply {
            text = "MIC"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
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
            val accent = Theme.palette(this).accent
            overlay?.text = if (active) "..." else "MIC"
            overlay?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) Color.parseColor("#34D399") else accent)
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
        const val ACTION_RELOAD = "reload"
        const val READY_TEXT = "Ready. Tap the mic or say a command."
        /** Auto listen stop after this long with no speech. Separate from the wake word window. */
        const val AUTO_SILENCE_STOP_MS = 6_000L
        const val AUTO_STOPPED_TEXT = "Auto listen stopped after 6 seconds with no speech. Tap the mic to listen again."
        const val AUTO_FOREIGN_MIC_TEXT = "Auto listen paused: another app is using the microphone. Tap the mic to resume."
        /** Source tag for commands spoken during auto listen (no wake word). */
        const val SOURCE_AUTO = "auto"
        const val FOREIGN_MIC_RECHECK_MS = 2_000L
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

        /** Tells a running service to re-read the wake word setting. No-op when it is not running. */
        fun reload(ctx: Context) {
            if (instance == null) return
            ctx.startService(Intent(ctx, VoiceService::class.java).putExtra(EXTRA_ACTION, ACTION_RELOAD))
        }

        /**
         * Runs any command text (spoken, typed, or a quick action) and speaks + logs the result.
         * Safe to call from any thread the caller is on; work is posted to the main thread.
         */
        fun execute(ctx: Context, raw: String, source: String = "typed") {
            val app = ctx.applicationContext
            val aliases = Store.aliases(app)
            val speaker = Speaker.instance(app)
            if (Sms.answerPending(app, raw, speaker)) return
            val cmd = CommandParser.parse(raw, aliases, language = Store.language(app))
            if (cmd is Command.Emergency) {
                EmergencyMode.run(app, speaker)
                return
            }
            if (source == SOURCE_AUTO && (cmd is Command.CarModeOn || cmd is Command.OpenApp)) {
                // Screens and other apps only come forward after the wake word.
                speaker.say("say the wake word first")
                return
            }
            if (cmd is Command.Screenshot) {
                ScreenshotCapture.capture(app) { ok, msg ->
                    Store.addHistory(app, raw, msg, ok)
                    speaker.say(msg)
                    if (!ok && !ScreenshotCapture.hasGrant() && source != SOURCE_AUTO) {
                        ProjectionRequestActivity.launch(app)
                    }
                }
                return
            }
            if (cmd is Command.RunRoutine) {
                runRoutine(app, cmd.name, raw, speaker)
                return
            }
            if (cmd is Command.ReadScreen || cmd is Command.StopReading) {
                // Paginated reading: the result is spoken per page by ScreenReader itself.
                val r = Actions.run(app, cmd)
                Store.addHistory(app, raw, r.speech, r.ok)
                if (!r.ok || cmd is Command.StopReading) speaker.say(r.speech)
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
                val cmd = CommandParser.parse(steps[i], Store.aliases(app), language = Store.language(app))
                val result = Actions.run(app, cmd)
                speaker.say(result.speech) { handler.postDelayed({ next(i + 1) }, 250) }
            }
            next(0)
        }

        /** Speaks a short confirmation from anywhere in the app. */
        fun say(ctx: Context, text: String) = Speaker.instance(ctx).say(text)
    }
}
