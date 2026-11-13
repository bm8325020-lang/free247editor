package clipfactory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTextTest {
    @Test
    fun buildsTitleCreditAndHashtags() {
        val t = PostText.build("ELE NÃO ACREDITOU", "paulinholoko", "#gta6, cortes  #Clips #gta6", PostText.Platform.TIKTOK)
        assertEquals("ELE NÃO ACREDITOU\nCréditos: @paulinholoko\n\n#gta6 #cortes #clips", t)
    }

    @Test
    fun shortsAlwaysGetsTheShortsTag() {
        assertTrue(PostText.build("x", "", "gta6", PostText.Platform.SHORTS).endsWith("#shorts #gta6"))
    }

    @Test
    fun hashtagsAreDroppedBeforeTheLimitIsCrossed() {
        val many = (1..500).joinToString(" ") { "tag$it" }
        val t = PostText.build("Título", "@x", many, PostText.Platform.KWAI)
        assertTrue(t.length <= 1000)
        assertTrue(t.startsWith("Título\nCréditos: @x\n\n#tag1 "))
        assertTrue(!t.endsWith("#")) // never cut a tag in half
    }

    @Test
    fun emptyFieldsStayClean() {
        assertEquals("Só o título", PostText.build("Só o título", "", "", PostText.Platform.REELS))
    }
}
