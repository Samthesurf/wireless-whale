package com.samuel.wirelesswhale.qr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.samuel.wirelesswhale.core.model.WifiCredentials

/**
 * Fully offline QR reading backed by ZXing core. No network, no Play
 * services model download, no logging of payload contents.
 *
 * [decodePixels] and [decodeYuv] are pure JVM logic over int and byte
 * arrays so they stay unit testable. The Bitmap, ImageProxy, and content
 * URI helpers are thin bounded adapters over the same core.
 */
object QrImageDecoder {

  /** Imported images are downsampled so neither side exceeds this. */
  const val MAX_IMPORT_DIMENSION = 1024

  /** Hard guard against absurd bitmap allocations. */
  const val MAX_BITMAP_PIXELS = 4096L * 4096L

  /** Import dimension requests are clamped into this safe range. */
  const val MIN_CLAMPED_DIMENSION = 64
  const val MAX_CLAMPED_DIMENSION = 2048

  private const val MIN_DECODE_DIMENSION = 32
  private const val MIN_FRAME_DIMENSION = 48

  private val READ_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8"
  )

  private val FRAME_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.CHARACTER_SET to "UTF-8"
  )

  /**
   * Reads QR text from ARGB pixels, trying normal and inverted polarity.
   * Returns null when no QR code is readable. Never throws for image
   * content; returns null instead. All area checks use Long before any
   * allocation so extreme dimensions cannot overflow to a small buffer.
   */
  fun decodePixels(argb: IntArray, width: Int, height: Int): String? {
    if (width < MIN_DECODE_DIMENSION || height < MIN_DECODE_DIMENSION) return null
    if (!isPixelCountSafe(width, height)) return null
    if (argb.size.toLong() != width.toLong() * height.toLong()) return null
    val source = RGBLuminanceSource(width, height, argb)
    readSource(source, READ_HINTS)?.let { return it }
    return readSource(InvertedLuminanceSource(source), READ_HINTS)
  }

  /** Reads QR text from a packed Y plane. Single fast attempt for frames. */
  fun decodeYuv(y: ByteArray, width: Int, height: Int): String? {
    if (width < MIN_FRAME_DIMENSION || height < MIN_FRAME_DIMENSION) return null
    if (!isPixelCountSafe(width, height)) return null
    if (y.size.toLong() != width.toLong() * height.toLong()) return null
    val source = PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
    return readSource(source, FRAME_HINTS)
  }

  /**
   * Decodes one camera frame. The caller keeps ownership of [image] and
   * must close it; this function never closes it. Returns null for
   * unsupported formats or unreadable frames.
   */
  fun decodeImageProxy(image: ImageProxy): String? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val width = image.width
    val height = image.height
    if (width < MIN_FRAME_DIMENSION || height < MIN_FRAME_DIMENSION) return null
    if (!isPixelCountSafe(width, height)) return null
    val plane = image.planes[0]
    if (plane.pixelStride != 1) return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val needed = rowStride.toLong() * (height - 1).toLong() + width.toLong()
    if (buffer.remaining().toLong() < needed) return null
    val area = width.toLong() * height.toLong()
    val y = if (rowStride == width) {
      ByteArray(area.toInt()).also { buffer.get(it) }
    } else {
      val out = ByteArray(area.toInt())
      val row = ByteArray(rowStride)
      var offset = 0
      repeat(height) {
        buffer.get(row)
        System.arraycopy(row, 0, out, offset, width)
        offset += width
      }
      out
    }
    return decodeYuv(y, width, height)
  }

  /** Pure bound for image dimensions: Long area math, no allocation. */
  fun isPixelCountSafe(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val area = width.toLong() * height.toLong()
    if (area > MAX_BITMAP_PIXELS) return false
    if (area > Int.MAX_VALUE.toLong()) return false
    return true
  }

  /** Pure clamp for caller-supplied max import dimensions. */
  fun clampImportDimension(requested: Int): Int =
    requested.coerceIn(MIN_CLAMPED_DIMENSION, MAX_CLAMPED_DIMENSION)

  /** Reads QR text from a bitmap. Returns null when unreadable. */
  fun decodeBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    if (!isPixelCountSafe(width, height)) return null
    val area = width.toLong() * height.toLong()
    val pixels = IntArray(area.toInt())
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return decodePixels(pixels, width, height)
  }

  /** Convenience wrapper that parses decoded text as Wi-Fi credentials. */
  fun decodeCredentials(argb: IntArray, width: Int, height: Int): Result<WifiCredentials> {
    val text = decodePixels(argb, width, height)
      ?: return Result.failure(IllegalArgumentException("No QR code was found in that image."))
    return WifiQrCodec.decode(text)
  }

  /**
   * Loads an image chosen through the Storage Access Framework, bounding
   * the decoded size to [maxDimension]. The request is clamped into a safe
   * range so callers cannot force an absurd allocation. Needs no storage
   * permission. Returns null when the image cannot be read. Never throws,
   * never logs.
   */
  fun decodeContentImage(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int = MAX_IMPORT_DIMENSION
  ): Bitmap? {
    return try {
      val bound = clampImportDimension(maxDimension)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      var sample = 1
      while (bounds.outWidth.toLong() / sample > bound ||
        bounds.outHeight.toLong() / sample > bound ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_BITMAP_PIXELS
      ) {
        if (sample >= 1 shl 30) break
        sample *= 2
      }
      val options = BitmapFactory.Options().apply { inSampleSize = sample }
      val bitmap =
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
          ?: return null
      if (!isPixelCountSafe(bitmap.width, bitmap.height)) {
        runCatching { bitmap.recycle() }
        return null
      }
      bitmap
    } catch (e: Exception) {
      null
    }
  }

  private fun readSource(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? {
    return try {
      QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: NotFoundException) {
      null
    } catch (e: ChecksumException) {
      null
    } catch (e: FormatException) {
      null
    }
  }
}
