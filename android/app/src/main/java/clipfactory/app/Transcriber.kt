package clipfactory.app

import clipfactory.core.Word
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Word timestamps from Groq's free Whisper API (key from console.groq.com). */
class Transcriber(private val key: String, private val language: String?) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    var detectedLanguage: String? = null
        private set

    /** Transcribe a WAV of the range starting at [offsetS]; returns words in absolute time. */
    fun words(wav: ByteArray, offsetS: Double): List<Word> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("Groq HTTP ${r.code}: ${text.take(200)}")
            val js = JSONObject(text)
            if (detectedLanguage == null) detectedLanguage = js.optString("language").ifBlank { null }
            val arr = js.optJSONArray("words") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val w = arr.getJSONObject(i)
                val t = w.optString("word").trim()
                val s = w.optDouble("start")
                val e = w.optDouble("end")
                if (t.isEmpty() || e < s) null else Word(t, s + offsetS, e + offsetS)
            }
        }
    }
}
