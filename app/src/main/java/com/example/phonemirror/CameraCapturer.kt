package com.example.phonemirror

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

class CameraCapturer(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit
) {
    private val thread = HandlerThread("camera-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    fun start() {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList
        val id = ids.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull() ?: return

        val map = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull() ?: return

        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onFrame(ImageUtils.jpegImageToBytes(image))
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, handler)
    }

    private fun createSession(device: CameraDevice) {
        device.createCaptureSession(listOf(reader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader!!.surface)
                    }
                    s.setRepeatingRequest(request.build(), null, handler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, handler)
    }

    fun stop() {
        session?.close()
        camera?.close()
        reader?.close()
        thread.quitSafely()
    }
}
