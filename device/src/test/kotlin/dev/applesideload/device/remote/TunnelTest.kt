package dev.applesideload.device.remote

import dev.applesideload.core.Plist
import dev.applesideload.device.DeviceException
import dev.applesideload.device.PlistService
import dev.applesideload.device.TcpTransport
import dev.applesideload.device.Transport
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Hashtable
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tunnel from the TLS-PSK handshake up: the userspace TCP against a
 * simulated iPhone's TCP, RemoteServiceDiscovery against a simulated RSD
 * endpoint, RSDCheckin, and the whole chain from the remote pairing port to
 * a service's echo, over real loopback sockets.
 */
class TunnelTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val hostAddress = CdTunnel.ipv6("fd00::2")
    private val deviceAddress = CdTunnel.ipv6("fd00::1")

    /** BouncyCastle's own TLS-PSK server, configured the way the iPhone's tunnel listener behaves. */
    private class PskServer(key: () -> ByteArray?) : PSKTlsServer(
        BcTlsCrypto(SecureRandom()),
        object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray? = null
            override fun getPSK(identity: ByteArray): ByteArray? = if (identity.isEmpty()) key() else null
        }
    ) {
        @Volatile var suite = -1
        @Volatile var extensions: Hashtable<*, *>? = null

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
        override fun getSupportedCipherSuites(): IntArray = TlsPskTransport.CIPHER_SUITES.copyOf()

        override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
            extensions = clientExtensions
            super.processClientExtensions(clientExtensions)
        }

        override fun notifyHandshakeComplete() {
            super.notifyHandshakeComplete()
            suite = context.securityParametersConnection.cipherSuite
        }
    }

    // ---- TLS-PSK -------------------------------------------------------------------

    @Test
    fun `TLS-PSK carries a megabyte both ways at once against BouncyCastle's server`() {
        val psk = ByteArray(32) { (it * 7 + 1).toByte() }
        val server = PskServer { psk }
        ServerSocket(0, 1, loopback).use { listener ->
            val serverSide = Background("TLS server") {
                listener.accept().use { socket ->
                    val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
                    protocol.accept(server)
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    try {
                        while (true) {
                            val n = protocol.inputStream.read(buffer)
                            if (n < 0) break
                            protocol.outputStream.write(buffer, 0, n)
                            total += n
                        }
                    } finally {
                        runCatching { protocol.close() }
                    }
                    total
                }
            }
            val tls = TlsPskTransport.connect(TcpTransport.connect("127.0.0.1", listener.localPort), psk)
            val payload = Random(1).nextBytes(1 shl 20)
            val writer = Background("writer") {
                var at = 0
                while (at < payload.size) {
                    val n = minOf(10_000, payload.size - at)
                    tls.write(payload, at, n, 15_000)
                    at += n
                }
            }
            val echoed = ByteArray(payload.size)
            var got = 0
            while (got < payload.size) {
                val n = tls.read(echoed, got, payload.size - got, 15_000)
                assertTrue(n > 0, "the tunnel ended after $got bytes")
                got += n
            }
            writer.await()
            assertContentEquals(payload, echoed)
            tls.close()
            assertEquals(payload.size.toLong(), serverSide.await())
        }
        assertEquals(CipherSuite.TLS_PSK_WITH_AES_256_CBC_SHA384, server.suite)
        assertTrue(server.extensions.isNullOrEmpty(), "the ClientHello carried extensions: ${server.extensions?.keys}")
    }

    @Test
    fun `TLS-PSK settles on AES-128 when that is all the listener takes`() {
        val psk = ByteArray(32) { 3 }
        val server = object : PSKTlsServer(
            BcTlsCrypto(SecureRandom()),
            object : TlsPSKIdentityManager {
                override fun getHint(): ByteArray? = null
                override fun getPSK(identity: ByteArray): ByteArray = psk
            }
        ) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
            override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA)
        }
        ServerSocket(0, 1, loopback).use { listener ->
            val serverSide = Background("TLS server") {
                listener.accept().use { socket ->
                    val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
                    protocol.accept(server)
                    val line = ByteArray(5)
                    var at = 0
                    while (at < 5) at += protocol.inputStream.read(line, at, 5 - at).also { check(it > 0) }
                    protocol.outputStream.write(line.reversedArray())
                    protocol.outputStream.flush()
                    runCatching { protocol.inputStream.read() }
                    runCatching { protocol.close() }
                }
            }
            val tls = TlsPskTransport.connect(TcpTransport.connect("127.0.0.1", listener.localPort), psk)
            tls.write("hello".toByteArray())
            assertEquals("olleh", String(tls.readFully(5, 10_000)))
            tls.close()
            serverSide.await()
        }
    }

    @Test
    fun `a wrong tunnel key fails the TLS-PSK handshake`() {
        ServerSocket(0, 1, loopback).use { listener ->
            val serverSide = Background("TLS server") {
                listener.accept().use { socket ->
                    runCatching {
                        TlsServerProtocol(socket.getInputStream(), socket.getOutputStream()).accept(PskServer { ByteArray(32) { 1 } })
                    }
                }
            }
            val error = assertFailsWith<RemotePairingException> {
                TlsPskTransport.connect(TcpTransport.connect("127.0.0.1", listener.localPort), ByteArray(32) { 2 }, 10_000)
            }
            assertTrue(error.message!!.startsWith("TLS-PSK handshake failed"), error.message)
            assertTrue(serverSide.await().isFailure, "the server accepted a client with the wrong key")
        }
    }

    // ---- the userspace TCP ------------------------------------------------------------

    private val echo: (Transport) -> Unit = { t ->
        val buffer = ByteArray(8192)
        while (true) {
            val n = t.read(buffer, 0, buffer.size, 30_000)
            if (n < 0) break
            t.write(buffer, 0, n, 30_000)
        }
    }

    private fun tunnel(
        services: Map<Int, (Transport) -> Unit>,
        configure: FakeTunnelPeer.() -> Unit = {}
    ): Pair<TunnelStack, FakeTunnelPeer> {
        val (hostEnd, deviceEnd) = PipeTransport.pair()
        val peer = FakeTunnelPeer(deviceEnd, deviceAddress, hostAddress, services).apply(configure).start()
        val stack = TunnelStack(hostEnd, hostAddress, deviceAddress, 1500).start()
        return stack to peer
    }

    /** Writes [size] random bytes on one thread while reading the echo on this one. */
    private fun roundTrip(connection: Transport, size: Int, seed: Int) {
        val payload = Random(seed).nextBytes(size)
        val writer = Background("writer $seed") {
            var at = 0
            while (at < size) {
                val n = minOf(7_000, size - at)
                connection.write(payload, at, n, 30_000)
                at += n
            }
        }
        val back = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = connection.read(back, got, size - got, 30_000)
            assertTrue(n > 0, "end of stream after $got of $size bytes")
            got += n
        }
        writer.await()
        assertContentEquals(payload, back)
    }

    private fun assertClean(peer: FakeTunnelPeer) {
        assertTrue(peer.problems.isEmpty(), "the simulated iPhone saw: ${peer.problems}")
    }

    @Test
    fun `a megabyte echoes through the tunnel's TCP`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo))
        stack.use {
            val connection = stack.connect(7000)
            roundTrip(connection, 1 shl 20, 1)
            connection.close()
            assertEquals(listOf(1440 to TunnelStack.WINDOW_SCALE), peer.synOptions.toList(), "the SYN's MSS and window scale")
            assertTrue(peer.largestSegmentFromHost <= 1000, "a ${peer.largestSegmentFromHost}-byte segment beat the device's MSS")
            assertEquals(1L shl 20, peer.bytesFromHost)
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `a lost segment is sent again`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo)) { dropFirstData += 7000 }
        stack.use {
            roundTrip(stack.connect(7000), 100_000, 2)
            assertEquals(1, peer.droppedSegments)
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `segments that arrive out of order are put back in order`() {
        val size = 50_000
        // Collects everything, then answers in one write, so the swap always happens.
        val collectThenAnswer: (Transport) -> Unit = { t -> t.write(t.readFully(size, 30_000), 30_000) }
        val (stack, peer) = tunnel(mapOf(7003 to collectThenAnswer)) { reorder += 7003 }
        stack.use {
            val connection = stack.connect(7003)
            val payload = Random(3).nextBytes(size)
            connection.write(payload, 0, size, 30_000)
            assertContentEquals(payload, connection.readFully(size, 30_000))
            assertEquals(1, peer.reorderedWrites)
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `a zero window is probed until the device opens it`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo)) { paused += 7000 }
        stack.use {
            val connection = stack.connect(7000)
            val payload = Random(4).nextBytes(10_000)
            connection.write(payload, 0, payload.size, 5_000) // fits in the send buffer
            Thread.sleep(1_500)
            assertEquals(0L, peer.bytesFromHost, "data went into a zero window")
            assertTrue(peer.windowProbes >= 1, "the host never probed the zero window")
            peer.resume(7000)
            assertContentEquals(payload, connection.readFully(payload.size, 15_000))
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `a closed port is refused`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo))
        stack.use {
            assertFailsWith<ConnectException> { stack.connect(7001) }
            assertEquals(1, peer.resetsSent)
            assertTrue(stack.isAlive)
            roundTrip(stack.connect(7000), 1_000, 5) // the tunnel is still fine
        }
        peer.close()
    }

    @Test
    fun `connections in parallel stay separate`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo))
        stack.use {
            val workers = (1..4).map { n ->
                Background("connection $n") { stack.connect(7000).use { roundTrip(it, 200_000, 10 + n) } }
            }
            workers.forEach { it.await(60_000) }
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `the device closing first reads as end of stream`() {
        val greeting = "hello from the device".toByteArray()
        val (stack, peer) = tunnel(mapOf(7002 to { t: Transport -> t.write(greeting, 5_000) }))
        stack.use {
            val connection = stack.connect(7002)
            assertContentEquals(greeting, connection.readFully(greeting.size, 5_000))
            assertEquals(-1, connection.read(ByteArray(10), 0, 10, 5_000))
            connection.close()
            val deadline = System.currentTimeMillis() + 5_000
            while (peer.openConnections > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, peer.openConnections, "the close never completed on the device's side")
            assertClean(peer)
        }
        peer.close()
    }

    @Test
    fun `losing the tunnel fails a blocked read instead of hanging`() {
        val (stack, peer) = tunnel(mapOf(7000 to echo))
        val connection = stack.connect(7000)
        val reader = Background("blocked reader") { runCatching { connection.read(ByteArray(10), 0, 10, 30_000) } }
        Thread.sleep(200)
        peer.close()
        val outcome = reader.await(10_000)
        assertTrue(outcome.exceptionOrNull() is IOException, "the read ended with $outcome")
        val deadline = System.currentTimeMillis() + 5_000
        while (stack.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(stack.isAlive)
        assertFailsWith<IOException> { stack.connect(7000) }
        stack.close()
    }

    // ---- RemoteServiceDiscovery and RSDCheckin ---------------------------------------

    @Test
    fun `RSD sends idevice's frames and reads a padded reply split across frames`() {
        val (ours, theirs) = PipeTransport.pair()
        val device = FakeRsd(FakeRsd.sampleReply())
        val deviceSide = Background("RSD") { device.serve(theirs) }
        val rsd = RsdHandshake.perform(ours, 10_000)
        deviceSide.await()
        assertEquals(50001, rsd.port(RemoteTunnel.LOCKDOWN))
        assertEquals(50002, rsd.port("com.apple.coredevice.appservice"))
        assertTrue(rsd.services.getValue("com.apple.coredevice.appservice").usesRemoteXpc)
        assertFalse(rsd.services.getValue(RemoteTunnel.LOCKDOWN).usesRemoteXpc)
        assertNull(rsd.port("com.apple.broken.service"), "a service without a port is skipped")
        assertEquals("iPhone18,1", rsd.property("ProductType"))
        assertEquals("27.0", rsd.property("OSVersion"))
        assertEquals("12", rsd.property("BoardId"))
        assertEquals("2be6e510-0325-4365-923e-b14c6f57db3a", rsd.uuid)
        assertEquals("Handshake", device.hello?.get("MessageType"))
    }

    @Test
    fun `RSDCheckin sends the reference's request and waits for StartService`() {
        val (ours, theirs) = PipeTransport.pair()
        val deviceSide = Background("service") {
            val raw = theirs.readFully(4, 5_000)
            val length = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            val body = String(theirs.readFully(length, 5_000))
            val service = PlistService(theirs, "fake service", sendBinary = false)
            service.send(Plist.dict("Request" to Plist.of("RSDCheckin")))
            service.send(Plist.dict("Request" to Plist.of("StartService")))
            body
        }
        RemoteTunnel.checkIn(ours, "AppleSideload (Test)")
        val xml = deviceSide.await()
        assertTrue(xml.startsWith("<?xml"), "RSDCheckin goes as an XML plist, as idevice sends it")
        val request = dev.applesideload.core.PlistReader.parse(xml.toByteArray())
        assertEquals("AppleSideload (Test)", request["Label"]?.asString)
        assertEquals("2", request["ProtocolVersion"]?.asString)
        assertEquals("RSDCheckin", request["Request"]?.asString)
    }

    @Test
    fun `RSDCheckin reports the device's error`() {
        val (ours, theirs) = PipeTransport.pair()
        val deviceSide = Background("service") {
            val service = PlistService(theirs, "fake service", sendBinary = false)
            service.receive(5_000)
            service.send(Plist.dict("Error" to Plist.of("EntitlementMissing")))
        }
        val error = assertFailsWith<IOException> { RemoteTunnel.checkIn(ours) }
        assertTrue(error.message!!.contains("EntitlementMissing"), error.message)
        deviceSide.await()
    }

    // ---- the whole chain ------------------------------------------------------------------

    /** A lockdown-shaped service: RSDCheckin, then an echo of whatever comes. */
    private fun checkInThenEcho(t: Transport, saw: AtomicReference<Plist>) {
        val service = PlistService(t, "fake lockdown", sendBinary = false)
        saw.set(service.receive(10_000))
        service.send(Plist.dict("Request" to Plist.of("RSDCheckin")))
        service.send(Plist.dict("Request" to Plist.of("StartService")))
        echo(t)
    }

    @Test
    fun `the tunnel comes up from the pairing port to a service against a simulated iPhone`() {
        val identity = RpPairingFile.generate()
        val control = ServerSocket(0, 4, loopback)
        val hangsUp = ServerSocket(0, 4, loopback)
        val listener = ServerSocket(0, 4, loopback)
        val shared = AtomicReference<ByteArray>()
        val server = PskServer { shared.get() }
        val peer = AtomicReference<FakeTunnelPeer>()
        val checkIn = AtomicReference<Plist>()
        val rsd = FakeRsd(FakeRsd.sampleReply(lockdownPort = 50001))
        try {
            val iphoneSide = Background("iPhone control") {
                control.accept().use { socket ->
                    val iphone = FakeIphone(TcpTransport.accepted(socket), RpChannel.DEVICE)
                    iphone.answerHandshake()
                    check(iphone.answerVerify(identity)) { "the iPhone did not recognise the host" }
                    shared.set(iphone.shared)
                    // The first listener hangs up during the handshake; the host asks for another.
                    val first = iphone.answerCreateListener(hangsUp.localPort)
                    val second = iphone.answerCreateListener(listener.localPort)
                    // remotepairingd keeps the control connection for the tunnel's lifetime.
                    runCatching { while (true) iphone.channel.receivePlain(60_000) }
                    listOf(first, second)
                }
            }
            val hangUpSide = Background("listener that hangs up") { hangsUp.accept().close() }
            val tunnelSide = Background("iPhone tunnel") {
                val socket = listener.accept()
                val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
                protocol.accept(server)
                val link = StreamTransport(protocol.inputStream, protocol.outputStream, "TLS server") {
                    runCatching { protocol.close() }
                    socket.close()
                }
                val request = CdTunnel.readMessage(link, 10_000)
                link.write(
                    CdTunnel.encode(
                        JSONObject()
                            .put("type", "serverHandshakeResponse")
                            .put(
                                "clientParameters",
                                JSONObject().put("address", "fd00::2").put("mtu", 1500).put("netmask", "ffff:ffff:ffff:ffff::")
                            )
                            .put("serverAddress", "fd00::1")
                            .put("serverRSDPort", 58783)
                    )
                )
                peer.set(
                    FakeTunnelPeer(
                        link, deviceAddress, hostAddress,
                        mapOf(
                            58783 to { t: Transport -> rsd.serve(t) },
                            50001 to { t: Transport -> checkInThenEcho(t, checkIn) }
                        )
                    ).start()
                )
                request
            }

            val steps = mutableListOf<String>()
            val tunnel = assertNotNull(
                RemoteTunnel.open("127.0.0.1", identity, "AppleSideload (Test)", control.localPort) { steps += it }
            )
            tunnel.use {
                assertEquals(
                    listOf(
                        "Connecting to remote pairing on 127.0.0.1:${control.localPort}",
                        "Verifying this phone's pairing",
                        "Opening the encrypted tunnel",
                        "Discovering the iPhone's services"
                    ),
                    steps
                )
                assertEquals(CdTunnel.Parameters(hostAddress, deviceAddress, 1500, 58783), it.parameters)
                assertEquals(50001, it.rsd.port(RemoteTunnel.LOCKDOWN))
                assertEquals("27.0", it.rsd.property("OSVersion"))

                val lockdown = it.openService(RemoteTunnel.LOCKDOWN)
                val request = assertNotNull(checkIn.get())
                assertEquals(RemoteTunnel.LABEL, request["Label"]?.asString)
                assertEquals("RSDCheckin", request["Request"]?.asString)
                val message = "through pairing, TLS-PSK, CDTunnel and TCP".toByteArray()
                lockdown.write(message)
                assertContentEquals(message, lockdown.readFully(message.size, 10_000))

                val missing = assertFailsWith<DeviceException> { it.openService("com.apple.not.offered") }
                assertTrue(missing.reason.contains("does not include"), missing.reason)
                assertTrue(it.isAlive)
                lockdown.close()
            }

            hangUpSide.await()
            val cdRequest = tunnelSide.await()
            assertEquals("clientHandshakeRequest", cdRequest.getString("type"))
            assertEquals(CdTunnel.REQUESTED_MTU, cdRequest.getInt("mtu"))
            assertEquals(CipherSuite.TLS_PSK_WITH_AES_256_CBC_SHA384, server.suite)
            assertNotNull(rsd.hello)
            val requests = iphoneSide.await()
            assertEquals(2, requests.size)
            requests.forEach { request ->
                val create = assertNotNull(RpChannel.path(request, "request", "_0", "createListener"), request.toString())
                assertEquals("tcp", create.getString("transportProtocolType"))
            }
            assertClean(peer.get())
        } finally {
            peer.get()?.close()
            control.close()
            hangsUp.close()
            listener.close()
        }
    }

    @Test
    fun `an identity the iPhone forgot opens no tunnel`() {
        ServerSocket(0, 1, loopback).use { control ->
            val iphoneSide = Background("iPhone control") {
                control.accept().use { socket ->
                    val iphone = FakeIphone(TcpTransport.accepted(socket), RpChannel.DEVICE)
                    iphone.answerHandshake()
                    val trusted = iphone.answerVerify(known = null)
                    runCatching { iphone.channel.receivePlain(5_000) } // pairVerifyFailed
                    trusted
                }
            }
            assertNull(RemoteTunnel.open("127.0.0.1", RpPairingFile.generate(), "AppleSideload (Test)", control.localPort))
            assertFalse(iphoneSide.await())
        }
    }

    @Test
    fun `nothing listening on the pairing port is a clear error`() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val error = assertFailsWith<DeviceException> {
            RemoteTunnel.open("127.0.0.1", RpPairingFile.generate(), "AppleSideload (Test)", port)
        }
        assertEquals("connect to 127.0.0.1:$port", error.operation)
        assertNotNull(error.limitation)
    }
}
