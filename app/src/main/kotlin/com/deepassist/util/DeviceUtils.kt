package com.deepassist.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.deepassist.data.DeviceState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DeviceUtils {

    private val turkishLocale = Locale("tr", "TR")

    fun getDeviceState(context: Context): DeviceState {
        val now = Calendar.getInstance().time
        val time = SimpleDateFormat("HH:mm", turkishLocale).format(now)
        val date = SimpleDateFormat("d MMMM yyyy", turkishLocale).format(now)
        val dayOfWeek = SimpleDateFormat("EEEE", turkishLocale).format(now)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return DeviceState(
            currentTime = time,
            currentDate = date,
            batteryLevel = level,
            isCharging = batteryManager.isCharging,
            networkType = getNetworkType(context),
            dayOfWeek = dayOfWeek
        )
    }

    fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Bağlantı yok"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil veri"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Bilinmiyor"
        }
    }

    // ------------------------------------------------------------------
    // Phone number normalization (Turkish mobile)
    // ------------------------------------------------------------------

    /**
     * Normalizes a Turkish phone number to the standard 0xxxxxxxxxx format.
     *
     *   541xxx... (10 digits)  → 0541xxx...
     *   0541xxx... (11 digits) → 0541xxx...  (unchanged)
     *   +90541xxx...           → 0541xxx...
     *   90541xxx... (12 digits)→ 0541xxx...
     */
    fun normalizePhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 10 && digits.startsWith("5") -> "0$digits"
            digits.length == 11 && digits.startsWith("0") -> digits
            digits.startsWith("90") && digits.length == 12 -> "0${digits.drop(2)}"
            digits.startsWith("900") && digits.length == 13 -> "0${digits.drop(3)}"
            digits.startsWith("90") && digits.length == 13 -> "0${digits.drop(2)}"
            digits.startsWith("0090") && digits.length == 14 -> "0${digits.drop(4)}"
            digits.startsWith("0090") && digits.length == 15 -> "0${digits.drop(5)}"
            else -> digits // unknown format — return digits as-is
        }
    }

    /** 0xxxxxxxxxx → [countryCode]xxxxxxxxxx, the form wa.me links expect. */
    fun toInternationalPhoneNumber(raw: String, countryCode: String = "90"): String {
        val normalized = normalizePhoneNumber(raw)
        return if (normalized.startsWith("0") && normalized.length == 11) {
            countryCode + normalized.substring(1)
        } else {
            normalized
        }
    }

    // ------------------------------------------------------------------
    // Turkish text utilities
    // ------------------------------------------------------------------

    /**
     * Converts Turkish letters to their ASCII equivalents so that
     * an ASCII STT query can match Turkish-character text.
     *
     *   ç→c  ş→s  ğ→g  ö→o  ü→u  ı→i  İ→i
     */
    fun toAsciiTurkce(s: String): String {
        var r = s.lowercase(turkishLocale)
        r = r.replace('ç', 'c')
        r = r.replace('ş', 's')
        r = r.replace('ğ', 'g')
        r = r.replace('ö', 'o')
        r = r.replace('ü', 'u')
        r = r.replace('ı', 'i')
        return r
    }

    /**
     * Strips common Turkish noun suffixes that STT glues onto names
     * ("Sevinç'i ara" → transcribed as "sevinci ara").
     *
     * Returns candidate stems, longest first. Example for "sevinci":
     *   ["sevinci", "sevinc"]
     */
    fun turkceIsimKirp(query: String): List<String> {
        val q = query.lowercase(turkishLocale).trim()
        val set = linkedSetOf(q)
        val s1 = _tekEkKirp(q)
        if (s1 != q && s1.length >= 2) {
            set.add(s1)
            val s2 = _tekEkKirp(s1)
            if (s2 != s1 && s2.length >= 2) set.add(s2)
        }
        return set.toList()
    }

    /** Removes one Turkish suffix layer from the end of [s]. */
    private fun _tekEkKirp(s: String): String {
        val suffixes = listOf(
            "den", "dan", "ten", "tan",       // -den/-dan/-ten/-tan
            "ler", "lar",                       // -ler/-lar
            "nin", "nın", "nun", "nün",        // -nin/-nın/-nun/-nün
            "de", "da", "te", "ta",            // -de/-da/-te/-ta
            "in", "ın", "un", "ün",            // -in/-ın/-un/-ün
            "im", "ım", "um", "üm",            // -im/-ım/-um/-üm
            "yi", "yı", "yu", "yü",            // -yi/-yı/-yu/-yü
            "i", "ı", "u", "ü",                // -i/-ı/-u/-ü
            "e", "a",                           // -e/-a
        )
        for (suffix in suffixes) {
            if (s.length > suffix.length && s.endsWith(suffix)) {
                val stem = s.dropLast(suffix.length)
                if (stem.length >= 2) return stem
            }
        }
        return s
    }
}
