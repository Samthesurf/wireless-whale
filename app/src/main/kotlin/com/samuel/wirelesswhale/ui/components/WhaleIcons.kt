package com.samuel.wirelesswhale.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.samuel.wirelesswhale.R
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single stroke family drawn on Canvas so the four capsule tabs share one
 * visual language without extra icon dependencies. 24 dp viewport, 2 dp
 * stroke at scale 1.0, round caps.
 */
enum class WhaleTabGlyph { Home, Wifi, Hotspot, Tools }

@Composable
fun TabGlyph(
  glyph: WhaleTabGlyph,
  tint: Color,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
) {
  Canvas(modifier = modifier.size(size)) {
    val s = size.toPx() / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
      WhaleTabGlyph.Home -> {
        val roof = Path().apply {
          moveTo(4f * s, 11f * s)
          lineTo(12f * s, 4.5f * s)
          lineTo(20f * s, 11f * s)
        }
        drawPath(roof, tint, style = stroke)
        val body = Path().apply {
          moveTo(6.5f * s, 10f * s)
          lineTo(6.5f * s, 19.5f * s)
          lineTo(17.5f * s, 19.5f * s)
          lineTo(17.5f * s, 10f * s)
        }
        drawPath(body, tint, style = stroke)
      }
      WhaleTabGlyph.Wifi -> {
        drawCircle(tint, radius = 1.7f * s, center = Offset(12f * s, 18.6f * s))
        drawArc(tint, 225f, 90f, false, Offset(12f * s - 6f * s, 18.6f * s - 6f * s), androidx.compose.ui.geometry.Size(12f * s, 12f * s), style = stroke)
        drawArc(tint, 225f, 90f, false, Offset(12f * s - 9.5f * s, 18.6f * s - 9.5f * s), androidx.compose.ui.geometry.Size(19f * s, 19f * s), style = stroke)
      }
      WhaleTabGlyph.Hotspot -> {
        drawCircle(tint, radius = 2f * s, center = Offset(12f * s, 12f * s))
        drawArc(tint, 135f, 90f, false, Offset(12f * s - 6.5f * s, 12f * s - 6.5f * s), androidx.compose.ui.geometry.Size(13f * s, 13f * s), style = stroke)
        drawArc(tint, 315f, 90f, false, Offset(12f * s - 6.5f * s, 12f * s - 6.5f * s), androidx.compose.ui.geometry.Size(13f * s, 13f * s), style = stroke)
        drawArc(tint, 135f, 90f, false, Offset(12f * s - 10f * s, 12f * s - 10f * s), androidx.compose.ui.geometry.Size(20f * s, 20f * s), style = stroke)
        drawArc(tint, 315f, 90f, false, Offset(12f * s - 10f * s, 12f * s - 10f * s), androidx.compose.ui.geometry.Size(20f * s, 20f * s), style = stroke)
      }
      WhaleTabGlyph.Tools -> {
        val knobR = 2.6f * s
        val rows = listOf(6.5f to 15f, 12f to 9f, 17.5f to 14f)
        for ((y, kx) in rows) {
          drawLine(tint, Offset(4f * s, y * s), Offset((kx * s) - knobR, y * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
          drawLine(tint, Offset((kx * s) + knobR, y * s), Offset(20f * s, y * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
          drawCircle(tint, radius = knobR, center = Offset(kx * s, y * s), style = stroke)
        }
      }
    }
  }
}

/** Small filled stop square for the discovery stop action. */
@Composable
fun StopGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
  Canvas(modifier = modifier.size(size)) {
    drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
  }
}

/**
 * Password visibility eye icons drawn on Canvas in the same 24 dp viewport
 * with 2 dp round-cap strokes as [TabGlyph], so no extended-icons dependency
 * is needed. [WhaleVisibilityIcon] is the open eye, [WhaleVisibilityOffIcon]
 * adds the diagonal slash.
 */
@Composable
fun WhaleVisibilityIcon(
  tint: Color,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
  contentDescription: String? = null,
) {
  Canvas(
    modifier = modifier.size(size).semantics {
      if (contentDescription != null) this.contentDescription = contentDescription
      this.role = Role.Image
    }
  ) {
    val s = size.toPx() / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val eye = Path().apply {
      moveTo(2f * s, 12f * s)
      cubicTo(7f * s, 5.5f * s, 17f * s, 5.5f * s, 22f * s, 12f * s)
      cubicTo(17f * s, 18.5f * s, 7f * s, 18.5f * s, 2f * s, 12f * s)
      close()
    }
    drawPath(eye, tint, style = stroke)
    drawCircle(tint, radius = 2.6f * s, center = Offset(12f * s, 12f * s))
  }
}

@Composable
fun WhaleVisibilityOffIcon(
  tint: Color,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
  contentDescription: String? = null,
) {
  Canvas(
    modifier = modifier.size(size).semantics {
      if (contentDescription != null) this.contentDescription = contentDescription
      this.role = Role.Image
    }
  ) {
    val s = size.toPx() / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val eye = Path().apply {
      moveTo(2f * s, 12f * s)
      cubicTo(7f * s, 5.5f * s, 17f * s, 5.5f * s, 22f * s, 12f * s)
      cubicTo(17f * s, 18.5f * s, 7f * s, 18.5f * s, 2f * s, 12f * s)
      close()
    }
    drawPath(eye, tint, style = stroke)
    drawCircle(tint, radius = 2.6f * s, center = Offset(12f * s, 12f * s))
    drawLine(tint, Offset(4f * s, 4f * s), Offset(20f * s, 20f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
  }
}

/**
 * Signal strength bars, 0 to 4 filled. Filled bars use [filled], empty
 * slots use [empty]. Meaning never relies on color alone: pair with
 * [com.samuel.wirelesswhale.ui.signalLabel] text nearby.
 */
@Composable
fun SignalBars(
  level: Int,
  modifier: Modifier = Modifier,
  filled: Color = Color(0xFF14171A),
  empty: Color = Color(0xFFE4E7EC),
) {
  Canvas(modifier = modifier.size(20.dp, 14.dp)) {
    val count = level.coerceIn(0, 4)
    val barW = size.width / 4f
    for (i in 0 until 4) {
      val h = size.height * (0.35f + 0.65f * (i + 1) / 4f)
      drawRoundRect(
        color = if (i < count) filled else empty,
        topLeft = Offset(i * barW + 1f, size.height - h),
        size = androidx.compose.ui.geometry.Size(barW - 2f, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f),
      )
    }
  }
}

/** Generated monochrome brand mark, decorative beside the app name. */
@Composable
fun WhaleMark(modifier: Modifier = Modifier, body: Color = Color.Black) {
  Icon(
    painter = painterResource(R.drawable.wireless_whale_mark),
    contentDescription = null,
    tint = body,
    modifier = modifier.size(56.dp, 36.dp),
  )
}
