package dev.applesideload.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.remote.AuthTag
import dev.applesideload.device.remote.PairableHost
import dev.applesideload.device.remote.RemoteTunnel
import dev.applesideload.device.remote.RpChannel
import dev.applesideload.device.remote.RpPairingFile
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Bonjour side of Remote Pairing: advertising this phone so an iPhone can
 * pair with it from its own Settings, and finding a paired iPhone's
 * `_remotepairing._tcp` listener afterwards.
 */
class RemotePairingNetwork(context: Context, private val store: PairingStore) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** A running advertisement; closing it withdraws it and stops listening. */
    inner class Advertisement internal constructor(
        private val server: ServerSocket,
        private val registration: NsdManager.RegistrationListener,
        val identity: RpPairingFile
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        val isOpen: Boolean get() = !closed.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { nsd.unregisterService(registration) }
            runCatching { server.close() }
            Log.i(LogTag.PAIR, "stopped offering this phone for pairing")
        }
    }

    interface PairingEvents {
        /** Bonjour accepted the advertisement; the iPhone can now list this phone. */
        fun onAdvertised(serviceName: String)
        /** The iPhone connected and asked for the PIN; show [pin] to the user. */
        fun onPin(pin: String)
        /** Pairing finished; [record] is already stored. [host] is the iPhone's address, if known. */
        fun onPaired(record: RpPairingFile, host: String?)
        /** Something went wrong; [fatal] means the advertisement has stopped. */
        fun onProblem(message: String, fatal: Boolean)
    }

    /**
     * Offers this phone as a pairable host under [name] until an iPhone pairs
     * with it or the advertisement is closed.
     *
     * On the iPhone: Settings > Privacy & Security > Developer Mode lists the
     * offer as "Pair with [name]"; tapping it connects here, this phone shows a
     * six-digit PIN, and the user types it on the iPhone.
     */
    fun advertise(name: String, events: PairingEvents): Advertisement {
        val identity = store.remoteIdentity()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(0))
        val info = NsdServiceInfo().apply {
            serviceName = identity.identifier
            serviceType = PairableHost.SERVICE_TYPE
            port = server.localPort
            PairableHost.txtRecord(identity, name).forEach { (key, value) -> setAttribute(key, value) }
        }
        // Assigned before registerService, so it is set by the time any
        // callback can run.
        lateinit var advertisement: Advertisement
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(LogTag.PAIR, "offering this phone for pairing as \"$name\" (${registered.serviceName}) on port ${server.localPort}")
                events.onAdvertised(registered.serviceName)
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                // Closing first marks the advertisement closed, so the accept
                // loop exits quietly instead of reporting a second problem.
                advertisement.close()
                events.onProblem("Android could not advertise this phone on the network (NSD error $errorCode)", true)
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) = Unit
        }
        advertisement = Advertisement(server, registration, identity)
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (error: RuntimeException) {
            runCatching { server.close() }
            throw DeviceException(
                operation = "advertising this phone for pairing",
                reason = Log.describe(error),
                limitation = "Android's network service discovery has to be available"
            )
        }
        Thread({ acceptLoop(advertisement, server, identity, name, events) }, "pairable-host").apply {
            isDaemon = true
            start()
        }
        return advertisement
    }

    private fun acceptLoop(
        advertisement: Advertisement,
        server: ServerSocket,
        identity: RpPairingFile,
        name: String,
        events: PairingEvents
    ) {
        while (advertisement.isOpen) {
            val socket = try {
                server.accept()
            } catch (error: SocketException) {
                if (advertisement.isOpen) events.onProblem("the pairing listener stopped: ${error.message}", true)
                return
            }
            // Kept with its scope (fe80::...%wlan0): an iPhone often connects
            // from its link-local address, which is only usable together with
            // the interface it came in on.
            val peerAddress = socket.inetAddress?.hostAddress
            val peerHost = peerAddress ?: "an unknown address"
            Log.i(LogTag.PAIR, "an iPhone at $peerHost connected to pair")
            val transport = TcpTransport.accepted(socket)
            try {
                val peer = PairableHost(RpChannel(transport, RpChannel.DEVICE), name)
                    .accept(identity) { pin -> events.onPin(pin) }
                val record = identity.withDevice(peer)
                store.saveRemote(record, peerAddress)
                events.onPaired(record, peerAddress)
                runCatching { transport.close() }
                advertisement.close()
                return
            } catch (error: Exception) {
                runCatching { transport.close() }
                Log.w(LogTag.PAIR, "pairing with the iPhone at $peerHost failed: ${Log.describe(error)}")
                events.onProblem("Pairing with the iPhone failed: ${error.message}. It can try again from Settings.", false)
            }
        }
    }

    /**
     * Finds where [record]'s iPhone is listening for Remote Pairing, by its
     * authenticated `_remotepairing._tcp` advertisement. Returns null when it
     * is not seen within [timeoutMs].
     */
    fun locate(record: RpPairingFile, timeoutMs: Long = 6_000): InetSocketAddress? {
        val altIrk = record.deviceAltIrk ?: return null
        val result = AtomicReference<InetSocketAddress?>(null)
        val done = CountDownLatch(1)
        val pending = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LogTag.TUNNEL, "Bonjour discovery of $serviceType could not start (error $errorCode)")
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { pending.offer(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        try {
            nsd.discoverServices(DEVICE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            Log.w(LogTag.TUNNEL, "Bonjour discovery is unavailable: ${error.message}")
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (result.get() == null && done.count > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val candidate = pending.poll(left, TimeUnit.MILLISECONDS) ?: break
                // Resolves one at a time: older Android refuses overlapping ones.
                val resolved = resolve(candidate, minOf(left, 4_000)) ?: continue
                val txt = resolved.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                val identifier = txt["identifier"] ?: resolved.serviceName
                val tag = txt["authTag"] ?: continue
                if (!AuthTag.matches(altIrk, identifier, tag)) continue
                val host = hostOf(resolved) ?: continue
                val port = resolved.port.takeIf { it in 1..65535 } ?: RemoteTunnel.REMOTE_PAIRING_PORT
                Log.i(LogTag.TUNNEL, "found ${record.deviceName ?: "the iPhone"} at ${host.hostAddress}:$port")
                result.set(InetSocketAddress(host, port))
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return result.get()
    }

    private fun resolve(info: NsdServiceInfo, timeoutMs: Long): NsdServiceInfo? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<NsdServiceInfo?>(null)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { latch.countDown() }
            override fun onServiceResolved(resolved: NsdServiceInfo) { out.set(resolved); latch.countDown() }
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        } catch (error: RuntimeException) {
            Log.w(LogTag.TUNNEL, "could not resolve ${info.serviceName}: ${error.message}")
            return null
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // A resolve left running makes the next one fail as "already
                // active"; Android 14 added a way to cancel it.
                runCatching { nsd.stopServiceResolution(listener) }
            } else {
                // Android 13 and older cannot cancel a resolve, so later
                // lookups may fail until this one ends; callers then fall
                // back to the address the iPhone last used.
                Log.w(LogTag.TUNNEL, "Bonjour did not resolve ${info.serviceName} in time")
            }
        }
        return out.get()
    }

    private fun hostOf(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val all = info.hostAddresses
            return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    companion object {
        /** What an iPhone's remotepairingd advertises once it has paired hosts. */
        const val DEVICE_SERVICE_TYPE = "_remotepairing._tcp"
    }
}
