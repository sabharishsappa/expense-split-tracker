package com.sabharish.splittracker

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val on = selected || strong
    Box(
        modifier
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) scheme.onPrimary else scheme.onSurface, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
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


/** A joined, equal-width choice control for a *filter* inside a page (the page tabs above stay underlined). */
@Composable
fun Segmented(options: List<String>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(scheme.surfaceVariant).padding(3.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(if (on) scheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onPick(o) },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(o, fontSize = 13.sp, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
            }
        }
    }
}


/** Shown while the app cannot read messages: says why nothing appears and how to fix it. */
@Composable
fun SmsBanner() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ok = remember(Epoch.value) { Repo.hasPermission(ctx, android.Manifest.permission.READ_SMS) }
    if (ok) return
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
    ) {
        Text("Allow SMS access", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text("Bank messages are read on this phone to find your payments. Nothing is uploaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        androidx.compose.material3.TextButton(onClick = {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
        }) { Text("Open settings") }
    }
}


/** The one search field used everywhere: it takes the cursor as soon as it appears, so you can type straight away. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = modifier.focusRequester(focus),
        placeholder = { Text(placeholder) },
        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) }
    )
}
