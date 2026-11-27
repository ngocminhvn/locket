package com.locket.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locket.app.ui.theme.LocketFontFamily
import com.locket.app.ui.theme.LocketTextDim

// Explicit metrics: changing only fontSize inherits Material3's body line-height
// and letter spacing, which leaves too much vertical space for profile subtitles.
internal fun profileTextStyle(size: TextUnit, line: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = LocketFontFamily,
    fontSize = size,
    lineHeight = line,
    fontWeight = weight,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

@Composable
internal fun ProfileSettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color.White,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val hasSubtitle = !subtitle.isNullOrBlank()
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = if (hasSubtitle) 68.dp else 56.dp)
            .padding(horizontal = 17.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = titleColor, style = profileTextStyle(17.sp, 21.sp, FontWeight.SemiBold))
            if (hasSubtitle) {
                Text(subtitle.orEmpty(), color = LocketTextDim,
                    style = profileTextStyle(14.sp, 18.sp, FontWeight.SemiBold))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
