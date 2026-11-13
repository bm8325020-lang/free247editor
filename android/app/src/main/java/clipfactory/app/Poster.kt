package clipfactory.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import clipfactory.core.PostText

/**
 * "Post with one tap": copies the ready post text and opens the platform's app with the clip attached.
 * The user pastes the text and publishes. No API is involved, so nothing is posted without the user.
 */
object Poster {
    private val PACKAGES = mapOf(
        PostText.Platform.TIKTOK to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        PostText.Platform.SHORTS to listOf("com.google.android.youtube"),
        PostText.Platform.REELS to listOf("com.instagram.android"),
        PostText.Platform.KWAI to listOf("com.kwai.video"),
    )

    private fun share(uri: Uri, text: String) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun post(activity: Activity, uri: Uri, title: String, opt: Options, platform: PostText.Platform) {
        val text = PostText.build(title, opt.credit, opt.hashtags, platform)
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("legenda", text))
        val pm = activity.packageManager
        val target = PACKAGES[platform]?.map { pkg -> share(uri, text).setPackage(pkg) }
            ?.firstOrNull { it.resolveActivity(pm) != null }
        if (target != null) {
            Toast.makeText(activity, "Legenda copiada: cole no ${platform.label} e publique.", Toast.LENGTH_LONG).show()
            activity.startActivity(target)
        } else {
            if (platform != PostText.Platform.OTHER) {
                Toast.makeText(activity, "${platform.label} não está instalado. Legenda copiada; escolha outro app.",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "Legenda copiada.", Toast.LENGTH_SHORT).show()
            }
            activity.startActivity(Intent.createChooser(share(uri, text), "Postar clipe"))
        }
    }
}
