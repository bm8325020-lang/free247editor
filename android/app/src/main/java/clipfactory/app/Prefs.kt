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
            hook = p.getBoolean("hook", true),
            groqKey = secret(ctx, "groqKey"),
            claudeKey = secret(ctx, "claudeKey"),
            language = p.getString("language", "") ?: "",
            credit = p.getString("credit", "") ?: "",
            hashtags = p.getString("hashtags", "#cortes #clips") ?: "",
        )
    }

    fun save(ctx: Context, o: Options) {
        sp(ctx).edit()
            .putInt("clips", o.clips).putInt("maxSeconds", o.maxSeconds).putString("layout", o.layout)
            .putBoolean("captions", o.captions).putBoolean("hook", o.hook)
            .putString("groqKey_enc", SecureKeys.encrypt(o.groqKey.trim()))
            .putString("claudeKey_enc", SecureKeys.encrypt(o.claudeKey.trim()))
            .remove("groqKey").remove("claudeKey")
            .putString("language", o.language.trim())
            .putString("credit", o.credit.trim()).putString("hashtags", o.hashtags.trim())
            .apply()
    }
}
