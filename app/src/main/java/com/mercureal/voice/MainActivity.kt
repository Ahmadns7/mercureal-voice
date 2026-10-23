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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
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
    private val bg = Color.parseColor("#0B0F0E")
    private val card = Color.parseColor("#151C1A")
    private val bubbleUser = Color.parseColor("#065F46")
    private val text = Color.parseColor("#E8F5F0")
    private val muted = Color.parseColor("#7C9A90")
    private val emerald = Color.parseColor("#10B981")
    private val red = Color.parseColor("#F87171")
    // Not created in a field initializer: views need a Context, and fields run before the
    // Activity is attached (v1.0.0 crashed on open because of this).
    private lateinit var body: LinearLayout
    private var tab = "home"
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
        } catch (e: Throwable) {
            CrashReport.report(this, "screen start", e)
            showFallback(e)
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
        listOf("home" to "Home", "chat" to "Assistant", "apps" to "Apps", "history" to "History", "aliases" to "Aliases")
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
        perms.addView(label(
            "Overlay (floating mic): " + if (Settings.canDrawOverlays(this)) "on" else "off",
            14f, text
        ))
        perms.addView(button("Grant screenshot access", primary = false) {
            ProjectionRequestActivity.launch(this)
        }.apply { layoutParams = lp(8) })
        perms.addView(button("Show tour and privacy", primary = false) {
            tourPage = 0
            showTour()
        }.apply { layoutParams = lp(6) })
        c.addView(perms, lp())

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
