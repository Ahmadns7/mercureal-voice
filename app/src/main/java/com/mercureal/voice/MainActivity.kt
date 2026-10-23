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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-activity UI built from framework views (no AndroidX, so the raw build route works).
 * Dark surface, emerald accent. Tabs: Home (quick actions + command box), History, Aliases.
 */
class MainActivity : Activity() {
    private val bg = Color.parseColor("#0B0F0E")
    private val card = Color.parseColor("#151C1A")
    private val text = Color.parseColor("#E8F5F0")
    private val muted = Color.parseColor("#7C9A90")
    private val emerald = Color.parseColor("#10B981")
    private val body = LinearLayout(this)
    private var tab = "home"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!Store.isOnboarded(this)) showOnboarding() else showTab("home")
    }

    override fun onResume() {
        super.onResume()
        if (Store.isOnboarded(this)) showTab(tab)
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

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun lp(margin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(margin); bottomMargin = dp(margin) }

    private fun navBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        listOf("home" to "Home", "history" to "History", "aliases" to "Aliases").forEach { (key, name) ->
            addView(TextView(this@MainActivity).apply {
                setText(name)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
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
        val input = EditText(this).apply {
            hint = "e.g. screenshot, call mom, flashlight on, timer 5 minutes"
            setHintTextColor(muted)
            setTextColor(this@MainActivity.text)
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
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
        perms.addView(button("Show privacy and setup", primary = false) {
            showOnboarding()
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
    }

    private fun permRow(name: String, perm: String): TextView {
        val ok = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        return label("$name: ${if (ok) "on" else "off"}", 14f, if (ok) emerald else muted).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun runCommand(text: String) {
        VoiceService.execute(this, text, "typed")
        // Refresh history view so the new entry shows up.
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow()
            }
            showTab(tab)
        }
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
        if (items.isEmpty()) {
            c.addView(label("No commands yet. Try one from Home.", 14f, muted), lp(12))
            return
        }
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        items.forEach { h ->
            val row = card()
            row.addView(label("\"${h.text}\"", 15f, text, true))
            row.addView(label(h.result, 13f, if (h.ok) emerald else Color.parseColor("#F87171")))
            row.addView(label(fmt.format(Date(h.time)), 11f, muted))
            c.addView(row, lp(6))
        }
    }

    // ---------- Aliases ----------

    private fun aliasView(c: LinearLayout) {
        c.addView(label("Custom aliases map a phrase to a command, e.g. \"mom\" -> \"call 555 0100\" or \"music\" -> \"open spotify\".", 13f, muted), lp())
        val phrase = EditText(this).apply { hint = "phrase you say"; setHintTextColor(muted); setTextColor(this@MainActivity.text) }
        val command = EditText(this).apply { hint = "command, e.g. open spotify"; setHintTextColor(muted); setTextColor(this@MainActivity.text) }
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

    // ---------- Onboarding ----------

    private fun showOnboarding() {
        val b = root()
        b.addView(label("Welcome to MercuReal Voice", 22f, text, true))
        b.addView(spacer(12))
        val page = card()
        page.addView(label("What it does", 14f, emerald, true))
        page.addView(label("Speak or type commands to open apps, call contacts, set timers and alarms, toggle the flashlight, take screenshots, read the time or battery, and adjust volume. Speech runs on-device when your offline language pack is installed.", 14f, text))
        page.addView(spacer(12))
        page.addView(label("Permissions", 14f, emerald, true))
        page.addView(label("Microphone: to hear commands.\nContacts: to find people by name for calls and texts.\nPhone: to place calls.\nCamera: for the flashlight (no photos).\nOverlay: the floating mic button.\nScreenshots: Android asks you once per session before capture. Screenshots save to Pictures/Screenshots.", 14f, text))
        page.addView(spacer(12))
        page.addView(label("Privacy", 14f, emerald, true))
        page.addView(label("Aliases, history and settings stay on this phone. Nothing is uploaded by this app. Tip: install your offline speech pack in Android settings so voice works without a connection.", 14f, text))
        b.addView(page, lp())
        b.addView(spacer(16))
        b.addView(button("Grant microphone and start") {
            Store.setOnboarded(this)
            startListeningFlow()
            showTab("home")
        }, lp())
        b.addView(spacer(8))
        b.addView(button("Skip for now", primary = false) {
            Store.setOnboarded(this)
            showTab("home")
        }, lp())
    }

    companion object {
        private const val REQ_PERMS = 7001
    }
}
