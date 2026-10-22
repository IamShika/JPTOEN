package com.example.jpsubs

/**
 * A tiny notebook where the audio service writes recognized sentences and the
 * screen reads them. Different threads use it at the same time, so every
 * function is marked @Synchronized (only one thread at a time).
 */
object Transcript {

    private const val MAX_LINES = 60

    private val lines = ArrayDeque<String>()

    /** Goes up by one every time the text changes, so the screen knows when to redraw. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun add(text: String) {
        lines.addLast(text)
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
        }
        version++
    }

    @Synchronized
    fun clear() {
        lines.clear()
        version++
    }

    /** Newest sentence first, so you never have to scroll to see the latest one. */
    @Synchronized
    fun asText(): String = lines.asReversed().joinToString("\n\n")

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()
}
