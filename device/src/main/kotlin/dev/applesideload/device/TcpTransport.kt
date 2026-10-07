package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The Wi-Fi side of the connection.
 *
 * A device that has been paired over USB publishes its services over the
 * network, and lockdown answers on the same port it does over USB. Nothing
 * above this class needs to know which one it is talking to.
 */
class TcpTransport private constructor(private val socket: Socket) : Transport {

    override val description: String
        get() = "TCP ${socket.inetAddress?.hostAddress}:${socket.port}"

    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs
        return socket.getInputStream().read(into, offset, length)
    }

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        val out = socket.getOutputStream()
        out.write(from, offset, length)
        out.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val LOCKDOWN_PORT = 62078

        /** What a failed connect means for an iPhone, with the system's words kept at the end. */
        private fun explain(error: IOException, host: String, port: Int, timeoutMs: Int): String {
            val raw = Log.describe(error)
            val message = error.message.orEmpty()
            return when {
                error is SocketTimeoutException ->
                    "nothing answered at $host within ${timeoutMs / 1000} seconds ($raw)"

                error is NoRouteToHostException || "EHOSTUNREACH" in message ->
                    "nothing answered at $host; an iPhone that is asleep or has left the " +
                        "network stops answering even though it was just advertised ($raw)"

                "ECONNREFUSED" in message ->
                    "the device at $host turned down the connection on port $port ($raw)"

                else -> raw
            }
        }

        fun connect(host: String, port: Int = LOCKDOWN_PORT, timeoutMs: Int = 8000): TcpTransport {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw DeviceException(
                    operation = "connect to $host:$port",
                    reason = explain(error, host, port, timeoutMs),
                    limitation = "the iPhone has to be awake, unlocked and on the same Wi-Fi " +
                        "network; guest and public networks often keep devices apart",
                    alternative = "check the address under Settings > Wi-Fi > (i) on the iPhone",
                    cause = error
                )
            }
            Log.i(LogTag.USBMUX, "connected to $host:$port")
            return TcpTransport(socket)
        }

        /**
         * Wraps a connection the iPhone made to this phone, as it does when it
         * pairs from Settings > Privacy & Security > Developer Mode.
         */
        fun accepted(socket: Socket): TcpTransport {
            runCatching { socket.tcpNoDelay = true }
            return TcpTransport(socket)
        }
    }
}
