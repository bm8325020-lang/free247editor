package clipfactory.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import clipfactory.app.ui.App
import clipfactory.app.ui.ClipTheme
import clipfactory.app.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProjectStore.load(this)
        ServerSync.schedule(this) // keeps the 15-min check running when a server is set
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        // debug builds only: open one screen directly (screen checks on a slow emulator), e.g. --es screen editor:pA:0
        val initial = if (BuildConfig.DEBUG) debugScreens(intent.getStringExtra("screen")) else null
        setContent {
            ClipTheme { App(incoming, consumeIncoming = { incoming.value = null }, nameOf = ::nameOf, initial = initial ?: listOf(Screen.Home)) }
        }
        if (savedInstanceState == null) handleIncoming(intent)
        // debug builds only: point the app at a test server and sync once (emulator test)
        if (BuildConfig.DEBUG && intent.hasExtra("srv_addr")) {
            Prefs.save(this, Prefs.options(this).copy(serverAddr = intent.getStringExtra("srv_addr").orEmpty(),
                serverCode = intent.getStringExtra("srv_code").orEmpty()))
            ServerSync.schedule(this)
            Thread { android.util.Log.i("ServerSync", "debug sync: " + ServerSync.sync(this)) }.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun debugScreens(spec: String?): List<Screen>? {
        val p = spec?.split(':') ?: return null
        val home = listOf<Screen>(Screen.Home)
        return when (p[0]) {
            "settings" -> home + Screen.Settings
            "choose" -> home + Screen.Choose(p[1])
            "results" -> home + Screen.Results(p[1], p.getOrNull(2)?.toInt() ?: 0, openPost = p.getOrNull(3) == "post")
            "editor" -> home + Screen.Results(p[1]) + Screen.Editor(p[1], p[2].toInt())
            "processing" -> home + Screen.Processing(p[1])
            "live" -> home + Screen.Live
            "diag" -> home + Screen.Settings + Screen.Diagnostics
            else -> null
        }
    }

    private fun nameOf(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video"

    private fun handleIncoming(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        // debug builds only: start right away with a ready transcript (used by the emulator test)
        if (BuildConfig.DEBUG && i?.getBooleanExtra("autostart", false) == true) {
            val o = Prefs.options(this)
            Prefs.save(this, o.copy(clips = i.getIntExtra("clips", o.clips), maxSeconds = i.getIntExtra("max", o.maxSeconds),
                chooseMoments = i.getBooleanExtra("choose", false)))
            ClipService.analyze(this, uri, i.getStringExtra("name") ?: "test", i.getStringExtra("test_words"))
            return
        }
        incoming.value = uri
    }
}
