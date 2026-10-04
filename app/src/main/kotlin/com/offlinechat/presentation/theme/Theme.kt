package com.offlinechat.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentEmerald,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = AccentEmeraldGlow,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    background = BgDeepDark,
    onBackground = TextPrimary,
    surface = BgCardDark,
    onSurface = TextPrimary,
    surfaceVariant = BgCardDarkHover,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = StatusError,
    onError = Color.White
)

@Composable
fun OfflineChatTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
