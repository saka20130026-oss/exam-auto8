package com.exam.auto

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest

class CameraHelper(
    private val context: Context,
    private val onStateChange: (String) -> Unit,
    private val onFrame: ((ByteArray) -> Unit)? = null
) {
    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: MultiCameraClient.ICamera? = null
    private var isRunning = false

    fun start(onReady: (Boolean) -> Unit) {
        try {
            cameraClient = MultiCameraClient(context, object : MultiCameraClient.IDeviceConnectCallBack {
                override fun onAttachDev(device: UsbDevice?) {
                    device ?: return
                    Log.d("CameraHelper", "USB 장치 연결: ${device.deviceName}")
                    openCamera(device, onReady)
                }

                override fun onDetachDec(device: UsbDevice?) {
                    onStateChange("USB 카메라 분리됨")
                    isRunning = false
                }

                override fun onConnectDev(device: UsbDevice?, ctrlBlock: Any?) {}
                override fun onDisConnectDev(device: UsbDevice?) {
                    isRunning = false
                }
            })
            cameraClient?.register()
            onStateChange("USB 카메라 대기 중...")
        } catch (e: Exception) {
            Log.e("CameraHelper", "카메라 초기화 실패", e)
            onReady(false)
        }
    }

    private fun openCamera(device: UsbDevice, onReady: (Boolean) -> Unit) {
        val request = CameraRequest.Builder()
            .setPreviewWidth(1920)
            .setPreviewHeight(1080)
            .setRawPreviewData(true)
            .create()

        currentCamera = cameraClient?.openCamera(
            null, // SurfaceView 없이 데이터만 받음
            device,
            request,
            object : ICameraStateCallBack {
                override fun onCameraState(
                    self: MultiCameraClient.ICamera,
                    code: ICameraStateCallBack.State,
                    msg: String?
                ) {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            onStateChange("✅ USB 카메라 연결됨 (FHD)")
                            isRunning = true
                            onReady(true)
                        }
                        ICameraStateCallBack.State.CLOSED -> {
                            onStateChange("카메라 닫힘")
                            isRunning = false
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            onStateChange("❌ 카메라 오류: $msg")
                            isRunning = false
                            onReady(false)
                        }
                    }
                }
            }
        )
    }

    fun captureFrame(callback: (ByteArray?) -> Unit) {
        if (!isRunning || currentCamera == null) {
            callback(null)
            return
        }
        try {
            (currentCamera as? CameraUVC)?.captureImage(object : ICaptureCallBack {
                override fun onBegin() {}
                override fun onError(error: String?) {
                    callback(null)
                }
                override fun onComplete(path: String?) {
                    if (path != null) {
                        try {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                callback(file.readBytes())
                                return
                            }
                        } catch (e: Exception) {
                            Log.e("CameraHelper", "파일 읽기 실패", e)
                        }
                    }
                    callback(null)
                }
            }, null)
        } catch (e: Exception) {
            Log.e("CameraHelper", "캡처 실패", e)
            callback(null)
        }
    }

    fun stop() {
        isRunning = false
        currentCamera = null
        cameraClient?.unRegister()
        cameraClient?.destroy()
        cameraClient = null
    }

    fun isRunning() = isRunning
}
