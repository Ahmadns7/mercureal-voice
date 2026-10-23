package com.mercureal.voice

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color

/**
 * Colour palette for the whole app. Theme is light or dark, accent is one of three.
 * Every screen reads from here so the accent setting applies everywhere.
 */
object Theme {
    data class Palette(
        val bg: Int,
        val card: Int,
        val bubbleUser: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val red: Int,
    )

    /** Accent choices: key -> colour. Emerald is the original and the default. */
    val accents: LinkedHashMap<String, Int> = linkedMapOf(
        "emerald" to Color.parseColor("#10B981"),
        "sky" to Color.parseColor("#0EA5E9"),
        "amber" to Color.parseColor("#F59E0B"),
    )

    fun accentLabel(key: String) = when (key) {
        "sky" -> "Sky"
        "amber" -> "Amber"
        else -> "Emerald"
    }

    fun palette(ctx: Context): Palette {
        val light = Store.theme(ctx) == "light"
        val accent = accents[Store.accent(ctx)] ?: accents.getValue("emerald")
        return if (light) {
            Palette(
                bg = Color.parseColor("#F4F7F6"),
                card = Color.parseColor("#FFFFFF"),
                bubbleUser = Color.parseColor("#D1FAE5"),
                text = Color.parseColor("#0B1F1A"),
                muted = Color.parseColor("#5B7A70"),
                accent = accent,
                onAccent = Color.parseColor("#0B1F1A"),
                red = Color.parseColor("#DC2626"),
            )
        } else {
            Palette(
                bg = Color.parseColor("#0B0F0E"),
                card = Color.parseColor("#151C1A"),
                bubbleUser = Color.parseColor("#065F46"),
                text = Color.parseColor("#E8F5F0"),
                muted = Color.parseColor("#7C9A90"),
                accent = accent,
                onAccent = Color.BLACK,
                red = Color.parseColor("#F87171"),
            )
        }
    }
}

/**
 * Alternate launcher icons via activity-alias. Only one alias is enabled at a time.
 * The default icon is the launcher alias ".LauncherDefault"; MainActivity has no launcher filter itself.
 */
object AppIcon {
    /** Key -> alias class name, label shown in settings. */
    val choices: LinkedHashMap<String, String> = linkedMapOf(
        "default" to "LauncherDefault",
        "wave" to "LauncherWave",
        "bolt" to "LauncherBolt",
        "mono" to "LauncherMono",
    )

    fun label(key: String) = when (key) {
        "wave" -> "Wave"
        "bolt" -> "Bolt"
        "mono" -> "Mono"
        else -> "Default (mic)"
    }

    /**
     * Switches the launcher icon. Returns false if the switch could not be written.
     * The launcher may take a few seconds (and sometimes a relaunch) to show the change.
     */
    fun apply(ctx: Context, key: String): Boolean {
        val target = choices[key] ?: return false
        val pm = ctx.packageManager
        return try {
            choices.forEach { (k, alias) ->
                val cn = ComponentName(ctx.packageName, "${ctx.packageName}.$alias")
                val state = if (k == key) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            }
            Store.setIcon(ctx, key)
            target.isNotEmpty()
        } catch (e: Throwable) {
            android.util.Log.w("MercuReal", "icon switch failed", e)
            false
        }
    }
}
