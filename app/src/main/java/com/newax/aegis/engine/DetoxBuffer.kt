package com.newax.aegis.engine

object DetoxBuffer {
    private const val MAX_SIZE = 100
    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val seen = LinkedHashSet<String>()

    fun addNotification(appName: String, content: String) {
        val entry = "App: $appName | Content: $content"
        synchronized(lock) {
            if (seen.contains(entry)) return
            if (buffer.size >= MAX_SIZE) {
                val oldest = buffer.removeFirst()
                seen.remove(oldest)
            }
            buffer.addLast(entry)
            seen.add(entry)
        }
    }

    fun dumpAndClear(): String = synchronized(lock) {
        val result = buffer.joinToString("\n")
        buffer.clear()
        seen.clear()
        result
    }

    fun hasNotifications(): Boolean = synchronized(lock) { buffer.isNotEmpty() }
    fun size(): Int = synchronized(lock) { buffer.size }
}
