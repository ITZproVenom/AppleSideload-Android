package dev.applesideload.app.web

import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.UiState
import dev.applesideload.core.LogLine
import dev.applesideload.device.DiscoveredDevice
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.SideloadStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app state as the browser sees it.
 *
 * Only what the page shows is sent: no tokens, keys, pairing records or
 * passwords ever leave the phone through this.
 */
object StateJson {

    fun encode(state: UiState, settings: SettingsSnapshot): JSONObject = JSONObject().apply {
        put("connection", state.connection.name)
        put("busy", state.busy.orNull())
        put("error", state.error.orNull())
        put("notice", state.notice.orNull())
        put("messageSerial", state.messageSerial)
        put("lastMessage", state.lastMessage.orNull())
        put("lastMessageIsError", state.lastMessageIsError)
        put("pairingHint", state.pairingHint.orNull())
        put("transport", state.transport.orNull())
        put("discovered", JSONArray().apply { state.discovered.forEach { put(device(it)) } })
        put("device", state.device?.let { info ->
            JSONObject().apply {
                put("name", info.name)
                put("udid", info.udid)
                put("productType", info.productType)
                put("productVersion", info.productVersion)
                put("buildVersion", info.buildVersion)
                put("cpuArchitecture", info.cpuArchitecture)
                put("deviceClass", info.deviceClass)
                put("wifiAddress", info.wifiAddress.orNull())
            }
        }.orNull())
        put("apps", JSONArray().apply {
            state.apps.forEach { app ->
                put(JSONObject().apply {
                    put("bundleId", app.bundleId)
                    put("name", app.name)
                    put("version", app.version)
                    put("shortVersion", app.shortVersion)
                    put("signer", app.signerIdentity.orNull())
                })
            }
        })
        put("account", state.account?.let { JSONObject().put("appleId", it.appleId) }.orNull())
        put("teams", JSONArray().apply {
            state.teams.forEach { team ->
                put(JSONObject().apply {
                    put("teamId", team.teamId)
                    put("name", team.name)
                    put("type", team.type)
                    put("status", team.status)
                    put("free", team.isFree)
                })
            }
        })
        put("selectedTeamId", state.selectedTeam?.teamId.orNull())
        put("twoFactor", state.twoFactor?.let { prompt ->
            JSONObject().apply {
                put("phoneNumbers", JSONArray().apply {
                    prompt.phoneNumbers.forEach { put(JSONObject().put("id", it.id).put("masked", it.maskedNumber)) }
                })
                put("numberId", prompt.numberId.orNull())
            }
        }.orNull())
        put("selectedIpa", state.selectedIpa?.info?.let { info ->
            JSONObject().apply {
                put("name", info.name)
                put("bundleId", info.bundleId)
                put("version", info.version)
                put("shortVersion", info.shortVersion)
                put("minimumOsVersion", info.minimumOsVersion)
                put("frameworkCount", info.frameworkCount)
                put("hasExtensions", info.hasExtensions)
                put("sizeBytes", info.sizeBytes)
            }
        }.orNull())
        put("step", state.step?.let(::step).orNull())
        put("lastOutcome", state.lastOutcome?.let { outcome ->
            JSONObject().apply {
                put("name", outcome.name)
                put("bundleId", outcome.bundleId)
                put("expiresInDays", outcome.expiresInDays)
                put("special", outcome.special.name)
                put("sideStoreFamily", outcome.special.isSideStoreFamily)
                put("pairingHandedOff", outcome.pairingHandedOff)
            }
        }.orNull())
        put("anisetteServers", JSONArray().apply {
            state.anisetteServers.forEach { put(JSONObject().put("name", it.name).put("address", it.address)) }
        })
        put("sources", JSONArray().apply {
            InstallSource.entries.filter { it != InstallSource.CUSTOM }.forEach {
                put(JSONObject().put("id", it.name).put("title", it.title))
            }
        })
        put("settings", JSONObject().apply {
            put("anisetteAddress", settings.anisetteAddress)
            put("effectiveAnisetteAddress", settings.effectiveAnisetteAddress)
            put("wifiDiscovery", settings.wifiDiscovery)
            put("lastAppleId", settings.lastAppleId)
            put("lastWirelessAddress", settings.lastWirelessAddress)
        })
    }

    fun device(device: DiscoveredDevice): JSONObject = JSONObject().apply {
        put("id", device.id)
        put("name", device.displayName)
        when (device) {
            is DiscoveredDevice.Usb -> put("kind", "usb")
            is DiscoveredDevice.Wifi -> {
                put("kind", "wifi")
                put("address", (device.host.hostAddress ?: device.host.toString()))
            }
        }
    }

    fun step(step: SideloadStep): JSONObject = JSONObject().apply {
        val (kind, label, percent) = when (step) {
            is SideloadStep.Preparing -> Triple("preparing", step.detail, null)
            is SideloadStep.Downloading -> Triple("downloading", "downloading", step.percent)
            is SideloadStep.Account -> Triple("account", step.detail, null)
            is SideloadStep.Signing -> Triple("signing", "signing ${step.bundle}", null)
            is SideloadStep.Uploading -> Triple("uploading", "copying to the iPhone", step.percent)
            is SideloadStep.Installing -> Triple("installing", step.status.lowercase(), step.percent)
            is SideloadStep.HandOff -> Triple("handoff", step.detail, null)
            is SideloadStep.Finished -> Triple("finished", "installed, valid for ${step.expiresInDays} days", 100)
        }
        put("kind", kind)
        put("label", label)
        put("percent", percent.orNull())
    }

    fun logs(lines: List<LogLine>, after: Long, limit: Int = 500): JSONObject {
        val fresh = lines.filter { it.sequence > after }.takeLast(limit)
        return JSONObject().apply {
            put("lines", JSONArray().apply {
                fresh.forEach { line ->
                    put(JSONObject().apply {
                        put("at", line.at)
                        put("sequence", line.sequence)
                        put("level", line.level.name)
                        put("tag", line.tag.label)
                        put("message", line.message)
                    })
                }
            })
            put("latest", fresh.lastOrNull()?.sequence ?: after)
        }
    }

    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
}
