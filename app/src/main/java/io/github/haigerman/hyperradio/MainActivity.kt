package io.github.haigerman.hyperradio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
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
import io.github.haigerman.hyperradio.ui.PairedDevice
import io.github.haigerman.hyperradio.ui.PairedDevicePicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                pairedChoice?.let { devices ->
                    PairedDevicePicker(
                        devices = devices,
                        alreadyAdded = SpeakerStore.speakers.value.map { it.address }.toSet(),
                        onPick = ::onPairedDeviceChosen,
                        onDismiss = { pairedChoice = null },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ Bluetooth-Lautsprecher

    /** Gerade angezeigte Auswahl der gekoppelten Geräte (null = Auswahl geschlossen) */
    private var pairedChoice by mutableStateOf<List<PairedDevice>?>(null)

    private val companionDevices: CompanionDeviceManager?
        get() = if (packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showPairedDevices()
            else Toast.makeText(this, "Ohne die Berechtigung „Geräte in der Nähe“ sieht HyperRadio keine gekoppelten Geräte", Toast.LENGTH_LONG).show()
        }

    /** „＋“: gekoppelte Geräte anzeigen (ab Android 12 zuerst die Berechtigung erfragen). */
    private fun addSpeaker() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            showPairedDevices()
        }
    }

    @SuppressLint("MissingPermission") // Berechtigung wird in addSpeaker() geprüft
    private fun showPairedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Dieses Gerät hat kein Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        val devices = try {
            adapter.bondedDevices.orEmpty().map { d ->
                val audio = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                PairedDevice(d.address, d.name?.takeIf { it.isNotBlank() } ?: d.address, audio)
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            Toast.makeText(this, "Keine gekoppelten Bluetooth-Geräte gefunden – bitte zuerst in den Android-Einstellungen koppeln", Toast.LENGTH_LONG).show()
            return
        }
        // Lautsprecher/Kopfhörer zuerst, dann alphabetisch
        pairedChoice = devices.sortedWith(compareBy<PairedDevice>({ !it.audio }, { it.name.lowercase() }))
    }

    /** Gekoppeltes Gerät gewählt: speichern und von Android für den Start im Hintergrund verknüpfen lassen. */
    private fun onPairedDeviceChosen(device: PairedDevice) {
        pairedChoice = null
        SpeakerStore.add(Speaker(device.address, device.name))
        Toast.makeText(this, "„${device.name}“ hinzugefügt", Toast.LENGTH_SHORT).show()
        associate(device.address)
    }

    /** Ergebnis von Androids Rückfrage „HyperRadio mit … verknüpfen?“ */
    private val associationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val address = pendingAssociation
            pendingAssociation = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.let(::onAssociated)
            } else if (address != null) {
                observePresence(address) // Android 12
            }
        }

    /** Gerät, für das gerade Androids Rückfrage läuft */
    private var pendingAssociation: String? = null

    @RequiresApi(33)
    private fun onAssociated(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString() ?: return
        val known = SpeakerStore.find(address)
        SpeakerStore.add(Speaker(address, known?.name ?: info.displayName?.toString() ?: address, info.id))
        observePresence(address)
    }

    private fun observePresence(address: String) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                @Suppress("DEPRECATION")
                companionDevices?.startObservingDevicePresence(address.uppercase())
            }
        }
    }

    /**
     * Verknüpft genau dieses (schon gekoppelte) Gerät mit HyperRadio – keine Suche nach neuen
     * Geräten, nur Androids kurze Rückfrage. Die Verknüpfung erlaubt es Android, HyperRadio beim
     * Verbinden zu wecken und die Wiedergabe auch aus dem Hintergrund zu starten.
     */
    private fun associate(address: String) {
        val cdm = companionDevices ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        ) {
            observePresence(address)
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address.uppercase()).build())
            .setSingleDevice(true)
            .build()
        val launch = { sender: IntentSender ->
            pendingAssociation = address
            associationLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                    override fun onAssociationCreated(associationInfo: AssociationInfo) = onAssociated(associationInfo)
                    override fun onFailure(error: CharSequence?) {}
                })
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, object : CompanionDeviceManager.Callback() {
                    @Deprecated("Ab Android 13 ersetzt")
                    override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
                    override fun onFailure(error: CharSequence?) {}
                }, null)
            }
        } catch (_: Exception) {
            // ohne Verknüpfung greift weiterhin die Bluetooth-Verbindungsmeldung
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
