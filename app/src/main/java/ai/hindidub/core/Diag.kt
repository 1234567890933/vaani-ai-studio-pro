package ai.hindidub.core

/**
 * In-memory engine trace.
 *
 * Two reasons this exists:
 *  1. `android.util.Log` cannot be used from the pure-logic classes, and those classes are
 *     exactly the ones worth unit-testing on a plain JVM. A tiny dependency-free recorder
 *     keeps them testable *and* gives the app something better than logcat: the Engine panel
 *     can show the last few hundred engine events, which is what you actually want when a
 *     job fails on someone else's phone.
 *  2. A ring buffer is bounded by construction, so tracing can never grow memory.
 */
object Diag {

    data class Event(val atMs: Long, val stage: String, val message: String)

    private const val MAX_EVENTS = 400
    private val ring = ArrayDeque<Event>(MAX_EVENTS)
    private val lock = Any()
    private var clock: () -> Long = { System.currentTimeMillis() }

    fun record(stage: String, message: String) {
        synchronized(lock) {
            ring.addLast(Event(clock(), stage, message))
            while (ring.size > MAX_EVENTS) ring.removeFirst()
        }
    }

    fun snapshot(): List<Event> = synchronized(lock) { ring.toList() }

    fun dump(limit: Int = 60): String = snapshot().takeLast(limit)
        .joinToString("\n") { "${it.atMs % 100000} [${it.stage}] ${it.message}" }

    fun clear() = synchronized(lock) { ring.clear() }

    /** Tests inject a deterministic clock. */
    fun setClockForTest(block: () -> Long) {
        clock = block
    }
}
