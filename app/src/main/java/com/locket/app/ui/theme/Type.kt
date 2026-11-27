package com.locket.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.locket.app.R

// Nunito is bundled locally (SIL OFL 1.1); minSdk 26 supports variable fonts.
@OptIn(ExperimentalTextApi::class)
internal val LocketFontFamily = FontFamily(
    (200..900 step 100).map { weight ->
        Font(
            resId = R.font.nunito_variable,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight))
        )
    }
)

private val DefaultTypography = Typography()

internal val LocketTypography = DefaultTypography.run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        displaySmall = displaySmall.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Medium),
        bodySmall = bodySmall.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = LocketFontFamily, fontWeight = FontWeight.SemiBold)
    )
}
