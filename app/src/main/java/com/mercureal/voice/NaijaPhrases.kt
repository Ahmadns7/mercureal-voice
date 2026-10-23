package com.mercureal.voice

import java.text.Normalizer

/**
 * Naija command phrases (1.9.0): Nigerian Pidgin, Yoruba and Hausa phrasings for the core commands
 * (open, call, text, battery, time, flashlight, reminder).
 *
 * How it works
 * - Recognized text is normalized first: lowercase, accents and tone marks stripped (ṣí -> si, bá -> ba),
 *   and ɗ ƙ ɓ mapped to d k b (buɗe -> bude, ƙarfe -> karfe). So the table is written in plain ASCII.
 * - The first rule whose pattern matches the WHOLE normalized phrase rewrites it to the English command
 *   (for example "how battery dey" -> "battery"). CommandParser then runs its normal English grammar.
 * - English always works. Phrases no rule matches pass through unchanged.
 * - Setting: "auto" (default) tries every language. "en", "pcm", "yo" or "ha" restrict rules to one language.
 *
 * Honesty
 * - This matches recognized TEXT. Whether the phone's speech service turns accented Pidgin, Yoruba or Hausa
 *   speech into the right words is up to that service. The matcher cannot fix a wrong transcript.
 * - Replies (speech out) stay English in 1.9.0.
 *
 * Adding a phrase: add one rule line below, then add a case to NaijaPhrasesTest.kt. {1}, {2} are capture groups.
 */
object NaijaPhrases {

    /** Language keys stored in settings. */
    const val AUTO = "auto"
    const val ENGLISH = "en"
    const val PIDGIN = "pcm"
    const val YORUBA = "yo"
    const val HAUSA = "ha"

    val languages: List<Pair<String, String>> = listOf(
        AUTO to "Auto",
        ENGLISH to "English",
        PIDGIN to "Pidgin",
        YORUBA to "Yoruba",
        HAUSA to "Hausa",
    )

    data class Rule(val lang: String, val pattern: Regex, val english: String)

    private fun r(lang: String, pattern: String, english: String) = Rule(lang, Regex(pattern), english)

    /**
     * Rule table, tried in order. Patterns match the normalized phrase in full (matchEntire).
     * Yoruba/Hausa examples use the ASCII forms; the accented spelling normalizes to the same thing.
     */
    val rules: List<Rule> = listOf(
        // ---- Nigerian Pidgin (pcm) ----
        // open: "make i open whatsapp", "abeg open whatsapp", "open whatsapp abeg"
        r(PIDGIN, "make (?:i|me) open (.+)", "open {1}"),
        r(PIDGIN, "abeg (?:open|launch|start) (.+)", "open {1}"),
        r(PIDGIN, "open (.+?) (?:abeg|for me|now|sharp)", "open {1}"),
        // call: "make i call ada", "call ada for me", "abeg ring ada"
        r(PIDGIN, "make (?:i|me) (?:call|ring|phone) (.+)", "call {1}"),
        r(PIDGIN, "(?:call|ring) (.+?) for me", "call {1}"),
        r(PIDGIN, "abeg (?:call|ring) (.+)", "call {1}"),
        // text: "make i text ada say i dey come", "text ada say running late", "send message to ada say ..."
        r(PIDGIN, "make (?:i|me) (?:text|message|send (?:message|sms|text) to) (.+?) (?:say|saying) (.+)", "text {1} saying {2}"),
        r(PIDGIN, "(?:text|message|sms) (.+?) (?:say|make i say|say make i say) (.+)", "text {1} saying {2}"),
        r(PIDGIN, "send (?:message|sms|text) to (.+?) (?:say|saying) (.+)", "text {1} saying {2}"),
        // battery: "how battery dey", "how the charge dey now", "wetin be the battery", "battery don reach"
        r(PIDGIN, "how (?:the )?(?:battery|charge|power) (?:dey|don reach|dey now|be)(?: now| na)?", "battery"),
        r(PIDGIN, "(?:wetin|wetin be|how much) (?:is )?(?:the )?(?:battery|charge|power)(?: (?:dey|be|level))?(?: now)?", "battery"),
        r(PIDGIN, "(?:battery|charge|power) (?:dey|don reach|level|remain|left)(?: now| how)?", "battery"),
        // time: "wetin time be", "time dey", "the time now"
        r(PIDGIN, "wetin (?:be )?(?:the )?time(?: (?:be|dey|now))?", "time"),
        r(PIDGIN, "(?:time|the time) (?:now|dey|be)", "time"),
        // flashlight: "shine light", "shine torch", "make e on the torch", "off the torch"
        r(PIDGIN, "shine (?:the )?(?:light|torch|lamp)", "flashlight on"),
        r(PIDGIN, "(?:make e |switch |put )?on (?:the )?(?:torch|light|lamp)", "flashlight on"),
        r(PIDGIN, "(?:switch |put )?off (?:the )?(?:torch|light|lamp)", "flashlight off"),
        // reminder: "remind me say take drug by 6", "make i remind me to call mum", "reminder say ..."
        r(PIDGIN, "remind me say (.+)", "remind me to {1}"),
        r(PIDGIN, "make (?:i|me) remind (?:me )?(?:say |to )?(.+)", "remind me to {1}"),
        r(PIDGIN, "reminder say (.+)", "reminder to {1}"),

        // ---- Yoruba (yo), tone marks removed ----
        // open: "ṣí whatsapp" / "sii whatsapp"
        r(YORUBA, "si (.+)", "open {1}"),
        r(YORUBA, "sii (.+)", "open {1}"),
        // call: "bá mi pè ada" (call ada for me), "pè ada"
        r(YORUBA, "(?:ba mi )?pe (.+)", "call {1}"),
        // reminder (must come before the text rule: both start with "ran"): "ran mi leti pe ki n mu oogun" (remind me that ...)
        r(YORUBA, "ran mi leti(?: pe| lati)? (.+)", "remind me to {1}"),
        // text: "fi ránṣẹ́ sí ada pé ..." / "ran ojise si ada pe ..."
        r(YORUBA, "(?:fi )?ran(?:se)? (?:ojise |ifiranse |oro )?(?:si )?(.+?) (?:pe|wipe) (.+)", "text {1} saying {2}"),
        // battery: "batiri mi ti ku melo", "bateri", "bawo ni batiri mi se ri"
        r(YORUBA, "(?:batiri|bateri)(?: [a-z]+)*", "battery"),
        r(YORUBA, "bawo ni (?:batiri|bateri)(?: mi)?(?: .*)?", "battery"),
        // time: "aago melo ni", "akoko ti ni bayi", "wakati", "kini aago"
        r(YORUBA, "(?:kini )?(?:aago|akoko|wakati)(?: [a-z]+)*", "time"),
        r(YORUBA, "ago melo ni(?: bayi)?", "time"),
        // flashlight: "tan ina" (switch on light), "pa ina" (switch off light)
        r(YORUBA, "tan (?:ina|imole|filasi|fitila)(?: mi)?", "flashlight on"),
        r(YORUBA, "pa (?:ina|imole|filasi|fitila)(?: mi)?", "flashlight off"),

        // ---- Hausa (ha), ɗ ƙ ɓ mapped to d k b ----
        // open: "buɗe whatsapp"
        r(HAUSA, "bude (.+)", "open {1}"),
        // call: "kira ada", "a kira ada"
        r(HAUSA, "(?:a |don )?kira (.+)", "call {1}"),
        // text: "aika saƙo ga ada cewa ..." / "aika ga ada cewa ..."
        r(HAUSA, "aika (?:sako|sakon) (?:ga |zuwa )?(.+?) (?:cewa|da cewa|mai cewa) (.+)", "text {1} saying {2}"),
        r(HAUSA, "aika (?:ga |zuwa )?(.+?) (?:cewa|da cewa) (.+)", "text {1} saying {2}"),
        // battery: "batirin waya ya rage nawa", "baturi", "nawa ne batirin"
        r(HAUSA, "(?:batirin|baturi|batir|baturin)(?: .*)?", "battery"),
        r(HAUSA, "(?:nawa|yaya) (?:ne )?(?:batirin|baturi|batir|baturin)(?: .*)?", "battery"),
        // time: "wane lokaci ne yanzu", "lokaci nawa", "karfe nawa ne"
        r(HAUSA, "(?:yanzu )?(?:wane|me) lokaci ne(?: yanzu)?", "time"),
        r(HAUSA, "lokaci(?: nawa| yanzu)?", "time"),
        r(HAUSA, "(?:yanzu )?karfe nawa(?: ne)?(?: yanzu)?", "time"),
        // flashlight: "kunna fitila" (switch on torch), "kashe fitila" (switch off torch)
        r(HAUSA, "kunna (?:fitila|fitilar|walkiya|haske)(?: [a-z]+)?", "flashlight on"),
        r(HAUSA, "kashe (?:fitila|fitilar|walkiya|haske)(?: [a-z]+)?", "flashlight off"),
        // reminder: "tunatar da ni cewa ..." (remind me that ...)
        r(HAUSA, "tunatar (?:da )?ni (?:cewa |game da |zuwa )?(.+)", "remind me to {1}"),
    )

    /** Normalizes recognized text: lowercase, accents and tone marks removed, ɗ ƙ ɓ to d k b, spaces collapsed. */
    fun normalize(text: String): String {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return stripped.replace('ɗ', 'd').replace('ƙ', 'k').replace('ɓ', 'b')
            .replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Rewrites a Naija (or English) phrase into the English command text the grammar understands.
     * [lang] = "auto" tries every language; a single key restricts rules to that language.
     * Returns the normalized input when no rule matches.
     */
    fun toEnglish(text: String, lang: String = AUTO): String {
        val norm = normalize(text)
        for (rule in rules) {
            if (lang != AUTO && rule.lang != lang) continue
            val m = rule.pattern.matchEntire(norm) ?: continue
            var out = rule.english
            for (i in 1 until m.groupValues.size) out = out.replace("{$i}", m.groupValues[i].trim())
            return out.replace(Regex("\\s+"), " ").trim()
        }
        return norm
    }

    /** Phrase-rule count per language key (for notes and tests). */
    fun countByLanguage(): Map<String, Int> = rules.groupingBy { it.lang }.eachCount()
}
