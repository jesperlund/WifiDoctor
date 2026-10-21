package io.github.jesperlund.wifidoctor.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.github.jesperlund.wifidoctor.ui.monitor.MonitorScreen
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.jesperlund.wifidoctor.platform.AppPermissions
import io.github.jesperlund.wifidoctor.ui.dashboard.DashboardScreen
import io.github.jesperlund.wifidoctor.ui.history.HistoryScreen
import io.github.jesperlund.wifidoctor.ui.permission.PermissionRationaleScreen
import io.github.jesperlund.wifidoctor.ui.permission.hasPermissions
import io.github.jesperlund.wifidoctor.ui.rooms.RoomsScreen
import io.github.jesperlund.wifidoctor.ui.settings.SettingsScreen

const val MONITOR_ROUTE = "monitor"

enum class Tab(val route: String, val label: String, val title: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", "WifiDoctor", Icons.Filled.Home),
    ROOMS("rooms", "Rooms", "Walk test", Icons.Filled.Place),
    HISTORY("history", "History", "History", Icons.Filled.DateRange),
    SETTINGS("settings", "Settings", "Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDoctorRoot(onPermissionsChanged: () -> Unit) {
    val context = LocalContext.current
    // Show the rationale on launch until the user grants access or chooses limited mode.
    var showRationale by rememberSaveable { mutableStateOf(!context.hasPermissions(AppPermissions.wifiPermissions())) }
    if (showRationale) {
        PermissionRationaleScreen(
            onFinished = {
                showRationale = false
                onPermissionsChanged()
            },
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by navController.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { tab -> backStack?.destination?.hierarchy?.any { it.route == tab.route } == true }
        ?: Tab.DASHBOARD
    val onMonitor = backStack?.destination?.route == MONITOR_ROUTE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onMonitor) "Call monitor" else current.title) },
                navigationIcon = {
                    if (onMonitor) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Tab.DASHBOARD.route, modifier = Modifier.padding(padding)) {
            composable(Tab.DASHBOARD.route) {
                DashboardScreen(
                    snackbar = snackbar,
                    onRequestPermissions = { showRationale = true },
                    onOpenMonitor = { navController.navigate(MONITOR_ROUTE) { launchSingleTop = true } },
                )
            }
            composable(MONITOR_ROUTE) { MonitorScreen(snackbar) }
            composable(Tab.ROOMS.route) { RoomsScreen(snackbar) }
            composable(Tab.HISTORY.route) { HistoryScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen(snackbar) }
        }
    }
}
