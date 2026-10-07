package dev.applesideload.apple

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import java.util.Locale
import java.util.UUID

data class DeveloperTeam(
    val teamId: String,
    val name: String,
    val type: String,
    val status: String
) {
    /** A free account is the usual case here, and it is the one with limits. */
    val isFree: Boolean get() = type.equals("Individual", ignoreCase = true) &&
        !status.equals("active", ignoreCase = true) ||
        type.equals("Free", ignoreCase = true)
}

data class RegisteredDevice(val deviceId: String, val name: String, val udid: String)

data class DeveloperCertificate(
    val certificateId: String,
    val serialNumber: String,
    val name: String,
    val machineName: String?,
    val data: ByteArray,
    /** The machine id the certificate was requested with; AltStore uses it as the p12 password. */
    val machineId: String? = null
) {
    override fun equals(other: Any?): Boolean =
        other is DeveloperCertificate && other.certificateId == certificateId

    override fun hashCode(): Int = certificateId.hashCode()
}

data class DeveloperAppId(
    val appIdId: String,
    val identifier: String,
    val name: String,
    val expiration: Long?
)

data class AppGroup(val groupId: String, val identifier: String, val name: String)

/** Raised when the developer services refuse. */
class DeveloperServiceException(
    val resultCode: Int,
    val detail: String,
    val limitation: String? = null,
    val alternative: String? = null
) : Exception(
    buildString {
        append("Apple's developer service refused: ")
        append(detail)
        if (resultCode != 0) append(" (result $resultCode)")
        if (limitation != null) append(". Limitation: $limitation")
        if (alternative != null) append(". Alternative: $alternative")
    }
)

/**
 * The developer services Xcode uses to issue a development certificate.
 *
 * Free accounts can do everything sideloading needs here: register the
 * device, have a certificate signed from a certificate request, create an
 * app identifier, and download the provisioning profile that ties them
 * together. The limits are Apple's - ten app identifiers a week, three apps
 * installed at a time, seven day profiles - and are reported as limits
 * rather than worked around.
 */
class DeveloperSession(
    private var session: AppleSession,
    private val anisette: AnisetteProvider,
    private val auth: AppleAuth,
    private val http: Http = Http()
) {

    val account: AppleSession get() = session

    fun listTeams(): List<DeveloperTeam> =
        post("listTeams.action", null, emptyMap())["teams"]?.asList.orEmpty().mapNotNull { entry ->
            val id = entry["teamId"]?.asString ?: return@mapNotNull null
            DeveloperTeam(
                teamId = id,
                name = entry["name"]?.asString ?: id,
                type = entry["type"]?.asString.orEmpty(),
                status = entry["status"]?.asString.orEmpty()
            )
        }

    fun listDevices(teamId: String): List<RegisteredDevice> =
        post("ios/listDevices.action", teamId, emptyMap())["devices"]?.asList.orEmpty()
            .mapNotNull { entry ->
                val udid = entry["deviceNumber"]?.asString ?: return@mapNotNull null
                RegisteredDevice(
                    deviceId = entry["deviceId"]?.asString.orEmpty(),
                    name = entry["name"]?.asString ?: udid,
                    udid = udid
                )
            }

    /** Registers the iPhone, which Apple requires before a profile will include it. */
    fun registerDevice(teamId: String, udid: String, name: String): RegisteredDevice {
        listDevices(teamId).firstOrNull { it.udid.equals(udid, ignoreCase = true) }?.let {
            return it
        }
        val reply = post(
            "ios/addDevice.action",
            teamId,
            mapOf("deviceNumber" to Plist.Str(udid), "name" to Plist.Str(name))
        )
        val device = reply["device"]
        Log.i(LogTag.APPLE, "registered this iPhone with the team")
        return RegisteredDevice(
            deviceId = device?.get("deviceId")?.asString.orEmpty(),
            name = device?.get("name")?.asString ?: name,
            udid = device?.get("deviceNumber")?.asString ?: udid
        )
    }

    fun listCertificates(teamId: String): List<DeveloperCertificate> =
        post("ios/listAllDevelopmentCerts.action", teamId, emptyMap())["certificates"]
            ?.asList.orEmpty().mapNotNull { entry ->
                val data = entry["certContent"]?.asData ?: return@mapNotNull null
                DeveloperCertificate(
                    certificateId = entry["certificateId"]?.asString.orEmpty(),
                    serialNumber = entry["serialNumber"]?.asString.orEmpty(),
                    name = entry["name"]?.asString.orEmpty(),
                    machineName = entry["machineName"]?.asString,
                    data = data,
                    machineId = entry["machineId"]?.asString
                )
            }

    /**
     * Has a certificate signing request signed.
     *
     * A free account may hold one development certificate at a time, so the
     * caller usually revokes the old one first. That is a destructive step
     * for anything else signed with it, which is why it is not done here.
     */
    fun submitCertificateRequest(teamId: String, csrPem: String, machineName: String): DeveloperCertificate {
        val machineId = UUID.randomUUID().toString().uppercase()
        val reply = post(
            "ios/submitDevelopmentCSR.action",
            teamId,
            mapOf(
                "csrContent" to Plist.Str(csrPem),
                "machineId" to Plist.Str(machineId),
                "machineName" to Plist.Str(machineName)
            )
        )
        val request = reply["certRequest"]
            ?: throw DeveloperServiceException(0, "Apple returned no certificate")
        return DeveloperCertificate(
            certificateId = request["certificateId"]?.asString.orEmpty(),
            serialNumber = request["serialNumber"]?.asString.orEmpty(),
            name = request["name"]?.asString.orEmpty(),
            machineName = machineName,
            data = request["certContent"]?.asData
                ?: throw DeveloperServiceException(0, "the signed certificate was empty"),
            machineId = request["machineId"]?.asString ?: machineId
        )
    }

    fun revokeCertificate(teamId: String, serialNumber: String) {
        post(
            "ios/revokeDevelopmentCert.action",
            teamId,
            mapOf("serialNumber" to Plist.Str(serialNumber))
        )
        Log.i(LogTag.APPLE, "revoked a development certificate")
    }

    fun listAppIds(teamId: String): List<DeveloperAppId> =
        post("ios/listAppIds.action", teamId, emptyMap())["appIds"]?.asList.orEmpty()
            .mapNotNull { entry ->
                val identifier = entry["identifier"]?.asString ?: return@mapNotNull null
                DeveloperAppId(
                    appIdId = entry["appIdId"]?.asString.orEmpty(),
                    identifier = identifier,
                    name = entry["name"]?.asString ?: identifier,
                    expiration = entry["expirationDate"]?.let { value ->
                        (value as? Plist.Stamp)?.date?.time
                    }
                )
            }

    fun addAppId(teamId: String, identifier: String, name: String): DeveloperAppId {
        val reply = post(
            "ios/addAppId.action",
            teamId,
            mapOf(
                "identifier" to Plist.Str(identifier),
                // Apple rejects names with anything but letters, digits and spaces.
                "name" to Plist.Str(name.filter { it.isLetterOrDigit() || it == ' ' }.ifBlank { "App" })
            )
        )
        val appId = reply["appId"]
            ?: throw DeveloperServiceException(0, "Apple returned no app identifier")
        return DeveloperAppId(
            appIdId = appId["appIdId"]?.asString.orEmpty(),
            identifier = appId["identifier"]?.asString ?: identifier,
            name = appId["name"]?.asString ?: name,
            expiration = null
        )
    }

    fun deleteAppId(teamId: String, appIdId: String) {
        post("ios/deleteAppId.action", teamId, mapOf("appIdId" to Plist.Str(appIdId)))
    }

    /** Turns entitlements on for an app identifier, one feature at a time. */
    fun updateAppIdFeatures(teamId: String, appIdId: String, features: Map<String, Plist>) {
        if (features.isEmpty()) return
        post(
            "ios/updateAppId.action",
            teamId,
            mapOf("appIdId" to Plist.Str(appIdId)) + features
        )
    }

    fun listAppGroups(teamId: String): List<AppGroup> =
        post("ios/listApplicationGroups.action", teamId, emptyMap())["applicationGroupList"]
            ?.asList.orEmpty().mapNotNull { entry ->
                val identifier = entry["identifier"]?.asString ?: return@mapNotNull null
                AppGroup(
                    groupId = entry["applicationGroup"]?.asString.orEmpty(),
                    identifier = identifier,
                    name = entry["name"]?.asString ?: identifier
                )
            }

    fun addAppGroup(teamId: String, identifier: String, name: String): AppGroup {
        val reply = post(
            "ios/addApplicationGroup.action",
            teamId,
            mapOf("identifier" to Plist.Str(identifier), "name" to Plist.Str(name))
        )
        val group = reply["applicationGroup"]
            ?: throw DeveloperServiceException(0, "Apple returned no app group")
        return AppGroup(
            groupId = group["applicationGroup"]?.asString.orEmpty(),
            identifier = group["identifier"]?.asString ?: identifier,
            name = group["name"]?.asString ?: name
        )
    }

    /** Finds the app group with [identifier], creating it if the account has none. */
    fun ensureAppGroup(teamId: String, identifier: String, name: String): AppGroup =
        listAppGroups(teamId).firstOrNull { it.identifier == identifier }
            ?: addAppGroup(teamId, identifier, name.filter { it.isLetterOrDigit() || it == ' ' }.ifBlank { "App Group" })

    /** Turns on the App Groups capability, which a group can only be assigned with. */
    fun enableAppGroups(teamId: String, appIdId: String) =
        updateAppIdFeatures(teamId, appIdId, mapOf(FEATURE_APP_GROUPS to Plist.Bool(true)))

    fun assignAppGroup(teamId: String, appIdId: String, groupId: String) {
        post(
            "ios/assignApplicationGroupToAppId.action",
            teamId,
            mapOf(
                "appIdId" to Plist.Str(appIdId),
                "applicationGroups" to Plist.Str(groupId)
            )
        )
    }

    /**
     * Downloads the provisioning profile for an app identifier.
     *
     * This is the file that gets embedded in the bundle as
     * embedded.mobileprovision, and it is what the device checks the
     * signature against. On a free account it is valid for seven days.
     */
    fun downloadProvisioningProfile(teamId: String, appIdId: String): ByteArray {
        val reply = post(
            "ios/downloadTeamProvisioningProfile.action",
            teamId,
            mapOf("appIdId" to Plist.Str(appIdId))
        )
        return reply["provisioningProfile"]?.get("encodedProfile")?.asData
            ?: throw DeveloperServiceException(
                0,
                "Apple returned no provisioning profile for this app identifier"
            )
    }

    // MARK: - Transport

    private fun post(action: String, teamId: String?, parameters: Map<String, Plist>): Plist {
        val token = session.developerToken ?: run {
            session = auth.fetchAppToken(session)
            session.developerToken
        } ?: throw DeveloperServiceException(
            0,
            "no developer token is available for this account"
        )

        val fields = linkedMapOf<String, Plist>(
            "clientId" to Plist.Str(CLIENT_ID),
            "protocolVersion" to Plist.Str(PROTOCOL),
            "requestId" to Plist.Str(UUID.randomUUID().toString().uppercase()),
            "userLocale" to Plist.Arr(listOf(Plist.Str(Locale.getDefault().toLanguageTag())))
        )
        teamId?.let { fields["teamId"] = Plist.Str(it) }
        fields.putAll(parameters)

        val data = anisette.fetch()
        val headers = buildMap {
            put("Content-Type", "text/x-xml-plist")
            put("Accept", "text/x-xml-plist")
            put("User-Agent", "Xcode")
            put("X-Apple-I-Identity-Id", session.adsid)
            put("X-Apple-GS-Token", token)
            put("X-Mme-Client-Info", CLIENT_INFO)
            putAll(data.headers())
        }

        val reply = http.plist(
            url = "$BASE$action?clientId=$CLIENT_ID",
            request = Plist.Dict(fields),
            headers = headers
        )
        val result = reply["resultCode"]?.asInt ?: 0
        if (result != 0) throw translate(result, reply)
        return reply
    }

    private fun translate(result: Int, reply: Plist): DeveloperServiceException {
        val detail = reply["userString"]?.asString
            ?: reply["resultString"]?.asString
            ?: "no further detail was given"
        return when (result) {
            3200 -> DeveloperServiceException(
                result,
                detail,
                limitation = "this account is not enrolled in the developer programme at all"
            )

            9401 -> DeveloperServiceException(
                result,
                detail,
                limitation = "a free account may register a limited number of devices each year",
                alternative = "wait for the yearly allowance to reset, or use a paid account"
            )

            3101, 9412 -> DeveloperServiceException(
                result,
                detail,
                limitation = "a free account may create ten app identifiers in a seven day " +
                    "window",
                alternative = "remove an unused identifier in the Account screen, or wait for " +
                    "the window to pass"
            )

            7460 -> DeveloperServiceException(
                result,
                detail,
                limitation = "the certificate limit for this account has been reached",
                alternative = "revoke the existing development certificate in the Account screen"
            )

            else -> DeveloperServiceException(result, detail)
        }
    }

    private companion object {
        const val BASE = "https://developerservices2.apple.com/services/QH65B2/"
        const val CLIENT_ID = "XABBG36SBA"
        const val PROTOCOL = "QH65B2"
        const val FEATURE_APP_GROUPS = "APG3427HIY"
        const val CLIENT_INFO =
            "<MacBookPro13,2> <Mac OS X;10.15.2;19C57> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>"
    }
}
