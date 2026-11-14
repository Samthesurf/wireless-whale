package com.samuel.wirelesswhale.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.ui.theme.WhaleAccent
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleBorder
import com.samuel.wirelesswhale.ui.theme.WhaleDestructive
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WhaleSubtle
import com.samuel.wirelesswhale.ui.theme.WhaleWarning

/** Section heading inside a screen. Sentence case, one idea per section. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleLarge,
    modifier = modifier.padding(top = 24.dp, bottom = 12.dp),
  )
}

/** White summary card, 24 dp radius, 1 dp border, 20 dp padding. */
@Composable
fun SummaryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    border = BorderStroke(1.dp, WhaleBorder),
    color = WhaleBackground,
    shadowElevation = 1.dp,
  ) {
    Column(modifier = Modifier.padding(20.dp), content = content)
  }
}

/** Small section label above a summary value, in secondary text. */
@Composable
fun CardKicker(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary)
}

/** Quiet timestamp line under a value, with the reason when stale. */
@Composable
fun FreshnessLine(stamp: String?, modifier: Modifier = Modifier) {
  if (stamp != null) {
    Text(
      text = stamp,
      style = MaterialTheme.typography.bodySmall,
      color = WhaleSecondary,
      modifier = modifier.padding(top = 4.dp),
    )
  }
}

/**
 * Decides when a [MetricRow] must stack its value under its label instead of
 * the compact side-by-side arrangement.
 *
 * A side-by-side row only works while both columns fit: the label keeps its
 * guaranteed share of the width only when the value is also constrained, and
 * an unconstrained long URL or sentence still steals the whole row and
 * squeezes the label to one character per line. Stack when the value is a
 * long description or URL, when it holds an unbreakable token (URL, MAC,
 * long identifier) that cannot wrap inside half a narrow row, or when the
 * combined text at the current font scale no longer fits two columns.
 */
internal fun shouldStackMetricRow(label: String, value: String, fontScale: Float): Boolean {
  if (value.length >= 30) return true
  val longestToken = value.split(' ', '\n', '\t').maxOfOrNull { it.length } ?: 0
  if (longestToken * fontScale >= 22f) return true
  if ((label.length + value.length) * fontScale >= 48f) return true
  return false
}

/** Plain label left, value right, with known/unavailable/stale recipes. */
@Composable
fun MetricRow(
  label: String,
  value: String?,
  modifier: Modifier = Modifier,
  mono: Boolean = false,
  staleStamp: String? = null,
  unavailableCause: String? = null,
) {
  val displayValue = when {
    unavailableCause != null -> "Unavailable"
    value != null -> value
    else -> "Unknown"
  }
  val displayColor = if (unavailableCause != null || value == null) WhaleSecondary else WhaleInk
  val displayStyle =
    if (mono && unavailableCause == null && value != null) {
      MaterialTheme.typography.labelMedium
    } else {
      MaterialTheme.typography.bodyMedium
    }
  val fontScale = LocalDensity.current.fontScale
  val stacked = remember(label, displayValue, fontScale) {
    shouldStackMetricRow(label, displayValue, fontScale)
  }
  Column(modifier = modifier.fillMaxWidth()) {
    if (stacked) {
      Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          color = WhaleInk,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = displayValue,
          style = displayStyle,
          color = displayColor,
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          color = WhaleInk,
          modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(
          text = displayValue,
          style = displayStyle,
          color = displayColor,
          textAlign = TextAlign.End,
          modifier = Modifier.weight(1f),
        )
      }
    }
    if (unavailableCause != null) {
      Text(
        text = unavailableCause,
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    if (staleStamp != null && value != null) {
      Text(
        text = staleStamp,
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
  }
}

enum class NoticeTone { Setup, Unsupported, Lost }

/**
 * Capability notice (COMPONENTS.md section 11). Leading icon, headline,
 * one sentence of cause, one recovery action. Lives next to the
 * unavailable feature, never in a hidden FAQ.
 */
@Composable
fun CapabilityNotice(
  tone: NoticeTone,
  headline: String,
  cause: String,
  actionLabel: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tint = when (tone) {
    NoticeTone.Setup -> WhaleAccent
    NoticeTone.Unsupported -> WhaleWarning
    NoticeTone.Lost -> WhaleDestructive
  }
  val icon = when (tone) {
    NoticeTone.Setup -> Icons.Filled.Check
    NoticeTone.Unsupported -> Icons.Filled.Warning
    NoticeTone.Lost -> Icons.Filled.Info
  }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, WhaleBorder),
    color = WhaleSubtle,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
      Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
        Text(headline, style = MaterialTheme.typography.bodyMedium, color = WhaleInk)
        Text(
          cause,
          style = MaterialTheme.typography.bodySmall,
          color = WhaleSecondary,
          modifier = Modifier.padding(top = 4.dp),
        )
        Text(
          text = actionLabel,
          style = MaterialTheme.typography.labelLarge,
          color = WhaleAccent,
          modifier = Modifier
            .padding(top = 8.dp)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onAction)
            .padding(vertical = 8.dp),
        )
      }
    }
  }
}

/**
 * Empty state (COMPONENTS.md section 13). Title, one sentence explaining
 * why it is empty, one action that fills it.
 */
@Composable
fun WhaleEmptyState(
  title: String,
  reason: String,
  actionLabel: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(WhaleSubtle, RoundedCornerShape(20.dp))
      .padding(20.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(
      reason,
      style = MaterialTheme.typography.bodyMedium,
      color = WhaleSecondary,
      modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(12.dp))
    WhaleSecondaryButton(label = actionLabel, onClick = onAction)
  }
}

/** Tappable row with label, detail, trailing value, and chevron. */
@Composable
fun SettingRow(
  title: String,
  detail: String?,
  onClick: (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val clickable = if (onClick != null) {
    Modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
  } else Modifier
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(clickable)
        .sizeIn(minHeight = 48.dp)
        .padding(vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = WhaleInk)
        if (detail != null) {
          Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      if (trailing != null) trailing()
      else if (onClick != null) {
        Icon(
          Icons.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = WhaleSecondary,
          modifier = Modifier.size(24.dp),
        )
      }
    }
    HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
  }
}

/**
 * Wi-Fi scan row. Connected rows are separated above nearby results by the
 * caller. Security problems name the limitation instead of dead-ending.
 */
@Composable
fun NetworkRow(
  ssid: String,
  signalText: String,
  securityText: String,
  bandText: String,
  level: Int,
  subtitle: String?,
  connected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .semantics { contentDescription = "$ssid, $signalText, $securityText" }
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SignalBars(level = level)
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
      Text(
        text = ssid,
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleInk,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = listOfNotNull(signalText, securityText, bandText).joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        maxLines = 2,
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
      if (connected) {
        Text(
          "Connected",
          style = MaterialTheme.typography.labelSmall,
          color = WhaleAccent,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Details", tint = WhaleSecondary)
  }
  HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
}
