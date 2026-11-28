package io.github.haigerman.hyperradio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import io.github.haigerman.hyperradio.data.Speaker
import io.github.haigerman.hyperradio.data.SpeakerStore
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.haigerman.hyperradio.data.PlayerPhase
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.playback.PlaybackService
import io.github.haigerman.hyperradio.ui.HyperRadioTheme
import io.github.haigerman.hyperradio.ui.RadioScreen

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* egal – Wiedergabe läuft auch ohne */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioRepository.init(this)
        SpeakerStore.init(this)
        // Anmeldedaten des entfernten Spotify-Mix vom Gerät löschen
        deleteSharedPreferences("spotify")
        enableEdgeToEdge()
        setContent {
            HyperRadioTheme {
                RadioScreen(
                    onStationClick = ::playStation,
                    onPlayPause = ::togglePlayback,
                    onSeekBack = { withController { it.seekBack() } },
                    onSeekForward = { withController { it.seekForward() } },
                    onAddSpeaker = ::addSpeaker,
                    onRemoveSpeaker = ::removeSpeaker,
                )
            }
        }
    }

    // ------------------------------------------------------------------ Bluetooth-Lautsprecher

    private val companionDevices: CompanionDeviceManager?
        get() = if (packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    /** Ergebnis des Android-Dialogs "Mit HyperRadio verknüpfen" */
    private val associationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= 33) {
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.let(::onAssociated)
            } else {
                @Suppress("DEPRECATION")
                val device = data.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE) ?: return@registerForActivityResult
                // Gerätename braucht ab Android 12 die Bluetooth-Berechtigung – sonst die Adresse anzeigen
                val mayReadName = Build.VERSION.SDK_INT < 31 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                val name = if (mayReadName) {
                    try {
                        @SuppressLint("MissingPermission")
                        val n = device.name
                        n
                    } catch (_: SecurityException) {
                        null
                    }
                } else {
                    null
                }
                saveSpeaker(Speaker(device.address, name?.takeIf { it.isNotBlank() } ?: device.address))
            }
        }

    @RequiresApi(33)
    private fun onAssociated(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString() ?: return
        saveSpeaker(Speaker(address, info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: address, info.id))
    }

    private fun saveSpeaker(speaker: Speaker) {
        SpeakerStore.add(speaker)
        // ab Android 12: Android meldet von nun an, wenn sich der Lautsprecher verbindet
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { @Suppress("DEPRECATION") companionDevices?.startObservingDevicePresence(speaker.address) }
        }
        Toast.makeText(this, "„${speaker.name}“ verknüpft", Toast.LENGTH_SHORT).show()
    }

    /** Öffnet Androids Dialog zur Auswahl und Verknüpfung eines Bluetooth-Geräts. */
    private fun addSpeaker() {
        val cdm = companionDevices
        if (cdm == null) {
            Toast.makeText(this, "Dieses Gerät unterstützt keine Geräte-Verknüpfung", Toast.LENGTH_LONG).show()
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val launch = { sender: IntentSender -> associationLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
        val failed = { error: CharSequence? ->
            Toast.makeText(this, "Verknüpfen nicht möglich" + (error?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = onAssociated(associationInfo)
                override fun onFailure(error: CharSequence?) = failed(error)
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Ab Android 13 ersetzt")
                override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
                override fun onFailure(error: CharSequence?) = failed(error)
            }, null)
        }
    }

    private fun removeSpeaker(speaker: Speaker) {
        SpeakerStore.remove(speaker.address)
        val cdm = companionDevices ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(speaker.address)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val id = cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(speaker.address, ignoreCase = true) }?.id
                    ?: speaker.associationId
                if (id >= 0) cdm.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(speaker.address)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun withController(action: (MediaController) -> Unit) {
        val f = controllerFuture ?: return
        f.addListener({ controller?.let(action) }, MoreExecutors.directExecutor())
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun playStation(id: String) {
        askNotificationPermission()
        withController { c ->
            val phase = RadioRepository.status.value.phase
            // Fehler oder zu Ende gehörte Podcast-Folge → neu laden (Podcast: neueste Folge)
            if (c.currentMediaItem?.mediaId == id && phase != PlayerPhase.ERROR && phase != PlayerPhase.ENDED) {
                // derselbe Sender: an/aus
                if (c.playWhenReady) c.pause() else resume(c)
            } else {
                // Der Dienst macht daraus die ganze Senderliste und löst die Stream-Adresse auf
                c.setMediaItems(listOf(MediaItem.Builder().setMediaId(id).build()))
                c.prepare()
                c.play()
            }
        }
    }

    private fun togglePlayback() {
        val status = RadioRepository.status.value
        withController { c ->
            when {
                c.currentMediaItem == null -> (status.stationId ?: RadioRepository.stations.value.firstOrNull()?.id)?.let(::playStation)
                status.phase == PlayerPhase.ERROR -> resume(c)
                // Podcast-Folge zu Ende → neueste Folge laden
                status.phase == PlayerPhase.ENDED -> c.currentMediaItem?.mediaId?.let(::playStation)
                c.playWhenReady -> c.pause()
                else -> {
                    askNotificationPermission()
                    resume(c)
                }
            }
        }
    }

    private fun resume(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.prepare()
        }
        c.play()
    }
}
