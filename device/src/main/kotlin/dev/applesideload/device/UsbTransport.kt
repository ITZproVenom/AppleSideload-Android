package dev.applesideload.device

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The USB side of the connection.
 *
 * An iPhone exposes several configurations and interfaces. The one that
 * matters here is Apple's mux interface: class 255, subclass 254, protocol 2.
 * It is the same interface usbmuxd claims on a desktop, and it carries every
 * device service through one pair of bulk endpoints.
 *
 * Two details decide whether this works at all:
 *
 *  * The interface only appears once the phone trusts the host, and on some
 *    iOS versions only after the device has been unlocked at least once since
 *    it was plugged in. Until then the device advertises a configuration with
 *    nothing but the AppleUSBMux placeholder, which is reported rather than
 *    retried silently.
 *  * Android hands out a UsbDeviceConnection for the current configuration.
 *    The mux interface frequently lives in a later configuration, so the
 *    configuration is set explicitly before the interface is claimed.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val request: UsbRequest,
    val device: UsbDevice
) : Transport {

    override val description: String
        get() = "USB ${device.deviceName} interface ${usbInterface.id}"

    private val inbound = ChunkQueue { description }
    private val stateLock = Any()
    private val writeLock = Any()
    private val cleanedUp = AtomicBoolean(false)

    @Volatile
    private var running = true

    /**
     * Whole packets per transfer in each direction.
     *
     * A read sized to a multiple of the packet size can never be overrun: the
     * controller ends the transfer at the buffer's end on a packet boundary and
     * the rest of the device's data starts the next one. A read of any other
     * size can be, and an overrun loses data.
     */
    private val inPacket = input.maxPacketSize.coerceAtLeast(1)
    private val outPacket = output.maxPacketSize.coerceAtLeast(1)
    private val inTransfer = (TRANSFER_BYTES / inPacket).coerceAtLeast(1) * inPacket
    private val outTransfer = (TRANSFER_BYTES / outPacket).coerceAtLeast(1) * outPacket

    private val reader = Thread({ readLoop() }, "usb-in").apply { isDaemon = true }

    private fun start() = reader.start()

    /**
     * Keeps one read queued on the input endpoint at all times.
     *
     * A synchronous bulk read with a timeout is not safe here: when it times
     * out, the kernel cancels the transfer and anything that had already
     * arrived for it is thrown away, which corrupts the mux stream at random.
     * A queued request is never cancelled by waiting, so nothing is lost, and
     * the endpoint is drained continuously, so the phone is never left
     * holding data while this side is busy writing.
     */
    private fun readLoop() {
        val buffer = ByteBuffer.allocateDirect(inTransfer)
        var emptyInARow = 0
        try {
            while (true) {
                buffer.clear()
                synchronized(stateLock) {
                    if (!running) return
                    if (!request.queue(buffer)) {
                        throw IOException(
                            "a USB read could not be queued; the iPhone was probably unplugged"
                        )
                    }
                }
                val completed = connection.requestWait()
                if (!running) return
                if (completed == null) {
                    throw IOException(
                        "the USB input endpoint stopped answering; the iPhone was probably unplugged"
                    )
                }
                val count = buffer.position()
                if (count == 0) {
                    // A zero length packet ends a transfer that filled whole
                    // packets exactly, and carries nothing. A run of them
                    // means the endpoint is failing every read instantly.
                    emptyInARow += 1
                    if (emptyInARow >= MAX_EMPTY_READS) {
                        throw IOException(
                            "the USB input endpoint returned nothing $emptyInARow times in a row; " +
                                "the iPhone stopped sending or was unplugged"
                        )
                    }
                    continue
                }
                emptyInARow = 0
                val chunk = ByteArray(count)
                buffer.flip()
                buffer.get(chunk)
                inbound.put(chunk)
            }
        } catch (error: Throwable) {
            if (running) {
                Log.w(LogTag.USB, "USB input stopped: ${Log.describe(error)}")
                inbound.fail(error as? IOException ?: IOException(Log.describe(error), error))
            }
        } finally {
            inbound.finish()
            // Closing while this thread sat in requestWait would free the
            // native connection under it, so whoever finishes last cleans up.
            if (!running) cleanUp()
        }
    }

    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        inbound.read(into, offset, length, timeoutMs)

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        synchronized(writeLock) {
            if (!running) throw IOException("$description is closed")
            var written = 0
            while (written < length) {
                val chunk = minOf(length - written, outTransfer)
                val sent = connection.bulkTransfer(output, from, offset + written, chunk, timeoutMs)
                if (sent != chunk) {
                    throw IOException(
                        "the USB write failed after ${written + sent.coerceAtLeast(0)} of " +
                            "$length bytes ($description)"
                    )
                }
                written += sent
            }
            if (length > 0 && length % outPacket == 0) {
                // A transfer that fills whole packets is only over when a
                // zero length packet says so. Without it the phone keeps
                // waiting and reads the next packet as part of this one.
                if (connection.bulkTransfer(output, EMPTY, 0, 0, timeoutMs) < 0) {
                    throw IOException("the USB write could not end its transfer ($description)")
                }
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (!running) return
            running = false
            // Wakes the reader out of requestWait; the read it had queued
            // completes as cancelled.
            runCatching { request.cancel() }
        }
        inbound.finish()
        if (Thread.currentThread() !== reader && reader.isAlive) {
            reader.join(CLOSE_WAIT_MS)
        }
        if (reader.isAlive) {
            Log.w(
                LogTag.USB,
                "the USB reader is still waiting; ${device.deviceName} is released once it returns"
            )
            return
        }
        cleanUp()
    }

    private fun cleanUp() {
        if (!cleanedUp.compareAndSet(false, true)) return
        runCatching { request.close() }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        Log.i(LogTag.USB, "released ${device.deviceName}")
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05AC
        private const val GET_CONFIGURATION = 0x08
        private const val CONTROL_TIMEOUT_MS = 1_000
        private const val TRANSFER_BYTES = 16 * 1024
        private const val MAX_EMPTY_READS = 64
        private const val CLOSE_WAIT_MS = 3_000L
        private val EMPTY = ByteArray(0)
        private const val MUX_CLASS = 255
        private const val MUX_SUBCLASS = 254
        private const val MUX_PROTOCOL = 2

        fun isApple(device: UsbDevice): Boolean = device.vendorId == APPLE_VENDOR_ID

        /** The mux interface of [configuration] with its bulk endpoints, if it has one. */
        private fun muxInterface(
            configuration: UsbConfiguration
        ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (interfaceIndex in 0 until configuration.interfaceCount) {
                val candidate = configuration.getInterface(interfaceIndex)
                if (candidate.interfaceClass != MUX_CLASS ||
                    candidate.interfaceSubclass != MUX_SUBCLASS ||
                    candidate.interfaceProtocol != MUX_PROTOCOL
                ) {
                    continue
                }
                var inEndpoint: UsbEndpoint? = null
                var outEndpoint: UsbEndpoint? = null
                for (endpointIndex in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = endpoint
                    } else {
                        outEndpoint = endpoint
                    }
                }
                if (inEndpoint != null && outEndpoint != null) {
                    return Triple(candidate, inEndpoint, outEndpoint)
                }
            }
            return null
        }

        /**
         * The configuration the device is in now, asked of the device itself
         * with a standard GET_CONFIGURATION request, or null if it would not say.
         */
        private fun activeConfiguration(connection: UsbDeviceConnection): Int? {
            val reply = ByteArray(1)
            val read = connection.controlTransfer(
                UsbConstants.USB_DIR_IN, GET_CONFIGURATION, 0, 0, reply, 1, CONTROL_TIMEOUT_MS
            )
            return if (read == 1) reply[0].toInt() and 0xFF else null
        }

        /**
         * Opens the mux interface, or explains why it is not there.
         *
         * Permission is the caller's job: Android only grants access to a USB
         * device through a user prompt, and claiming an interface without it
         * fails with nothing useful to report.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbTransport {
            if (!isApple(device)) {
                throw DeviceException(
                    operation = "USB open",
                    reason = "vendor ${device.vendorId} is not Apple",
                    limitation = "this app only talks to Apple devices"
                )
            }
            if (!manager.hasPermission(device)) {
                throw DeviceException(
                    operation = "USB open",
                    reason = "Android has not granted this app access to the device",
                    alternative = "accept the USB permission prompt and connect again"
                )
            }

            val connection = manager.openDevice(device)
                ?: throw DeviceException(
                    operation = "USB open",
                    reason = "the device would not open; another app may hold it"
                )

            // Every configuration that carries the mux interface, as usbmuxd
            // looks for them. The one already active is used as it is;
            // otherwise the highest is chosen and switched to, which is
            // usbmuxd's choice too.
            val candidates = (0 until device.configurationCount).mapNotNull { index ->
                val configuration = device.getConfiguration(index)
                muxInterface(configuration)?.let { configuration to it }
            }
            val active = activeConfiguration(connection)
            val chosen = candidates.firstOrNull { it.first.id == active }
                ?: candidates.maxByOrNull { it.first.id }
            if (chosen != null && chosen.first.id != active) {
                if (connection.setConfiguration(chosen.first)) {
                    Log.i(
                        LogTag.USB,
                        "switched ${device.deviceName} from USB configuration " +
                            "${active ?: "unknown"} to ${chosen.first.id}"
                    )
                } else {
                    Log.w(
                        LogTag.USB,
                        "could not switch ${device.deviceName} to USB configuration " +
                            "${chosen.first.id}; claiming the interface anyway"
                    )
                }
            }
            val found = chosen?.second

            val (claimed, input, output) = found ?: run {
                connection.close()
                throw DeviceException(
                    operation = "USB open",
                    reason = "the device exposes no Apple mux interface " +
                        "(class $MUX_CLASS, subclass $MUX_SUBCLASS, protocol $MUX_PROTOCOL)",
                    limitation = "an iPhone only exposes this interface once it has been " +
                        "unlocked and has trusted a host at least once since it was attached",
                    alternative = "unlock the phone, answer Trust This Computer, and reconnect"
                )
            }

            if (!connection.claimInterface(claimed, true)) {
                connection.close()
                throw DeviceException(
                    operation = "USB open",
                    reason = "interface ${claimed.id} could not be claimed"
                )
            }
            val request = UsbRequest()
            if (!request.initialize(connection, input)) {
                runCatching { request.close() }
                runCatching { connection.releaseInterface(claimed) }
                connection.close()
                throw DeviceException(
                    operation = "USB open",
                    reason = "Android could not set up reads on endpoint ${input.address}"
                )
            }
            Log.i(
                LogTag.USB,
                "claimed interface ${claimed.id} on ${device.deviceName} " +
                    "(in ${input.address}, out ${output.address}, " +
                    "${input.maxPacketSize} byte packets)"
            )
            return UsbTransport(connection, claimed, input, output, request, device)
                .apply { start() }
        }
    }
}
