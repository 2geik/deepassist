package com.deepassist.tools

import android.app.KeyguardManager
import android.util.Log
import com.deepassist.agent.PanelControl
import com.deepassist.agent.ScreenCapture
import com.deepassist.agent.ScreenReader
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.llm.DeepSeekClient
import com.deepassist.service.AccessibilitySvc
import com.google.gson.JsonObject

/**
 * "Ekranda ne var": reads the app behind the session panel. A well-labelled
 * screen goes to the main model as text; a screen without structure (web
 * pages, images, games) is described from a screenshot that is never stored.
 */
class ReadScreenTool(private val deepSeek: DeepSeekClient) : Tool() {

    override val name = "read_screen"
    override val description =
        "Telefonun ekranında şu an ne olduğunu okur: açık uygulama, yazılar, uyarılar, butonlar. " +
            "'Ekranda ne var', 'ekranda ne yazıyor', 'bu uyarı ne diyor', 'hangi uygulama açık' gibi isteklerde kullan. " +
            "Kameradaki nesneler için değil (onun için look_with_camera)."
    override val parameters = mapOf(
        "question" to ToolProperty(
            type = "string",
            description = "Kullanıcının ekranla ilgili sorusu, örn. 'ekranda ne yazıyor', 'bu uyarı ne diyor'"
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase = "Ekrana bakıyorum..."
    override val timeoutMs = 30_000L

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args.optString("question")?.trim().orEmpty().ifBlank { "Ekranda ne var?" }
        val service = AccessibilitySvc.instance
            ?: return spoken("Ekranı okuyabilmem için erişilebilirlik servisinin açık olması gerekiyor.")
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return spoken("Telefon kilitli. Kilit açıkken ekranı okuyabilirim.")
        }

        PanelControl.stepAside()
        try {
            val screen = ScreenReader.read(service)
                ?: return spoken("Ekranı okuyamadım.")
            Log.i(TAG, "read ${screen.packageName}: ${screen.elements.size} elements, poor=${screen.poor}")
            if (!screen.poor) {
                return ToolResult(
                    success = true,
                    data = "EKRAN İÇERİĞİ (köşeli parantezli numaraları, rol adlarını ve paket adını kullanıcıya söyleme; " +
                        "sorusuna göre doğal dille özetle ya da ilgili yazıyı oku):\n" + screen.describe()
                )
            }
            // Little structure: look at a screenshot instead (memory only)
            return when (val shot = ScreenCapture.take(service, screen.elements)) {
                is ScreenCapture.Result.Failed -> when (shot.reason) {
                    ScreenCapture.Reason.SECURE -> spoken("Bu uygulama ekranına bakmama izin vermiyor.")
                    else -> ToolResult(true, "EKRAN İÇERİĞİ (görüntü alınamadı, bulunanlar):\n" + screen.describe())
                }
                is ScreenCapture.Result.Image -> shot.image.use { image ->
                    deepSeek.describeImage(
                        system = SCREEN_PROMPT,
                        question = "Kullanıcının sorusu: $question\n\nEkrandaki öğeler (numaralar sadece senin için):\n${screen.describe()}",
                        jpeg = image.bytes
                    ).fold(
                        onSuccess = { ToolResult(true, it, speakDirectly = true) },
                        onFailure = { spoken("Ekrana baktım ama yorumlayamadım, internet bağlantısını kontrol eder misin?") }
                    )
                }
            }
        } finally {
            PanelControl.restore()
        }
    }

    private fun spoken(text: String) = ToolResult(false, "", error = text, speakDirectly = true)

    companion object {
        private const val TAG = "ReadScreen"

        private val SCREEN_PROMPT = """
Sen görme engelli bir kullanıcının telefon ekranını okuyorsun. Ekran görüntüsünde dokunulabilir öğeler numaralı turuncu kutularla işaretli; numaralar yalnızca senin için. Cevabın sesli okunacak.

KURALLAR:
- Kullanıcının sorusunu cevapla. Soru genelse önce hangi uygulamada ya da sayfada olduğunu, sonra ekrandaki en önemli içeriği kısaca söyle.
- Uyarı veya pencere varsa onu önce oku.
- Numara, kutu, renk, koordinat, paket adı söyleme. Madde işareti, yıldız, emoji kullanma; doğal Türkçe cümleler kur.
- Görmediğin şeyi uydurma. Okunmayan yer varsa söyle.
- Normalde iki üç cümle yeter; kullanıcı yazının tamamını isterse oku.
        """.trimIndent()
    }
}
