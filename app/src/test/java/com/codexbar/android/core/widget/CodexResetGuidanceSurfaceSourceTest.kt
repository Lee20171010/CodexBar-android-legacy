package com.codexbar.android.core.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexResetGuidanceSurfaceSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `widget caches and renders compact reset advice`() {
        val prefs = sourceFile("core/widget/WidgetPrefsManager.kt")
        val widget = sourceFile("core/widget/WidgetTemplates.kt") + sourceFile("core/widget/WidgetDisplayData.kt")

        assertTrue(prefs.contains("metric.resetPlan?.compactActionLabel"))
        assertTrue(prefs.contains("_reset_plan_label"))
        assertTrue(prefs.contains("fun getCachedResetPlanLabel("))
        assertTrue(widget.contains("getCachedResetPlanLabel(connection.id, label)"))
        assertTrue(widget.contains("metric.resetAdvice?.let"))
    }

    @Test
    fun `notifications prefer actionable reset advice over generic pace`() {
        val notification = sourceFile("core/notification/QuotaNotificationService.kt")

        assertTrue(notification.contains("primaryMetric.resetPlan?.compactActionLabel"))
        assertTrue(notification.contains("?: primaryMetric.pace.label"))
    }

    private fun sourceFile(relativePath: String): String {
        return File(
            appDir,
            "src/main/java/com/codexbar/android/$relativePath"
        ).readText().replace("\r\n", "\n")
    }
}
