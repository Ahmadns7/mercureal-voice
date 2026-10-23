package com.mercureal.voice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used only to read the active window. It does not act on the screen,
 * does not log what it reads, and does nothing until the user enables it in Accessibility settings.
 */
class ScreenReaderService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are not used. We read the tree on demand when the user asks.
    }

    override fun onInterrupt() {
        ScreenReader.stopSilently()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: ScreenReaderService? = null
    }
}

/**
 * "read screen" walks the active window's AccessibilityNodeInfo tree, keeps visible text and
 * content descriptions, splits them into pages of about 1200 characters at sentence or word
 * boundaries, and speaks one page at a time. The next page starts from the TTS completion
 * callback. "stop reading" bumps a session counter, so callbacks from an old run do nothing.
 */
object ScreenReader {
    const val PAGE_CHARS = 1200
    const val MAX_PAGES = 30
    private const val MAX_DEPTH = 60

    @Volatile private var session = 0
    @Volatile private var active = false

    fun enabled(): Boolean = ScreenReaderService.instance != null

    fun isActive(): Boolean = active

    fun openSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Reads the active window. Returns right away; speech continues on the TTS callbacks. */
    fun start(ctx: Context): Actions.Result {
        val svc = ScreenReaderService.instance
            ?: return Actions.Result(false, "turn on mercureal screen reader in accessibility settings first")
        val root = svc.rootInActiveWindow
            ?: return Actions.Result(false, "i can't read this screen. the app may block it, or there is no window")
        val lines = ArrayList<String>()
        try {
            collect(root, lines, 0)
        } finally {
            recycle(root)
        }
        if (lines.isEmpty()) return Actions.Result(false, "there's no text on this screen to read")

        val all = paginate(lines.joinToString(". ") { it.trimEnd('.', '!', '?', ':', ';') })
        val pages = all.take(MAX_PAGES)
        val truncated = all.size > pages.size

        val sid = ++session
        active = true
        val speaker = Speaker.instance(ctx)
        readPage(ctx, speaker, pages, 0, sid, truncated)
        val note = if (truncated) ", first $MAX_PAGES pages only" else ""
        return Actions.Result(true, "reading ${pages.size} page${if (pages.size == 1) "" else "s"}$note")
    }

    /** "stop reading": cuts speech and forgets the rest of the pages. */
    fun stop(ctx: Context): Actions.Result {
        val was = active
        stopSilently()
        Speaker.instance(ctx).stop()
        return Actions.Result(true, if (was) "stopped reading" else "nothing is being read")
    }

    fun stopSilently() {
        session++
        active = false
    }

    private fun readPage(ctx: Context, speaker: Speaker, pages: List<String>, i: Int, sid: Int, truncated: Boolean) {
        if (sid != session) return
        if (i >= pages.size) {
            active = false
            Store.addHistory(ctx, "read screen", "read ${pages.size} pages${if (truncated) " (cut short)" else ""}", true)
            return
        }
        val label = if (pages.size > 1) "page ${i + 1} of ${pages.size}. " else ""
        speaker.say(label + pages[i]) {
            readPage(ctx, speaker, pages, i + 1, sid, truncated)
        }
    }

    /** Visible text and content descriptions, depth-first, skipping repeats of the line above. */
    fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return
        val raw = node.text ?: node.contentDescription
        val t = raw?.toString()?.replace(Regex("\\s+"), " ")?.trim()
        if (!t.isNullOrEmpty() && (out.isEmpty() || out.last() != t)) out.add(t)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                collect(child, out, depth + 1)
            } finally {
                recycle(child)
            }
        }
    }

    /**
     * Splits text into pages of at most [size] characters, preferring a sentence break, then a
     * word break, so no sentence is cut in the middle when it can be avoided.
     */
    fun paginate(text: String, size: Int = PAGE_CHARS): List<String> {
        val pages = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.length <= size) {
                pages.add(rest)
                break
            }
            var cut = rest.lastIndexOf(". ", size)
            if (cut < size / 2) cut = rest.lastIndexOf(' ', size)
            if (cut <= 0) cut = size
            pages.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        return pages
    }

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo?) {
        // recycle() is deprecated from API 33 and does nothing there; older releases need it.
        if (Build.VERSION.SDK_INT < 33) n?.recycle()
    }
}
