package dev.applesideload.device

import dev.applesideload.core.Plist
import dev.applesideload.device.remote.PipeTransport
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
