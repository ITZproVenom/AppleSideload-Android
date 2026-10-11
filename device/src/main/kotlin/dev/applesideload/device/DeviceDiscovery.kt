package dev.applesideload.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/** How a device was found, and how it can be reached. */
sealed class DiscoveredDevice {
    abstract val id: String
    abstract val displayName: String

    data class Usb(val device: UsbDevice) : DiscoveredDevice() {
        override val id: String get() = device.deviceName
        override val displayName: String
            get() = device.productName ?: "iPhone over USB"
        val serial: String? get() = runCatching { device.serialNumber }.getOrNull()
    }

    data class Wifi(
        val serviceName: String,
        val host: InetAddress,
        val port: Int
    ) : DiscoveredDevice() {
        override val id: String get() = serviceName
        override val displayName: String get() = "${serviceName.substringBefore('@')} over Wi-Fi"
    }
}

/**
 * Finds iPhones, over the cable and over the network.
 *
 * USB is an enumeration of attached devices filtered to Apple's vendor ID -
 * the mux interface check happens when the device is opened, because a device
 * in recovery or DFU also answers to that vendor ID and has to be reported
 * honestly rather than hidden.
 *
 * Wi-Fi is Bonjour: a paired device that has Wi-Fi sync enabled advertises
 * _apple-mobdev2._tcp, and that advertisement is the only way to find it
 * without scanning the subnet.
 */
class DeviceDiscovery(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    /** Apple devices currently attached by cable. */
    fun usbDevices(): List<DiscoveredDevice.Usb> = usbManager.deviceList.values
        .filter { UsbTransport.isApple(it) }
        .map { DiscoveredDevice.Usb(it) }

    fun refreshUsb() {
        val wifi = _devices.value.filterIsInstance<DiscoveredDevice.Wifi>()
        _devices.value = usbDevices() + wifi
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Asks Android for access to the device.
     *
     * Without this the USB endpoints cannot be claimed at all, and the
     * request has to come from a user gesture, so the UI calls it rather than
     * the connection code doing it behind the scenes.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val action = "${context.packageName}.USB_PERMISSION"
        val answered = CompletableDeferred<Boolean>()
        // Android's answer comes as a broadcast, but a broadcast can be lost or
        // arrive without its extra on some versions and makes, so it is only a
        // nudge: the verdict is whatever UsbManager says about the device.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val extra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(LogTag.USB, "Android answered the USB access request (granted flag: $extra)")
                answered.complete(extra)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system sends this on the app's behalf, so a receiver that
            // refuses outside senders can miss it.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, intent)
            Log.i(LogTag.USB, "asked Android for access to ${device.deviceName}")
            val deadline = System.nanoTime() + WAIT_FOR_PERMISSION_NANOS
            while (System.nanoTime() < deadline) {
                if (usbManager.hasPermission(device)) {
                    Log.i(LogTag.USB, "USB access was granted")
                    return true
                }
                if (answered.isCompleted) {
                    // Give Android a moment to record the grant before judging.
                    delay(400)
                    val granted = usbManager.hasPermission(device)
                    Log.i(LogTag.USB, if (granted) "USB access was granted" else "USB access was refused")
                    return granted
                }
                delay(200)
            }
            Log.w(LogTag.USB, "no answer to the USB access request after two minutes")
            return usbManager.hasPermission(device)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun openUsb(device: UsbDevice): UsbTransport {
        if (!usbManager.hasPermission(device)) {
            throw DeviceException(
                operation = "opening the USB connection",
                reason = "Android has not granted this app access to the device",
                alternative = "allow access when Android asks, and tick the box to remember it"
            )
        }
        return UsbTransport.open(usbManager, device)
    }

    // MARK: - Wi-Fi

    fun startWifiDiscovery() {
        if (listener != null) return
        val found = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LogTag.USBMUX, "Bonjour discovery could not start (error $errorCode)")
                listener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener = null
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(LogTag.USBMUX, "looking for devices advertising $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                listener = null
            }

            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)

            override fun onServiceLost(info: NsdServiceInfo) {
                _devices.value = _devices.value.filterNot {
                    it is DiscoveredDevice.Wifi && it.serviceName == info.serviceName
                }
            }
        }
        listener = found
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, found) }
            .onFailure {
                listener = null
                Log.w(LogTag.USBMUX, "Bonjour discovery is unavailable: ${it.message}")
            }
    }

    fun stopWifiDiscovery() {
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
    }

    private fun resolve(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(LogTag.USBMUX, "could not resolve ${failed.serviceName} (error $errorCode)")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = resolved.host ?: return
                val device = DiscoveredDevice.Wifi(
                    serviceName = resolved.serviceName,
                    host = host,
                    port = TcpTransport.LOCKDOWN_PORT
                )
                Log.i(LogTag.USBMUX, "found ${resolved.serviceName} on the network")
                _devices.value = _devices.value.filterNot { it.id == device.id } + device
            }
        })
    }

    companion object {
        /** What a device with Wi-Fi sync enabled advertises. */
        const val SERVICE_TYPE = "_apple-mobdev2._tcp"

        private const val WAIT_FOR_PERMISSION_NANOS = 120_000_000_000L
    }
}
