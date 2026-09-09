package com.codexbar.android.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.codexbar.android.MainActivity
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.workmanager.WorkManagerInitializer
import com.codexbar.android.ui.components.ProviderIcon
import com.codexbar.android.ui.theme.CodexBarStateColors
import com.codexbar.android.ui.theme.CodexBarTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class WidgetConfigurationActivity : AppCompatActivity() {

    @Inject
    lateinit var encryptedPrefsManager: EncryptedPrefsManager

    @Inject
    lateinit var widgetPrefsManager: WidgetPrefsManager

    private var isCompletingConfiguration = false

    private val appWidgetId: Int by lazy {
        intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result is CANCELED — if user backs out, widget isn't added.
        // Some launchers require the allocated ID even for cancellation.
        setResult(
            RESULT_CANCELED,
            Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        lifecycleScope.launch {
            encryptedPrefsManager.warmCache()
            if (!encryptedPrefsManager.getPrivacySettings().screenPrivacyEnabled) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            val availableServices = encryptedPrefsManager.loadConnections()

            setContent {
                CodexBarTheme {
                    val existingConfig = widgetPrefsManager.getWidgetConfig(appWidgetId)
                    var liveConfig by rememberSaveable(stateSaver = WidgetConfigSaver) {
                        mutableStateOf(
                            existingConfig.copy(
                                connectionIds = existingConfig.connectionIds.ifEmpty { availableServices.map { it.id } },
                                maxRows = 2
                            )
                        )
                    }
                    val checkedState = availableServices.associate { it.id to (it.id in liveConfig.connectionIds) }
                    val anyChecked = checkedState.values.any { it }
                    val widgetHeight = AppWidgetManager.getInstance(this@WidgetConfigurationActivity)
                        .getAppWidgetOptions(appWidgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    val isReconfigure = existingConfig.connectionIds.isNotEmpty()

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.widget_setup_title)) }
                            )
                        },
                        bottomBar = {
                            BottomAppBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .widthIn(max = 720.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                setResult(
                                                    RESULT_CANCELED,
                                                    Intent().apply {
                                                        putExtra(
                                                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                                                            appWidgetId
                                                        )
                                                    }
                                                )
                                                finish()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.action_cancel))
                                        }

                                        Button(
                                            onClick = {
                                                confirmSelection(liveConfig)
                                            },
                                            enabled = anyChecked,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (isReconfigure) {
                                                        R.string.widget_setup_save
                                                    } else {
                                                        R.string.widget_setup_add
                                                    }
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .widthIn(max = 720.dp)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                WidgetStyleEditor(liveConfig, availableServices) { updated ->
                                    liveConfig = updated
                                }
                                widgetPrefsManager.renderDiagnostics(appWidgetId)?.let { diagnostics ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(stringResource(R.string.widget_diagnostics_title), style = MaterialTheme.typography.titleSmall)
                                            Text(diagnostics, style = MaterialTheme.typography.bodySmall)
                                            Text(stringResource(R.string.widget_diagnostics_description), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.widget_setup_description),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.widget_setup_services),
                                            style = MaterialTheme.typography.headlineSmall
                                        )
                                        Text(
                                            text = stringResource(R.string.widget_setup_select_service),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (availableServices.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.widget_setup_connect_first),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            OutlinedButton(onClick = ::openAppConnections) {
                                                Text(
                                                    stringResource(
                                                        R.string.widget_setup_open_app_connections
                                                    )
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = stringResource(
                                                    R.string.widget_setup_selected_count,
                                                    checkedState.values.count { it },
                                                    availableServices.size
                                                ),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            availableServices.forEach { service ->
                                                ServiceCheckRow(
                                                    service = service,
                                                    checked = checkedState[service.id] ?: false,
                                                    onCheckedChange = { checked ->
                                                        liveConfig = liveConfig.copy(connectionIds = if (checked)
                                                            liveConfig.connectionIds + service.id else liveConfig.connectionIds - service.id)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.widget_setup_display_options),
                                            style = MaterialTheme.typography.headlineSmall
                                        )
                                        ConfigToggleRow(
                                            title = stringResource(R.string.widget_setup_reset_title),
                                            subtitle = stringResource(R.string.widget_setup_reset_description),
                                            checked = liveConfig.showReset,
                                            onCheckedChange = { liveConfig = liveConfig.copy(showReset = it) }
                                        )
                                        if (widgetHeight >= 100 && liveConfig.style.template !in listOf(
                                            WidgetTemplate.LEDGER, WidgetTemplate.METERS, WidgetTemplate.DUAL, WidgetTemplate.DUAL_SEGMENTS,
                                            WidgetTemplate.SEGMENTS_DUAL, WidgetTemplate.RESET)) {
                                        ConfigToggleRow(
                                            title = stringResource(R.string.widget_setup_pace_title),
                                            subtitle = stringResource(R.string.widget_setup_pace_description),
                                            checked = liveConfig.showPace,
                                            onCheckedChange = { liveConfig = liveConfig.copy(showPace = it) }
                                        )
                                        }
                                        if (liveConfig.style.template !in listOf(WidgetTemplate.METERS, WidgetTemplate.DUAL, WidgetTemplate.DUAL_SEGMENTS, WidgetTemplate.RESET)) {
                                        ConfigToggleRow(
                                            title = stringResource(R.string.widget_setup_freshness_title),
                                            subtitle = stringResource(R.string.widget_setup_freshness_description),
                                            checked = liveConfig.showFreshness,
                                            onCheckedChange = { liveConfig = liveConfig.copy(showFreshness = it) }
                                        )
                                        }

                                    }
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.widget_setup_data_hint),
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openAppConnections() {
        setResult(
            RESULT_CANCELED,
            Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        )
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                data = Uri.parse("codexbar://connections")
            }
        )
        finish()
    }

    private fun confirmSelection(config: WidgetDisplayConfig) {
        if (isCompletingConfiguration) return
        isCompletingConfiguration = true

        val hadExistingConfiguration = widgetPrefsManager
            .getWidgetConfig(appWidgetId)
            .connectionIds
            .isNotEmpty()

        // commit() ensures data is persisted before the widget reads it
        widgetPrefsManager.saveWidgetConfig(
            appWidgetId,
            config
        )

        // Configuration widgets do not reliably receive an initial onUpdate broadcast on every
        // launcher. Attempt the documented immediate update, then always schedule a second local
        // render after the host has finished binding the widget.
        lifecycleScope.launch {
            if (!hadExistingConfiguration) {
                runCatching {
                    AppWidgetManager.getInstance(this@WidgetConfigurationActivity).updateAppWidget(
                        appWidgetId,
                        RemoteViews(packageName, R.layout.widget_loading).apply {
                            setTextViewText(
                                R.id.widget_loading_text,
                                getString(R.string.widget_preparing)
                            )
                            // A placeholder that outlives the first render must still open the app.
                            setOnClickPendingIntent(
                                R.id.widget_loading_root,
                                PendingIntent.getActivity(
                                    this@WidgetConfigurationActivity,
                                    appWidgetId,
                                    Intent(
                                        this@WidgetConfigurationActivity,
                                        MainActivity::class.java
                                    ).apply {
                                        action = Intent.ACTION_VIEW
                                        data = Uri.parse("codexbar://dashboard")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                        PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        }
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Could not install widget placeholder for id=$appWidgetId", error)
                }
            }

            try {
                val rendered = withTimeoutOrNull(IMMEDIATE_RENDER_TIMEOUT_MILLIS) {
                    WidgetUpdater.update(this@WidgetConfigurationActivity, appWidgetId)
                }
                if (rendered != true) {
                    Log.w(TAG, "Immediate widget render timed out for id=$appWidgetId")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Immediate widget render failed for id=$appWidgetId", error)
            }

            runCatching {
                WorkManagerInitializer.enqueueWidgetRender(
                    context = this@WidgetConfigurationActivity,
                    appWidgetId = appWidgetId
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not schedule widget render for id=$appWidgetId", error)
            }
            runCatching {
                WorkManagerInitializer.enqueueManualQuotaRefresh(
                    context = this@WidgetConfigurationActivity,
                    source = "widget_config"
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not schedule widget data refresh", error)
            }

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }

    companion object {
        private const val TAG = "CodexBarWidget"
        private const val IMMEDIATE_RENDER_TIMEOUT_MILLIS = 7_000L
    }
}

@Composable
private fun ConfigToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ServiceCheckRow(
    service: AccountConnection,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val accent = CodexBarStateColors.providerAccent(service.service)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (checked) {
            accent.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (checked) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = accent.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ProviderIcon(service.service, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = service.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = checked,
                onCheckedChange = null
            )
        }
    }
}
