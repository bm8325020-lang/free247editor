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
        assertEquals("rect", cam.shape)
        val layout = LayoutMath.choose(cam, 1280, 720, force = "split")
        assertTrue(layout is Layout.Split)
        val game = (layout as Layout.Split).game
        val webcam = cam.rect!!
        assertTrue("webcam must not overlap the gameplay crop", game.x >= webcam.x + webcam.w - 1e-6)
        // the face crop stays inside the webcam: no gameplay in the webcam panel
        assertTrue(box.x >= webcam.x && box.y >= webcam.y)
        assertTrue(box.x + box.w <= webcam.x + webcam.w + 1e-9 && box.y + box.h <= webcam.y + webcam.h + 1e-9)
        // the whole webcam on top: the box covers (almost) all of the webcam, not a face crop
        assertTrue(box.w >= webcam.w * 0.95 && box.h >= webcam.h * 0.9)
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
    fun weakMomentsNeverFillTheCountAndTheExplanationSaysWhy() {
        val loud = res("loud.txt").readLines().filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()
        val ex = Hype.audioExcitement(loud, 0.1)
        val many = s.copy(clips = 15)
        val ranked = Hype.rescore(Hype.audioCandidates(ex, duration, many), ex, words(), duration, many.maxSeconds)
        val strong = ranked.count { Hype.isStrong(it, many) }
        val picked = Hype.pick(ranked, words(), many)
        assertTrue(picked.size in 1..strong)
        assertTrue(strong < 15)
        assertTrue(picked.all { Hype.isStrong(it, many) })
        val msg = Hype.explain(15, strong, picked.size, "auto", picked.size, emptyList())
        assertTrue(msg, msg.startsWith("Pedido: até 15 clipes. Saíram ${picked.size}.") && "momentos fortes" in msg)
        assertEquals("Pedido: até 3 clipes. Saíram 3.", Hype.explain(3, 5, 3, "auto", 3, emptyList()))
    }

    private fun loadCam(prefix: String): Facecam {
        val lines = res("${prefix}faces.txt").readLines()
        val faces = lines.map { line ->
            if (line.isBlank()) emptyList() else line.split(';').map { f ->
                val v = f.split(',').map { it.toDouble() }
                Face(v[0], v[1], v[2], v[3])
            }
        }
        val (w, h) = res("${prefix}edgefreq.dim").readText().trim().split(' ').map { it.toInt() }
        val fb = ByteBuffer.wrap(res("${prefix}edgefreq.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val freq = FloatArray(fb.remaining()).also { fb.get(it) }
        return LayoutMath.detectFacecam(faces, freq, w, h) as Facecam
    }

    @Test
    fun roundWebcamIsDetectedLikePython() {
        val cam = loadCam("round_")
        assertEquals("round", cam.shape)
        val exp = res("expected_round.txt").readText().trim().split(' ').map { it.toDouble() }
        for (i in 0..3) assertEquals(exp[i], cam.circle!![i], 2e-3)
    }

    @Test
    fun pipLayoutMatchesPythonForSquareAndRoundWebcams() {
        val rows = res("expected_pip.txt").readLines().filter { it.isNotBlank() }.associate { line ->
            val p = line.split(' '); p[0] to p.drop(1)
        }
        for ((name, cam) in listOf("rect" to loadCam(""), "round" to loadCam("round_"))) {
            val e = rows.getValue(name).map { it.toDouble() }
            val pip = LayoutMath.choose(cam, 1280, 720, force = "pip") as Layout.Pip
            assertEquals("$name src", e.subList(0, 4), listOf(pip.src.x, pip.src.y, pip.src.w, pip.src.h).map { Math.round(it * 1e5) / 1e5 })
            assertEquals(name == "round", pip.round)
            assertEquals(e[4] == 1.0, pip.round)
            assertEquals("$name dst", e.subList(5, 9), listOf(pip.dst.x * 1080, pip.dst.y * 1920, pip.dst.w * 1080, pip.dst.h * 1920).map { Math.round(it).toDouble() })
            for (i in 0..3) assertEquals("$name game", e[9 + i], listOf(pip.game.x, pip.game.y, pip.game.w, pip.game.h)[i], 2e-5)
        }
    }

    @Test
    fun aGameCharacterHoldingStillIsNotAWebcam() {
        val faces = List(30) { listOf(Face(0.5, 0.45, 0.1, 0.18)) } + List(10) { emptyList<Face>() }
        assertEquals(null, LayoutMath.detectFacecam(faces, FloatArray(640 * 360), 640, 360))
        assertEquals(null, LayoutMath.detectFacecam(faces, null, 640, 360))
        assertTrue(LayoutMath.choose(null, 1280, 720) is Layout.Game) // no webcam: gameplay only
    }

    @Test
    fun webcamOnTopIsTheDefault() {
        assertTrue(LayoutMath.choose(loadCam(""), 1280, 720) is Layout.Split)
        assertTrue(LayoutMath.choose(loadCam("round_"), 1280, 720) is Layout.Split)
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
