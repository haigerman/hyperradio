package io.github.haigerman.hyperradio.playback

import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.data.SpeakerStore

/**
 * Startet den zuletzt gehörten Sender, sobald sich ein verknüpfter Bluetooth-Lautsprecher
 * verbindet – nur wenn gerade nichts läuft und der Lautsprecher vorher mindestens 30 s getrennt war.
 */
object SpeakerAutoPlay {

    fun onSpeakerConnected(context: Context, address: String) {
        val app = context.applicationContext
        SpeakerStore.init(app)
        RadioRepository.init(app)
        if (!SpeakerStore.onConnected(address)) return
        val stationId = RadioRepository.lastStationId ?: RadioRepository.stations.value.firstOrNull()?.id ?: return
        val main = Handler(Looper.getMainLooper())
        // kurz warten, bis die Audioverbindung zum Lautsprecher steht (sonst kurz aus dem Handy)
        main.postDelayed({
            // über die Mediensitzung starten – genau wie ein Druck auf Play
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener({
                val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
                if (!controller.playWhenReady) {
                    controller.setMediaItems(listOf(MediaItem.Builder().setMediaId(stationId).build()))
                    controller.prepare()
                    controller.play()
                }
                // Verbindung zum Dienst wieder lösen, die Wiedergabe läuft selbständig weiter
                main.postDelayed({ MediaController.releaseFuture(future) }, 10_000)
            }, ContextCompat.getMainExecutor(app))
        }, AUDIO_ROUTE_DELAY_MS)
    }

    private const val AUDIO_ROUTE_DELAY_MS = 3_000L

    fun onSpeakerDisconnected(context: Context, address: String) {
        SpeakerStore.init(context.applicationContext)
        if (SpeakerStore.find(address) != null) SpeakerStore.onDisconnected(address)
    }
}

/**
 * Ab Android 12: Android meldet über diese Klasse, wenn ein mit HyperRadio verknüpfter
 * Lautsprecher verbunden bzw. getrennt wird – auch wenn die App gerade nicht läuft. Dank der
 * Verknüpfung darf HyperRadio dann auch aus dem Hintergrund die Wiedergabe starten.
 */
@RequiresApi(Build.VERSION_CODES.S)
class SpeakerPresenceService : CompanionDeviceService() {

    @Deprecated("Ab Android 13 ersetzt")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) SpeakerAutoPlay.onSpeakerConnected(this, address)
    }

    @Deprecated("Ab Android 13 ersetzt")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) SpeakerAutoPlay.onSpeakerDisconnected(this, address)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { SpeakerAutoPlay.onSpeakerConnected(this, it) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { SpeakerAutoPlay.onSpeakerDisconnected(this, it) }
    }
}

/**
 * Bluetooth-Verbindungsmeldung von Android (alle Versionen; ab Android 12 mit der Berechtigung
 * „Geräte in der Nähe“). Doppelte Meldungen zusammen mit [SpeakerPresenceService] fängt die
 * 30-Sekunden-Regel ab.
 */
class SpeakerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> SpeakerAutoPlay.onSpeakerConnected(context, device.address)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> SpeakerAutoPlay.onSpeakerDisconnected(context, device.address)
        }
    }
}
