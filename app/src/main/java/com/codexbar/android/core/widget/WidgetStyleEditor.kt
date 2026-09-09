package com.codexbar.android.core.widget

import android.appwidget.AppWidgetHostView
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.compose
import com.codexbar.android.ui.components.ConnectionOrderEditor
import com.codexbar.android.ui.components.ProviderOrderEditor
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetStyleEditor(config: WidgetDisplayConfig, connections: List<AccountConnection>, onChange: (WidgetDisplayConfig) -> Unit) {
    val style = config.style
    val orderedConnections = connections.filter { it.id in config.connectionIds }
    Text(stringResource(R.string.widget_studio_title), style = MaterialTheme.typography.headlineSmall)
    if (orderedConnections.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.provider_order_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.widget_provider_order_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConnectionOrderEditor(orderedConnections) { reordered ->
                    onChange(config.copy(connectionIds = reordered.map { it.id }))
                }
            }
        }
    }
    WidgetLivePreview(config)
    Text(stringResource(R.string.widget_studio_preview_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WidgetTemplate.entries.forEachIndexed { index, template ->
            FilterChip(selected = style.template == template,
                onClick = { onChange(config.copy(style = style.copy(template = template))) },
                label = { Text("${index + 1} · ${stringResource(template.titleResource())}") })
        }
    }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.widget_studio_background), style = MaterialTheme.typography.titleMedium)
            WidgetColorEditor(style.backgroundRgb) { onChange(config.copy(style = style.copy(backgroundRgb = it))) }
            Text(stringResource(R.string.widget_studio_opacity, style.opacity))
            Slider(style.opacity.toFloat(), { onChange(config.copy(style = style.copy(opacity = it.roundToInt()))) },
                valueRange = 0f..100f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetForeground.entries.forEach { foreground ->
                    FilterChip(selected = style.foreground == foreground,
                        onClick = { onChange(config.copy(style = style.copy(foreground = foreground))) },
                        label = { Text(stringResource(if (foreground == WidgetForeground.LIGHT)
                            R.string.widget_studio_light_text else R.string.widget_studio_dark_text)) })
                }
            }
            Text(stringResource(R.string.widget_studio_radius, style.cornerRadius))
            Slider(style.cornerRadius.toFloat(), { onChange(config.copy(style = style.copy(cornerRadius = it.roundToInt()))) },
                valueRange = 0f..28f)
            Text(stringResource(R.string.widget_studio_font, (style.fontScale * 100).roundToInt()))
            Slider(style.fontScale, { onChange(config.copy(style = style.copy(fontScale = it))) }, valueRange = .8f..1.2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.widget_studio_secondary), Modifier.weight(1f))
                Switch(style.showSecondary, { onChange(config.copy(style = style.copy(showSecondary = it))) })
            }
        }
    }
    if (orderedConnections.isNotEmpty()) {
        val services = orderedConnections.map { it.service }.distinct()
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.widget_studio_provider_colors), style = MaterialTheme.typography.titleMedium)
                var selected by remember { mutableStateOf(services.first()) }
                val current = selected.takeIf { it in services } ?: services.first()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    services.forEach { service ->
                        FilterChip(current == service, { selected = service }, label = {
                            Text(if (service == AiService.COPILOT) "Copilot" else service.displayName)
                        })
                    }
                }
                WidgetColorEditor(style.accent(current) and 0xFFFFFF) { rgb ->
                    onChange(config.copy(style = style.copy(providerColors = style.providerColors + (current to rgb))))
                }

            }
        }
    }
}

@Composable
internal fun WidgetLivePreview(config: WidgetDisplayConfig) {
    val context = LocalContext.current.applicationContext
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(config) {
        delay(180)
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(4_000) {
                runCatching { QuotaGlanceWidget(config).compose(context, size = DpSize(347.dp, 69.dp)) }.getOrNull()
            }
        }
        views = result
        failed = result == null
    }
    Box(Modifier.fillMaxWidth().height(105.dp)
        .background(Brush.linearGradient(listOf(Color(0xFF334C57), Color(0xFF665774), Color(0xFF292D42))),
            RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        val rendered = views
        if (rendered != null) {
            AndroidView(factory = { AppWidgetHostView(it.applicationContext).apply { setPadding(0, 0, 0, 0) } },
                modifier = Modifier.width(347.dp).height(69.dp),
                update = { it.updateAppWidget(rendered); it.setPadding(0, 0, 0, 0) })
        } else {
            Text(stringResource(if (failed) R.string.widget_studio_preview_failed else R.string.widget_preparing),
                color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetColorEditor(rgb: Int, onColor: (Int) -> Unit) {
    var hex by remember(rgb) { mutableStateOf(String.format(Locale.ROOT, "%06X", rgb and 0xFFFFFF)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Color(0xFF000000.toInt() or rgb), RoundedCornerShape(10.dp)))
        OutlinedTextField(value = hex, onValueChange = { value ->
            hex = value.removePrefix("#").take(6)
            if (hex.length == 6) hex.toIntOrNull(16)?.let(onColor)
        }, prefix = { Text("#") }, label = { Text(stringResource(R.string.widget_studio_hex)) },
            singleLine = true, modifier = Modifier.weight(1f))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0x303238, 0x171A22, 0xE5E5E5, 0x79DABC, 0xB9BDF4, 0xEDB48F, 0xF28DAC).forEach { color ->
            Surface(onClick = { onColor(color) }, color = Color(0xFF000000.toInt() or color),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.size(32.dp)) {}
        }
    }
    listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${(rgb shr shift) and 255}", Modifier.width(46.dp), style = MaterialTheme.typography.labelSmall)
            Slider(((rgb shr shift) and 255).toFloat(), { value ->
                onColor((rgb and (255 shl shift).inv()) or (value.roundToInt() shl shift))
            }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
        }
    }
}

internal fun WidgetTemplate.titleResource(): Int = when (this) {
    WidgetTemplate.LEDGER -> R.string.widget_template_ledger
    WidgetTemplate.METERS -> R.string.widget_template_meters
    WidgetTemplate.COLUMNS -> R.string.widget_template_columns
    WidgetTemplate.TILES -> R.string.widget_template_tiles
    WidgetTemplate.RINGS -> R.string.widget_template_rings
    WidgetTemplate.SEGMENTS -> R.string.widget_template_segments
    WidgetTemplate.VERTICAL -> R.string.widget_template_vertical
    WidgetTemplate.FOCUS -> R.string.widget_template_focus
    WidgetTemplate.DUAL -> R.string.widget_template_dual
    WidgetTemplate.RESET -> R.string.widget_template_reset
    WidgetTemplate.DUAL_SEGMENTS -> R.string.widget_template_dual_segments
    WidgetTemplate.SEGMENTS_DUAL -> R.string.widget_template_segments_dual
}
