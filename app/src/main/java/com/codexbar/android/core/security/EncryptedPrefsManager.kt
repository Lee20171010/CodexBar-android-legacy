package com.codexbar.android.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppThemeStyle
import com.codexbar.android.core.domain.model.CodexTelemetryCredential
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.providerMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SECURE_DATASTORE_NAME = "codexbar_secure_prefs"
private const val DEFAULT_REFRESH_INTERVAL_MINUTES = 30L
private val FAIL_CLOSED_PRIVACY_SETTINGS = PrivacySettings(
    notificationRedactionEnabled = true,
    widgetRedactionEnabled = true
)
private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SECURE_DATASTORE_NAME
)

@Singleton
class EncryptedPrefsManager internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val valueCipher: PreferenceValueCipher
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.secureDataStore,
        AndroidKeyStoreValueCipher()
    ) {
        context.deleteSharedPreferences(SECURE_PREFS_NAME)
    }

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedSettings = CachedSettings()

    private val _appThemeStyle = MutableStateFlow(AppThemeStyle.MATERIAL_3)
    val appThemeStyle: StateFlow<AppThemeStyle> = _appThemeStyle.asStateFlow()

    private val _minimalDisplayEnabled = MutableStateFlow(false)
    val minimalDisplayEnabled: StateFlow<Boolean> = _minimalDisplayEnabled.asStateFlow()

    @Volatile
    private var cacheRefreshStarted = false

    init {
        refreshCacheAsync()
    }

    suspend fun warmCache() {
        val initialPrefs = readPreferencesOrNull()
        val servicesToRemove = buildList {
            if (initialPrefs?.containsLegacyGeminiTokens() == true) add(AiService.GEMINI)
            if (initialPrefs?.containsLegacyClaudeTokens() == true) add(AiService.CLAUDE)
        }
        val prefs = if (servicesToRemove.isNotEmpty()) {
            dataStore.edit { mutablePrefs ->
                servicesToRemove.forEach { service ->
                    mutablePrefs.removeServiceEntries(service)
                }
            }
        } else {
            initialPrefs
        }
        if (prefs == null) {
            updateCachedSettings(CachedSettings())
        } else {
            updateCache(prefs)
        }
    }

    suspend fun saveCredential(service: AiService, credential: Credential) {
        val updated = dataStore.edit { prefs ->
            prefs.writeCredential(AccountConnection.legacy(service), credential)
        }
        updateCache(updated)
    }

    suspend fun loadConnections(): List<AccountConnection> = readConnections(readPreferences())

    suspend fun createConnection(
        service: AiService,
        name: String,
        credential: Credential
    ): AccountConnection {
        val connection = AccountConnection(UUID.randomUUID().toString(), service, name.trim())
        val updated = dataStore.edit { prefs ->
            prefs.writeCredential(connection, credential)
            prefs.putEncryptedString("${connection.id}_connection_name", connection.name)
        }
        updateCache(updated)
        return connection
    }

    suspend fun renameConnection(connection: AccountConnection, name: String) {
        val renamed = connection.copy(name = name.trim())
        dataStore.edit { prefs ->
            require(prefs.containsConnection(connection)) { "Connection no longer exists" }
            prefs.putEncryptedString("${connection.id}_connection_name", renamed.name)
        }
    }

    suspend fun saveCredential(connection: AccountConnection, credential: Credential) {
        val updated = dataStore.edit { prefs ->
            require(prefs.containsConnection(connection)) { "Connection no longer exists" }
            prefs.writeCredential(connection, credential)
        }
        updateCache(updated)
    }

    suspend fun replaceCredential(
        connection: AccountConnection,
        expected: Credential,
        replacement: Credential
    ): Boolean {
        var replaced = false
        val updated = dataStore.edit { prefs ->
            if (prefs.containsConnection(connection) &&
                readCredential(prefs, connection.service, connection.id) == expected
            ) {
                prefs.writeCredential(connection, replacement)
                replaced = true
            }
        }
        updateCache(updated)
        return replaced
    }

    suspend fun loadCredential(connection: AccountConnection): Credential? {
        val prefs = readPreferences()
        if (!prefs.containsConnection(connection)) return null
        return readCredential(prefs, connection.service, connection.id)
    }

    private fun MutablePreferences.writeCredential(connection: AccountConnection, credential: Credential) {
        val service = connection.service
        val credentialService = when (credential) {
            is Credential.AntigravityCompanionCredential -> AiService.ANTIGRAVITY
            is Credential.ClaudeCompanionCredential -> AiService.CLAUDE
            is Credential.CodexCredential -> AiService.CODEX
            is Credential.GeminiCompanionCredential -> AiService.GEMINI
            is Credential.CopilotCredential -> AiService.COPILOT
            is Credential.ProviderSecretCredential -> credential.service
        }
        require(credentialService == service) { "Provider credential does not match ${service.name}" }
        require(
            service != AiService.CLAUDE || credential is Credential.ClaudeCompanionCredential
        ) { "Claude accepts only a local companion pairing" }
        require(service != AiService.ANTIGRAVITY || credential is Credential.AntigravityCompanionCredential) {
            "Antigravity accepts only a local companion pairing"
        }
        val prefs = this
        val prefix = connection.id
        prefs.asMap().keys.filter { key ->
            key.name.startsWith("${prefix}_") && key.name.removePrefix("${prefix}_") in CREDENTIAL_FIELDS
        }.forEach { key -> prefs.removeEntry(key) }
        prefs[stringPreferencesKey("${prefix}_connection_provider")] = service.name

        if (
            credential !is Credential.GeminiCompanionCredential &&
            credential !is Credential.ClaudeCompanionCredential &&
            credential !is Credential.AntigravityCompanionCredential
        ) {
            prefs.putEncryptedString("${prefix}_access_token", credential.accessToken)
            credential.refreshToken?.let {
                prefs.putEncryptedString("${prefix}_refresh_token", it)
            }
        }

        when (credential) {
            is Credential.AntigravityCompanionCredential -> {
                prefs.putEncryptedString("${prefix}_companion_host", credential.host)
                prefs[longPreferencesKey("${prefix}_companion_port")] = credential.port.toLong()
                prefs.putEncryptedString("${prefix}_companion_id", credential.companionId)
                prefs.putEncryptedString(
                    "${prefix}_companion_shared_key",
                    credential.sharedKeyBase64Url
                )
            }

            is Credential.ClaudeCompanionCredential -> {
                prefs.putEncryptedString("${prefix}_companion_host", credential.host)
                prefs[longPreferencesKey("${prefix}_companion_port")] = credential.port.toLong()
                prefs.putEncryptedString("${prefix}_companion_id", credential.companionId)
                prefs.putEncryptedString(
                    "${prefix}_companion_shared_key",
                    credential.sharedKeyBase64Url
                )
            }

            is Credential.CodexCredential -> {
                credential.accountId?.let {
                    prefs.putEncryptedString("${prefix}_account_id", it)
                }
                credential.expiresAt?.let {
                    prefs[longPreferencesKey("${prefix}_expires_at")] = it.epochSecond
                }
            }

            is Credential.GeminiCompanionCredential -> {
                prefs.putEncryptedString("${prefix}_companion_host", credential.host)
                prefs[longPreferencesKey("${prefix}_companion_port")] = credential.port.toLong()
                prefs.putEncryptedString("${prefix}_companion_id", credential.companionId)
                prefs.putEncryptedString(
                    "${prefix}_companion_shared_key",
                    credential.sharedKeyBase64Url
                )
            }

            is Credential.CopilotCredential -> {
                // Access-token only; GitHub's device flow used here does not issue refresh tokens.
            }

            is Credential.ProviderSecretCredential -> {
                prefs.putEncryptedString("${prefix}_secret_kind", credential.kind.name)
                credential.accountReference?.let {
                    prefs.putEncryptedString("${prefix}_account_reference", it)
                }
            }
        }
    }

    suspend fun loadCredential(service: AiService): Credential? {
        return readCredential(readPreferences(), service)
    }

    suspend fun updateAntigravityCompanionHostIfCurrent(
        expected: Credential.AntigravityCompanionCredential,
        host: String
    ): Boolean {
        var applied = false
        val updated = dataStore.edit { prefs ->
            // Disconnect and re-pair also use this transaction, so an in-flight scan cannot
            // restore a deleted pairing or overwrite a newer one.
            if (readCredential(prefs, AiService.ANTIGRAVITY) == expected) {
                prefs.putEncryptedString("${AiService.ANTIGRAVITY.name}_companion_host", host)
                applied = true
            }
        }
        updateCache(updated)
        return applied
    }

    suspend fun updateClaudeCompanionHostIfCurrent(
        expected: Credential.ClaudeCompanionCredential,
        host: String,
        connection: AccountConnection? = null
    ): Boolean {
        var applied = false
        val updated = dataStore.edit { prefs ->
            // Disconnect and re-pair also use this transaction, so an in-flight scan cannot
            // restore a deleted pairing or overwrite a newer one.
            val prefix = connection?.id ?: AiService.CLAUDE.name
            if (readCredential(prefs, AiService.CLAUDE, prefix) == expected) {
                prefs.putEncryptedString("${prefix}_companion_host", host)
                applied = true
            }
        }
        updateCache(updated)
        return applied
    }

    suspend fun saveCodexTelemetryCredential(credential: CodexTelemetryCredential) {
        writeCodexTelemetryCredential(null, credential)
    }

    suspend fun saveCodexTelemetryCredential(connection: AccountConnection, credential: CodexTelemetryCredential) {
        require(connection.service == AiService.CODEX) { "Telemetry requires a Codex connection" }
        writeCodexTelemetryCredential(connection, credential)
    }

    private suspend fun writeCodexTelemetryCredential(
        connection: AccountConnection?,
        credential: CodexTelemetryCredential
    ) {
        require(credential.host.isNotBlank()) { "Companion host is required" }
        require(credential.port in 1..65535) { "Invalid companion port" }
        require(credential.companionId.isNotBlank()) { "Companion ID is required" }
        require(credential.sharedKeyBase64Url.isNotBlank()) { "Companion key is required" }
        dataStore.edit { prefs ->
            require(connection == null || prefs.containsConnection(connection)) { "Connection no longer exists" }
            prefs.removeCodexTelemetryEntries()
            prefs[KEY_CODEX_TELEMETRY_CONNECTION] = connection?.id ?: AiService.CODEX.name
            prefs.putEncryptedString("${CODEX_TELEMETRY_PREFIX}host", credential.host)
            prefs[longPreferencesKey("${CODEX_TELEMETRY_PREFIX}port")] = credential.port.toLong()
            prefs.putEncryptedString("${CODEX_TELEMETRY_PREFIX}id", credential.companionId)
            prefs.putEncryptedString(
                "${CODEX_TELEMETRY_PREFIX}shared_key",
                credential.sharedKeyBase64Url
            )
        }
    }

    suspend fun loadCodexTelemetryCredential(): CodexTelemetryCredential? {
        return readCodexTelemetryCredential(readPreferences(), AiService.CODEX.name)
    }

    suspend fun loadCodexTelemetryCredential(connection: AccountConnection): CodexTelemetryCredential? {
        val prefs = readPreferences()
        if (connection.service != AiService.CODEX || !prefs.containsConnection(connection)) return null
        return readCodexTelemetryCredential(prefs, connection.id)
    }

    private fun readCodexTelemetryCredential(prefs: Preferences, connectionId: String): CodexTelemetryCredential? {
        val owner = prefs[KEY_CODEX_TELEMETRY_CONNECTION] ?: AiService.CODEX.name
        if (owner != connectionId) return null
        val host = prefs.getEncryptedString("${CODEX_TELEMETRY_PREFIX}host") ?: return null
        val port = prefs[longPreferencesKey("${CODEX_TELEMETRY_PREFIX}port")]
            ?.takeIf { it in 1..65535 }
            ?.toInt()
            ?: return null
        val companionId = prefs.getEncryptedString("${CODEX_TELEMETRY_PREFIX}id") ?: return null
        val sharedKey = prefs.getEncryptedString("${CODEX_TELEMETRY_PREFIX}shared_key") ?: return null
        return CodexTelemetryCredential(host, port, companionId, sharedKey)
    }

    suspend fun deleteCodexTelemetryCredential() {
        dataStore.edit { prefs -> prefs.removeCodexTelemetryEntries() }
    }

    suspend fun deleteCodexTelemetryCredential(connection: AccountConnection) {
        dataStore.edit { prefs ->
            val owner = prefs[KEY_CODEX_TELEMETRY_CONNECTION] ?: AiService.CODEX.name
            if (connection.service == AiService.CODEX && owner == connection.id) {
                prefs.removeCodexTelemetryEntries()
            }
        }
    }

    suspend fun deleteCredential(service: AiService) {
        deleteCredential(AccountConnection.legacy(service))
    }

    suspend fun deleteCredential(connection: AccountConnection) {
        val updated = dataStore.edit { prefs ->
            if (prefs.containsConnection(connection)) {
                prefs.removeConnectionEntries(connection.id)
                val telemetryOwner = prefs[KEY_CODEX_TELEMETRY_CONNECTION] ?: AiService.CODEX.name
                if (telemetryOwner == connection.id) prefs.removeCodexTelemetryEntries()
            }
        }
        updateCache(updated)
    }

    suspend fun deleteAllCredentials() {
        val updated = dataStore.edit { prefs ->
            readConnections(prefs).forEach { prefs.removeConnectionEntries(it.id) }
            AiService.entries.forEach { prefs.removeServiceEntries(it) }
            prefs.removeCodexTelemetryEntries()
        }
        updateCache(updated)
    }

    fun hasCredential(service: AiService): Boolean {
        refreshCacheAsync()
        return service in cachedSettings.credentialServices
    }

    fun getRefreshInterval(): Long {
        refreshCacheAsync()
        return cachedSettings.refreshIntervalMinutes
    }

    suspend fun setRefreshInterval(minutes: Long) {
        val updated = dataStore.edit { prefs ->
            prefs[KEY_REFRESH_INTERVAL] = minutes
        }
        updateCache(updated)
    }

    fun isPersistentNotificationEnabled(): Boolean {
        refreshCacheAsync()
        return cachedSettings.persistentNotificationEnabled
    }

    suspend fun setPersistentNotificationEnabled(enabled: Boolean) {
        val updated = dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS_ENABLED] = enabled
        }
        updateCache(updated)
    }

    fun getPrivacySettings(): PrivacySettings {
        refreshCacheAsync()
        return cachedSettings.privacySettings
    }

    suspend fun setPrivacySettings(settings: PrivacySettings) {
        val updated = dataStore.edit { prefs ->
            prefs[KEY_PRIVACY_SCREEN_ENABLED] = settings.screenPrivacyEnabled
            prefs[KEY_PRIVACY_LOCK_SCREEN_REDACTION_ENABLED] = settings.lockScreenRedactionEnabled
            prefs[KEY_PRIVACY_NOTIFICATION_REDACTION_ENABLED] = settings.notificationRedactionEnabled
            prefs[KEY_PRIVACY_WIDGET_REDACTION_ENABLED] = settings.widgetRedactionEnabled
        }
        updateCache(updated)
    }

    suspend fun setMinimalDisplayEnabled(enabled: Boolean) {
        val updated = dataStore.edit { prefs ->
            prefs[KEY_MINIMAL_DISPLAY_ENABLED] = enabled
        }
        updateCache(updated)
    }

    suspend fun setAppThemeStyle(style: AppThemeStyle) {
        val updated = dataStore.edit { prefs ->
            prefs[KEY_APP_THEME_STYLE] = style.name
        }
        updateCache(updated)
    }

    suspend fun saveResetTimes(service: AiService, windows: List<Pair<String, Instant?>>) {
        saveResetTimes(AccountConnection.legacy(service), windows)
    }

    suspend fun saveResetTimes(connection: AccountConnection, windows: List<Pair<String, Instant?>>) {
        val updated = dataStore.edit { prefs ->
            if (!prefs.containsConnection(connection)) return@edit
            windows.forEach { (label, resetsAt) ->
                val key = longPreferencesKey("${connection.id}_${label}_resets_at")
                if (resetsAt != null) {
                    prefs[key] = resetsAt.epochSecond
                } else {
                    prefs.remove(key)
                }
            }
        }
        updateCache(updated)
    }

    suspend fun loadResetTimes(service: AiService): Map<String, Instant> {
        return loadResetTimes(AccountConnection.legacy(service))
    }

    suspend fun loadResetTimes(connection: AccountConnection): Map<String, Instant> {
        val prefs = readPreferences()
        if (!prefs.containsConnection(connection)) return emptyMap()
        val prefix = "${connection.id}_"
        val suffix = "_resets_at"
        return prefs.asMap()
            .filter { it.key.name.startsWith(prefix) && it.key.name.endsWith(suffix) }
            .mapNotNull { (key, value) ->
                val label = key.name.removePrefix(prefix).removeSuffix(suffix)
                val epochSecond = (value as? Long)?.takeIf { it > 0 } ?: return@mapNotNull null
                label to Instant.ofEpochSecond(epochSecond)
            }
            .toMap()
    }

    private fun refreshCacheAsync() {
        if (cacheRefreshStarted) return
        synchronized(this) {
            if (cacheRefreshStarted) return
            cacheRefreshStarted = true
            cacheScope.launch {
                try {
                    warmCache()
                } catch (_: Exception) {
                    updateCachedSettings(CachedSettings())
                    synchronized(this@EncryptedPrefsManager) {
                        cacheRefreshStarted = false
                    }
                }
            }
        }
    }

    private suspend fun readPreferences(): Preferences {
        return readPreferencesOrNull() ?: emptyPreferences()
    }

    private suspend fun readPreferencesOrNull(): Preferences? {
        return try {
            dataStore.data.first()
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun updateCache(prefs: Preferences) {
        updateCachedSettings(CachedSettings(
            credentialServices = readConnections(prefs)
                .filter { connection -> readCredential(prefs, connection.service, connection.id) != null }
                .map { it.service }
                .toSet(),
            refreshIntervalMinutes = prefs[KEY_REFRESH_INTERVAL] ?: DEFAULT_REFRESH_INTERVAL_MINUTES,
            persistentNotificationEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
            appThemeStyle = AppThemeStyle.fromStoredValue(prefs[KEY_APP_THEME_STYLE]),
            minimalDisplayEnabled = prefs[KEY_MINIMAL_DISPLAY_ENABLED] ?: false,
            privacySettings = PrivacySettings(
                screenPrivacyEnabled = prefs[KEY_PRIVACY_SCREEN_ENABLED] ?: true,
                lockScreenRedactionEnabled = prefs[KEY_PRIVACY_LOCK_SCREEN_REDACTION_ENABLED] ?: true,
                notificationRedactionEnabled = prefs[KEY_PRIVACY_NOTIFICATION_REDACTION_ENABLED] ?: false,
                widgetRedactionEnabled = prefs[KEY_PRIVACY_WIDGET_REDACTION_ENABLED] ?: false
            )
        ))
    }

    private fun updateCachedSettings(settings: CachedSettings) {
        cachedSettings = settings
        _appThemeStyle.value = settings.appThemeStyle
        _minimalDisplayEnabled.value = settings.minimalDisplayEnabled
    }

    private fun readConnections(prefs: Preferences): List<AccountConnection> {
        val providers = linkedMapOf<String, AiService>()
        // Adopt shipped namespaces in place, including temporarily unreadable ciphertext.
        AiService.entries.forEach { service ->
            if (prefs[stringPreferencesKey("${service.name}_access_token")] != null ||
                prefs[stringPreferencesKey("${service.name}_companion_shared_key")] != null
            ) providers[service.name] = service
        }
        prefs.asMap().forEach { (key, value) ->
            if (key.name.endsWith("_connection_provider")) {
                val service = AiService.entries.firstOrNull { it.name == value } ?: return@forEach
                providers[key.name.removeSuffix("_connection_provider")] = service
            }
        }
        return providers.mapNotNull { (id, service) ->
            val name = prefs.getEncryptedString("${id}_connection_name")
                ?.takeIf { it.isNotBlank() && it.length <= 80 } ?: service.displayName
            runCatching { AccountConnection(id, service, name) }.getOrNull()
        }
    }

    private fun Preferences.containsConnection(connection: AccountConnection): Boolean {
        val provider = this[stringPreferencesKey("${connection.id}_connection_provider")]
        if (provider != null) return provider == connection.service.name
        return connection.id == connection.service.name && (
            this[stringPreferencesKey("${connection.id}_access_token")] != null ||
                this[stringPreferencesKey("${connection.id}_companion_shared_key")] != null
            )
    }

    private fun readCredential(prefs: Preferences, service: AiService, prefix: String = service.name): Credential? {
        return when {
            service == AiService.ANTIGRAVITY -> {
                val companionHost = prefs.getEncryptedString("${prefix}_companion_host")
                if (companionHost != null) {
                    val port = prefs[longPreferencesKey("${prefix}_companion_port")]
                        ?.takeIf { it in 1..65535 }
                        ?.toInt()
                        ?: return null
                    val companionId = prefs.getEncryptedString("${prefix}_companion_id")
                        ?: return null
                    val sharedKey = prefs.getEncryptedString("${prefix}_companion_shared_key")
                        ?: return null
                    return Credential.AntigravityCompanionCredential(
                        host = companionHost,
                        port = port,
                        companionId = companionId,
                        sharedKeyBase64Url = sharedKey
                    )
                }
                null
            }

            service == AiService.CLAUDE -> {
                val companionHost = prefs.getEncryptedString("${prefix}_companion_host")
                if (companionHost != null) {
                    val port = prefs[longPreferencesKey("${prefix}_companion_port")]
                        ?.takeIf { it in 1..65535 }
                        ?.toInt()
                        ?: return null
                    val companionId = prefs.getEncryptedString("${prefix}_companion_id")
                        ?: return null
                    val sharedKey = prefs.getEncryptedString("${prefix}_companion_shared_key")
                        ?: return null
                    return Credential.ClaudeCompanionCredential(
                        host = companionHost,
                        port = port,
                        companionId = companionId,
                        sharedKeyBase64Url = sharedKey
                    )
                }
                null
            }

            service == AiService.CODEX -> {
                val accessToken = prefs.getEncryptedString("${prefix}_access_token") ?: return null
                val refreshToken = prefs.getEncryptedString("${prefix}_refresh_token") ?: return null
                val accountId = prefs.getEncryptedString("${prefix}_account_id")
                val expiresAt = prefs[longPreferencesKey("${prefix}_expires_at")]
                    ?.takeIf { it > 0 }
                    ?.let { Instant.ofEpochSecond(it) }
                Credential.CodexCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = accountId,
                    expiresAt = expiresAt
                )
            }

            service == AiService.GEMINI -> {
                val host = prefs.getEncryptedString("${prefix}_companion_host") ?: return null
                val port = prefs[longPreferencesKey("${prefix}_companion_port")]
                    ?.takeIf { it in 1..65535 }
                    ?.toInt()
                    ?: return null
                val companionId = prefs.getEncryptedString("${prefix}_companion_id") ?: return null
                val sharedKey = prefs.getEncryptedString("${prefix}_companion_shared_key")
                    ?: return null
                Credential.GeminiCompanionCredential(
                    host = host,
                    port = port,
                    companionId = companionId,
                    sharedKeyBase64Url = sharedKey
                )
            }

            service == AiService.COPILOT -> {
                val accessToken = prefs.getEncryptedString("${prefix}_access_token") ?: return null
                Credential.CopilotCredential(accessToken = accessToken)
            }

            service.providerMetadata.secretKind != null -> {
                val accessToken = prefs.getEncryptedString("${prefix}_access_token") ?: return null
                val expectedKind = checkNotNull(service.providerMetadata.secretKind)
                val kind = prefs.getEncryptedString("${prefix}_secret_kind")
                    ?.let { runCatching { ProviderSecretKind.valueOf(it) }.getOrNull() }
                    ?: expectedKind
                if (kind != expectedKind) return null
                Credential.ProviderSecretCredential(
                    service = service,
                    kind = kind,
                    accessToken = accessToken,
                    accountReference = prefs.getEncryptedString("${prefix}_account_reference")
                )
            }
            else -> null
        }
    }

    private fun Preferences.containsLegacyGeminiTokens(): Boolean {
        val prefix = "${AiService.GEMINI.name}_"
        val hasCompanionKey = this[stringPreferencesKey("${prefix}companion_shared_key")] != null
        if (hasCompanionKey) return false
        return asMap().keys.any { key -> key.name.startsWith(prefix) }
    }

    private fun Preferences.containsLegacyClaudeTokens(): Boolean {
        val prefix = "${AiService.CLAUDE.name}_"
        val hasCompanionKey = this[stringPreferencesKey("${prefix}companion_shared_key")] != null
        if (hasCompanionKey) return false
        return asMap().keys.any { key -> key.name.startsWith(prefix) }
    }

    private fun MutablePreferences.putEncryptedString(keyName: String, value: String) {
        this[stringPreferencesKey(keyName)] = valueCipher.encryptToString(value)
    }

    private fun Preferences.getEncryptedString(keyName: String): String? {
        val encryptedValue = this[stringPreferencesKey(keyName)] ?: return null
        return valueCipher.decryptToString(encryptedValue)
    }

    private fun MutablePreferences.removeServiceEntries(service: AiService) {
        removeConnectionEntries(service.name)
    }

    private fun MutablePreferences.removeConnectionEntries(id: String) {
        val prefix = "${id}_"
        val keysToRemove = asMap().keys.filter { it.name.startsWith(prefix) }
        keysToRemove.forEach { key -> removeEntry(key) }
    }

    private fun MutablePreferences.removeEntry(key: Preferences.Key<*>) {
        @Suppress("UNCHECKED_CAST")
        remove(key as Preferences.Key<Any>)
    }

    private fun MutablePreferences.removeCodexTelemetryEntries() {
        val keysToRemove = asMap().keys.filter { it.name.startsWith(CODEX_TELEMETRY_PREFIX) }
        keysToRemove.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            remove(key as Preferences.Key<Any>)
        }
    }

    private data class CachedSettings(
        val credentialServices: Set<AiService> = emptySet(),
        val refreshIntervalMinutes: Long = DEFAULT_REFRESH_INTERVAL_MINUTES,
        val persistentNotificationEnabled: Boolean = true,
        val appThemeStyle: AppThemeStyle = AppThemeStyle.MATERIAL_3,
        val minimalDisplayEnabled: Boolean = false,
        val privacySettings: PrivacySettings = FAIL_CLOSED_PRIVACY_SETTINGS
    )

    companion object {
        const val SECURE_PREFS_NAME = SECURE_DATASTORE_NAME
        const val SECURE_DATASTORE_BACKUP_PATH = "datastore/$SECURE_DATASTORE_NAME.preferences_pb"

        private const val CODEX_TELEMETRY_PREFIX = "LOCAL_CODEX_TELEMETRY_"
        private val KEY_CODEX_TELEMETRY_CONNECTION = stringPreferencesKey("${CODEX_TELEMETRY_PREFIX}connection")
        private val CREDENTIAL_FIELDS = setOf(
            "access_token", "refresh_token", "account_id", "expires_at", "companion_host",
            "companion_port", "companion_id", "companion_shared_key", "secret_kind", "account_reference"
        )

        private val KEY_REFRESH_INTERVAL = longPreferencesKey("refresh_interval_minutes")
        private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_APP_THEME_STYLE = stringPreferencesKey("app_theme_style")
        private val KEY_MINIMAL_DISPLAY_ENABLED = booleanPreferencesKey("minimal_display_enabled")
        private val KEY_PRIVACY_SCREEN_ENABLED = booleanPreferencesKey("privacy_screen_enabled")
        private val KEY_PRIVACY_LOCK_SCREEN_REDACTION_ENABLED =
            booleanPreferencesKey("privacy_lock_screen_redaction_enabled")
        private val KEY_PRIVACY_NOTIFICATION_REDACTION_ENABLED =
            booleanPreferencesKey("privacy_notification_redaction_enabled")
        private val KEY_PRIVACY_WIDGET_REDACTION_ENABLED =
            booleanPreferencesKey("privacy_widget_redaction_enabled")
    }
}

internal interface PreferenceValueCipher {
    fun encryptToString(plainText: String): String
    fun decryptToString(encryptedValue: String): String?
}

internal suspend fun EncryptedPrefsManager.loadCredential(
    service: AiService,
    connection: AccountConnection?
): Credential? = when {
    connection == null -> loadCredential(service)
    connection.service == service -> loadCredential(connection)
    else -> null
}

private class AndroidKeyStoreValueCipher : PreferenceValueCipher {
    /**
     * Loading the Android Keystore is a binder round trip. A cold credential read decrypts dozens
     * of values, so resolving the key once keeps widget and worker composition well inside their
     * broadcast deadlines.
     */
    @Volatile
    private var cachedSecretKey: SecretKey? = null
    private val keyLock = Any()

    override fun encryptToString(plainText: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            ENVELOPE_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(":")
    }

    override fun decryptToString(encryptedValue: String): String? {
        val parts = encryptedValue.split(":", limit = 3)
        if (parts.size != 3 || parts[0] != ENVELOPE_VERSION) return null
        val iv = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return null
        val ciphertext = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull()
            ?: return null

        return decryptWithCachedKey(iv, ciphertext) ?: run {
            // A cached handle can outlive the keystore entry it points at. Drop it once and
            // resolve the key again before treating the value as undecryptable.
            invalidateCachedSecretKey()
            decryptWithCachedKey(iv, ciphertext)
        }
    }

    private fun decryptWithCachedKey(iv: ByteArray, ciphertext: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun invalidateCachedSecretKey() {
        synchronized(keyLock) {
            cachedSecretKey = null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        synchronized(keyLock) {
            cachedSecretKey?.let { return it }
            return loadOrCreateSecretKey().also { cachedSecretKey = it }
        }
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "codexbar_secure_prefs_aes_gcm"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = "v1"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
