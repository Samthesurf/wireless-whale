package com.samuel.wirelesswhale.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure pixel and Y-plane decoding core. Runs with ZXing
 * core on the unit-test classpath, no Android framework needed.
 */
class QrImageDecoderTest {

  @Test
  fun pixelRoundTripDecodesWifiPayload() {
    val credentials = WifiCredentials("PixelNet", "password123", WifiSecurity.WPA2_PERSONAL, true)
    val payload = WifiQrCodec.encode(credentials)
    val (pixels, width, height) = renderArgb(payload, scale = 4)
    assertEquals(payload, QrImageDecoder.decodePixels(pixels, width, height))
    assertEquals(credentials, WifiQrCodec.decode(payload).getOrThrow())
  }

  @Test
  fun credentialsRoundTripThroughPixels() {
    val credentials = WifiCredentials("CafeOpen", "", WifiSecurity.OPEN, false)
    val payload = WifiQrCodec.encode(credentials)
    val (pixels, width, height) = renderArgb(payload, scale = 3)
    val decoded = QrImageDecoder.decodeCredentials(pixels, width, height).getOrThrow()
    assertEquals(credentials, decoded)
  }

  @Test
  fun yuvRoundTrip() {
    val payload = WifiQrCodec.encode(
      WifiCredentials("YuvNet", "password123", WifiSecurity.WPA2_PERSONAL, false)
    )
    val (y, width, height) = renderY(payload, scale = 3)
    assertEquals(payload, QrImageDecoder.decodeYuv(y, width, height))
  }

  @Test
  fun blankImageReturnsNull() {
    val width = 128
    val height = 128
    assertNull(QrImageDecoder.decodePixels(IntArray(width * height) { 0xFFFFFFFF.toInt() }, width, height))
  }

  @Test
  fun invalidPixelInputReturnsNull() {
    assertNull(QrImageDecoder.decodePixels(IntArray(0), 0, 0))
    assertNull(QrImageDecoder.decodePixels(IntArray(10), 4, 4))
    assertNull(QrImageDecoder.decodePixels(IntArray(16 * 16), 16, 16))
  }

  @Test
  fun tinyImageReturnsNull() {
    assertNull(QrImageDecoder.decodePixels(IntArray(10 * 10) { 0xFF000000.toInt() }, 10, 10))
  }

  @Test
  fun invalidYuvInputReturnsNull() {
    assertNull(QrImageDecoder.decodeYuv(ByteArray(0), 0, 0))
    assertNull(QrImageDecoder.decodeYuv(ByteArray(10), 4, 4))
  }

  @Test
  fun credentialsFailureWhenNoQrPresent() {
    val width = 96
    val height = 96
    val result = QrImageDecoder.decodeCredentials(
      IntArray(width * height) { 0xFFFFFFFF.toInt() },
      width,
      height
    )
    assertTrue(result.isFailure)
  }

  @Test
  fun clampImportDimensionStaysInSafeRange() {
    assertEquals(64, QrImageDecoder.clampImportDimension(0))
    assertEquals(64, QrImageDecoder.clampImportDimension(-100))
    assertEquals(1024, QrImageDecoder.clampImportDimension(1024))
    assertEquals(2048, QrImageDecoder.clampImportDimension(100000))
    assertEquals(2048, QrImageDecoder.clampImportDimension(Int.MAX_VALUE))
  }

  @Test
  fun pixelCountGuardRejectsOverflowDimensions() {
    assertTrue(!QrImageDecoder.isPixelCountSafe(0, 100))
    assertTrue(!QrImageDecoder.isPixelCountSafe(100000, 100000))
    assertTrue(!QrImageDecoder.isPixelCountSafe(Int.MAX_VALUE, 2))
    assertTrue(QrImageDecoder.isPixelCountSafe(1024, 1024))
    // Extreme dimensions must return null, never allocate or crash.
    assertNull(QrImageDecoder.decodePixels(IntArray(10), 100000, 100000))
    assertNull(QrImageDecoder.decodeYuv(ByteArray(10), 100000, 100000))
  }

  private data class ArgbImage(val pixels: IntArray, val width: Int, val height: Int)
  private data class YImage(val y: ByteArray, val width: Int, val height: Int)

  private fun renderArgb(text: String, scale: Int): ArgbImage {
    val matrix = QRCodeWriter().encode(
      text,
      BarcodeFormat.QR_CODE,
      0,
      0,
      mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    )
    val width = matrix.width * scale
    val height = matrix.height * scale
    val pixels = IntArray(width * height) { index ->
      val x = (index % width) / scale
      val y = (index / width) / scale
      if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return ArgbImage(pixels, width, height)
  }

  private fun renderY(text: String, scale: Int): YImage {
    val matrix = QRCodeWriter().encode(
      text,
      BarcodeFormat.QR_CODE,
      0,
      0,
      mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    )
    val width = matrix.width * scale
    val height = matrix.height * scale
    val y = ByteArray(width * height) { index ->
      val x = (index % width) / scale
      val yy = (index / width) / scale
      if (matrix.get(x, yy)) 0x00 else 0xFF.toByte()
    }
    return YImage(y, width, height)
  }
}
