package clipfactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import clipfactory.app.CaptionStyles
import clipfactory.app.KeyCheck
import clipfactory.app.Options
import clipfactory.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onDiagnostics: () -> Unit = {}) {
    val ctx = LocalContext.current
    var opt by remember { mutableStateOf(Prefs.options(ctx)) }
    fun set(o: Options) { opt = o; Prefs.save(ctx, o) }
    val samples = rememberStyleSamples()
    val scope = rememberCoroutineScope()
    var groqMsg by remember { mutableStateOf<KeyCheck.Result?>(null) }
    var claudeMsg by remember { mutableStateOf<KeyCheck.Result?>(null) }
    var coarenaMsg by remember { mutableStateOf<KeyCheck.Result?>(null) }
    var ytMsg by remember { mutableStateOf<KeyCheck.Result?>(null) }
    var srvMsg by remember { mutableStateOf<clipfactory.app.ServerSync.Result?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Configurações", onBack) {
            androidx.compose.material3.TextButton(onClick = onDiagnostics) { Text("Diagnóstico", color = C.accent) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Chaves (opcionais)", "Ficam criptografadas só neste celular.")
                    Field("Chave do Groq", opt.groqKey, secret = true, hint = "Legendas. Grátis em console.groq.com") { set(opt.copy(groqKey = it)) }
                    SoftButton("Testar Groq", icon = Icons.Filled.Wifi) {
                        groqMsg = KeyCheck.Result(true, "Testando…")
                        scope.launch { groqMsg = withContext(Dispatchers.IO) { KeyCheck.groq(opt.groqKey) } }
                    }
                    groqMsg?.let { Text(it.message, color = if (it.ok) C.good else C.bad, style = MaterialTheme.typography.bodyMedium) }
                    Field("Chave do YouTube (grátis)", opt.youtubeKey, secret = true,
                        hint = "YouTube Data API v3, para a aba Descobrir. Começa com AIza") { set(opt.copy(youtubeKey = it)) }
                    SoftButton("Testar YouTube", icon = Icons.Filled.Wifi) {
                        ytMsg = KeyCheck.Result(true, "Testando…")
                        scope.launch { ytMsg = withContext(Dispatchers.IO) { KeyCheck.youtube(opt.youtubeKey) } }
                    }
                    ytMsg?.let { Text(it.message, color = if (it.ok) C.good else C.bad, style = MaterialTheme.typography.bodyMedium) }
                    Field("Chave da Coarena (grátis)", opt.coarenaKey, secret = true,
                        hint = "IA grátis que escolhe os momentos e escreve os títulos. coarena.ai") { set(opt.copy(coarenaKey = it)) }
                    SoftButton("Testar Coarena", icon = Icons.Filled.Wifi) {
                        coarenaMsg = KeyCheck.Result(true, "Testando…")
                        scope.launch { coarenaMsg = withContext(Dispatchers.IO) { KeyCheck.coarena(opt.coarenaKey) } }
                    }
                    coarenaMsg?.let { Text(it.message, color = if (it.ok) C.good else C.bad, style = MaterialTheme.typography.bodyMedium) }
                    Field("Chave da Anthropic", opt.claudeKey, secret = true, hint = "Paga. Se tiver as duas, usa esta primeiro") { set(opt.copy(claudeKey = it)) }
                    SoftButton("Testar Anthropic", icon = Icons.Filled.Wifi) {
                        claudeMsg = KeyCheck.Result(true, "Testando…")
                        scope.launch { claudeMsg = withContext(Dispatchers.IO) { KeyCheck.anthropic(opt.claudeKey) } }
                    }
                    claudeMsg?.let { Text(it.message, color = if (it.ok) C.good else C.bad, style = MaterialTheme.typography.bodyMedium) }
                    Field("Idioma da fala", opt.language, hint = "Vazio = detectar sozinho. Ex.: pt") { set(opt.copy(language = it)) }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Servidor do bot de lives", "Os clipes que o bot faz na Oracle chegam aqui sozinhos (a cada 15 min).")
                    Field("Endereço (IP público da Oracle)", opt.serverAddr, hint = "ex.: 129.146.10.20 (a porta 8080 é automática)") {
                        set(opt.copy(serverAddr = it))
                    }
                    Field("Código (APP_CODE do script)", opt.serverCode, secret = true) { set(opt.copy(serverCode = it)) }
                    SoftButton("Conectar e buscar agora", icon = Icons.Filled.Wifi) {
                        srvMsg = clipfactory.app.ServerSync.Result(true, "Buscando…")
                        clipfactory.app.ServerSync.schedule(ctx)
                        scope.launch { srvMsg = withContext(Dispatchers.IO) { clipfactory.app.ServerSync.sync(ctx) } }
                    }
                    srvMsg?.let { Text(it.message, color = if (it.ok) C.good else C.bad, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Postagem", "Vai na legenda copiada ao postar.")
                    Field("Créditos (ex.: @paulinholoko)", opt.credit) { set(opt.copy(credit = it)) }
                    Field("Hashtags", opt.hashtags) { set(opt.copy(hashtags = it)) }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Padrões de um clipe novo")
                    Text("Até ${opt.clips} clipes por vídeo", color = C.text, style = MaterialTheme.typography.bodyMedium)
                    Slider(opt.clips.toFloat(), { set(opt.copy(clips = it.toInt())) }, valueRange = 1f..15f, steps = 13, colors = sliderColors())
                    Text("Até ${opt.maxSeconds} s cada", color = C.text, style = MaterialTheme.typography.bodyMedium)
                    Slider(opt.maxSeconds.toFloat(), { set(opt.copy(maxSeconds = (it / 5).toInt() * 5)) }, valueRange = 20f..60f, colors = sliderColors())
                    Toggle("Escolher os momentos antes de montar", opt.chooseMoments) { set(opt.copy(chooseMoments = it)) }
                    Toggle("Legendas", opt.captions) { set(opt.copy(captions = it)) }
                    Toggle("Título no começo do clipe", opt.hook) { set(opt.copy(hook = it)) }
                    Text("Estilo da legenda", color = C.text, style = MaterialTheme.typography.bodyMedium)
                    StylePicker(opt.captionStyle, samples) { set(opt.copy(captionStyle = it)) }
                    Text("Layout", color = C.text, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LAYOUTS.forEach { (k, label) -> Chip(label, opt.layout == k) { set(opt.copy(layout = k)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.surface2)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.onAccent))
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        supportingText = hint?.let { { Text(it, color = C.muted) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
            focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent,
            focusedLabelColor = C.accent, unfocusedLabelColor = C.muted))
}
