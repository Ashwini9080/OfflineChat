package com.offlinechat.presentation.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Discovery : Screen("discovery")
    data object Settings : Screen("settings")
    data object Chat : Screen("chat/{conversationId}/{peerId}/{peerDisplayName}") {
        fun createRoute(conversationId: String, peerId: String, peerDisplayName: String): String {
            val encodedName = URLEncoder.encode(peerDisplayName, StandardCharsets.UTF_8.toString())
            return "chat/$conversationId/$peerId/$encodedName"
        }
    }
}
