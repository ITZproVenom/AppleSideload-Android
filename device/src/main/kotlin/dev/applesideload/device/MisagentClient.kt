package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import java.io.Closeable

/** A provisioning profile as the device holds it. */
data class DeviceProfile(val uuid: String, val name: String, val raw: ByteArray) {
    override fun equals(other: Any?): Boolean = other is DeviceProfile && other.uuid == uuid
    override fun hashCode(): Int = uuid.hashCode()
}

/**
 * misagent, which holds the provisioning profiles installed on the device.
 *
 * A free development profile lasts seven days, and the device will happily
 * accumulate one per install, so the profiles are listed and the stale ones
 * removed rather than left to pile up against the limit.
 */
class MisagentClient(private val service: PlistService) : Closeable {

    fun install(profile: ByteArray) {
        val reply = service.request(
            Plist.dict(
                "MessageType" to Plist.Str("Install"),
                "Profile" to Plist.Data(profile),
                "ProfileType" to Plist.Str("Provisioning")
            )
        )
        check(reply, "installing a provisioning profile")
        Log.i(LogTag.INSTALL, "the device accepted the provisioning profile")
    }

    fun copyAll(): List<ByteArray> {
        val reply = service.request(
            Plist.dict(
                "MessageType" to Plist.Str("CopyAll"),
                "ProfileType" to Plist.Str("Provisioning")
            )
        )
        check(reply, "listing provisioning profiles")
        return reply["Payload"]?.asList?.mapNotNull { it.asData }.orEmpty()
    }

    fun remove(uuid: String) {
        val reply = service.request(
            Plist.dict(
                "MessageType" to Plist.Str("Remove"),
                "ProfileID" to Plist.Str(uuid),
                "ProfileType" to Plist.Str("Provisioning")
            )
        )
        check(reply, "removing a provisioning profile")
    }

    override fun close() = service.close()

    private fun check(reply: Plist, operation: String) {
        val status = reply["Status"]?.asInt ?: 0
        if (status != 0) {
            throw DeviceException(
                operation = operation,
                reason = "misagent returned status $status" +
                    (reply["ErrorString"]?.asString?.let { ": $it" } ?: ""),
                limitation = if (status == 2) {
                    "iOS 16 and later refuse profile installation through misagent for " +
                        "profiles that are not signed by a recognised certificate"
                } else {
                    null
                }
            )
        }
    }

    companion object {
        const val SERVICE = "com.apple.misagent"
    }
}
