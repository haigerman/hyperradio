package io.github.haigerman.hyperradio.playback

/**
 * Entscheidet, ob eine Verbindung mit Android Auto eine "neue Fahrt" ist, bei der Auto Play
 * den letzten Sender starten soll: Das Auto muss vorher mindestens [rearmMs] getrennt gewesen
 * sein (kurze Aussetzer der Verbindung lösen keinen Neustart aus). Zeiten in Millisekunden
 * (z. B. SystemClock.elapsedRealtime()).
 */
class DriveDetector(private val rearmMs: Long) {
    private var connected = false
    private var disconnectedAt: Long? = null
    private var lastTrigger: Long? = null

    /** Auto wurde verbunden. true = neue Fahrt → Auto Play auslösen. */
    fun onConnected(now: Long): Boolean {
        if (connected) return false
        connected = true
        val longEnough = disconnectedAt?.let { now - it >= rearmMs } ?: true
        val notJustTriggered = lastTrigger?.let { now - it >= rearmMs } ?: true
        val newDrive = longEnough && notJustTriggered
        if (newDrive) lastTrigger = now
        return newDrive
    }

    /** Auto wurde getrennt (Aussteigen, Kabel ab, Bluetooth weg). */
    fun onDisconnected(now: Long) {
        if (!connected) return
        connected = false
        disconnectedAt = now
    }
}
