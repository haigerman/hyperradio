package io.github.haigerman.hyperradio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.haigerman.hyperradio.ui.Sand
import io.github.haigerman.hyperradio.ui.SpotifyScreen
import io.github.haigerman.hyperradio.spotify.SpotifyConfig
import io.github.haigerman.hyperradio.spotify.SpotifyRepository

class MainActivity : ComponentActivity() {

    private companion object {
        const val SCREEN_RADIO = "radio"
        const val SCREEN_SPOTIFY = "spotify"
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* egal – Wiedergabe läuft auch ohne */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioRepository.init(this)
        SpotifyRepository.init(this)
        handleSpotifyRedirect(intent)
        enableEdgeToEdge()
        setContent {
            HyperRadioTheme {
                var screen by rememberSaveable { mutableStateOf(if (openSpotifyAfterLogin) SCREEN_SPOTIFY else SCREEN_RADIO) }
                var setupInfo by rememberSaveable { mutableStateOf(false) }
                openSpotifyScreen = { screen = SCREEN_SPOTIFY }
                when (screen) {
                    SCREEN_SPOTIFY -> SpotifyScreen(
                        onBack = { screen = SCREEN_RADIO },
                        onStartMix = ::startSpotifyMix,
                    )
                    else -> RadioScreen(
                        onStationClick = ::playStation,
                        onPlayPause = ::togglePlayback,
                        onSpotifyClick = {
                            val sp = SpotifyRepository.state.value
                            when {
                                !sp.configured -> setupInfo = true
                                !sp.loggedIn -> loginToSpotify()
                                else -> screen = SCREEN_SPOTIFY
                            }
                        },
                    )
                }
                if (setupInfo) {
                    AlertDialog(
                        onDismissRequest = { setupInfo = false },
                        title = { Text("Spotify einrichten") },
                        text = {
                            Text(
                                "Für Spotify braucht HyperRadio eine eigene Client ID aus dem Spotify-Entwicklerportal " +
                                    "(developer.spotify.com). Die Anleitung steht im README des Projekts.",
                            )
                        },
                        confirmButton = { TextButton(onClick = { setupInfo = false }) { Text("OK", color = Sand) } },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ Spotify

    private var openSpotifyAfterLogin = false
    private var openSpotifyScreen: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleSpotifyRedirect(intent)) openSpotifyScreen?.invoke()
    }

    private fun handleSpotifyRedirect(intent: Intent?): Boolean {
        val uri: Uri = intent?.data ?: return false
        if (!SpotifyRepository.isLoginRedirect(uri)) return false
        intent.data = null // nicht noch einmal verarbeiten (z. B. nach Drehen)
        SpotifyRepository.handleRedirect(uri)
        openSpotifyAfterLogin = true
        return true
    }

    private fun loginToSpotify() {
        val url = Uri.parse(SpotifyRepository.loginUrl())
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        }
    }

    private fun startSpotifyMix() {
        // Radio anhalten, Spotify übernimmt
        withController { c -> if (c.playWhenReady) c.pause() }
        SpotifyRepository.startMix(this)
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
