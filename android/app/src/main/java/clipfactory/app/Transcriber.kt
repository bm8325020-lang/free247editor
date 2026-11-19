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

    /** Transcribe audio (WAV or .m4a) of the range starting at [offsetS]; returns words in absolute time. */
    fun words(wav: ByteArray, offsetS: Double, m4a: Boolean = false): List<Word> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", if (m4a) "audio.m4a" else "audio.wav",
                wav.toRequestBody((if (m4a) "audio/mp4" else "audio/wav").toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        var waited = 0L
        while (true) {
            val r = http.newCall(req).execute()
            // free-tier limit (audio per hour): wait as Groq says, up to 20 minutes in total, instead of failing
            if (r.code == 429 && waited < 20 * 60) {
                val wait = (r.header("retry-after")?.toDoubleOrNull()?.toLong() ?: 60L).coerceIn(5L, 300L)
                r.close()
                Thread.sleep(wait * 1000)
                waited += wait
                continue
            }
            return parse(r, offsetS)
        }
    }

    private fun parse(resp: okhttp3.Response, offsetS: Double): List<Word> {
        resp.use { r ->
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
