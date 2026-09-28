package com.codexbar.android.core.network.antigravity

import com.codexbar.android.core.domain.model.Credential
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
open class AntigravityCompanionClient @Inject constructor(
    private val json: Json
) {
    private val secureRandom = SecureRandom()

    open suspend fun fetchSnapshot(
        credential: Credential.AntigravityCompanionCredential,
        now: Instant = Instant.now()
    ): AntigravityCompanionSnapshot = withContext(Dispatchers.IO) {
        AntigravityCompanionPairing.validate(credential)
        val masterKey = decodeUrlBase64(credential.sharedKeyBase64Url)
        val authKey = deriveKey(masterKey, AUTH_KEY_CONTEXT)
        val encryptionKey = deriveKey(masterKey, ENCRYPTION_KEY_CONTEXT)
        val nonce = ByteArray(REQUEST_NONCE_BYTES).also(secureRandom::nextBytes).toUrlBase64()
        val requestedAt = now.epochSecond
        val signature = hmacSha256(
            authKey,
            requestCanonical(
                companionId = credential.companionId,
                requestedAtEpochSeconds = requestedAt,
                nonce = nonce
            ).toByteArray(StandardCharsets.UTF_8)
        ).toUrlBase64()
        val request = AntigravityCompanionRequest(
            protocolVersion = PROTOCOL_VERSION,
            companionId = credential.companionId,
            requestedAtEpochSeconds = requestedAt,
            nonce = nonce,
            signature = signature
        )

        val responseLine = exchange(request, credential)

        val envelope = runCatching {
            json.decodeFromString<AntigravityCompanionEnvelope>(responseLine)
        }.getOrElse { throw AntigravityCompanionProtocolException("Invalid companion envelope", it) }
        validateEnvelope(envelope, credential, nonce, now)
        val plaintext = decryptEnvelope(envelope, encryptionKey)
        val snapshot = runCatching {
            json.decodeFromString<AntigravityCompanionSnapshot>(plaintext)
        }.getOrElse { throw AntigravityCompanionProtocolException("Invalid quota snapshot", it) }
        validateSnapshot(snapshot, now)
        snapshot
    }

    private suspend fun exchange(
        request: AntigravityCompanionRequest,
        credential: Credential.AntigravityCompanionCredential
    ): String = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        try {
            val response = socket.use {
                socket.soTimeout = IO_TIMEOUT_MILLIS
                socket.connect(
                    InetSocketAddress(credential.host, credential.port),
                    CONNECT_TIMEOUT_MILLIS
                )
                socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(json.encodeToString(request))
                    writer.newLine()
                    writer.flush()
                    readLimitedLine(socket)
                }
            }
            continuation.resume(response)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private fun readLimitedLine(socket: Socket): String {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        val deadlineNanos = System.nanoTime() + IO_TIMEOUT_MILLIS * 1_000_000L
        while (output.size() <= MAX_RESPONSE_BYTES) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw java.net.SocketTimeoutException("Companion response timed out")
            socket.soTimeout = ((remainingNanos + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
            val next = input.read()
            if (next == -1) throw IOException("Companion closed the connection")
            if (next == '\n'.code) {
                return output.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            }
            output.write(next)
        }
        throw AntigravityCompanionProtocolException("Companion response is too large")
    }

    private fun validateEnvelope(
        envelope: AntigravityCompanionEnvelope,
        credential: Credential.AntigravityCompanionCredential,
        requestNonce: String,
        now: Instant
    ) {
        if (
            envelope.protocolVersion != PROTOCOL_VERSION ||
            envelope.companionId != credential.companionId ||
            envelope.requestNonce != requestNonce ||
            kotlin.math.abs(envelope.sentAtEpochSeconds - now.epochSecond) > MAX_CLOCK_SKEW_SECONDS
        ) {
            throw AntigravityCompanionAuthenticationException(
                "Companion response authentication failed"
            )
        }
    }

    private fun decryptEnvelope(
        envelope: AntigravityCompanionEnvelope,
        encryptionKey: ByteArray
    ): String {
        return try {
            val iv = decodeUrlBase64(envelope.iv)
            if (iv.size != GCM_IV_BYTES) {
                throw AntigravityCompanionAuthenticationException("Invalid companion IV")
            }
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encryptionKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(responseCanonical(envelope).toByteArray(StandardCharsets.UTF_8))
            String(
                cipher.doFinal(decodeUrlBase64(envelope.ciphertext)),
                StandardCharsets.UTF_8
            )
        } catch (error: AntigravityCompanionAuthenticationException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw AntigravityCompanionAuthenticationException(
                "Companion response authentication failed",
                error
            )
        } catch (error: IllegalArgumentException) {
            throw AntigravityCompanionAuthenticationException(
                "Companion response encoding is invalid",
                error
            )
        }
    }

    private fun validateSnapshot(snapshot: AntigravityCompanionSnapshot, now: Instant) {
        if (
            snapshot.schemaVersion != SNAPSHOT_SCHEMA_VERSION ||
            snapshot.source != SNAPSHOT_SOURCE ||
            snapshot.cliVersion.isBlank() ||
            snapshot.cliVersion.length > MAX_TEXT_LENGTH ||
            snapshot.windows.isEmpty() ||
            snapshot.windows.size > MAX_WINDOWS ||
            snapshot.generatedAtEpochSeconds > now.epochSecond + MAX_CLOCK_SKEW_SECONDS ||
            now.epochSecond - snapshot.generatedAtEpochSeconds > MAX_SNAPSHOT_AGE_SECONDS
        ) {
            throw AntigravityCompanionProtocolException("Companion snapshot failed validation")
        }
        snapshot.tier?.let {
            if (it.isBlank() || it.length > MAX_TEXT_LENGTH || it.hasControlCharacter()) {
                throw AntigravityCompanionProtocolException("Invalid companion tier")
            }
        }
        val labels = mutableSetOf<String>()
        snapshot.windows.forEach { window ->
            if (
                window.label.isBlank() ||
                window.label.length > MAX_LABEL_LENGTH ||
                window.label.hasControlCharacter() ||
                !window.usedFraction.isFinite() ||
                window.usedFraction !in 0.0..1.0 ||
                !labels.add(window.label.lowercase()) ||
                window.resetsAtEpochSeconds?.let {
                    it < snapshot.generatedAtEpochSeconds - MAX_CLOCK_SKEW_SECONDS ||
                        it > snapshot.generatedAtEpochSeconds + MAX_RESET_HORIZON_SECONDS
                } == true
            ) {
                throw AntigravityCompanionProtocolException("Invalid companion quota window")
            }
        }
    }

    private fun String.hasControlCharacter(): Boolean = any(Char::isISOControl)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val SNAPSHOT_SCHEMA_VERSION = 1
        const val SNAPSHOT_SOURCE = "antigravity-local-server"
        internal const val AUTH_KEY_CONTEXT = "codexbar-antigravity-auth-v1"
        internal const val ENCRYPTION_KEY_CONTEXT = "codexbar-antigravity-encryption-v1"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val IO_TIMEOUT_MILLIS = 8_000
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val REQUEST_NONCE_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_CLOCK_SKEW_SECONDS = 120L
        private const val MAX_SNAPSHOT_AGE_SECONDS = 2 * 60 * 60L
        private const val MAX_RESET_HORIZON_SECONDS = 31 * 24 * 60 * 60L
        private const val MAX_WINDOWS = 32
        private const val MAX_TEXT_LENGTH = 64
        private const val MAX_LABEL_LENGTH = 64
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_ALGORITHM = "AES"

        internal fun requestCanonical(
            companionId: String,
            requestedAtEpochSeconds: Long,
            nonce: String
        ): String = listOf(
            PROTOCOL_VERSION.toString(),
            companionId,
            requestedAtEpochSeconds.toString(),
            nonce
        ).joinToString("\n")

        internal fun responseCanonical(envelope: AntigravityCompanionEnvelope): String = listOf(
            envelope.protocolVersion.toString(),
            envelope.companionId,
            envelope.requestNonce,
            envelope.sentAtEpochSeconds.toString()
        ).joinToString("\n")

        internal fun deriveKey(masterKey: ByteArray, context: String): ByteArray {
            return hmacSha256(masterKey, context.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun hmacSha256(key: ByteArray, payload: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(payload)
        }

        internal fun ByteArray.toUrlBase64(): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(this)

        internal fun decodeUrlBase64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}

class AntigravityCompanionAuthenticationException(
    message: String,
    cause: Throwable? = null
) : GeneralSecurityException(message, cause)

class AntigravityCompanionProtocolException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
