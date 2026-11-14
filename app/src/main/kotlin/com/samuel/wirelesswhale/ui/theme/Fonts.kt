package com.samuel.wirelesswhale.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.samuel.wirelesswhale.R

/**
 * Bundled Geist families (design/DESIGN.md section 9, release v1.9.2).
 * Static OTF files under res/font. No network font download.
 */
val GeistSans = FontFamily(
  Font(R.font.geist_regular, FontWeight.Normal),
  Font(R.font.geist_medium, FontWeight.Medium),
  Font(R.font.geist_semibold, FontWeight.SemiBold),
)

val GeistMono = FontFamily(
  Font(R.font.geist_mono_regular, FontWeight.Normal),
  Font(R.font.geist_mono_medium, FontWeight.Medium),
)
