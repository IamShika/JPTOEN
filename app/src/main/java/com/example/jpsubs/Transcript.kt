package com.example.jpsubs

/**
 * A tiny notebook where the audio service writes recognized sentences and the
 * screen reads them. Different threads use it at the same time, so every
 * function is marked @Synchronized (only one thread at a time).
 */
object Transcript {

    /** One sentence. `english` is null if it could not be translated (or is just a note). */
    data class Line(val japanese: String, val english: String?)

    private const val MAX_LINES = 60

    private val lines = ArrayDeque<Line>()

    /** Goes up by one every time the text changes, so the screen knows when to redraw. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun add(japanese: String, english: String? = null) {
        lines.addLast(Line(japanese, english))
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

    /** A copy of the lines, newest first, so you never have to scroll to see the latest one. */
    @Synchronized
    fun snapshot(): List<Line> = lines.asReversed().toList()

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()
}
