package dev.applesideload.device.remote

import dev.applesideload.device.Transport
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One direction of an in-memory byte stream, with the read semantics of a socket. */
internal class Pipe {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var closed = false

    fun write(from: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            if (closed) throw IOException("the pipe is closed")
            if (length > 0) chunks.addLast(from.copyOfRange(offset, offset + length))
            changed.signalAll()
        }
    }

    fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length == 0) return 0
        lock.withLock {
            val deadline = System.currentTimeMillis() + if (timeoutMs > 0) timeoutMs.toLong() else Int.MAX_VALUE.toLong()
            while (chunks.isEmpty()) {
                if (closed) return -1
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw SocketTimeoutException("pipe read timed out after $timeoutMs ms")
                changed.await(left, TimeUnit.MILLISECONDS)
            }
            var copied = 0
            while (copied < length && chunks.isNotEmpty()) {
                val head = chunks.first()
                val n = minOf(length - copied, head.size - headOffset)
                System.arraycopy(head, headOffset, into, offset + copied, n)
                copied += n
                headOffset += n
                if (headOffset == head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            return copied
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
    }
}

/** Two ends of an in-memory connection, standing in for a socket in tests. */
internal class PipeTransport(
    private val incoming: Pipe,
    private val outgoing: Pipe,
    override val description: String
) : Transport {
    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        incoming.read(into, offset, length, timeoutMs)

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) =
        outgoing.write(from, offset, length)

    override fun close() {
        incoming.close()
        outgoing.close()
    }

    companion object {
        fun pair(): Pair<PipeTransport, PipeTransport> {
            val forward = Pipe()
            val backward = Pipe()
            return PipeTransport(backward, forward, "pipe A") to PipeTransport(forward, backward, "pipe B")
        }
    }
}

internal fun hex(text: String): ByteArray {
    val clean = text.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Runs [block] on its own thread and hands back what it returned or threw. */
internal class Background<T>(name: String, block: () -> T) {
    @Volatile private var result: Result<T>? = null
    private val thread = Thread({ result = runCatching(block) }, name).apply {
        isDaemon = true
        start()
    }

    fun await(timeoutMs: Long = 20_000): T {
        thread.join(timeoutMs)
        val done = result ?: throw AssertionError("${thread.name} did not finish within $timeoutMs ms")
        return done.getOrThrow()
    }
}

/**
 * A blocking stream pair as a [Transport], for the far side of a test
 * connection: reads ignore the timeout and wait, the way the simulated
 * iPhone's threads want them to.
 */
internal class StreamTransport(
    private val input: java.io.InputStream,
    private val output: java.io.OutputStream,
    override val description: String,
    private val onClose: () -> Unit
) : Transport {
    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        if (length == 0) 0 else input.read(into, offset, length)

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        output.write(from, offset, length)
        output.flush()
    }

    override fun close() = onClose()
}
