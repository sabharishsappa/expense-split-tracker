package com.sabharish.splittracker

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bumped whenever the app goes to the background; screens use it to forget half-finished selections. */
object Epoch {
    var value by mutableIntStateOf(0)
}

/** "system" | "light" | "dark" */
object ThemeState {
    var mode by mutableStateOf("system")
}

@Composable
fun isDarkNow(): Boolean = when (ThemeState.mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEEA),
    onPrimaryContainer = Color(0xFF053B36),
    secondary = Color(0xFF475569),
    background = Color(0xFFF4F7F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EFED),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFB91C1C)
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF0B1210),
    surface = Color(0xFF141C1A),
    surfaceVariant = Color(0xFF22302D),
    onSurface = Color(0xFFE6EEEC),
    onSurfaceVariant = Color(0xFFA7B6B2),
    error = Color(0xFFF87171)
)

// Theme-aware accent colours
@Composable fun muted(): Color = MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun teal(): Color = MaterialTheme.colorScheme.primary
@Composable fun green(): Color = if (isDarkNow()) Color(0xFF4ADE80) else Color(0xFF15803D)
@Composable fun amber(): Color = if (isDarkNow()) Color(0xFFFBBF24) else Color(0xFFB45309)

/** Large rounded tap target used for people, categories and filters (min height 44dp). */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    strong: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val on = selected || strong
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) scheme.onPrimary else scheme.onSurface, fontSize = 15.sp)
    }
}

/** Round initial avatar with a stable colour per name. */
@Composable
fun Avatar(name: String, size: Int = 40) {
    val palette = listOf(0xFF0F766E, 0xFF7C3AED, 0xFFB45309, 0xFF0369A1, 0xFFBE185D, 0xFF4D7C0F)
    val c = Color(palette[Math.abs(name.hashCode()) % palette.size])
    androidx.compose.foundation.layout.Box(
        Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().take(1).uppercase(), color = c, fontSize = (size * 0.42).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
