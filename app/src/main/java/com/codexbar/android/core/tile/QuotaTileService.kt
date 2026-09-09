package com.codexbar.android.core.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.codexbar.android.MainActivity
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.widget.WidgetPrefsManager
import com.codexbar.android.core.workmanager.WorkManagerInitializer
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class QuotaTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    @Inject
    lateinit var prefsManager: EncryptedPrefsManager

    @Inject
    lateinit var widgetPrefsManager: WidgetPrefsManager

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (AiService.entries.any { prefsManager.hasCredential(it) }) {
            WorkManagerInitializer.enqueueManualQuotaRefresh(this, source = "tile")
            updateTile(subtitleOverride = localizedString(R.string.tile_refreshing))
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("codexbar://dashboard")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startLegacyActivityAndCollapse(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startLegacyActivityAndCollapse(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun updateTile(subtitleOverride: String? = null) {
        serviceScope.launch {
            prefsManager.warmCache()
            renderTile(prefsManager.loadConnections(), subtitleOverride)
        }
    }

    private fun renderTile(connections: List<AccountConnection>, subtitleOverride: String?) {
        val tile = qsTile ?: return

        val hasAnyCredential = connections.isNotEmpty()

        if (!hasAnyCredential) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = localizedString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = localizedString(R.string.tile_tap_to_setup)
            }
            tile.updateTile()
            return
        }

        tile.state = Tile.STATE_ACTIVE
        tile.label = localizedString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleOverride ?: buildSummarySubtitle(connections)
        }
        tile.updateTile()
    }

    private fun buildSummarySubtitle(services: List<AccountConnection>): String {
        val privacySettings = prefsManager.getPrivacySettings()
        if (privacySettings.widgetRedactionEnabled || privacySettings.notificationRedactionEnabled) {
            return localizedString(R.string.tile_quota_hidden)
        }

        val cachedService = services
            .mapNotNull { service ->
                val labels = widgetPrefsManager.getCachedLabels(service.id)
                if (labels.isEmpty()) return@mapNotNull null
                val primaryLabel = labels.maxBy { widgetPrefsManager.getCachedUtilization(service.id, it) }
                val maxUtilization = widgetPrefsManager.getCachedUtilization(service.id, primaryLabel)
                val updatedAt = widgetPrefsManager.getCachedUpdatedAt(service.id)
                TileSnapshot(
                    service = service,
                    utilization = maxUtilization,
                    updatedAt = updatedAt,
                    remainingLabel = widgetPrefsManager.getCachedRemainingLabel(service.id, primaryLabel)
                )
            }
            .maxByOrNull { it.utilization }

        if (cachedService == null) {
            return services.joinToString(" | ") { it.name }
        }

        val age = formatAge(cachedService.updatedAt)
        return "${cachedService.service.name}: ${cachedService.remainingLabel}$age"
    }

    private fun formatAge(updatedAtMillis: Long): String {
        if (updatedAtMillis <= 0) return ""
        val duration = Duration.between(Instant.ofEpochMilli(updatedAtMillis), Instant.now())
        if (duration.isNegative) return ""
        return when {
            duration.toMinutes() < 1 -> localizedString(R.string.tile_age_just_now)
            duration.toMinutes() < 60 -> localizedString(
                R.string.tile_age_minutes,
                duration.toMinutes()
            )
            else -> localizedString(R.string.tile_age_hours, duration.toHours())
        }
    }

    private fun localizedString(@StringRes resourceId: Int, vararg formatArgs: Any): String {
        return ContextCompat.getContextForLanguage(this)
            .getString(resourceId, *formatArgs)
    }

    private data class TileSnapshot(
        val service: AccountConnection,
        val utilization: Float,
        val updatedAt: Long,
        val remainingLabel: String
    )
}
