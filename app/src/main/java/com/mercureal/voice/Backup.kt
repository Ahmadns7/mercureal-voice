package com.mercureal.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Aliases and command history as one JSON file. The user picks the file location (no storage permission). */
object Backup {
    private const val FORMAT = "mercureal-voice-backup"

    fun export(ctx: Context): String {
        val aliases = JSONObject()
        Store.aliases(ctx).forEach { (k, v) -> aliases.put(k, v) }
        val hist = JSONArray()
        Store.history(ctx).forEach {
            hist.put(JSONObject().put("t", it.time).put("text", it.text).put("result", it.result).put("ok", it.ok))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("aliases", aliases)
            .put("history", hist)
            .toString(2)
    }

    /** Returns (aliases restored, history items added). Throws if the file is not a backup. */
    fun import(ctx: Context, text: String): Pair<Int, Int> {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "not a MercuReal Voice backup" }
        val al = o.getJSONObject("aliases")
        var aliasCount = 0
        for (k in al.keys()) {
            Store.setAlias(ctx, k, al.getString(k))
            aliasCount++
        }
        val h = o.getJSONArray("history")
        val items = (0 until h.length()).map { i ->
            val x = h.getJSONObject(i)
            Store.HistoryItem(x.getLong("t"), x.getString("text"), x.getString("result"), x.getBoolean("ok"))
        }
        return aliasCount to Store.mergeHistory(ctx, items)
    }
}
