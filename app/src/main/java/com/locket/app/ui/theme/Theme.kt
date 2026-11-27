package com.locket.app.ui.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LocketTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        // Remove only the legacy app-private emoji assets, never system fonts.
        withContext(Dispatchers.IO) {
            val folder = File(context.filesDir, "emoji")
            listOf("ios26_4.ttf", "ios26_4.ttf.tmp", "unicode17.txt", "unicode17.txt.tmp").forEach { File(folder, it).delete() }
            if (folder.list()?.isEmpty() == true) folder.delete()
        }
    }
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        MaterialExpressiveTheme(
            colorScheme = DarkColorScheme,
            typography = LocketTypography,
            content = content
        )
    }
}
