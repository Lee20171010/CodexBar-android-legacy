package com.codexbar.android.core.network.antigravity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AntigravityCompanionPairingTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val id = "5b017391-6dc4-4ab7-b0ad-2255dada62d7"

    @Test
    fun `parses a non browsable private network pairing code`() {
        val credential = AntigravityCompanionPairing.parse(
            "CBANTIGRAVITY1|192.168.1.24|43824|$id|$key"
        )

        assertEquals("192.168.1.24", credential.host)
        assertEquals(43824, credential.port)
        assertEquals(id, credential.companionId)
        assertEquals(key, credential.sharedKeyBase64Url)
    }

    @Test
    fun `accepts Tailscale addresses and rejects a Claude pairing`() {
        assertEquals("100.74.102.71", AntigravityCompanionPairing.parse(
            "CBANTIGRAVITY1|100.74.102.71|43824|$id|$key"
        ).host)
        assertThrows(IllegalArgumentException::class.java) {
            AntigravityCompanionPairing.parse("CBCLAUDE1|100.74.102.71|43823|$id|$key")
        }
    }

    @Test
    fun `rejects DNS public URL and extra pairing fields`() {
        listOf(
            "CBANTIGRAVITY1|example.com|43824|$id|$key",
            "CBANTIGRAVITY1|8.8.8.8|43824|$id|$key",
            "codexbar://antigravity-pair?v=1&address=127.0.0.1&port=43824&id=$id&key=$key",
            "CBANTIGRAVITY1|127.0.0.1|43824|$id|$key|extra"
        ).forEach { pairing ->
            assertThrows(IllegalArgumentException::class.java) {
                AntigravityCompanionPairing.parse(pairing)
            }
        }
    }
}
