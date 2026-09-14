package com.deepassist.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import java.util.Locale

class FlashlightTool : Tool() {

    override val name = "control_flashlight"
    override val description =
        "El fenerini (flaş) açar veya kapatır. Karanlıkta ışık yakma, feneri aç/kapat gibi komutlar için kullan."
    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "Fener işlemi",
            enum = listOf("on", "off", "toggle")
        )
    )
    override val required = listOf("action")

    private var lastTorchState = false
    private var lastCameraId: String? = null

    override fun dynamicThinkingPhrase(args: JsonObject): String? =
        when (args.optString("action")) {
            "toggle" -> "Feneri açıp kapatıyorum..."
            "on" -> "Feneri açıyorum..."
            "off" -> "Feneri kapatıyorum..."
            else -> "Feneri ayarlıyorum..."
        }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.optString("action")
            ?: return ToolResult(false, "", error = "Fener işlemi (action) belirtilmedi. on, off veya toggle olmalı.")
        if (!PermissionsHelper.hasCamera(context)) {
            return ToolResult(
                false, "",
                error = "Kamera izni verilmemiş. Fener için kamera izni gerekiyor. " +
                    "Lütfen Ayarlar > Uygulamalar > deepAssist > İzinler'den Kamera iznini açın."
            )
        }

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult(false, "", error = "Cihazınızda flaş (el feneri) bulunamadı.")

            val turnOn = when (action.lowercase(Locale.ROOT)) {
                "on" -> true
                "off" -> false
                "toggle" -> !isTorchOn(flashCameraId)
                else -> return ToolResult(false, "", error = "Geçersiz işlem: $action. on, off veya toggle olmalı.")
            }
            cameraManager.setTorchMode(flashCameraId, turnOn)
            lastTorchState = turnOn
            lastCameraId = flashCameraId
            ToolResult(true, if (turnOn) "El feneri açıldı." else "El feneri kapatıldı.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Torch access denied", e)
            ToolResult(false, "", error = "Fener erişimi reddedildi. Lütfen kamera iznini kontrol edin.")
        } catch (e: Exception) {
            Log.e(TAG, "Torch control failed", e)
            ToolResult(false, "", error = "El feneri kontrol edilemedi: ${e.message}")
        }
    }

    private fun isTorchOn(cameraId: String): Boolean =
        cameraId == lastCameraId && lastTorchState

    companion object {
        private const val TAG = "FlashlightTool"
    }
}
