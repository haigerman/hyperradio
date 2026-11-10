package io.github.haigerman.hyperradio.spotify

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SpotifyArtist(
    val id: String,
    val name: String,
    val genres: List<String> = emptyList(),
    val followers: Int = 0,
)

data class SpotifyDevice(val id: String, val name: String, val type: String, val isActive: Boolean)

data class SpotifyPlayback(
    val track: String?,
    val artists: String?,
    val isPlaying: Boolean,
    val device: String?,
)

class SpotifyException(val status: Int, val reason: String?, message: String) : IOException(message)

/**
 * Schlanker Client für die Spotify Web API. Hält Access- und Refresh-Token in den
 * SharedPreferences und erneuert den Access-Token automatisch. Alle Aufrufe blockieren –
 * nur im Hintergrund (Dispatchers.IO) verwenden.
 */
class SpotifyApi(
    private val prefs: SharedPreferences,
    private val baseUrl: String = "https://api.spotify.com/v1",
) {

    val isLoggedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null

    // ------------------------------------------------------------------ Login

    /** Startet einen neuen Login und gibt die Adresse für den Browser zurück. */
    fun beginLogin(): String {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        return Pkce.authorizeUrl(SpotifyConfig.CLIENT_ID, SpotifyConfig.REDIRECT_URI, SpotifyConfig.SCOPES, verifier, state)
    }

    /** Verarbeitet die Rückleitung von Spotify (hyperradio://spotify-callback?code=…&state=…). */
    fun finishLogin(code: String?, state: String?, error: String?) {
        if (error != null) throw SpotifyException(0, error, "Spotify-Anmeldung abgebrochen ($error)")
        val verifier = prefs.getString(KEY_VERIFIER, null)
        if (code == null || verifier == null || state != prefs.getString(KEY_STATE, null)) {
            throw SpotifyException(0, "state", "Anmeldung ungültig – bitte noch einmal versuchen")
        }
        tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SpotifyConfig.REDIRECT_URI,
                "client_id" to SpotifyConfig.CLIENT_ID,
                "code_verifier" to verifier,
            ),
        )
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
    }

    fun logout() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).apply()
    }

    private fun tokenRequest(params: Map<String, String>) {
        val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(Pkce.formEncode(params).toByteArray()) }
        val code = conn.responseCode
        val body = readBody(conn)
        if (code !in 200..299) {
            val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
            if (err == "invalid_grant") logout()
            throw SpotifyException(code, err, "Spotify-Anmeldung fehlgeschlagen ($code ${err ?: ""})")
        }
        val json = JSONObject(body)
        val edit = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000)
        // Spotify schickt beim Erneuern manchmal einen neuen Refresh-Token mit
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_REFRESH, it) }
        edit.apply()
    }

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String {
        val access = prefs.getString(KEY_ACCESS, null)
        if (!forceRefresh && access != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0)) return access
        val refresh = prefs.getString(KEY_REFRESH, null)
            ?: throw SpotifyException(401, "not_logged_in", "Nicht bei Spotify angemeldet")
        tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to SpotifyConfig.CLIENT_ID))
        return prefs.getString(KEY_ACCESS, null)!!
    }

    // ------------------------------------------------------------------ HTTP

    private fun call(method: String, path: String, body: JSONObject? = null, retried: Boolean = false): String {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${accessToken(forceRefresh = retried)}")
            if (body != null || method == "PUT" || method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (conn.doOutput) conn.outputStream.use { it.write((body?.toString() ?: "").toByteArray()) }
        val code = conn.responseCode
        val text = readBody(conn)
        when {
            code in 200..299 -> return text
            code == 401 && !retried -> return call(method, path, body, retried = true)
            code == 429 && !retried -> {
                val wait = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 2
                Thread.sleep(wait.coerceIn(1, 10) * 1000)
                return call(method, path, body, retried = true)
            }
        }
        val err = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val reason = err?.optString("reason")?.takeIf { it.isNotEmpty() }
        val msg = when {
            reason == "PREMIUM_REQUIRED" || (code == 403 && path.startsWith("/me/player")) ->
                "Für die Wiedergabe über HyperRadio ist Spotify Premium nötig"
            reason == "NO_ACTIVE_DEVICE" || code == 404 && path.startsWith("/me/player") ->
                "Spotify ist auf diesem Handy nicht bereit – öffne kurz die Spotify-App"
            code == 403 -> "Spotify verweigert den Zugriff (403) – ist dein Konto im Entwicklerportal unter „User Management“ eingetragen?"
            else -> "Spotify-Fehler $code ${err?.optString("message") ?: ""}".trim()
        }
        throw SpotifyException(code, reason, msg)
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (_: IOException) {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ Künstler

    fun displayName(): String? = runCatching {
        JSONObject(call("GET", "/me")).optString("display_name").takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun searchArtists(query: String, limit: Int = 10): List<SpotifyArtist> {
        if (query.isBlank()) return emptyList()
        val json = JSONObject(call("GET", "/search?type=artist&limit=$limit&q=${q(query.trim())}"))
        return parseArtists(json.optJSONObject("artists")?.optJSONArray("items"))
    }

    /**
     * GET mit Seitengröße: Spotify hat für Apps im Development Mode die erlaubte Seitengröße
     * mancher Endpunkte gesenkt (z. B. Suche: höchstens 10). Lehnt Spotify die große Seite ab,
     * wird für diesen Endpunkt dauerhaft auf 10 umgeschaltet.
     */
    private fun getPaged(key: String, path: (limit: Int) -> String): JSONObject {
        val limit = pageLimits[key] ?: 50
        return try {
            JSONObject(call("GET", path(limit)))
        } catch (e: SpotifyException) {
            if (e.status != 400 || limit <= 10) throw e
            pageLimits[key] = 10
            JSONObject(call("GET", path(10)))
        }
    }

    private val pageLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Gefolgte Künstler (alle Seiten). */
    fun followedArtists(max: Int = 500): List<SpotifyArtist> {
        val out = mutableListOf<SpotifyArtist>()
        var after: String? = null
        while (out.size < max) {
            val artists = getPaged("following") { l ->
                "/me/following?type=artist&limit=$l" + (after?.let { "&after=${q(it)}" } ?: "")
            }.optJSONObject("artists") ?: break
            out += parseArtists(artists.optJSONArray("items"))
            after = artists.optJSONObject("cursors")?.optString("after")?.takeIf { it.isNotEmpty() && it != "null" } ?: break
        }
        return out
    }

    /** Meistgehörte Künstler (ca. letztes halbes Jahr). */
    fun topArtists(): List<SpotifyArtist> {
        val out = mutableListOf<SpotifyArtist>()
        var offset = 0
        while (out.size < 50) {
            val items = getPaged("top") { l -> "/me/top/artists?limit=$l&offset=$offset&time_range=medium_term" }.optJSONArray("items")
            if (items == null || items.length() == 0) break
            out += parseArtists(items)
            offset += items.length()
        }
        return out
    }

    private fun parseArtists(items: JSONArray?): List<SpotifyArtist> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList()
            SpotifyArtist(
                id = o.optString("id"),
                name = o.optString("name"),
                genres = genres,
                followers = o.optJSONObject("followers")?.optInt("total") ?: 0,
            ).takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------ Titel

    /**
     * Bis zu [max] Titel-URIs eines Künstlers aus drei Quellen (jede darf fehlschlagen –
     * Spotify schränkt einzelne Endpunkte für neue Apps ein):
     * 1. seine bekanntesten Titel, 2. die Titelsuche, 3. seine Alben und Singles.
     * Nur Titel, an denen der Künstler wirklich beteiligt ist; verschiedene Fassungen desselben
     * Titels ("Live", "Remastered" …) zählen einmal. Liefert keine Quelle etwas, wird Spotifys
     * Fehler weitergereicht.
     */
    fun tracksOf(artist: SpotifyArtist, max: Int = 100): List<String> {
        val seenNames = HashSet<String>()
        val uris = LinkedHashSet<String>()
        val errors = mutableListOf<Exception>()
        fun add(track: JSONObject) {
            val artists = track.optJSONArray("artists") ?: return
            val involved = (0 until artists.length()).any { artists.optJSONObject(it)?.optString("id") == artist.id }
            if (!involved || track.optBoolean("is_playable", true).not()) return
            val base = track.optString("name").lowercase().substringBefore(" - ").substringBefore(" (").trim()
            val uri = track.optString("uri")
            if (uri.startsWith("spotify:track:") && seenNames.add(base)) uris += uri
        }
        fun addAll(items: JSONArray?) {
            if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let(::add)
        }

        // 1. bekannteste Titel (für neue Apps teils gesperrt – deshalb zählt dieser Fehler zuletzt)
        var topError: Exception? = null
        try {
            addAll(JSONObject(call("GET", "/artists/${artist.id}/top-tracks?market=from_token")).optJSONArray("tracks"))
        } catch (e: Exception) {
            topError = e
        }

        // 2. Titelsuche (erst mit Künstler-Filter, notfalls nur mit dem Namen)
        for (query in listOf("artist:\"${artist.name}\"", artist.name)) {
            var offset = 0
            var pages = 0
            while (uris.size < max && pages < 12 && offset < 300) {
                val items = try {
                    getPaged("search-track") { l -> "/search?type=track&limit=$l&offset=$offset&market=from_token&q=${q(query)}" }
                        .optJSONObject("tracks")?.optJSONArray("items")
                } catch (e: Exception) {
                    errors += e
                    null
                } ?: break
                if (items.length() == 0) break
                addAll(items)
                offset += items.length()
                pages++
            }
            if (uris.size >= 20) break
        }

        // 3. Alben und Singles
        if (uris.size < max / 2) {
            try {
                val albums = mutableListOf<String>()
                var offset = 0
                while (albums.size < 30) {
                    val items = getPaged("artist-albums") { l ->
                        "/artists/${artist.id}/albums?include_groups=album,single&market=from_token&limit=$l&offset=$offset"
                    }.optJSONArray("items")
                    if (items == null || items.length() == 0) break
                    for (i in 0 until items.length()) items.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(albums::add)
                    offset += items.length()
                }
                for (album in albums.shuffled()) {
                    if (uris.size >= max) break
                    addAll(getPaged("album-tracks") { l -> "/albums/$album/tracks?market=from_token&limit=$l" }.optJSONArray("items"))
                }
            } catch (e: Exception) {
                errors += e
            }
        }

        if (uris.isEmpty()) (errors.firstOrNull() ?: topError)?.let { throw it }
        return uris.take(max)
    }

    // ------------------------------------------------------------------ Wiedergabe

    fun devices(): List<SpotifyDevice> {
        val arr = JSONObject(call("GET", "/me/player/devices")).optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            SpotifyDevice(id, o.optString("name"), o.optString("type"), o.optBoolean("is_active"))
        }
    }

    fun play(deviceId: String, uris: List<String>) {
        // eigene Zufallsreihenfolge → Spotifys Shuffle aus, damit sie nicht nochmal umgewürfelt wird
        runCatching { call("PUT", "/me/player/shuffle?state=false&device_id=${q(deviceId)}") }
        call("PUT", "/me/player/play?device_id=${q(deviceId)}", JSONObject().put("uris", JSONArray(uris)))
        runCatching { call("PUT", "/me/player/repeat?state=off&device_id=${q(deviceId)}") }
    }

    fun pause() {
        call("PUT", "/me/player/pause")
    }

    fun resume() {
        call("PUT", "/me/player/play", JSONObject())
    }

    fun next() {
        call("POST", "/me/player/next")
    }

    fun playback(): SpotifyPlayback? {
        val text = call("GET", "/me/player")
        if (text.isBlank()) return null
        val o = JSONObject(text)
        val item = o.optJSONObject("item")
        val artists = item?.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it).optString("name") } }
        return SpotifyPlayback(
            track = item?.optString("name"),
            artists = artists,
            isPlaying = o.optBoolean("is_playing"),
            device = o.optJSONObject("device")?.optString("name"),
        )
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "pkce_state"
    }
}
