package clipfactory.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import clipfactory.app.AnalyzeQueue
import clipfactory.app.BuildConfig
import clipfactory.app.JobState
import clipfactory.app.KeyCheck
import clipfactory.app.Prefs
import clipfactory.app.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Level { OK, WARN, BAD }

private data class Check(val title: String, val level: Level, val detail: String, val action: Pair<String, () -> Unit>? = null)

/** One place that says what works, what is limited and what is missing, with the fix for each. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val work by JobState.flow.collectAsState()
    val projects by ProjectStore.projects.collectAsState()
    var keys by remember { mutableStateOf<List<Check>?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun open(intent: Intent) { runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val appSettings: () -> Unit = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }

    val local = remember(tick, work, projects) {
        val l = ArrayList<Check>()
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        l += if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            Check("Notificações", Level.OK, "Você é avisado quando os clipes ficam prontos.")
        else Check("Notificações", Level.WARN, "Desligadas: o progresso e o aviso de pronto não aparecem.", "Permitir" to appSettings)
        l += if (granted(Manifest.permission.RECORD_AUDIO)) Check("Som da live", Level.OK, "Clipar live pode gravar o som do celular.")
        else Check("Som da live", Level.WARN, "Sem permissão: em Clipar live os clipes saem sem som.", "Permitir" to appSettings)
        l += if (Settings.canDrawOverlays(ctx)) Check("Botão flutuante", Level.OK, "O ✂ aparece por cima da Twitch.")
        else Check("Botão flutuante", Level.WARN, "Sem permissão: use a notificação para cortar.",
            "Permitir" to { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) })
        val pm = ctx.getSystemService(PowerManager::class.java)
        l += if (pm.isIgnoringBatteryOptimizations(ctx.packageName))
            Check("Economia de bateria", Level.OK, "O Android não corta o trabalho longo em segundo plano.")
        else Check("Economia de bateria", Level.WARN, "Ativa para este app: em vídeos longos o Android pode pausar o trabalho. " +
            "Escolha 'Sem restrições' para a Fábrica de Clipes.", "Abrir" to { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        val free = StatFs(ctx.filesDir.path).availableBytes / 1e9
        l += when {
            free < 1 -> Check("Espaço livre", Level.BAD, "%.1f GB: pouco demais para montar clipes. Apague vídeos antigos.".format(free))
            free < 4 -> Check("Espaço livre", Level.WARN, "%.1f GB: dá, mas lives longas e Clipar live ocupam espaço.".format(free))
            else -> Check("Espaço livre", Level.OK, "%.1f GB livres.".format(free))
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { it.supportedTypes.toList() }
        val h264 = MediaFormat.MIMETYPE_VIDEO_AVC in codecs
        val aac = MediaFormat.MIMETYPE_AUDIO_AAC in codecs
        l += if (h264 && aac) Check("Codificadores de vídeo e som", Level.OK, "H.264 e AAC disponíveis.")
        else Check("Codificadores de vídeo e som", Level.BAD, "Faltando: ${listOfNotNull("H.264".takeIf { !h264 }, "AAC".takeIf { !aac }).joinToString()}. Este celular não consegue montar clipes.")
        val q = AnalyzeQueue.size(ctx)
        l += Check("Trabalho agora", if (work.error != null) Level.WARN else Level.OK,
            (if (work.phase == "idle") "Parado." else "${work.stage} (${(work.progress * 100).toInt()}%).") +
                (if (q > 0) " $q vídeo(s) na fila." else "") + (work.error?.let { " Último erro: $it" } ?: ""))
        projects.firstOrNull { p -> p.notes.any { it.startsWith("Erro:") } }?.let { p ->
            l += Check("Último projeto com erro", Level.WARN, "${p.name}: ${p.notes.first { it.startsWith("Erro:") }.take(200)}")
        }
        l += Check("Versão", Level.OK, "Fábrica de Clipes ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
        l
    }

    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        val o = Prefs.options(ctx)
        keys = withContext(Dispatchers.IO) {
            fun k(name: String, key: String, test: (String) -> KeyCheck.Result, optional: String): Check =
                if (key.isBlank()) Check(name, Level.WARN, "Sem chave: $optional")
                else test(key).let { Check(name, if (it.ok) Level.OK else Level.BAD, it.message) }
            listOf(
                k("Groq (legendas)", o.groqKey, KeyCheck::groq, "os clipes saem sem legenda e o app não entende a fala."),
                k("Coarena (IA grátis)", o.coarenaKey, KeyCheck::coarena, "o seletor automático escolhe e dá os títulos."),
                k("YouTube (Descobrir)", o.youtubeKey, KeyCheck::youtube, "a aba Descobrir não funciona."),
                k("Anthropic (IA paga)", o.claudeKey, KeyCheck::anthropic, "opcional; a Coarena já faz isso de graça."),
            )
        }
        testing = false
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Diagnóstico", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Celular e permissões")
            local.forEach { CheckRow(it) }
            SectionTitle("Chaves", "O teste usa a internet e mostra o que cada serviço respondeu.")
            if (keys == null && !testing) SoftButton("Testar as chaves", Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh) { testing = true }
            if (testing) Text("Testando…", color = C.accent, style = MaterialTheme.typography.bodyMedium)
            keys?.forEach { CheckRow(it) }
            if (keys != null && !testing) SoftButton("Testar de novo", Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh) { testing = true }
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    Card {
        Row(Modifier.padding(14.dp)) {
            Text(when (c.level) { Level.OK -> "✅"; Level.WARN -> "⚠️"; Level.BAD -> "❌" }, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, color = C.text)
                Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = C.muted)
                c.action?.let { (label, act) -> SoftButton(label, Modifier.width(160.dp)) { act() } }
            }
        }
    }
}
