package dev.applesideload.sideload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusedAppIdTest {
    @Test
    fun defaultInstallKeepsCurrentStableTeamSuffixedId() {
        assertEquals("com.example.notes.ABCDE12345", targetBundleIdentifier("com.example.notes", "ABCDE12345", null))
        assertEquals("com.example.notes.ABCDE12345", targetBundleIdentifier("com.example.notes.ABCDE12345", "ABCDE12345", null))
    }

    @Test
    fun selectedExplicitAppIdBecomesTheMainBundleIdentifier() {
        assertEquals(
            "com.example.zeus.ABCDE12345",
            targetBundleIdentifier("com.other.notes", "ABCDE12345", "com.example.zeus.ABCDE12345")
        )
    }

    @Test
    fun wildcardAndBlankIdentifiersCannotBeSelectedForReuse() {
        assertTrue(runCatching { targetBundleIdentifier("com.example.notes", "ABCDE12345", "com.example.*") }.isFailure)
        assertTrue(runCatching { targetBundleIdentifier("com.example.notes", "ABCDE12345", " ") }.isFailure)
    }
}
