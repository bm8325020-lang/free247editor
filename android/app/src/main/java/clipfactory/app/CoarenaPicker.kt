package clipfactory.app

import android.util.Log
import clipfactory.core.Clip
import clipfactory.core.Word
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Free alternative to ClaudePicker: Coarena (coarena.ai) gives free daily access to a few models through an
 * OpenAI-compatible API. Same prompt and answer format as ClaudePicker; tries the next free model on failure.
 */
object CoarenaPicker {
    private const val URL = "https://coarena.ai/api/v1/chat/completions"
    // the free-tier models (Nov 2026), best first; all three picked well in a test with real candidates
    val MODELS = listOf("gpt-6-luna", "deepseek-v4-1-flash-1017", "gemini-3-8-flash")
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    fun pick(key: String, cands: List<Clip>, words: List<Word>, count: Int, language: String?): List<Clip>? {
        if (key.isBlank() || cands.isEmpty()) return null
        val prompt = ClaudePicker.prompt(cands, words, count, language) +
            "\n\nAnswer with ONLY a JSON object, no other text: " +
            "{\"clips\":[{\"id\":0,\"title\":\"...\",\"reason\":\"...\",\"virality\":90}]}"
        for (model in MODELS) {
            try {
                val text = ask(key.trim(), model, prompt)
                val json = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: continue
                ClaudePicker.parse(json, cands, count, "coarena")?.let { return it }
            } catch (e: Exception) {
                Log.w("CoarenaPicker", "$model failed: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun ask(key: String, model: String, prompt: String): String {
        val body = JSONObject().put("model", model).put("max_tokens", 4000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder().url(URL).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }
}
