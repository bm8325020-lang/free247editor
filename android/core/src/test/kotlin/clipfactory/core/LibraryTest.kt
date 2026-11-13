package clipfactory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun signal(fps: Double, cutsAt: List<Int>, blackFrames: Set<Int>, n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val times = DoubleArray(n) { it / fps }
        val diffs = DoubleArray(n) { i -> if (i in cutsAt) 70.0 else 2.0 + (i % 3) }
        val luma = DoubleArray(n) { i -> if (i in blackFrames) 3.0 else 110.0 }
        return Triple(times, diffs, luma)
    }

    @Test
    fun hardCutsBecomeScenes() {
        val (t, d, l) = signal(30.0, listOf(60, 150), emptySet(), 240)
        val s = SceneDetect.cuts(t, d, l, 8.0)
        assertEquals(3, s.size)
        assertEquals(2.0, s[1].start, 1e-9)
        assertEquals(5.0, s[1].end, 1e-9)
        assertEquals(8.0, s[2].end, 1e-9)
    }

    @Test
    fun blackFramesBetweenClipsAreSkippedAndTinyBitsDropped() {
        val black = (90 until 96).toSet() + (200 until 205).toSet()
        val (t, d, l) = signal(30.0, emptyList(), black, 300)
        val s = SceneDetect.cuts(t, d, l, 10.0)
        assertEquals(3, s.size)
        assertEquals(3.0, s[0].end, 1e-9) // stops at the first black frame
        assertEquals(96 / 30.0, s[1].start, 1e-9) // starts after the black gap
        assertTrue(s.all { it.end - it.start >= 0.5 })
    }

    @Test
    fun fastActionIsNotACut() {
        // a fight scene changes a lot frame to frame: the threshold adapts to it
        val n = 200
        val times = DoubleArray(n) { it / 30.0 }
        val diffs = DoubleArray(n) { i -> if (i == 100) 120.0 else 25.0 + (i % 5) * 3 }
        val s = SceneDetect.cuts(times, diffs, DoubleArray(n) { 100.0 }, n / 30.0)
        assertEquals(2, s.size)
    }

    @Test
    fun searchUnderstandsPortugueseWithoutAccentsAndRanksBestFirst() {
        val docs = mapOf(
            "a" to "Gojo tira a venda dos olhos, close no rosto, olhos azuis brilhando | removes blindfold",
            "b" to "Gojo sorrindo em pé numa rua à noite | smiling",
            "c" to "Sukuna rindo, plano aberto | laughing",
            "d" to "Gojo diz: você é fraco | fala: \"you are weak\"",
        )
        assertEquals("a", SceneSearch.search("gojo tirando a venda", docs).first())
        assertEquals("d", SceneSearch.search("Gojo falando que é fraco", docs).first())
        assertEquals(listOf("c"), SceneSearch.search("súkuna", docs))
        assertTrue(SceneSearch.search("blindfold", docs).contains("a"))
        assertEquals(emptyList<String>(), SceneSearch.search("de que", docs))
    }

    @Test
    fun aLongDescriptionDoesNotMatchAnUnrelatedVideoOnOneWord() {
        // the user's real case: a quote from Hercules searched in a library that only had a Jujutsu Kaisen video
        val docs = mapOf("jjk#0" to "Jujutsu Kaisen reaction to Satoru Gojo's sacrifice  parada")
        val q = "o cara que tinha tudo, velocidade nos pés, aguentava qualquer parada, mas aquela droga de calcanhar do filme Hércules"
        assertEquals(emptyList<String>(), SceneSearch.search(q, docs))
        assertEquals(listOf("jjk#0"), SceneSearch.search("gojo sacrifice", docs))
    }

    @Test
    fun realVideoSignalSplitsWhereTheClipsChange() {
        // measured on a real test video: clips change at 4 s and 7 s, then black from 12.0 to 12.3 s, ends at 16.3 s
        val rows = javaClass.classLoader!!.getResource("cut_signal.tsv")!!.readText().lines().filter { it.isNotBlank() }
            .map { it.split('\t').map(String::toDouble) }
        val t = rows.map { it[0] }.toDoubleArray()
        val s = SceneDetect.cuts(t, rows.map { it[1] }.toDoubleArray(), rows.map { it[2] }.toDoubleArray(), t.last() + 1 / 30.0)
        assertEquals(4, s.size)
        assertEquals(4.0, s[1].start, 0.05)
        assertEquals(7.0, s[2].start, 0.05)
        assertEquals(12.0, s[2].end, 0.1)
        assertTrue(s[3].start > 12.25)
    }
}
