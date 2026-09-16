package pro.simonroux.myllm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.AppSettings
import pro.simonroux.myllm.ui.chat.ChatScreen
import pro.simonroux.myllm.ui.devloop.DevLoopScreen
import pro.simonroux.myllm.ui.models.ModelsScreen
import pro.simonroux.myllm.ui.settings.SettingsScreen
import pro.simonroux.myllm.ui.skills.SkillsScreen
import pro.simonroux.myllm.ui.theme.AppIcons

/**
 * Five destinations, flat.
 *
 * A drawer or nested graph would be more conventional at this size, but the
 * whole point is reaching any of these one-handed on a phone that is already in
 * use. Chat is first because it is where nine visits out of ten end up.
 */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Chat("chat", "Chat", AppIcons.Chat),
    Models("models", "Modèles", AppIcons.Chip),
    Skills("skills", "Skills", AppIcons.Blocks),
    DevLoop("devloop", "Évolutions", Icons.Default.Refresh),
    Settings("settings", "Réglages", Icons.Default.Settings),
}

@Composable
fun MyLlmApp(container: AppContainer, settings: AppSettings) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Without this every tab switch stacks another
                                // entry and back becomes a tour of the app.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Destination.Chat.route,
            ) {
                composable(Destination.Chat.route) {
                    ChatScreen(container = container, settings = settings)
                }
                composable(Destination.Models.route) {
                    ModelsScreen(container = container)
                }
                composable(Destination.Skills.route) {
                    SkillsScreen(container = container)
                }
                composable(Destination.DevLoop.route) {
                    DevLoopScreen(container = container)
                }
                composable(Destination.Settings.route) {
                    SettingsScreen(container = container, settings = settings)
                }
            }
        }
    }
}
