package com.deepassist.tools

import com.deepassist.camera.CameraCapture
import com.deepassist.camera.LastPhoto
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.llm.DeepSeekClient
import com.deepassist.llm.DeepSeekHttpException
import com.deepassist.speech.Earcons
import com.google.gson.JsonObject
import java.io.IOException

/**
 * "Gözüm ol": takes a photo with the back camera and has deepseek-flash
 * describe it for a blind user. The answer is spoken as is.
 */
class CameraLookTool(private val deepSeek: DeepSeekClient) : Tool() {

    override val name = "look_with_camera"
    override val description =
        "Telefonun arka kamerasıyla hemen fotoğraf çekip görme engelli kullanıcıya ne gördüğünü anlatır. " +
            "'Önümde ne var', 'bu ne', 'bunu oku', 'ne yazıyor' (kağıt, kutu, tabela, ekran dışı her şey), " +
            "'bu kaç lira', 'bu ne renk', 'son kullanma tarihi ne', 'ilaç ne' gibi isteklerde kullan. " +
            "Sonuç doğrudan kullanıcıya okunur."
    override val parameters = mapOf(
        "question" to ToolProperty(
            type = "string",
            description = "Kullanıcının sorusu kendi sözleriyle, örn. 'önümde ne var', 'bu ilacın son kullanma tarihi ne'"
        ),
        "mode" to ToolProperty(
            type = "string",
            description = "describe: etrafı/nesneyi anlat. read_text: yazıyı oku. money: parayı say.",
            enum = listOf("describe", "read_text", "money")
        ),
        "use_last_photo" to ToolProperty(
            type = "boolean",
            description = "true: yeni fotoğraf çekmeden az önceki fotoğraf hakkında takip sorusu (son 2 dakika). " +
                "Kullanıcı aynı şey hakkında soruyorsa true, yeni bir şey gösteriyorsa false."
        )
    )
    override val required = listOf("question")
    override val thinkingPhrase = "Bakıyorum..."
    override val timeoutMs = 30_000L

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args.optString("question")?.trim().orEmpty().ifBlank { "Önümde ne var?" }
        val mode = args.optString("mode") ?: "describe"
        val reuse = args.has("use_last_photo") && runCatching { args.get("use_last_photo").asBoolean }.getOrDefault(false)

        var jpeg: ByteArray? = if (reuse) LastPhoto.get() else null
        var qualityNote = ""
        try {
            if (jpeg == null) {
                when (val shot = CameraCapture.capture(context)) {
                    is CameraCapture.Result.Failed -> return spoken(false, shot.spoken)
                    is CameraCapture.Result.Photo -> {
                        playEarcon?.invoke(Earcons.Sound.SHUTTER)
                        if (shot.quality.unusable) {
                            shot.jpeg.fill(0)
                            return spoken(
                                false,
                                "Görüntü tamamen karanlık. Kamerayı parmağın ya da bir şey kapatıyor olabilir, " +
                                    "ya da ortam çok karanlık. Telefonu biraz uzaklaştırıp tekrar dener misin?"
                            )
                        }
                        jpeg = shot.jpeg
                        LastPhoto.set(shot.jpeg)
                        qualityNote = when {
                            shot.quality.dark && shot.quality.blurry -> "\n(Not: Görüntü karanlık ve bulanık görünüyor.)"
                            shot.quality.dark -> "\n(Not: Görüntü karanlık görünüyor.)"
                            shot.quality.blurry -> "\n(Not: Görüntü bulanık görünüyor.)"
                            else -> ""
                        }
                    }
                }
            }
            val image = jpeg ?: return spoken(false, "Bakacak bir fotoğraf bulamadım, tekrar dener misin?")
            val result = deepSeek.describeImage(
                system = SYSTEM_PROMPT,
                question = "Görev: ${modeHint(mode)}\nKullanıcının sorusu: $question$qualityNote",
                jpeg = image
            )
            return result.fold(
                onSuccess = { spoken(true, it) },
                onFailure = { e ->
                    spoken(
                        false,
                        if (e is DeepSeekHttpException) "Fotoğrafı yorumlayamadım, servis şu an cevap vermiyor."
                        else if (e is IOException) "İnternete bağlanamadım, fotoğrafı yorumlayamadım."
                        else "Fotoğrafı yorumlayamadım."
                    )
                }
            )
        } finally {
            // The cached copy lives in LastPhoto; this one is wiped right after the request
            jpeg?.fill(0)
        }
    }

    private fun spoken(ok: Boolean, text: String) =
        ToolResult(success = ok, data = if (ok) text else "", error = if (ok) null else text, speakDirectly = true)

    private fun modeHint(mode: String) = when (mode) {
        "read_text" -> "Fotoğraftaki yazıyı oku."
        "money" -> "Fotoğraftaki parayı say: her banknot ve madeni paranın değerini ve toplamı söyle."
        else -> "Fotoğrafta ne olduğunu anlat."
    }

    companion object {
        // Tuned on Turkish labels and bills against deepseek-flash (thinking off, ~2 s)
        private val SYSTEM_PROMPT = """
Sen görme engelli bir kullanıcının gözüsün. Kullanıcı telefonunun arka kamerasıyla bir fotoğraf çekti ve sesli bir soru sordu. Cevabın sesli okunacak.

KURALLAR:
- Önce sorunun cevabını ver. Kısa, doğal Türkçe konuşma cümleleri kur. Madde işareti, yıldız, başlık, emoji kullanma.
- Sembol yerine okunuşunu yaz: "yüzde", "lira", "miligram".
- Tehlike varsa (merdiven, basamak, çukur, araç, sıcak yüzey, açık ocak, kesici alet) EN BAŞTA söyle.
- Yerleri kullanıcıya göre anlat: solunda, sağında, tam önünde, yakında, uzakta.
- Yazı okuma istenirse yazıyı olduğu gibi oku; uydurma, tamamlama. Uzunsa en önemli kısmı oku (ürün adı, tutar, son ödeme ya da son kullanma tarihi) ve "tamamını okumamı ister misin" diye sor.
- Tarihleri doğal oku: "03/2028" → "Mart 2028". Tutarları doğal oku: "742,35 TL" → "742 lira 35 kuruş".
- Para sorulursa Türk lirası banknot ve madeni paraları değerleriyle say ve toplamı söyle. Emin değilsen bunu açıkça söyle.
- İlaç kutusunda ilacın adını, miligramını ve son kullanma tarihini söyle; dozu sormak gerekirse kutuyu bir yakınına kontrol ettirmesini kısaca öner.
- İnsanları yüzünden tanımaya çalışma, kim olduklarını söyleme; sadece kaç kişi olduğunu ve ne yaptıklarını anlat.
- Görüntü çok karanlık, bulanık, parmakla kapalıysa veya yazı ya da nesne kadrajın bir kenarından taşıyorsa bunu MUTLAKA söyle: önce okuyabildiğini oku, sonra eksik kaldığını ve nasıl düzelteceğini tek cümleyle tarif et. Yazının devamı sağ kenarda kesiliyorsa "telefonu biraz sağa çevir", sol kenardaysa "biraz sola çevir", üstteyse "biraz yukarı kaldır", altta ise "biraz aşağı indir", hem sağ hem sol kesikse "telefonu biraz uzaklaştır", çok karanlıksa "ışığı açmayı dene" de. Görmediğin şeyi asla tahmin edip kesinmiş gibi söyleme.
- Toplam cevap normalde bir iki cümle; yazı okuma veya sorulan detay gerektiriyorsa daha uzun olabilir.
        """.trimIndent()
    }
}
