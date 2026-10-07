package dev.applesideload.device

import dev.applesideload.core.Bytes
import org.junit.After
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MuxDevice] against [FakeMuxPhone]: the framing usbmuxd uses, the TCP
 * handshake, flow control in both directions, and every way a connection or
 * the link can end.
 */
class MuxDeviceTest {

    private val opened = mutableListOf<AutoCloseable>()

    private fun phone(
        major: Long = 2,
        stale: Int = 0,
        buffer: Int = 131_072
    ): Pair<FakeMuxPhone, MuxDevice> {
        val phone = FakeMuxPhone(major, stale, buffer)
        val mux = MuxDevice(phone.host)
        opened += mux
        opened += phone
        mux.start()
        return phone to mux
    }

    @After
    fun tearDown() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private fun echo(socket: FakeMuxPhone.PhoneSocket) {
        while (true) {
            val chunk = socket.read(1, timeoutMs = 30_000) ?: return
            socket.send(chunk)
        }
    }

    private fun Transport.readExactly(length: Int): ByteArray = readFully(length, 20_000)

    @Test
    fun versionExchangeUsesTheShortHeaderAndSetupResetsCounters() {
        val (phone, _) = phone()
        val version = assertNotNull(phone.versionRequest)
        assertEquals(20, version.size)
        assertEquals(0L, Bytes.u32be(version, 0))
        assertEquals(20L, Bytes.u32be(version, 4))
        assertEquals(2L, Bytes.u32be(version, 8))
        waitFor { phone.setupPacket != null }
        val setup = phone.setupPacket!!
        assertEquals(2L, Bytes.u32be(setup, 0))
        assertEquals(17L, Bytes.u32be(setup, 4))
        assertEquals(0xfeedfaceL, Bytes.u32be(setup, 8))
        assertEquals(0, Bytes.u16be(setup, 12))
        assertEquals(0xFFFF, Bytes.u16be(setup, 14))
        assertEquals(0x07, setup[16].toInt())
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun connectsAndEchoesOverVersion2() {
        val (phone, mux) = phone()
        phone.listen(62078) { echo(it) }
        val connection = mux.connect(62078)
        val message = "hello lockdown".toByteArray()
        connection.write(message)
        assertContentEquals(message, connection.readExactly(message.size))
        connection.close()
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun version1PhonesUseTheShortHeaderThroughout() {
        val (phone, mux) = phone(major = 1)
        phone.listen(62078) { echo(it) }
        val connection = mux.connect(62078)
        connection.write(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), connection.readExactly(3))
        assertEquals(null, phone.setupPacket)
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun unknownVersionIsReported() {
        val phone = FakeMuxPhone(replyMajor = 7)
        opened += phone
        val mux = MuxDevice(phone.host)
        opened += mux
        val error = assertFailsWith<DeviceException> { mux.start() }
        assertTrue("7.0" in error.reason, error.reason)
    }

    @Test
    fun packetsLeftFromAnEarlierSessionAreSkipped() {
        val (phone, mux) = phone(stale = 3)
        phone.listen(1234) { echo(it) }
        val connection = mux.connect(1234)
        connection.write(byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), connection.readExactly(1))
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun refusedPortsAreReportedAsRefused() {
        val (_, mux) = phone()
        val error = assertFailsWith<DeviceException> { mux.connect(5555) }
        assertTrue("refused" in error.reason, error.reason)
    }

    @Test
    fun dataGoesOutWithAckAloneAndConnectionsEndWithReset() {
        val (phone, mux) = phone()
        var socket: FakeMuxPhone.PhoneSocket? = null
        phone.listen(62078) { socket = it; it.read(4) }
        val connection = mux.connect(62078)
        connection.write(byteArrayOf(1, 2, 3, 4))
        waitFor { socket != null }
        connection.close()
        assertTrue(socket!!.awaitHostReset())
        // SYN, then ACK alone until the RST that ends it. Anything the phone
        // still sends for the closed connection is answered with RST too,
        // as usbmuxd does.
        val flags = phone.hostFlags.toList()
        val firstReset = flags.indexOf(FakeMuxPhone.FLAG_RST)
        assertEquals(FakeMuxPhone.FLAG_SYN, flags.first())
        assertTrue(firstReset > 1, flags.toString())
        assertTrue(flags.subList(1, firstReset).all { it == FakeMuxPhone.FLAG_ACK }, flags.toString())
        assertTrue(flags.drop(firstReset).all { it == FakeMuxPhone.FLAG_RST }, flags.toString())
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun largeWritesWaitForThePhonesWindow() {
        // A 4 KiB phone buffer and a service that reads slowly: the host has
        // to stop at the window and resume only when the phone opens it.
        val (phone, mux) = phone(buffer = 4096)
        val payload = Random(1).nextBytes(256 * 1024)
        val received = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        phone.listen(9000) { socket ->
            while (received.size() < payload.size) {
                val chunk = socket.read(minOf(1000, payload.size - received.size())) ?: break
                received.write(chunk)
                if (received.size() % 50_000 < 1000) Thread.sleep(5)
            }
            done.countDown()
        }
        val connection = mux.connect(9000)
        connection.write(payload, 30_000)
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertContentEquals(payload, received.toByteArray())
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun aSlowReaderClosesTheWindowAndReopensItWithoutLosingData() {
        val (phone, mux) = phone()
        val payload = Random(2).nextBytes(1024 * 1024)
        var socket: FakeMuxPhone.PhoneSocket? = null
        phone.listen(9001) { socket = it; it.send(payload) }
        val connection = mux.connect(9001)
        // Read nothing for a while: the phone must run into a closed window.
        waitFor { socket != null }
        Thread.sleep(300)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(7000)
        while (out.size() < payload.size) {
            val read = connection.read(buffer, 0, minOf(buffer.size, payload.size - out.size()), 20_000)
            assertTrue(read > 0)
            out.write(buffer, 0, read)
        }
        assertContentEquals(payload, out.toByteArray())
        assertTrue(socket!!.minimumHostWindow < 16 * 1024, "window never closed: ${socket!!.minimumHostWindow}")
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun anIdleLinkStaysUp() {
        val (phone, mux) = phone()
        phone.listen(62078) { echo(it) }
        Thread.sleep(2_500)
        val connection = mux.connect(62078)
        connection.write(byteArrayOf(5))
        assertContentEquals(byteArrayOf(5), connection.readExactly(1))
    }

    @Test
    fun aResetFromThePhoneFailsReadsAndWrites() {
        val (phone, mux) = phone()
        phone.listen(7000) { socket -> socket.send(byteArrayOf(1, 2)); socket.reset() }
        val connection = mux.connect(7000)
        assertContentEquals(byteArrayOf(1, 2), connection.readExactly(2))
        val error = assertFailsWith<IOException> { connection.readExactly(1) }
        assertTrue("reset" in error.message.orEmpty(), error.message)
        assertFailsWith<IOException> { connection.write(byteArrayOf(1)) }
    }

    @Test
    fun aFinFromThePhoneEndsTheStreamAfterItsData() {
        val (phone, mux) = phone()
        var socket: FakeMuxPhone.PhoneSocket? = null
        phone.listen(7001) { socket = it; it.send("bye".toByteArray()); it.finish() }
        val connection = mux.connect(7001)
        assertContentEquals("bye".toByteArray(), connection.readExactly(3))
        assertEquals(-1, connection.read(ByteArray(1), 0, 1, 5_000))
        assertTrue(socket!!.awaitHostReset())
    }

    @Test
    fun repeatedSegmentsAreDeliveredOnce() {
        val (phone, mux) = phone()
        phone.listen(7002) { socket ->
            val first = "abc".toByteArray()
            socket.send(first)
            socket.repeatLast(first)
            socket.send("def".toByteArray())
        }
        val connection = mux.connect(7002)
        assertContentEquals("abcdef".toByteArray(), connection.readExactly(6))
    }

    @Test
    fun theHostEchoesThePhonesRxCounter() {
        val (phone, mux) = phone()
        phone.phoneRxField = 0x4242
        phone.listen(7003) { echo(it) }
        val connection = mux.connect(7003)
        connection.write(byteArrayOf(1))
        connection.readExactly(1)
        connection.write(byteArrayOf(2))
        connection.readExactly(1)
        assertEquals(0x4242, phone.lastHostRx)
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun manyConnectionsAtOnceStayApart() {
        val (phone, mux) = phone()
        phone.listen(62078) { echo(it) }
        val threads = (0 until 8).map { index ->
            Thread {
                val connection = mux.connect(62078)
                val data = Random(index).nextBytes(100_000)
                val reader = Thread { assertContentEquals(data, connection.readExactly(data.size)) }
                reader.start()
                connection.write(data, 30_000)
                reader.join(30_000)
                connection.close()
            }.apply { start() }
        }
        threads.forEach { it.join(60_000) }
        assertEquals(emptyList(), phone.violations)
    }

    @Test
    fun unpluggingFailsOpenConnectionsAndNewOnes() {
        val (phone, mux) = phone()
        phone.listen(62078) { echo(it) }
        val connection = mux.connect(62078)
        phone.unplug()
        assertFailsWith<IOException> { connection.readExactly(1) }
        val error = assertFailsWith<DeviceException> { mux.connect(62078) }
        assertTrue("failed" in error.reason, error.reason)
    }

    @Test
    fun closingTheMuxClosesTheTransport() {
        val (phone, mux) = phone()
        mux.close()
        assertTrue(phone.hostClosedTransport)
        assertFailsWith<DeviceException> { mux.connect(62078) }
    }

    @Test
    fun controlMessagesDoNotDisturbTheStream() {
        val (phone, mux) = phone()
        phone.listen(62078) { echo(it) }
        phone.sendControl(7, "hello from the fake phone")
        phone.sendControl(3, "a pretend error")
        val connection = mux.connect(62078)
        connection.write(byteArrayOf(4))
        assertContentEquals(byteArrayOf(4), connection.readExactly(1))
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting")
            Thread.sleep(5)
        }
    }
}
