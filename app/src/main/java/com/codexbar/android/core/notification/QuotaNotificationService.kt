package com.codexbar.android.core.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codexbar.android.EXTRA_DASHBOARD_SERVICE
import com.codexbar.android.MainActivity
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.monitoring.MonitoringActionReceiver
import com.codexbar.android.core.monitoring.MonitoringSession
import com.codexbar.android.core.presentation.QuotaPresentationSnapshot
import com.codexbar.android.core.presentation.QuotaSeverity
import com.codexbar.android.core.presentation.ServiceQuotaPresentation
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.PrivacySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsManager: EncryptedPrefsManager
) {
    @Volatile private var latestSnapshot: QuotaPresentationSnapshot? = null
    private val retainedNotifications = java.util.concurrent.ConcurrentHashMap<Int, Notification>()

    companion object {
        const val CHANNEL_ID = "quota_monitor"
        const val LIVE_CHANNEL_ID = "quota_live_monitor"
        const val RESET_CHANNEL_ID = "quota_reset_alert"
        const val NOTIFICATION_ID = 1001
        const val MONITORING_NOTIFICATION_ID = 1002
        const val RESET_NOTIFICATION_ID_BASE = 2000
        const val ACTION_REFRESH = "com.codexbar.android.ACTION_REFRESH"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        private const val MAX_OVERVIEW_SERVICES = 4
        private const val OVERVIEW_SEPARATOR = " · "

        /** Mirrors the thresholds QuotaSeverity uses, so the bar and the colors agree. */
        private const val WARNING_THRESHOLD_PERCENT = 60
        private const val CRITICAL_THRESHOLD_PERCENT = 85
        private val SEVERITY_GOOD_COLOR = 0xFF34A853.toInt()
        private val SEVERITY_WARNING_COLOR = 0xFFFBBC04.toInt()
        private val SEVERITY_CRITICAL_COLOR = 0xFFEA4335.toInt()
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val monitorChannel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.notification_channel_quota_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localizedString(R.string.notification_channel_quota_description)
            setShowBadge(false)
        }

        val resetChannel = NotificationChannel(
            RESET_CHANNEL_ID,
            localizedString(R.string.notification_channel_reset_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = localizedString(R.string.notification_channel_reset_description)
        }

        val liveChannel = NotificationChannel(
            LIVE_CHANNEL_ID,
            localizedString(R.string.notification_channel_live_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localizedString(R.string.notification_channel_live_description)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(monitorChannel, liveChannel, resetChannel))
    }

    /**
     * Publishes one immutable presentation snapshot to every enabled notification surface.
     * The persistent status and the user-started Live Update are intentionally independent.
     */
    fun publishSnapshot(
        snapshot: QuotaPresentationSnapshot,
        monitoringSession: MonitoringSession?
    ) {
        latestSnapshot = snapshot
        retainedNotifications.clear()
        if (prefsManager.isPersistentNotificationEnabled()) {
            showQuotaNotification(snapshot)
        } else {
            cancelQuotaNotification()
        }

        if (monitoringSession != null) {
            showMonitoringNotification(snapshot, monitoringSession)
        } else {
            cancelMonitoringNotification()
        }
    }

    fun showQuotaNotification(snapshot: QuotaPresentationSnapshot) {
        val privacySettings = prefsManager.getPrivacySettings()
        val elapsed = snapshot.services.firstOrNull()?.freshness?.ageLabel
            ?: localizedString(R.string.notification_just_now)
        val title = localizedString(R.string.notification_quota_status_title)
        val hiddenText = localizedString(R.string.notification_quota_hidden)
        val updatedText = localizedString(R.string.notification_updated, elapsed)
        val mostUsedProgress = snapshot.services
            .mapNotNull { it.primaryMetric?.usedPercent }
            .maxOrNull()
            ?: 0
        val summary = if (privacySettings.notificationRedactionEnabled) {
            hiddenText
        } else {
            snapshot.services.firstOrNull()?.let { service ->
                localizedString(
                    R.string.notification_service_summary,
                    service.accountLabel ?: service.service.displayName,
                    formatRemaining(service)
                )
            } ?: localizedString(R.string.notification_no_quota_data)
        }
        val style = if (privacySettings.notificationRedactionEnabled) {
            NotificationCompat.InboxStyle()
                .setBigContentTitle(title)
                .addLine(hiddenText)
                .setSummaryText(updatedText)
        } else {
            NotificationCompat.InboxStyle()
                .setBigContentTitle(title)
                .setSummaryText(updatedText)
                .also { inbox ->
                    snapshot.services.take(5).forEach { service ->
                        inbox.addLine(
                            localizedString(
                                R.string.notification_service_summary,
                                service.accountLabel ?: service.service.displayName,
                                formatRemaining(service)
                            )
                        )
                    }
                }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(title)
            .setContentText(summary)
            .setSubText(updatedText)
            .setStyle(style)
            .setProgress(100, if (privacySettings.notificationRedactionEnabled) 0 else mostUsedProgress, false)
            .setContentIntent(dashboardPendingIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .applyPrivacy(
                privacySettings = privacySettings,
                channelId = CHANNEL_ID,
                redactedTitle = title,
                redactedText = hiddenText
            )
            .addAction(
                R.drawable.ic_refresh,
                localizedString(R.string.action_refresh),
                refreshPendingIntent()
            )
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showMonitoringNotification(snapshot: QuotaPresentationSnapshot, session: MonitoringSession) {
        latestSnapshot = snapshot
        val privacySettings = prefsManager.getPrivacySettings()
        val primaryService = snapshot.services
            .maxByOrNull { service -> service.primaryMetric?.usedPercent ?: -1 }
        val primaryMetric = primaryService?.primaryMetric
        val progress = if (privacySettings.notificationRedactionEnabled) {
            0
        } else {
            primaryMetric?.usedPercent?.coerceIn(0, 100) ?: 0
        }
        val remaining = session.remainingMinutes()
        val remainingDuration = localizedString(R.string.duration_minutes, remaining)
        val title = if (
            privacySettings.notificationRedactionEnabled ||
            primaryService == null ||
            primaryMetric == null
        ) {
            localizedString(R.string.notification_monitoring_title)
        } else {
            // The collapsed entry has room for one line, so it names the provider that needs
            // attention instead of a constant app-level label.
            localizedString(
                R.string.notification_monitoring_headline,
                primaryService.service.displayName,
                primaryMetric.remainingLabel
            )
        }
        val hiddenText = localizedString(R.string.notification_quota_hidden)
        val text = when {
            privacySettings.notificationRedactionEnabled -> {
                localizedString(R.string.notification_monitoring_hidden, remainingDuration)
            }
            primaryService == null || primaryMetric == null -> {
                localizedString(R.string.notification_monitoring_waiting, remainingDuration)
            }
            else -> formatRemaining(primaryService)
        }
        val overview = monitoringOverview(snapshot, privacySettings)
        val subText = localizedString(R.string.notification_live_session, remainingDuration)
        val notification = if (Build.VERSION.SDK_INT >= 36) {
            buildPlatformMonitoringNotification(
                title = title,
                text = text,
                overview = overview,
                subText = subText,
                progress = progress,
                primaryService = primaryService,
                privacySettings = privacySettings,
                endsAtMillis = session.endsAtMillis
            )
        } else {
            NotificationCompat.Builder(context, LIVE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quota)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(listOf(text, overview).filter { it.isNotBlank() }.joinToString("\n"))
                )
                .setProgress(100, progress, primaryMetric == null)
                .setContentIntent(dashboardPendingIntent(primaryService?.service))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setWhen(session.endsAtMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .applyPrivacy(
                    privacySettings = privacySettings,
                    channelId = LIVE_CHANNEL_ID,
                    redactedTitle = localizedString(R.string.notification_monitoring_title),
                    redactedText = hiddenText
                )
                .addAction(
                    R.drawable.ic_refresh,
                    localizedString(R.string.action_refresh),
                    refreshPendingIntent()
                )
                .addAction(
                    R.drawable.ic_quota,
                    localizedString(R.string.action_stop),
                    stopMonitoringPendingIntent()
                )
                .build()
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(MONITORING_NOTIFICATION_ID, notification)
    }

    /**
     * Summarizes every monitored provider on one line, so expanding the entry answers "what about
     * the others" without opening the app.
     */
    private fun monitoringOverview(
        snapshot: QuotaPresentationSnapshot,
        privacySettings: PrivacySettings
    ): String {
        if (privacySettings.notificationRedactionEnabled) return ""
        return snapshot.services
            .sortedByDescending { it.primaryMetric?.usedPercent ?: -1 }
            .take(MAX_OVERVIEW_SERVICES)
            .mapNotNull { service ->
                val metric = service.primaryMetric ?: return@mapNotNull null
                localizedString(
                    R.string.notification_overview_entry,
                    service.service.displayName,
                    metric.remainingLabel
                )
            }
            .joinToString(OVERVIEW_SEPARATOR)
    }

    fun showMonitoringPlaceholder(session: MonitoringSession) {
        showMonitoringNotification(
            snapshot = QuotaPresentationSnapshot(
                generatedAt = Instant.now(),
                services = emptyList()
            ),
            session = session
        )
    }

    fun cancelMonitoringNotification() {
        retainedNotifications.remove(MONITORING_NOTIFICATION_ID)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(MONITORING_NOTIFICATION_ID)
    }

    fun cancelQuotaNotification() {
        retainedNotifications.remove(NOTIFICATION_ID)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    fun cancelAllNotifications() {
        latestSnapshot = null
        retainedNotifications.clear()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }

    fun cancelResetNotifications(connection: AccountConnection) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter { it.tag?.startsWith("reset:${connection.id}:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun showResetNotification(service: AiService, windowLabel: String) {
        showResetNotification(AccountConnection.legacy(service), windowLabel)
    }

    fun showResetNotification(connection: AccountConnection, windowLabel: String) {
        val privacySettings = prefsManager.getPrivacySettings()
        val contentTitle = if (privacySettings.notificationRedactionEnabled) {
            localizedString(R.string.notification_reset_title)
        } else {
            localizedString(R.string.notification_reset_service_title, connection.name)
        }
        val contentText = if (privacySettings.notificationRedactionEnabled) {
            localizedString(R.string.notification_reset_generic)
        } else {
            localizedString(R.string.notification_reset_window, windowLabel)
        }

        val notification = NotificationCompat.Builder(context, RESET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(dashboardPendingIntent())
            .setAutoCancel(true)
            .applyPrivacy(
                privacySettings = privacySettings,
                channelId = RESET_CHANNEL_ID,
                redactedTitle = localizedString(R.string.notification_reset_title),
                redactedText = localizedString(R.string.notification_quota_hidden)
            )
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify("reset:${connection.id}:$windowLabel", RESET_NOTIFICATION_ID_BASE, notification)
    }

    @RequiresApi(36)
    @SuppressLint("WrongConstant")
    private fun buildPlatformMonitoringNotification(
        title: String,
        text: String,
        overview: String,
        subText: String,
        progress: Int,
        primaryService: ServiceQuotaPresentation?,
        privacySettings: PrivacySettings,
        endsAtMillis: Long
    ): Notification {
        val progressColor = when (primaryService?.primaryMetric?.severity) {
            QuotaSeverity.Good -> SEVERITY_GOOD_COLOR
            QuotaSeverity.Warning -> SEVERITY_WARNING_COLOR
            QuotaSeverity.Critical -> SEVERITY_CRITICAL_COLOR
            else -> primaryService?.service?.brandColor?.toInt() ?: Color.GRAY
        }
        // Splitting the track along the severity thresholds turns the bar itself into a reading:
        // the filled portion shows how far into the warning and critical bands usage has gone.
        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progress)
            .addProgressSegment(
                Notification.ProgressStyle.Segment(WARNING_THRESHOLD_PERCENT)
                    .setColor(SEVERITY_GOOD_COLOR)
            )
            .addProgressSegment(
                Notification.ProgressStyle.Segment(
                    CRITICAL_THRESHOLD_PERCENT - WARNING_THRESHOLD_PERCENT
                ).setColor(SEVERITY_WARNING_COLOR)
            )
            .addProgressSegment(
                Notification.ProgressStyle.Segment(100 - CRITICAL_THRESHOLD_PERCENT)
                    .setColor(SEVERITY_CRITICAL_COLOR)
            )
            .setProgressTrackerIcon(
                Icon.createWithResource(context, R.drawable.ic_quota)
            )
        primaryService?.primaryMetric?.resetPlan?.let {
            // Marks where usage has to stop to still reach the reset.
            progressStyle.addProgressPoint(
                Notification.ProgressStyle.Point(CRITICAL_THRESHOLD_PERCENT)
                    .setColor(SEVERITY_CRITICAL_COLOR)
            )
        }

        val publicVersion = Notification.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(localizedString(R.string.notification_monitoring_title))
            .setContentText(localizedString(R.string.notification_quota_hidden))
            .setShowWhen(false)
            .build()
        val criticalText = shortCriticalText(primaryService, privacySettings)

        return Notification.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(title)
            .setContentText(listOf(text, overview).filter { it.isNotBlank() }.joinToString(" — "))
            .setSubText(subText)
            .setStyle(progressStyle)
            .setColor(progressColor)
            .setContentIntent(dashboardPendingIntent(primaryService?.service))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(endsAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setShortCriticalText(criticalText)
            .addExtras(
                Bundle().apply {
                    putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                }
            )
            .setPublicVersion(publicVersion.takeIf { privacySettings.lockScreenRedactionEnabled })
            .setVisibility(
                if (privacySettings.lockScreenRedactionEnabled) Notification.VISIBILITY_PRIVATE
                else Notification.VISIBILITY_PUBLIC
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_refresh),
                    localizedString(R.string.action_refresh),
                    refreshPendingIntent()
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_quota),
                    localizedString(R.string.action_stop),
                    stopMonitoringPendingIntent()
                ).build()
            )
            .build()
    }

    private fun refreshPendingIntent(): PendingIntent {
        val refreshIntent = Intent(context, RefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopMonitoringPendingIntent(): PendingIntent {
        val intent = Intent(context, MonitoringActionReceiver::class.java).apply {
            action = MonitoringActionReceiver.ACTION_STOP_MONITORING
        }
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Returns the short label the collapsed Now Bar entry shows.
     *
     * Remaining quota is the number the entry exists to communicate, so it is preferred over the
     * used percentage the progress bar already conveys.
     */
    private fun shortCriticalText(
        primaryService: ServiceQuotaPresentation?,
        privacySettings: PrivacySettings
    ): String {
        if (privacySettings.notificationRedactionEnabled) {
            return localizedString(R.string.notification_live_short)
        }
        val remainingPercent = primaryService?.primaryMetric?.remainingPercent
            ?: return localizedString(R.string.notification_live_short)
        return localizedString(R.string.notification_live_remaining_short, remainingPercent)
    }

    /**
     * Opens the dashboard, on [service]'s detail when the entry was about one provider.
     */
    private fun dashboardPendingIntent(service: AiService? = null): PendingIntent {
        val uri = android.net.Uri.parse("codexbar://dashboard").let { base ->
            service?.let {
                base.buildUpon().appendQueryParameter(EXTRA_DASHBOARD_SERVICE, it.name).build()
            } ?: base
        }
        val dashboardIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            service?.ordinal?.plus(1) ?: 0,
            dashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatRemaining(service: ServiceQuotaPresentation): String {
        val primaryMetric = service.primaryMetric
        if (primaryMetric == null) {
            return service.freshness.staleReason
                ?: localizedString(R.string.notification_waiting_for_data)
        }
        val resetText = primaryMetric.resetLabel?.let { " - $it" } ?: ""
        val guidance = primaryMetric.resetPlan?.compactActionLabel
            ?: primaryMetric.pace.label.takeIf { it.isNotBlank() }
        val guidanceText = guidance?.let { " - $it" } ?: ""
        return "${primaryMetric.remainingLabel} (${primaryMetric.label})$resetText$guidanceText"
    }

    private fun localizedString(@StringRes resourceId: Int, vararg formatArgs: Any): String {
        return ContextCompat.getContextForLanguage(context)
            .getString(resourceId, *formatArgs)
    }

    /** Apply a user's privacy change immediately, without waiting for another provider request. */
    fun refreshPrivacySettings(session: MonitoringSession?) {
        latestSnapshot?.let {
            val currentServices = it.services.filter { service -> prefsManager.hasCredential(service.service) }
            publishSnapshot(it.copy(services = currentServices), session)
            return
        }
        // After process death Android may still hold notifications, even though this instance has
        // no snapshot. Recover their existing content only when detail redaction permits it.
        val privacy = prefsManager.getPrivacySettings()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter {
            it.id == NOTIFICATION_ID || it.id == MONITORING_NOTIFICATION_ID
        }.forEach { active ->
            val original = retainedNotifications.getOrPut(active.id) { active.notification }
            val channel = active.notification.channelId
            val generic = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_quota)
                .setContentTitle(localizedString(R.string.notification_monitoring_title))
                .setContentText(localizedString(R.string.notification_quota_hidden))
                .setContentIntent(original.contentIntent)
                .setActions(*original.actions.orEmpty())
                .setOngoing(original.flags and Notification.FLAG_ONGOING_EVENT != 0)
                .setWhen(original.`when`)
                .setUsesChronometer(original.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                .setChronometerCountDown(original.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
                .setShowWhen(original.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
                .build()
            val builder = Notification.Builder.recoverBuilder(
                context, if (privacy.notificationRedactionEnabled) generic else original
            )
                .setVisibility(if (privacy.lockScreenRedactionEnabled) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
                .setPublicVersion(generic.takeIf { privacy.lockScreenRedactionEnabled })
            manager.notify(active.id, builder.build())
        }
    }

    private fun NotificationCompat.Builder.applyPrivacy(
        privacySettings: PrivacySettings,
        channelId: String,
        redactedTitle: String,
        redactedText: String
    ): NotificationCompat.Builder {
        if (!privacySettings.lockScreenRedactionEnabled) {
            return setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(redactedTitle)
            .setContentText(redactedText)
            .setShowWhen(false)
            .build()

        return setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
    }
}
