package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import java.io.Closeable

/**
 * com.apple.mobile.heartbeat, answered on a thread of its own.
 *
 * The iPhone sends "Marco" with the number of seconds to the next one, and
 * the host answers "Polo". Over the network iOS closes a host's service
 * connections once nobody answers ("iOS automatically closes service
 * connections if there is no heartbeat client connected and responding",
 * idevice's heartbeat service), which is why SideStore's minimuxer keeps one
 * running. "SleepyTime" means the iPhone is going to sleep, which ends it.
 *
 * Over the cable usbmuxd stands in for the host, so only Wi-Fi sessions,
 * plain and tunnelled, run one.
 */
class Heartbeat private constructor(
    private val service: PlistService,
    private val deviceName: String
) : Closeable {

    @Volatile private var closed = false

    private fun loop() {
        var waitMs = FIRST_WAIT_MS
        var answered = 0
        try {
            while (!closed) {
                val message = service.receive(waitMs)
                if (message["Command"]?.asString == SLEEPY_TIME) {
                    Log.i(LogTag.LOCKDOWN, "$deviceName is going to sleep, so its Wi-Fi connection will drop")
                    return
                }
                val interval = message["Interval"]?.asLong?.takeIf { it in 1..MAX_INTERVAL_S } ?: DEFAULT_INTERVAL_S
                service.send(POLO)
                answered++
                waitMs = ((interval + GRACE_S) * 1_000).toInt()
            }
        } catch (error: Exception) {
            when {
                closed -> Unit
                // Seen with iOS 27 over the tunnel: the service is opened and
                // closed again before any Marco, and everything else keeps
                // working. Nothing was lost, so it is not a warning.
                answered == 0 && error is java.io.EOFException -> Log.i(
                    LogTag.LOCKDOWN,
                    "$deviceName closed its heartbeat service as soon as it opened; the session goes on without it"
                )
                else -> Log.w(LogTag.LOCKDOWN, "$deviceName stopped answering the heartbeat: ${Log.describe(error)}")
            }
        } finally {
            runCatching { service.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { service.close() }
    }

    companion object {
        const val SERVICE = "com.apple.mobile.heartbeat"

        /** The first Marco comes as soon as the service starts; this is generous. */
        private const val FIRST_WAIT_MS = 30_000
        private const val DEFAULT_INTERVAL_S = 10L
        private const val MAX_INTERVAL_S = 600L

        /** How much later than announced a Marco may come, as idevice allows. */
        private const val GRACE_S = 5L
        private const val SLEEPY_TIME = "SleepyTime"
        private val POLO = Plist.Dict(linkedMapOf("Command" to Plist.Str("Polo")))

        /** Answers the heartbeat on [service] until it ends or [close] is called. */
        fun start(service: PlistService, deviceName: String): Heartbeat {
            val heartbeat = Heartbeat(service, deviceName)
            Thread(heartbeat::loop, "heartbeat").apply {
                isDaemon = true
                start()
            }
            return heartbeat
        }
    }
}
