package com.mercureal.voice

import android.content.Context
import android.content.Intent

/** One app that has a launcher icon. */
data class AppEntry(val label: String, val pkg: String)

/**
 * Every launchable app, plus fuzzy matching for "open <anything>".
 * Tiers, best first: exact, prefix, word prefix, contains, initials, phrase contains name, typo (edit distance).
 */
object AppIndex {
    /** All launcher apps, alphabetical by label. Needs the <queries> entry in the manifest on Android 11+. */
    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName) }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.pkg }
            .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.pkg })
    }

    fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removePrefix("the ")
        .removeSuffix(" app")
        .trim()

    /** Apps matching [query], best first. Only score > 0 is returned. */
    fun rank(apps: List<AppEntry>, query: String): List<Pair<AppEntry, Int>> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qc = q.replace(" ", "")
        return apps.mapNotNull { app ->
            val l = normalize(app.label)
            val score = scoreOf(q, qc, l, l.replace(" ", ""))
            if (score > 0) app to score else null
        }.sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.lowercase() })
    }

    /** Letter used for the alphabetical browse headers. Non-letters group under #. */
    fun letterOf(label: String): String {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private fun scoreOf(q: String, qc: String, l: String, c: String): Int {
        if (l.isEmpty()) return 0
        if (l == q || c == qc) return 100
        if (q.length >= 2 && l.startsWith(q)) return 92
        if (q.length >= 2 && Regex("(^| )" + Regex.escape(q)).containsMatchIn(l)) return 88
        if (q.length >= 3 && l.contains(q)) return 82
        if (q.length >= 3 && c.contains(qc)) return 78
        if (qc.length >= 2 && qc == initials(l)) return 80
        // spoken phrase that contains the whole app name: "open google maps please"
        if (l.length >= 3 && (" $q ").contains(" $l ")) return 70
        if (c.length >= 3 && qc.length >= 3) {
            val d = editDistance(qc, c)
            val maxLen = maxOf(qc.length, c.length)
            if (d <= 2 && d * 4 <= maxLen) return 65 - d * 5
        }
        return 0
    }

    private fun initials(label: String): String =
        label.split(" ").filter { it.isNotEmpty() }.joinToString("") { it.substring(0, 1) }

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
