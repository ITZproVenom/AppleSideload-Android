package dev.applesideload.device

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bytes that arrived before anyone asked for them, handed out as a stream.
 *
 * Both ends of the USB path need this. The endpoint delivers whole transfers
 * while a protocol asks for four bytes of length and then a body, and a mux
 * connection receives whole TCP segments while its reader wants a stream.
 *
 * Nothing is ever dropped: the queue has no capacity limit, because a lost
 * chunk in the middle of a stream is unrecoverable and a full queue is
 * prevented one level up, by the TCP window, rather than here.
 */
internal class ChunkQueue(private val what: () -> String) {

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var head: ByteArray? = null
    private var headAt = 0
    private var finished = false
    private var failure: IOException? = null

    private var queued = 0L

    /** Bytes waiting to be read. */
    val queuedBytes: Long get() = lock.withLock { queued }

    fun put(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        lock.withLock {
            if (finished) return
            chunks.addLast(chunk)
            queued += chunk.size
            arrived.signalAll()
        }
    }

    /** No more data will arrive; what is already queued can still be read. */
    fun finish() {
        lock.withLock {
            finished = true
            arrived.signalAll()
        }
    }

    /**
     * No more data will arrive because something broke. Queued data is still
     * handed out first, then every read reports [error].
     */
    fun fail(error: IOException) {
        lock.withLock {
            if (!finished) {
                failure = error
                finished = true
            }
            arrived.signalAll()
        }
    }

    /**
     * Copies up to [length] bytes, waiting up to [timeoutMs] for the first.
     *
     * Returns -1 once the stream has finished and everything queued has been
     * read. A timeout throws [SocketTimeoutException] and loses nothing: the
     * next read carries on exactly where this one would have. A timeout of
     * zero or less waits indefinitely, as a socket does.
     */
    fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length == 0) return 0
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (head == null) {
                val next = chunks.removeFirstOrNull()
                if (next != null) {
                    head = next
                    headAt = 0
                    break
                }
                if (finished) {
                    failure?.let { throw IOException(it.message, it) }
                    return -1
                }
                if (timeoutMs <= 0) {
                    arrived.await()
                } else {
                    if (remaining <= 0L) {
                        throw SocketTimeoutException(
                            "nothing arrived within ${timeoutMs}ms on ${what()}"
                        )
                    }
                    remaining = arrived.awaitNanos(remaining)
                }
            }
            val current = head!!
            val count = minOf(length, current.size - headAt)
            System.arraycopy(current, headAt, into, offset, count)
            headAt += count
            queued -= count
            if (headAt == current.size) {
                head = null
                headAt = 0
            }
            return count
        }
    }
}
