package com.samuel.wirelesswhale.qr

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for QR bitmap rendering. Require a device or emulator;
 * the parent runs them during integrated verification.
 */
@RunWith(AndroidJUnit4::class)
class WifiQrBitmapTest {

  private val credentials = WifiCredentials(
    "BitmapNet",
    "password123",
    WifiSecurity.WPA2_PERSONAL,
    false
  )

  @Test
  fun bitmapHasRequestedSize() {
    val bitmap = createWifiQrBitmap(credentials, 512)
    assertEquals(512, bitmap.width)
    assertEquals(512, bitmap.height)
  }

  @Test
  fun defaultSizeIs768() {
    val bitmap = createWifiQrBitmap(credentials)
    assertEquals(768, bitmap.width)
    assertEquals(768, bitmap.height)
  }

  @Test
  fun bitmapContainsBothBlackAndWhite() {
    val bitmap = createWifiQrBitmap(credentials, 256)
    var black = 0
    var white = 0
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (pixel in pixels) {
      if (pixel == Color.BLACK) black++
      if (pixel == Color.WHITE) white++
    }
    assertTrue(black > 0)
    assertTrue(white > 0)
  }

  @Test
  fun sizeIsClampedToBounds() {
    assertEquals(MIN_QR_BITMAP_SIZE, createWifiQrBitmap(credentials, 64).width)
    assertEquals(MAX_QR_BITMAP_SIZE, createWifiQrBitmap(credentials, 5000).width)
  }

  @Test
  fun bitmapRoundTripsThroughDecoder() {
    val bitmap = createWifiQrBitmap(credentials, 512)
    val text = QrImageDecoder.decodeBitmap(bitmap)
      ?: throw AssertionError("Expected the rendered QR to decode on device")
    assertEquals(credentials, WifiQrCodec.decode(text).getOrThrow())
  }

  @Test
  fun hiddenOpenBitmapRoundTrips() {
    val open = WifiCredentials("OpenYard", "", WifiSecurity.OPEN, true)
    val bitmap = createWifiQrBitmap(open, 512)
    val text = QrImageDecoder.decodeBitmap(bitmap)
      ?: throw AssertionError("Expected the rendered QR to decode on device")
    assertEquals(open, WifiQrCodec.decode(text).getOrThrow())
  }

  @Test
  fun unsupportedSecurityThrows() {
    try {
      createWifiQrBitmap(WifiCredentials("Net", "password123", WifiSecurity.WEP, false))
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.isNotEmpty())
    }
  }
}
