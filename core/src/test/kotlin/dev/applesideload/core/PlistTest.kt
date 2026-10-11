package dev.applesideload.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlistTest {

    private val sample = Plist.dict(
        "CFBundleIdentifier" to Plist.of("com.example.app"),
        "CFBundleVersion" to Plist.of("12"),
        "MinimumOSVersion" to Plist.of("15.0"),
        "UIDeviceFamily" to Plist.Arr(listOf(Plist.of(1), Plist.of(2))),
        "LSRequiresIPhoneOS" to Plist.of(true),
        "SomeData" to Plist.of(byteArrayOf(1, 2, 3, 4, 5)),
        "Nested" to Plist.dict("deep" to Plist.of(42L))
    )

    @Test
    fun `xml round trip`() {
        val encoded = XmlPlist.write(sample)
        val decoded = PlistReader.parse(encoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun `binary round trip`() {
        val encoded = BinaryPlist.write(sample)
        assertEquals("bplist00", String(encoded.copyOfRange(0, 8)))
        val decoded = PlistReader.parse(encoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun `binary round trip with many objects`() {
        // More than 255 objects, so the offset table has to widen.
        val items = (0 until 400).map { Plist.of("value-$it") }
        val value = Plist.Arr(items)
        val decoded = PlistReader.parse(BinaryPlist.write(value))
        assertEquals(value, decoded)
    }

    @Test
    fun `unicode strings survive both encodings`() {
        val value = Plist.dict("name" to Plist.of("Pokémon · 日本語"))
        assertEquals(value, PlistReader.parse(XmlPlist.write(value)))
        assertEquals(value, PlistReader.parse(BinaryPlist.write(value)))
    }

    @Test
    fun `data elements ignore the whitespace apple writes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>blob</key>
              <data>
              AQIDBAU=
              </data>
            </dict>
            </plist>
        """.trimIndent().toByteArray()
        val parsed = PlistReader.parse(xml)
        assertTrue(byteArrayOf(1, 2, 3, 4, 5).contentEquals(parsed["blob"]?.asData))
    }

    @Test
    fun `a truncated binary plist is rejected rather than guessed at`() {
        val encoded = BinaryPlist.write(sample)
        assertFailsWith<PlistException> {
            BinaryPlist.parse(encoded.copyOfRange(0, 20))
        }
    }

    @Test
    fun `negative integers survive the binary encoding`() {
        val value = Plist.dict("offset" to Plist.of(-5L))
        assertEquals(value, PlistReader.parse(BinaryPlist.write(value)))
    }

    @Test
    fun `base64 round trips every length`() {
        for (size in 0 until 12) {
            val input = ByteArray(size) { (it * 7 + 1).toByte() }
            assertTrue(input.contentEquals(Base64.decode(Base64.encode(input))))
        }
    }

    @Test
    fun `redaction removes credentials and identifiers`() {
        val text = "password: hunter2 for user bestin@example.com udid " +
            "00008030-001A2B3C4D5E6F70"
        val clean = Redaction.apply(text)
        assertTrue("hunter2" !in clean)
        assertTrue("bestin@example.com" !in clean)
        assertTrue("001A2B3C4D5E6F70" !in clean)
    }

    @Test
    fun `a bare key value payload under plist reads as a dictionary`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><key>adsid</key><string>001</string><key>sk</key><data>AAEC</data><key>t</key><dict><key>x</key><integer>7</integer></dict></plist>"""
        val parsed = PlistReader.parse(xml.toByteArray())
        assertEquals("001", parsed["adsid"]?.asString)
        assertEquals(3, parsed["sk"]?.asData?.size)
        assertEquals(7L, parsed["t"]?.get("x")?.asLong)
    }
}
