package com.deepassist.tools

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.DeviceUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class PhoneActionTool : Tool() {

    override val name = "phone_action"
    override val description =
        "Telefonda genel işlemler yapar. Uygulama açma, URL açma, alarm kurma, " +
            "zamanlayıcı başlatma, paylaşım, e-posta, navigasyon, ayar ekranı açma ve " +
            "kişi düzenleme gibi işlemler için kullan. Tanımlı özel bir araç yoksa bunu dene."

    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "Yapılacak işlem türü",
            enum = listOf(
                "open_app", "open_url", "set_alarm", "set_timer",
                "share_text", "compose_email", "navigate_map",
                "open_settings_screen", "edit_contact_screen"
            )
        ),
        "app_name" to ToolProperty(
            type = "string",
            description = "Açılacak uygulamanın adı (open_app için)"
        ),
        "url" to ToolProperty(
            type = "string",
            description = "Açılacak URL (open_url için)"
        ),
        "hour" to ToolProperty(
            type = "string",
            description = "Alarm saati (set_alarm için, 0-23)"
        ),
        "minute" to ToolProperty(
            type = "string",
            description = "Alarm dakikası (set_alarm için, 0-59)"
        ),
        "days" to ToolProperty(
            type = "array",
            description = "Alarmın tekrarlanacağı günler (set_alarm için). Boş bırakılırsa tek seferlik alarm kurulur. " +
                "Geçerli değerler: \"pazartesi\", \"salı\", \"çarşamba\", \"perşembe\", \"cuma\", \"cumartesi\", \"pazar\". " +
                "\"her gün\" için tüm günleri gönder.",
            items = ToolProperty(type = "string", description = "Gün adı")
        ),
        "message" to ToolProperty(
            type = "string",
            description = "Alarm mesajı veya paylaşım metni"
        ),
        "seconds" to ToolProperty(
            type = "string",
            description = "Zamanlayıcı süresi saniye cinsinden (set_timer için)"
        ),
        "text" to ToolProperty(
            type = "string",
            description = "Paylaşılacak metin (share_text için)"
        ),
        "email_to" to ToolProperty(
            type = "string",
            description = "Alıcı e-posta adresi (compose_email için)"
        ),
        "subject" to ToolProperty(
            type = "string",
            description = "E-posta konusu (compose_email için)"
        ),
        "body" to ToolProperty(
            type = "string",
            description = "E-posta gövdesi (compose_email için)"
        ),
        "destination" to ToolProperty(
            type = "string",
            description = "Navigasyon hedefi adres (navigate_map için)"
        ),
        "settings_screen" to ToolProperty(
            type = "string",
            description = "Ayar ekranı türü (open_settings_screen için)",
            enum = listOf(
                "wifi", "bluetooth", "battery", "display", "sound",
                "location", "airplane", "notifications", "date", "app_details"
            )
        ),
        "contact_name" to ToolProperty(
            type = "string",
            description = "Düzenlenecek kişinin adı (edit_contact_screen için)"
        )
    )

    override val required = listOf("action")
    override val waitForSpeech = false

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        val action = args.optString("action")
        return when (action) {
            "open_app" -> "${args.optString("app_name") ?: "Uygulama"} uygulamasını açıyorum..."
            "open_url" -> "Sayfayı açıyorum..."
            "set_alarm" -> "Alarmı kuruyorum..."
            "set_timer" -> "Zamanlayıcıyı başlatıyorum..."
            "share_text" -> "Paylaşımı başlatıyorum..."
            "compose_email" -> "E-postayı hazırlıyorum..."
            "navigate_map" -> "Haritada yol tarifini açıyorum..."
            "open_settings_screen" -> "Ayarları açıyorum..."
            "edit_contact_screen" -> "${args.optString("contact_name") ?: "Kişi"} kaydını açıyorum..."
            else -> "İşlemini yapıyorum..."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action")
            ?: return@withContext ToolResult(false, "", error = "İşlem türü (action) belirtilmedi.")

        try {
            when (action) {
                "open_app" -> executeOpenApp(args)
                "open_url" -> executeOpenUrl(args)
                "set_alarm" -> executeSetAlarm(args)
                "set_timer" -> executeSetTimer(args)
                "share_text" -> executeShareText(args)
                "compose_email" -> executeComposeEmail(args)
                "navigate_map" -> executeNavigateMap(args)
                "open_settings_screen" -> executeOpenSettingsScreen(args)
                "edit_contact_screen" -> executeEditContactScreen(args)
                else -> ToolResult(false, "", error = "Bilinmeyen işlem: $action")
            }
        } catch (e: Exception) {
            ToolResult(false, "", error = "İşlem sırasında hata oluştu: ${e.message}")
        }
    }

    // ----------------------------------------------------------------
    // open_app
    // ----------------------------------------------------------------
    private fun executeOpenApp(args: JsonObject): ToolResult {
        val appName = args.optString("app_name")?.trim()
            ?: return ToolResult(false, "", error = "Uygulama adı (app_name) belirtilmedi.")

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        var bestMatch: String? = null
        var bestLabel: String? = null

        // Prefer exact match (ignoring case / Turkish chars)
        val asciiAppName = DeviceUtils.toAsciiTurkce(appName)
        for (ri in resolveInfos) {
            val label = ri.loadLabel(pm).toString()
            val asciiLabel = DeviceUtils.toAsciiTurkce(label)
            if (asciiLabel == asciiAppName) {
                bestMatch = ri.activityInfo.packageName
                bestLabel = label
                break
            }
        }

        // Fallback to contains match
        if (bestMatch == null) {
            for (ri in resolveInfos) {
                val label = ri.loadLabel(pm).toString()
                val asciiLabel = DeviceUtils.toAsciiTurkce(label)
                if (asciiLabel.contains(asciiAppName)) {
                    bestMatch = ri.activityInfo.packageName
                    bestLabel = label
                    break
                }
            }
        }

        if (bestMatch == null) {
            return ToolResult(true, "Rehberde \"$appName\" uygulaması bulunamadı.")
        }

        val launchIntent = pm.getLaunchIntentForPackage(bestMatch)
        if (launchIntent == null) {
            return ToolResult(false, "", error = "\"$bestLabel\" uygulaması açılamadı.")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ToolResult(true, "\"$bestLabel\" uygulaması açıldı.")
    }

    // ----------------------------------------------------------------
    // open_url
    // ----------------------------------------------------------------
    private fun executeOpenUrl(args: JsonObject): ToolResult {
        var url = args.optString("url")?.trim()
            ?: return ToolResult(false, "", error = "URL (url) belirtilmedi.")

        if (!url.contains("://")) {
            url = "https://$url"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "Sayfa açılıyor...")
    }

    // ----------------------------------------------------------------
    // set_alarm
    // ----------------------------------------------------------------
    private fun executeSetAlarm(args: JsonObject): ToolResult {
        val hourStr = args.optString("hour")
        val minuteStr = args.optString("minute")
        if (hourStr == null || minuteStr == null) {
            return ToolResult(false, "", error = "Alarm için saat (hour) ve dakika (minute) belirtilmeli.")
        }
        val hour = hourStr.toIntOrNull()
            ?: return ToolResult(false, "", error = "Saat değeri geçersiz: $hourStr")
        val minute = minuteStr.toIntOrNull()
            ?: return ToolResult(false, "", error = "Dakika değeri geçersiz: $minuteStr")

        val message = args.optString("message")?.trim()
        val days = parseDays(args.get("days")?.takeIf { it.isJsonArray }?.asJsonArray)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message ?: "")
            if (days != null) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val formatted = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        val daysSuffix = if (days != null) " (tekrarlı)" else ""
        return ToolResult(true, "Alarm $formatted için kuruldu$daysSuffix.")
    }

    private val dayNameToCalendar = mapOf(
        "pazartesi" to Calendar.MONDAY,
        "salı" to Calendar.TUESDAY,
        "çarşamba" to Calendar.WEDNESDAY,
        "perşembe" to Calendar.THURSDAY,
        "cuma" to Calendar.FRIDAY,
        "cumartesi" to Calendar.SATURDAY,
        "pazar" to Calendar.SUNDAY
    )

    /** Turkish day names → Calendar constants for EXTRA_DAYS; null means a one-shot alarm. */
    private fun parseDays(daysArray: JsonArray?): ArrayList<Int>? {
        if (daysArray == null || daysArray.size() == 0) return null
        val result = ArrayList<Int>()
        for (elem in daysArray) {
            val dayName = runCatching { elem.asString }.getOrNull()
                ?.lowercase(Locale.ROOT)?.trim() ?: continue
            if (dayName == "her gün" || dayName == "hergun") {
                return arrayListOf(
                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                    Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
                )
            }
            val calDay = dayNameToCalendar[dayName] ?: continue
            if (calDay !in result) result.add(calDay)
        }
        return result.ifEmpty { null }
    }

    // ----------------------------------------------------------------
    // set_timer
    // ----------------------------------------------------------------
    private fun executeSetTimer(args: JsonObject): ToolResult {
        val secondsStr = args.optString("seconds")
            ?: return ToolResult(false, "", error = "Zamanlayıcı için süre (seconds) belirtilmeli.")
        val seconds = secondsStr.toIntOrNull()
            ?: return ToolResult(false, "", error = "Süre değeri geçersiz: $secondsStr")

        val message = args.optString("message")?.trim()

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message ?: "")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(true, "Zamanlayıcı $seconds saniye için başlatıldı.")
    }

    // ----------------------------------------------------------------
    // share_text
    // ----------------------------------------------------------------
    private fun executeShareText(args: JsonObject): ToolResult {
        val text = args.optString("text")?.trim()
            ?: return ToolResult(false, "", error = "Paylaşılacak metin (text) belirtilmedi.")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "Paylaş")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return ToolResult(true, "Paylaşım ekranı açıldı.")
    }

    // ----------------------------------------------------------------
    // compose_email
    // ----------------------------------------------------------------
    private fun executeComposeEmail(args: JsonObject): ToolResult {
        val emailTo = args.optString("email_to")?.trim()
            ?: return ToolResult(false, "", error = "Alıcı e-posta adresi (email_to) belirtilmedi.")
        val subject = args.optString("subject")?.trim() ?: ""
        val body = args.optString("body")?.trim() ?: ""

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailTo))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(true, "E-posta ekranı açıldı.")
    }

    // ----------------------------------------------------------------
    // navigate_map
    // ----------------------------------------------------------------
    private fun executeNavigateMap(args: JsonObject): ToolResult {
        val destination = args.optString("destination")?.trim()
            ?: return ToolResult(false, "", error = "Navigasyon hedefi (destination) belirtilmedi.")

        val googleIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        try {
            context.startActivity(googleIntent)
        } catch (e: ActivityNotFoundException) {
            val geoIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(geoIntent)
        }
        return ToolResult(true, "Navigasyon açıldı.")
    }

    // ----------------------------------------------------------------
    // open_settings_screen
    // ----------------------------------------------------------------
    private fun executeOpenSettingsScreen(args: JsonObject): ToolResult {
        val screen = args.optString("settings_screen")?.trim()
            ?: return ToolResult(false, "", error = "Ayar ekranı türü (settings_screen) belirtilmedi.")

        val intent = when (screen) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "app_details" -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            else -> return ToolResult(false, "", error = "Bilinmeyen ayar ekranı: $screen")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "Ayar ekranı açıldı.")
    }

    // ----------------------------------------------------------------
    // edit_contact_screen
    // ----------------------------------------------------------------
    private fun executeEditContactScreen(args: JsonObject): ToolResult {
        val contactName = args.optString("contact_name")?.trim()
            ?: return ToolResult(false, "", error = "Kişi adı (contact_name) belirtilmedi.")

        val results = ContactLookup.search(context, contactName)

        if (results.isEmpty()) {
            return ToolResult(true, "Rehberde \"$contactName\" bulunamadı.")
        }

        // Deduplicate by contact ID
        val uniqueIds = linkedSetOf<String>()
        val namesById = linkedMapOf<String, String>()
        for (r in results) {
            if (uniqueIds.add(r.id)) {
                namesById[r.id] = r.name
            }
        }

        if (uniqueIds.size > 1) {
            val listing = namesById.values.joinToString("\n") { "- $it" }
            return ToolResult(
                true,
                "Rehberde \"$contactName\" ile eşleşen birden fazla kişi bulundu:\n$listing\n\nHangisini düzenlemek istersiniz?"
            )
        }

        val id = uniqueIds.single()
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                id.toLong()
            )
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(true, "Kişi düzenleme ekranı açıldı.")
    }
}
