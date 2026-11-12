package clipfactory.app

import android.content.Context

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun options(ctx: Context): Options {
        val p = sp(ctx)
        return Options(
            clips = p.getInt("clips", 8),
            maxSeconds = p.getInt("maxSeconds", 50),
            layout = p.getString("layout", "auto") ?: "auto",
            captions = p.getBoolean("captions", true),
            hook = p.getBoolean("hook", true),
            groqKey = p.getString("groqKey", "") ?: "",
            claudeKey = p.getString("claudeKey", "") ?: "",
            language = p.getString("language", "") ?: "",
        )
    }

    fun save(ctx: Context, o: Options) {
        sp(ctx).edit()
            .putInt("clips", o.clips).putInt("maxSeconds", o.maxSeconds).putString("layout", o.layout)
            .putBoolean("captions", o.captions).putBoolean("hook", o.hook)
            .putString("groqKey", o.groqKey.trim()).putString("claudeKey", o.claudeKey.trim())
            .putString("language", o.language.trim())
            .apply()
    }
}
