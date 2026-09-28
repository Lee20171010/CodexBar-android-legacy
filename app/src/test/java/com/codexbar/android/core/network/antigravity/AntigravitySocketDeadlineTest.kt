package com.codexbar.android.core.network.antigravity

import com.codexbar.android.core.domain.model.Credential
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AntigravitySocketDeadlineTest {
    @Test fun `a trickling peer cannot extend the total response deadline`() {
        withTricklingPeer { credential ->
            val started = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                runBlocking { AntigravityCompanionClient(Json).fetchSnapshot(credential) }
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 12_000)
        }
    }

    @Test fun `cancelling a fetch closes the socket promptly`() {
        withTricklingPeer { credential ->
            val started = System.nanoTime()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(1_000) { AntigravityCompanionClient(Json).fetchSnapshot(credential) }
                }
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    private fun withTricklingPeer(check: (Credential.AntigravityCompanionCredential) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val thread = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        repeat(150) {
                            socket.getOutputStream().write('x'.code)
                            socket.getOutputStream().flush()
                            Thread.sleep(100)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                check(Credential.AntigravityCompanionCredential(
                    "127.0.0.1", server.localPort, UUID.randomUUID().toString(),
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                ))
            } finally {
                thread.join(2_000)
            }
            assertFalse("The cancelled or timed-out connection must close", thread.isAlive)
        }
    }
}
