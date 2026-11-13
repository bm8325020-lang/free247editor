package clipfactory.core

/** Ready-to-paste post text per platform: title, credit to the streamer, hashtags. */
object PostText {
    enum class Platform(val label: String, val maxChars: Int) {
        TIKTOK("TikTok", 2200), SHORTS("YouTube Shorts", 5000), REELS("Instagram Reels", 2200),
        KWAI("Kwai", 1000), OTHER("Outros", 2200)
    }

    /** "#gta6, cortes  #Clips" -> ["#gta6", "#cortes", "#clips"] (deduplicated, no empties). */
    fun hashtags(raw: String): List<String> =
        raw.split(Regex("[\\s,;]+")).map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
            .map { "#" + it.lowercase() }.distinct()

    fun build(title: String, credit: String, rawTags: String, platform: Platform): String {
        val tags = hashtags(rawTags).toMutableList()
        if (platform == Platform.SHORTS && "#shorts" !in tags) tags.add(0, "#shorts")
        val who = credit.trim().let { c -> if (c.isEmpty()) "" else if (c.startsWith("@")) c else "@$c" }
        val parts = ArrayList<String>()
        if (title.isNotBlank()) parts.add(title.trim())
        if (who.isNotEmpty()) parts.add("Créditos: $who")
        var text = parts.joinToString("\n")
        // add hashtags while they fit the platform limit
        val fitted = ArrayList<String>()
        for (t in tags) {
            val candidate = (fitted + t).joinToString(" ")
            if ((if (text.isEmpty()) candidate else "$text\n\n$candidate").length > platform.maxChars) break
            fitted.add(t)
        }
        if (fitted.isNotEmpty()) text = if (text.isEmpty()) fitted.joinToString(" ") else "$text\n\n" + fitted.joinToString(" ")
        return text.take(platform.maxChars)
    }
}
