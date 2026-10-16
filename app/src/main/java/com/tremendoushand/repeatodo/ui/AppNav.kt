package com.tremendoushand.repeatodo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tremendoushand.repeatodo.RepeatodoApp
import com.tremendoushand.repeatodo.notify.Notifications
import com.tremendoushand.repeatodo.ui.dashboard.DashboardScreen
import com.tremendoushand.repeatodo.ui.settings.SettingsScreen
import com.tremendoushand.repeatodo.ui.tasks.CategoryScreen
import com.tremendoushand.repeatodo.ui.tasks.TaskEditScreen
import com.tremendoushand.repeatodo.ui.tasks.TaskListScreen
import com.tremendoushand.repeatodo.ui.today.TodayScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "오늘", Icons.Outlined.Today),
    Tab("dashboard", "대시보드", Icons.Outlined.BarChart),
    Tab("tasks", "할 일", Icons.AutoMirrored.Outlined.ListAlt),
    Tab("settings", "설정", Icons.Outlined.Settings),
)

@Composable
fun RepeatodoNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val openEditor: (String?) -> Unit = { id -> nav.navigate(if (id == null) "edit" else "edit?taskId=$id") }

    AskNotificationPermissionOnce()

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                // Slimmer than the stock 80dp bar; the inset keeps it clear of the system navigation.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("today") { TodayScreen(onAdd = { openEditor(null) }, onOpen = { openEditor(it) }) }
            composable("dashboard") { DashboardScreen() }
            composable("tasks") {
                TaskListScreen(onAdd = { openEditor(null) }, onOpen = { openEditor(it) }, onCategories = { nav.navigate("categories") })
            }
            composable("settings") { SettingsScreen() }
            composable(
                "edit?taskId={taskId}",
                arguments = listOf(navArgument("taskId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStack ->
                TaskEditScreen(taskId = backStack.arguments?.getString("taskId"), onDone = { nav.popBackStack() })
            }
            composable("categories") { CategoryScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun AskNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val container = (context.applicationContext as RepeatodoApp).container
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { container.reminders.rescheduleAll() }
    }
    LaunchedEffect(Unit) {
        if (!container.settings.current().askedNotificationPermission && !Notifications.canNotify(context)) {
            container.settings.setAskedNotificationPermission()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
