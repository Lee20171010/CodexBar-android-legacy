package com.codexbar.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.codexbar.android.feature.dashboard.DashboardScreen
import com.codexbar.android.feature.dashboard.DashboardPreviewScreen
import com.codexbar.android.feature.settings.ConnectionsScreen
import com.codexbar.android.feature.settings.SettingsScreen
import com.codexbar.android.feature.settings.SettingsViewModel
import com.codexbar.android.ui.theme.LocalCodexBarThemeProfile
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.presentation.QuotaPresentationSnapshot

private const val DashboardRoute = "dashboard"
private const val ConnectionsRoute = "connections"
private const val SettingsRoute = "settings"
private const val ExpandedNavigationMinWidthDp = 600f

private enum class AppDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Dashboard(
        route = DashboardRoute,
        labelRes = R.string.navigation_dashboard,
        icon = Icons.Rounded.DonutLarge
    ),
    Connections(
        route = ConnectionsRoute,
        labelRes = R.string.connections_title,
        icon = Icons.AutoMirrored.Rounded.Login
    ),
    Settings(
        route = SettingsRoute,
        labelRes = R.string.settings_title,
        icon = Icons.Rounded.Settings
    )
}

@Composable
fun CodexBarApp(
    initialDestination: String,
    initialGeminiPairingUri: String? = null,
    onGeminiPairingConsumed: () -> Unit = {},
    initialCodexTelemetryPairingUri: String? = null,
    onCodexTelemetryPairingConsumed: () -> Unit = {},
    initialDashboardService: AiService? = null,
    onDashboardServiceConsumed: () -> Unit = {},
    dashboardPreviewSnapshot: QuotaPresentationSnapshot? = null,
    minimalDisplayEnabled: Boolean = false,
    onScreenPrivacyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val themeProfile = LocalCodexBarThemeProfile.current
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route ?: initialDestination
    var initialDestinationHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialDestination, initialDestinationHandled) {
        if (!initialDestinationHandled) {
            initialDestinationHandled = true
            if (initialDestination == ConnectionsRoute || initialDestination == SettingsRoute) {
                navController.navigate(initialDestination) {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(initialGeminiPairingUri) {
        if (initialGeminiPairingUri != null) {
            navController.navigate(ConnectionsRoute) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(initialCodexTelemetryPairingUri) {
        if (initialCodexTelemetryPairingUri != null) {
            navController.navigate(ConnectionsRoute) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(initialDashboardService) {
        if (initialDashboardService != null) {
            navController.navigateTopLevel(DashboardRoute)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(themeProfile.backgroundBrush)
    ) {
        if (useExpandedNavigation(maxWidth.value)) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(88.dp),
                    containerColor = themeProfile.navigationContainerColor,
                    header = {
                        Surface(
                            modifier = Modifier.padding(vertical = 12.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(
                                modifier = Modifier.padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DonutLarge,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                ) {
                    AppDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigateTopLevel(destination.route)
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = stringResource(destination.labelRes)
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) }
                        )
                    }
                }
                VerticalDivider()
                AppNavHost(
                    navController = navController,
                    initialGeminiPairingUri = initialGeminiPairingUri,
                    onGeminiPairingConsumed = onGeminiPairingConsumed,
                    initialCodexTelemetryPairingUri = initialCodexTelemetryPairingUri,
                    onCodexTelemetryPairingConsumed = onCodexTelemetryPairingConsumed,
                    initialDashboardService = initialDashboardService,
                    onDashboardServiceConsumed = onDashboardServiceConsumed,
                    onScreenPrivacyChanged = onScreenPrivacyChanged,
                    settingsViewModel = settingsViewModel,
                    dashboardPreviewSnapshot = dashboardPreviewSnapshot,
                    minimalDisplayEnabled = minimalDisplayEnabled,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = themeProfile.navigationContainerColor) {
                        AppDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigateTopLevel(destination.route)
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = stringResource(destination.labelRes)
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                AppNavHost(
                    navController = navController,
                    initialGeminiPairingUri = initialGeminiPairingUri,
                    onGeminiPairingConsumed = onGeminiPairingConsumed,
                    initialCodexTelemetryPairingUri = initialCodexTelemetryPairingUri,
                    onCodexTelemetryPairingConsumed = onCodexTelemetryPairingConsumed,
                    initialDashboardService = initialDashboardService,
                    onDashboardServiceConsumed = onDashboardServiceConsumed,
                    onScreenPrivacyChanged = onScreenPrivacyChanged,
                    settingsViewModel = settingsViewModel,
                    dashboardPreviewSnapshot = dashboardPreviewSnapshot,
                    minimalDisplayEnabled = minimalDisplayEnabled,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    initialGeminiPairingUri: String?,
    onGeminiPairingConsumed: () -> Unit,
    initialCodexTelemetryPairingUri: String?,
    onCodexTelemetryPairingConsumed: () -> Unit,
    initialDashboardService: AiService?,
    onDashboardServiceConsumed: () -> Unit,
    onScreenPrivacyChanged: (Boolean) -> Unit,
    settingsViewModel: SettingsViewModel,
    dashboardPreviewSnapshot: QuotaPresentationSnapshot?,
    minimalDisplayEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = DashboardRoute,
        modifier = modifier
    ) {
        composable(DashboardRoute) {
            val navigateToConnections = {
                navController.navigateTopLevel(ConnectionsRoute)
            }
            if (dashboardPreviewSnapshot == null) {
                DashboardScreen(
                    onNavigateToConnections = navigateToConnections,
                    initialSelectedService = initialDashboardService,
                    onInitialSelectionConsumed = onDashboardServiceConsumed,
                    minimalDisplayEnabled = minimalDisplayEnabled
                )
            } else {
                DashboardPreviewScreen(
                    snapshot = dashboardPreviewSnapshot,
                    minimalDisplayEnabled = minimalDisplayEnabled,
                    onNavigateToConnections = navigateToConnections
                )
            }
        }
        composable(ConnectionsRoute) {
            ConnectionsScreen(
                onNavigateBack = {
                    navController.navigateTopLevel(DashboardRoute)
                },
                showBackButton = false,
                initialGeminiPairingUri = initialGeminiPairingUri,
                onGeminiPairingConsumed = onGeminiPairingConsumed,
                initialCodexTelemetryPairingUri = initialCodexTelemetryPairingUri,
                onCodexTelemetryPairingConsumed = onCodexTelemetryPairingConsumed,
                viewModel = settingsViewModel
            )
        }
        composable(SettingsRoute) {
            SettingsScreen(
                onNavigateBack = {
                    navController.navigateTopLevel(DashboardRoute)
                },
                showBackButton = false,
                onScreenPrivacyChanged = onScreenPrivacyChanged,
                viewModel = settingsViewModel
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun useExpandedNavigation(widthDp: Float): Boolean {
    return widthDp >= ExpandedNavigationMinWidthDp
}
