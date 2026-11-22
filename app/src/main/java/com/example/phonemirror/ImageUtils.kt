package com.example.phonemirror

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream

object ImageUtils {

    /** RGBA_8888 ImageReader (экран) -> JPEG. */
    fun imageToJpeg(image: Image, quality: Int = 70): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val w = image.width
        val h = image.height
        val bitmap: Bitmap

        if (plane.pixelStride == 4 && plane.rowStride == w * 4) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val src = ByteArray(rowStride * h)
            buffer.get(src)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                var s = y * rowStride
                var d = y * w
                for (x in 0 until w) {
                    val r = src[s].toInt() and 0xff
                    val g = src[s + 1].toInt() and 0xff
                    val b = src[s + 2].toInt() and 0xff
                    val a = if (pixelStride == 4) src[s + 3].toInt() and 0xff else 0xff
                    pixels[d] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    s += pixelStride
                    d++
                }
            }
            bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** JPEG ImageReader (камера) -> готовые байты. */
    fun jpegImageToBytes(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
