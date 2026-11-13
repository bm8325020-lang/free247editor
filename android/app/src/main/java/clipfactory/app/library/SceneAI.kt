package clipfactory.app.library

import android.util.Base64
import android.util.Log
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import org.json.JSONObject
import java.io.File

/** Claude describes what happens in each scene (so any search works) and runs the "smart" search. */
object SceneAI {
    private const val MODEL = "claude-opus-5-5"

    private fun client(key: String) = AnthropicOkHttpClient.builder().apiKey(key.trim()).build()

    private fun schema(props: Map<String, Any>, required: List<String>) = JsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .putAdditionalProperty("required", JsonValue.from(required))
        .putAdditionalProperty("properties", JsonValue.from(props))
        .build()

    private val describeSchema = schema(
        mapOf("scenes" to mapOf("type" to "array", "items" to mapOf(
            "type" to "object", "additionalProperties" to false, "required" to listOf("n", "description"),
            "properties" to mapOf("n" to mapOf("type" to "integer"), "description" to mapOf("type" to "string")),
        ))),
        listOf("scenes"),
    )

    /**
     * Describe a batch of scenes (one thumbnail each). context: the video title (e.g. "Gojo Satoru scene pack").
     * Returns scene index -> description (Portuguese, with English keywords so both languages search).
     */
    fun describe(key: String, context: String, batch: List<Pair<Int, File>>): Map<Int, String> {
        if (batch.isEmpty()) return emptyMap()
        val blocks = ArrayList<ContentBlockParam>()
        blocks.add(ContentBlockParam.ofText("""These are frames from a scene pack video titled "$context", used by
people who make edits. Each image is one scene, labelled with its number. For each scene write one line in Brazilian
Portuguese that someone would search for: who is on screen (use the character names you recognise; the title tells
you the series), what they do, their expression or emotion, the shot (close, plano aberto, câmera lenta...), the
setting, any striking visual (explosão, olhos brilhando, chuva...). Then add " | " and the same key words in
English. Describe only what you see; if you are not sure who someone is, say what they look like. Keep it under 40 words."""))
        for ((n, f) in batch) {
            blocks.add(ContentBlockParam.ofText("Cena $n:"))
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder().source(
                Base64ImageSource.builder().data(b64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
            ).build()))
        }
        val params = MessageCreateParams.builder().model(MODEL).maxTokens(16000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder().schema(describeSchema).build()).build())
            .addUserMessageOfContentBlockParams(blocks)
            .build()
        val msg = client(key).messages().create(params)
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) return emptyMap()
        val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val arr = JSONObject(text).getJSONArray("scenes")
        return (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getInt("n") to o.getString("description") } }
    }

    private val searchSchema = schema(
        mapOf("ids" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
        listOf("ids"),
    )

    /**
     * Smart search: Claude reads the whole index and returns the scene ids that match what the user typed, best
     * first. The index is marked for prompt caching, so repeated searches over the same library cost little.
     */
    fun search(key: String, query: String, index: List<Pair<String, String>>): List<String>? {
        if (index.isEmpty()) return emptyList()
        return try {
            val listing = index.joinToString("\n") { (id, text) -> "[$id] $text" }
            val params = MessageCreateParams.builder().model(MODEL).maxTokens(4000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(searchSchema).build()).build())
                .addUserMessageOfContentBlockParams(listOf(
                    ContentBlockParam.ofText(TextBlockParam.builder().text(
                        "Scene library of someone who makes edits. One scene per line: [id] description | speech.\n$listing"
                    ).cacheControl(CacheControlEphemeral.builder().build()).build()),
                    ContentBlockParam.ofText("""Find the scenes that match this search, best match first, at most 40
ids. Understand intent, synonyms, Portuguese and English, slang and typos (e.g. "gojo tirando a venda" matches a
scene where he removes his blindfold; "fala que é fraco" matches speech saying someone is weak). Return only ids
from the list; an empty list if nothing fits.
Search: $query"""),
                ))
                .build()
            val msg = client(key).messages().create(params)
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) return null
            val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            val arr = JSONObject(text).getJSONArray("ids")
            val known = index.map { it.first }.toSet()
            (0 until arr.length()).map { arr.getString(it) }.filter { it in known }
        } catch (e: Exception) {
            Log.w("SceneAI", "smart search failed", e)
            null
        }
    }

    private val webSchema = schema(mapOf("query" to mapOf("type" to "string"), "guess" to mapOf("type" to "string")),
        listOf("query", "guess"))

    /**
     * The user describes a moment ("o cara que tinha tudo... aquela droga de calcanhar"). Claude says where it is
     * from (guess, in Portuguese) and writes a short YouTube search for that scene. Null if unavailable.
     */
    fun webQuery(key: String, description: String): Pair<String, String>? = try {
        val params = MessageCreateParams.builder().model(MODEL).maxTokens(2000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder().schema(webSchema).build()).build())
            .addUserMessage("""Someone who makes video edits wants this scene but does not know where it is from.
Identify the film, series or anime and the moment if you can. Return "guess": one short sentence in Brazilian
Portuguese saying what you think it is (or that you are not sure), and "query": a short YouTube search (5-8 words,
the language most likely to find the clip; add "scene" or "cena") to find that exact moment.
Description: $description""")
            .build()
        val msg = client(key).messages().create(params)
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) null
        else {
            val o = JSONObject(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
            o.getString("query") to o.getString("guess")
        }
    } catch (e: Exception) {
        Log.w("SceneAI", "web query failed", e)
        null
    }
}
