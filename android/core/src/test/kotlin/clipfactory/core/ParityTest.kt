package clipfactory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The Kotlin engine must give the same answers as the validated Python engine on the same inputs. */
class ParityTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())
    private val duration = 276.0
    private val s = Settings()

    private fun words() = res("words.tsv").readLines().filter { it.isNotBlank() }.map {
        val p = it.split('\t')
        Word(p[2], p[0].toDouble(), p[1].toDouble())
    }

    @Test
    fun excitementMatchesPython() {
        val loud = res("loud.txt").readLines().filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()
        val expected = res("ex.txt").readLines().filter { it.isNotBlank() }.map { it.toDouble() }
        val ex = Hype.audioExcitement(loud, 0.1)
        assertEquals(expected.size, ex.size)
        for (i in ex.indices) assertEquals("second $i", expected[i], ex[i], 1e-3)
        val peaks = Hype.candidatePeaks(ex, s, maxOf(12, s.clips * 3))
        assertEquals(res("peaks.txt").readText().trim().split(' ').map { it.toInt() }, peaks)
    }

    @Test
    fun windowsSnapHypeAndHooksMatchPython() {
        val words = words()
        for (line in res("expected_windows.tsv").readLines().filter { it.isNotBlank() }) {
            val p = line.split('\t')
            val peak = p[0].toInt() + 0.5
            val (a, b) = Hype.windowFor(peak, duration, s)
            assertEquals(p[1].toDouble(), a, 1e-3)
            assertEquals(p[2].toDouble(), b, 1e-3)
            val (a2, b2) = Hype.snap(words, a, b, duration)
            assertEquals("snap start @${p[0]}", p[3].toDouble(), a2, 1e-3)
            assertEquals("snap end @${p[0]}", p[4].toDouble(), b2, 1e-3)
            val (h, burst, n) = Hype.hypeScore(words, a2, b2, peak)
            assertEquals("hype @${p[0]}", p[5].toDouble(), h, 1e-3)
            assertEquals(p[6].toDouble(), burst, 1e-3)
            assertEquals(p[7].toInt(), n)
            assertEquals(p.getOrElse(8) { "" }, Hype.hookFrom(words, peak))
        }
    }

    @Test
    fun facecamMatchesPython() {
        val faces = res("faces.txt").readLines().map { line ->
            if (line.isBlank()) emptyList() else line.split(';').map { f ->
                val v = f.split(',').map { it.toDouble() }
                Face(v[0], v[1], v[2], v[3])
            }
        }
        val (w, h) = res("edgefreq.dim").readText().trim().split(' ').map { it.toInt() }
        val bytes = res("edgefreq.f32").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val freq = FloatArray(fb.remaining()).also { fb.get(it) }
        val cam = LayoutMath.detectFacecam(faces, freq, w, h) as Facecam
        val exp = res("expected_cam.txt").readLines()
        val rect = exp[0].split(' ').map { it.toDouble() }
        assertNotNull(cam.rect)
        assertEquals(rect[0], cam.rect!!.x, 2e-3)
        assertEquals(rect[1], cam.rect!!.y, 2e-3)
        assertEquals(rect[2], cam.rect!!.w, 2e-3)
        assertEquals(rect[3], cam.rect!!.h, 2e-3)
        val panel = LayoutMath.panelFraction(cam, 1280, 720)
        assertEquals(exp[1].trim().toInt(), Math.round(panel * LayoutMath.OUT_H).toInt())
        val box = LayoutMath.camBox(cam, 1280, 720, panel)
        val eb = exp[2].split(' ').map { it.toDouble() }
        assertEquals(eb[0], box.x, 2e-3); assertEquals(eb[1], box.y, 2e-3)
        assertEquals(eb[2], box.w, 2e-3); assertEquals(eb[3], box.h, 2e-3)
        val layout = LayoutMath.choose(cam, 1280, 720)
        assertTrue(layout is Layout.Split)
        val game = (layout as Layout.Split).game
        assertTrue("webcam must not overlap the gameplay crop", game.x >= box.x + box.w - 1e-6)
    }

    @Test
    fun fullPickMatchesPython() {
        val loud = res("loud.txt").readLines().filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()
        val ex = Hype.audioExcitement(loud, 0.1)
        val cands = Hype.audioCandidates(ex, duration, s)
        val words = words()
        val picked = Hype.pick(Hype.rescore(cands, ex, words, duration), words, s.copy(clips = 3))
        val expected = res("expected_pick3.txt").readText().trim().split(' ').map { it.toDouble() }
        assertEquals(expected, picked.map { it.peak })
        // the loudest planted scream (232 s) must be the top clip
        assertEquals(233.5, picked[0].peak, 1e-9)
    }

    @Test
    fun clipsNeverExceedTheMaxLength() {
        val loud = res("loud.txt").readLines().filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()
        val ex = Hype.audioExcitement(loud, 0.1)
        val short = s.copy(maxSeconds = 20.0, targetSeconds = 20.0)
        val cands = Hype.audioCandidates(ex, duration, short)
        val picked = Hype.pick(Hype.rescore(cands, ex, words(), duration, 20.0), words(), short)
        assertTrue(picked.isNotEmpty())
        for (c in picked) {
            assertTrue("clip ${c.seconds}s", c.seconds <= 20.0 + 1e-9)
            assertTrue("peak inside", c.peak >= c.start && c.peak <= c.end)
        }
    }

    @Test
    fun captionsGroupAndLookup() {
        val w = listOf(Word("Não", 10.0, 10.3), Word("acredito!", 10.35, 10.9), Word("olha", 11.0, 11.2),
            Word("isso", 11.25, 11.5), Word("mano", 11.55, 11.8), Word("demais", 11.85, 12.3))
        val ev = Captions.build(w, 10.0, 20.0)
        assertEquals(listOf("NÃO", "ACREDITO!"), ev[0].words)
        assertEquals(0, Captions.at(ev, 0.1))
        assertEquals(1, Captions.at(ev, 0.5))
        assertEquals(listOf("OLHA", "ISSO", "MANO"), ev[2].words)
        assertEquals(-1, Captions.at(ev, 9.0))
    }
}
