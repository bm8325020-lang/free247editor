package clipfactory.app

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real connection checks for the optional API keys. Never logs or shows the key itself. */
object KeyCheck {
    data class Result(val ok: Boolean, val message: String)

    fun groq(key: String): Result {
        if (key.isBlank()) return Result(false, "Sem chave: os clipes saem sem legenda e escolhidos só pelo áudio.")
        if (!key.trim().startsWith("gsk_")) return Result(false, "Isso não parece uma chave do Groq (começa com gsk_).")
        val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer ${key.trim()}").build()
        return try {
            http.newCall(req).execute().use { r ->
                when (r.code) {
                    200 -> Result(true, "Groq funcionando: transcrição e legendas ativas.")
                    401, 403 -> Result(false, "Chave do Groq recusada (HTTP ${r.code}). Gere outra em console.groq.com/keys.")
                    429 -> Result(true, "Chave válida, mas o limite gratuito está esgotado agora (HTTP 429). Tente mais tarde.")
                    else -> Result(false, "Groq respondeu HTTP ${r.code}. Tente de novo mais tarde.")
                }
            }
        } catch (e: IOException) {
            Result(false, "Sem conexão com o Groq (${e.javaClass.simpleName}). Verifique a internet.")
        }
    }

    fun anthropic(key: String): Result {
        if (key.isBlank()) return Result(false, "Sem chave: o seletor automático escolhe os momentos.")
        if (!key.trim().startsWith("sk-ant-")) return Result(false, "Isso não parece uma chave da Anthropic (começa com sk-ant-).")
        return try {
            AnthropicOkHttpClient.builder().apiKey(key.trim()).maxRetries(0).build().models().retrieve("claude-opus-5-5")
            Result(true, "Claude funcionando: ele escolhe os momentos e escreve os títulos (pago por uso).")
        } catch (e: UnauthorizedException) {
            Result(false, "Chave da Anthropic recusada (401). Confira em platform.claude.com.")
        } catch (e: PermissionDeniedException) {
            Result(false, "A chave não tem acesso a esse modelo (403).")
        } catch (e: RateLimitException) {
            Result(true, "Chave válida, mas com limite de uso atingido agora (429).")
        } catch (e: AnthropicIoException) {
            Result(false, "Sem conexão com a Anthropic. Verifique a internet.")
        } catch (e: Exception) {
            Result(false, "Erro ao testar: ${e.javaClass.simpleName}.")
        }
    }
}
