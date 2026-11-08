package com.jamina.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jamina.ui.screens.DashboardScreen
import com.jamina.ui.screens.DiagnosticsScreen
import com.jamina.ui.screens.FloorScreen
import com.jamina.ui.screens.PlannerScreen
import com.jamina.ui.screens.ProjectListScreen
import com.jamina.ui.screens.ProjectScreen
import com.jamina.ui.screens.ReportsScreen
import com.jamina.ui.screens.RoamingScreen
import com.jamina.ui.screens.ScannerScreen
import com.jamina.ui.screens.SettingsScreen

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TopTab("dashboard", "Dashboard", Icons.Default.Dashboard),
    TopTab("scanner", "Scanner", Icons.Default.Wifi),
    TopTab("survey", "Survey", Icons.Default.Map),
    TopTab("planner", "Planner", Icons.Default.Architecture),
    TopTab("reports", "Reports", Icons.Default.Assessment),
)

private fun NavHostController.openFloor(floorId: String, mode: String?) =
    navigate("floor/$floorId?mode=${mode ?: ""}")

@Composable
fun JaminaRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }
    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = route == t.route,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(t.icon, t.label) },
                    label = { Text(t.label) },
                )
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("dashboard") {
                DashboardScreen(
                    onOpenFloor = { fid, mode -> nav.openFloor(fid, mode) },
                    onDiagnostics = { nav.navigate("diagnostics") },
                    onRoaming = { nav.navigate("roaming") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("scanner") { ScannerScreen() }
            composable("survey") {
                ProjectListScreen("Survey", "Buildings and their floors. Open one to draw or import a plan and collect measurements.", onOpen = { nav.navigate("project/$it") })
            }
            composable("planner") {
                ProjectListScreen("Planner", "Choose a building to place virtual access points and predict coverage.", onOpen = { nav.navigate("plannerProject/$it") })
            }
            composable("reports") {
                ProjectListScreen("Reports", "Choose a building to view insights, compare surveys and export reports.", onOpen = { nav.navigate("reportProject/$it") })
            }
            composable("project/{pid}", arguments = listOf(navArgument("pid") { type = NavType.StringType })) { e ->
                val pid = e.arguments?.getString("pid") ?: return@composable
                ProjectScreen(
                    pid, onBack = { nav.popBackStack() },
                    onOpenFloor = { fid, mode -> nav.openFloor(fid, mode) },
                    onPlanner = { nav.navigate("plannerProject/$pid") },
                    onReports = { nav.navigate("reportProject/$pid") },
                )
            }
            composable(
                "floor/{fid}?mode={mode}",
                arguments = listOf(
                    navArgument("fid") { type = NavType.StringType },
                    navArgument("mode") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val fid = e.arguments?.getString("fid") ?: return@composable
                val mode = e.arguments?.getString("mode")?.ifBlank { null }
                FloorScreen(fid, mode, onBack = { nav.popBackStack() })
            }
            composable("plannerProject/{pid}") { e -> PlannerScreen(e.arguments?.getString("pid") ?: return@composable, onBack = { nav.popBackStack() }) }
            composable("reportProject/{pid}") { e -> ReportsScreen(e.arguments?.getString("pid") ?: return@composable, onBack = { nav.popBackStack() }) }
            composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
            composable("roaming") { RoamingScreen(onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
