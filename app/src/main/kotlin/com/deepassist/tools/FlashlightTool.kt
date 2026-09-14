package com.deepassist.tools

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

class FlashlightTool : Tool() {

    override val name = "control_flashlight"
    override val description = "El fenerini açar, kapatır veya durumunu tersine çevirir."
    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "on (aç), off (kapat), toggle (tersine çevir)",
            enum = listOf("on", "off", "toggle")
        )
    )
    override val required = listOf("action")

    private var cameraId: String? = null

    @Volatile private var torchOn = false

    override fun initialize(context: Context) {
        super.initialize(context)
        val cm = this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = runCatching {
            val withFlash = cm.cameraIdList.filter {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        }.getOrNull()

        // Track state changed from quick settings too, so "toggle" stays accurate.
        runCatching {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = cameraId ?: return ToolResult(false, "", error = "Bu cihazda el feneri bulunamadı.")
        val on = when (args.optString("action")?.lowercase()) {
            "on" -> true
            "off" -> false
            "toggle" -> !torchOn
            else -> return ToolResult(false, "", error = "Geçersiz fener komutu.")
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            cm.setTorchMode(id, on)
            torchOn = on
            ToolResult(true, if (on) "Fener açıldı." else "Fener kapatıldı.")
        } catch (e: CameraAccessException) {
            ToolResult(false, "", error = "Kamera başka bir uygulama tarafından kullanıldığı için fener kontrol edilemedi.")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, "", error = "Fener kontrol edilemedi.")
        }
    }
}
