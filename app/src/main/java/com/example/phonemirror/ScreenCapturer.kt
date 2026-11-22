package com.example.phonemirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.graphics.PixelFormat
import android.view.WindowManager

class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit
) {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    fun start() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onFrame(ImageUtils.imageToJpeg(image))
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "PhoneMirrorScreen",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    fun stop() {
        virtualDisplay?.release()
        reader?.close()
        thread.quitSafely()
    }
}
