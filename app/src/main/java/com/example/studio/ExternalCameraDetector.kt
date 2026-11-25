package com.example.studio

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class DetectedUsbVideoDevice(
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    val isDjiDevice: Boolean,
    val isUvcVideoClass: Boolean
)

data class ExternalCameraInventory(
    val hasFrontCamera: Boolean = false,
    val hasRearCamera: Boolean = false,
    val externalCamera2Ids: List<String> = emptyList(),
    val usbVideoDevices: List<DetectedUsbVideoDevice> = emptyList(),
    val statusSummary: String = "No external USB/UVC or DJI camera detected"
) {
    val hasAnyExternalHardware: Boolean
        get() = externalCamera2Ids.isNotEmpty() || usbVideoDevices.isNotEmpty()

    val hasExternalCamera2: Boolean
        get() = externalCamera2Ids.isNotEmpty()

    val hasDjiUsbConnected: Boolean
        get() = usbVideoDevices.any { it.isDjiDevice }

    val hasDjiUsbDevice: Boolean
        get() = hasDjiUsbConnected

    val djiDeviceLabel: String?
        get() = usbVideoDevices.firstOrNull { it.isDjiDevice }?.productName

    val hasUvcUsbDevice: Boolean
        get() = usbVideoDevices.any { it.isUvcVideoClass }
}

/**
 * Inspects real Android Camera2 hardware (front, rear, LENS_FACING_EXTERNAL) and attached USB Host
 * UVC video / HDMI capture card / DJI devices (DJI USB Vendor ID 0x2CA3).
 * Never fabricates connected hardware.
 */
object ExternalCameraDetector {

    private const val DJI_USB_VENDOR_ID = 0x2CA3
    private const val USB_CLASS_VIDEO = 14 // 0x0E UVC

    fun inspect(context: Context): ExternalCameraInventory {
        var hasFront = false
        var hasRear = false
        val externalIds = ArrayList<String>()

        runCatching {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            camMgr?.cameraIdList?.forEach { id ->
                val chars = runCatching { camMgr.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
                when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> hasFront = true
                    CameraCharacteristics.LENS_FACING_BACK -> hasRear = true
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> externalIds.add(id)
                }
            }
        }

        val usbList = ArrayList<DetectedUsbVideoDevice>()
        runCatching {
            val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val devices: Collection<UsbDevice> = usbMgr?.deviceList?.values.orEmpty()
            for (dev in devices) {
                val isDji = dev.vendorId == DJI_USB_VENDOR_ID ||
                    (dev.manufacturerName?.contains("DJI", ignoreCase = true) == true) ||
                    (dev.productName?.contains("DJI", ignoreCase = true) == true) ||
                    (dev.productName?.contains("Osmo", ignoreCase = true) == true)
                val isUvc = isUvcDevice(dev)
                if (isDji || isUvc) {
                    val friendly = dev.productName?.takeIf { it.isNotBlank() }
                        ?: if (isDji) "DJI USB Camera" else "USB UVC / HDMI Capture Card"
                    usbList.add(
                        DetectedUsbVideoDevice(
                            deviceName = dev.deviceName,
                            productName = friendly,
                            vendorId = dev.vendorId,
                            productId = dev.productId,
                            isDjiDevice = isDji,
                            isUvcVideoClass = isUvc
                        )
                    )
                }
            }
        }

        val summary = when {
            usbList.any { it.isDjiDevice } && externalIds.isNotEmpty() ->
                "DJI USB/UVC camera detected (${usbList.first { it.isDjiDevice }.productName}) — ready for live preview"
            usbList.any { it.isDjiDevice } ->
                "DJI USB device detected (${usbList.first { it.isDjiDevice }.productName}). Switch DJI camera to USB Webcam / UVC mode or use an HDMI-to-USB UVC capture card."
            externalIds.isNotEmpty() ->
                "External Camera2/UVC source detected (${externalIds.size} camera${if (externalIds.size > 1) "s" else ""}) — ready"
            usbList.isNotEmpty() ->
                "USB UVC capture device detected (${usbList.first().productName}). OS Camera2 External support depends on device kernel."
            else ->
                "No external DJI / USB-UVC camera connected. Connect via USB-C UVC mode, HDMI capture card, or wireless companion."
        }

        return ExternalCameraInventory(
            hasFrontCamera = hasFront,
            hasRearCamera = hasRear,
            externalCamera2Ids = externalIds,
            usbVideoDevices = usbList,
            statusSummary = summary
        )
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO ||
                (iface.interfaceClass == UsbConstants.USB_CLASS_MISC && iface.interfaceSubclass == 2)
            ) {
                return true
            }
        }
        return false
    }

    fun supportsExternalCamera2Api(): Boolean = true
}
