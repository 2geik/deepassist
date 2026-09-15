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

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI, DEFAULT_OPENAI) ?: DEFAULT_OPENAI
        set(value) = prefs.edit().putString(KEY_OPENAI, value.trim()).apply()

    var deepseekApiKey: String
        get() = prefs.getString(KEY_DEEPSEEK, DEFAULT_DEEPSEEK) ?: DEFAULT_DEEPSEEK
        set(value) = prefs.edit().putString(KEY_DEEPSEEK, value.trim()).apply()

    var tavilyApiKey: String
        get() = prefs.getString(KEY_TAVILY, DEFAULT_TAVILY) ?: DEFAULT_TAVILY
        set(value) = prefs.edit().putString(KEY_TAVILY, value.trim()).apply()

    var voiceResponseEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_RESPONSE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_RESPONSE, value).apply()

    fun hasOpenAiKey(): Boolean = openAiApiKey.isNotBlank()

    fun hasDeepSeekKey(): Boolean = deepseekApiKey.isNotBlank()

    companion object {
        private const val KEY_OPENAI = "openai_api_key"
        private const val KEY_DEEPSEEK = "deepseek_api_key"
        private const val KEY_TAVILY = "tavily_api_key"
        private const val KEY_VOICE_RESPONSE = "voice_response_enabled"

        // Defaults come from BuildConfig, generated at build time from the
        // gitignored local.properties — never hardcoded in source/git history.
        private val DEFAULT_OPENAI = BuildConfig.OPENAI_API_KEY
        private val DEFAULT_DEEPSEEK = BuildConfig.DEEPSEEK_API_KEY
        private val DEFAULT_TAVILY = BuildConfig.TAVILY_API_KEY
    }
}
