package com.moviebox.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.downloader.MainViewModel
import com.moviebox.downloader.Screen

/** Root: scaffold + 3-tab bottom navigation (Home / Downloads / Debug). */
@Composable
fun MovieBoxApp() {
    val vm: MainViewModel = viewModel()
    val homeUi = vm.homeUi
    val detailUi = vm.detailUi
    val debugUi = vm.debugUi
    val entries = vm.entries.collectAsState()
    val message = vm.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message.value) {
        message.value?.let {
            snackbarHostState.showSnackbar(it)
            vm.onMessageShown()
        }
    }

    BackHandler(enabled = vm.canGoBack) {
        vm.goBack()
    }

    val activeDownloads = entries.value.count { it.status == "queued" || it.status == "running" }

    data class Tab(
        val label: String,
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector,
        val isSelected: () -> Boolean,
        val onClick: () -> Unit,
        val badge: Int = 0,
    )

    val tabs = listOf(
        Tab(
            label = "Home",
            selectedIcon = Icons.Rounded.Home,
            unselectedIcon = Icons.Outlined.Home,
            isSelected = { vm.screen is Screen.Home },
            onClick = { if (vm.screen !is Screen.Home) vm.navigateHome() },
        ),
        Tab(
            label = "Downloads",
            selectedIcon = Icons.Rounded.Download,
            unselectedIcon = Icons.Outlined.Download,
            isSelected = { vm.screen is Screen.Downloads },
            onClick = { if (vm.screen !is Screen.Downloads) vm.navigateDownloads() },
            badge = activeDownloads,
        ),
        Tab(
            label = "Debug",
            selectedIcon = Icons.Rounded.BugReport,
            unselectedIcon = Icons.Outlined.BugReport,
            isSelected = { vm.screen is Screen.Debug },
            onClick = { if (vm.screen !is Screen.Debug) vm.openDebug() },
        ),
    )

    Scaffold(
        containerColor = MaterialThemeNavBackground(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialThemeNavBackground(),
                tonalElevation = 0.dp,
            ) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab.isSelected(),
                        onClick = tab.onClick,
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (tab.badge > 0) {
                                        Badge { Text("${tab.badge}") }
                                    }
                                }
                            ) {
                                Icon(
                                    if (tab.isSelected()) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                )
                            }
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialThemeNavIndicator(),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        when (val s = vm.screen) {
            is Screen.Home ->
                HomeScreen(vm, homeUi, Modifier.padding(innerPadding))

            is Screen.Detail ->
                DetailScreen(vm, detailUi, innerPadding.calculateBottomPadding())

            is Screen.Downloads ->
                HistoryScreen(vm, entries.value, Modifier.padding(innerPadding))

            is Screen.Debug ->
                DebugScreen(vm, debugUi.value, Modifier.padding(innerPadding))
        }
    }
}

/* small local aliases so the nav bar reads cleanly */
@Composable
private fun MaterialThemeNavBackground() =
    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
private fun MaterialThemeNavIndicator() =
    androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer

