package com.codexbar.android.core.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePreferencesImplementationTest {

    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val repoDir: File = listOf(File("."), File(".."))
        .first { File(it, "gradle/libs.versions.toml").isFile }

    @Test
    fun `secure preferences use datastore and value encryption`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/security/EncryptedPrefsManager.kt"
        ).readText()

        assertTrue(source.contains("preferencesDataStore"))
        assertTrue(source.contains("AndroidKeyStore"))
        assertTrue(source.contains("AES/GCM/NoPadding"))
        assertFalse(source.contains("EncryptedSharedPreferences"))
        assertFalse(source.contains("MasterKeys"))
    }

    @Test
    fun `credential key is resolved once instead of per value`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/security/EncryptedPrefsManager.kt"
        ).readText().replace("\r\n", "\n")
        val cipher = source.substring(source.indexOf("private class AndroidKeyStoreValueCipher"))

        assertTrue(cipher.contains("private var cachedSecretKey: SecretKey?"))
        assertTrue(cipher.contains("cachedSecretKey?.let { return it }"))
        assertTrue(cipher.contains("private fun invalidateCachedSecretKey()"))
        // A stale handle must not silently turn every stored credential into a missing one.
        assertTrue(cipher.contains("invalidateCachedSecretKey()"))
        // Only the cache miss path may reach the keystore.
        assertEquals(1, cipher.split("KeyStore.getInstance(ANDROID_KEYSTORE)").size - 1)
        assertTrue(
            cipher.substringAfter("private fun loadOrCreateSecretKey()")
                .contains("KeyStore.getInstance(ANDROID_KEYSTORE)")
        )
    }

    @Test
    fun `Claude relocation checks the current pairing inside the write transaction`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/security/EncryptedPrefsManager.kt"
        ).readText()
        val relocation = source.substringAfter("suspend fun updateClaudeCompanionHostIfCurrent(")
            .substringBefore("suspend fun saveCodexTelemetryCredential(")
        val transaction = relocation.substringAfter("dataStore.edit { prefs ->")
            .substringBefore("updateCache(updated)")

        assertTrue(transaction.contains("val prefix = connection?.id ?: AiService.CLAUDE.name"))
        assertTrue(transaction.contains("if (readCredential(prefs, AiService.CLAUDE, prefix) == expected)"))
        assertTrue(transaction.contains("prefs.putEncryptedString"))
        assertFalse(relocation.contains("loadCredential("))
        assertFalse(relocation.contains("saveCredential("))
        assertFalse(transaction.contains("removeServiceEntries"))
        assertTrue(relocation.contains("return applied"))
    }

    @Test
    fun `security crypto dependency is removed`() {
        val appBuild = File(appDir, "build.gradle.kts").readText()
        val versionCatalog = File(repoDir, "gradle/libs.versions.toml").readText()

        assertFalse(appBuild.contains("security.crypto"))
        assertFalse(versionCatalog.contains("security-crypto"))
    }
}
