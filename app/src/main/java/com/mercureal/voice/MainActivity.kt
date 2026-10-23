package com.mercureal.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-activity UI built from framework views (no AndroidX, so the raw build route works).
 * Dark surface, emerald accent. Tabs: Home, Assistant (on-device chat), Apps, History, Aliases.
 */
class MainActivity : Activity() {
    // Colours come from the active theme and accent (1.6.0). Read on each access.
    private val pal get() = Theme.palette(this)
    private val bg get() = pal.bg
    private val card get() = pal.card
    private val bubbleUser get() = pal.bubbleUser
    private val text get() = pal.text
    private val muted get() = pal.muted
    private val emerald get() = pal.accent
    private val red get() = pal.red
    // Not created in a field initializer: views need a Context, and fields run before the
    // Activity is attached (v1.0.0 crashed on open because of this).
    private lateinit var body: LinearLayout
    private var tab = "home"
    // live brain-download progress widgets, rebuilt whenever the chat tab renders
    private var brainBar: ProgressBar? = null
    private var brainLabel: TextView? = null
    private var brainLastUi = 0L
    private var brainActive = false
    private var tourPage = 0
    private var appQuery = ""
    private var pendingBackupJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            body = LinearLayout(this)
            window.statusBarColor = bg
            window.navigationBarColor = bg
            if (!Store.isOnboarded(this)) showTour() else showTab("home")
        handleIntent(intent)
        } catch (e: Throwable) {
            CrashReport.report(this, "screen start", e)
            showFallback(e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Widget taps: the mic widget opens the app and starts listening. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_LISTEN, false)) {
            i.removeExtra(EXTRA_LISTEN)
            if (Store.isOnboarded(this)) startListeningFlow()
        }
        i.getStringExtra(EXTRA_ROUTINE)?.let { name ->
            i.removeExtra(EXTRA_ROUTINE)
            if (Store.isOnboarded(this)) runCommand(name)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::body.isInitialized) return
        try {
            if (Store.isOnboarded(this)) showTab(tab)
        } catch (e: Throwable) {
            CrashReport.report(this, "screen refresh", e)
        }
    }

    /** Plain fallback screen so a failed UI build still shows the reason. */
    private fun showFallback(e: Throwable) {
        try {
            setContentView(TextView(this).apply {
                setBackgroundColor(bg)
                setTextColor(this@MainActivity.text)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                text = "MercuReal Voice hit an error starting the screen:\n${e.javaClass.simpleName}: ${e.message}\n\nSend this to the developer."
            })
        } catch (_: Throwable) {
            // Nothing else we can do; CrashReport already logged it.
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun root(): LinearLayout {
        body.removeAllViews()
        body.orientation = LinearLayout.VERTICAL
        body.setBackgroundColor(bg)
        body.setPadding(dp(18), dp(18), dp(18), dp(12))
        setContentView(body)
        return body
    }

    private fun label(s: String, size: Float = 15f, color: Int = text, bold: Boolean = false) = TextView(this).apply {
        setText(s)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(this@MainActivity.card); cornerRadius = dp(16).toFloat() }
    }

    private fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        setText(s)
        isAllCaps = false
        setTextColor(if (primary) Color.BLACK else emerald)
        background = GradientDrawable().apply {
            setColor(if (primary) emerald else Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            if (!primary) setStroke(dp(1), emerald)
        }
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(muted)
        setTextColor(this@MainActivity.text)
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun lp(margin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(margin); bottomMargin = dp(margin) }

    private fun navBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        listOf("home" to "Home", "chat" to "Assistant", "apps" to "Apps", "usage" to "Usage", "history" to "History", "aliases" to "Aliases")
            .forEach { (key, name) ->
                addView(TextView(this@MainActivity).apply {
                    setText(name)
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(if (tab == key) emerald else muted)
                    setTypeface(typeface, if (tab == key) Typeface.BOLD else Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { showTab(key) }
                })
            }
    }

    private fun showTab(which: String) {
        tab = which
        val b = root()
        b.addView(label("MercuReal Voice", 22f, text, true))
        b.addView(label("offline voice control, on-device", 12f, muted))
        b.addView(spacer(10))
        b.addView(navBar())
        b.addView(spacer(8))
        when (which) {
            "chat" -> chatView(b)
            "apps" -> appsView(b)
            "usage" -> usageView(b)
            else -> {
                val scroll = ScrollView(this)
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                scroll.addView(content)
                b.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                when (which) {
                    "history" -> historyView(content)
                    "aliases" -> aliasView(content)
                    else -> homeView(content)
                }
            }
        }
    }

    // ---------- Home ----------

    private fun homeView(c: LinearLayout) {
        val status = card()
        status.addView(label("Voice listener", 13f, muted))
        val running = VoiceService.isRunning()
        status.addView(label(if (running) "Listening in background" else "Off", 17f, text, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (running) "Stop" else "Start listening", primary = !running) {
            if (running) VoiceService.stop(this) else startListeningFlow()
            showTab("home")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        status.addView(spacer(10))
        status.addView(row)
        c.addView(status, lp())

        c.addView(label("Type or speak a command", 13f, muted), lp(12))
        val input = field("e.g. screenshot, call mom, flashlight on, timer 5 minutes")
        input.inputType = InputType.TYPE_CLASS_TEXT
        c.addView(input, lp(6))
        c.addView(button("Run") {
            val t = input.text.toString()
            if (t.isNotBlank()) {
                runCommand(t)
                input.setText("")
            }
        }, lp(6))

        c.addView(label("Quick actions", 13f, muted), lp(14))
        val grid = GridLayout(this).apply { columnCount = 2 }
        val actions = listOf(
            "Screenshot" to "screenshot",
            "Flashlight" to "flashlight toggle",
            "Battery" to "battery",
            "Time" to "what time is it",
            "Wi-Fi" to "open wifi settings",
            "Bluetooth" to "open bluetooth",
            "Volume up" to "volume up",
            "Volume down" to "volume down",
            "Car mode" to "car mode",
        )
        actions.forEach { (name, cmd) ->
            grid.addView(button(name, primary = false) { runCommand(cmd) }.apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            })
        }
        c.addView(grid, lp(6))

        c.addView(label("Permissions", 13f, muted), lp(14))
        val perms = card()
        perms.addView(permRow("Microphone", Manifest.permission.RECORD_AUDIO))
        perms.addView(permRow("Phone calls", Manifest.permission.CALL_PHONE))
        perms.addView(permRow("Contacts", Manifest.permission.READ_CONTACTS))
        perms.addView(permRow("Camera (flashlight)", Manifest.permission.CAMERA))
        perms.addView(permRow("Send texts (SMS)", Manifest.permission.SEND_SMS))
        perms.addView(permRow("Location (emergency)", Manifest.permission.ACCESS_FINE_LOCATION))
        perms.addView(button("Grant text and location access", primary = false) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_PERMS
            )
        }.apply { layoutParams = lp(8) })
        perms.addView(label(
            "Overlay (floating mic): " + if (Settings.canDrawOverlays(this)) "on" else "off",
            14f, text
        ))
        perms.addView(button("Grant screenshot access", primary = false) {
            ProjectionRequestActivity.launch(this)
        }.apply { layoutParams = lp(8) })
        perms.addView(label(
            "Screen reader (read screen aloud): " + if (ScreenReader.enabled()) "on" else "off",
            14f, if (ScreenReader.enabled()) emerald else muted
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        perms.addView(button("Turn on screen reader", primary = false) {
            if (!ScreenReader.openSettings(this)) {
                Toast.makeText(this, "couldn't open accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = lp(4) })
        perms.addView(label(
            "Screen time (usage access): " + if (UsageStatsReader.granted(this)) "on" else "off",
            14f, if (UsageStatsReader.granted(this)) emerald else muted
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        perms.addView(button("Turn on usage access", primary = false) {
            if (!UsageStatsReader.openSettings(this)) {
                Toast.makeText(this, "couldn't open usage access settings", Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = lp(4) })
        perms.addView(button("Show tour and privacy", primary = false) {
            tourPage = 0
            showTour()
        }.apply { layoutParams = lp(6) })
        c.addView(perms, lp())

        c.addView(label("Emergency", 13f, muted), lp(14))
        val em = card()
        em.addView(label("Say \"emergency\" or \"help me\" to text your location to this contact.", 12f, muted))
        val nameField = field("contact name").apply { setText(SmsPrefs.emergencyName(this@MainActivity) ?: "") }
        val numField = field("phone number, e.g. +234...").apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setText(SmsPrefs.emergencyNumber(this@MainActivity) ?: "")
        }
        val msgField = field("message (optional), e.g. i need help").apply {
            setText(SmsPrefs.emergencyMessage(this@MainActivity))
        }
        em.addView(nameField, lp(8))
        em.addView(numField, lp(6))
        em.addView(msgField, lp(6))
        val panicSwitch = Switch(this).apply {
            text = "Panic mode: send without asking for yes"
            setTextColor(this@MainActivity.text)
            isChecked = SmsPrefs.panic(this@MainActivity)
        }
        em.addView(panicSwitch, lp(8))
        em.addView(button("Save emergency contact", primary = true) {
            val n = nameField.text.toString().trim()
            val num = numField.text.toString().trim()
            if (n.isEmpty() || num.count { it.isDigit() } < 3) {
                android.widget.Toast.makeText(this, "add a name and a phone number", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                SmsPrefs.saveEmergency(this, n, num, msgField.text.toString().trim(), panicSwitch.isChecked)
                android.widget.Toast.makeText(this, "emergency contact saved", android.widget.Toast.LENGTH_SHORT).show()
                showTab("home")
            }
        }.apply { layoutParams = lp(8) })
        em.addView(label(
            "Panic mode sends right away with no confirmation. Off: you hear the text and say yes first.",
            12f, muted
        ), lp(6))
        c.addView(em, lp())

        c.addView(label("Notifications", 13f, muted), lp(14))
        val notif = card()
        val granted = NotifyAccess.granted(this)
        notif.addView(label(
            "Notification access: " + if (granted) "on" else "off",
            14f, if (granted) emerald else muted
        ))
        notif.addView(button("Open notification access", primary = false) {
            if (!NotifyAccess.openSettings(this)) {
                android.widget.Toast.makeText(this, "can't open settings here", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = lp(8) })
        val master = Switch(this).apply {
            text = "Read notifications aloud"
            setTextColor(this@MainActivity.text)
            isChecked = NotifyPrefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> NotifyPrefs.setEnabled(this@MainActivity, checked) }
        }
        notif.addView(master, lp(8))
        notif.addView(button("Muted apps (${NotifyPrefs.muted(this).size})", primary = false) {
            showMuteList()
        }.apply { layoutParams = lp(6) })
        notif.addView(label(
            "Skipped during do not disturb, on silent or vibrate, and for muted apps. Off until you turn it on.",
            12f, muted
        ), lp(6))
        c.addView(notif, lp())

        c.addView(label("Wake word", 13f, muted), lp(14))
        val wake = card()
        val wakeSwitch = Switch(this).apply {
            text = "Wake word: say the phrase, then the command"
            setTextColor(this@MainActivity.text)
            isChecked = Store.wakeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Store.setWakeEnabled(this@MainActivity, checked)
                if (VoiceService.isRunning()) {
                    VoiceService.stop(this@MainActivity)
                    VoiceService.start(this@MainActivity)
                }
            }
        }
        wake.addView(wakeSwitch, lp(4))
        val phraseField = field("wake phrase").apply { setText(Store.wakePhrase(this@MainActivity)) }
        wake.addView(phraseField, lp(8))
        wake.addView(button("Save phrase", primary = false) {
            Store.setWakePhrase(this@MainActivity, phraseField.text.toString())
            Toast.makeText(this@MainActivity, "wake phrase saved", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = lp(6) })
        wake.addView(label(
            "Listens in short windows with rests, stops after 5 minutes of silence, and pauses on battery saver or under 15% battery. Matching on partial results is approximate. Battery use depends on your phone's speech service.",
            12f, muted
        ), lp(6))
        wake.addView(label("Wake mode", 13f, muted), lp(8))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeButton(mode: String, name: String, note: String) = button(
            if (Store.wakeMode(this@MainActivity) == mode) "$name (on)" else name,
            primary = Store.wakeMode(this@MainActivity) == mode
        ) {
            Store.setWakeMode(this@MainActivity, mode)
            Toast.makeText(this@MainActivity, "$name wake: $note", Toast.LENGTH_SHORT).show()
            if (VoiceService.isRunning()) { VoiceService.stop(this@MainActivity); VoiceService.start(this@MainActivity) }
            showTab("home")
        }
        modeRow.addView(modeButton("light", "Light", "4 s listens, 12 s rests, best battery"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(modeButton("full", "Full", "6 s listens, 2.5 s rests, catches more"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        wake.addView(modeRow, lp(4))
        wake.addView(label(
            "Light: fewer recognizer starts, so it uses less battery but can miss a fast phrase. Full: more responsive, uses more battery. Neither is a true always-on keyword model: both use the phone's speech service.",
            11f, muted
        ), lp(4))
        // Offline partial results are what the wake word needs. Only Android 12+ can report this.
        val offline = if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.isOnDeviceRecognitionAvailable(this) else null
        wake.addView(label(
            when (offline) {
                true -> "Offline speech: available on this phone."
                false -> "Warning: this phone's speech service does not offer offline recognition. The wake word may not work, or may need internet and use more battery."
                null -> "Offline speech: can't be checked on Android 11 and below."
            },
            12f, if (offline == false) red else muted
        ).apply { setPadding(0, dp(6), 0, 0) })
        c.addView(wake, lp())

        c.addView(label("Appearance", 13f, muted), lp(14))
        val look = card()
        val curTheme = Store.theme(this)
        look.addView(label("Theme", 14f, text))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("dark" to "Dark", "light" to "Light").forEach { (key, name) ->
            themeRow.addView(button(name, primary = curTheme == key) {
                Store.setTheme(this@MainActivity, key)
                recreate()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        look.addView(themeRow, lp(6))
        look.addView(label("Accent", 14f, text), lp(8))
        val curAccent = Store.accent(this)
        val accRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Theme.accents.keys.forEach { key ->
            accRow.addView(button(Theme.accentLabel(key), primary = curAccent == key) {
                Store.setAccent(this@MainActivity, key)
                recreate()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        look.addView(accRow, lp(6))
        look.addView(label("App icon (the launcher can take a few seconds to update)", 14f, text), lp(8))
        val curIcon = Store.icon(this)
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AppIcon.choices.keys.forEach { key ->
            iconRow.addView(button(AppIcon.label(key), primary = curIcon == key) {
                if (AppIcon.apply(this@MainActivity, key)) {
                    Toast.makeText(this@MainActivity, "icon set to ${AppIcon.label(key)}", Toast.LENGTH_SHORT).show()
                    showTab("home")
                } else {
                    Toast.makeText(this@MainActivity, "couldn't change the icon", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        look.addView(iconRow, lp(6))
        c.addView(look, lp())

        c.addView(label("Listening", 13f, muted), lp(14))
        val sw = Switch(this).apply {
            text = "Continuous listening"
            setTextColor(this@MainActivity.text)
            isChecked = Store.continuous(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.setContinuous(this@MainActivity, checked) }
        }
        c.addView(sw, lp())
        c.addView(label(
            "Tip: add the Flashlight and Screenshot tiles from your quick settings edit panel.",
            12f, muted
        ), lp(10))
    }

    private fun showMuteList() {
        val apps = AppIndex.all(this)
        val muted = NotifyPrefs.muted(this)
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].pkg in muted }
        android.app.AlertDialog.Builder(this)
            .setTitle("Mute notifications from")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                apps.forEachIndexed { i, app -> NotifyPrefs.setMuted(this, app.pkg, checked[i]) }
                showTab("home")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun permRow(name: String, perm: String): TextView {
        val ok = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        return label("$name: ${if (ok) "on" else "off"}", 14f, if (ok) emerald else muted).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun runCommand(text: String) {
        VoiceService.execute(this, text, "typed")
    }

    private fun startListeningFlow() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        VoiceService.start(this)
    }

    // ---------- Assistant (on-device chat) ----------

    private fun chatView(b: LinearLayout) {
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        b.addView(label("On-device assistant. Rules run on this phone. No cloud, no AI model, nothing sent out.", 12f, muted), lp())
        b.addView(spacer(6))
        b.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val msgs = Store.chat(this)
        if (msgs.isEmpty()) {
            list.addView(bubble("a", "hey. type help to see what i can do, or type apps to browse every app."), lp(4))
        }
        msgs.forEach { list.addView(bubble(it.role, it.text), lp(4)) }

        // Mode switch: Rules is instant and stores nothing. Smart uses the on-device model.
        val smart = Store.assistantMode(this) == "smart"
        val modeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeBar.addView(button(if (!smart) "Rules (on)" else "Rules", primary = !smart) {
            Store.setAssistantMode(this, "rules"); showTab("chat")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeBar.addView(button(if (smart) "Smart (on)" else "Smart", primary = smart) {
            Store.setAssistantMode(this, "smart"); showTab("chat")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) } })
        b.addView(modeBar, lp(4))
        val brainNote = when {
            Llm.blocker(this) == null -> "Smart mode: brain ready on this phone. free-form chat, streams replies, and runs commands."
            Llm.ready(this) -> "Smart mode: ${Llm.blocker(this)}, using rules."
            else -> "Smart mode needs the brain (about 105 MB, one time, download on this phone). Until then it uses rules."
        }
        b.addView(label(brainNote, 11f, muted), lp(2))
        if (brainActive || Llm.isDownloading()) {
            b.addView(brainProgressRow(), lp(2))
        } else if (!Llm.ready(this) && Llm.ramMb(this) >= Llm.MIN_RAM_MB) {
            b.addView(button("Download brain (105 MB)", primary = false) { confirmBrainDownload() }.apply { layoutParams = lp(2) })
        } else if (Llm.ready(this)) {
            b.addView(button("Delete brain", primary = false) { Llm.deleteModel(this); showTab("chat") }.apply { layoutParams = lp(2) })
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = field("message")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Mic", primary = false) { startSpeech() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(6) }
        })
        row.addView(button("Send") { sendChat(input.text.toString(), input) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(6) }
        })
        b.addView(spacer(6))
        b.addView(row)
        b.addView(label("chat history stays on this phone. clear it below.", 11f, muted), lp(4))
        b.addView(button("Clear chat", primary = false) {
            Store.clearChat(this)
            Store.setPending(this, null)
            showTab("chat")
        }.apply { layoutParams = lp(2) })

        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun confirmBrainDownload() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Download the brain?")
            .setMessage("About 105 MB, one time. It is saved on this phone and used for Smart mode. Chat stays on this phone, the download is the only network use, and you can delete it any time.")
            .setPositiveButton("Download") { _, _ -> startBrainDownload() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun brainProgressRow(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val total = Llm.MODEL_BYTES
        val status = label("downloading the brain: starting...", 12f, muted)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        box.addView(status, lp(2))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(button("Cancel download", primary = false) { Llm.cancelDownload(); status.text = "cancelling..." }.apply { layoutParams = lp(2) })
        brainLabel = status
        brainBar = bar
        return box
    }

    private fun startBrainDownload() {
        brainLastUi = 0L
        brainActive = true
        Toast.makeText(this, "downloading the brain, progress is on the Assistant tab", Toast.LENGTH_SHORT).show()
        showTab("chat")
        Thread({
            val result = Llm.download(this) { got, total ->
                // throttle UI updates to every 256 KB (and the final chunk)
                if (got - brainLastUi >= 256L * 1024 || got >= total) {
                    brainLastUi = got
                    runOnUiThread {
                        val pct = (got * 100 / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                        brainBar?.progress = pct
                        brainLabel?.text = "downloading the brain: $pct% (${got / 1_048_576} of ${total / 1_048_576} MB)"
                    }
                }
            }
            runOnUiThread {
                brainActive = false
                result.onSuccess { Toast.makeText(this, "brain ready", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this, it.message ?: "download failed", Toast.LENGTH_LONG).show() }
                if (tab == "chat") showTab("chat")
            }
        }, "brain-download").start()
    }

    private fun bubble(role: String, msg: String): LinearLayout {
        val mine = role == "u"
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
        }
        val bub = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(if (mine) bubbleUser else card)
                cornerRadius = dp(14).toFloat()
            }
        }
        bub.addView(label(msg, 15f, text))
        val bubParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.82f)
        val gap = LinearLayout.LayoutParams(0, 1, 0.18f)
        // user bubbles hug the right edge, assistant bubbles the left
        if (mine) {
            wrap.addView(View(this), gap)
            wrap.addView(bub, bubParams)
        } else {
            wrap.addView(bub, bubParams)
            wrap.addView(View(this), gap)
        }
        return wrap
    }

    private fun sendChat(raw: String, input: EditText) {
        val t = raw.trim()
        if (t.isEmpty()) return
        input.setText("")
        hideKeyboard(input)
        Store.addChat(this, "u", t)
        Assistant.respond(this, t) { reply ->
            Store.addChat(applicationContext, "a", reply.text)
            if (tab == "chat") showTab("chat")
        }
        showTab("chat")
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun startSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "say a command")
        }
        try {
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(this, "speech recognition isn't available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Usage (top 5 today, with time bars) ----------
    private fun usageView(b: LinearLayout) {
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(c)
        b.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        c.addView(label("Screen time today", 16f, text, true), lp(4))
        if (!UsageStatsReader.granted(this)) {
            val card = card()
            card.addView(label("Usage access is off. Android needs you to allow it once in settings, then this list fills in.", 14f, text))
            card.addView(button("Open usage access settings") {
                if (!UsageStatsReader.openSettings(this)) {
                    Toast.makeText(this, "couldn't open usage access settings", Toast.LENGTH_SHORT).show()
                }
            }.apply { layoutParams = lp(8) })
            c.addView(card, lp(6))
            return
        }
        val totals = UsageStatsReader.totals(this, yesterday = false)
        if (totals.isEmpty()) {
            c.addView(label("No screen time recorded yet today.", 14f, muted), lp(6))
            return
        }
        val labels = AppIndex.all(this).associateBy { it.pkg }
        val top = totals.entries.sortedByDescending { it.value }.take(5)
        val max = top.first().value.coerceAtLeast(1L)
        top.forEach { (pkg, ms) ->
            val card = card()
            card.addView(label(labels[pkg]?.label ?: pkg, 15f, text, true))
            card.addView(label(UsageStatsReader.formatDuration(ms), 13f, muted))
            val bar = View(this).apply { setBackgroundColor(emerald) }
            val frac = (ms.toDouble() / max).coerceIn(0.02, 1.0)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(8), frac.toFloat()))
            row.addView(View(this), LinearLayout.LayoutParams(0, dp(8), (1.0 - frac).toFloat()))
            card.addView(row, lp(4))
            c.addView(card, lp(6))
        }
        c.addView(label("Say \"how much time on whatsapp today\" or \"yesterday\" for one app.", 12f, muted), lp(8))
    }

    // ---------- Apps (browse + search, alphabetical) ----------

    private fun appsView(b: LinearLayout) {
        val all = AppIndex.all(this)
        b.addView(label("${all.size} launchable apps. type to search, tap to open.", 13f, muted), lp())
        val search = field("search apps, e.g. whats, maps, cal")
        search.setText(appQuery)
        search.inputType = InputType.TYPE_CLASS_TEXT
        b.addView(spacer(6))
        b.addView(search, lp())
        b.addView(spacer(6))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        b.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun render(q: String) {
            appQuery = q
            list.removeAllViews()
            val shown: List<AppEntry> = if (q.isBlank()) all else AppIndex.rank(all, q).map { it.first }
            if (shown.isEmpty()) {
                list.addView(label("no app matches \"$q\". try part of the name.", 14f, muted), lp(8))
                return
            }
            var lastLetter = ""
            shown.forEach { app ->
                if (q.isBlank()) {
                    val letter = AppIndex.letterOf(app.label)
                    if (letter != lastLetter) {
                        lastLetter = letter
                        list.addView(label(letter, 13f, emerald, true), lp(8))
                    }
                }
                list.addView(button(app.label, primary = false) {
                    val r = Actions.launchPackage(this, app)
                    Store.addHistory(this, "open ${app.label}", r.speech, r.ok)
                    if (!r.ok) Toast.makeText(this, r.speech, Toast.LENGTH_SHORT).show()
                }.apply { layoutParams = lp(3) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render(appQuery)
    }

    // ---------- History ----------

    private fun historyView(c: LinearLayout) {
        val items = Store.history(this)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(label("${items.size} recent", 13f, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Clear", primary = false) {
            Store.clearHistory(this)
            showTab("history")
        })
        c.addView(header, lp())

        val io = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        io.addView(button("Back up", primary = false) { exportBackup() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        io.addView(button("Restore", primary = false) { importBackup() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        c.addView(io, lp(8))
        c.addView(label("Backup saves aliases and history to one JSON file you choose. Restore merges it in.", 12f, muted), lp(4))

        if (items.isEmpty()) {
            c.addView(label("No commands yet. Try one from Home.", 14f, muted), lp(12))
            return
        }
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        items.forEach { h ->
            val row = card()
            row.addView(label("\"${h.text}\"", 15f, text, true))
            row.addView(label(h.result, 13f, if (h.ok) emerald else red))
            row.addView(label(fmt.format(Date(h.time)), 11f, muted))
            c.addView(row, lp(6))
        }
    }

    private fun exportBackup() {
        pendingBackupJson = Backup.export(this)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mercureal-voice-backup.json")
        }
        startActivityForResult(i, REQ_SAVE)
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_OPEN)
    }

    // ---------- Aliases ----------

    private fun aliasView(c: LinearLayout) {
        c.addView(label("Custom aliases map a phrase to a command, e.g. \"mom\" -> \"call 555 0100\" or \"music\" -> \"open spotify\".", 13f, muted), lp())
        val phrase = field("phrase you say")
        val command = field("command, e.g. open spotify")
        c.addView(phrase, lp(6))
        c.addView(command, lp(4))
        c.addView(button("Save alias") {
            val p = phrase.text.toString().trim()
            val cmd = command.text.toString().trim()
            if (p.isNotEmpty() && cmd.isNotEmpty()) {
                Store.setAlias(this, p, cmd)
                showTab("aliases")
            }
        }, lp(6))
        Store.aliases(this).forEach { (p, cmd) ->
            val row = card()
            row.addView(label("\"$p\"  →  $cmd", 14f, text))
            row.addView(button("Remove", primary = false) {
                Store.removeAlias(this, p)
                showTab("aliases")
            }.apply { layoutParams = lp(4) })
            c.addView(row, lp(6))
        }
    }

    // ---------- Tour (multi-page onboarding) ----------

    private val tourPages = listOf(
        "What it does" to "Speak or type commands to open any app, call contacts, set timers and alarms, toggle the flashlight, take screenshots, read the time or battery, and change volume.",
        "Assistant tab" to "A chat that runs on this phone. Type or use the mic. It uses the same commands as voice. Calls, texts, alarms and timers ask you yes first. Type help for the list.",
        "Apps tab" to "Every app with a launcher icon, A to Z. Search by part of the name. Typos and initials work too: \"whats\" finds WhatsApp.",
        "Permissions" to "Microphone: hear commands. Contacts: find people by name. Phone: place calls. Camera: flashlight only, no photos. Overlay: floating mic. Screenshots ask you once per session and save to Pictures/Screenshots.",
        "Privacy" to "Aliases, history, chat and settings stay on this phone. Nothing is uploaded. Backups are a file you choose to save. Tip: install an offline speech pack so voice works with no connection."
    )

    private fun showTour() {
        val b = root()
        val last = tourPage >= tourPages.size - 1
        val (title, pageText) = tourPages[tourPage]
        b.addView(label("Tour ${tourPage + 1} of ${tourPages.size}", 12f, muted))
        b.addView(spacer(6))
        b.addView(label(title, 22f, text, true))
        b.addView(spacer(12))
        val page = card()
        page.addView(label(pageText, 15f, text))
        b.addView(page, lp())
        b.addView(spacer(16))
        if (last) {
            b.addView(button("Grant microphone and start") {
                Store.setOnboarded(this)
                startListeningFlow()
                showTab("home")
            }, lp())
            b.addView(spacer(8))
            b.addView(button("Finish, no listening yet", primary = false) {
                Store.setOnboarded(this)
                showTab("home")
            }, lp())
        } else {
            b.addView(button("Next") { tourPage++; showTour() }, lp())
            b.addView(spacer(8))
            b.addView(button("Skip", primary = false) {
                Store.setOnboarded(this)
                showTab("home")
            }, lp())
        }
        if (tourPage > 0) {
            b.addView(spacer(8))
            b.addView(button("Back", primary = false) { tourPage--; showTour() }, lp())
        }
    }

    // ---------- results ----------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SPEECH -> {
                if (resultCode == RESULT_OK) {
                    val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!said.isNullOrBlank()) {
                        Store.addChat(this, "u", said)
                        Assistant.respond(this, said) { reply ->
                            Store.addChat(applicationContext, "a", reply.text)
                            if (tab == "chat") showTab("chat")
                        }
                    }
                }
                showTab("chat")
            }
            REQ_SAVE -> {
                val uri = data?.data
                val json = pendingBackupJson
                pendingBackupJson = null
                if (resultCode == RESULT_OK && uri != null && json != null) {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        Toast.makeText(this, "backup saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "couldn't save the backup: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            REQ_OPEN -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    try {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalStateException("file is empty")
                        val (aliases, history) = Backup.import(this, text)
                        Toast.makeText(this, "restored $aliases aliases, $history history items", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    showTab("history")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC) {
            // Re-open the mic from the chat tab if the user just granted it.
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeech()
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow()
            }
            showTab(tab)
        }
    }

    companion object {
        private const val REQ_PERMS = 7001
        private const val REQ_MIC = 7002
        private const val REQ_SPEECH = 7003
        private const val REQ_SAVE = 7004
        private const val REQ_OPEN = 7005
    }
}
