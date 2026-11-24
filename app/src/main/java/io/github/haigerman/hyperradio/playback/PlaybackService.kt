package io.github.haigerman.hyperradio.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
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
    private var currentId: String? = null
    /** Aktueller Titel aus den Stream-Metadaten (ICY "StreamTitle"), z. B. "Interpret - Titel" */
    private var icyTitle: String? = null

    /** Zuletzt aus dem Feed geholte Folge je Podcast (mit Zeitpunkt) */
    private val episodes = java.util.concurrent.ConcurrentHashMap<String, Pair<PodcastFeed.Episode, Long>>()

    /** Cover je Sender: ruhige Welle für die Liste, Live-Schnappschuss für "läuft gerade" */
    private val staticCovers = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val liveCovers = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val snapshot = FloatArray(1024)

    private val coverTicker = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            refreshLiveCover()
            main.postDelayed(this, COVER_REFRESH_MS)
        }
    }

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
        try {
            carConnection = CarConnection(this).also { it.type.observeForever(carObserver) }
        } catch (_: Exception) {
        }
        main.post(statusTicker)
        main.postDelayed(coverTicker, COVER_REFRESH_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        try {
            carConnection?.type?.removeObserver(carObserver)
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

        // Abgriff des Audiosignals für das Live-Cover
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioTap)))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }

        return ExoPlayer.Builder(this, renderers)
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
                    if (!hadReadyForItem) main.postDelayed(coverTicker, FIRST_COVER_DELAY_MS)
                    hadReadyForItem = true
                }
                Player.STATE_ENDED -> {
                    // Ein Live-Stream "endet" nur, wenn der Server die Verbindung schließt –
                    // eine Podcast-Folge ist dagegen einfach zu Ende gehört
                    if (player.playWhenReady && !isPodcast(player.currentMediaItem)) scheduleReconnect()
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
            // nur das Cover wurde ausgetauscht → kein Senderwechsel
            if (mediaItem?.mediaId == currentId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            currentId = mediaItem?.mediaId
            icyTitle = null
            cancelReconnect()
            failures = 0
            gaveUp = false
            hadReadyForItem = false
            mediaItem?.mediaId?.let { RadioRepository.lastStationId = it }
            // per Weiter/Zurück zu einem Podcast gewechselt → neueste Folge holen, falls veraltet
            if (isPodcast(mediaItem) && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) refreshPodcastIfStale()
            publishStatus()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val title = mediaMetadata.title?.toString()
            if (title != lastTitle) {
                lastTitle = title
                // neuer Titel → frisches Cover
                if (hadReadyForItem) main.postDelayed(coverTicker, FIRST_COVER_DELAY_MS)
            }
            publishStatus()
        }

        private var lastTitle: String? = null

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i) as? IcyInfo ?: continue
                val title = entry.title?.trim()?.takeIf { it.isNotEmpty() && it != "-" && it != " - " }
                if (title != icyTitle) {
                    icyTitle = title
                    applyNowPlaying()
                }
            }
        }
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
        // Podcast mit schon aufgelöster Folge: einfach an derselben Stelle weiter
        if (station.podcast && item.localConfiguration?.uri?.toString() !in station.urls) {
            hadReadyForItem = false
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(index)
            player.prepare()
            publishStatus()
            return
        }
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
                if (icyTitle != null) applyNowPlaying()
            }
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(index)
            player.prepare()
            publishStatus()
        }, ContextCompatExecutor(main))
    }

    /**
     * Schreibt den laufenden Titel in den aktuellen Eintrag: oben der Titel, darunter der Sender.
     * Nötig, weil ExoPlayer die Angaben des Eintrags (Sendername) über die Stream-Metadaten legt –
     * sonst sähe man weder in der App noch in der Benachrichtigung oder in Android Auto den Titel.
     */
    private fun applyNowPlaying() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = RadioRepository.find(item.mediaId) ?: return
        val title = icyTitle
        val updated = item.mediaMetadata.buildUpon()
            .setTitle(title ?: station.name)
            .setArtist(if (title != null) station.name else station.subtitle)
            .build()
        if (updated != item.mediaMetadata) {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updated).build())
        }
        publishStatus()
    }

    // ------------------------------------------------------------------ Podcasts

    private fun isPodcast(item: MediaItem?): Boolean = RadioRepository.find(item?.mediaId)?.podcast == true

    /** Holt die neueste Folge, wenn die gemerkte älter als [EPISODE_MAX_AGE_MS] ist. */
    private fun refreshPodcastIfStale() {
        val item = player.currentMediaItem ?: return
        val station = RadioRepository.find(item.mediaId)?.takeIf { it.podcast } ?: return
        val cached = episodes[station.id]
        if (cached != null && System.currentTimeMillis() - cached.second < EPISODE_MAX_AGE_MS) return
        val future = io.submit<MediaItem> { mediaItemFor(station, resolve = true) }
        future.addListener({
            val fresh = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            if (player.currentMediaItem?.mediaId != station.id) return@addListener
            if (fresh.localConfiguration?.uri != player.currentMediaItem?.localConfiguration?.uri) {
                player.replaceMediaItem(player.currentMediaItemIndex, fresh)
                player.prepare()
            }
        }, ContextCompatExecutor(main))
    }

    // ------------------------------------------------------------------ Status für die UI

    private fun publishStatus() {
        val item = player.currentMediaItem
        val station = RadioRepository.find(item?.mediaId)
        val podcast = station?.podcast == true
        val phase = when {
            item == null -> PlayerPhase.IDLE
            gaveUp -> PlayerPhase.ERROR
            podcast && player.playbackState == Player.STATE_ENDED -> PlayerPhase.ENDED
            !player.playWhenReady -> PlayerPhase.PAUSED
            waitingForNetwork -> PlayerPhase.WAITING_FOR_NETWORK
            reconnectTask != null || player.playerError != null || player.playbackState == Player.STATE_ENDED ->
                PlayerPhase.RECONNECTING
            player.playbackState == Player.STATE_READY -> PlayerPhase.PLAYING
            player.playbackState == Player.STATE_BUFFERING && hadReadyForItem -> PlayerPhase.BUFFERING
            else -> PlayerPhase.CONNECTING
        }
        // Radio: Titel aus dem Stream; Podcast: Titel der Folge
        val nowPlaying = icyTitle ?: if (podcast) item?.mediaMetadata?.title?.toString()?.takeIf { it != station?.name } else null
        RadioRepository.publishStatus(
            PlaybackStatus(
                stationId = station?.id ?: RadioRepository.lastStationId,
                phase = phase,
                nowPlaying = if (phase == PlayerPhase.IDLE) null else nowPlaying,
                bufferedMs = if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.BUFFERING) player.totalBufferedDuration else 0,
                positionMs = if (podcast) player.currentPosition.coerceAtLeast(0) else 0,
                durationMs = if (podcast) player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0 else 0,
                podcast = podcast,
            ),
        )
    }

    // ------------------------------------------------------------------ Senderliste (App & Android Auto)

    private fun mediaItemFor(station: Station, resolve: Boolean): MediaItem {
        if (station.podcast) return podcastItemFor(station, resolve)
        val i = (urlIndex[station.id] ?: 0).coerceIn(0, station.urls.lastIndex)
        val raw = station.urls[i]
        val uri = if (resolve) StreamResolver.resolve(raw, userAgent) else raw
        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(uri)
            .apply { if (StreamResolver.isHls(uri)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(browseMetadata(station, liveCovers[station.id]))
            .build()
    }

    /** Podcast: neueste Folge aus dem Feed (oder die zuletzt gefundene, wenn der Feed gerade nicht erreichbar ist). */
    private fun podcastItemFor(station: Station, resolve: Boolean): MediaItem {
        val feed = station.urls.first()
        val episode = (if (resolve) PodcastFeed.latest(feed, userAgent) else null)
            ?.also { episodes[station.id] = it to System.currentTimeMillis() }
            ?: episodes[station.id]?.first
        val metadata = browseMetadata(station, liveCovers[station.id]).buildUpon()
            .setTitle(episode?.title ?: station.name)
            .setArtist(if (episode?.title != null) station.name else station.subtitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(episode?.url ?: feed)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Neues Cover mit einem Schnappschuss der aktuellen Welle (Android Auto kann keine Animation). */
    private fun refreshLiveCover() {
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val station = RadioRepository.find(item.mediaId) ?: return
        if (!AudioTap.snapshot(snapshot)) return
        val samples = snapshot.copyOf()
        val future = io.submit<ByteArray> { CoverArt.render(station, samples) }
        future.addListener({
            val bytes = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            liveCovers.clear()
            liveCovers[station.id] = bytes
            val index = player.currentMediaItemIndex
            val current = player.currentMediaItem ?: return@addListener
            if (current.mediaId != station.id) return@addListener
            val updated = current.buildUpon()
                .setMediaMetadata(
                    current.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                )
                .build()
            player.replaceMediaItem(index, updated)
        }, ContextCompatExecutor(main))
    }

    private fun staticCover(station: Station): ByteArray =
        staticCovers.getOrPut(station.id) { CoverArt.render(station, null, size = 320) }

    private fun browseMetadata(station: Station, artwork: ByteArray? = null) = MediaMetadata.Builder()
        .setArtworkData(artwork ?: staticCover(station), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
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
            // Podcast-Feeds nur für den gestarteten Eintrag laden (sonst verzögert jeder Senderstart);
            // bei Weiter/Zurück zu einem Podcast holt refreshPodcastIfStale die neueste Folge
            val items = stations.map { mediaItemFor(it, resolve = !it.podcast || it.id == stations[start].id) }
            MediaSession.MediaItemsWithStartPosition(items, start, C.TIME_UNSET)
        }
    }

    // ------------------------------------------------------------------ Auto Play

    /** Neue Fahrt = Auto war vorher mindestens 30 s getrennt */
    private val drive = DriveDetector(AUTO_REARM_MS)

    /**
     * Verbindungsstatus von Android Auto über Androids offizielle Abfrage (CarConnection).
     * Zuverlässiger als die Verbindungen zum Mediendienst: Android Auto bleibt nach dem Aussteigen
     * oft noch im Hintergrund mit dem Dienst verbunden – dann sah ein kurzer Stopp (Einkaufen)
     * nicht wie eine neue Fahrt aus.
     */
    private var carConnection: CarConnection? = null
    private var carStateKnown = false
    private val carObserver = Observer<Int> { type ->
        carStateKnown = true
        val now = SystemClock.elapsedRealtime()
        if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) drive.onDisconnected(now)
        else if (drive.onConnected(now)) startAutoPlay()
    }

    /** Ersatzweg, falls CarConnection nichts meldet: Verbindungen der Android-Auto-Oberfläche zählen */
    private var autoConnections = 0

    private fun isCar(controller: MediaSession.ControllerInfo) = controller.packageName in CAR_PACKAGES

    private fun onCarConnected() {
        autoConnections++
        if (!carStateKnown && autoConnections == 1 && drive.onConnected(SystemClock.elapsedRealtime())) startAutoPlay()
    }

    private fun onCarDisconnected() {
        autoConnections = (autoConnections - 1).coerceAtLeast(0)
        if (!carStateKnown && autoConnections == 0) drive.onDisconnected(SystemClock.elapsedRealtime())
    }

    private fun startAutoPlay() {
        if (!RadioRepository.autoPlay.value) return
        main.removeCallbacks(autoPlayTask)
        main.postDelayed(autoPlayTask, AUTO_PLAY_DELAY_MS)
    }

    private val autoPlayTask = Runnable { autoPlayLastStation() }

    /** Zuletzt gehörten Sender fortsetzen – nur wenn gerade nichts läuft. */
    private fun autoPlayLastStation() {
        if (!RadioRepository.autoPlay.value || player.playWhenReady) return
        val id = RadioRepository.lastStationId ?: return
        val future = playlistStartingAt(id)
        future.addListener({
            val items = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            if (player.playWhenReady) return@addListener
            player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
            player.prepare()
            player.play()
        }, ContextCompatExecutor(main))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isCar(controller)) onCarConnected()
            return super.onConnect(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (isCar(controller)) onCarDisconnected()
            super.onDisconnected(session, controller)
        }

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
            val categories = RadioRepository.categories.value
            val category = categories.firstOrNull { CATEGORY_PREFIX + it.id == parentId }
            val list = when {
                // Kategorien → in Android Auto als Tabs oben (höchstens 4 – den Rest bündelt "Weitere")
                parentId == ROOT_ID ->
                    if (categories.size <= MAX_TABS) categories.map { folder(CATEGORY_PREFIX + it.id, it.title) }
                    else categories.take(MAX_TABS - 1).map { folder(CATEGORY_PREFIX + it.id, it.title) } + folder(MORE_ID, "Weitere")
                parentId == MORE_ID -> categories.drop(MAX_TABS - 1).map { folder(CATEGORY_PREFIX + it.id, "${it.emoji} ${it.title}") }
                category != null -> RadioRepository.stations.value.filter { it.categoryId == category.id }.map(::browseItem)
                parentId == RECENT_ID -> listOfNotNull(RadioRepository.find(RadioRepository.lastStationId) ?: RadioRepository.stations.value.firstOrNull())
                    .map(::browseItem)
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(paged(list, page, pageSize), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            RadioRepository.categories.value.firstOrNull { CATEGORY_PREFIX + it.id == mediaId }?.let {
                return Futures.immediateFuture(LibraryResult.ofItem(folder(mediaId, it.title), null))
            }
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
        private const val COVER_REFRESH_MS = 12_000L
        private const val FIRST_COVER_DELAY_MS = 2_500L
        private const val NO_NETWORK_RETRY_MS = 15_000L
        private const val MAX_FAILURES = 25

        private const val ROOT_ID = "root"
        private const val CATEGORY_PREFIX = "cat-"
        private const val MORE_ID = "more"
        private const val MAX_TABS = 4

        /** Android Auto (Handy) und Android Automotive (im Auto eingebaut) */
        private val CAR_PACKAGES = setOf("com.google.android.projection.gearhead", "com.android.car.media", "com.google.android.carassistant")
        private const val AUTO_PLAY_DELAY_MS = 1_500L
        private const val AUTO_REARM_MS = 30_000L
        private const val EPISODE_MAX_AGE_MS = 30 * 60_000L
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
