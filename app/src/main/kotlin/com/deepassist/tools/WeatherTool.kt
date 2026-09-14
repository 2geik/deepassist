package com.deepassist.tools

import android.location.Geocoder
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gets current weather from Open-Meteo (free, no API key required).
 *
 * Can look up weather by city name (geocoded via Open-Meteo Geocoding API)
 * or uses the device's current location if no city is provided.
 */
class WeatherTool : Tool() {

    override val name = "get_weather"
    override val description =
        "Hava durumunu sorgular. Şehir adı verilirse o şehrin, " +
            "verilmezse cihazın bulunduğu konumun hava durumunu döndürür. " +
            "Sıcaklık, nem, rüzgar ve hava durumu açıklaması içerir."
    override val parameters = mapOf(
        "city" to ToolProperty(
            type = "string",
            description = "Hava durumu sorgulanacak şehir adı. " +
                "Boş bırakılırsa cihazın şu anki konumu kullanılır."
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = null

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        val city = args.optString("city")
        return if (city != null) {
            "$city için hava durumuna bakıyorum..."
        } else {
            "Hava durumuna bakıyorum..."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val city = args.optString("city")?.trim()

        try {
            // Resolve coordinates
            val (lat, lng, locationLabel) = if (city != null) {
                geocodeCity(city) ?: return@withContext ToolResult(
                    false, "",
                    error = "\"$city\" için konum bilgisi alınamadı. Lütfen şehir adını kontrol edin."
                )
            } else {
                // Use device location
                val loc = getDeviceLocation()
                if (loc != null) {
                    val label = reverseGeocode(loc.first, loc.second) ?: "Bulunduğunuz konum"
                    Triple(loc.first, loc.second, label)
                } else {
                    return@withContext ToolResult(
                        false, "",
                        error = "Konum alınamadı. Şehir adı vererek deneyin veya konum izinlerini kontrol edin."
                    )
                }
            }

            // Fetch weather from Open-Meteo
            val weather = fetchWeather(lat, lng)
            if (weather == null) {
                return@withContext ToolResult(false, "", error = "Hava durumu alınamadı. Lütfen tekrar deneyin.")
            }

            val desc = weatherCodeToTurkish(weather.weatherCode)
            val roundedTemp = Math.round(weather.temperature).toInt()
            val result = buildString {
                append("$locationLabel için hava durumu:\n")
                append("• $roundedTemp°C, $desc\n")
                append("• Nem: %${weather.humidity}\n")
                append("• Rüzgar: ${Math.round(weather.windSpeed).toInt()} km/s")
            }

            ToolResult(true, result)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            ToolResult(false, "", error = "Hava durumu alınamadı: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    // Internal — Geocoding
    // ---------------------------------------------------------------

    /**
     * Geocode a city name to coordinates.
     * Tries Open-Meteo Geocoding API first, falls back to Android Geocoder.
     */
    private fun geocodeCity(city: String): Triple<Double, Double, String>? {
        // Try Open-Meteo Geocoding API
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?" +
                "name=${java.net.URLEncoder.encode(city, "UTF-8")}" +
                "&count=1&language=tr&format=json"
            val json = httpGet(url) ?: return null
            val results = json.getAsJsonArray("results")
            if (results != null && results.size() > 0) {
                val r = results[0].asJsonObject
                val lat = r.get("latitude")?.asDouble ?: return null
                val lng = r.get("longitude")?.asDouble ?: return null
                val name = r.get("name")?.asString ?: city
                val country = r.get("country")?.asString
                val label = if (country != null) "$name, $country" else name
                return Triple(lat, lng, label)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo geocoding failed, trying Android Geocoder", e)
        }

        // Fallback to Android Geocoder
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocationName(city, 1)
                if (!addresses.isNullOrEmpty()) {
                    val a = addresses[0]
                    return Triple(a.latitude, a.longitude, city)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Android Geocoder failed: ${e.message}")
            }
        }

        return null
    }

    /**
     * Reverse-geocode device coordinates to a human-readable neighborhood/district
     * name so the assistant can name the actual locality (e.g. "Kağıthane") instead
     * of the whole city. Falls back to sub-locality → locality → sub-admin area.
     */
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(context, Locale("tr", "TR"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            val a = addresses?.firstOrNull() ?: return null
            a.subLocality
                ?: a.locality
                ?: a.subAdminArea
                ?: a.adminArea
        } catch (e: IOException) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }
    }

    /**
     * Get device location for weather when no city is specified.
     * Uses last-known location with a short freshness check.
     */
    private fun getDeviceLocation(): Pair<Double, Double>? {
        if (!PermissionsHelper.hasLocation(context)) return null

        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager

        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        )
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null && (System.currentTimeMillis() - loc.time) < 30 * 60 * 1000) {
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (_: SecurityException) {}
        }
        return null
    }

    // ---------------------------------------------------------------
    // Internal — Weather API
    // ---------------------------------------------------------------

    /**
     * Fetch current weather from Open-Meteo.
     * https://open-meteo.com/en/docs
     */
    private fun fetchWeather(lat: Double, lng: Double): WeatherCurrent? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lng" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&timezone=auto&forecast_days=1"

        val json = httpGet(url) ?: return null

        val currentObj = json.getAsJsonObject("current")
            ?: return null

        return WeatherCurrent(
            temperature = currentObj.get("temperature_2m")?.asDouble ?: return null,
            humidity = currentObj.get("relative_humidity_2m")?.asInt ?: 0,
            weatherCode = currentObj.get("weather_code")?.asInt ?: 0,
            windSpeed = currentObj.get("wind_speed_10m")?.asDouble ?: 0.0
        )
    }

    // ---------------------------------------------------------------
    // Internal — HTTP
    // ---------------------------------------------------------------

    private fun httpGet(url: String): JsonObject? {
        val request = Request.Builder().url(url).get().build()
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code}: $body")
                return null
            }
            Gson().fromJson(body, JsonObject::class.java)
        } catch (e: IOException) {
            Log.e(TAG, "HTTP GET failed: $url — ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "HTTP parse error: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------
    // Internal — Weather Code Mapping (WMO)
    // ---------------------------------------------------------------

    /**
     * Map WMO weather codes to Turkish descriptions.
     * https://www.nodc.noaa.gov/archive/arc0021/0002199/1.1/data/0-data/HTML/WMO-CODE/WMO4677.HTM
     */
    private fun weatherCodeToTurkish(code: Int): String {
        return when (code) {
            0 -> "Açık"
            1 -> "Az bulutlu"
            2 -> "Parçalı bulutlu"
            3 -> "Bulutlu"
            45, 48 -> "Sisli"
            51 -> "Hafif çiseleme"
            53 -> "Çiseleme"
            55 -> "Yoğun çiseleme"
            56, 57 -> "Dondurucu çiseleme"
            61 -> "Hafif yağmur"
            63 -> "Yağmurlu"
            65 -> "Şiddetli yağmur"
            66, 67 -> "Dondurucu yağmur"
            71 -> "Hafif kar"
            73 -> "Karlı"
            75 -> "Yoğun kar"
            77 -> "Kar taneleri"
            80 -> "Hafif sağanak"
            81 -> "Sağanak yağış"
            82 -> "Şiddetli sağanak"
            85 -> "Hafif kar sağanağı"
            86 -> "Yoğun kar sağanağı"
            95 -> "Gök gürültülü fırtına"
            96, 99 -> "Dolulu fırtına"
            else -> "Bilinmiyor"
        }
    }

    // ---------------------------------------------------------------
    // Data classes
    // ---------------------------------------------------------------

    data class WeatherCurrent(
        val temperature: Double,
        val humidity: Int,
        val weatherCode: Int,
        val windSpeed: Double
    )

    companion object {
        private const val TAG = "WeatherTool"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
