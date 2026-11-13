package clipfactory.app

import android.util.Log
import clipfactory.core.Clip
import clipfactory.core.Word
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject

/** Optional: Claude makes the final pick among the candidate moments and writes the hook titles. */
object ClaudePicker {
    private const val MODEL = "claude-opus-5-5"

    private fun hms(t: Double) = "%d:%02d:%02d".format((t / 3600).toInt(), (t % 3600 / 60).toInt(), (t % 60).toInt())

    private val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .putAdditionalProperty("required", JsonValue.from(listOf("clips")))
        .putAdditionalProperty(
            "properties", JsonValue.from(
                mapOf(
                    "clips" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "additionalProperties" to false,
                            "required" to listOf("id", "title", "reason", "virality"),
                            "properties" to mapOf(
                                "id" to mapOf("type" to "integer"),
                                "title" to mapOf("type" to "string"),
                                "reason" to mapOf("type" to "string"),
                                "virality" to mapOf("type" to "integer"),
                            ),
                        ),
                    ),
                )
            )
        )
        .build()

    /** Returns Claude's picks (best first) or null if unavailable, so the built-in scorer takes over. */
    fun pick(key: String, cands: List<Clip>, words: List<Word>, count: Int, language: String?): List<Clip>? {
        if (key.isBlank() || cands.isEmpty()) return null
        val blocks = cands.mapIndexed { i, c ->
            val said = words.filter { it.start >= c.start && it.start < c.end }.joinToString(" ") { it.text }
                .ifBlank { "(no speech, game audio only)" }
            "[$i] ${hms(c.start)}-${hms(c.end)}  audio_spike=%.1f  hype_words=%.1f\n    $said".format(c.audio, c.hype)
        }
        val prompt = """You are the clipper for a gaming streamer. These are candidate moments from a stream or gameplay
video, found by audio spikes (screams, laughs, explosions) and hype words. For each you get the time range, how hard
the audio spiked, a hype-word score, and what the streamer said.

Pick up to $count moments most likely to go viral as vertical clips (TikTok, Reels, Shorts): big reactions,
clutch plays, fails, funny moments, rage, jump scares, wholesome or absurd moments. Skip moments that only make
sense with context or where nothing happens. Fewer picks are better than weak picks: return only moments that
genuinely work on their own. Order best first.

For each pick give: id, a title for the on-screen hook (max 6 words, in the streamer's language
(${language ?: "same as transcript"}), punchy, like a clip title on Twitch or TikTok, no hashtags, no emoji),
reason (one short sentence), virality 1-100.

Candidates:
""" + blocks.joinToString("\n")
        return try {
            val client = AnthropicOkHttpClient.builder().apiKey(key).build()
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .format(JsonOutputFormat.builder().schema(schema).build())
                        .build()
                )
                .addUserMessage(prompt)
                .build()
            val msg = client.messages().create(params)
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) return null
            val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
            val arr = JSONObject(text).getJSONArray("clips")
            val used = HashSet<Int>()
            val out = ArrayList<Clip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                if (id !in cands.indices || !used.add(id)) continue
                val c = cands[id].copy()
                c.title = o.getString("title").trim()
                c.hook = c.title
                c.reason = o.getString("reason").trim()
                c.score = o.getInt("virality").coerceIn(1, 100)
                c.picker = "claude"
                out.add(c)
                if (out.size >= count) break
            }
            out.ifEmpty { null }
        } catch (e: Exception) {
            Log.w("ClaudePicker", "Claude unavailable, using the built-in scorer", e)
            null
        }
    }
}
