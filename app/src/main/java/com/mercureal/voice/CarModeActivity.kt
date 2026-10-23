package com.mercureal.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.speech.RecognizerIntent
import android.content.pm.PackageManager

/**
 * Car mode: full-screen, high contrast, voice-first. Four huge buttons for listen,
 * read notifications, next track, and home. Announces incoming notifications while on.
 * Entered by "car mode" (voice or the quick action), exited by "exit car mode" or the Exit button.
 */
class CarModeActivity : Activity() {
    private lateinit var status: TextView
    private val speaker by lazy { Speaker.instance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        active = true
        instance = this
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        status = TextView(this).apply {
            text = "car mode on. say a command, or tap a button."
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(row(bigButton("LISTEN", Color.parseColor("#10B981"), Color.BLACK) { listen() }, bigButton("NOTIFS", Color.parseColor("#0EA5E9"), Color.BLACK) { readNotifications() }))
        grid.addView(row(bigButton("NEXT", Color.parseColor("#F59E0B"), Color.BLACK) { media("next") }, bigButton("HOME", Color.WHITE, Color.BLACK) { navigateHome() }))
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(bigButton("EXIT CAR MODE", Color.parseColor("#1F2937"), Color.WHITE) { exit() }
            .apply { textSize = 20f }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(88)).apply { topMargin = dp(12) })
        setContentView(root)
        speaker.say("car mode on")
    }

    override fun onDestroy() {
        active = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
    }.let { r ->
        // Rows share the grid height equally.
        r.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        r
    }

    private fun bigButton(label: String, bg: Int, fg: Int, onTap: () -> Unit) = Button(this).apply {
        text = label
        textSize = 34f
        isAllCaps = false
        minHeight = dp(120)
        setTextColor(fg)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
        setOnClickListener { onTap() }
    }

    private fun say(text: String) {
        status.text = text
        speaker.say(text)
    }

    /** Opens the app's listener for a voice command. */
    private fun listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("mic permission is needed. open mercureal once to allow it.")
            return
        }
        VoiceService.start(this)
        say("listening")
    }

    private fun readNotifications() {
        val list = CarMode.pendingNotifications()
        if (list.isEmpty()) say("no new notifications") else {
            say("reading ${list.size} notifications")
            list.forEach { speaker.say(it) }
        }
    }

    private fun media(op: String) {
        val r = Actions.run(this, Command.Media(op))
        say(r.speech)
    }

    private fun navigateHome() {
        val home = Store.homeAddress(this)
        if (home.isBlank()) {
            say("no home address saved. set one in the app first")
            return
        }
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(home) + "&mode=d")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
            say("navigating home")
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(home))))
            say("navigating home")
        }
    }

    private fun exit() {
        active = false
        speaker.say("car mode off") { finish() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile var active = false
        private var instance: CarModeActivity? = null

        fun enter(ctx: Context) {
            ctx.startActivity(Intent(ctx, CarModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Closes car mode from voice ("exit car mode"). */
        fun exitNow() {
            instance?.runOnUiThread { instance?.exit() }
        }
    }
}

/** Voice-only helpers shared by car mode and the parser. */
object CarMode {
    /** Notifications waiting to be read in car mode, newest first. */
    fun pendingNotifications(): List<String> = NotifyQueue.drain()
}
