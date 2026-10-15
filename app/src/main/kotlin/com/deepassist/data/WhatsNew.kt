package com.deepassist.data

import android.content.Context
import com.deepassist.BuildConfig

/**
 * What's new in this version, told once: spoken after the first finished
 * request (so it never delays an urgent one) and shown on Home until dismissed.
 */
object WhatsNew {

    /** First version code this text belongs to; bump together with the text. */
    private const val RELEASE_CODE = 4
    private const val PREFS = "deepassist_state"
    private const val KEY_SPOKEN = "whats_new_spoken"
    private const val KEY_DISMISSED = "whats_new_dismissed"

    const val SPOKEN =
        "Bu arada, deepAssist yenilendi. Artık kamerayla önündekini anlatabilir, yazıları ve faturaları okuyabilirim; " +
            "\"önümde ne var\" ya da \"bunu oku\" demen yeterli. Ekranda ne yazdığını söyleyebilir, ayar değiştirmek gibi " +
            "işleri telefonda senin yerine yapabilirim. Mesaj göndermeden ve ödeme yapmadan önce mutlaka sana sorarım."

    val items = listOf(
        "Kamerayla \"önümde ne var\", \"bunu oku\", \"bu kaç lira\"",
        "Belge okurken \"biraz sola çevir\" diye yönlendirme",
        "\"Ekranda ne yazıyor\" ile ekranı okuma",
        "Telefonu senin yerine kullanma; mesaj ve ödemede önce sorar",
        "Yeni görünüm ve yumuşak sesler"
    )

    fun shouldSpeak(context: Context): Boolean =
        BuildConfig.VERSION_CODE >= RELEASE_CODE && prefs(context).getInt(KEY_SPOKEN, 0) < RELEASE_CODE

    fun markSpoken(context: Context) = prefs(context).edit().putInt(KEY_SPOKEN, RELEASE_CODE).apply()

    fun shouldShowCard(context: Context): Boolean =
        BuildConfig.VERSION_CODE >= RELEASE_CODE && prefs(context).getInt(KEY_DISMISSED, 0) < RELEASE_CODE

    fun dismissCard(context: Context) = prefs(context).edit().putInt(KEY_DISMISSED, RELEASE_CODE).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
