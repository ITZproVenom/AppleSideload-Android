package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogLevel
import dev.applesideload.core.LogLine
import dev.applesideload.core.Plist
import dev.applesideload.device.remote.PipeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HeartbeatTest {

    private fun marco(interval: Long) = Plist.Dict(
        linkedMapOf("Command" to Plist.Str("Marco"), "Interval" to Plist.Num(interval))
    )

    @Test
    fun `every Marco is answered with Polo until the iPhone goes to sleep`() {
        val (host, device) = PipeTransport.pair()
        val iphone = PlistService(device, "the simulated iPhone's heartbeat", sendBinary = false)
        val heartbeat = Heartbeat.start(PlistService(host, "heartbeat", sendBinary = false), "Test iPhone")
        repeat(3) {
            iphone.send(marco(1))
            assertEquals("Polo", iphone.receive(5_000)["Command"]?.asString)
        }
        iphone.send(Plist.Dict(linkedMapOf("Command" to Plist.Str("SleepyTime"))))
        // SleepyTime ends the exchange: the host closes its end.
        assertEquals(-1, device.read(ByteArray(1), 0, 1, 5_000))
        heartbeat.close()
    }

    @Test
    fun `closing the session ends the heartbeat`() {
        val (host, device) = PipeTransport.pair()
        val iphone = PlistService(device, "the simulated iPhone's heartbeat", sendBinary = false)
        val heartbeat = Heartbeat.start(PlistService(host, "heartbeat", sendBinary = false), "Test iPhone")
        iphone.send(marco(10))
        assertEquals("Polo", iphone.receive(5_000)["Command"]?.asString)
        heartbeat.close()
        assertEquals(-1, device.read(ByteArray(1), 0, 1, 5_000))
    }

    @Test
    fun `an iPhone that closes the heartbeat before any Marco is not reported as a failure`() {
        // iOS 27 over the tunnel opens the service and closes it straight away.
        val (host, device) = PipeTransport.pair()
        val heartbeat = Heartbeat.start(PlistService(host, "heartbeat", sendBinary = false), "Quiet iPhone")
        device.close()
        val line = awaitLine("Quiet iPhone")
        assertEquals(LogLevel.INFO, line.level, line.message)
        assertTrue("closed its heartbeat service as soon as it opened" in line.message, line.message)
        heartbeat.close()
    }

    @Test
    fun `losing a heartbeat that was already being answered is still a warning`() {
        val (host, device) = PipeTransport.pair()
        val iphone = PlistService(device, "the simulated iPhone's heartbeat", sendBinary = false)
        val heartbeat = Heartbeat.start(PlistService(host, "heartbeat", sendBinary = false), "Dropped iPhone")
        iphone.send(marco(10))
        assertEquals("Polo", iphone.receive(5_000)["Command"]?.asString)
        device.close()
        val line = awaitLine("Dropped iPhone")
        assertEquals(LogLevel.WARN, line.level, line.message)
        assertTrue("stopped answering the heartbeat" in line.message, line.message)
        heartbeat.close()
    }

    private fun awaitLine(deviceName: String): LogLine {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Log.lines.value.lastOrNull { it.message.startsWith(deviceName) }?.let { return it }
            Thread.sleep(20)
        }
        fail("nothing was logged about $deviceName")
    }
}
