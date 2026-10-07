package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

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

        fun connect(host: String, port: Int = LOCKDOWN_PORT, timeoutMs: Int = 8000): TcpTransport {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw DeviceException(
                    operation = "connect to $host:$port",
                    reason = Log.describe(error),
                    limitation = "the iPhone has to be awake, unlocked and on the same Wi-Fi " +
                        "network; guest and public networks often keep devices apart",
                    alternative = "check the address under Settings > Wi-Fi > (i) on the iPhone"
                )
            }
            Log.i(LogTag.USBMUX, "connected to $host:$port")
            return TcpTransport(socket)
        }
    }
}
