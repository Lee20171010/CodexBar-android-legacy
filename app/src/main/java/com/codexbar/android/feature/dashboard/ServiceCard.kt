package com.codexbar.android.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.presentation.compactModelQuotaLabel
import com.codexbar.android.core.presentation.ServiceQuotaPresentation
import com.codexbar.android.core.presentation.ServiceQuotaStatus
import com.codexbar.android.ui.components.ProviderIcon
import com.codexbar.android.ui.theme.CodexBarSpacing
import com.codexbar.android.ui.theme.CodexBarStateColors
import com.codexbar.android.ui.theme.providerVisualStyle
import com.codexbar.android.ui.theme.LocalCodexBarThemeProfile

/** A glanceable overview; history, telemetry and reset plans live in the detail sheet. */
@Composable
fun ServiceCard(
    service: ServiceQuotaPresentation,
    onClick: () -> Unit,
    selected: Boolean = false,
    minimalDisplayEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val visualStyle = providerVisualStyle(service.service)
    val themeProfile = LocalCodexBarThemeProfile.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = visualStyle.shape,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            visualStyle.accent.copy(alpha = if (selected) 0.8f else 0.22f)),
        colors = CardDefaults.cardColors(
            containerColor = if (themeProfile.usesProviderCardShapes) visualStyle.container
                else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(service.service, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(service.service.displayName, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(service.status, visualStyle.accent)
            }
            if (service.metrics.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    (listOfNotNull(service.primaryMetric) + service.metrics.filterNot { it.id == service.primaryMetric?.id }).take(2).forEach { metric ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val compactLabel = if (service.service == AiService.ANTIGRAVITY) {
                                    compactModelQuotaLabel(metric.label) ?: metric.label
                                } else metric.label
                                Text(compactLabel, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(metric.remainingLabel, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CodexBarStateColors.severityColor(metric.severity, visualStyle.accent),
                                    maxLines = 1)
                            }
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { metric.barProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = visualStyle.accent,
                                trackColor = visualStyle.accent.copy(alpha = 0.13f),
                                drawStopIndicator = {}
                            )
                            metric.resetLabel?.let { reset ->
                                Text(reset, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                Text(service.tier ?: service.status.toLabel(),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            val freshnessLabel = service.freshness.staleReason
                ?: service.freshness.ageLabel.takeIf { !minimalDisplayEnabled }
            if (freshnessLabel != null) {
                Text(freshnessLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (service.needsAttention()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ServiceQuotaStatus.toLabel(): String {
    return when (this) {
        ServiceQuotaStatus.Fresh -> stringResource(R.string.status_fresh)
        ServiceQuotaStatus.Stale -> stringResource(R.string.status_stale)
        ServiceQuotaStatus.Loading -> stringResource(R.string.status_loading)
        ServiceQuotaStatus.AuthRequired -> stringResource(R.string.status_reauthentication_required)
        ServiceQuotaStatus.RateLimited -> stringResource(R.string.status_rate_limited)
        ServiceQuotaStatus.Offline -> stringResource(R.string.status_offline)
        ServiceQuotaStatus.ProviderError -> stringResource(R.string.status_provider_error)
        ServiceQuotaStatus.Disconnected -> stringResource(R.string.status_not_connected)
        ServiceQuotaStatus.Redacted -> stringResource(R.string.status_quota_hidden)
    }
}

@Composable
private fun StatusPill(status: ServiceQuotaStatus, freshColor: Color) {
    val color = status.color(freshColor)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = status.toStatusLabel(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(
                horizontal = CodexBarSpacing.small,
                vertical = CodexBarSpacing.xsmall
            )
        )
    }
}

@Composable
private fun ServiceQuotaStatus.color(freshColor: Color): Color {
    return when (this) {
        ServiceQuotaStatus.Fresh -> freshColor
        ServiceQuotaStatus.Redacted -> MaterialTheme.colorScheme.outline
        ServiceQuotaStatus.Stale,
        ServiceQuotaStatus.Loading,
        ServiceQuotaStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        ServiceQuotaStatus.AuthRequired,
        ServiceQuotaStatus.RateLimited,
        ServiceQuotaStatus.Offline,
        ServiceQuotaStatus.ProviderError -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun ServiceQuotaStatus.toStatusLabel(): String {
    return when (this) {
        ServiceQuotaStatus.Fresh -> stringResource(R.string.status_up_to_date)
        ServiceQuotaStatus.Redacted -> stringResource(R.string.status_hidden)
        else -> toLabel()
    }
}
