package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0F766E)
val Green = Color(0xFF15803D)
val Amber = Color(0xFFB45309)
val Muted = Color(0xFF64748B)

val AppColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEEA),
    onPrimaryContainer = Color(0xFF053B36),
    secondary = Color(0xFF475569),
    background = Color(0xFFF4F7F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EFED),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF334155),
    error = Color(0xFFB91C1C)
)

/** Rounded tap target used for people, categories and filters. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    strong: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected || strong -> scheme.primary
        else -> scheme.surfaceVariant
    }
    val fg = if (selected || strong) scheme.onPrimary else scheme.onSurface
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, color = fg, fontSize = 14.sp)
    }
}
