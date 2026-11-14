package com.samuel.wirelesswhale.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WhaleColorScheme = lightColorScheme(
  primary = WhaleAccent,
  onPrimary = Color.White,
  primaryContainer = WhaleAccent,
  onPrimaryContainer = Color.White,
  secondary = WhaleSecondary,
  onSecondary = Color.White,
  secondaryContainer = WhaleSubtle,
  onSecondaryContainer = WhaleInk,
  tertiary = WhaleInk,
  onTertiary = Color.White,
  background = WhaleBackground,
  onBackground = WhaleInk,
  surface = WhaleBackground,
  onSurface = WhaleInk,
  surfaceVariant = WhaleSubtle,
  onSurfaceVariant = WhaleSecondary,
  surfaceContainerLowest = WhaleBackground,
  surfaceContainerLow = WhaleSubtle,
  surfaceContainer = WhaleSubtle,
  surfaceContainerHigh = WhaleSubtle,
  error = WhaleDestructive,
  onError = Color.White,
  outline = WhaleBorder,
  outlineVariant = WhaleBorder,
  scrim = WhaleScrim,
)

/**
 * Wireless Whale theme. App content stays on the light instrument theme even
 * when the phone system theme is dark (design/DESIGN.md section 1). The
 * signature is kept so existing callers such as MainActivity compile
 * unchanged; the scheme is intentionally always light.
 */
@Composable
fun WirelessWhaleTheme(
  darkTheme: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = WhaleColorScheme,
    typography = Typography,
    content = content,
  )
}
