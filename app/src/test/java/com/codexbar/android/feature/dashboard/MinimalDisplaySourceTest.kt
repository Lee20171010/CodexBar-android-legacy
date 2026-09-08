package com.codexbar.android.feature.dashboard

import com.codexbar.android.feature.settings.SettingsUiState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalDisplaySourceTest {
    private val appDir = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    private fun source(path: String) = File(
        appDir, "src/main/java/com/codexbar/android/$path.kt"
    ).readText()

    @Test
    fun `preference defaults off persists and survives credential removal`() {
        assertFalse(SettingsUiState().minimalDisplayEnabled)
        val prefs = source("core/security/EncryptedPrefsManager")
        assertTrue(prefs.contains("minimalDisplayEnabled = prefs[KEY_MINIMAL_DISPLAY_ENABLED] ?: false"))
        assertTrue(prefs.contains("prefs[KEY_MINIMAL_DISPLAY_ENABLED] = enabled"))
        assertTrue(prefs.contains("_minimalDisplayEnabled.value = settings.minimalDisplayEnabled"))
        val settings = source("feature/settings/SettingsViewModel")
        assertTrue(settings.contains("minimalDisplayEnabled = prefsManager.minimalDisplayEnabled.value"))
        assertTrue(settings.contains("prefsManager.setMinimalDisplayEnabled(enabled)"))
        assertTrue(settings.substringAfter("fun deleteAllCredentials()").contains(
            "minimalDisplayEnabled = it.minimalDisplayEnabled"
        ))
        assertTrue(source("feature/settings/SettingsScreen").contains(
            "onCheckedChange = viewModel::setMinimalDisplayEnabled"
        ))
    }

    @Test
    fun `live and preview dashboards receive preference in both layouts`() {
        assertTrue(source("MainActivity").contains(
            "prefsManager.minimalDisplayEnabled.collectAsStateWithLifecycle()"
        ))
        val app = source("CodexBarApp")
        assertTrue(app.substringAfter("DashboardScreen(").substringBefore(")").contains(
            "minimalDisplayEnabled = minimalDisplayEnabled"
        ))
        assertTrue(app.substringAfter("DashboardPreviewScreen(").substringBefore(")").contains(
            "minimalDisplayEnabled = minimalDisplayEnabled"
        ))
        val dashboard = source("feature/dashboard/DashboardScreen")
        assertTrue(dashboard.split("CardList(").drop(1).take(2).all {
            it.substringBefore(")").contains("minimalDisplayEnabled = minimalDisplayEnabled")
        })
        assertTrue(dashboard.substringAfter("ServiceCard(").contains(
            "minimalDisplayEnabled = minimalDisplayEnabled"
        ))
        val preview = File(appDir,
            "src/debug/java/com/codexbar/android/debug/ScreenshotActivity.kt").readText()
        assertTrue(preview.contains("prefsManager.minimalDisplayEnabled.collectAsStateWithLifecycle()"))
        assertTrue(preview.contains("minimalDisplayEnabled = minimalDisplayEnabled"))
    }

    @Test
    fun `minimal cards keep quota bars and hide only the card age`() {
        val card = source("feature/dashboard/ServiceCard")
        assertTrue(card.contains("minimalDisplayEnabled: Boolean = false"))
        assertTrue(card.contains("service.freshness.ageLabel.takeIf { !minimalDisplayEnabled }"))
        assertTrue(card.contains("service.freshness.staleReason"))
        assertTrue(card.contains("Text(metric.remainingLabel"))
        assertTrue(card.contains("metric.resetLabel?.let"))
        assertTrue(card.contains("onClick = onClick"))
        listOf("insights", "balance", "codexTelemetry", "codexResetCredits", "extraUsage",
            "renewal", "resetPlan", "QuotaGaugeBar(").forEach { hidden ->
            assertFalse(card.contains(hidden))
        }
        val gauge = source("feature/dashboard/QuotaGaugeBar")
        assertTrue(gauge.contains("showPace: Boolean = true"))
        assertTrue(gauge.contains("if (showPace) metric.pace.label"))
        assertTrue(gauge.contains("metric.resetLabel?.let(::add)"))
        assertTrue(gauge.contains("text = metric.remainingLabel"))
        assertFalse(source("feature/dashboard/ServiceDetailSheet").contains("minimalDisplayEnabled"))
    }
}
