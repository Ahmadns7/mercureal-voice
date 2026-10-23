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
    private const val KEY_CHAT = "chat"
    private const val KEY_PENDING = "chat_pending"
    private const val MAX_HISTORY = 100
    private const val MAX_CHAT = 200

    data class HistoryItem(val time: Long, val text: String, val result: String, val ok: Boolean)
    data class ChatMsg(val role: String, val text: String, val time: Long)

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

    /** Merges restored history into the current list (dedupe by time+text), newest first. Returns items added. */
    @Synchronized
    fun mergeHistory(ctx: Context, incoming: List<HistoryItem>): Int {
        val current = history(ctx)
        val seen = current.map { it.time to it.text }.toMutableSet()
        val added = incoming.filter { seen.add(it.time to it.text) }
        val merged = (current + added).sortedByDescending { it.time }.take(MAX_HISTORY)
        val arr = JSONArray()
        merged.forEach {
            arr.put(JSONObject().put("t", it.time).put("text", it.text).put("result", it.result).put("ok", it.ok))
        }
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
        return added.size
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

    // ---- assistant chat (on-device) ----

    @Synchronized
    fun chat(ctx: Context): List<ChatMsg> {
        val arr = JSONArray(prefs(ctx).getString(KEY_CHAT, "[]") ?: "[]")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatMsg(o.getString("r"), o.getString("x"), o.getLong("t"))
        }
    }

    @Synchronized
    fun addChat(ctx: Context, role: String, text: String) {
        val arr = JSONArray(prefs(ctx).getString(KEY_CHAT, "[]") ?: "[]")
        arr.put(JSONObject().put("r", role).put("x", text).put("t", System.currentTimeMillis()))
        while (arr.length() > MAX_CHAT) arr.remove(0)
        prefs(ctx).edit().putString(KEY_CHAT, arr.toString()).apply()
    }

    @Synchronized
    fun clearChat(ctx: Context) {
        prefs(ctx).edit().putString(KEY_CHAT, "[]").apply()
    }

    /** A command waiting for a yes/no from the user (calls, texts, alarms, timers). */
    fun pending(ctx: Context): String? = prefs(ctx).getString(KEY_PENDING, null)

    fun setPending(ctx: Context, raw: String?) {
        val e = prefs(ctx).edit()
        if (raw == null) e.remove(KEY_PENDING) else e.putString(KEY_PENDING, raw)
        e.apply()
    }
}
