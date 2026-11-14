package com.samuel.wirelesswhale.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WhaleSelectedSegment

/**
 * Top bar, root and detail variants (COMPONENTS.md section 3). White, 64 dp
 * min height, 20 dp horizontal padding at 360 dp, actions are 48 dp targets.
 */
@Composable
fun AppTopBar(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  showSettings: Boolean = false,
  onSettings: (() -> Unit)? = null,
  onBack: (() -> Unit)? = null,
  horizontalPadding: Dp = 20.dp,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(WhaleBackground)
      .padding(horizontal = horizontalPadding, vertical = 8.dp)
      .height(64.dp)
      .testTag("whaleTopBar"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("whaleTopBarBack")) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = WhaleInk)
      }
    }
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text(
        text = title,
        style = if (onBack == null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = WhaleSecondary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (showSettings && onSettings != null) {
      IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).testTag("whaleTopBarSettings")) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = WhaleInk)
      }
    }
  }
}

enum class WhaleDestination(val label: String, val glyph: WhaleTabGlyph) {
  Home("Home", WhaleTabGlyph.Home),
  Wifi("Wi-Fi", WhaleTabGlyph.Wifi),
  Hotspot("Hotspot", WhaleTabGlyph.Hotspot),
  Tools("Tools", WhaleTabGlyph.Tools),
}

/**
 * Floating graphite capsule navigation (DESIGN.md section 5, COMPONENTS.md
 * section 2). True pill, 72 dp high at scale 1.0, graphite shell with a
 * light selected segment wrapping icon plus label. Four destinations only.
 */
@Composable
fun CapsuleNav(
  current: WhaleDestination,
  onSelect: (WhaleDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 640.dp)
      .navigationBarsPadding()
      .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
      .testTag("whaleCapsuleNav"),
  ) {
    val narrow = maxWidth < 340.dp
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(8.dp, CircleShape)
        .clip(CircleShape)
        .background(WhaleInk)
        .padding(horizontal = 4.dp, vertical = 6.dp)
        .heightIn(min = 60.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (destination in WhaleDestination.entries) {
        val selected = destination == current
        val fullLabel = destination.label
        Box(
          modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(WhaleSelectedSegment) else Modifier)
            .semantics { contentDescription = fullLabel }
            .testTag("whaleTab" + destination.name)
            .selectable(
              selected = selected,
              onClick = { onSelect(destination) },
              role = Role.Tab,
            )
            .padding(vertical = 8.dp, horizontal = 2.dp),
          contentAlignment = Alignment.Center,
        ) {
          val contentColor = if (selected) WhaleInk else Color.White.copy(alpha = 0.78f)
          if (narrow) {
            // Narrow widths reflow the label under the icon so the full
            // name is kept and the TalkBack label stays complete.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              TabGlyph(destination.glyph, tint = contentColor, size = 22.dp)
              Text(
                text = fullLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
              TabGlyph(destination.glyph, tint = contentColor)
              Spacer(Modifier.size(6.dp))
              Text(
                text = fullLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}
