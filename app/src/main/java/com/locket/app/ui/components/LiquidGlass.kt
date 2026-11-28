package com.locket.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.locket.app.ui.theme.LocketGlassHighlight
import com.locket.app.ui.theme.LocketGlassShadow
import com.locket.app.ui.theme.LocketPillBg

fun Modifier.liquidGlassEffect(
    shape: Shape,
    backgroundColor: Color = LocketPillBg,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                backgroundColor,
                backgroundColor.copy(alpha = if (backgroundColor == LocketPillBg) backgroundColor.alpha else backgroundColor.alpha * 0.90f)
            )
        ),
        shape = shape
    )
    .border(
        width = borderWidth,
        brush = Brush.verticalGradient(
            colors = listOf(
                LocketGlassHighlight,
                LocketGlassShadow
            )
        ),
        shape = shape
    )
