package com.codexbar.android.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexbar.android.R
import com.codexbar.android.core.presentation.QuotaMetricPresentation
import com.codexbar.android.ui.theme.CodexBarStateColors
import kotlin.math.abs

/**
 * Displays a gauge bar showing remaining quota.
 * @param utilization 0.0-1.0 representing how much has been USED
 * The bar fills with the REMAINING amount and text shows "X% left"
 */
@Composable
fun QuotaGaugeBar(
    metric: QuotaMetricPresentation,
    showExtendedDetails: Boolean = false,
    showPace: Boolean = true,
    goodColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val targetProgress = metric.barProgress.coerceIn(0f, 1f)
    val progress = remember(metric.id) { Animatable(targetProgress) }
    LaunchedEffect(targetProgress) {
        if (abs(progress.value - targetProgress) > 0.001f) {
            progress.animateTo(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing)
            )
        }
    }

    val gaugeColor = CodexBarStateColors.severityColor(metric.severity, goodColor)

    val animatedColor by animateColorAsState(
        targetValue = gaugeColor,
        animationSpec = tween(durationMillis = 400),
        label = "gauge_color"
    )
    val gaugeDescription = stringResource(
        R.string.quota_metric_description,
        metric.label,
        metric.remainingLabel
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = gaugeDescription
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .clip(RoundedCornerShape(50))
                        .background(animatedColor)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = metric.remainingLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = animatedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val detailLabels = buildList {
            metric.resetLabel?.let(::add)
            if (showPace) metric.pace.label.takeIf { it.isNotBlank() }?.let(::add)
            if (showPace && showExtendedDetails) {
                metric.pace.cycleProgressLabel?.let(::add)
                metric.pace.usageRateLabel?.let(::add)
                metric.pace.paceMultiplierLabel?.let(::add)
                metric.pace.reserveLabel?.let(::add)
                metric.pace.forecastLabel?.let(::add)
            }
        }.distinct()
        if (detailLabels.isNotEmpty()) {
            Text(
                text = detailLabels.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
                maxLines = if (showExtendedDetails) 3 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
