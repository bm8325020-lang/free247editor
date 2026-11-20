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
            .addFormDataPart("timestamp_granularities[]", "segment")
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
            val drop = gameDialogue(js.optJSONArray("segments"))
            return (0 until arr.length()).mapNotNull { i ->
                val w = arr.getJSONObject(i)
                val t = w.optString("word").trim()
                val s = w.optDouble("start")
                val e = w.optDouble("end")
                if (t.isEmpty() || e < s || drop.any { (a, b) -> s >= a && s < b }) null else Word(t, s + offsetS, e + offsetS)
            }
        }
    }

    /**
     * Stretches spoken by in-game characters (English, with the game's own subtitles on screen) while the streamer
     * speaks Portuguese: left out of the captions, which carry only the streamer. Same rule as the server bot.
     */
    private fun gameDialogue(segs: org.json.JSONArray?): List<Pair<Double, Double>> {
        if (segs == null) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        var ptTotal = 0
        for (i in 0 until segs.length()) {
            val o = segs.getJSONObject(i)
            val toks = o.optString("text").lowercase().split(Regex("\\s+")).map { it.trim('.', ',', '!', '?', '"', '\'', '…') }
            val en = toks.count { it in EN }
            val pt = toks.count { it in PT || it.startsWith("kkk") }
            ptTotal += pt
            if (en >= 2 && en > 1.5 * pt) out.add(o.optDouble("start") - 0.05 to o.optDouble("end") + 0.05)
        }
        return if (ptTotal < 3) emptyList() else out // an English-speaking streamer: keep every caption
    }

    private companion object {
        val EN = ("the you and is it what i my don't that this are be get go we your of on for just right come here yeah " +
            "hey gonna wanna know got can all out up now let's i'm you're it's what's he she they with have was will not did " +
            "there where why how who fuck shit damn please sir think want need look sorry thank thanks guy fucking").split(" ").toSet()
        val PT = ("o os as de que e não nao é um uma eu você voce mano pra para tá ta isso ele ela com na da dos das em meu " +
            "minha vai vou aqui aí ai cara mas se já ja tô muito tem só então entao porque nossa caralho porra véi velho irmão " +
            "irmao gente bora olha ó tipo foi tava essa esse lá la vamo vamos ver quero sei pô po né ne mesmo agora").split(" ").toSet()
    }
}
