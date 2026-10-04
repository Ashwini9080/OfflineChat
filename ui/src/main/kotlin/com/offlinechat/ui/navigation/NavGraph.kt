package com.offlinechat.ui.navigation

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
import com.offlinechat.ui.screen.chat.ChatScreen
import com.offlinechat.ui.screen.chat.ChatViewModel
import com.offlinechat.ui.screen.devices.DeviceDiscoveryScreen
import com.offlinechat.ui.screen.devices.DeviceDiscoveryViewModel
import com.offlinechat.ui.screen.home.HomeScreen
import com.offlinechat.ui.screen.home.HomeViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Discovery : Screen("discovery")
    data object Chat : Screen("chat/{conversationId}/{peerId}/{peerDisplayName}") {
        fun createRoute(conversationId: String, peerId: String, peerDisplayName: String): String {
            val encodedName = URLEncoder.encode(peerDisplayName, StandardCharsets.UTF_8.toString())
            return "chat/$conversationId/$peerId/$encodedName"
        }
    }
}

@Composable
fun OfflineChatNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeIn(tween(300))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeOut(tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeIn(tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(300))
        }
    ) {
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            HomeScreen(
                viewModel = viewModel,
                onNavigateToDiscovery = {
                    navController.navigate(Screen.Discovery.route)
                },
                onNavigateToChat = { conversationId, peerId, peerDisplayName ->
                    navController.navigate(
                        Screen.Chat.createRoute(conversationId, peerId, peerDisplayName)
                    )
                }
            )
        }

        composable(Screen.Discovery.route) {
            val viewModel: DeviceDiscoveryViewModel = hiltViewModel()
            DeviceDiscoveryScreen(
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
