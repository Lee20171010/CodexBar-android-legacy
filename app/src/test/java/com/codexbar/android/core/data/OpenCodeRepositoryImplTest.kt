package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.network.opencode.OpenCodeApiService
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit

class OpenCodeRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: OpenCodeRepositoryImpl

    private val credential = Credential.ProviderSecretCredential(
        service = AiService.OPENCODE_GO,
        kind = ProviderSecretKind.API_KEY,
        accessToken = "oc-test-key"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefsManager = mock(EncryptedPrefsManager::class.java)
        val apiService = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(OpenCodeApiService::class.java)
        repository = OpenCodeRepositoryImpl(
            apiService = apiService,
            prefsManager = prefsManager
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `maps rolling weekly and monthly windows from the fixed usage endpoint`() = runTest {
        `when`(prefsManager.loadCredential(AiService.OPENCODE_GO)).thenReturn(credential)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "usage": {
                    "rolling": {"status":"ok","percent":25,"resetsAt":"2026-08-13T12:00:00Z"},
                    "weekly": {"status":"ok","percent":48.5,"resetsAt":"2026-08-16T07:00:00Z"},
                    "monthly": {"status":"ok","percent":100,"resetsAt":"2026-09-13T07:00:00Z"}
                  }
                }
                """.trimIndent()
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quota = (result as Result.Success).value
        assertEquals(AiService.OPENCODE_GO, quota.service)
        assertEquals(listOf("5-Hour", "Weekly", "Monthly"), quota.windows.map { it.label })
        assertEquals(0.25, quota.windows[0].utilization, 0.0)
        assertEquals(0.485, quota.windows[1].utilization, 0.0)
        assertEquals(1.0, quota.windows[2].utilization, 0.0)
        assertEquals(Instant.parse("2026-08-13T12:00:00Z"), quota.windows[0].resetsAt)
        assertEquals(5L * 60L * 60L, quota.windows[0].windowDurationSeconds)
        assertEquals(7L * 24L * 60L * 60L, quota.windows[1].windowDurationSeconds)
        assertEquals(30L * 24L * 60L * 60L, quota.windows[2].windowDurationSeconds)
        assertNull(quota.extraUsage)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/zen/go/v1/usage", request.requestUrl?.encodedPath)
        assertEquals("Bearer oc-test-key", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `clamps out-of-range percentages to the plan window`() = runTest {
        `when`(prefsManager.loadCredential(AiService.OPENCODE_GO)).thenReturn(credential)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "usage": {
                    "rolling": {"percent":-5,"resetsAt":"2026-08-13T12:00:00Z"},
                    "weekly": {"percent":140,"resetsAt":"2026-08-16T07:00:00Z"}
                  }
                }
                """.trimIndent()
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val windows = (result as Result.Success).value.windows
        assertEquals(listOf("5-Hour", "Weekly"), windows.map { it.label })
        assertEquals(0.0, windows[0].utilization, 0.0)
        assertEquals(1.0, windows[1].utilization, 0.0)
    }

    @Test
    fun `reports a parse error when usage windows are absent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"usage":{}}"""))

        val result = repository.validateCredential(credential)

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.ParseError)
    }

    @Test
    fun `maps rejected key to terminal authentication error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))

        val unauthorized = repository.validateCredential(credential)
        val forbidden = repository.validateCredential(credential)

        listOf(unauthorized, forbidden).forEach { result ->
            assertTrue(result is Result.Failure)
            val error = (result as Result.Failure).error
            assertTrue(error is AppError.AuthError && error.isTerminal)
        }
    }

    @Test
    fun `maps rate limiting to a retryable error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))

        val result = repository.validateCredential(credential)

        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.RateLimited)
        assertTrue((error as AppError.RateLimited).retryAt != null)
    }

    @Test
    fun `treats server failures as service unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository.validateCredential(credential)

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error == AppError.ServiceUnavailable)
    }

    @Test
    fun `rejects cookie credentials before network access`() = runTest {
        val result = repository.validateCredential(
            credential.copy(kind = ProviderSecretKind.COOKIE_HEADER)
        )

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.AuthError)
        assertEquals(0, server.requestCount)
    }
}
