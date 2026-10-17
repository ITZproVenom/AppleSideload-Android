package dev.applesideload.sideload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SideloadEngineEntitlementsTest {
    @Test
    fun `free profile gets no more than three LiveContainer keychain groups`() {
        val groups = liveContainerKeychainGroups("ABCDE12345").value.mapNotNull { it.asString }
        assertEquals(3, groups.size)
        assertEquals("ABCDE12345.com.kdt.livecontainer.shared", groups[0])
        assertEquals("ABCDE12345.com.kdt.livecontainer.shared.1", groups[1])
        assertEquals("ABCDE12345.com.kdt.livecontainer.shared.2", groups[2])
        assertTrue(groups.all { it.startsWith("ABCDE12345.") })
    }
}
