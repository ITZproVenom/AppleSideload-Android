package dev.applesideload.device.remote

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.device.DeviceException
import dev.applesideload.device.PlistService
import dev.applesideload.device.TcpTransport
import dev.applesideload.device.Transport
import java.io.Closeable
import java.io.IOException

/**
 * A CoreDevice tunnel to an iPhone over Wi-Fi, which is how iOS 17 and later
 * (and, on iOS 27, the only way) serve a computer without a cable.
 *
 * The chain, as Xcode, pymobiledevice3 and idevice build it:
 *  1. the iPhone's remotepairingd on `_remotepairing._tcp` (port 49152):
 *     handshake, then pair-verify with the stored [RpPairingFile];
 *  2. createListener opens a TLS-PSK listener on the iPhone's Wi-Fi address,
 *     keyed with the pair-verify secret;
 *  3. inside that, CDTunnel hands out IPv6 addresses, and the stream becomes
 *     raw IPv6 packets, carried by [TunnelStack];
 *  4. RemoteServiceDiscovery on the tunnel lists every service's port, and
 *     the lockdown-era services are reached through their `.shim.remote`
 *     names after an RSDCheckin.
 *
 * The control connection from step 1 stays open for the tunnel's lifetime.
 */
class RemoteTunnel private constructor(
    val host: String,
    private val control: RemotePairingClient,
    private val stack: TunnelStack,
    val parameters: CdTunnel.Parameters,
    val rsd: RsdHandshake
) : Closeable {

    val isAlive: Boolean get() = stack.isAlive

    val description: String get() = "Wi-Fi tunnel ($host)"

    /** A plain TCP connection to [port] on the iPhone, inside the tunnel. */
    fun connect(port: Int): Transport = stack.connect(port)

    /** Opens a service by its RSD name and checks in, ready for its own protocol. */
    fun openService(rsdName: String, label: String = LABEL): Transport {
        val port = rsd.port(rsdName) ?: throw DeviceException(
            operation = "starting $rsdName",
            reason = "the iPhone's service list over the tunnel does not include it",
            limitation = "the device offers ${rsd.services.size} services; this one may need Developer Mode " +
                "or a newer iOS"
        )
        val transport = try {
            stack.connect(port)
        } catch (error: IOException) {
            throw DeviceException(
                operation = "starting $rsdName",
                reason = "could not connect to port $port inside the tunnel: ${Log.describe(error)}",
                cause = error
            )
        }
        try {
            checkIn(transport, label)
        } catch (error: Exception) {
            runCatching { transport.close() }
            throw if (error is DeviceException) error else DeviceException(
                operation = "starting $rsdName",
                reason = "RSDCheckin failed: ${Log.describe(error)}",
                cause = error
            )
        }
        return transport
    }

    override fun close() {
        runCatching { stack.close() }
        runCatching { control.close() }
    }

    companion object {
        const val REMOTE_PAIRING_PORT = 49152
        const val LABEL = "AppleSideload"
        const val LOCKDOWN = "com.apple.mobile.lockdown.remote.trusted"

        /** The RSD name a lockdown-era service is offered under inside the tunnel. */
        fun shimName(lockdownName: String): String = "$lockdownName.shim.remote"

        /**
         * Builds the whole tunnel. Returns null when the iPhone no longer
         * knows this identity (it was unpaired), so the caller can ask for a
         * fresh pairing instead of reporting a broken connection.
         */
        fun open(
            host: String,
            file: RpPairingFile,
            hostName: String,
            port: Int = REMOTE_PAIRING_PORT,
            onStep: (String) -> Unit = {}
        ): RemoteTunnel? {
            onStep("Connecting to remote pairing on $host:$port")
            val controlTransport = TcpTransport.connect(host, port)
            val control = RemotePairingClient(RpChannel(controlTransport), hostName)
            var stack: TunnelStack? = null
            try {
                control.handshake()
                onStep("Verifying this phone's pairing")
                if (!control.verify(file)) {
                    control.close()
                    return null
                }
                onStep("Opening the encrypted tunnel")
                // A listener that hangs up during the handshake is replaced
                // once, the way idevice retries, before giving up.
                var tls: TlsPskTransport? = null
                var parameters: CdTunnel.Parameters? = null
                var lastError: Exception? = null
                for (attempt in 1..2) {
                    val listenerPort = control.createListener()
                    var candidate: TlsPskTransport? = null
                    try {
                        candidate = TlsPskTransport.connect(TcpTransport.connect(host, listenerPort), control.encryptionKey)
                        parameters = CdTunnel.handshake(candidate)
                        tls = candidate
                        break
                    } catch (error: Exception) {
                        runCatching { candidate?.close() }
                        if (error is DeviceException && attempt == 2) throw error
                        lastError = error
                        Log.w(LogTag.TUNNEL, "tunnel attempt $attempt on port $listenerPort failed: ${Log.describe(error)}")
                    }
                }
                if (tls == null || parameters == null) {
                    throw lastError ?: IOException("the tunnel listener could not be reached")
                }
                stack = TunnelStack(tls, parameters.clientAddress, parameters.serverAddress, parameters.mtu).start()
                onStep("Discovering the iPhone's services")
                val rsd = stack.connect(parameters.rsdPort).use { RsdHandshake.perform(it) }
                Log.i(LogTag.TUNNEL, "tunnel to $host is up")
                return RemoteTunnel(host, control, stack, parameters, rsd)
            } catch (error: Exception) {
                runCatching { stack?.close() }
                runCatching { control.close() }
                if (error is DeviceException) throw error
                throw DeviceException(
                    operation = "opening the Wi-Fi tunnel to $host",
                    reason = Log.describe(error),
                    limitation = "the iPhone must be unlocked, on the same Wi-Fi, and paired with this phone " +
                        "through Remote Pairing",
                    cause = error
                )
            }
        }

        /** RSDCheckin: the handshake every service on the tunnel wants before its own protocol. */
        fun checkIn(transport: Transport, label: String = LABEL) {
            val service = PlistService(transport, "RSDCheckin", sendBinary = false)
            service.send(
                Plist.Dict(
                    linkedMapOf(
                        "Label" to Plist.Str(label),
                        "ProtocolVersion" to Plist.Str("2"),
                        "Request" to Plist.Str("RSDCheckin")
                    )
                )
            )
            val first = service.receive(15_000)
            val firstRequest = first["Request"]?.asString
            if (firstRequest != "RSDCheckin") {
                throw IOException("the service answered RSDCheckin with ${firstRequest ?: first["Error"]?.asString ?: "nothing"}")
            }
            val second = service.receive(15_000)
            val secondRequest = second["Request"]?.asString
            if (secondRequest != "StartService") {
                throw IOException("the service sent ${secondRequest ?: second["Error"]?.asString ?: "nothing"} instead of StartService")
            }
        }
    }
}
