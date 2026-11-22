package io.github.haigerman.hyperradio

import io.github.haigerman.hyperradio.playback.DriveDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveDetectorTest {
    private val s = 1000L
    private val min = 60 * s

    @Test
    fun firstConnectStartsDrive() {
        assertTrue(DriveDetector(30 * s).onConnected(5 * s))
    }

    @Test
    fun shortShoppingStopRestartsAfterMoreThan30Seconds() {
        val d = DriveDetector(30 * s)
        assertTrue(d.onConnected(0))
        d.onDisconnected(20 * min)          // aussteigen
        assertTrue(d.onConnected(22 * min)) // nach 2 Minuten wieder einsteigen → Auto Play
    }

    @Test
    fun briefDropoutDoesNotRestart() {
        val d = DriveDetector(30 * s)
        assertTrue(d.onConnected(0))
        d.onDisconnected(10 * min)
        assertFalse(d.onConnected(10 * min + 20 * s)) // 20 s Aussetzer
        d.onDisconnected(15 * min)
        assertTrue(d.onConnected(15 * min + 31 * s))  // 31 s getrennt → neue Fahrt
    }

    @Test
    fun repeatedConnectWithoutDisconnectIsNotANewDrive() {
        val d = DriveDetector(30 * s)
        assertTrue(d.onConnected(0))
        assertFalse(d.onConnected(5 * min)) // z. B. Pause gedrückt, Oberfläche verbindet neu
    }
}
