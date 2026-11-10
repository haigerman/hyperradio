package io.github.haigerman.hyperradio.spotify

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SpotifyState(
    val configured: Boolean = SpotifyConfig.isConfigured,
    val loggedIn: Boolean = false,
    val userName: String? = null,
    /** Die Künstler für die Zufallswiedergabe */
    val selected: List<SpotifyArtist> = emptyList(),
    /** Gefolgte + meistgehörte Künstler, alphabetisch */
    val library: List<SpotifyArtist> = emptyList(),
    val libraryLoading: Boolean = false,
    val query: String = "",
    val suggestions: List<SpotifyArtist> = emptyList(),
    val searching: Boolean = false,
    val playback: SpotifyPlayback? = null,
    /** Läuft gerade unser Mix? */
    val mixActive: Boolean = false,
    /** Fortschrittsmeldung, z. B. "Sammle Titel …" */
    val busy: String? = null,
    /** Fehlermeldung oder Hinweis für den Nutzer */
    val message: String? = null,
)

/** Zustand und Abläufe der Spotify-Anbindung – geteilt von allen Bildschirmen. */
object SpotifyRepository {
    private val _state = MutableStateFlow(SpotifyState())
    val state: StateFlow<SpotifyState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: SharedPreferences
    private lateinit var api: SpotifyApi
    private var searchJob: Job? = null
    private val trackCache = mutableMapOf<String, List<String>>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("spotify", Context.MODE_PRIVATE)
        api = SpotifyApi(prefs)
        _state.update {
            it.copy(
                loggedIn = api.isLoggedIn,
                userName = prefs.getString(KEY_USER, null),
                selected = decodeArtists(prefs.getString(KEY_SELECTED, null)),
                mixActive = prefs.getBoolean(KEY_MIX_ACTIVE, false),
            )
        }
    }

    // ------------------------------------------------------------------ Login

    /** Adresse der Spotify-Anmeldeseite (für Browser/Custom Tab). */
    fun loginUrl(): String = api.beginLogin()

    fun isLoginRedirect(uri: Uri?): Boolean =
        uri != null && uri.toString().startsWith(SpotifyConfig.REDIRECT_URI)

    fun handleRedirect(uri: Uri) {
        scope.launch {
            _state.update { it.copy(busy = "Melde bei Spotify an …", message = null) }
            try {
                val name = withContext(Dispatchers.IO) {
                    api.finishLogin(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"))
                    api.displayName()
                }
                prefs.edit().putString(KEY_USER, name).apply()
                _state.update { it.copy(loggedIn = true, userName = name, busy = null) }
                loadLibrary()
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = e.message) }
            }
        }
    }

    fun logout() {
        api.logout()
        prefs.edit().remove(KEY_USER).putBoolean(KEY_MIX_ACTIVE, false).apply()
        trackCache.clear()
        _state.update { it.copy(loggedIn = false, userName = null, library = emptyList(), playback = null, mixActive = false) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ Künstler

    fun loadLibrary() {
        if (!api.isLoggedIn || _state.value.libraryLoading) return
        scope.launch {
            _state.update { it.copy(libraryLoading = true) }
            try {
                val list = withContext(Dispatchers.IO) {
                    val followed = runCatching { api.followedArtists() }.getOrDefault(emptyList())
                    val top = runCatching { api.topArtists() }.getOrDefault(emptyList())
                    (followed + top).distinctBy { it.id }.sortedBy { it.name.lowercase() }
                }
                _state.update { it.copy(library = list, libraryLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(libraryLoading = false, message = e.message) }
            }
        }
    }

    /** Suche mit Autovervollständigung: wartet kurz, bis die Eingabe ruht. */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searching = false) }
            return
        }
        searchJob = scope.launch {
            delay(300)
            _state.update { it.copy(searching = true) }
            try {
                val res = withContext(Dispatchers.IO) { api.searchArtists(query) }
                if (_state.value.query == query) _state.update { it.copy(suggestions = res, searching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, message = e.message) }
            }
        }
    }

    fun isSelected(id: String) = _state.value.selected.any { it.id == id }

    fun toggleArtist(artist: SpotifyArtist) {
        _state.update { s ->
            val sel = if (s.selected.any { it.id == artist.id }) s.selected.filterNot { it.id == artist.id }
            else (s.selected + artist).sortedBy { it.name.lowercase() }
            s.copy(selected = sel)
        }
        prefs.edit().putString(KEY_SELECTED, encodeArtists(_state.value.selected)).apply()
    }

    // ------------------------------------------------------------------ Wiedergabe

    /**
     * Sammelt Titel aller gewählten Künstler, mischt sie und startet sie in der Spotify-App
     * auf diesem Handy. Ist Spotify noch nicht bereit, wird die App geöffnet und kurz gewartet.
     */
    fun startMix(context: Context) {
        val artists = _state.value.selected
        if (artists.isEmpty()) {
            _state.update { it.copy(message = "Wähle zuerst mindestens einen Künstler") }
            return
        }
        val app = context.applicationContext
        scope.launch {
            try {
                _state.update { it.copy(busy = "Sammle Titel von ${artists.size} Künstler${if (artists.size == 1) "" else "n"} …", message = null) }
                val pool = withContext(Dispatchers.IO) { collectTracks(artists) }
                val uris = MixBuilder.build(pool, MAX_TRACKS)
                if (uris.isEmpty()) throw IllegalStateException("Keine abspielbaren Titel gefunden")

                _state.update { it.copy(busy = "Verbinde mit Spotify …") }
                var device = withContext(Dispatchers.IO) { pickDevice(app) }
                if (device == null) {
                    // Spotify läuft noch nicht → App starten, dann meldet sie sich als Gerät an
                    openSpotify(app)
                    _state.update { it.copy(busy = "Warte auf die Spotify-App …") }
                    for (i in 0 until 15) {
                        delay(1000)
                        device = withContext(Dispatchers.IO) { runCatching { pickDevice(app) }.getOrNull() }
                        if (device != null) break
                    }
                }
                if (device == null) throw IllegalStateException("Spotify-App nicht gefunden – bitte Spotify einmal öffnen und erneut tippen")

                withContext(Dispatchers.IO) { api.play(device.id, uris) }
                prefs.edit().putBoolean(KEY_MIX_ACTIVE, true).apply()
                _state.update { it.copy(busy = null, mixActive = true) }
                delay(1500)
                refreshPlayback()
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = e.message) }
            }
        }
    }

    private suspend fun collectTracks(artists: List<SpotifyArtist>): Map<String, List<String>> {
        val gate = Semaphore(3) // nicht zu viele Anfragen gleichzeitig
        return kotlinx.coroutines.coroutineScope {
            artists.map { a ->
                async {
                    a.id to (trackCache[a.id] ?: gate.withPermit { runCatching { api.tracksOf(a) }.getOrDefault(emptyList()) }
                        .also { if (it.isNotEmpty()) synchronized(trackCache) { trackCache[a.id] = it } })
                }
            }.awaitAll().toMap()
        }
    }

    /** Das Spotify-Gerät dieses Handys (nicht etwa der PC, auf dem gerade Spotify läuft). */
    private fun pickDevice(context: Context): SpotifyDevice? {
        val devices = api.devices()
        val names = listOfNotNull(
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
            Build.MODEL,
        ).map { it.lowercase() }
        val phones = devices.filter { it.type.equals("Smartphone", ignoreCase = true) }
        return phones.firstOrNull { d -> names.any { n -> d.name.lowercase() == n || d.name.lowercase().contains(n) } }
            ?: phones.singleOrNull()
            ?: phones.firstOrNull { it.isActive }
    }

    private fun openSpotify(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            ?: throw IllegalStateException("Die Spotify-App ist nicht installiert")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun togglePause() {
        val playing = _state.value.playback?.isPlaying == true
        command { if (playing) api.pause() else api.resume() }
    }

    fun skip() = command { api.next() }

    private fun command(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                delay(700)
                refreshPlayback()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** Aktuellen Titel abfragen (wird regelmäßig aufgerufen, solange der Spotify-Bildschirm offen ist). */
    suspend fun refreshPlayback() {
        if (!api.isLoggedIn) return
        val pb = withContext(Dispatchers.IO) { runCatching { api.playback() }.getOrNull() }
        _state.update { it.copy(playback = pb) }
    }

    // ------------------------------------------------------------------ Speichern

    internal fun encodeArtists(list: List<SpotifyArtist>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }.toString()

    internal fun decodeArtists(json: String?): List<SpotifyArtist> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SpotifyArtist(o.optString("id"), o.optString("name")).takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val MAX_TRACKS = 100
    private const val KEY_SELECTED = "selected_artists"
    private const val KEY_USER = "user_name"
    private const val KEY_MIX_ACTIVE = "mix_active"
}
