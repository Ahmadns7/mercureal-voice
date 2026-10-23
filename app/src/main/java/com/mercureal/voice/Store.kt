package com.mercureal.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All app state lives in SharedPreferences on this device. Nothing is uploaded. */
object Store {
    private const val FILE = "mercureal_prefs"
    private const val KEY_HISTORY = "history"
    private const val KEY_ALIASES = "aliases"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_CONTINUOUS = "continuous"
    private const val MAX_HISTORY = 100

    data class HistoryItem(val time: Long, val text: String, val result: String, val ok: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    fun continuous(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONTINUOUS, true)

    fun setContinuous(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CONTINUOUS, value).apply()
    }

    @Synchronized
    fun history(ctx: Context): List<HistoryItem> {
        val raw = prefs(ctx).getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(o.getLong("t"), o.getString("text"), o.getString("result"), o.getBoolean("ok"))
        }
    }

    @Synchronized
    fun addHistory(ctx: Context, text: String, result: String, ok: Boolean) {
        val arr = JSONArray()
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("text", text).put("result", result).put("ok", ok))
        val existing = JSONArray(prefs(ctx).getString(KEY_HISTORY, "[]") ?: "[]")
        for (i in 0 until minOf(existing.length(), MAX_HISTORY - 1)) arr.put(existing.get(i))
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    @Synchronized
    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().putString(KEY_HISTORY, "[]").apply()
    }

    @Synchronized
    fun aliases(ctx: Context): Map<String, String> {
        val obj = JSONObject(prefs(ctx).getString(KEY_ALIASES, "{}") ?: "{}")
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    @Synchronized
    fun setAlias(ctx: Context, phrase: String, command: String) {
        val obj = JSONObject(prefs(ctx).getString(KEY_ALIASES, "{}") ?: "{}")
        obj.put(phrase.lowercase().trim(), command.trim())
        prefs(ctx).edit().putString(KEY_ALIASES, obj.toString()).apply()
    }

    @Synchronized
    fun removeAlias(ctx: Context, phrase: String) {
        val obj = JSONObject(prefs(ctx).getString(KEY_ALIASES, "{}") ?: "{}")
        obj.remove(phrase)
        prefs(ctx).edit().putString(KEY_ALIASES, obj.toString()).apply()
    }
}
