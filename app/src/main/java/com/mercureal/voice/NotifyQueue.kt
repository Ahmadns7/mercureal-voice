package com.mercureal.voice

/** Holds notification announcements that car mode should read aloud. Cleared when read. */
object NotifyQueue {
    private val items = ArrayDeque<String>()

    @Synchronized fun add(text: String) {
        items.addLast(text)
        while (items.size > 20) items.removeFirst()
    }

    @Synchronized fun drain(): List<String> {
        val out = items.toList().reversed()
        items.clear()
        return out
    }
}
