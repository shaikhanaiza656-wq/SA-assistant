package com.sa.assistant.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sa.assistant.ui.chat.ChatScreen
import com.sa.assistant.ui.home.HomeScreen
import com.sa.assistant.ui.pdfmark.PdfMarkScreen
import com.sa.assistant.ui.pdfpages.PdfPageManagerScreen
import com.sa.assistant.ui.pdfstudio.PdfStudioScreen
import com.sa.assistant.ui.settings.SettingsScreen
import com.sa.assistant.ui.tools.ToolsScreen

/** Route for the Phase 3 Part 2A page manager (merge/split/rotate/reorder). */
private const val PDF_PAGE_MANAGER_ROUTE = "pdf_pages/{path}"
private fun pdfPageManagerRoute(absolutePath: String) = "pdf_pages/${Uri.encode(absolutePath)}"

/** Route for the Phase 3 Part 2B mark/edit screen (highlight/underline/strikethrough/draw). */
private const val PDF_MARK_ROUTE = "pdf_mark/{path}"
private fun pdfMarkRoute(absolutePath: String) = "pdf_mark/${Uri.encode(absolutePath)}"

/**
 * @param openChatSignal Bumped by [com.sa.assistant.MainActivity] each time the "SA" wake
 * word fires a fresh launch (Phase 6 Part 1) — a counter rather than a boolean so repeated
 * wake-ups while the app is already open each re-trigger the navigation effect below.
 */
@Composable
fun SaNavHost(openChatSignal: Int = 0) {
    val navController = rememberNavController()

    LaunchedEffect(openChatSignal) {
        if (openChatSignal > 0) {
            navController.navigate(SaDestination.Chat.route) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                SaDestination.bottomBarItems.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SaDestination.Home.route,
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable(SaDestination.Home.route) { HomeScreen() }
            composable(SaDestination.Chat.route) { ChatScreen() }
            composable(SaDestination.Pdf.route) {
                PdfStudioScreen(
                    onManagePages = { path -> navController.navigate(pdfPageManagerRoute(path)) },
                    onMarkEdit = { path -> navController.navigate(pdfMarkRoute(path)) }
                )
            }
            composable(
                route = PDF_PAGE_MANAGER_ROUTE,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) {
                PdfPageManagerScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = PDF_MARK_ROUTE,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) {
                PdfMarkScreen(onBack = { navController.popBackStack() })
            }
            composable(SaDestination.Tools.route) {
                ToolsScreen()
            }
            composable(SaDestination.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
