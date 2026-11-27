package com.locket.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LocketYellow,
    onPrimary = Color.Black,
    secondary = LocketYellow,
    onSecondary = Color.Black,
    background = LocketBlack,
    onBackground = LocketTextWhite,
    surface = LocketDarkGray,
    onSurface = LocketTextWhite,
    surfaceVariant = LocketCardGray,
    onSurfaceVariant = LocketTextDim
)

@Composable
fun LocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
