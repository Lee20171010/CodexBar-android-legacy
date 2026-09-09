package com.codexbar.android.core.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyCacheInitializationSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `privacy cache starts fail closed without changing saved defaults`() {
        val source = sourceFile("security/EncryptedPrefsManager.kt")

        assertTrue(source.contains("notificationRedactionEnabled = true"))
        assertTrue(source.contains("widgetRedactionEnabled = true"))
        assertTrue(source.contains("privacySettings: PrivacySettings = FAIL_CLOSED_PRIVACY_SETTINGS"))
        assertTrue(source.contains("KEY_PRIVACY_NOTIFICATION_REDACTION_ENABLED] ?: false"))
        assertTrue(source.contains("KEY_PRIVACY_WIDGET_REDACTION_ENABLED] ?: false"))
    }

    @Test
    fun `external surfaces await privacy cache before rendering`() {
        val widget = sourceFile("widget/QuotaGlanceWidget.kt")
        val worker = sourceFile("workmanager/QuotaRefreshWorker.kt")

        assertInOrder(widget, "prefsManager.warmCache()", "prefsManager.getPrivacySettings()")
        assertInOrder(
            worker,
            "prefsManager.warmCache()",
            "val connections = prefsManager.loadConnections()"
        )
        assertInOrder(worker, "prefsManager.warmCache()", "prefsManager.getPrivacySettings()")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("Missing source fragment: $first", firstIndex >= 0)
        assertTrue("Missing source fragment: $second", secondIndex >= 0)
        assertTrue("Expected '$first' before '$second'", firstIndex < secondIndex)
    }

    private fun sourceFile(relativePath: String): String {
        return File(
            appDir,
            "src/main/java/com/codexbar/android/core/$relativePath"
        ).readText().replace("\r\n", "\n")
    }
}
