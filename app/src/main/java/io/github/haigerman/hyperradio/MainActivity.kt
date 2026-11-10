package io.github.haigerman.hyperradio

import android.Manifest
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
        enableEdgeToEdge()
        setContent {
            HyperRadioTheme {
                RadioScreen(
                    onStationClick = ::playStation,
                    onPlayPause = ::togglePlayback,
                )
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
            if (c.currentMediaItem?.mediaId == id && RadioRepository.status.value.phase != PlayerPhase.ERROR) {
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
