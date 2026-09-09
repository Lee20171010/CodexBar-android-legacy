package com.codexbar.android.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.CodexTelemetryCredential
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.data.CodexRepositoryImpl
import com.codexbar.android.core.network.codex.CodexApiService
import com.codexbar.android.core.network.codex.CodexDto
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Response

class ConnectionCredentialsTest {
    @Test
    fun `one Codex repository refreshes only the requested connection and never falls back after deletion`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val first = manager.createConnection(
            AiService.CODEX, "Work", credential("work").copy(expiresAt = Instant.now().minusSeconds(60))
        )
        val second = manager.createConnection(AiService.CODEX, "Personal", credential("personal"))
        val api = mock(CodexApiService::class.java)
        val refresh = mock(CodexTokenRefreshService::class.java)
        val telemetry = mock(CodexTelemetryClient::class.java)
        val usage = CodexDto.UsageResponse(rateLimit = CodexDto.RateLimit(
            primaryWindow = CodexDto.RateLimitWindow(20.0, 2_000_000_000, 18000)
        ))
        `when`(refresh.refreshToken(CodexDto.TokenRefreshRequest(
            CodexDto.CODEX_CLIENT_ID, "refresh_token", "work-refresh"
        ))).thenReturn(Response.success(CodexDto.TokenRefreshResponse("rotated-access", "rotated-refresh", 3600)))
        `when`(api.getUsage("Bearer rotated-access", "work-account")).thenReturn(Response.success(usage))
        `when`(api.getRateLimitResetCredits("Bearer rotated-access", "work-account"))
            .thenReturn(Response.success(CodexDto.ResetCreditsResponse()))
        `when`(api.getUsage("Bearer personal-access", "personal-account")).thenReturn(Response.success(usage))
        `when`(api.getRateLimitResetCredits("Bearer personal-access", "personal-account"))
            .thenReturn(Response.success(CodexDto.ResetCreditsResponse()))
        val repository = CodexRepositoryImpl(api, refresh, manager, TokenRefreshCoordinator(), telemetry)
        assertTrue(repository.fetchQuota(first) is Result.Success)
        assertEquals("rotated-refresh", manager.loadCredential(first)?.refreshToken)
        assertEquals(credential("personal"), manager.loadCredential(second))
        assertTrue(repository.fetchQuota(second) is Result.Success)
        manager.deleteCredential(first)
        assertTrue(repository.fetchQuota(first) is Result.Failure)
        assertEquals(credential("personal"), manager.loadCredential(second))
    }

    @Test
    fun `legacy adoption preserves ciphertext reset times and stable identity across reloads`() = runBlocking {
        val legacy = preferencesOf(
            stringPreferencesKey("CODEX_access_token") to "encrypted:old-access",
            stringPreferencesKey("CODEX_refresh_token") to "encrypted:old-refresh",
            stringPreferencesKey("CODEX_account_id") to "encrypted:old-account",
            longPreferencesKey("CODEX_weekly_resets_at") to 2_000_000_000L
        )
        val store = MemoryPreferences(legacy)
        val manager = manager(store)
        manager.warmCache()
        val connection = manager.loadConnections().single()

        assertEquals("CODEX", connection.id)
        assertEquals(AiService.CODEX, connection.service)
        assertEquals("Codex", connection.name)
        assertEquals("old-access", manager.loadCredential(connection)?.accessToken)
        assertEquals(mapOf("weekly" to Instant.ofEpochSecond(2_000_000_000)), manager.loadResetTimes(connection))
        legacy.asMap().forEach { (key, value) -> assertEquals(value, store.value[key]) }

        val reloaded = manager(store)
        reloaded.warmCache()
        assertEquals(listOf(connection), reloaded.loadConnections())
        legacy.asMap().forEach { (key, value) -> assertEquals(value, store.value[key]) }
    }

    @Test
    fun `two Codex connections isolate refresh rename reset and deletion`() = runBlocking {
        val store = MemoryPreferences()
        val manager = manager(store)
        val first = manager.createConnection(AiService.CODEX, "Work", credential("work"))
        val second = manager.createConnection(AiService.CODEX, "Personal", credential("personal"))
        assertNotEquals(first.id, second.id)
        manager.saveResetTimes(first, listOf("weekly" to Instant.ofEpochSecond(2_000_000_000)))
        manager.saveResetTimes(second, listOf("weekly" to Instant.ofEpochSecond(2_100_000_000)))

        assertTrue(manager.replaceCredential(first, credential("work"), credential("rotated")))
        manager.renameConnection(first, "Office")
        assertEquals("Office", manager.loadConnections().first { it.id == first.id }.name)
        assertEquals(credential("personal"), manager.loadCredential(second))
        assertEquals(credential("rotated"), manager.loadCredential(first))
        assertEquals(Instant.ofEpochSecond(2_000_000_000), manager.loadResetTimes(first)["weekly"])

        manager.deleteCredential(first)
        assertNull(manager.loadCredential(first))
        assertEquals(listOf(second), manager.loadConnections())
        assertEquals(credential("personal"), manager.loadCredential(second))
        assertEquals(Instant.ofEpochSecond(2_100_000_000), manager.loadResetTimes(second)["weekly"])
        assertFalse(store.value.asMap().keys.any { it.name.startsWith("${first.id}_") })
    }

    @Test
    fun `late token replacement cannot resurrect deleted connection or overwrite reauthentication`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val connection = manager.createConnection(AiService.CODEX, "Work", credential("original"))
        manager.saveCredential(connection, credential("reauth"))
        assertFalse(manager.replaceCredential(connection, credential("original"), credential("late")))
        assertEquals(credential("reauth"), manager.loadCredential(connection))
        manager.deleteCredential(connection)
        assertFalse(manager.replaceCredential(connection, credential("reauth"), credential("later")))
        assertNull(manager.loadCredential(connection))
        assertTrue(manager.loadConnections().isEmpty())
    }

    @Test
    fun `telemetry has one owner and deleting other Codex account keeps pairing`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val first = manager.createConnection(AiService.CODEX, "Work", credential("work"))
        val second = manager.createConnection(AiService.CODEX, "Personal", credential("personal"))
        val telemetry = CodexTelemetryCredential("127.0.0.1", 1234, "fixture", "fixture-key")
        manager.saveCodexTelemetryCredential(first, telemetry)
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(first))
        assertNull(manager.loadCodexTelemetryCredential(second))
        manager.deleteCodexTelemetryCredential(second)
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(first))
        manager.deleteCredential(second)
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(first))
        manager.deleteCredential(first)
        assertNull(manager.loadCodexTelemetryCredential(first))
        val replacement = manager.createConnection(AiService.CODEX, "New", credential("new"))
        assertNull(manager.loadCodexTelemetryCredential(replacement))
    }

    @Test
    fun `legacy telemetry belongs only to legacy Codex connection`() = runBlocking {
        val manager = manager(MemoryPreferences(preferencesOf(
            stringPreferencesKey("LOCAL_CODEX_TELEMETRY_host") to "encrypted:127.0.0.1",
            longPreferencesKey("LOCAL_CODEX_TELEMETRY_port") to 1234L,
            stringPreferencesKey("LOCAL_CODEX_TELEMETRY_id") to "encrypted:fixture",
            stringPreferencesKey("LOCAL_CODEX_TELEMETRY_shared_key") to "encrypted:fixture-key"
        )))
        manager.saveCredential(AiService.CODEX, credential("legacy"))
        val telemetry = CodexTelemetryCredential("127.0.0.1", 1234, "fixture", "fixture-key")
        val legacy = manager.loadConnections().single()
        val added = manager.createConnection(AiService.CODEX, "Other", credential("other"))
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(legacy))
        assertNull(manager.loadCodexTelemetryCredential(added))
        manager.deleteAllCredentials()
        assertTrue(manager.loadConnections().isEmpty())
        assertNull(manager.loadCredential(added))
        assertNull(manager.loadCodexTelemetryCredential())
    }

    @Test
    fun `telemetry reassignment and explicit unpair affect only current owner`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val first = manager.createConnection(AiService.CODEX, "Work", credential("work"))
        val second = manager.createConnection(AiService.CODEX, "Personal", credential("personal"))
        val telemetry = CodexTelemetryCredential("127.0.0.1", 1234, "fixture", "fixture-key")
        manager.saveCodexTelemetryCredential(first, telemetry)
        manager.saveCodexTelemetryCredential(second, telemetry)
        assertNull(manager.loadCodexTelemetryCredential(first))
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(second))
        manager.deleteCodexTelemetryCredential(first)
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(second))
        manager.deleteCodexTelemetryCredential(second)
        assertNull(manager.loadCodexTelemetryCredential(second))
        assertEquals(credential("personal"), manager.loadCredential(second))
    }

    @Test
    fun `shipped telemetry pairing can precede Codex login without losing pairing`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val telemetry = CodexTelemetryCredential("127.0.0.1", 1234, "fixture", "fixture-key")
        manager.saveCodexTelemetryCredential(telemetry)
        assertEquals(telemetry, manager.loadCodexTelemetryCredential())
        val added = manager.createConnection(AiService.CODEX, "Other", credential("other"))
        assertNull(manager.loadCodexTelemetryCredential(added))
        manager.saveCredential(AiService.CODEX, credential("legacy"))
        val legacy = manager.loadConnections().first { it.id == "CODEX" }
        assertEquals(telemetry, manager.loadCodexTelemetryCredential(legacy))
    }

    @Test
    fun `unreadable legacy credentials remain discoverable without changing stored bytes`() = runBlocking {
        val store = MemoryPreferences(preferencesOf(
            stringPreferencesKey("CODEX_access_token") to "unreadable",
            stringPreferencesKey("CODEX_refresh_token") to "encrypted:refresh"
        ))
        val manager = manager(store)
        manager.warmCache()
        val connection = manager.loadConnections().single()
        assertEquals("CODEX", connection.id)
        assertNull(manager.loadCredential(connection))
        assertEquals("unreadable", store.value[stringPreferencesKey("CODEX_access_token")])
    }

    @Test
    fun `concurrent rotations have one winner without touching sibling connection`() = runBlocking {
        val manager = manager(MemoryPreferences())
        val first = manager.createConnection(AiService.CODEX, "Work", credential("original"))
        val second = manager.createConnection(AiService.CODEX, "Home", credential("home"))
        val outcomes = listOf("one", "two").map { token ->
            async(Dispatchers.Default) { manager.replaceCredential(first, credential("original"), credential(token)) }
        }.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertEquals(credential("home"), manager.loadCredential(second))
    }

    @Test
    fun `connection writes validate identity provider name and existence before changing data`() = runBlocking {
        val store = MemoryPreferences()
        val manager = manager(store)
        val connection = manager.createConnection(AiService.CODEX, "Work", credential("work"))
        assertTrue(runCatching { AccountConnection("CODEX", AiService.COPILOT) }.isFailure)
        assertTrue(runCatching { AccountConnection("CODEX_other", AiService.CODEX) }.isFailure)
        assertTrue(runCatching { manager.renameConnection(connection, " ") }.isFailure)
        assertTrue(runCatching { manager.renameConnection(connection, "x".repeat(81)) }.isFailure)
        assertTrue(runCatching {
            manager.saveCredential(connection, Credential.CopilotCredential("invalid"))
        }.isFailure)
        val forged = connection.copy(service = AiService.COPILOT)
        assertNull(manager.loadCredential(forged))
        manager.deleteCredential(forged)
        assertEquals(credential("work"), manager.loadCredential(connection))
        assertTrue(runCatching {
            manager.saveCredential(forged, Credential.CopilotCredential("invalid"))
        }.isFailure)
        manager.deleteCredential(connection)
        assertTrue(runCatching { manager.saveCredential(connection, credential("late")) }.isFailure)
        assertTrue(runCatching { manager.renameConnection(connection, "Late") }.isFailure)
        manager.saveResetTimes(connection, listOf("weekly" to Instant.ofEpochSecond(2_000_000_000)))
        assertTrue(manager.loadConnections().isEmpty())
        assertFalse(store.value.asMap().keys.any { it.name.startsWith("${connection.id}_") })
    }

    @Test
    fun `reauth clears expired optional fields but keeps encrypted name and reset times`() = runBlocking {
        val store = MemoryPreferences()
        val manager = manager(store)
        val connection = manager.createConnection(
            AiService.CODEX, "Private name", credential("old").copy(expiresAt = Instant.ofEpochSecond(2_000_000_000))
        )
        manager.saveResetTimes(connection, listOf("weekly" to Instant.ofEpochSecond(2_100_000_000)))
        manager.saveCredential(connection, credential("new").copy(accountId = null))
        assertNull(store.value[longPreferencesKey("${connection.id}_expires_at")])
        assertNull(store.value[stringPreferencesKey("${connection.id}_account_id")])
        assertEquals("encrypted:Private name", store.value[stringPreferencesKey("${connection.id}_connection_name")])
        assertEquals("Private name", manager.loadConnections().single().name)
        assertEquals(Instant.ofEpochSecond(2_100_000_000), manager.loadResetTimes(connection)["weekly"])
    }

    private fun credential(token: String) = Credential.CodexCredential(
        accessToken = "$token-access",
        refreshToken = "$token-refresh",
        accountId = "$token-account"
    )

    private fun manager(store: DataStore<Preferences>) = EncryptedPrefsManager(store, object : PreferenceValueCipher {
        override fun encryptToString(plainText: String) = "encrypted:$plainText"
        override fun decryptToString(encryptedValue: String) =
            encryptedValue.takeIf { it.startsWith("encrypted:") }?.removePrefix("encrypted:")
    })

    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        val value: Preferences get() = state.value
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
}
