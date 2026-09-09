package com.codexbar.android.feature.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHealthPresentationSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `dashboard and background refresh publish connection health`() {
        val dashboard = source(
            "src/main/java/com/codexbar/android/feature/dashboard/DashboardViewModel.kt"
        )
        val worker = source(
            "src/main/java/com/codexbar/android/core/workmanager/QuotaRefreshWorker.kt"
        )

        assertTrue(dashboard.contains("connectionHealthStore.record(connection, result)"))
        assertTrue(worker.contains("connectionHealthStore.record(connection, result)"))
    }

    @Test
    fun `connection card distinguishes reauthentication and temporary offline states`() {
        val screen = source(
            "src/main/java/com/codexbar/android/feature/settings/SettingsScreen.kt"
        )

        assertTrue(screen.contains("ConnectionHealth.NEEDS_REAUTHENTICATION"))
        assertTrue(screen.contains("R.string.status_reauthentication_required"))
        assertTrue(screen.contains("ConnectionHealth.OFFLINE"))
        assertTrue(screen.contains("R.string.status_offline"))
        assertTrue(screen.contains("R.string.status_verification_pending"))
    }

    private fun source(path: String): String {
        return File(appDir, path).readText().replace("\r\n", "\n")
    }
}
