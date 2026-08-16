package com.lsd.wififrankenstein.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HandshakeParserTest {

    private fun resource(name: String): File {
        val url = javaClass.classLoader?.getResource(name)
            ?: throw IllegalStateException("missing test resource: $name")
        return File(url.toURI())
    }

    private fun crackableEapolHashes(file: File, password: String): List<HandshakeHash> {
        val hashes = HandshakeParser.parseFile(file)
        assertTrue(
            "${file.name}: expected EAPOL hashes, got: $hashes",
            hashes.any { it.type == HandshakeType.EAPOL }
        )
        val eapol = hashes.filter { it.type == HandshakeType.EAPOL }
        val crackable = eapol.filter { WpaCracker.tryPassword(password, it).found }
        assertTrue(
            "${file.name}: no EAPOL hash is crackable with '$password' (parsed=${eapol.size})",
            crackable.isNotEmpty()
        )
        return crackable
    }

    @Test
    fun `wpa2-psk-linksys cap parses to crackable EAPOL hash`() {
        val hashes = HandshakeParser.parseFile(resource("wpa2-psk-linksys.cap"))
        assertTrue(hashes.any { it.type == HandshakeType.EAPOL })
        val eapol = hashes.first { it.type == HandshakeType.EAPOL }
        assertTrue(WpaCracker.tryPassword("dictionary", eapol).found)
    }

    @Test
    fun `wpa-psk-linksys cap (TKIP) parses to crackable EAPOL hash`() {
        val hashes = HandshakeParser.parseFile(resource("wpa-psk-linksys.cap"))
        assertTrue(hashes.any { it.type == HandshakeType.EAPOL })
        val eapol = hashes.first { it.type == HandshakeType.EAPOL }
        assertTrue(WpaCracker.tryPassword("dictionary", eapol).found)
    }

    @Test
    fun `wpa2 eapol cap parses to crackable EAPOL hash`() {
        val hashes = HandshakeParser.parseFile(resource("wpa2.eapol.cap"))
        assertTrue(hashes.any { it.type == HandshakeType.EAPOL })
        val eapol = hashes.first { it.type == HandshakeType.EAPOL }
        assertTrue(WpaCracker.tryPassword("12345678", eapol).found)
    }

    @Test
    fun `MOM1 cap parses to crackable EAPOL hash`() {
        val hashes = HandshakeParser.parseFile(resource("MOM1.cap"))
        assertTrue(hashes.any { it.type == HandshakeType.EAPOL })
        val eapol = hashes.first { it.type == HandshakeType.EAPOL }
        assertTrue(WpaCracker.tryPassword("MOM12345", eapol).found)
    }

    @Test
    fun `all EAPOL hashes from reference captures are crackable`() {
        crackableEapolHashes(resource("wpa2-psk-linksys.cap"), "dictionary")
        crackableEapolHashes(resource("wpa-psk-linksys.cap"), "dictionary")
        crackableEapolHashes(resource("wpa2.eapol.cap"), "12345678")
        crackableEapolHashes(resource("MOM1.cap"), "MOM12345")
    }

    @Test
    fun `pmkid test pcap parses to PMKID hash`() {
        val hashes = HandshakeParser.parseFile(resource("test-pmkid.pcap"))
        assertTrue(
            "expected a PMKID hash, got: $hashes",
            hashes.any { it.type == HandshakeType.PMKID }
        )
    }

    @Test
    fun `testm1m2m3 pcap parses to EAPOL hashes`() {
        val hashes = HandshakeParser.parseFile(resource("testm1m2m3.pcap"))
        assertTrue(
            "expected EAPOL hashes, got: $hashes",
            hashes.any { it.type == HandshakeType.EAPOL }
        )
    }

    @Test
    fun `reference captures are detected as PCAP format`() {
        for (name in listOf(
            "wpa2-psk-linksys.cap",
            "wpa-psk-linksys.cap",
            "wpa2.eapol.cap",
            "MOM1.cap"
        )) {
            assertEquals(name, HandshakeFormat.PCAP, HandshakeHash.detectFileFormat(resource(name)))
        }
    }
}
