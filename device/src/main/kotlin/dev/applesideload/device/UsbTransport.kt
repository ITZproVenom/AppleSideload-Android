package dev.applesideload.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException

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
    val device: UsbDevice
) : Transport {

    override val description: String
        get() = "USB ${device.deviceName} interface ${usbInterface.id}"

    private val readBuffer = ByteArray(16 * 1024)
    private var readFilled = 0
    private var readConsumed = 0

    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (readConsumed == readFilled) {
            // Bulk transfers arrive in whole packets, so a short protocol read
            // has to be served from a buffer rather than by asking the
            // endpoint for exactly the bytes wanted.
            val read = connection.bulkTransfer(input, readBuffer, readBuffer.size, timeoutMs)
            if (read < 0) return -1
            readFilled = read
            readConsumed = 0
            if (read == 0) return 0
        }
        val available = readFilled - readConsumed
        val count = minOf(available, length)
        System.arraycopy(readBuffer, readConsumed, into, offset, count)
        readConsumed += count
        return count
    }

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        var written = 0
        while (written < length) {
            val chunk = minOf(length - written, output.maxPacketSize * 16)
            val slice = if (offset == 0 && written == 0 && chunk == from.size) {
                from
            } else {
                from.copyOfRange(offset + written, offset + written + chunk)
            }
            val sent = connection.bulkTransfer(output, slice, chunk, timeoutMs)
            if (sent < 0) throw IOException("the USB write failed after $written bytes")
            written += sent
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        Log.i(LogTag.USB, "released ${device.deviceName}")
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05AC
        private const val MUX_CLASS = 255
        private const val MUX_SUBCLASS = 254
        private const val MUX_PROTOCOL = 2

        fun isApple(device: UsbDevice): Boolean = device.vendorId == APPLE_VENDOR_ID

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

            var found: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
            outer@ for (configIndex in 0 until device.configurationCount) {
                val configuration = device.getConfiguration(configIndex)
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
                        if (configuration.id != device.configurationCount) {
                            // Harmless when it is already current, required
                            // when it is not.
                            runCatching { connection.setConfiguration(configuration) }
                        }
                        found = Triple(candidate, inEndpoint, outEndpoint)
                        break@outer
                    }
                }
            }

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
            Log.i(
                LogTag.USB,
                "claimed interface ${claimed.id} on ${device.deviceName} " +
                    "(in ${input.address}, out ${output.address})"
            )
            return UsbTransport(connection, claimed, input, output, device)
        }
    }
}
