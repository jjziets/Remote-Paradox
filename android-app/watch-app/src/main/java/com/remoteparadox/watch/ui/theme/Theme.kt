package com.remoteparadox.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

val AlarmGreen = Color(0xCC2E7D32)
val AlarmRed = Color(0xCCC62828)
val AlarmYellow = Color(0xCCF9A825)
val AlarmPulseRed = Color(0xFFB71C1C)
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1A1A1A)

@Composable
fun RemoteParadoxWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = AlarmGreen,
            onPrimary = Color.White,
            secondary = AlarmYellow,
            onSecondary = Color.Black,
            error = AlarmRed,
            onError = Color.White,
            background = DarkBackground,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        typography = Typography(),
        content = content,
    )
}
