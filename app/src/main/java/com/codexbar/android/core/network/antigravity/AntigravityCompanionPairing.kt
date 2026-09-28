package com.codexbar.android.core.network.antigravity

import com.codexbar.android.core.domain.model.Credential
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64
import java.util.UUID

object AntigravityCompanionPairing {
    const val PREFIX = "CBANTIGRAVITY1"
    const val PROTOCOL_VERSION = 1

    fun parse(input: String): Credential.AntigravityCompanionCredential {
        val trimmed = input.trim()
        require(trimmed.length in 1..MAX_PAIRING_CODE_LENGTH) { "Pairing code is too long" }
        val fields = trimmed.split(FIELD_SEPARATOR)
        require(fields.size == FIELD_COUNT && fields.first() == PREFIX) {
            "Invalid pairing code"
        }

        val address = normalizeAndValidateAddress(fields[1])
        val port = fields[2].toIntOrNull()
            ?.takeIf { it in MIN_PORT..MAX_PORT }
            ?: throw IllegalArgumentException("Invalid companion port")
        val companionId = runCatching { UUID.fromString(fields[3]) }
            .getOrElse { throw IllegalArgumentException("Invalid companion ID", it) }
            .toString()
        val sharedKey = fields[4]
        require(sharedKey.matches(BASE64_URL_PATTERN)) { "Invalid pairing key" }
        val decodedKey = runCatching { Base64.getUrlDecoder().decode(sharedKey) }
            .getOrElse { throw IllegalArgumentException("Invalid pairing key", it) }
        require(decodedKey.size == SHARED_KEY_BYTES) { "Invalid pairing key length" }

        return Credential.AntigravityCompanionCredential(
            host = address,
            port = port,
            companionId = companionId,
            sharedKeyBase64Url = sharedKey
        )
    }

    fun validate(credential: Credential.AntigravityCompanionCredential) {
        normalizeAndValidateAddress(credential.host)
        require(credential.port in MIN_PORT..MAX_PORT) { "Invalid companion port" }
        require(UUID.fromString(credential.companionId).toString() == credential.companionId) {
            "Invalid companion ID"
        }
        val key = Base64.getUrlDecoder().decode(credential.sharedKeyBase64Url)
        require(key.size == SHARED_KEY_BYTES) { "Invalid pairing key length" }
    }

    private fun normalizeAndValidateAddress(value: String): String {
        val addressText = value.trim()
        require(addressText.length in 2..MAX_ADDRESS_LENGTH) { "Invalid companion address" }
        val isIpv4 = IPV4_PATTERN.matches(addressText) && addressText.split('.').all {
            it.toIntOrNull() in 0..255
        }
        require(isIpv4) { "Use a numeric private IPv4 address" }

        val address = runCatching { InetAddress.getByName(addressText) }
            .getOrElse { throw IllegalArgumentException("Invalid companion address", it) }
        require(address.isAllowedLocalAddress()) { "Companion must be on the local network" }
        return addressText
    }

    private fun InetAddress.isAllowedLocalAddress(): Boolean {
        if (isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress) return true
        if (this !is Inet4Address) return false
        val octets = address.map { it.toInt() and 0xff }
        return octets[0] == 100 && octets[1] in 64..127
    }

    private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]{43}=?$")
    private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private const val FIELD_SEPARATOR = '|'
    private const val FIELD_COUNT = 5
    private const val SHARED_KEY_BYTES = 32
    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535
    private const val MAX_PAIRING_CODE_LENGTH = 256
    private const val MAX_ADDRESS_LENGTH = 64
}
