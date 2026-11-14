package com.samuel.wirelesswhale.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.samuel.wirelesswhale.core.model.WifiCredentials

/** Minimum rendered QR bitmap edge, in pixels. */
const val MIN_QR_BITMAP_SIZE = 256

/** Maximum rendered QR bitmap edge, in pixels. Bounds memory use. */
const val MAX_QR_BITMAP_SIZE = 2048

/**
 * Renders Wi-Fi credentials as a black on white QR bitmap, fully offline.
 *
 * The payload comes from [WifiQrCodec.encode], so unsupported types and
 * invalid values throw IllegalArgumentException with a safe generic
 * message. Throws IllegalStateException only when encoding itself fails.
 * Never logs credentials or payload text.
 */
fun createWifiQrBitmap(credentials: WifiCredentials, size: Int = 768): Bitmap {
  val bounded = size.coerceIn(MIN_QR_BITMAP_SIZE, MAX_QR_BITMAP_SIZE)
  val payload = WifiQrCodec.encode(credentials)
  val matrix = try {
    QRCodeWriter().encode(
      payload,
      BarcodeFormat.QR_CODE,
      bounded,
      bounded,
      mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
      )
    )
  } catch (e: WriterException) {
    throw IllegalStateException("Could not render the QR code.")
  }
  val pixels = IntArray(bounded * bounded)
  for (y in 0 until bounded) {
    val row = y * bounded
    for (x in 0 until bounded) {
      pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    }
  }
  val bitmap = Bitmap.createBitmap(bounded, bounded, Bitmap.Config.ARGB_8888)
  bitmap.setPixels(pixels, 0, bounded, 0, 0, bounded, bounded)
  return bitmap
}
