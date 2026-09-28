package com.codexbar.android.core.network.antigravity

import com.codexbar.android.core.domain.model.Credential
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AntigravityCompanionClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val masterKey = ByteArray(32) { index -> index.toByte() }

    @Test
    fun `authenticates and decrypts a bounded Antigravity snapshot`() = runTest {
        val now = Instant.ofEpochSecond(1_750_000_000L)
        val fixture = startServer(now = now, tamperCiphertext = false)

        val snapshot = AntigravityCompanionClient(json).fetchSnapshot(fixture.credential, now)
        fixture.thread.join(5_000)

        assertNull(fixture.failure.get())
        assertEquals("desktop-local-server", snapshot.cliVersion)
        assertEquals("Gemini 3.1 Pro (High)", snapshot.windows.single().label)
        assertEquals(0.25, snapshot.windows.single().usedFraction, 0.001)
    }

    @Test
    fun `rejects a tampered encrypted Antigravity response`() {
        val now = Instant.ofEpochSecond(1_750_000_000L)
        val fixture = startServer(now = now, tamperCiphertext = true)

        assertThrows(AntigravityCompanionAuthenticationException::class.java) {
            kotlinx.coroutines.runBlocking {
                AntigravityCompanionClient(json).fetchSnapshot(fixture.credential, now)
            }
        }
        fixture.thread.join(5_000)
        assertNull(fixture.failure.get())
    }

    @Test
    fun `rejects stale or foreign snapshots and missing model measurements`() {
        val now = Instant.ofEpochSecond(1_750_000_000L)
        val changes: List<(AntigravityCompanionSnapshot) -> AntigravityCompanionSnapshot> = listOf(
            { it.copy(source = "claude-cli-terminal") },
            { it.copy(generatedAtEpochSeconds = now.epochSecond - 7201) },
            { it.copy(windows = emptyList()) },
            { it.copy(windows = listOf(it.windows.first().copy(usedFraction = -0.1))) },
            { it.copy(windows = it.windows + it.windows) }
        )
        changes.forEach { change ->
            val fixture = startServer(now, false, change)
            assertThrows(AntigravityCompanionProtocolException::class.java) {
                kotlinx.coroutines.runBlocking {
                    AntigravityCompanionClient(json).fetchSnapshot(fixture.credential, now)
                }
            }
            fixture.thread.join(5_000)
            assertNull(fixture.failure.get())
        }
    }

    private fun startServer(
        now: Instant,
        tamperCiphertext: Boolean,
        transform: (AntigravityCompanionSnapshot) -> AntigravityCompanionSnapshot = { it }
    ): ServerFixture {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val companionId = UUID.randomUUID().toString()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        val requestLine = socket.getInputStream().bufferedReader().readLine()
                        val request = json.decodeFromString<AntigravityCompanionRequest>(requestLine)
                        val authKey = AntigravityCompanionClient.deriveKey(
                            masterKey,
                            AntigravityCompanionClient.AUTH_KEY_CONTEXT
                        )
                        val expectedSignature = AntigravityCompanionClient.hmacSha256(
                            authKey,
                            AntigravityCompanionClient.requestCanonical(
                                request.companionId,
                                request.requestedAtEpochSeconds,
                                request.nonce
                            ).toByteArray(StandardCharsets.UTF_8)
                        )
                        check(
                            MessageDigest.isEqual(
                                expectedSignature,
                                AntigravityCompanionClient.decodeUrlBase64(request.signature)
                            )
                        )

                        val iv = ByteArray(12) { index -> (index + 1).toByte() }
                        val envelopeTemplate = AntigravityCompanionEnvelope(
                            protocolVersion = 1,
                            companionId = companionId,
                            requestNonce = request.nonce,
                            sentAtEpochSeconds = now.epochSecond,
                            iv = with(AntigravityCompanionClient) { iv.toUrlBase64() },
                            ciphertext = ""
                        )
                        val snapshot = AntigravityCompanionSnapshot(
                            schemaVersion = 1,
                            source = "antigravity-local-server",
                            generatedAtEpochSeconds = now.epochSecond,
                            cliVersion = "desktop-local-server",
                            windows = listOf(
                                AntigravityCompanionWindow(
                                    "Gemini 3.1 Pro (High)",
                                    0.25,
                                    now.epochSecond + 5_400
                                )
                            )
                        )
                        val encryptionKey = AntigravityCompanionClient.deriveKey(
                            masterKey,
                            AntigravityCompanionClient.ENCRYPTION_KEY_CONTEXT
                        )
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(encryptionKey, "AES"),
                            GCMParameterSpec(128, iv)
                        )
                        cipher.updateAAD(
                            AntigravityCompanionClient.responseCanonical(envelopeTemplate)
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                        val encrypted = cipher.doFinal(
                            json.encodeToString(transform(snapshot)).toByteArray(StandardCharsets.UTF_8)
                        )
                        if (tamperCiphertext) encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
                        val envelope = envelopeTemplate.copy(
                            ciphertext = with(AntigravityCompanionClient) {
                                encrypted.toUrlBase64()
                            }
                        )
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            writer.write(json.encodeToString(envelope))
                            writer.newLine()
                            writer.flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            start()
        }
        val credential = Credential.AntigravityCompanionCredential(
            host = "127.0.0.1",
            port = server.localPort,
            companionId = companionId,
            sharedKeyBase64Url = with(AntigravityCompanionClient) { masterKey.toUrlBase64() }
        )
        return ServerFixture(credential, thread, failure)
    }

    private data class ServerFixture(
        val credential: Credential.AntigravityCompanionCredential,
        val thread: Thread,
        val failure: AtomicReference<Throwable?>
    )
}
