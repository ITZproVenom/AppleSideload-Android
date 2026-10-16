package dev.applesideload.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssuerChainTest {

    @Test
    fun `an Apple certificate address is tried over https first`() {
        assertEquals(
            listOf("https://certs.apple.com/wwdrg3.der", "http://certs.apple.com/wwdrg3.der"),
            IssuerChain.candidateUrls("http://certs.apple.com/wwdrg3.der")
        )
    }

    @Test
    fun `addresses outside apple dot com are never contacted`() {
        assertTrue(IssuerChain.candidateUrls("http://evil.example/wwdrg3.der").isEmpty())
        assertTrue(IssuerChain.candidateUrls("http://apple.com.evil.example/x.der").isEmpty())
        assertTrue(IssuerChain.candidateUrls("ftp://certs.apple.com/x.der").isEmpty())
        assertTrue(IssuerChain.candidateUrls("not a url").isEmpty())
    }
}
