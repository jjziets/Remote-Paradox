package com.remoteparadox.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkNavy = Color(0xFF1a1a2e)
private val DeepBlue = Color(0xFF16213e)
private val AccentBlue = Color(0xFF0f3460)
private val BrightCyan = Color(0xFF00d2ff)
private val AlertRed = Color(0xFFe94560)
private val SafeGreen = Color(0xFF4caf50)
private val WarnAmber = Color(0xFFff9800)

val AlarmArmed = AlertRed
val AlarmDisarmed = SafeGreen
val AlarmStay = WarnAmber
val ZoneOpen = WarnAmber
val ZoneClosed = SafeGreen

private val DarkColorScheme = darkColorScheme(
    primary = BrightCyan,
    onPrimary = DarkNavy,
    secondary = AccentBlue,
    background = DarkNavy,
    surface = DeepBlue,
    onBackground = Color.White,
    onSurface = Color.White,
    error = AlertRed,
)

@Composable
fun RemoteParadoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
