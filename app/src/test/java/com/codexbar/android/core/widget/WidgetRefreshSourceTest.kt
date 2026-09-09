package com.codexbar.android.core.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `widget configuration requests fresh data after saving`() {
        val source = sourceFile("WidgetConfigurationActivity.kt")
        val functionIndex = source.indexOf("private fun confirmSelection(")
        assertTrue("confirmSelection must exist", functionIndex >= 0)
        val confirmSelection = source.substring(functionIndex)

        assertTrue(
            confirmSelection.contains("WorkManagerInitializer.enqueueManualQuotaRefresh(") &&
                confirmSelection.contains("source = \"widget_config\"")
        )
    }

    @Test
    fun `widget configuration renders before reporting success`() {
        val source = sourceFile("WidgetConfigurationActivity.kt")
        val functionIndex = source.indexOf("private fun confirmSelection(")
        assertTrue("confirmSelection must exist", functionIndex >= 0)
        val confirmSelection = source.substring(functionIndex)
        val nativePlaceholder = confirmSelection.indexOf(
            "AppWidgetManager.getInstance(this@WidgetConfigurationActivity).updateAppWidget("
        )
        val initialRender = confirmSelection.indexOf("WidgetUpdater.update(")
        val successfulResult = confirmSelection.indexOf("setResult(RESULT_OK, resultValue)")

        assertTrue("the launcher loading layout must be replaced immediately", nativePlaceholder >= 0)
        assertTrue("the initial Glance render must run", initialRender >= 0)
        assertTrue("the native placeholder must precede Glance composition", initialRender > nativePlaceholder)
        assertTrue(
            "RESULT_OK must only be returned after the initial render succeeds",
            successfulResult > initialRender
        )
        assertTrue(confirmSelection.contains("WorkManagerInitializer.enqueueWidgetRender("))
        assertTrue(confirmSelection.contains("if (!hadExistingConfiguration)"))
        assertTrue(confirmSelection.contains("withTimeoutOrNull(IMMEDIATE_RENDER_TIMEOUT_MILLIS)"))
        assertTrue(confirmSelection.contains("WidgetUpdater.update(this@WidgetConfigurationActivity, appWidgetId)"))
        assertFalse(confirmSelection.contains(".getGlanceIdBy(intent)"))
    }

    @Test
    fun `widget cancellation returns the allocated widget id`() {
        val source = sourceFile("WidgetConfigurationActivity.kt")
        val resultCanceled = source.indexOf("RESULT_CANCELED,")
        val widgetIdExtra = source.indexOf(
            "putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)",
            resultCanceled
        )

        assertTrue("RESULT_CANCELED must include the allocated App Widget ID", resultCanceled >= 0)
        assertTrue(widgetIdExtra > resultCanceled)
        assertFalse(source.contains("setResult(RESULT_CANCELED)"))
    }

    @Test
    fun `app startup repairs widgets left on their loading layout`() {
        val source = sourceFileFromCore("workmanager/WorkManagerInitializer.kt")
        val functionIndex = source.indexOf("fun applySavedRefreshPolicyAsync(")
        val functionEnd = source.indexOf("fun schedulePeriodicRefresh(", functionIndex)
        assertTrue("applySavedRefreshPolicyAsync must exist", functionIndex >= 0 && functionEnd > functionIndex)
        val initializer = source.substring(functionIndex, functionEnd)

        assertTrue(initializer.contains("WidgetUpdater.updateAll(appContext)"))
    }

    @Test
    fun `launcher widget updates cannot trigger provider network traffic`() {
        val source = sourceFile("QuotaWidgetReceiver.kt")

        assertFalse(source.contains("enqueueManualQuotaRefresh"))
        assertTrue(source.contains("override fun onUpdate("))
        assertTrue(source.contains("withTimeoutOrNull(8_000L)"))
        assertTrue(source.contains("WorkManagerInitializer.enqueueWidgetRender(appContext, it)"))
    }

    @Test
    fun `widget receiver is exported for standards compliant launchers`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml")
            .readText()
            .replace("\r\n", "\n")
        val receiver = manifest
            .substringAfter("android:name=\".core.widget.QuotaWidgetReceiver\"")
            .substringBefore("</receiver>")

        assertTrue(receiver.contains("android:exported=\"true\""))
    }

    @Test
    fun `widget preview only uses RemoteViews compatible containers`() {
        val preview = File(appDir, "src/main/res/layout/widget_preview.xml")
            .readText()
            .replace("\r\n", "\n")

        assertFalse(
            "RemoteViews cannot inflate the base android View class",
            Regex("<View(?:\\s|/|>)").containsMatchIn(preview)
        )
        assertTrue(preview.contains("<FrameLayout"))
    }

    @Test
    fun `explicit widget refresh supersedes stale manual work`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/workmanager/WorkManagerInitializer.kt"
        ).readText().replace("\r\n", "\n")
        val start = source.indexOf("fun enqueueManualQuotaRefresh(")
        val end = source.indexOf("fun startMonitoringSession(", start)
        assertTrue("enqueueManualQuotaRefresh must exist", start >= 0 && end > start)
        val enqueueManualRefresh = source.substring(start, end)

        assertTrue(enqueueManualRefresh.contains("ExistingWorkPolicy.REPLACE"))
        assertFalse(enqueueManualRefresh.contains("ExistingWorkPolicy.KEEP"))
    }

    @Test
    fun `provider failures replace waiting state with an actionable message`() {
        val worker = sourceFileFromCore("workmanager/QuotaRefreshWorker.kt")
        val widgetPrefs = sourceFile("WidgetPrefsManager.kt")
        val widget = sourceFile("QuotaGlanceWidget.kt")

        assertTrue(worker.contains("errors[connection] = result.error"))
        assertTrue(worker.contains("connectionErrors = errors"))
        assertTrue(worker.contains("cacheQuotaData(snapshot)"))
        assertTrue(widgetPrefs.contains("service.freshness.staleReason?.let"))
        assertTrue(widgetPrefs.contains("fun getCachedStatusMessage("))
        assertTrue(widgetPrefs.contains("fun cacheStatusMessageIfEmpty("))
        assertTrue(worker.contains("R.string.widget_not_connected"))
        assertTrue(sourceFile("WidgetDisplayData.kt").contains("getCachedStatusMessage(connection.id) ?: waiting"))
    }

    @Test
    fun `widget uses one exact layout and native progress bars`() {
        val widget = sourceFile("QuotaGlanceWidget.kt")

        assertTrue(widget.contains("override val sizeMode: SizeMode = SizeMode.Exact"))
        assertTrue(sourceFile("WidgetTemplates.kt").contains("LinearProgressIndicator("))
        assertTrue(sourceFile("WidgetTemplates.kt").contains("WidgetRenderPolicy.rowCount("))
        assertTrue(sourceFile("WidgetDisplayData.kt").contains("selectWidgetMetrics("))
        assertFalse(widget.contains("val totalSegments"))
        assertFalse(widget.contains("SizeMode.Responsive"))
    }

    @Test
    fun `widget composition errors have a localized fallback layout`() {
        val widget = sourceFile("QuotaGlanceWidget.kt")
        val errorLayout = File(appDir, "src/main/res/layout/widget_error.xml")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(widget.contains("GlanceAppWidget(errorUiLayout = R.layout.widget_error)"))
        assertTrue(widget.contains("override fun onCompositionError("))
        assertTrue(widget.contains("super.onCompositionError("))
        assertTrue(errorLayout.contains("@string/widget_render_error"))
    }

    @Test
    fun `delayed widget renderer performs no provider network request`() {
        val worker = sourceFileFromCore("workmanager/WidgetRenderWorker.kt")
        val initializer = sourceFileFromCore("workmanager/WorkManagerInitializer.kt")

        assertTrue(worker.contains("WidgetUpdater.update(applicationContext, appWidgetId)"))
        assertTrue(worker.contains("WidgetUpdater.update(applicationContext, appWidgetId)"))
        assertFalse(worker.contains("QuotaRepository"))
        assertFalse(worker.contains("NetworkType"))
        assertTrue(initializer.contains("OneTimeWorkRequestBuilder<WidgetRenderWorker>()"))
        assertTrue(initializer.contains("ExistingWorkPolicy.REPLACE"))
    }

    private fun sourceFile(name: String): String {
        return File(
            appDir,
            "src/main/java/com/codexbar/android/core/widget/$name"
        ).readText().replace("\r\n", "\n")
    }

    private fun sourceFileFromCore(relativePath: String): String {
        return File(
            appDir,
            "src/main/java/com/codexbar/android/core/$relativePath"
        ).readText().replace("\r\n", "\n")
    }
}
