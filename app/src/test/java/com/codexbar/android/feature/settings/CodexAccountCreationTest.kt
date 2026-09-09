package com.codexbar.android.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.codexbar.android.R
import com.codexbar.android.core.auth.AccountLinkManager
import com.codexbar.android.core.auth.DeviceAuthSession
import com.codexbar.android.core.data.*
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.monitoring.MonitoringSessionStore
import com.codexbar.android.core.network.codex.*
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.security.*
import com.codexbar.android.core.widget.WidgetPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexAccountCreationTest {
    @Test
    fun `manual Codex validation creates two persisted accounts with independent credentials`() =
        checkAccountCreation(oauth = false, legacyFirst = false)

    @Test
    fun `Codex device sign in creates two persisted accounts with independent credentials`() =
        checkAccountCreation(oauth = true, legacyFirst = false)

    @Test
    fun `manual Codex validation adds an account alongside a legacy credential`() =
        checkAccountCreation(oauth = false, legacyFirst = true)

    @Test
    fun `rejected second Codex credential reports failure without overwriting the first`() =
        checkAccountCreation(oauth = false, legacyFirst = false, rejectSecond = true)

    private fun checkAccountCreation(oauth: Boolean, legacyFirst: Boolean, rejectSecond: Boolean = false) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val prefs = EncryptedPrefsManager(store, object : PreferenceValueCipher {
            override fun encryptToString(plainText: String) = "encrypted:$plainText"
            override fun decryptToString(encryptedValue: String) = encryptedValue.removePrefix("encrypted:")
        })
        val api = mock(CodexApiService::class.java)
        val gate = TokenRefreshCoordinator()
        val telemetry = mock(CodexTelemetryClient::class.java)
        val repo = CodexRepositoryImpl(api, mock(CodexTokenRefreshService::class.java), prefs, gate, telemetry)
        val registry = mock(QuotaRepositoryRegistry::class.java)
        `when`(registry.repositoryFor(AiService.CODEX)).thenReturn(repo)
        val health = mock(ConnectionHealthStore::class.java)
        `when`(health.health).thenReturn(MutableStateFlow(emptyMap()))
        `when`(health.current(AiService.CODEX)).thenReturn(ConnectionHealth.CONNECTED)
        `when`(health.current(AccountConnection.legacy(AiService.CODEX).copy(name = "Work")))
            .thenReturn(ConnectionHealth.CONNECTED)
        if (legacyFirst) {
            prefs.saveCredential(AiService.CODEX, Credential.CodexCredential("Work-access", "Work-refresh", "Work-account"))
            prefs.renameConnection(AccountConnection.legacy(AiService.CODEX), "Work")
        }
        val auth = mock(AccountLinkManager::class.java)
        val context = mock(Context::class.java)
        `when`(context.getString(R.string.validation_authentication_required)).thenReturn("Sign in again")
        `when`(context.getString(R.string.validation_unknown)).thenReturn("Unknown")
        val vm = SettingsViewModel(registry, auth, telemetry, prefs,
            health, gate, mock(TokenRefreshStateStore::class.java), mock(QuotaHistoryStore::class.java),
            mock(WidgetPrefsManager::class.java), mock(MonitoringSessionStore::class.java),
            mock(QuotaNotificationService::class.java), context)
        try {
            for (name in if (legacyFirst) listOf("Personal") else listOf("Work", "Personal")) {
                `when`(api.getUsage("Bearer $name-access", "$name-account"))
                    .thenReturn(if (rejectSecond && name == "Personal") Response.error(401, "{}".toResponseBody())
                        else Response.success(CodexDto.UsageResponse()))
                vm.selectConnection(AiService.CODEX, null)
                runCurrent()
                vm.updateField(AiService.CODEX, "connectionName", name)
                if (oauth) {
                    val session = DeviceAuthSession(AiService.CODEX, "https://example.invalid/device", "FAKE", name, 1, Long.MAX_VALUE)
                    `when`(auth.requestDeviceCode(AiService.CODEX)).thenReturn(session)
                    `when`(auth.completeDeviceCode(session))
                        .thenReturn(Credential.CodexCredential("$name-access", "$name-refresh", "$name-account"))
                    vm.startAccountLink(AiService.CODEX)
                } else {
                    vm.updateField(AiService.CODEX, "accessToken", "$name-access")
                    vm.updateField(AiService.CODEX, "refreshToken", "$name-refresh")
                    vm.updateField(AiService.CODEX, "accountId", "$name-account")
                    vm.validateCredential(AiService.CODEX)
                }
                runCurrent()
                assertEquals(
                    if (rejectSecond && name == "Personal") ValidationResult.Failure("Sign in again") else ValidationResult.Success,
                    vm.uiState.value.serviceStates[AiService.CODEX]?.validationResult
                )
            }
            val accounts = prefs.loadConnections()
            val expectedNames = if (rejectSecond) listOf("Work") else listOf("Work", "Personal")
            assertEquals(expectedNames, accounts.map { it.name })
            assertEquals(expectedNames.size, accounts.map { it.id }.toSet().size)
            assertEquals(accounts, vm.uiState.value.connections)
            accounts.forEach {
                assertEquals("${it.name}-access", prefs.loadCredential(it)?.accessToken)
            }
            if (legacyFirst) assertEquals(AccountConnection.legacy(AiService.CODEX).id, accounts.first().id)
        } finally {
            androidx.lifecycle.ViewModelStore().apply { put("settings", vm) }.clear()
            Dispatchers.resetMain()
        }
    }
}
