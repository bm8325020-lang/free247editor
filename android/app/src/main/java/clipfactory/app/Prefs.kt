package clipfactory.app

import android.content.Context

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Keys are kept encrypted (SecureKeys); plain values from version 1.0.0 are migrated on first read. */
    private fun secret(ctx: Context, name: String): String {
        val p = sp(ctx)
        p.getString(name, null)?.let { plain ->
            p.edit().putString("${name}_enc", SecureKeys.encrypt(plain.trim())).remove(name).apply()
            return plain.trim()
        }
        return SecureKeys.decrypt(p.getString("${name}_enc", "") ?: "")
    }

    fun options(ctx: Context): Options {
        val p = sp(ctx)
        return Options(
            clips = p.getInt("clips", 8),
            maxSeconds = p.getInt("maxSeconds", 50),
            layout = p.getString("layout", "auto") ?: "auto",
            captions = p.getBoolean("captions", true),
            hook = p.getBoolean("hook_v2", false), // off by default: a title burned at the start looked amateur
            groqKey = secret(ctx, "groqKey"),
            claudeKey = secret(ctx, "claudeKey"),
            coarenaKey = secret(ctx, "coarenaKey"),
            youtubeKey = secret(ctx, "youtubeKey"),
            serverAddr = p.getString("serverAddr", "") ?: "",
            serverCode = secret(ctx, "serverCode"),
            language = p.getString("language", "") ?: "",
            credit = p.getString("credit", "") ?: "",
            hashtags = p.getString("hashtags", "#cortes #clips") ?: "",
            chooseMoments = p.getBoolean("chooseMoments", false),
            captionStyle = p.getString("captionStyle", "amarelo") ?: "amarelo",
            live = p.getBoolean("live", false),
            liveLevel = p.getInt("liveLevel", 1),
        )
    }

    fun save(ctx: Context, o: Options) {
        sp(ctx).edit()
            .putInt("clips", o.clips).putInt("maxSeconds", o.maxSeconds).putString("layout", o.layout)
            .putBoolean("captions", o.captions).putBoolean("hook_v2", o.hook)
            .putString("groqKey_enc", SecureKeys.encrypt(o.groqKey.trim()))
            .putString("claudeKey_enc", SecureKeys.encrypt(o.claudeKey.trim()))
            .putString("coarenaKey_enc", SecureKeys.encrypt(o.coarenaKey.trim()))
            .putString("youtubeKey_enc", SecureKeys.encrypt(o.youtubeKey.trim()))
            .putString("serverAddr", o.serverAddr.trim()).putString("serverCode_enc", SecureKeys.encrypt(o.serverCode.trim()))
            .remove("groqKey").remove("claudeKey")
            .putString("language", o.language.trim())
            .putString("credit", o.credit.trim()).putString("hashtags", o.hashtags.trim())
            .putBoolean("chooseMoments", o.chooseMoments).putString("captionStyle", o.captionStyle)
            .putBoolean("live", o.live).putInt("liveLevel", o.liveLevel)
            .putBoolean("onboarded", true)
            .apply()
    }
}
