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
    @Test
    fun `bundled Apple developer chain contains WWDR G3 and its root`() {
        val chain = IssuerChain.bundledFallbackChain()
        assertEquals(2, chain.size)
        assertTrue(chain[0].subjectX500Principal.name.contains("Worldwide Developer Relations"))
        assertEquals(chain[0].issuerX500Principal, chain[1].subjectX500Principal)
        chain[0].verify(chain[1].publicKey)
    }
}
