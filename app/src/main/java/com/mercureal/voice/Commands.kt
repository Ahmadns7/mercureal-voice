package com.mercureal.voice

/** A parsed voice command. Each variant maps to one device action in [Actions]. */
sealed class Command {
    object Screenshot : Command()
    data class OpenApp(val query: String) : Command()
    data class Call(val target: String) : Command()
    data class Dial(val number: String) : Command()
    data class Sms(val recipient: String, val body: String) : Command()
    /** on = true/false, or null to toggle. */
    data class Flashlight(val on: Boolean?) : Command()
    data class Timer(val seconds: Int) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    object Time : Command()
    object Battery : Command()
    data class SettingsPanel(val panel: String) : Command()
    /** op: "up", "down", "mute", "unmute", "set" (level 0..100). */
    data class Volume(val op: String, val level: Int? = null) : Command()
    data class Unknown(val text: String) : Command()
    /** Runs a saved routine by name, in order, speaking between steps. */
    data class RunRoutine(val name: String) : Command()
    /** "remind me to X at 6pm": parsed by [Reminders.parse] at run time. */
    data class Remind(val raw: String) : Command()
}

object CommandParser {
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60
    )

    /**
     * Parse spoken or typed text. A user alias (phrase -> command) is applied first,
     * then the built-in grammar. depth stops alias loops.
     */
    fun parse(raw: String, aliases: Map<String, String> = emptyMap(), depth: Int = 0): Command {
        val text = raw.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
        if (text.isEmpty()) return Command.Unknown(raw)
        if (depth == 0) {
            aliases[text]?.let { return parse(it, emptyMap(), 1) }
        }

        if (text.startsWith("remind me") || text.startsWith("reminder to")) return Command.Remind(raw)

        // sms: "text mom saying running late", "send sms to 0801... that i'm here"
        Regex("^(?:send )?(?:an? )?(?:sms|text|message)(?: to)? (.+?) (?:saying|that says|with message|message|that) (.+)$")
            .matchEntire(text)?.let { return Command.Sms(it.groupValues[1], it.groupValues[2]) }
        Regex("^(?:send )?(?:an? )?(?:sms|text|message)(?: to)? (.+)$")
            .matchEntire(text)?.let { return Command.Sms(it.groupValues[1], "") }

        if (Regex("\\b(screen ?shot|screen capture|capture (?:the )?screen)\\b").containsMatchIn(text)) {
            return Command.Screenshot
        }

        Regex("^(call|ring|phone) (.+)$").matchEntire(text)?.let {
            return Command.Call(it.groupValues[2])
        }
        Regex("^dial (.+)$").matchEntire(text)?.let {
            val target = it.groupValues[1]
            val digits = target.filter { c -> c.isDigit() || c == '+' }
            return if (digits.count { c -> c.isDigit() } >= 3) Command.Dial(digits) else Command.Call(target)
        }

        if (text.contains("timer")) {
            Regex("(\\d+|[a-z]+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(text.replace("timer", ""))
                ?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1]]
                    if (n != null && n > 0) {
                        val unit = m.groupValues[2]
                        val secs = when (unit.first()) {
                            's' -> n
                            'm' -> n * 60
                            else -> n * 3600
                        }
                        return Command.Timer(secs)
                    }
                }
        }

        Regex("alarm(?: (?:for|at))? (\\d{1,2})(?::(\\d{2}))?(?: ?(am|pm))?").find(text)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return Command.Alarm(h, min)
        }

        if (text in setOf("time", "what time is it", "what's the time", "whats the time", "current time", "tell me the time")) {
            return Command.Time
        }
        if (text.contains("battery")) return Command.Battery

        if (text.contains("volume") || text.contains("louder") || text.contains("quieter") ||
            text == "mute" || text == "unmute"
        ) {
            Regex("volume (?:to )?(\\d{1,3})").find(text)?.let {
                return Command.Volume("set", it.groupValues[1].toInt().coerceIn(0, 100))
            }
            return when {
                text.contains("unmute") -> Command.Volume("unmute")
                text.contains("mute") -> Command.Volume("mute")
                text.contains("up") || text.contains("louder") || text.contains("increase") -> Command.Volume("up")
                text.contains("down") || text.contains("quieter") || text.contains("lower") ||
                    text.contains("decrease") -> Command.Volume("down")
                else -> Command.Unknown(raw)
            }
        }

        if (Regex("\\b(flash ?light|torch)\\b").containsMatchIn(text)) {
            val state = when {
                Regex("\\boff\\b").containsMatchIn(text) -> false
                Regex("\\bon\\b").containsMatchIn(text) -> true
                else -> null
            }
            return Command.Flashlight(state)
        }

        if (Regex("\\b(wi-?fi|wireless)\\b").containsMatchIn(text)) return Command.SettingsPanel("wifi")
        if (text.contains("bluetooth")) return Command.SettingsPanel("bluetooth")

        Regex("^(open|launch|start|run|go to) (.+)$").matchEntire(text)?.let {
            return Command.OpenApp(it.groupValues[2])
        }

        return Command.Unknown(raw)
    }
}
