package clipfactory.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import clipfactory.app.JobState
import clipfactory.app.ProjectStore
import kotlinx.coroutines.flow.StateFlow

sealed class Screen {
    data object Home : Screen()
    data class NewClip(val uri: Uri, val name: String, val more: List<Pair<Uri, String>> = emptyList()) : Screen()
    data class Processing(val projectId: String?) : Screen()
    data class Choose(val projectId: String) : Screen()
    data class Results(val projectId: String, val start: Int = 0, val openPost: Boolean = false,
                       val group: List<String>? = null) : Screen()
    data class Editor(val projectId: String, val index: Int) : Screen()
    data object Settings : Screen()
    data object Discover : Screen()
    data object Live : Screen()
    data object Diagnostics : Screen()
}

/** The whole app: a small screen stack, driven also by the background work (analysis done -> choose/results). */
@Composable
fun App(incoming: StateFlow<Uri?>, consumeIncoming: () -> Unit, nameOf: (Uri) -> String, initial: List<Screen> = listOf(Screen.Home)) {
    val ctx = LocalContext.current
    var stack by remember { mutableStateOf(initial) }
    fun go(s: Screen) { stack = stack + s }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun home() { stack = listOf(Screen.Home) }

    LaunchedEffect(Unit) { ProjectStore.load(ctx) }
    val pending by incoming.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { go(Screen.NewClip(it, nameOf(it))); consumeIncoming() }
    }
    // when the background work finishes, move on from the Processing screen
    val work by JobState.flow.collectAsState()
    val projects by ProjectStore.projects.collectAsState()
    LaunchedEffect(work.phase, work.projectId, projects) {
        val top = stack.last()
        if (top is Screen.Processing && work.phase == "idle" && work.projectId != null) {
            val p = projects.firstOrNull { it.id == work.projectId } ?: return@LaunchedEffect
            when {
                p.status == "choosing" -> replace(Screen.Choose(p.id))
                p.doneClips.isNotEmpty() -> replace(Screen.Results(p.id))
            }
        }
        if (top is Screen.Processing && top.projectId == null && work.projectId != null) replace(Screen.Processing(work.projectId))
    }

    BackHandler(stack.size > 1) { back() }
    Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        AnimatedContent(stack.last(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
            when (s) {
                Screen.Home -> HomeScreen(
                    onNew = { uris -> go(Screen.NewClip(uris[0], nameOf(uris[0]), uris.drop(1).map { it to nameOf(it) })) },
                    onOpen = { p -> go(if (p.status == "choosing") Screen.Choose(p.id) else if (p.status in setOf("analyzing", "rendering")) Screen.Processing(p.id) else Screen.Results(p.id)) },
                    onSettings = { go(Screen.Settings) },
                    onWork = { go(Screen.Processing(work.projectId)) },
                    onDiscover = { go(Screen.Discover) },
                    onLive = { go(Screen.Live) },
                    onReview = { ids -> if (ids.isNotEmpty()) go(Screen.Results(ids[0], group = ids)) },
                )
                is Screen.NewClip -> NewClipScreen(s.uri, s.name, s.more, onBack = ::back, onStarted = { replace(Screen.Processing(null)) })
                is Screen.Processing -> ProcessingScreen(s.projectId, onBack = ::home)
                is Screen.Choose -> ChooseScreen(s.projectId, onBack = ::home, onStarted = { replace(Screen.Processing(s.projectId)) })
                is Screen.Results -> ResultsScreen(s.projectId, s.start, onBack = ::home, onEdit = { id, i -> go(Screen.Editor(id, i)) },
                    openPost = s.openPost, group = s.group ?: listOf(s.projectId))
                is Screen.Editor -> EditorScreen(s.projectId, s.index, onBack = ::back, onApplied = { stack = stack.dropLast(2) + Screen.Processing(s.projectId) })
                Screen.Settings -> SettingsScreen(onBack = ::back, onDiagnostics = { go(Screen.Diagnostics) })
                Screen.Discover -> DiscoverScreen(onBack = ::back, onSettings = { go(Screen.Settings) })
                Screen.Live -> LiveScreen(onBack = ::back)
                Screen.Diagnostics -> DiagnosticsScreen(onBack = ::back)
            }
        }
    }
}
