package com.deepassist.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

class LocationTool : Tool() {

    override val name = "get_location"
    override val description =
        "Cihazın şu anki konumunu (enlem/boylam ve adres) döndürür. Konumum nerede, neredeyim gibi sorular için kullan."
    override val parameters = emptyMap<String, ToolProperty>()
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Konuma bakıyorum..."

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (!PermissionsHelper.hasLocation(context)) {
                return@withContext ToolResult(
                    false, "",
                    error = "Konum izni verilmemiş. Lütfen Ayarlar > Uygulamalar > deepAssist > İzinler'den Konum iznini açın."
                )
            }
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                return@withContext ToolResult(true, "Konum servisi kapalı. Lütfen cihazınızın konum ayarlarını açın.")
            }

            val location = getCurrentLocation(locationManager)
                ?: return@withContext ToolResult(
                    true,
                    "Konum alınamadı. GPS sinyali bekleniyor olabilir, biraz sonra tekrar deneyin."
                )

            val lat = location.latitude
            val lng = location.longitude
            val accuracy = if (location.hasAccuracy()) " (±${location.accuracy.toInt()}m)" else ""
            val address = reverseGeocode(lat, lng)
            if (address != null) {
                ToolResult(true, "Konumunuz: $address (${formatCoord(lat)}, ${formatCoord(lng)})$accuracy")
            } else {
                ToolResult(true, "Konumunuz: ${formatCoord(lat)}, ${formatCoord(lng)}$accuracy")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location access denied", e)
            ToolResult(false, "", error = "Konum erişimi reddedildi. Lütfen konum iznini kontrol edin.")
        } catch (e: Exception) {
            Log.e(TAG, "Location failed", e)
            ToolResult(false, "", error = "Konum alınamadı: ${e.message}")
        }
    }

    /** Fresh fix with a 10 s cap: fused provider on API 30+, single GPS/network update below. */
    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(locationManager: LocationManager): Location? {
        if (Build.VERSION.SDK_INT >= 30) {
            return suspendCancellableCoroutine { cont ->
                try {
                    locationManager.getCurrentLocation("fused", null, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (cont.isActive) cont.resume(null)
                    }, TIMEOUT_MS)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        return suspendCancellableCoroutine { cont ->
            var finished = false
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (finished) return
                    finished = true
                    if (cont.isActive) {
                        runCatching { locationManager.removeUpdates(this) }
                        cont.resume(loc)
                    }
                }
            }
            val providers = listOfNotNull(
                LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
                LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(it) }
            )
            if (providers.isEmpty()) {
                cont.resume(tryLastKnown(locationManager))
                return@suspendCancellableCoroutine
            }
            val mainLooper = Looper.getMainLooper()
            for (provider in providers) {
                try {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(provider, listener, mainLooper)
                } catch (e: Exception) {
                    Log.w(TAG, "requestSingleUpdate failed for $provider: ${e.message}")
                }
            }
            Handler(mainLooper).postDelayed({
                if (!finished && cont.isActive) {
                    finished = true
                    runCatching { locationManager.removeUpdates(listener) }
                    cont.resume(tryLastKnown(locationManager))
                }
            }, TIMEOUT_MS)
            cont.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    runCatching { locationManager.removeUpdates(listener) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastKnown(locationManager: LocationManager): Location? {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null && System.currentTimeMillis() - loc.time < 30 * 60 * 1000L) return loc
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                ?.firstOrNull() ?: return null
            listOfNotNull(
                address.thoroughfare,
                address.subLocality,
                address.locality,
                address.adminArea,
                address.countryName
            ).joinToString(", ").ifEmpty { null }
        } catch (e: IOException) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder error: ${e.message}")
            null
        }
    }

    private fun formatCoord(coord: Double): String = "%.4f".format(coord)

    companion object {
        private const val TAG = "LocationTool"
        private const val TIMEOUT_MS = 10_000L
    }
}
