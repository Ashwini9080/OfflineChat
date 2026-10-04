package com.offlinechat.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinechat.presentation.chat.ChatScreen
import com.offlinechat.presentation.chat.ChatViewModel
import com.offlinechat.presentation.chats.DiscoveryScreen
import com.offlinechat.presentation.chats.DiscoveryViewModel
import com.offlinechat.presentation.home.HomeScreen
import com.offlinechat.presentation.home.HomeViewModel
import com.offlinechat.presentation.settings.SettingsScreen
import com.offlinechat.presentation.settings.SettingsViewModel

@Composable
fun OfflineChatNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) + fadeIn(tween(250))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) + fadeOut(tween(250))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) + fadeIn(tween(250))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) + fadeOut(tween(250))
        }
    ) {
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            HomeScreen(
                viewModel = viewModel,
                onNavigateToDiscovery = {
                    navController.navigate(Screen.Discovery.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToChat = { conversationId, peerId, peerDisplayName ->
                    navController.navigate(
                        Screen.Chat.createRoute(conversationId, peerId, peerDisplayName)
                    )
                }
            )
        }

        composable(Screen.Discovery.route) {
            val viewModel: DiscoveryViewModel = hiltViewModel()
            DiscoveryScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToChat = { conversationId, peerId, peerDisplayName ->
                    navController.navigate(
                        Screen.Chat.createRoute(conversationId, peerId, peerDisplayName)
                    ) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("peerId") { type = NavType.StringType },
                navArgument("peerDisplayName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val viewModel: ChatViewModel = hiltViewModel(backStackEntry)
            ChatScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
