package com.codexbar.android.core.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `api 36 monitoring notification requests promoted ongoing treatment`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/notification/QuotaNotificationService.kt"
        ).readText().replace("\r\n", "\n")
        val builderIndex = source.indexOf("private fun buildPlatformMonitoringNotification(")
        assertTrue("platform monitoring builder must exist", builderIndex >= 0)
        val builder = source.substring(builderIndex)

        assertTrue(builder.contains("putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)"))
        assertTrue(builder.contains(".setChronometerCountDown(true)"))
        assertTrue(builder.contains(".setShortCriticalText(criticalText)"))
        assertTrue(builder.contains("Notification.Builder(context, LIVE_CHANNEL_ID)"))
        assertTrue(builder.contains(".setProgressTrackerIcon("))
        assertFalse(builder.contains("setFlag(Notification.FLAG_PROMOTED_ONGOING"))
    }

    @Test
    fun `monitoring starts with a placeholder and a system countdown`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/notification/QuotaNotificationService.kt"
        ).readText().replace("\r\n", "\n")
        val monitoringIndex = source.indexOf("fun showMonitoringNotification(")
        val platformBuilderIndex = source.indexOf("private fun buildPlatformMonitoringNotification(")
        assertTrue("monitoring builder must exist", monitoringIndex >= 0)
        assertTrue("platform builder must exist", platformBuilderIndex > monitoringIndex)
        val compatBuilder = source.substring(monitoringIndex, platformBuilderIndex)

        assertTrue(compatBuilder.contains("R.string.notification_monitoring_waiting"))
        assertTrue(compatBuilder.contains(".setWhen(session.endsAtMillis)"))
        assertTrue(compatBuilder.contains(".setUsesChronometer(true)"))
        assertTrue(compatBuilder.contains(".setChronometerCountDown(true)"))
        assertTrue(source.contains("fun showMonitoringPlaceholder(session: MonitoringSession)"))
        assertTrue(source.contains("services = emptyList()"))
    }

    @Test
    fun `one snapshot independently updates persistent and live notifications`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/notification/QuotaNotificationService.kt"
        ).readText().replace("\r\n", "\n")
        val publishIndex = source.indexOf("fun publishSnapshot(")
        val quotaIndex = source.indexOf("fun showQuotaNotification(")
        assertTrue("snapshot publisher must exist", publishIndex >= 0)
        assertTrue("quota builder must follow publisher", quotaIndex > publishIndex)
        val publisher = source.substring(publishIndex, quotaIndex)

        assertTrue(publisher.contains("isPersistentNotificationEnabled()"))
        assertTrue(publisher.contains("showQuotaNotification(snapshot)"))
        assertTrue(publisher.contains("showMonitoringNotification(snapshot, monitoringSession)"))
        assertTrue(publisher.contains("cancelQuotaNotification()"))
        assertTrue(publisher.contains("cancelMonitoringNotification()"))
    }

    @Test
    fun `live progress uses the same used percentage shown on dashboard`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/notification/QuotaNotificationService.kt"
        ).readText().replace("\r\n", "\n")
        val monitoringIndex = source.indexOf("fun showMonitoringNotification(")
        val platformBuilderIndex = source.indexOf("private fun buildPlatformMonitoringNotification(")
        val monitoring = source.substring(monitoringIndex, platformBuilderIndex)

        assertTrue(monitoring.contains("primaryMetric?.usedPercent?.coerceIn(0, 100)"))
        assertFalse(monitoring.contains("barProgress?.times(100)"))
    }

    @Test
    fun `metric-less snapshot shows its error instead of waiting`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/notification/QuotaNotificationService.kt"
        ).readText().replace("\r\n", "\n")
        val formatter = source.substring(
            source.indexOf("private fun formatRemaining("),
            source.indexOf("private fun localizedString(")
        )
        val monitoring = source.substring(
            source.indexOf("fun showMonitoringNotification("),
            source.indexOf("private fun buildPlatformMonitoringNotification(")
        )
        val staleReasonIndex = formatter.indexOf("service.freshness.staleReason")
        val waitingIndex = formatter.indexOf("R.string.notification_waiting_for_data")

        assertTrue(monitoring.contains("primaryService == null || primaryMetric == null ->"))
        assertTrue(staleReasonIndex >= 0)
        assertTrue(waitingIndex > staleReasonIndex)
    }
}
