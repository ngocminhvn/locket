package com.locket.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// Locally drawn 24-unit silhouettes, with rounded shapes inspired by the reference.
internal enum class ReferenceGlyph(val path: String) {
    Horn("M3 10 L8 10 L17 4 Q19 3 19 6 L19 17 Q19 20 17 19 L8 15 L7 20 Q6.8 21 5.8 21 L4 20 Q3 19.7 3.4 18.7 L5 15 L3 15 Q1 15 1 13 L1 12 Q1 10 3 10 Z M15.5 6.4 L15.5 16.6 L17 17.3 L17 5.5 Z M20 3 L22 1.7 L23 3.2 L21 5 Z M21 8 L24 7.3 L24 9.4 L21 10 Z M21 13 L24 14 L23.3 16 L20.5 15 Z"),
    People("M9 3.2 A4 4 0 1 1 9 11.2 A4 4 0 1 1 9 3.2 Z M15 3.4 C21 1.8 24 9.5 17.2 11.1 C16.5 11.2 15.9 11.1 15.5 11 C17.7 8.9 17.8 5.5 15 3.4 Z M1 20 L1 17.5 C1 11.1 17 11.1 17 17.5 L17 20 Q17 21 16 21 L2 21 Q1 21 1 20 Z M17 13 C21 12.8 24 14.5 24 17.8 L24 20 Q24 21 23 21 L19 21 L19 17.5 Q19 14.8 17 13 Z"),
    Calendar("M6 2 H18 Q22 2 22 6 V18 Q22 22 18 22 H6 Q2 22 2 18 V6 Q2 2 6 2 Z M9 5 Q8.2 5 8.2 6 Q8.2 7 9 7 H15 Q15.8 7 15.8 6 Q15.8 5 15 5 Z M6 9 H8 Q9 9 9 10 V11 Q9 12 8 12 H6 Q5 12 5 11 V10 Q5 9 6 9 Z M11 9 H13 Q14 9 14 10 V11 Q14 12 13 12 H11 Q10 12 10 11 V10 Q10 9 11 9 Z M16 9 H18 Q19 9 19 10 V11 Q19 12 18 12 H16 Q15 12 15 11 V10 Q15 9 16 9 Z M6 14 H8 Q9 14 9 15 V16 Q9 17 8 17 H6 Q5 17 5 16 V15 Q5 14 6 14 Z M11 14 H13 Q14 14 14 15 V16 Q14 17 13 17 H11 Q10 17 10 16 V15 Q10 14 11 14 Z M16 14 H18 Q19 14 19 15 V16 Q19 17 18 17 H16 Q15 17 15 16 V15 Q15 14 16 14 Z"),
    Home("M2 10 Q2 8 4 6.6 L9.6 2.2 Q12 0.3 14.4 2.2 L20 6.6 Q22 8 22 10 V19 Q22 22 19 22 H15 V15 Q15 13 12 13 Q9 13 9 15 V22 H5 Q2 22 2 19 Z"),
    Chat("M12 2 C18.1 2 22 6.4 22 12 S17.8 22 12 22 Q9.8 22 7.8 21 L3.2 22.7 Q1 23.4 1.8 21.1 L3.2 17 C2.3 15.5 2 13.9 2 12 C2 6.4 6 2 12 2 Z"),
    Gallery("M4 4 L13 1.8 Q17 0.8 18.2 3.4 L16 3.7 Q15.4 3 13.6 3.4 L4.7 5.6 Q2.8 6.1 3.2 8 L5.2 17.7 Q5.4 18.8 6.2 19.1 L6.2 21 Q3.9 20.7 3.4 18.3 L1.2 8.5 Q0.4 5 4 4 Z M10 5 H19 Q23 5 23 9 V19 Q23 23 19 23 H10 Q6 23 6 19 V9 Q6 5 10 5 Z M10 7 Q8 7 8 9 V17 L12 13 Q12.8 12.2 13.6 13 L15 14.5 L17.5 12 Q18.3 11.2 19.1 12 L21 14 V9 Q21 7 19 7 Z M12 8.5 A1.8 1.8 0 1 1 12 12.1 A1.8 1.8 0 1 1 12 8.5 Z"),
    Flip("M6 4.5 C11 0 19 3 20.1 9.9 L22 8.4 Q22.7 7.8 23.3 8.5 Q24 9.2 23.3 10 L20 13.4 Q19.3 14 18.6 13.4 L15.1 10 Q14.4 9.3 15.1 8.6 Q15.8 7.9 16.5 8.5 L18.2 10 C17.3 4.5 11.2 2.8 7.5 6 Q6.6 6.8 6 6 Q5.4 5.3 6 4.5 Z M18 19.5 C13 24 5 21 3.9 14.1 L2 15.6 Q1.3 16.2 0.7 15.5 Q0 14.8 0.7 14 L4 10.6 Q4.7 10 5.4 10.6 L8.9 14 Q9.6 14.7 8.9 15.4 Q8.2 16.1 7.5 15.5 L5.8 14 C6.7 19.5 12.8 21.2 16.5 18 Q17.4 17.2 18 18 Q18.6 18.7 18 19.5 Z"),
    Flash("M12.8 1.5 Q13.8 0.2 14.3 1.8 L14 9 H19 Q20.6 9 19.6 10.5 L10.5 22.1 Q9.3 23.4 9.2 21.7 L10 14 H5 Q3.4 14 4.5 12.5 Z")
}

@Composable
internal fun ReferenceIcon(glyph: ReferenceGlyph, description: String?, tint: Color, modifier: Modifier = Modifier) {
    val path = remember(glyph) {
        PathParser().parsePathString(glyph.path).toPath().apply { fillType = PathFillType.EvenOdd }
    }
    Canvas(modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val factor = minOf(size.width, size.height) / 24f
        val center = Offset((size.width - 24f * factor) / 2f, (size.height - 24f * factor) / 2f)
        withTransform({
            translate(center.x, center.y)
            scale(factor, factor, pivot = Offset.Zero)
        }) {
            drawPath(path, tint)
        }
    }
}
