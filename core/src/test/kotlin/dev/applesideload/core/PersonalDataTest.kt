package dev.applesideload.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalDataTest {

    @Test
    fun `names learnt at run time are left out, whatever the case`() {
        PersonalData.remember("Ahmed's iPhone", "[iPhone name]")
        PersonalData.rememberTeam("Jane Doe (Personal Team)", "AB12CD34EF")
        assertEquals(
            "Connecting to [iPhone name]; team [team] ([team-id]); [name] signed in; com.example.app.[team-id]",
            PersonalData.scrub(
                "Connecting to ahmed's iphone; team Jane Doe (Personal Team) (AB12CD34EF); " +
                    "Jane Doe signed in; com.example.app.AB12CD34EF"
            )
        )
    }

    @Test
    fun `the names every iPhone starts with are not treated as personal`() {
        PersonalData.remember("iPhone", "[iPhone name]")
        PersonalData.remember("ab", "[x]")
        assertEquals("the iPhone answered ab", PersonalData.scrub("the iPhone answered ab"))
    }
}
