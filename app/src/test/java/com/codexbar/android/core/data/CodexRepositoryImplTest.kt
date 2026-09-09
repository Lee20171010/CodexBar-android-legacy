package com.codexbar.android.core.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.CodexContextUsage
import com.codexbar.android.core.domain.model.CodexTelemetry
import com.codexbar.android.core.domain.model.CodexTelemetryCredential
import com.codexbar.android.core.domain.model.CodexTokenTotals
import com.codexbar.android.core.domain.model.CodexTokenUsage
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.QuotaNotice
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.network.codex.CodexApiService
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.TokenRefreshCoordinator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.mockingDetails
import retrofit2.Retrofit
import java.time.Instant

class CodexRepositoryImplTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: CodexApiService
    private lateinit var tokenRefreshService: CodexTokenRefreshService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var codexTelemetryClient: CodexTelemetryClient
    private lateinit var repository: CodexRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val testCredential = Credential.CodexCredential(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        accountId = "test-account-id"
    )

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val client = OkHttpClient.Builder().build()
        val contentType = "application/json".toMediaType()

        apiService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(CodexApiService::class.java)

        tokenRefreshService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(CodexTokenRefreshService::class.java)

        prefsManager = mock(EncryptedPrefsManager::class.java) { invocation ->
            if (invocation.method.name == "replaceCredential") true
            else org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        codexTelemetryClient = mock(CodexTelemetryClient::class.java)
        runTest {
            `when`(prefsManager.loadCredential(AiService.CODEX)).thenReturn(testCredential)
        }

        repository = CodexRepositoryImpl(
            apiService,
            tokenRefreshService,
            prefsManager,
            TokenRefreshCoordinator(),
            codexTelemetryClient
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchQuota returns success with rate limit windows`() = runTest {
        val responseJson = """
        {
            "plan_type": "pro",
            "rate_limit": {
                "primary_window": {
                    "used_percent": 45,
                    "reset_at": 1234567890,
                    "limit_window_seconds": 18000
                },
                "secondary_window": {
                    "used_percent": 20,
                    "reset_at": 1234567890,
                    "limit_window_seconds": 604800
                }
            },
            "credits": {
                "has_credits": true,
                "unlimited": false,
                "balance": 8.50
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.CODEX, quotaInfo.service)
        assertEquals(2, quotaInfo.windows.size)
        assertEquals("5-Hour", quotaInfo.windows[0].label)
        assertEquals(0.45, quotaInfo.windows[0].utilization, 0.001)
        assertEquals(18000L, quotaInfo.windows[0].windowDurationSeconds)
        assertEquals("7-Day", quotaInfo.windows[1].label)
        assertEquals(0.20, quotaInfo.windows[1].utilization, 0.001)
        assertEquals(604800L, quotaInfo.windows[1].windowDurationSeconds)
        assertEquals("Pro", quotaInfo.tier)
        assertTrue(quotaInfo.notices.isEmpty())
        assertEquals(0, quotaInfo.codexResetCredits?.availableCount)
    }

    @Test
    fun `weekly-only response reports that the five-hour limit is not provided`() = runTest {
        val responseJson = """
        {
            "plan_type": "pro",
            "rate_limit": {
                "primary_window": {
                    "used_percent": 20,
                    "reset_at": 1999999999,
                    "limit_window_seconds": 604800
                }
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(1, quotaInfo.windows.size)
        assertEquals(
            setOf(QuotaNotice.WindowLimitNotProvided(18000L)),
            quotaInfo.notices
        )
    }

    @Test
    fun `fetchQuota returns AuthError on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
    }

    @Test
    fun `expired Codex token refresh uses the official minimal request and preserves credential on server failure`() = runTest {
        val expiredCredential = testCredential.copy(expiresAt = Instant.now().minusSeconds(60))
        `when`(prefsManager.loadCredential(AiService.CODEX)).thenReturn(expiredCredential)
        mockWebServer.enqueue(MockResponse().setResponseCode(503))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.NetworkError)
        val refreshRequest = mockWebServer.takeRequest()
        val refreshJson = json.parseToJsonElement(refreshRequest.body.readUtf8()).jsonObject
        assertEquals(
            setOf("client_id", "grant_type", "refresh_token"),
            refreshJson.keys
        )
        verify(prefsManager, never()).deleteCredential(AiService.CODEX)
    }

    @Test
    fun `successful proactive refresh stores rotated token expiry before usage request`() = runTest {
        val expiredCredential = testCredential.copy(expiresAt = Instant.now().minusSeconds(60))
        `when`(prefsManager.loadCredential(AiService.CODEX)).thenReturn(expiredCredential)
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000}}}"""
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val saveInvocation = mockingDetails(prefsManager).invocations.first {
            it.method.name == "replaceCredential"
        }
        assertEquals(AccountConnection.legacy(AiService.CODEX), saveInvocation.arguments[0])
        assertEquals(expiredCredential, saveInvocation.arguments[1])
        val saved = saveInvocation.arguments[2] as Credential.CodexCredential
        assertEquals("rotated-access", saved.accessToken)
        assertEquals("rotated-refresh", saved.refreshToken)
        assertTrue(saved.expiresAt?.isAfter(Instant.now().plusSeconds(3_500)) == true)
        mockWebServer.takeRequest()
        val usageRequest = mockWebServer.takeRequest()
        assertEquals("Bearer rotated-access", usageRequest.getHeader("Authorization"))
    }

    @Test
    fun `stale refresh does not delete or overwrite newer credential`() = runTest {
        val newerCredential = Credential.CodexCredential(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            accountId = "test-account-id"
        )
        `when`(prefsManager.loadCredential(AiService.CODEX))
            .thenReturn(testCredential)
            .thenReturn(newerCredential)

        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "plan_type": "pro",
                    "rate_limit": {
                        "primary_window": {
                            "used_percent": 10,
                            "reset_at": 1234567890,
                            "limit_window_seconds": 18000
                        }
                    }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val firstUsageRequest = mockWebServer.takeRequest()
        val retryUsageRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test-access-token", firstUsageRequest.getHeader("Authorization"))
        assertEquals("Bearer new-access-token", retryUsageRequest.getHeader("Authorization"))
        verify(prefsManager, never()).deleteCredential(AiService.CODEX)
        verify(prefsManager, never()).saveCredential(AiService.CODEX, newerCredential)
    }

    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.RateLimited)
    }

    @Test
    fun `fetchQuota returns CredentialNotFound when no credential`() = runTest {
        `when`(prefsManager.loadCredential(AiService.CODEX)).thenReturn(null)

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }

    @Test
    fun `parseBalance handles Double`() {
        val element = kotlinx.serialization.json.JsonPrimitive(8.50)
        val balance = CodexRepositoryImpl.parseBalance(element)
        assertEquals(8.50, balance!!, 0.001)
    }

    @Test
    fun `parseBalance handles String`() {
        val element = kotlinx.serialization.json.JsonPrimitive("12.75")
        val balance = CodexRepositoryImpl.parseBalance(element)
        assertEquals(12.75, balance!!, 0.001)
    }

    @Test
    fun `parseBalance returns null for null`() {
        val balance = CodexRepositoryImpl.parseBalance(null)
        assertTrue(balance == null)
    }

    @Test
    fun `custom window label for non-standard seconds`() = runTest {
        val responseJson = """
        {
            "plan_type": "team",
            "rate_limit": {
                "primary_window": {
                    "used_percent": 30,
                    "reset_at": 1234567890,
                    "limit_window_seconds": 3600
                }
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals("1h", quotaInfo.windows[0].label)
    }

    @Test
    fun `fetchQuota includes only available unexpired reset credits`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "plan_type": "pro",
                    "rate_limit": {
                        "primary_window": {
                            "used_percent": 30,
                            "reset_at": 1999999999,
                            "limit_window_seconds": 18000
                        }
                    }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "credits": [
                        {
                            "id": "available-later",
                            "reset_type": "codex_rate_limits",
                            "status": "available",
                            "granted_at": "2026-08-01T00:00:00Z",
                            "expires_at": "2099-08-30T00:00:00.123456Z"
                        },
                        {
                            "id": "available-no-expiry",
                            "reset_type": "codex_rate_limits",
                            "status": "available",
                            "granted_at": "2026-08-01T00:00:00Z",
                            "expires_at": null
                        },
                        {
                            "id": "redeemed",
                            "reset_type": "codex_rate_limits",
                            "status": "redeemed",
                            "granted_at": "2026-08-01T00:00:00Z",
                            "expires_at": "2099-09-30T00:00:00Z"
                        }
                    ],
                    "available_count": 2
                }
                """.trimIndent()
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val resetCredits = (result as Result.Success).value.codexResetCredits
        assertEquals(2, resetCredits?.availableCount)
        assertEquals(1, resetCredits?.expiresAt?.size)
        val creditsRequest = mockWebServer.takeRequest()
        val resetRequest = mockWebServer.takeRequest()
        assertEquals("/backend-api/wham/usage", creditsRequest.path)
        assertEquals("/backend-api/wham/rate-limit-reset-credits", resetRequest.path)
        assertEquals("codex-1", resetRequest.getHeader("OpenAI-Beta"))
        assertEquals("Codex Desktop", resetRequest.getHeader("originator"))
        assertEquals("test-account-id", resetRequest.getHeader("ChatGPT-Account-ID"))
    }

    @Test
    fun `reset credit failure does not discard successful usage`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "plan_type": "pro",
                    "rate_limit": {
                        "primary_window": {
                            "used_percent": 30,
                            "reset_at": 1999999999,
                            "limit_window_seconds": 18000
                        }
                    }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(503))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals(null, (result as Result.Success).value.codexResetCredits)
    }

    @Test
    fun `adds optional local telemetry without exposing it as a required dependency`() = runTest {
        val telemetryCredential = CodexTelemetryCredential(
            host = "127.0.0.1",
            port = 43822,
            companionId = "5b017391-6dc4-4ab7-b0ad-2255dada62d7",
            sharedKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        val totals = CodexTokenTotals(100, 20, 10, 3, 110)
        val telemetry = CodexTelemetry(
            generatedAt = Instant.ofEpochSecond(1_750_000_000L),
            currentContext = CodexContextUsage(
                capturedAt = Instant.ofEpochSecond(1_750_000_000L),
                model = "gpt-5.6",
                usedTokens = 64_000,
                contextWindowTokens = 258_400,
                sessionTokens = 110
            ),
            tokenUsage = CodexTokenUsage(totals, totals, totals, emptyList(), emptyList())
        )
        `when`(prefsManager.loadCodexTelemetryCredential()).thenReturn(telemetryCredential)
        val telemetryClient = object : CodexTelemetryClient(json) {
            override suspend fun fetchSnapshot(
                credential: CodexTelemetryCredential,
                now: Instant
            ): CodexTelemetry = telemetry
        }
        val telemetryRepository = CodexRepositoryImpl(
            apiService,
            tokenRefreshService,
            prefsManager,
            TokenRefreshCoordinator(),
            telemetryClient
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000}}}"""
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = telemetryRepository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals("gpt-5.6", (result as Result.Success).value.codexTelemetry?.currentContext?.model)
    }

    @Test
    fun `telemetry failure never discards OpenAI quota`() = runTest {
        val telemetryCredential = CodexTelemetryCredential(
            host = "127.0.0.1",
            port = 43822,
            companionId = "5b017391-6dc4-4ab7-b0ad-2255dada62d7",
            sharedKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        `when`(prefsManager.loadCodexTelemetryCredential()).thenReturn(telemetryCredential)
        val failingClient = object : CodexTelemetryClient(json) {
            override suspend fun fetchSnapshot(
                credential: CodexTelemetryCredential,
                now: Instant
            ): CodexTelemetry = throw java.io.IOException("companion offline")
        }
        val telemetryRepository = CodexRepositoryImpl(
            apiService,
            tokenRefreshService,
            prefsManager,
            TokenRefreshCoordinator(),
            failingClient
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000}}}"""
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = telemetryRepository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals(null, (result as Result.Success).value.codexTelemetry)
        assertEquals(1, result.value.windows.size)
    }

    @Test
    fun `maps valid model-specific limits while skipping malformed siblings`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "plan_type": "pro",
                    "rate_limit": {
                        "primary_window": {
                            "used_percent": 22,
                            "reset_at": 1999999999,
                            "limit_window_seconds": 18000
                        }
                    },
                    "additional_rate_limits": [
                        "malformed",
                        {
                            "limit_name": "GPT-5.3-Codex-Spark",
                            "metered_feature": "gpt_5_3_codex_spark",
                            "rate_limit": {
                                "primary_window": {
                                    "used_percent": 30,
                                    "reset_at": 1999999999,
                                    "limit_window_seconds": 18000
                                },
                                "secondary_window": {
                                    "used_percent": 80,
                                    "reset_at": 2000000999,
                                    "limit_window_seconds": 604800
                                }
                            }
                        },
                        42
                    ]
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val windows = (result as Result.Success).value.windows
        assertEquals(3, windows.size)
        assertEquals("5-Hour", windows[0].label)
        assertEquals("Codex Spark 5-Hour", windows[1].label)
        assertEquals(0.30, windows[1].utilization, 0.001)
        assertEquals("Codex Spark Weekly", windows[2].label)
        assertEquals(0.80, windows[2].utilization, 0.001)
    }

    @Test
    fun `malformed additional limit container preserves core windows`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "rate_limit": {
                        "primary_window": {
                            "used_percent": 22,
                            "reset_at": 1999999999,
                            "limit_window_seconds": 18000
                        }
                    },
                    "additional_rate_limits": "unexpected"
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(resetCreditsResponse())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals(1, (result as Result.Success).value.windows.size)
        assertEquals("5-Hour", result.value.windows.single().label)
    }

    private fun resetCreditsResponse(): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setBody("""{"credits":[],"available_count":0}""")
    }
}
