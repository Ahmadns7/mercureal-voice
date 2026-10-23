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
        "play, pause, next, previous (work without access; sends the media key)\n" +
        "what's playing (needs notification access)\n" +
        "read screen, stop reading (needs the screen reader on in accessibility settings)\n" +
        "how much time on whatsapp today, screen time yesterday (needs usage access)\n" +
        "your aliases work here too."

    private val yes = setOf("yes", "y", "yep", "yeah", "yup", "do it", "confirm", "go ahead", "sure", "ok", "okay")
    private val no = setOf("no", "n", "nope", "cancel", "never mind", "nevermind", "stop")
    private val greet = setOf("hi", "hello", "hey", "yo", "sup", "hi there", "hey there", "good morning", "good afternoon", "good evening")
    private val helpWords = setOf("help", "what can you do", "what can u do", "commands", "what can i say", "what do you do", "menu")
    private val appWords = setOf("apps", "list apps", "show apps", "all apps", "my apps")

    /**
     * Answers one message. Smart mode uses the on-device model when it is downloaded and
     * usable; otherwise (or when a yes/no is pending) it answers with rules, with a notice.
     * [done] runs once, on the main thread.
     */
    fun respond(ctx: Context, raw: String, done: (Reply) -> Unit) {
        val app = ctx.applicationContext
        val waiting = Store.pending(app)
        if (Store.assistantMode(app) != "smart" || waiting != null) {
            rulesRespond(app, raw, done)
            return
        }
        val why = Llm.blocker(app)
        if (why != null) {
            rulesRespond(app, raw) { r -> done(Reply("smart mode is off: $why. answered with rules.\n\n${r.text}")) }
            return
        }
        smartRespond(app, raw, done)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Streams a model reply on a worker thread, then runs any action it asks for. */
    private fun smartRespond(app: Context, raw: String, done: (Reply) -> Unit) {
        val history = Store.chat(app).takeLast(8)
        val roles = ArrayList<String>().apply { add("system"); addAll(history.map { if (it.role == "u") "user" else "assistant" }) }
        val contents = ArrayList<String>().apply { add(SYSTEM_PROMPT); addAll(history.map { it.text }) }
        if (history.lastOrNull()?.text != raw) { roles.add("user"); contents.add(raw) }
        Thread({
            if (!Llm.ensureLoaded(app)) {
                main.post { rulesRespond(app, raw) { r -> done(Reply("smart mode couldn't load the model, answered with rules.\n\n${r.text}")) } }
                return@Thread
            }
            val full = Llm.generate(roles, contents) { _ -> }
            main.post {
                if (full == null) {
                    done(Reply("the brain is busy or failed. try again in a moment"))
                    return@post
                }
                val act = parseAction(full)
                if (act == null) {
                    done(Reply(full.trim().ifBlank { "i didn't get that. type help to see what i can do" }))
                } else {
                    val say = act.first.ifBlank { "on it" }
                    rulesRespond(app, act.second) { r -> done(Reply("$say\n${r.text}")) }
                }
            }
        }, "mercureal-llm").start()
    }

    /** Pulls {"say": "...", "run": "..."} out of a model reply. Null when there is no action. */
    internal fun parseAction(reply: String): Pair<String, String>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = org.json.JSONObject(reply.substring(start, end + 1))
            val run = o.optString("run", "").trim()
            if (run.isEmpty()) null else Pair(o.optString("say", "").trim(), run)
        } catch (e: Exception) {
            null
        }
    }

    internal const val SYSTEM_PROMPT = "You are the on-device assistant of the Mercureal Voice app. " +
        "Answer briefly and plainly, in lowercase. When the user wants the phone to do something, reply " +
        "with ONLY one JSON object: {\"say\": \"short confirmation\", \"run\": \"command\"}. The command must be one " +
        "of these phrases: open <app>, call <name or number>, text <name> saying <message>, timer <n> minutes, " +
        "alarm <time>, flashlight on, flashlight off, volume up, volume down, mute, battery, what time is it, " +
        "screenshot, play, pause, next, previous, wifi settings, bluetooth settings, help. " +
        "Calls, texts, timers and alarms ask the user to confirm first. Otherwise reply in plain text."

    /** The rules engine. Also the executor Smart mode's actions run through. */
    fun rulesRespond(ctx: Context, raw: String, done: (Reply) -> Unit) {
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
