package com.deepassist.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class LocationTool : Tool() {

    override val name = "get_location"
    override val description =
        "Cihazın şu anki konumunu (semt, ilçe, şehir ve açık adres) döndürür. " +
            "'Neredeyim', 'konumum neresi' gibi sorularda kullan."
    override val parameters = emptyMap<String, com.deepassist.data.ToolProperty>()
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Konumuna bakıyorum..."

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.hasLocation(context)) {
            return ToolResult(false, "", error = "Konum izni verilmemiş. Uygulama ayarlarından konum iznini açman gerekiyor.")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            return ToolResult(false, "", error = "Telefonun konum servisi kapalı.")
        }

        val loc = currentLocation(lm) ?: lastKnownLocation(lm)
            ?: return ToolResult(false, "", error = "Konum alınamadı. Açık alanda tekrar dene.")

        val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }
        if (address == null) {
            return ToolResult(
                true,
                "Adres çözülemedi. Koordinatlar: %.5f, %.5f".format(Locale.US, loc.latitude, loc.longitude)
            )
        }
        val area = listOfNotNull(address.subLocality, address.subAdminArea, address.adminArea)
            .distinct()
            .joinToString(", ")
        val line = address.getAddressLine(0)
        return ToolResult(
            true,
            buildString {
                append("Konum: $area.")
                if (!line.isNullOrBlank()) append(" Açık adres: $line.")
                if (loc.hasAccuracy()) append(" Doğruluk yaklaşık ${loc.accuracy.toInt()} metre.")
            }
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, signal, ContextCompat.getMainExecutor(context)
                ) { location -> if (cont.isActive) cont.resume(location) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    private fun reverseGeocode(loc: Location): android.location.Address? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.forLanguageTag("tr-TR"))
                .getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()
        } catch (e: Exception) {
            Log.w("LocationTool", "reverse geocode failed: ${e.message}")
            null
        }
    }
}
