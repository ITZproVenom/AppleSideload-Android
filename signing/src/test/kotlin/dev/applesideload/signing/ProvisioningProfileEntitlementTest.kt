package dev.applesideload.signing

import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningProfileEntitlementTest {
    private fun profile(appIdentifier: String, team: String = "ABCDE12345") = ProvisioningProfile(
        XmlPlist.write(
            Plist.dict(
                "UUID" to Plist.Str("profile-test"),
                "TeamIdentifier" to Plist.Arr(listOf(Plist.Str(team))),
                "Entitlements" to Plist.dict("application-identifier" to Plist.Str(appIdentifier))
            )
        )
    )

    @Test
    fun exactProfileMustMatchTheBundleIdAndTeam() {
        assertTrue(profile("ABCDE12345.com.example.app").authorizes("com.example.app", "ABCDE12345"))
        assertFalse(profile("ABCDE12345.com.example.app").authorizes("com.example.other", "ABCDE12345"))
        assertFalse(profile("ABCDE12345.com.example.app").authorizes("com.example.app", "ZZZZZ99999"))
    }

    @Test
    fun wildcardProfileMatchesOnlyItsDotDelimitedSuffix() {
        val p = profile("ABCDE12345.com.example.*")
        assertTrue(p.authorizes("com.example.zeus", "ABCDE12345"))
        assertFalse(p.authorizes("com.examples.zeus", "ABCDE12345"))
        assertFalse(p.authorizes("com.example", "ABCDE12345"))
        assertFalse(profile("ABCDE12345.com.*.app").authorizes("com.test.app", "ABCDE12345"))
    }

    @Test
    fun legacyAppIdPrefixMayDifferFromTeamIdentifier() {
        assertTrue(profile("LEGACY12345.com.example.app").authorizes("com.example.app", "ABCDE12345"))
    }
}
