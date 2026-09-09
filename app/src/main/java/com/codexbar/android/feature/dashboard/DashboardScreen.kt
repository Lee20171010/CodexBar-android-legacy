package com.codexbar.android.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.codexbar.android.ui.components.ProviderOrderEditor
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.presentation.QuotaPresentationSnapshot
import com.codexbar.android.core.presentation.ServiceQuotaPresentation
import com.codexbar.android.core.presentation.ServiceQuotaStatus
import com.codexbar.android.ui.theme.CodexBarSpacing
import com.codexbar.android.ui.theme.LocalCodexBarThemeProfile

private const val TwoPaneMinWidthDp = 720f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToConnections: () -> Unit,
    initialSelectedService: AiService? = null,
    onInitialSelectionConsumed: () -> Unit = {},
    minimalDisplayEnabled: Boolean = false,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val providerOrder by viewModel.providerOrder.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    DashboardContent(
        uiState = uiState,
        minimalDisplayEnabled = minimalDisplayEnabled,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        onNavigateToConnections = onNavigateToConnections,
        initialSelectedService = initialSelectedService,
        onInitialSelectionConsumed = onInitialSelectionConsumed,
        providerOrder = providerOrder,
        onProviderOrderChange = viewModel::setProviderOrder
    )
}

@Composable
fun DashboardPreviewScreen(
    snapshot: QuotaPresentationSnapshot,
    minimalDisplayEnabled: Boolean = false,
    onNavigateToConnections: () -> Unit = {}
) {
    var order by remember { mutableStateOf(emptyList<AiService>()) }
    DashboardContent(
        providerOrder = order,
        onProviderOrderChange = { order = it },
        uiState = DashboardUiState.Content(snapshot),
        minimalDisplayEnabled = minimalDisplayEnabled,
        isRefreshing = false,
        onRefresh = {},
        onNavigateToConnections = onNavigateToConnections
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    minimalDisplayEnabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onNavigateToConnections: () -> Unit,
    initialSelectedService: AiService? = null,
    onInitialSelectionConsumed: () -> Unit = {},
    providerOrder: List<AiService> = emptyList(),
    onProviderOrderChange: (List<AiService>) -> Unit = {}
) {
    val themeProfile = LocalCodexBarThemeProfile.current
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }
    var showOnlyAttention by rememberSaveable { mutableStateOf(false) }
    var showOrder by rememberSaveable { mutableStateOf(false) }
    val services = (uiState as? DashboardUiState.Content)?.snapshot?.services.orEmpty()
    val orderedServices = remember(services, providerOrder) {
        DashboardProviderOrder.apply(services, providerOrder) { it.service }
    }
    if (showOrder) {
        ModalBottomSheet(onDismissRequest = { showOrder = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.provider_order_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.dashboard_order_hint), style = MaterialTheme.typography.bodyMedium)
                ProviderOrderEditor(orderedServices.map { it.service }, onProviderOrderChange)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onProviderOrderChange(emptyList()) }, enabled = providerOrder.isNotEmpty()) {
                        Text(stringResource(R.string.dashboard_order_automatic))
                    }
                    Button(onClick = { showOrder = false }) { Text(stringResource(R.string.provider_order_done)) }
                }
            }
        }
    }

    // A notification or Now Bar entry names the provider it was opened for.
    LaunchedEffect(initialSelectedService, uiState) {
        val target = (uiState as? DashboardUiState.Content)?.snapshot?.services
            ?.firstOrNull { it.service == initialSelectedService }
        if (target != null) {
            selectedConnectionId = target.connection.id
            onInitialSelectionConsumed()
        }
    }

    val explicitlySelectedService = (uiState as? DashboardUiState.Content)
        ?.snapshot
        ?.services
        ?.firstOrNull { it.connection.id == selectedConnectionId }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (services.size > 1) {
                        TextButton(onClick = { showOrder = true }) { Text(stringResource(R.string.provider_order_action)) }
                    }
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = themeProfile.topBarContainerColor,
                    scrolledContainerColor = themeProfile.topBarContainerColor
                )
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val useTwoPane = useTwoPaneDashboard(maxWidth.value)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when (val state = uiState) {
                    is DashboardUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is DashboardUiState.Content -> {
                        if (state.snapshot.services.isEmpty()) {
                            EmptyState(onOpenConnections = onNavigateToConnections)
                        } else {
                            val failedServices = state.snapshot.services
                                .filter { it.needsAttention() }
                                .joinToString(", ") { it.accountLabel ?: it.service.displayName }
                            val errorBanner = failedServices.takeIf { it.isNotBlank() }
                                ?.let {
                                    stringResource(R.string.dashboard_needs_attention, it)
                                }
                            val paneService = explicitlySelectedService
                                ?: orderedServices.first()
                            val summary = remember(state.snapshot) {
                                state.snapshot.toDashboardSummary()
                            }
                            // The filter row disappears once everything recovers, so a stale
                            // selection must not leave the list empty with no way back.
                            val filterAttention = showOnlyAttention && summary.attentionCount > 0
                            val visibleServices = if (filterAttention) {
                                orderedServices.filter { it.needsAttention() }
                            } else {
                                orderedServices
                            }

                            if (useTwoPane) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    CardList(
                                        services = visibleServices,
                                        summary = summary,
                                        minimalDisplayEnabled = minimalDisplayEnabled,
                                        errorBanner = errorBanner,
                                        attentionCount = summary.attentionCount,
                                        showOnlyAttention = filterAttention,
                                        onAttentionFilterChange = { showOnlyAttention = it },
                                        selectedConnectionId = paneService.connection.id,
                                        onServiceClick = {
                                            selectedConnectionId = it.connection.id
                                        },
                                        onSummaryProviderClick = { provider ->
                                            selectedConnectionId = state.snapshot.services
                                                .firstOrNull { it.service == provider }?.connection?.id
                                        },
                                        modifier = Modifier.weight(0.46f)
                                    )
                                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                                    Box(
                                        modifier = Modifier
                                            .weight(0.54f)
                                            .fillMaxHeight()
                                            .padding(CodexBarSpacing.large)
                                    ) {
                                        ServiceDetailPane(
                                            service = paneService,
                                            onRefresh = onRefresh,
                                            onManageConnection = onNavigateToConnections
                                        )
                                    }
                                }
                            } else {
                                CardList(
                                    services = visibleServices,
                                    summary = summary,
                                    minimalDisplayEnabled = minimalDisplayEnabled,
                                    errorBanner = errorBanner,
                                    attentionCount = summary.attentionCount,
                                    showOnlyAttention = filterAttention,
                                    onAttentionFilterChange = { showOnlyAttention = it },
                                    selectedConnectionId = null,
                                    onServiceClick = {
                                        selectedConnectionId = it.connection.id
                                    },
                                    onSummaryProviderClick = { provider ->
                                        selectedConnectionId = state.snapshot.services
                                            .firstOrNull { it.service == provider }?.connection?.id
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (!useTwoPane) {
                explicitlySelectedService?.let { service ->
                    ServiceDetailSheet(
                        service = service,
                        onDismiss = { selectedConnectionId = null },
                        onRefresh = onRefresh,
                        onManageConnection = {
                            selectedConnectionId = null
                            onNavigateToConnections()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CardList(
    services: List<ServiceQuotaPresentation>,
    summary: DashboardSummary,
    minimalDisplayEnabled: Boolean,
    errorBanner: String?,
    attentionCount: Int,
    showOnlyAttention: Boolean,
    onAttentionFilterChange: (Boolean) -> Unit,
    selectedConnectionId: String?,
    onServiceClick: (ServiceQuotaPresentation) -> Unit,
    onSummaryProviderClick: (AiService) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "summary") {
            DashboardSummaryCard(
                summary = summary,
                onProviderClick = onSummaryProviderClick
            )
        }

        if (attentionCount > 0) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showOnlyAttention,
                        onClick = { onAttentionFilterChange(false) },
                        label = { Text(stringResource(R.string.dashboard_filter_all)) }
                    )
                    FilterChip(
                        selected = showOnlyAttention,
                        onClick = { onAttentionFilterChange(true) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.dashboard_filter_attention,
                                    attentionCount
                                )
                            )
                        }
                    )
                }
            }
        }

        if (errorBanner != null) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorBanner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        items(services, key = { it.connection.id }) { service ->
            ServiceCard(
                minimalDisplayEnabled = minimalDisplayEnabled,
                service = service,
                onClick = { onServiceClick(service) },
                selected = service.connection.id == selectedConnectionId
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenConnections: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onOpenConnections) {
                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_open_connections))
            }
        }
    }
}

internal fun useTwoPaneDashboard(widthDp: Float): Boolean {
    return widthDp >= TwoPaneMinWidthDp
}
