package com.mercureal.voice

// Pure Kotlin unit tests for the Naija command matcher (1.9.0). No device, no Android, no JUnit.
// Run by scripts/build-raw.sh (the test gate): compiled with Commands.kt and NaijaPhrases.kt, then main() runs.

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passed++ else failures += "$name: expected <$expected> got <$actual>"
}

private fun cmd(text: String, lang: String = NaijaPhrases.AUTO): Command =
    CommandParser.parse(text, emptyMap(), 0, lang)

fun main() {
    // ---- normalization ----
    check("norm accents", NaijaPhrases.normalize("Bá mi pè Ada"), "ba mi pe ada")
    check("norm dotted", NaijaPhrases.normalize("ṣí WhatsApp"), "si whatsapp")
    check("norm hausa hooks", NaijaPhrases.normalize("Buɗe Whatsapp  ƙarfe"), "bude whatsapp karfe")

    // ---- English passes through unchanged ----
    check("en open", NaijaPhrases.toEnglish("open whatsapp"), "open whatsapp")
    check("en call", cmd("call ada"), Command.Call("ada"))
    check("en battery", cmd("battery"), Command.Battery)
    check("en time", cmd("what time is it"), Command.Time)
    check("en flash on", cmd("flashlight on"), Command.Flashlight(true))
    check("en remind", cmd("remind me to call mum at 6pm") is Command.Remind, true)
    check("en sms", cmd("text mom saying running late"), Command.Sms("mom", "running late"))
    check("en alias still wins", CommandParser.parse("gym", mapOf("gym" to "call coach"), 0, NaijaPhrases.AUTO), Command.Call("coach"))

    // ---- Nigerian Pidgin ----
    check("pcm open whatsapp", cmd("open whatsapp"), Command.OpenApp("whatsapp"))
    check("pcm make i open", cmd("make i open whatsapp"), Command.OpenApp("whatsapp"))
    check("pcm abeg open", cmd("abeg open youtube"), Command.OpenApp("youtube"))
    check("pcm call ada", cmd("call ada"), Command.Call("ada"))
    check("pcm call for me", cmd("ring ada for me"), Command.Call("ada"))
    check("pcm make i call", cmd("make i call mama"), Command.Call("mama"))
    check("pcm text say", cmd("make i text ada say i dey come"), Command.Sms("ada", "i dey come"))
    check("pcm send message say", cmd("send message to ada say i dey come"), Command.Sms("ada", "i dey come"))
    check("pcm how battery dey", cmd("how battery dey"), Command.Battery)
    check("pcm how charge dey now", cmd("how the charge dey now"), Command.Battery)
    check("pcm wetin battery", cmd("wetin be the battery"), Command.Battery)
    check("pcm wetin time be", cmd("wetin time be"), Command.Time)
    check("pcm time dey", cmd("time dey"), Command.Time)
    check("pcm shine light", cmd("shine light"), Command.Flashlight(true))
    check("pcm shine torch", cmd("shine torch"), Command.Flashlight(true))
    check("pcm off torch", cmd("off the torch"), Command.Flashlight(false))
    check("pcm remind say", cmd("remind me say take drug by 6pm") is Command.Remind, true)
    check("pcm make i remind", cmd("make i remind me to call mum") is Command.Remind, true)

    // ---- Yoruba (tone marks removed, accented spelling also works) ----
    check("yo open si", cmd("ṣí whatsapp"), Command.OpenApp("whatsapp"))
    check("yo ba mi pe", cmd("bá mi pè ada"), Command.Call("ada"))
    check("yo pe", cmd("pè ada"), Command.Call("ada"))
    check("yo text", cmd("fi ránṣẹ́ sí ada pé mo ti de").let { it is Command.Sms && it.recipient == "ada" }, true)
    check("yo battery", cmd("batiri mi ti ku melo"), Command.Battery)
    check("yo bawo ni batiri", cmd("bawo ni batiri mi se ri"), Command.Battery)
    check("yo time aago", cmd("aago melo ni"), Command.Time)
    check("yo time bayi", cmd("ago melo ni bayi"), Command.Time)
    check("yo light on", cmd("tan ina"), Command.Flashlight(true))
    check("yo light off", cmd("pa ina"), Command.Flashlight(false))
    check("yo remind", cmd("ran mi leti pe ki n mu oogun") is Command.Remind, true)

    // ---- Hausa (ɗ ƙ ɓ mapped to d k b) ----
    check("ha open bude", cmd("buɗe whatsapp"), Command.OpenApp("whatsapp"))
    check("ha call kira", cmd("kira ada"), Command.Call("ada"))
    check("ha a kira", cmd("a kira ada"), Command.Call("ada"))
    check("ha text aika", cmd("aika saƙo ga ada cewa ina zuwa").let { it is Command.Sms && it.recipient == "ada" && it.body == "ina zuwa" }, true)
    check("ha battery batirin", cmd("batirin waya ya rage nawa"), Command.Battery)
    check("ha battery nawa", cmd("nawa ne batirin"), Command.Battery)
    check("ha time lokaci", cmd("wane lokaci ne yanzu"), Command.Time)
    check("ha time karfe", cmd("karfe nawa ne"), Command.Time)
    check("ha light kunna", cmd("kunna fitila"), Command.Flashlight(true))
    check("ha light kashe", cmd("kashe fitila"), Command.Flashlight(false))
    check("ha remind", cmd("tunatar da ni cewa in sha magani") is Command.Remind, true)

    // ---- language restriction ----
    check("only yo ignores pidgin", cmd("batirin waya", NaijaPhrases.YORUBA) is Command.Unknown, true)
    check("only pcm handles pidgin", cmd("how battery dey", NaijaPhrases.PIDGIN), Command.Battery)
    check("only en ignores pidgin", cmd("shine light", NaijaPhrases.ENGLISH) is Command.Unknown, true)
    check("only ha handles hausa", cmd("kunna fitila", NaijaPhrases.HAUSA), Command.Flashlight(true))
    check("only en keeps english", cmd("battery", NaijaPhrases.ENGLISH), Command.Battery)

    // ---- no false positives on ordinary English ----
    check("sing not si", NaijaPhrases.toEnglish("sing a song"), "sing a song")
    check("sit not si", NaijaPhrases.toEnglish("sit down"), "sit down")
    check("timer stays timer", cmd("set a timer for 5 minutes") is Command.Timer, true)
    check("unknown stays unknown", cmd("tell me a joke") is Command.Unknown, true)
    check("pepper not pe", cmd("pepper soup recipe") is Command.Unknown, true)

    // ---- table size (asserted so the notes stay honest) ----
    val counts = NaijaPhrases.countByLanguage()
    check("pcm rules", counts[NaijaPhrases.PIDGIN], 20)
    check("yo rules", counts[NaijaPhrases.YORUBA], 11)
    check("ha rules", counts[NaijaPhrases.HAUSA], 12)

    println("NaijaPhrasesTest: ${counts.values.sum()} rules, $passed checks passed, ${failures.size} failed")
    failures.forEach { println("FAIL $it") }
    if (failures.isNotEmpty()) throw AssertionError("${failures.size} naija test(s) failed")
}
