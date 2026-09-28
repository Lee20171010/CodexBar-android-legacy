package com.codexbar.android.core.security

import android.content.Context
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.Credential
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenRefreshStateStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(service: AiService): TokenRefreshRetryState? {
        val fingerprint = prefs.getString("${service.name}_fingerprint", null) ?: return null
        val nextAttemptAtMillis = prefs.getLong("${service.name}_next_attempt_at_ms", 0L)
        val failureCount = prefs.getInt("${service.name}_failure_count", 0)
        val terminal = prefs.getBoolean("${service.name}_terminal", false)
        return TokenRefreshRetryState(
            credentialFingerprint = fingerprint,
            nextAttemptAtMillis = nextAttemptAtMillis,
            failureCount = failureCount,
            terminal = terminal
        )
    }

    fun save(service: AiService, state: TokenRefreshRetryState) {
        prefs.edit()
            .putString("${service.name}_fingerprint", state.credentialFingerprint)
            .putLong("${service.name}_next_attempt_at_ms", state.nextAttemptAtMillis)
            .putInt("${service.name}_failure_count", state.failureCount)
            .putBoolean("${service.name}_terminal", state.terminal)
            .apply()
    }

    fun reset(service: AiService) {
        prefs.edit()
            .remove("${service.name}_fingerprint")
            .remove("${service.name}_next_attempt_at_ms")
            .remove("${service.name}_failure_count")
            .remove("${service.name}_terminal")
            .apply()
    }

    fun fingerprintFor(service: AiService, credential: Credential): String {
        val subject = when (credential) {
            is Credential.AntigravityCompanionCredential -> listOf(
                credential.companionId,
                credential.sharedKeyBase64Url
            )
            is Credential.ClaudeCompanionCredential -> listOf(
                credential.companionId,
                credential.sharedKeyBase64Url
            )

            is Credential.CodexCredential -> listOf(
                credential.refreshToken,
                credential.accountId.orEmpty()
            )

            is Credential.GeminiCompanionCredential -> listOf(
                credential.companionId,
                credential.sharedKeyBase64Url
            )

            is Credential.CopilotCredential -> listOf(credential.accessToken)

            is Credential.ProviderSecretCredential -> listOf(
                credential.service.name,
                credential.kind.name,
                credential.accessToken,
                credential.accountReference.orEmpty()
            )
        }.joinToString(separator = "\u001F")

        return "${service.name}:${subject.sha256Hex()}"
    }

    private fun String.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val PREFS_NAME = "codexbar_token_refresh_state"
        const val BACKUP_PATH = "$PREFS_NAME.xml"
    }
}
