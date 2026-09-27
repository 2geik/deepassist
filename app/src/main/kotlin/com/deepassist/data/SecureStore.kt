package com.deepassist.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.deepassist.BuildConfig

class SecureStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "deepassist_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore can be corrupted on some devices; degrade to plain prefs instead of crashing
        context.getSharedPreferences("deepassist_fallback", Context.MODE_PRIVATE)
    }

    val openAiApiKey: String get() = BuildConfig.OPENAI_API_KEY

    val deepseekApiKey: String get() = BuildConfig.DEEPSEEK_API_KEY

    val tavilyApiKey: String get() = BuildConfig.TAVILY_API_KEY

    var voiceResponseEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_RESPONSE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_RESPONSE, value).apply()

    fun hasOpenAiKey(): Boolean = openAiApiKey.isNotBlank()

    fun hasDeepSeekKey(): Boolean = deepseekApiKey.isNotBlank()

    companion object {
        private const val KEY_VOICE_RESPONSE = "voice_response_enabled"
    }
}
