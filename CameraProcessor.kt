package com.museum.guide.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

private const val TAG = "CameraProcessor"

/**
 * Camera2 封装（按需开关模式）。
 *
 * 设计原则：相机只在 Recognizing 状态下打开，识别结束后调用 [close] 关闭硬件，
 * 彻底解决常开导致的设备发热问题。
 *
 * 生命周期：
 *   open(context)   → 进入 Recognizing 状态时：打开相机硬件，开始推帧
 *   close()         → 离开 Recognizing 状态时：停止推帧 + 关闭相机硬件（保留后台线程）
 *   destroy()       → Activity.onDestroy 时：完全释放所有资源包括后台线程
 */
class CameraProcessor {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /** false = 暂停帧推送 */
    @Volatile private var active = false

    /** true = destroy() 已调用，拒绝一切操作 */
    @Volatile private var destroyed = false

    /** 每帧回调（送 JPEG byte[] 给 TFLiteEngine，仅 active=true 时推送） */
    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 打开相机并开始推帧。每次进入 Recognizing 状态时调用。
     * 若相机已开启（极少情况），直接置 active=true 即可。
     */
    @SuppressLint("MissingPermission")
    fun open(context: Context) {
        if (destroyed) return
        if (cameraDevice != null) { active = true; return }   // 已开启，直接推帧

        active = true
        if (backgroundThread == null) startBackgroundThread()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            Log.e(TAG, "No back camera found"); return
        }

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 4).apply {
            setOnImageAvailableListener({ reader ->
                // 无论 active 状态都必须 acquire+close，否则 buffer 满导致 Camera HAL 超时
                val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    if (active && !destroyed) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        onFrameAvailable?.invoke(bytes)
                    }
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (destroyed) { camera.close(); return }
                cameraDevice = camera
                startCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected"); camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error"); camera.close()
            }
        }, backgroundHandler)
    }

    /**
     * 离开 Recognizing 状态：停止推帧 + 关闭相机硬件（保留后台线程供下次重用）。
     * 彻底断电相机传感器，消除常开发热。
     */
    fun close() {
        active = false
        val handler = backgroundHandler
        val sessionToClose = captureSession; captureSession = null
        val deviceToClose  = cameraDevice;  cameraDevice  = null
        val readerToClose  = imageReader;   imageReader   = null

        if (handler != null) {
            handler.post {
                try { sessionToClose?.close() } catch (_: Exception) {}
                try { deviceToClose?.close()  } catch (_: Exception) {}
                try { readerToClose?.close()  } catch (_: Exception) {}
                Log.d(TAG, "Camera closed (hardware off)")
            }
        } else {
            try { sessionToClose?.close() } catch (_: Exception) {}
            try { deviceToClose?.close()  } catch (_: Exception) {}
            try { readerToClose?.close()  } catch (_: Exception) {}
        }
    }

    /** 兼容旧调用：等同于 close() */
    fun pause() = close()

    /**
     * Activity.onDestroy 时调用：关闭相机硬件 + 停止后台线程，彻底释放资源。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        onFrameAvailable = null
        close()
        stopBackgroundThread()
        Log.d(TAG, "Camera destroy() called")
    }

    /** 兼容旧调用方：等同于 close() */
    fun release() = close()

    // ─────────────────────────────────────────────────────────────────────────

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        val surface = imageReader?.surface ?: return
        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (destroyed) { session.close(); return }
                    captureSession = session
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }.build()
                    try {
                        session.setRepeatingRequest(request, null, backgroundHandler)
                        Log.i(TAG, "Camera capture session started")
                    } catch (e: Exception) {
                        Log.e(TAG, "setRepeatingRequest failed: ${e.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configure failed")
                }
            },
            backgroundHandler
        )
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }
}
