package com.locket.app.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.locket.app.ui.theme.LocketBlack
import com.wexpa.liquidglass.GlassTabBarStyle

/** TKB SV's upstream Liquid Glass pipeline, with Locket's background unchanged. */
@Composable
internal fun LocketNavigationGlassTheme(
    background: Color = LocketBlack,
    content: @Composable () -> Unit
) {
    // Match TKB's full-resolution recording and default optical settings.
    com.wexpa.liquidglass.LiquidGlassTheme(
        dark = true, background = background, content = content
    )
}

/** Same unmodified Calm optics, rim, selector and gestures as TKB SV.
 * Only the three geometry values retain Locket's existing navigation layout.
 * Item content and color remain owned by the caller.
 */
@Composable
internal fun LocketGlassTabBar(
    itemCount: Int,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int, isSelected: Boolean) -> Unit
) {
    CompositionLocalProvider(LocalHapticFeedback provides SilentHapticFeedback) {
        com.wexpa.liquidglass.GlassTabBar(
            itemCount = itemCount,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = modifier,
            style = GlassTabBarStyle.Calm(true).copy(
                height = 54.dp, contentPadding = 4.dp, pillInset = 4.dp
            ),
            item = item
        )
    }
}

private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
