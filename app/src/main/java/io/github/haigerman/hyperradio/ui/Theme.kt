package io.github.haigerman.hyperradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Night = Color(0xFF0F1A24)
val DeepTeal = Color(0xFF123238)
val Card = Color(0xFF18282F)
val CardOn = Color(0xFF1E3A3E)
val Sand = Color(0xFFE8C77A)
val Mist = Color(0xFF8CC7C4)
val TextSoft = Color(0xFFB8C7C9)
val Coral = Color(0xFFFF8A65)

private val colors = darkColorScheme(
    primary = Sand,
    onPrimary = Color(0xFF2A1E05),
    secondary = Mist,
    onSecondary = Color(0xFF062224),
    background = Night,
    surface = Card,
    onSurface = Color(0xFFEAF1F1),
    onSurfaceVariant = TextSoft,
    surfaceVariant = CardOn,
    secondaryContainer = Color(0xFF2B4F52),
    onSecondaryContainer = Color(0xFFEAF1F1),
)

@Composable
fun HyperRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
