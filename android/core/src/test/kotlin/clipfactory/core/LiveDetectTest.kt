package clipfactory.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDetectTest {
    @Test
    fun reactionsWeighMore() {
        assertEquals(1.0, LiveDetect.messageWeight("boa noite galera"), 1e-9)
        assertTrue(LiveDetect.messageWeight("KEKW KEKW kkkkkkk CLIPA") >= 5.0)
        assertEquals(6.0, LiveDetect.messageWeight("clip clip clip clip clip"), 1e-9)
    }

    @Test
    fun steadyChatDoesNotFireButABurstDoes() {
        val r = Random(1)
        val steady = DoubleArray(300) { 2.0 + r.nextInt(3) }
        assertFalse(LiveDetect.fires(LiveDetect.robustZ(steady, 240), 0.0, 3.0))
        val burst = steady.copyOf() + doubleArrayOf(25.0, 30.0, 28.0)
        val z = LiveDetect.robustZ(burst, 240)
        assertTrue("z=$z", z > 6)
        assertTrue(LiveDetect.fires(z, 0.0, 3.0))
    }

    @Test
    fun onsetFindsWhereTheBurstStarted() {
        val x = DoubleArray(200) { 2.0 } + DoubleArray(8) { 20.0 }
        assertEquals(7, LiveDetect.chatOnset(x))
    }

    @Test
    fun loudMomentStandsOutFromTheLivesOwnLevel() {
        val r = Random(2)
        val calm = DoubleArray(200) { -30.0 + r.nextDouble(-1.5, 1.5) }
        assertTrue(LiveDetect.audioZ(calm).first < 3)
        val (z, ago) = LiveDetect.audioZ(calm + doubleArrayOf(-30.0, -12.0, -30.0))
        assertTrue("z=$z", z > 5)
        assertEquals(1, ago)
    }

    @Test
    fun needsWarmUpBeforeFiring() {
        assertEquals(0.0, LiveDetect.robustZ(DoubleArray(20) { 50.0 }, 240), 1e-9)
        assertEquals(0.0, LiveDetect.audioZ(DoubleArray(60) { 0.0 }).first, 1e-9)
    }
}
