package io.github.haigerman.hyperradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.haigerman.hyperradio.BuildConfig
import io.github.haigerman.hyperradio.MainActivity
import io.github.haigerman.hyperradio.R
import io.github.haigerman.hyperradio.data.PlaybackStatus
import io.github.haigerman.hyperradio.data.PlayerPhase
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.data.Station
import java.util.concurrent.Executors

/**
 * Spielt die Radiostreams – im Hintergrund, über die Benachrichtigung, Bluetooth-Tasten und
 * Android Auto (MediaLibraryService = durchsuchbare Senderliste für das Auto-Display).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    private val userAgent = "HyperRadio/${BuildConfig.VERSION_NAME} (Android)"

    /** Welche der Ausweich-URLs je Sender gerade benutzt wird */
    private val urlIndex = mutableMapOf<String, Int>()
    /** Fehlversuche seit der letzten erfolgreichen Wiedergabe */
    private var failures = 0
    private var reconnectTask: Runnable? = null
    private var waitingForNetwork = false
    private var gaveUp = false
    private var hadReadyForItem = false

    private var connectivity: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                // Netz ist zurück (z. B. nach einem Tunnel) → sofort neu verbinden statt auf den Timer zu warten
                if (waitingForNetwork || reconnectTask != null) {
                    cancelReconnect()
                    reconnect()
                }
            }
        }
    }

    private val stopAfterPause = Runnable {
        // Länger pausiert: Verbindung freigeben. Beim Fortsetzen geht es dann wieder live weiter,
        // statt minutenlang zeitversetzt aus einem veralteten Puffer zu spielen.
        if (!player.playWhenReady && player.playbackState != Player.STATE_IDLE) player.stop()
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            publishStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RadioRepository.init(this)
        player = buildPlayer()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_radio) },
        )

        connectivity = getSystemService(ConnectivityManager::class.java)
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        main.post(statusTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        session?.run {
            player.release()
            release()
        }
        session = null
        io.shutdownNow()
        RadioRepository.publishStatus(PlaybackStatus(stationId = RadioRepository.lastStationId))
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Player & Puffer

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            // viele Radio-Server leiten von https auf http um (oder umgekehrt)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(StreamErrorPolicy())

        // Puffer: Wiedergabe startet erst, wenn BUFFER_FOR_PLAYBACK_MS Audio da sind; nach einem
        // Aussetzer wird ein größeres Polster aufgebaut, damit es nicht gleich wieder stockt.
        // Solange Daten schneller ankommen (Burst beim Verbinden, gutes Netz), lädt der Player bis
        // MAX_BUFFER_MS voraus.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // hält CPU und WLAN wach, solange gespielt wird
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** Kurze Netz-Aussetzer werden schon innerhalb des Players überbrückt, ohne die Wiedergabe abzubrechen. */
    private class StreamErrorPolicy : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 6) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val base = super.getRetryDelayMsFor(info)
            if (base == C.TIME_UNSET) return C.TIME_UNSET
            return (info.errorCount * 1000L).coerceAtMost(5000L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (gaveUp) {
                    // nach "nicht erreichbar" vom Nutzer neu gestartet
                    gaveUp = false
                    failures = 0
                }
                Player.STATE_READY -> {
                    failures = 0
                    gaveUp = false
                    waitingForNetwork = false
                    hadReadyForItem = true
                }
                Player.STATE_ENDED -> {
                    // Ein Live-Stream "endet" nur, wenn der Server die Verbindung schließt
                    if (player.playWhenReady) scheduleReconnect()
                }
            }
            publishStatus()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player.playWhenReady) scheduleReconnect(error)
            publishStatus()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            main.removeCallbacks(stopAfterPause)
            if (!playWhenReady) {
                cancelReconnect()
                main.postDelayed(stopAfterPause, STOP_AFTER_PAUSE_MS)
            } else {
                gaveUp = false
                if (player.playbackState == Player.STATE_IDLE && player.currentMediaItem != null) {
                    failures = 0
                    reconnect()
                }
            }
            publishStatus()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cancelReconnect()
            failures = 0
            gaveUp = false
            hadReadyForItem = false
            mediaItem?.mediaId?.let { RadioRepository.lastStationId = it }
            publishStatus()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publishStatus()
    }

    // ------------------------------------------------------------------ Wiederverbinden

    private fun isOnline(): Boolean {
        val cm = connectivity ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scheduleReconnect(error: PlaybackException? = null) {
        if (reconnectTask != null) return
        val station = RadioRepository.find(player.currentMediaItem?.mediaId) ?: return
        if (!isOnline()) {
            // ohne Netz nicht zählen – der NetworkCallback startet neu, sobald wieder Netz da ist
            waitingForNetwork = true
            post(NO_NETWORK_RETRY_MS)
            return
        }
        waitingForNetwork = false
        failures++
        if (failures > MAX_FAILURES) {
            gaveUp = true
            player.stop()
            return
        }
        // Server-/Formatfehler oder wiederholte Netzfehler → nächste Ausweich-URL probieren
        val serverProblem = error != null && error.errorCode !in NETWORK_ERRORS
        if ((serverProblem || failures % 2 == 0) && station.urls.size > 1) {
            urlIndex[station.id] = ((urlIndex[station.id] ?: 0) + 1) % station.urls.size
        }
        // schnell beim ersten Mal, dann immer längere Pausen (max. 30 s)
        val delay = if (failures <= station.urls.size) 500L
        else (1000L shl (failures - station.urls.size).coerceAtMost(5)).coerceAtMost(30_000L)
        post(delay)
    }

    private fun post(delayMs: Long) {
        val task = Runnable {
            reconnectTask = null
            reconnect()
        }
        reconnectTask = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelReconnect() {
        reconnectTask?.let { main.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun reconnect() {
        if (!player.playWhenReady) return
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = RadioRepository.find(item.mediaId) ?: return
        val future = io.submit<MediaItem> { mediaItemFor(station, resolve = true) }
        future.addListener({
            val fresh = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            if (player.currentMediaItem?.mediaId != station.id || !player.playWhenReady) return@addListener
            hadReadyForItem = false
            if (fresh.localConfiguration?.uri != item.localConfiguration?.uri) {
                player.replaceMediaItem(index, fresh)
            }
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(index)
            player.prepare()
            publishStatus()
        }, ContextCompatExecutor(main))
    }

    // ------------------------------------------------------------------ Status für die UI

    private fun publishStatus() {
        val item = player.currentMediaItem
        val station = RadioRepository.find(item?.mediaId)
        val phase = when {
            item == null -> PlayerPhase.IDLE
            gaveUp -> PlayerPhase.ERROR
            !player.playWhenReady -> PlayerPhase.PAUSED
            waitingForNetwork -> PlayerPhase.WAITING_FOR_NETWORK
            reconnectTask != null || player.playerError != null || player.playbackState == Player.STATE_ENDED ->
                PlayerPhase.RECONNECTING
            player.playbackState == Player.STATE_READY -> PlayerPhase.PLAYING
            player.playbackState == Player.STATE_BUFFERING && hadReadyForItem -> PlayerPhase.BUFFERING
            else -> PlayerPhase.CONNECTING
        }
        val title = player.mediaMetadata.title?.toString()?.trim()
        val nowPlaying = title?.takeIf { it.isNotEmpty() && it != station?.name && it != "-" }
        RadioRepository.publishStatus(
            PlaybackStatus(
                stationId = station?.id ?: RadioRepository.lastStationId,
                phase = phase,
                nowPlaying = if (phase == PlayerPhase.IDLE) null else nowPlaying,
                bufferedMs = if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.BUFFERING) player.totalBufferedDuration else 0,
            ),
        )
    }

    // ------------------------------------------------------------------ Senderliste (App & Android Auto)

    private fun mediaItemFor(station: Station, resolve: Boolean): MediaItem {
        val i = (urlIndex[station.id] ?: 0).coerceIn(0, station.urls.lastIndex)
        val raw = station.urls[i]
        val uri = if (resolve) StreamResolver.resolve(raw, userAgent) else raw
        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(uri)
            .apply { if (StreamResolver.isHls(uri)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(browseMetadata(station))
            .build()
    }

    private fun browseMetadata(station: Station) = MediaMetadata.Builder()
        .setTitle(station.name)
        .setArtist(station.subtitle)
        .setStation(station.name)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    /** Eintrag ohne Stream-Adresse für die Liste (die URL wird erst beim Abspielen aufgelöst). */
    private fun browseItem(station: Station) = MediaItem.Builder()
        .setMediaId(station.id)
        .setMediaMetadata(browseMetadata(station))
        .build()

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build(),
        )
        .build()

    private fun findByQuery(query: String): List<Station> {
        val q = query.trim().lowercase()
        val all = RadioRepository.stations.value
        if (q.isEmpty()) return all
        val words = q.split(Regex("\\s+"))
        return all.filter { s -> words.all { w -> s.name.lowercase().contains(w) || s.subtitle.lowercase().contains(w) } }
            .ifEmpty { all.filter { s -> words.any { w -> s.name.lowercase().contains(w) } } }
    }

    /** Ganze Senderliste als Playlist, damit Weiter/Zurück (Lenkrad, Auto-Display) den Sender wechselt. */
    private fun playlistStartingAt(stationId: String?): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val stations = RadioRepository.stations.value
        val start = stations.indexOfFirst { it.id == stationId }.coerceAtLeast(0)
        return io.submit<MediaSession.MediaItemsWithStartPosition> {
            val items = stations.map { mediaItemFor(it, resolve = true) }
            MediaSession.MediaItemsWithStartPosition(items, start, C.TIME_UNSET)
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = if (params?.isRecent == true) folder(RECENT_ID, getString(R.string.app_name))
            else folder(ROOT_ID, getString(R.string.app_name))
            val rootParams = LibraryParams.Builder()
                .setExtras(Bundle().apply { putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST) })
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = when (parentId) {
                ROOT_ID -> RadioRepository.stations.value
                RECENT_ID -> listOfNotNull(RadioRepository.find(RadioRepository.lastStationId) ?: RadioRepository.stations.value.firstOrNull())
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(paged(list.map(::browseItem), page, pageSize), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = RadioRepository.find(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(browseItem(station), null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, findByQuery(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(paged(findByQuery(query).map(::browseItem), page, pageSize), params))

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Ein einzelner Sender (Tipp in der App/im Auto, Sprachbefehl) → ganze Liste ab diesem Sender
            val first = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            val query = first?.requestMetadata?.searchQuery
            val id = when {
                first == null -> RadioRepository.lastStationId
                RadioRepository.find(first.mediaId) != null -> first.mediaId
                query != null -> findByQuery(query).firstOrNull()?.id ?: RadioRepository.lastStationId
                else -> RadioRepository.lastStationId
            }
            return playlistStartingAt(id)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = io.submit<MutableList<MediaItem>> {
            mediaItems.mapNotNull { item ->
                val station = RadioRepository.find(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { findByQuery(it).firstOrNull() }
                station?.let { mediaItemFor(it, resolve = true) }
            }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = playlistStartingAt(RadioRepository.lastStationId)
    }

    private fun <T> paged(list: List<T>, page: Int, pageSize: Int): ImmutableList<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(list)
        val from = (page.toLong() * pageSize).coerceAtMost(list.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(list.size)
        return ImmutableList.copyOf(list.subList(from, to))
    }

    private class ContextCompatExecutor(private val handler: Handler) : java.util.concurrent.Executor {
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    companion object {
        /** Mindestens so viel wird vorgehalten, solange Daten nachkommen */
        const val MIN_BUFFER_MS = 30_000
        /** Höchstens so weit wird vorausgeladen */
        const val MAX_BUFFER_MS = 120_000
        /** Start der Wiedergabe, sobald so viel Audio da ist */
        const val BUFFER_FOR_PLAYBACK_MS = 4_000
        /** Nach einem Aussetzer erst weiter, wenn ein größeres Polster da ist */
        const val BUFFER_AFTER_REBUFFER_MS = 10_000

        private const val STOP_AFTER_PAUSE_MS = 60_000L
        private const val NO_NETWORK_RETRY_MS = 15_000L
        private const val MAX_FAILURES = 25

        private const val ROOT_ID = "root"
        private const val RECENT_ID = "recent"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1

        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}
