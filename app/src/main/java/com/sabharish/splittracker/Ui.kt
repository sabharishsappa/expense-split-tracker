package com.sabharish.splittracker

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
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
/** Bumped whenever permissions may have changed (after the prompt, and on coming back from Settings). */
object Perm {
    var tick by mutableIntStateOf(0)
}

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
    primary = Color(0xFFF2554D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3E0),
    onPrimaryContainer = Color(0xFF5B100C),
    secondary = Color(0xFF5F6368),
    background = Color(0xFFF3F3F5),
    surface = Color.White,
    surfaceTint = Color.Transparent,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE9E9EC), surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White,
    surfaceVariant = Color(0xFFE9E9EC),
    onSurface = Color(0xFF1B1C20),
    onSurfaceVariant = Color(0xFF6A6C73),
    outline = Color(0xFFCFCFD4),
    error = Color(0xFFD93025)
)

// Dark palette follows Money Manager: charcoal pages, slightly lighter panels, coral accent
val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6159),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2724),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF9AA0A6),
    background = Color(0xFF212226),
    surface = Color(0xFF26272D),
    surfaceTint = Color.Transparent,
    surfaceContainer = Color(0xFF26272D), surfaceContainerHigh = Color(0xFF2B2D34), surfaceContainerHighest = Color(0xFF30323A), surfaceContainerLow = Color(0xFF26272D), surfaceContainerLowest = Color(0xFF212226),
    surfaceVariant = Color(0xFF30323A),
    onSurface = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF9A9CA4),
    outline = Color(0xFF3A3C44),
    error = Color(0xFFFF6159)
)

// Theme-aware accent colours. Income and positive balances are blue, expenses coral (as in Money Manager).
@Composable fun muted(): Color = MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun teal(): Color = MaterialTheme.colorScheme.primary
@Composable fun blue(): Color = if (isDarkNow()) Color(0xFF4DA3FF) else Color(0xFF1A73E8)
@Composable fun coral(): Color = MaterialTheme.colorScheme.primary
@Composable fun green(): Color = blue()
@Composable fun amber(): Color = if (isDarkNow()) Color(0xFFFBBF24) else Color(0xFFB45309)

/** One type scale and one set of corners for the whole app, so every page reads the same. */
val AppTypography = androidx.compose.material3.Typography(
    bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, letterSpacing = 0.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, letterSpacing = 0.sp),
    bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, letterSpacing = 0.sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, letterSpacing = 0.sp),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, letterSpacing = 0.sp),
    titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, letterSpacing = 0.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, letterSpacing = 0.sp),
    labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, letterSpacing = 0.sp),
    labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, letterSpacing = 0.sp)
)
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(10.dp)
)

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
            .heightIn(min = if (compact) 36.dp else 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) scheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(androidx.compose.foundation.BorderStroke(1.dp, if (on) scheme.primary else scheme.outline), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) scheme.primary else scheme.onSurface, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
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
        Text(name.trim().take(1).uppercase(), color = c, fontSize = (size * 0.42).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}


/** Equal-width choice boxes, drawn like the Income / Expense / Transfer switch on the entry screen. */
@Composable
fun Segmented(options: List<String>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(shape)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, if (on) scheme.primary else scheme.outline), shape)
                    .clickable { onPick(o) },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(o, fontSize = 14.sp, color = if (on) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}


/** Shown while the app cannot read messages: says why nothing appears and how to fix it. */
@Composable
fun SmsBanner() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ok = remember(Epoch.value, Perm.tick) { Repo.hasPermission(ctx, android.Manifest.permission.READ_SMS) }
    if (ok) return
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
    ) {
        Text("Allow SMS access", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
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

/** The usual "split" symbol: one path forking into two. */
val SplitIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "Split", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.addPathNodes(
            "M14,4l2.29,2.29l-2.88,2.88l1.42,1.42l2.88,-2.88L20,10V4H14z M10,4H4v6l2.29,-2.29l4.71,4.7V20h2v-8.41l-5.29,-5.3L10,4z"
        ),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
    ).build()
}


/** Screen-edge space (status bar, navigation bar, keyboard) measured in the main window. Dialog windows don't
 *  reliably receive insets, so full-screen pages read it from here instead of from their own window. */
val LocalScreenInsets = androidx.compose.runtime.compositionLocalOf<androidx.compose.foundation.layout.PaddingValues> { androidx.compose.foundation.layout.PaddingValues() }

@androidx.compose.runtime.Composable
fun FullScreenDialog(onDismiss: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        content = content
    )
}


/** One button style for the whole app: flat, 6dp corners, 44dp tall. Filled for the main action, outlined for the rest. */
@Composable
fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled,
    shape = RoundedCornerShape(6.dp), elevation = null,
    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White),
    content = content
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = if (compact) 36.dp else 44.dp), enabled = enabled,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 4.dp else 8.dp),
    shape = RoundedCornerShape(6.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    content = content
)


/** One dialog style for the whole app: flat, 8dp corners, 17sp title, 14sp text, same colour as the page panels. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier, dismissButton = dismissButton,
    title = title?.let { t -> { androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 17.sp)) { t() } } },
    text = text?.let { t -> { androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 14.sp)) { t() } } },
    shape = RoundedCornerShape(8.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp
)
