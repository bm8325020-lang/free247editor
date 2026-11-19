package clipfactory.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import clipfactory.app.LiveCaptureService
import clipfactory.app.Prefs

/** "Clipar live": the user watches a Twitch live in the Twitch app; this app records it in the background and clips. */
@Composable
fun LiveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("live", 0) }
    var channel by remember { mutableStateOf(sp.getString("channel", "") ?: "") }
    var auth by remember { mutableStateOf(sp.getString("auth", "") ?: "") }
    var opt by remember { mutableStateOf(Prefs.options(ctx)) }
    val st by LiveCaptureService.flow.collectAsState()
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }

    // re-check the overlay permission when the user comes back from the system settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) overlayOk = Settings.canDrawOverlays(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun openTwitch(name: String) {
        if (clipfactory.app.BuildConfig.DEBUG && name == "localtest") return // emulator test: nothing to open
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("twitch://stream/$name")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(app) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/$name")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val name = channel.trim().removePrefix("@").lowercase()
            LiveCaptureService.start(ctx, r.resultCode, data, name)
            openTwitch(name)
        } else error = "Sem a permissão de gravar a tela o app não consegue clipar a live."
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // with or without it (without: clips have no sound), go on to the screen-capture prompt
        capture.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Clipar live", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (st.running) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔴 Clipando @${st.channel}", style = MaterialTheme.typography.titleLarge, color = C.text)
                        Text(st.status, style = MaterialTheme.typography.bodyMedium, color = C.muted)
                        Text("${st.msgsPerMin} mensagens/min no chat · ${st.clips} clipe(s)", style = MaterialTheme.typography.bodyMedium, color = C.text)
                        st.warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = C.bad) }
                        BigButton("Clipar agora", icon = Icons.Filled.ContentCut) { LiveCaptureService.clip(ctx) }
                        SoftButton("Voltar para a live", Modifier.fillMaxWidth()) { openTwitch(st.channel) }
                        SoftButton("Parar", Modifier.fillMaxWidth(), icon = Icons.Filled.Stop) { LiveCaptureService.stop(ctx) }
                        Text("Os clipes aparecem na tela inicial como projetos e na galeria (Movies/Clipes).",
                            style = MaterialTheme.typography.labelSmall, color = C.muted)
                    }
                }
            } else {
                if (st.status.isNotBlank()) Text(st.status, color = C.muted, style = MaterialTheme.typography.bodyMedium)
                st.warning?.let { Text(it, color = C.bad, style = MaterialTheme.typography.bodyMedium) }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Qual live?", "O nome do canal na Twitch (o que vem depois de twitch.tv/).")
                        OutlinedTextField(channel, { channel = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("ex.: paulinholoko", color = C.muted) }, colors = fieldColors())
                        SectionTitle("De onde vem a autorização", "Campanha de clipping, regra publicada pelo streamer ou acordo.")
                        OutlinedTextField(auth, { auth = it.take(120) }, Modifier.fillMaxWidth(), colors = fieldColors())
                        SectionTitle("Quão exigente")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Mais clipes", "Equilibrado", "Só os insanos").forEachIndexed { i, l ->
                                Chip(l, opt.liveLevel == i) { opt = opt.copy(liveLevel = i); Prefs.save(ctx, opt) }
                            }
                        }
                    }
                }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Botão flutuante", if (overlayOk) "Liberado: o ✂ aparece por cima da Twitch."
                            else "Opcional: um botão ✂ por cima da Twitch. Sem ele, use a notificação.")
                        if (!overlayOk) SoftButton("Permitir aparecer sobre outros apps", Modifier.fillMaxWidth()) {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
                        }
                    }
                }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle("Como funciona")
                        listOf(
                            "1. O Android pergunta se pode gravar a tela: escolha a tela inteira (ou o app da Twitch).",
                            "2. A Twitch abre na live. Deixe em tela cheia, com o celular deitado, e a tela ligada.",
                            "3. O app guarda os últimos 3 minutos e lê o chat. Nos primeiros 2 minutos ele aprende o ritmo da live.",
                            "4. Quando o chat ou o som explodem, ele corta sozinho. Viu algo bom? Toque no ✂.",
                            "5. Cada clipe é montado com legenda e webcam no canto e aparece na tela inicial.",
                        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = C.muted) }
                        Text("Gasta bateria e esquenta: deixe carregando. Notificações e outros apps abertos também " +
                            "aparecem na gravação.", style = MaterialTheme.typography.labelSmall, color = C.bad)
                    }
                }
                error?.let { Text(it, color = C.bad, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (!st.running) BigButton("Começar a clipar", Modifier.padding(16.dp),
            enabled = channel.isNotBlank() && auth.isNotBlank(), icon = Icons.Filled.PlayArrow) {
            error = null
            sp.edit().putString("channel", channel).putString("auth", auth).apply()
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
    focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent)
