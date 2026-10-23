package com.mercureal.voice

import android.content.Context

/**
 * On-device chat. Plain rules, no network, no model. Uses the same parser and Actions as voice.
 * Calls, texts, dials, alarms and timers wait for a yes before they run.
 */
object Assistant {
    class Reply(val text: String)

    const val HELP = "on-device assistant. rules run on this phone, nothing leaves it.\n\n" +
        "open any app: open whatsapp, open calc, open google maps. close names and typos work. type apps to list them all.\n" +
        "call mom, call 0801..., dial 0801... (asks yes first)\n" +
        "text mom saying running late (opens the composer, you tap send; asks yes first)\n" +
        "screenshot\n" +
        "flashlight on, flashlight off, flashlight\n" +
        "timer 5 minutes, alarm 7am (asks yes first)\n" +
        "battery, what time is it\n" +
        "volume up, volume down, mute, volume 40\n" +
        "wifi settings, bluetooth settings\n" +
        "your aliases work here too."

    private val yes = setOf("yes", "y", "yep", "yeah", "yup", "do it", "confirm", "go ahead", "sure", "ok", "okay")
    private val no = setOf("no", "n", "nope", "cancel", "never mind", "nevermind", "stop")
    private val greet = setOf("hi", "hello", "hey", "yo", "sup", "hi there", "hey there", "good morning", "good afternoon", "good evening")
    private val helpWords = setOf("help", "what can you do", "what can u do", "commands", "what can i say", "what do you do", "menu")
    private val appWords = setOf("apps", "list apps", "show apps", "all apps", "my apps")

    /** Answers one message. [done] runs once, on the main thread (screenshots reply later). */
    fun respond(ctx: Context, raw: String, done: (Reply) -> Unit) {
        val app = ctx.applicationContext
        val t = AppIndex.normalize(raw)

        val waiting = Store.pending(app)
        if (waiting != null) {
            Store.setPending(app, null)
            if (t in yes) { runNow(app, waiting, done); return }
            if (t in no) { done(Reply("cancelled")); return }
            // anything else drops the pending action and is handled as a new message
        }

        when {
            t.isEmpty() -> done(Reply("say something, or type help"))
            t in yes -> done(Reply("nothing to confirm right now"))
            t in no -> done(Reply("nothing to cancel"))
            t in helpWords -> done(Reply(HELP))
            t in greet -> done(Reply("hey. type help to see what i can do"))
            t == "thanks" || t == "thank you" || t == "thx" || t == "ty" -> done(Reply("anytime"))
            t == "who are you" || t == "what are you" ->
                done(Reply("mercureal assistant. rules that run on this phone, no cloud, no ai model."))
            t == "how are you" -> done(Reply("running fine. what do you need"))
            t in appWords -> {
                val n = AppIndex.all(app).size
                done(Reply("$n apps can open from here. open the Apps tab to browse them alphabetically, or type open then a name."))
            }
            else -> {
                val cmd = CommandParser.parse(raw, Store.aliases(app))
                when (cmd) {
                    is Command.Unknown -> {
                        // a bare app name that matches exactly opens that app
                        val top = AppIndex.rank(AppIndex.all(app), raw).firstOrNull()
                        if (top != null && top.second == 100) runNow(app, "open ${top.first.label}", done)
                        else done(Reply("i didn't get that. type help to see what i can do"))
                    }
                    is Command.Call, is Command.Dial, is Command.Sms, is Command.Alarm, is Command.Timer -> {
                        Store.setPending(app, raw)
                        done(Reply(confirmText(cmd) + ". reply yes to go ahead or no to cancel"))
                    }
                    else -> runNow(app, raw, done)
                }
            }
        }
    }

    private fun confirmText(cmd: Command): String = when (cmd) {
        is Command.Call -> "call ${cmd.target}"
        is Command.Dial -> "dial ${cmd.number}"
        is Command.Sms -> "open a text to ${cmd.recipient}" +
            (if (cmd.body.isNotBlank()) " saying \"${cmd.body}\"" else "") + " (you tap send)"
        is Command.Alarm -> "set an alarm for " + "%02d:%02d".format(cmd.hour, cmd.minute)
        is Command.Timer -> "start a timer for ${cmd.seconds} seconds"
        else -> "run that"
    }

    private fun runNow(app: Context, raw: String, done: (Reply) -> Unit) {
        val cmd = CommandParser.parse(raw, Store.aliases(app))
        if (cmd is Command.Screenshot) {
            ScreenshotCapture.capture(app) { ok, msg ->
                Store.addHistory(app, raw, msg, ok)
                if (!ok && !ScreenshotCapture.hasGrant()) {
                    ProjectionRequestActivity.launch(app)
                    done(Reply("$msg. approve the screen capture prompt, then ask again"))
                } else {
                    done(Reply(msg))
                }
            }
            return
        }
        val r = Actions.run(app, cmd)
        Store.addHistory(app, raw, r.speech, r.ok)
        done(Reply(r.speech))
    }
}
