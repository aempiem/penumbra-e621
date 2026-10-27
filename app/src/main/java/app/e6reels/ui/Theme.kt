package app.e6reels.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF3B5C)
val Surface0 = Color(0xFF000000)
val Surface1 = Color(0xFF14141A)

@Composable
fun E6Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            error = Color(0xFFFF6B4A),
            background = Surface0,
            surface = Surface1,
            onSurface = Color.White,
            secondaryContainer = Color(0xFF3A1D26),
            onSecondaryContainer = Color.White,
        ),
        content = content,
    )
}
