package com.codexbar.android.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppThemeStyle
import com.codexbar.android.core.domain.model.ProviderAuthMode
import com.codexbar.android.core.domain.model.ProviderCategory
import com.codexbar.android.core.domain.model.providerMetadata
import com.codexbar.android.core.network.antigravity.AntigravityCompanionPairing
import com.codexbar.android.core.network.claude.ClaudeCompanionPairing
import com.codexbar.android.core.security.PrivacySettings
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.security.ConnectionHealth
import com.codexbar.android.core.workmanager.RefreshIntervalPolicy
import com.codexbar.android.ui.components.ProviderIcon
import com.codexbar.android.ui.theme.providerVisualStyle
import com.codexbar.android.ui.theme.LocalCodexBarThemeProfile
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    onScreenPrivacyChanged: (Boolean) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeProfile = LocalCodexBarThemeProfile.current
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.canPostNotifications()) }
    var promotedUpdatesAllowed by remember { mutableStateOf(context.canPostPromotedNotifications()) }
    var enablePersistentAfterSettings by remember { mutableStateOf(false) }
    var enablePersistentAfterPermission by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.current()) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val allowed = granted && context.canPostNotifications()
        notificationsAllowed = allowed
        if (allowed && enablePersistentAfterPermission) {
            viewModel.setPersistentNotificationEnabled(true)
        }
        enablePersistentAfterPermission = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val allowed = context.canPostNotifications()
        notificationsAllowed = allowed
        promotedUpdatesAllowed = context.canPostPromotedNotifications()
        if (enablePersistentAfterSettings) {
            enablePersistentAfterSettings = false
            if (allowed) {
                viewModel.setPersistentNotificationEnabled(true)
            }
        }
        viewModel.syncMonitoringState()
    }

    LaunchedEffect(uiState.isMonitoring) {
        if (!uiState.isMonitoring) return@LaunchedEffect
        while (true) {
            delay(60_000L)
            viewModel.syncMonitoringState()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = themeProfile.topBarContainerColor
                ),
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_monitoring_title),
                    description = stringResource(R.string.settings_monitoring_description)
                )

                NotificationsSection(
                    persistentEnabled = uiState.persistentNotificationEnabled,
                    notificationsAllowed = notificationsAllowed,
                    isMonitoring = uiState.isMonitoring,
                    durationMinutes = uiState.monitoringDurationMinutes,
                    remainingMinutes = uiState.monitoringRemainingMinutes,
                    hasConnectedService = uiState.serviceStates.values.any { it.isConnected },
                    promotedUpdatesAllowed = promotedUpdatesAllowed,
                    onPersistentToggle = { requested ->
                        when {
                            !requested -> viewModel.setPersistentNotificationEnabled(false)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !context.hasNotificationPermission() -> {
                                enablePersistentAfterPermission = true
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                            !context.canPostNotifications() -> {
                                enablePersistentAfterSettings = true
                                openAppNotificationSettings(context)
                            }
                            else -> viewModel.setPersistentNotificationEnabled(true)
                        }
                    },
                    onDurationChange = viewModel::setMonitoringDuration,
                    onStartMonitoring = viewModel::startMonitoring,
                    onStopMonitoring = viewModel::stopMonitoring,
                    onOpenNotificationSettings = {
                        openAppNotificationSettings(context)
                    },
                    onOpenPromotionSettings = { openPromotionSettings(context) }
                )

                RefreshIntervalSection(
                    currentMinutes = uiState.refreshIntervalMinutes,
                    onIntervalChange = { viewModel.setRefreshInterval(it) }
                )

                SettingsSectionHeader(
                    title = stringResource(R.string.settings_preferences_title),
                    description = stringResource(R.string.settings_preferences_description)
                )

                ThemeStyleSection(
                    selectedStyle = uiState.appThemeStyle,
                    onStyleSelected = viewModel::setAppThemeStyle
                )

                LanguageSection(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { language ->
                        selectedLanguage = language
                        language.apply()
                        viewModel.refreshLocalizedSurfaces()
                    }
                )

                PrivacySection(
                    settings = uiState.privacySettings,
                    onSettingsChange = { settings ->
                        viewModel.setPrivacySettings(settings)
                        onScreenPrivacyChanged(settings.screenPrivacyEnabled)
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    initialGeminiPairingUri: String? = null,
    onGeminiPairingConsumed: () -> Unit = {},
    initialCodexTelemetryPairingUri: String? = null,
    onCodexTelemetryPairingConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeProfile = LocalCodexBarThemeProfile.current
    val context = LocalContext.current
    var providerSearchQuery by rememberSaveable { mutableStateOf("") }
    var providerFilterName by rememberSaveable {
        mutableStateOf(ProviderConnectionFilter.ALL.name)
    }
    var providerCategoryName by rememberSaveable {
        mutableStateOf(ProviderCategoryFilter.ALL.name)
    }
    var expandedProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    val providerFilter = runCatching {
        ProviderConnectionFilter.valueOf(providerFilterName)
    }.getOrDefault(ProviderConnectionFilter.ALL)
    val providerCategory = runCatching {
        ProviderCategoryFilter.valueOf(providerCategoryName)
    }.getOrDefault(ProviderCategoryFilter.ALL)
    val visibleProviders = filterProviders(
        query = providerSearchQuery,
        filter = providerFilter,
        categoryFilter = providerCategory,
        serviceStates = uiState.serviceStates
    )
    val connectedCount = uiState.serviceStates.values.count {
        it.isVerifiedConnected
    }

    LaunchedEffect(initialGeminiPairingUri) {
        if (initialGeminiPairingUri != null) {
            viewModel.importGeminiPairingCode(initialGeminiPairingUri)
            expandedProviderName = AiService.GEMINI.name
            onGeminiPairingConsumed()
        }
    }

    LaunchedEffect(initialCodexTelemetryPairingUri) {
        if (initialCodexTelemetryPairingUri != null) {
            viewModel.importCodexTelemetryPairingCode(initialCodexTelemetryPairingUri)
            expandedProviderName = AiService.CODEX.name
            onCodexTelemetryPairingConsumed()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = themeProfile.topBarContainerColor
                ),
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsSectionHeader(
                    title = stringResource(R.string.connections_manage_title),
                    description = stringResource(R.string.connections_manage_description)
                )

                ConnectionSummaryCard(
                    connectedCount = connectedCount,
                    totalCount = AiService.entries.size
                )

                ProviderDirectoryControls(
                    query = providerSearchQuery,
                    onQueryChange = { providerSearchQuery = it },
                    filter = providerFilter,
                    onFilterChange = { providerFilterName = it.name },
                    categoryFilter = providerCategory,
                    onCategoryFilterChange = { providerCategoryName = it.name },
                    visibleCount = visibleProviders.size,
                    totalCount = AiService.entries.size
                )

                if (visibleProviders.isEmpty()) {
                    ProviderDirectoryEmptyState()
                }

                visibleProviders.forEach { service ->
                    val state = uiState.serviceStates[service] ?: ServiceCredentialState()
                    ServiceCredentialSection(
                        service = service,
                        state = state,
                        expanded = expandedProviderName == service.name,
                        onToggleExpanded = {
                            expandedProviderName = if (expandedProviderName == service.name) {
                                null
                            } else {
                                service.name
                            }
                        },
                        onFieldChange = { field, value ->
                            viewModel.updateField(service, field, value)
                        },
                        onStartAccountLink = { viewModel.startAccountLink(service) },
                        onOpenAccountLink = { url -> openAuthUrl(context, url) },
                        onCopyAccountCode = { code ->
                            copyToClipboard(
                                context = context,
                                text = code,
                                labelRes = R.string.clipboard_sign_in_code,
                                sensitive = true
                            )
                        },
                        onAntigravityPairingCodeChange = viewModel::updateAntigravityPairingCode,
                        onScanAntigravityPairing = {
                            startClaudePairingScan(
                                context = context,
                                onPairingCode = viewModel::importAntigravityPairingCode,
                                onFailure = viewModel::reportAntigravityPairingScanFailure
                            )
                        },
                        onPasteAntigravityPairing = {
                            val pasted = pairingCodeFromClipboard(context, AntigravityCompanionPairing.PREFIX)
                            if (pasted == null) {
                                viewModel.reportAntigravityPairingClipboardEmpty()
                            } else {
                                viewModel.importAntigravityPairingCode(pasted)
                            }
                        },
                        onConnectAntigravityCompanion = viewModel::connectAntigravityCompanion,
                        onClaudePairingCodeChange = viewModel::updateClaudePairingCode,
                        onScanClaudePairing = {
                            startClaudePairingScan(
                                context = context,
                                onPairingCode = viewModel::importClaudePairingCode,
                                onFailure = viewModel::reportClaudePairingScanFailure
                            )
                        },
                        onPasteClaudePairing = {
                            val pasted = pairingCodeFromClipboard(context)
                            if (pasted == null) {
                                viewModel.reportClaudePairingClipboardEmpty()
                            } else {
                                viewModel.importClaudePairingCode(pasted)
                            }
                        },
                        onConnectClaudeCompanion = viewModel::connectClaudeCompanion,
                        onGeminiPairingCodeChange = viewModel::updateGeminiPairingCode,
                        onConnectGeminiCompanion = viewModel::connectGeminiCompanion,
                        onCodexTelemetryPairingCodeChange = viewModel::updateCodexTelemetryPairingCode,
                        onConnectCodexTelemetryCompanion = viewModel::connectCodexTelemetryCompanion,
                        onDisconnectCodexTelemetryCompanion = viewModel::disconnectCodexTelemetryCompanion,
                        onOpenSetupGuide = {
                            openAuthUrl(context, accountGuideUrl(service))
                        },
                        onValidate = { viewModel.validateCredential(service) },
                        onDisconnect = { viewModel.showDisconnectConfirmDialog(service) }
                    )
                }

                DangerZoneSection(
                    onDeleteAll = { viewModel.showDeleteConfirmDialog() }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (uiState.showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            onConfirm = {
                viewModel.deleteAllCredentials()
                viewModel.dismissDeleteConfirmDialog()
            },
            onDismiss = { viewModel.dismissDeleteConfirmDialog() }
        )
    }

    uiState.disconnectConfirmService?.let { service ->
        DisconnectConfirmDialog(
            service = service,
            onConfirm = { viewModel.disconnectService(service) },
            onDismiss = { viewModel.dismissDisconnectConfirmDialog() }
        )
    }
}

/**
 * Returns a companion pairing code from the clipboard.
 *
 * Reading only the leading token keeps a pasted chat message or e-mail from being rejected for
 * the surrounding text, and anything that is not a pairing code is reported as empty.
 */
private fun pairingCodeFromClipboard(context: Context, prefix: String = ClaudeCompanionPairing.PREFIX): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val text = clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return text.split(Regex("\\s+"))
        .firstOrNull { it.startsWith(prefix) }
        ?.take(CLIPBOARD_PAIRING_CODE_LIMIT)
}

private const val CLIPBOARD_PAIRING_CODE_LIMIT = 512

private fun startClaudePairingScan(
    context: Context,
    onPairingCode: (String) -> Unit,
    onFailure: () -> Unit
) {
    runCatching {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let(onPairingCode) ?: onFailure()
            }
            .addOnFailureListener {
                onFailure()
            }
    }.onFailure {
        onFailure()
    }
}

@Composable
private fun ThemeStyleSection(
    selectedStyle: AppThemeStyle,
    onStyleSelected: (AppThemeStyle) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.theme_style_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.theme_style_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppThemeStyle.entries.forEach { style ->
                    ThemeStyleOption(
                        style = style,
                        selected = style == selectedStyle,
                        onClick = { onStyleSelected(style) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeStyleOption(
    style: AppThemeStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    val previewColors = when (style) {
        AppThemeStyle.MATERIAL_3 -> listOf(Color(0xFF4355B9), Color(0xFFF3DAFF), Color(0xFF52D7F0))
        AppThemeStyle.LIQUID_GLASS -> listOf(Color(0xFF65BFFF), Color(0xFFE0F4FF), Color(0xFFB391FF))
        AppThemeStyle.WINUI_3 -> listOf(Color(0xFF0067C0), Color(0xFF60CDFF), Color(0xFFF3F3F3))
        AppThemeStyle.AURORA -> listOf(Color(0xFF00E5B8), Color(0xFF7957FF), Color(0xFFFF4FA3))
    }
    Card(
        onClick = onClick,
        modifier = Modifier.width(184.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                previewColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(color, CircleShape)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.theme_style_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = stringResource(style.titleResource()),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = stringResource(style.descriptionResource()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2
            )
        }
    }
}

@StringRes
private fun AppThemeStyle.titleResource(): Int = when (this) {
    AppThemeStyle.MATERIAL_3 -> R.string.theme_style_material_3
    AppThemeStyle.LIQUID_GLASS -> R.string.theme_style_liquid_glass
    AppThemeStyle.WINUI_3 -> R.string.theme_style_winui_3
    AppThemeStyle.AURORA -> R.string.theme_style_aurora
}

@StringRes
private fun AppThemeStyle.descriptionResource(): Int = when (this) {
    AppThemeStyle.MATERIAL_3 -> R.string.theme_style_material_3_description
    AppThemeStyle.LIQUID_GLASS -> R.string.theme_style_liquid_glass_description
    AppThemeStyle.WINUI_3 -> R.string.theme_style_winui_3_description
    AppThemeStyle.AURORA -> R.string.theme_style_aurora_description
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionSummaryCard(
    connectedCount: Int,
    totalCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Login,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.connections_summary,
                        connectedCount,
                        totalCount
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.connections_summary_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSection(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.language_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.language_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { index, language ->
                    SegmentedButton(
                        selected = selectedLanguage == language,
                        onClick = { onLanguageSelected(language) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AppLanguage.entries.size
                        )
                    ) {
                        Text(stringResource(language.labelRes))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderDirectoryControls(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: ProviderConnectionFilter,
    onFilterChange: (ProviderConnectionFilter) -> Unit,
    categoryFilter: ProviderCategoryFilter,
    onCategoryFilterChange: (ProviderCategoryFilter) -> Unit,
    visibleCount: Int,
    totalCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.provider_search_label)) },
                placeholder = { Text(stringResource(R.string.provider_search_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_clear_search)
                            )
                        }
                    }
                } else {
                    null
                }
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderConnectionFilter.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == filter,
                        onClick = { onFilterChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderConnectionFilter.entries.size
                        )
                    ) {
                        Text(stringResource(option.labelRes()))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProviderCategoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == categoryFilter,
                        onClick = { onCategoryFilterChange(option) },
                        label = { Text(stringResource(option.labelRes())) }
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.provider_result_count,
                    visibleCount,
                    totalCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderDirectoryEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.provider_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.provider_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@StringRes
private fun ProviderConnectionFilter.labelRes(): Int = when (this) {
    ProviderConnectionFilter.ALL -> R.string.provider_filter_all
    ProviderConnectionFilter.CONNECTED -> R.string.provider_filter_connected
    ProviderConnectionFilter.NOT_CONNECTED -> R.string.provider_filter_not_connected
}

@StringRes
private fun ProviderCategoryFilter.labelRes(): Int = when (this) {
    ProviderCategoryFilter.ALL -> R.string.provider_category_all
    ProviderCategoryFilter.CODING -> R.string.provider_category_coding
    ProviderCategoryFilter.MODEL_API -> R.string.provider_category_model_api
    ProviderCategoryFilter.ROUTER -> R.string.provider_category_router
    ProviderCategoryFilter.MEDIA -> R.string.provider_category_media
}

@StringRes
private fun ProviderAuthMode.labelRes(): Int = when (this) {
    ProviderAuthMode.DEVICE_SIGN_IN -> R.string.provider_auth_sign_in
    ProviderAuthMode.LOCAL_COMPANION -> R.string.provider_auth_companion
    ProviderAuthMode.API_KEY -> R.string.provider_auth_api_key
    ProviderAuthMode.SESSION_COOKIE -> R.string.provider_auth_session_cookie
    ProviderAuthMode.ACCESS_TOKEN -> R.string.provider_auth_access_token
}

@StringRes
private fun ProviderCategory.labelRes(): Int = when (this) {
    ProviderCategory.CODING -> R.string.provider_category_coding
    ProviderCategory.MODEL_API -> R.string.provider_category_model_api
    ProviderCategory.ROUTER -> R.string.provider_category_router
    ProviderCategory.MEDIA -> R.string.provider_category_media
}

@Composable
private fun ServiceCredentialSection(
    service: AiService,
    state: ServiceCredentialState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onFieldChange: (String, String) -> Unit,
    onStartAccountLink: () -> Unit,
    onOpenAccountLink: (String) -> Unit,
    onCopyAccountCode: (String) -> Unit,
    onAntigravityPairingCodeChange: (String) -> Unit,
    onScanAntigravityPairing: () -> Unit,
    onPasteAntigravityPairing: () -> Unit,
    onConnectAntigravityCompanion: () -> Unit,
    onClaudePairingCodeChange: (String) -> Unit,
    onScanClaudePairing: () -> Unit,
    onPasteClaudePairing: () -> Unit,
    onConnectClaudeCompanion: () -> Unit,
    onGeminiPairingCodeChange: (String) -> Unit,
    onConnectGeminiCompanion: () -> Unit,
    onCodexTelemetryPairingCodeChange: (String) -> Unit,
    onConnectCodexTelemetryCompanion: () -> Unit,
    onDisconnectCodexTelemetryCompanion: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onValidate: () -> Unit,
    onDisconnect: () -> Unit
) {
    val visualStyle = providerVisualStyle(service)
    var showManualSetup by rememberSaveable(service) {
        mutableStateOf(service.requiresManualCredentials)
    }
    LaunchedEffect(state.hasUnsavedChanges) {
        if (state.hasUnsavedChanges) showManualSetup = true
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = visualStyle.shape,
        colors = CardDefaults.cardColors(containerColor = visualStyle.container),
        border = BorderStroke(1.dp, visualStyle.accent.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (expanded) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = MaterialTheme.shapes.small,
                    color = visualStyle.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, visualStyle.accent.copy(alpha = 0.3f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ProviderIcon(service, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = service.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.provider_metadata_summary,
                            stringResource(service.providerMetadata.category.labelRes()),
                            stringResource(service.providerMetadata.authMode.labelRes())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val statusColor = when {
                    !state.isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.connectionHealth == ConnectionHealth.NEEDS_REAUTHENTICATION -> {
                        MaterialTheme.colorScheme.error
                    }
                    state.connectionHealth == ConnectionHealth.OFFLINE -> {
                        MaterialTheme.colorScheme.tertiary
                    }
                    state.connectionHealth == ConnectionHealth.CONNECTED -> visualStyle.accent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val statusLabel = when {
                    !state.isConnected -> R.string.status_not_connected
                    state.connectionHealth == ConnectionHealth.NEEDS_REAUTHENTICATION -> {
                        R.string.status_reauthentication_required
                    }
                    state.connectionHealth == ConnectionHealth.OFFLINE -> R.string.status_offline
                    state.connectionHealth == ConnectionHealth.CONNECTED -> R.string.status_connected
                    else -> R.string.status_verification_pending
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = stringResource(statusLabel),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.ExpandLess
                    } else {
                        Icons.Rounded.ExpandMore
                    },
                    contentDescription = stringResource(
                        if (expanded) {
                            R.string.action_collapse_provider
                        } else {
                            R.string.action_expand_provider
                        },
                        service.displayName
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                when {
                    service == AiService.ANTIGRAVITY -> AntigravityCompanionSetup(
                        state = state,
                        accent = visualStyle.accent,
                        onPairingCodeChange = onAntigravityPairingCodeChange,
                        onScanPairing = onScanAntigravityPairing,
                        onPastePairing = onPasteAntigravityPairing,
                        onConnect = onConnectAntigravityCompanion
                    )
                    service == AiService.CLAUDE -> ClaudeCompanionSetup(
                        state = state,
                        accent = visualStyle.accent,
                        onPairingCodeChange = onClaudePairingCodeChange,
                        onScanPairing = onScanClaudePairing,
                        onPastePairing = onPasteClaudePairing,
                        onConnect = onConnectClaudeCompanion
                    )
                    service == AiService.GEMINI -> GeminiCompanionSetup(
                        state = state,
                        accent = visualStyle.accent,
                        onPairingCodeChange = onGeminiPairingCodeChange,
                        onConnect = onConnectGeminiCompanion
                    )
                    service.supportsAccountLink() -> AccountLinkControls(
                        service = service,
                        state = state,
                        accent = visualStyle.accent,
                        onAccent = visualStyle.onAccent,
                        onStartAccountLink = onStartAccountLink,
                        onOpenAccountLink = onOpenAccountLink,
                        onCopyAccountCode = onCopyAccountCode
                    )
                    service == AiService.CURSOR -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_cursor_setup_title),
                        body = stringResource(R.string.credential_cursor_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.ZAI -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_zai_setup_title),
                        body = stringResource(R.string.credential_zai_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.ZENMUX -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_zenmux_setup_title),
                        body = stringResource(R.string.credential_zenmux_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.KIMI -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_kimi_setup_title),
                        body = stringResource(R.string.credential_kimi_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.ELEVENLABS -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_elevenlabs_setup_title),
                        body = stringResource(R.string.credential_elevenlabs_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.OPENROUTER -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_openrouter_setup_title),
                        body = stringResource(R.string.credential_openrouter_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.SYNTHETIC -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_synthetic_setup_title),
                        body = stringResource(R.string.credential_synthetic_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.CHUTES -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_chutes_setup_title),
                        body = stringResource(R.string.credential_chutes_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.DEEPSEEK -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_deepseek_setup_title),
                        body = stringResource(R.string.credential_deepseek_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.VENICE -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_venice_setup_title),
                        body = stringResource(R.string.credential_venice_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.MOONSHOT -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_moonshot_setup_title),
                        body = stringResource(R.string.credential_moonshot_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.CLINEPASS -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_clinepass_setup_title),
                        body = stringResource(R.string.credential_clinepass_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.IBM_BOB -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_ibm_bob_setup_title),
                        body = stringResource(R.string.credential_ibm_bob_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.DEVIN -> ProviderSecretSetupGuide(
                        title = "Devin",
                        body = stringResource(R.string.credential_devin_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.FIREWORKS -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_fireworks_setup_title),
                        body = stringResource(R.string.credential_fireworks_setup_body),
                        accent = visualStyle.accent
                    )
                    service == AiService.OPENCODE_GO -> ProviderSecretSetupGuide(
                        title = stringResource(R.string.credential_opencode_go_setup_title),
                        body = stringResource(R.string.credential_opencode_go_setup_body),
                        accent = visualStyle.accent
                    )
                }

                if (service == AiService.CODEX) {
                    CodexTelemetryCompanionSetup(
                        state = state,
                        accent = visualStyle.accent,
                        onPairingCodeChange = onCodexTelemetryPairingCodeChange,
                        onConnect = onConnectCodexTelemetryCompanion,
                        onDisconnect = onDisconnectCodexTelemetryCompanion
                    )
                }

            if (service != AiService.GEMINI && service != AiService.CLAUDE && service != AiService.ANTIGRAVITY) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = visualStyle.accent,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = stringResource(R.string.account_security_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            TextButton(onClick = onOpenSetupGuide) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_view_setup_guide))
            }

            HorizontalDivider(color = visualStyle.accent.copy(alpha = 0.2f))

            if (service != AiService.GEMINI && service != AiService.CLAUDE && service != AiService.ANTIGRAVITY) {
                TextButton(
                    onClick = { showManualSetup = !showManualSetup },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (showManualSetup) {
                            Icons.Rounded.ExpandLess
                        } else {
                            Icons.Rounded.ExpandMore
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.account_manual_setup),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (showManualSetup) {
                    Text(
                        text = stringResource(R.string.account_manual_setup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ManualCredentialFields(
                        service = service,
                        state = state,
                        onFieldChange = onFieldChange,
                        onValidate = onValidate
                    )
                }
            }

            if (state.isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !state.isValidating,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_disconnect))
                }
            }

            CredentialValidationResult(state.validationResult, visualStyle.accent)

            if (state.hasUnsavedChanges) {
                Text(
                    text = stringResource(R.string.credential_changes_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
        }
    }
}

@Composable
private fun GeminiCompanionSetup(
    state: ServiceCredentialState,
    accent: Color,
    onPairingCodeChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.credential_gemini_companion_title),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Text(
                text = stringResource(R.string.credential_gemini_companion_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.credential_gemini_companion_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.geminiPairingCode,
                onValueChange = onPairingCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.credential_gemini_pairing_code)) },
                supportingText = {
                    Text(stringResource(R.string.credential_gemini_pairing_hint))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboardOptions()
            )
            Button(
                onClick = onConnect,
                enabled = state.geminiPairingCode.isNotBlank() && !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (state.isConnected) {
                            R.string.action_repair_gemini_companion
                        } else {
                            R.string.action_pair_gemini_companion
                        }
                    )
                )
            }
            Text(
                text = stringResource(R.string.credential_gemini_companion_security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

}

@Composable
private fun CodexTelemetryCompanionSetup(
    state: ServiceCredentialState,
    accent: Color,
    onPairingCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.credential_codex_telemetry_title),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Text(
                text = stringResource(R.string.credential_codex_telemetry_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.credential_codex_telemetry_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.codexTelemetryPairingCode,
                onValueChange = onPairingCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.credential_codex_telemetry_pairing_code)) },
                supportingText = {
                    Text(stringResource(R.string.credential_codex_telemetry_pairing_hint))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboardOptions()
            )
            Button(
                onClick = onConnect,
                enabled = state.codexTelemetryPairingCode.isNotBlank() &&
                    !state.isCodexTelemetryValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isCodexTelemetryValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (state.isCodexTelemetryConnected) {
                            R.string.action_repair_codex_telemetry
                        } else {
                            R.string.action_pair_codex_telemetry
                        }
                    )
                )
            }
            if (state.isCodexTelemetryConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !state.isCodexTelemetryValidating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_disconnect_codex_telemetry))
                }
            }
            Text(
                text = stringResource(R.string.credential_codex_telemetry_security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CredentialValidationResult(state.codexTelemetryValidationResult, accent)
        }
    }
}

@Composable
private fun AntigravityCompanionSetup(
    state: ServiceCredentialState,
    accent: Color,
    onPairingCodeChange: (String) -> Unit,
    onScanPairing: () -> Unit,
    onPastePairing: () -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.credential_antigravity_companion_title),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Text(
                text = stringResource(R.string.credential_antigravity_companion_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.credential_antigravity_companion_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onScanPairing,
                enabled = !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_scan_antigravity_pairing))
            }
            OutlinedButton(
                onClick = onPastePairing,
                enabled = !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_paste_antigravity_pairing))
            }
            var pairingCodeVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = state.antigravityPairingCode,
                onValueChange = onPairingCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.credential_antigravity_pairing_code)) },
                supportingText = {
                    Text(stringResource(R.string.credential_antigravity_pairing_hint))
                },
                singleLine = true,
                visualTransformation = if (pairingCodeVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { pairingCodeVisible = !pairingCodeVisible }) {
                        Icon(
                            imageVector = if (pairingCodeVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = stringResource(
                                if (pairingCodeVisible) {
                                    R.string.action_hide_pairing_code
                                } else {
                                    R.string.action_show_pairing_code
                                }
                            )
                        )
                    }
                },
                keyboardOptions = secretKeyboardOptions()
            )
            Button(
                onClick = onConnect,
                enabled = state.antigravityPairingCode.isNotBlank() && !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (state.isConnected) {
                            R.string.action_repair_antigravity_companion
                        } else {
                            R.string.action_pair_antigravity_companion
                        }
                    )
                )
            }
            Text(
                text = stringResource(R.string.credential_antigravity_companion_security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClaudeCompanionSetup(
    state: ServiceCredentialState,
    accent: Color,
    onPairingCodeChange: (String) -> Unit,
    onScanPairing: () -> Unit,
    onPastePairing: () -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.credential_claude_companion_title),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Text(
                text = stringResource(R.string.credential_claude_companion_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.credential_claude_companion_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onScanPairing,
                enabled = !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_scan_claude_pairing))
            }
            OutlinedButton(
                onClick = onPastePairing,
                enabled = !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_paste_claude_pairing))
            }
            var pairingCodeVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = state.claudePairingCode,
                onValueChange = onPairingCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.credential_claude_pairing_code)) },
                supportingText = {
                    Text(stringResource(R.string.credential_claude_pairing_hint))
                },
                singleLine = true,
                visualTransformation = if (pairingCodeVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { pairingCodeVisible = !pairingCodeVisible }) {
                        Icon(
                            imageVector = if (pairingCodeVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = stringResource(
                                if (pairingCodeVisible) {
                                    R.string.action_hide_pairing_code
                                } else {
                                    R.string.action_show_pairing_code
                                }
                            )
                        )
                    }
                },
                keyboardOptions = secretKeyboardOptions()
            )
            Button(
                onClick = onConnect,
                enabled = state.claudePairingCode.isNotBlank() && !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (state.isConnected) {
                            R.string.action_repair_claude_companion
                        } else {
                            R.string.action_pair_claude_companion
                        }
                    )
                )
            }
            Text(
                text = stringResource(R.string.credential_claude_companion_security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderSecretSetupGuide(
    title: String,
    body: String,
    accent: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = accent)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ManualCredentialFields(
    service: AiService,
    state: ServiceCredentialState,
    onFieldChange: (String, String) -> Unit,
    onValidate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.accessToken,
            onValueChange = { onFieldChange("accessToken", it) },
            label = {
                Text(
                    stringResource(
                        when {
                            service == AiService.COPILOT -> R.string.credential_github_oauth_token
                            service == AiService.ZENMUX -> R.string.credential_zenmux_management_key
                            service.providerMetadata.authMode == ProviderAuthMode.SESSION_COOKIE -> {
                                R.string.credential_provider_session_cookie
                            }
                            service.providerMetadata.authMode == ProviderAuthMode.API_KEY -> {
                                R.string.credential_provider_api_key
                            }
                            else -> R.string.credential_access_token
                        }
                    )
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = secretKeyboardOptions(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (service == AiService.CODEX) {
            OutlinedTextField(
                value = state.refreshToken,
                onValueChange = { onFieldChange("refreshToken", it) },
                label = { Text(stringResource(R.string.credential_refresh_token)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboardOptions(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (service == AiService.CODEX) {
            OutlinedTextField(
                value = state.accountId,
                onValueChange = { onFieldChange("accountId", it) },
                label = { Text(stringResource(R.string.credential_account_id_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (service.providerMetadata.requiresAccountReference) {
            OutlinedTextField(
                value = state.accountReference,
                onValueChange = { onFieldChange("accountReference", it) },
                label = { Text(stringResource(if (service == AiService.DEVIN) R.string.credential_organization_id else R.string.credential_account_slug)) },
                supportingText = { Text(stringResource(if (service == AiService.DEVIN) R.string.credential_devin_org_support else R.string.credential_fireworks_slug_support)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        OutlinedButton(
            onClick = onValidate,
            enabled = !state.isValidating &&
                state.accessToken.isNotBlank() &&
                (!service.providerMetadata.requiresAccountReference || state.accountReference.isNotBlank()),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isValidating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when {
                        state.isConnected && state.hasUnsavedChanges -> R.string.action_save_changes
                        state.isConnected -> R.string.action_revalidate
                        else -> R.string.action_validate_connect
                    }
                )
            )
        }
    }
}

@Composable
private fun CredentialValidationResult(
    validationResult: ValidationResult?,
    successColor: Color
) {
    when (validationResult) {
        is ValidationResult.Success -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.content_description_valid),
                    tint = successColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.status_valid),
                    color = successColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        is ValidationResult.Failure -> {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.content_description_invalid),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    validationResult.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun AccountLinkControls(
    service: AiService,
    state: ServiceCredentialState,
    accent: Color,
    onAccent: Color,
    onStartAccountLink: () -> Unit,
    onOpenAccountLink: (String) -> Unit,
    onCopyAccountCode: (String) -> Unit
) {
    val prompt = state.accountLinkPrompt
    var copiedCode by rememberSaveable(service) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = accent.copy(alpha = 0.12f)
        ) {
            Text(
                text = stringResource(R.string.account_connect_recommended),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
        Text(
            text = when (service) {
                AiService.CODEX -> stringResource(R.string.account_link_codex_description)
                AiService.GEMINI -> stringResource(R.string.credential_gemini_companion_body)
                AiService.COPILOT -> stringResource(R.string.account_link_copilot_description)
                AiService.ANTIGRAVITY -> stringResource(R.string.credential_antigravity_companion_body)
                AiService.CLAUDE -> stringResource(R.string.credential_claude_instructions)
                AiService.CURSOR -> stringResource(R.string.credential_cursor_setup_body)
                AiService.ZAI -> stringResource(R.string.credential_zai_setup_body)
                AiService.ZENMUX -> stringResource(R.string.credential_zenmux_setup_body)
                AiService.KIMI -> stringResource(R.string.credential_kimi_setup_body)
                AiService.ELEVENLABS -> stringResource(R.string.credential_elevenlabs_setup_body)
                AiService.OPENROUTER -> stringResource(R.string.credential_openrouter_setup_body)
                AiService.SYNTHETIC -> stringResource(R.string.credential_synthetic_setup_body)
                AiService.CHUTES -> stringResource(R.string.credential_chutes_setup_body)
                AiService.DEEPSEEK -> stringResource(R.string.credential_deepseek_setup_body)
                AiService.VENICE -> stringResource(R.string.credential_venice_setup_body)
                AiService.MOONSHOT -> stringResource(R.string.credential_moonshot_setup_body)
                AiService.CLINEPASS -> stringResource(R.string.credential_clinepass_setup_body)
                AiService.IBM_BOB -> stringResource(R.string.credential_ibm_bob_setup_body)
                AiService.DEVIN -> stringResource(R.string.credential_devin_setup_body)
                AiService.FIREWORKS -> stringResource(R.string.credential_fireworks_setup_body)
                AiService.OPENCODE_GO -> stringResource(R.string.credential_opencode_go_setup_body)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onStartAccountLink,
            enabled = !state.isAccountLinking &&
                !state.isValidating,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = onAccent
            )
        ) {
            if (state.isAccountLinking && prompt == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = onAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when {
                        state.isAccountLinking -> R.string.action_waiting_for_sign_in
                        state.isConnected -> R.string.action_reconnect_account
                        else -> R.string.action_connect_account
                    }
                )
            )
        }

        prompt?.let {
            val clipboardCode = remember(prompt.userCode) {
                deviceCodeForClipboard(prompt.userCode)
            }
            val verificationHost = remember(prompt.verificationUrl) {
                Uri.parse(prompt.verificationUrl).host ?: prompt.verificationUrl
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.account_link_steps_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = accent
                    )
                    AccountLinkInstructionStep(
                        number = 1,
                        text = stringResource(R.string.account_link_step_copy),
                        accent = accent
                    )
                    Text(
                        text = stringResource(R.string.account_link_enter_code),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = prompt.userCode,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = accent
                            )
                        }
                    }
                    Button(
                        onClick = {
                            onCopyAccountCode(clipboardCode)
                            copiedCode = clipboardCode
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = onAccent
                        )
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (copiedCode == clipboardCode) {
                                    R.string.action_copied
                                } else {
                                    R.string.action_copy_sign_in_code
                                }
                            )
                        )
                    }
                    AccountLinkInstructionStep(
                        number = 2,
                        text = stringResource(
                            R.string.account_link_step_open,
                            verificationHost
                        ),
                        accent = accent
                    )
                    OutlinedButton(
                        onClick = { onOpenAccountLink(prompt.verificationUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_sign_in_page))
                    }
                    AccountLinkInstructionStep(
                        number = 3,
                        text = stringResource(R.string.account_link_step_authorize),
                        accent = accent
                    )
                    AccountLinkInstructionStep(
                        number = 4,
                        text = stringResource(R.string.account_link_step_return),
                        accent = accent
                    )
                    Text(
                        text = stringResource(R.string.account_link_expires, prompt.expiresAtDisplay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.account_link_retry_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = accent.copy(alpha = 0.2f))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.account_link_code_safety),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountLinkInstructionStep(
    number: Int,
    text: String,
    accent: Color
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = accent.copy(alpha = 0.16f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun deviceCodeForClipboard(userCode: String): String {
    return userCode
        .filter(Char::isLetterOrDigit)
        .uppercase(Locale.ROOT)
}

private fun AiService.supportsAccountLink(): Boolean {
    return providerMetadata.authMode == ProviderAuthMode.DEVICE_SIGN_IN
}

internal fun accountGuideUrl(service: AiService): String {
    return "$ACCOUNT_GUIDE_BASE_URL#${service.providerMetadata.guideAnchor}"
}

private fun openAuthUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

private fun Context.hasNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.canPostNotifications(): Boolean {
    return hasNotificationPermission() &&
        NotificationManagerCompat.from(this).areNotificationsEnabled()
}

private fun Context.canPostPromotedNotifications(): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    val manager = getSystemService(NotificationManager::class.java) ?: return false
    return manager.canPostPromotedNotifications()
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private fun openPromotionSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 36) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    } catch (_: ActivityNotFoundException) {
        openAppNotificationSettings(context)
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
    @StringRes labelRes: Int,
    sensitive: Boolean
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(labelRes), text).apply {
        if (sensitive) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, context.getString(R.string.message_code_copied), Toast.LENGTH_SHORT).show()
    }
}

private const val ACCOUNT_GUIDE_BASE_URL =
    "https://github.com/lingmulongtai/CodexBar-android"

private fun secretKeyboardOptions(): KeyboardOptions {
    return KeyboardOptions(
        keyboardType = KeyboardType.Password,
        autoCorrectEnabled = false
    )
}

@Composable
private fun RefreshIntervalSection(
    currentMinutes: Long,
    onIntervalChange: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val automaticRefresh = currentMinutes > RefreshIntervalPolicy.MANUAL_MINUTES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.background_refresh_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (automaticRefresh) {
                            stringResource(
                                R.string.background_refresh_every,
                                formatMonitoringDuration(currentMinutes)
                            )
                        } else {
                            stringResource(R.string.background_refresh_manual_only)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = automaticRefresh,
                    onCheckedChange = { enabled ->
                        onIntervalChange(
                            if (enabled) {
                                RefreshIntervalPolicy.DEFAULT_MINUTES
                            } else {
                                RefreshIntervalPolicy.MANUAL_MINUTES
                            }
                        )
                    }
                )
            }

            if (automaticRefresh) {
                val refreshRange = RefreshIntervalPolicy.MIN_MINUTES.toFloat()..
                    RefreshIntervalPolicy.MAX_MINUTES.toFloat()
                val refreshSteps = (
                    (RefreshIntervalPolicy.MAX_MINUTES - RefreshIntervalPolicy.MIN_MINUTES) /
                        RefreshIntervalPolicy.STEP_MINUTES - 1
                    ).toInt()
                Text(
                    text = formatMonitoringDuration(currentMinutes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = currentMinutes.toFloat(),
                    onValueChange = { value ->
                        onIntervalChange(
                            RefreshIntervalPolicy.normalize(value.roundToInt().toLong())
                        )
                    },
                    valueRange = refreshRange,
                    steps = refreshSteps
                )
            }

            Text(
                text = stringResource(R.string.background_refresh_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationsSection(
    persistentEnabled: Boolean,
    notificationsAllowed: Boolean,
    isMonitoring: Boolean,
    durationMinutes: Long,
    remainingMinutes: Long?,
    hasConnectedService: Boolean,
    promotedUpdatesAllowed: Boolean,
    onPersistentToggle: (Boolean) -> Unit,
    onDurationChange: (Long) -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPromotionSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.notifications_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.notifications_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notifications_persistent_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.notifications_persistent_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = persistentEnabled,
                    onCheckedChange = onPersistentToggle
                )
            }

            if (!notificationsAllowed) {
                Text(
                    text = stringResource(R.string.notifications_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onOpenNotificationSettings) {
                    Text(stringResource(R.string.action_open_notification_settings))
                }
            }

            Text(
                text = when {
                    isMonitoring -> {
                        val remaining = if (remainingMinutes == null) {
                            stringResource(R.string.notifications_live_ending_soon)
                        } else {
                            formatMonitoringDuration(remainingMinutes)
                        }
                        stringResource(R.string.notifications_live_running, remaining)
                    }
                    persistentEnabled && notificationsAllowed -> {
                        stringResource(R.string.notifications_persistent_on)
                    }
                    persistentEnabled -> stringResource(R.string.notifications_permission_needed)
                    else -> stringResource(R.string.notifications_persistent_off)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (isMonitoring) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Text(
                text = stringResource(R.string.notifications_session_length),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = formatMonitoringDuration(durationMinutes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = durationMinutes.toFloat(),
                onValueChange = { value ->
                    val minutes = ((value / 15f).roundToInt() * 15)
                        .coerceIn(15, 180)
                        .toLong()
                    onDurationChange(minutes)
                },
                valueRange = 15f..180f,
                steps = 10,
                enabled = !isMonitoring
            )
            Text(
                text = stringResource(R.string.notifications_schedule_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isMonitoring) {
                OutlinedButton(
                    onClick = onStopMonitoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_stop_live_monitor))
                }
            } else {
                Button(
                    onClick = onStartMonitoring,
                    enabled = notificationsAllowed && hasConnectedService,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_start_live_monitor))
                }
            }

            if (!hasConnectedService) {
                Text(
                    text = stringResource(R.string.notifications_service_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (Build.VERSION.SDK_INT >= 36) {
                if (promotedUpdatesAllowed) {
                    Text(
                        text = stringResource(R.string.notifications_promoted_allowed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedButton(
                        onClick = onOpenPromotionSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_allow_promoted_updates))
                    }
                }
            }
        }
    }
}

@Composable
private fun formatMonitoringDuration(minutes: Long): String {
    if (minutes < 60) return stringResource(R.string.duration_minutes, minutes)
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0L) {
        stringResource(R.string.duration_hours, hours)
    } else {
        stringResource(R.string.duration_hours_minutes, hours, remainder)
    }
}

@Composable
private fun DangerZoneSection(onDeleteAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.danger_zone_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDeleteAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.delete_all_credentials))
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_all_credentials)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_all_credentials_confirmation))
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    label = { Text(stringResource(R.string.delete_all_credentials_type)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmText == "DELETE"
            ) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun PrivacySection(
    settings: PrivacySettings,
    onSettingsChange: (PrivacySettings) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.privacy_title),
                style = MaterialTheme.typography.titleMedium
            )

            PrivacyToggle(
                title = stringResource(R.string.privacy_screen_title),
                subtitle = stringResource(R.string.privacy_screen_description),
                checked = settings.screenPrivacyEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(screenPrivacyEnabled = it))
                }
            )
            PrivacyToggle(
                title = stringResource(R.string.privacy_lock_screen_title),
                subtitle = stringResource(R.string.privacy_lock_screen_description),
                checked = settings.lockScreenRedactionEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(lockScreenRedactionEnabled = it))
                }
            )
            PrivacyToggle(
                title = stringResource(R.string.privacy_notification_title),
                subtitle = stringResource(R.string.privacy_notification_description),
                checked = settings.notificationRedactionEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(notificationRedactionEnabled = it))
                }
            )
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, QuotaNotificationService.LIVE_CHANNEL_ID))
                } catch (_: ActivityNotFoundException) {
                    openAppNotificationSettings(context)
                }
            }) {
                Text(stringResource(R.string.privacy_lock_screen_system_settings))
            }
            PrivacyToggle(
                title = stringResource(R.string.privacy_widget_title),
                subtitle = stringResource(R.string.privacy_widget_description),
                checked = settings.widgetRedactionEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(widgetRedactionEnabled = it))
                }
            )
        }
    }
}

@Composable
private fun PrivacyToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun DisconnectConfirmDialog(
    service: AiService,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disconnect_service_title, service.displayName)) },
        text = {
            Text(stringResource(R.string.disconnect_service_message, service.displayName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_disconnect), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
