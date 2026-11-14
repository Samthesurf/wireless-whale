package com.samuel.wirelesswhale.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.ui.theme.WhaleAccent
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleBorder
import com.samuel.wirelesswhale.ui.theme.WhaleDestructive
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WhaleWarning

/**
 * Button variants (COMPONENTS.md section 4). Primary is teal fill, secondary
 * is white with border, destructive names its target. 52 dp min height,
 * 16 dp radius. Disabled keeps shape plus opacity; loading preserves layout.
 */
@Composable
fun WhalePrimaryButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
  fullWidth: Boolean = true,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier = modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
      .defaultMinSize(minHeight = 52.dp),
    shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = WhaleAccent,
      contentColor = Color.White,
      disabledContainerColor = WhaleAccent.copy(alpha = 0.45f),
      disabledContentColor = Color.White.copy(alpha = 0.9f),
    ),
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
  ) {
    if (loading) {
      Text("Working", style = MaterialTheme.typography.labelLarge, color = Color.White)
    } else {
      Text(label, style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
fun WhaleSecondaryButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  fullWidth: Boolean = true,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
      .defaultMinSize(minHeight = 52.dp),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, WhaleBorder),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = WhaleBackground,
      contentColor = WhaleInk,
      disabledContainerColor = WhaleBackground,
      disabledContentColor = WhaleInk.copy(alpha = 0.45f),
    ),
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun WhaleDestructiveButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, WhaleDestructive.copy(alpha = 0.5f)),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = WhaleBackground,
      contentColor = WhaleDestructive,
    ),
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}

enum class ChipTone { Neutral, Live, Warning, Error }

/**
 * Status chip (COMPONENTS.md section 9). Capsule, min 32 dp height, icon
 * plus words so meaning survives without color.
 */
@Composable
fun StatusChip(tone: ChipTone, text: String, modifier: Modifier = Modifier) {
  val icon = when (tone) {
    ChipTone.Neutral -> Icons.Filled.Info
    ChipTone.Live -> Icons.Filled.Check
    ChipTone.Warning -> Icons.Filled.Warning
    ChipTone.Error -> Icons.Filled.Warning
  }
  val tint = when (tone) {
    ChipTone.Neutral -> WhaleSecondary
    ChipTone.Live -> WhaleAccent
    ChipTone.Warning -> WhaleWarning
    ChipTone.Error -> WhaleDestructive
  }
  Surface(
    modifier = modifier,
    shape = CircleShape,
    border = BorderStroke(1.dp, WhaleBorder),
    color = WhaleBackground,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        .defaultMinSize(minHeight = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
      Spacer(Modifier.size(6.dp))
      Text(text, style = MaterialTheme.typography.bodySmall, color = WhaleInk)
    }
  }
}

/**
 * Text field (COMPONENTS.md section 10). 56 dp min height, 16 dp radius,
 * 1 dp border, helper text space reserved so layout does not jump.
 */
@Composable
fun WhaleTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  helper: String? = null,
  error: String? = null,
  enabled: Boolean = true,
  keyboardType: KeyboardType = KeyboardType.Text,
  singleLine: Boolean = true,
  maxLength: Int? = null,
) {
  OutlinedTextField(
    value = value,
    onValueChange = { next -> onValueChange(if (maxLength == null) next else next.take(maxLength)) },
    label = { Text(label) },
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
    enabled = enabled,
    singleLine = singleLine,
    isError = error != null,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    supportingText = {
      Text(
        text = error ?: helper.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) WhaleDestructive else WhaleSecondary,
      )
    },
    shape = RoundedCornerShape(16.dp),
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = WhaleAccent,
      unfocusedBorderColor = WhaleBorder,
      errorBorderColor = WhaleDestructive,
      focusedLabelColor = WhaleAccent,
      unfocusedLabelColor = WhaleSecondary,
    ),
  )
}

/** Secret field with an explicit visibility toggle. Never previews otherwise. */
@Composable
fun WhaleSecretField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  helper: String? = null,
  error: String? = null,
  enabled: Boolean = true,
) {
  var visible by remember { mutableStateOf(false) }
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
    enabled = enabled,
    singleLine = true,
    isError = error != null,
    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    trailingIcon = {
      IconButton(onClick = { visible = !visible }, modifier = Modifier.size(48.dp)) {
        if (visible) {
          WhaleVisibilityOffIcon(
            tint = WhaleSecondary,
            contentDescription = "Hide password",
          )
        } else {
          WhaleVisibilityIcon(
            tint = WhaleSecondary,
            contentDescription = "Show password",
          )
        }
      }
    },
    supportingText = {
      Text(
        text = error ?: helper.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) WhaleDestructive else WhaleSecondary,
      )
    },
    shape = RoundedCornerShape(16.dp),
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = WhaleAccent,
      unfocusedBorderColor = WhaleBorder,
      errorBorderColor = WhaleDestructive,
      focusedLabelColor = WhaleAccent,
      unfocusedLabelColor = WhaleSecondary,
    ),
  )
}
