package com.samuel.wirelesswhale.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale (design/DESIGN.md section 3). All sizes in sp so system font
 * scaling applies. Geist Sans for interface text, Geist Mono only for
 * technical values.
 */
val Typography = Typography(
  // Screen title: 28 sp SemiBold.
  displaySmall = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.5).sp,
    color = WhaleInk,
  ),
  // Summary name: 22 sp SemiBold.
  headlineMedium = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = (-0.25).sp,
    color = WhaleInk,
  ),
  // Section title: 18 sp SemiBold.
  titleLarge = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
    color = WhaleInk,
  ),
  // Body and input: 16 sp Regular.
  bodyLarge = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
    color = WhaleInk,
  ),
  // Body: 15 sp Regular; emphasized body uses Medium via copy.
  bodyMedium = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp,
    color = WhaleInk,
  ),
  // Secondary label: 13 sp Regular.
  bodySmall = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
    color = WhaleSecondary,
  ),
  // Button labels: 15 sp Medium, sentence case.
  labelLarge = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
  ),
  // Nav label: 12 sp Medium.
  labelSmall = TextStyle(
    fontFamily = GeistSans,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.1.sp,
  ),
  // Mono value: 13 sp Mono for technical values only.
  labelMedium = TextStyle(
    fontFamily = GeistMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
    color = WhaleInk,
  ),
)
