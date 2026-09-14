package com.dabhiram.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.notification.NAV_DESTINATION_REPORTS
import com.dabhiram.expensetracker.ui.screens.HomeScreen
import com.dabhiram.expensetracker.ui.screens.InboxScreen
import com.dabhiram.expensetracker.ui.screens.LogViewerScreen
import com.dabhiram.expensetracker.ui.screens.ReportsScreen
import com.dabhiram.expensetracker.ui.screens.SettingsScreen
import com.dabhiram.expensetracker.ui.viewmodel.InboxViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Reports : Screen("reports", "Reports", Icons.Default.PieChart)
    object Inbox : Screen("inbox", "Inbox", Icons.Default.Inbox)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object LogViewer : Screen("log_viewer", "Logs", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Reports, Screen.Inbox, Screen.Settings)

@Composable
fun AppNavigation(
    repository: TransactionRepository,
    pendingTransactionId: String?,
    pendingNavDestination: String? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // InboxViewModel at nav level so badge can reflect both uncategorized + budget alerts
    val inboxVm: InboxViewModel = viewModel(factory = InboxViewModel.Factory(repository))
    val uncategorizedCount by inboxVm.uncategorized.collectAsState()
    val budgetAlerts by inboxVm.budgetAlerts.collectAsState()
    val inboxBadgeCount = uncategorizedCount.size + budgetAlerts.count { it.percentUsed > 1.0f }

    LaunchedEffect(pendingTransactionId) {
        if (pendingTransactionId != null) {
            navController.navigate(Screen.Inbox.route) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(pendingNavDestination) {
        if (pendingNavDestination == NAV_DESTINATION_REPORTS) {
            navController.navigate(Screen.Reports.route) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            if (screen == Screen.Inbox && inboxBadgeCount > 0) {
                                BadgedBox(badge = {
                                    Badge {
                                        Text(if (inboxBadgeCount > 99) "99+" else "$inboxBadgeCount")
                                    }
                                }) {
                                    Icon(screen.icon, contentDescription = screen.label)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.label)
                            }
                        },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    repository = repository,
                    onNavigateToInbox = {
                        navController.navigate(Screen.Inbox.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Reports.route) {
                ReportsScreen(repository = repository)
            }
            composable(Screen.Inbox.route) {
                InboxScreen(
                    inboxVm = inboxVm,
                    pendingTransactionId = pendingTransactionId
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    repository = repository,
                    onViewLogs = { navController.navigate(Screen.LogViewer.route) }
                )
            }
            composable(Screen.LogViewer.route) {
                LogViewerScreen()
            }
        }
    }
}
