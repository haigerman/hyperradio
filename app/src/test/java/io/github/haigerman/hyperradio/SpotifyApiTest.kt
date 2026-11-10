package io.github.haigerman.hyperradio

import android.content.SharedPreferences
import io.github.haigerman.hyperradio.spotify.SpotifyApi
import io.github.haigerman.hyperradio.spotify.SpotifyArtist
import io.github.haigerman.hyperradio.spotify.SpotifyException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.URLDecoder

/**
 * Prüft die Titelsuche gegen einen nachgebauten Spotify-Server, der sich wie die eingeschränkte
 * API für Apps im Development Mode verhält: Top-Titel gesperrt, höchstens 10 Treffer pro Seite.
 */
class SpotifyApiTest {

    private lateinit var server: ServerSocket
    private val requests = mutableListOf<String>()
    private var maxLimit = 10
    private var topTracksAllowed = false
    private var searchAllowed = true

    // Wiedergabe-Simulation
    private var transferred = false
    private var playingContext: String? = null
    private var ignorePlay = false
    private var playlistItems: String? = null
    private var meCreateAllowed = true

    private val artist = SpotifyArtist("art1", "The Band")

    private fun track(i: Int, artistId: String = "art1") =
        """{"name":"Song $i","uri":"spotify:track:t$i","is_playable":true,"artists":[{"id":"$artistId"}]}"""

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { socket.use { handle(it) } }
            }
        }
    }

    private fun handle(socket: java.net.Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine()?.split(" ") ?: return
        val method = requestLine[0]
        val target = requestLine.getOrNull(1) ?: return
        var length = 0
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(":").trim().toInt()
        }
        // Content-Length zählt Bytes, der Reader liefert Zeichen (Umlaute/„–“ sind mehrere Bytes)
        val sb = StringBuilder()
        var bytes = 0
        while (bytes < length) {
            val c = reader.read()
            if (c < 0) break
            sb.append(c.toChar())
            bytes += c.toChar().toString().toByteArray().size
        }
        val body = sb.toString()
        val path = target.substringBefore("?").removePrefix("/v1")
        val rawQuery = target.substringAfter("?", "")
        run {
            val params = rawQuery.split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            synchronized(requests) { requests += "$path?$rawQuery" }
            val limit = params["limit"]?.toInt() ?: 0
            val offset = params["offset"]?.toInt() ?: 0
            val (code, body) = when {
                limit > maxLimit -> 400 to """{"error":{"status":400,"message":"Invalid limit"}}"""
                path.endsWith("/top-tracks") ->
                    if (topTracksAllowed) 200 to """{"tracks":[${(1..10).joinToString(",") { track(it) }}]}"""
                    else 403 to """{"error":{"status":403,"message":"Forbidden"}}"""
                path == "/search" && !searchAllowed -> 403 to """{"error":{"status":403,"message":"Forbidden"}}"""
                path == "/search" -> {
                    // 35 Titel des Künstlers, dazwischen fremde Titel und eine Live-Fassung (Duplikat)
                    val all = (1..35).map { track(it) } + track(900, "other") + """{"name":"Song 1 - Live","uri":"spotify:track:live1","artists":[{"id":"art1"}]}"""
                    val page = all.drop(offset).take(limit)
                    200 to """{"tracks":{"items":[${page.joinToString(",")}]}}"""
                }
                path == "/artists/art1/albums" -> {
                    val all = (1..3).map { """{"id":"alb$it"}""" }
                    200 to """{"items":[${all.drop(offset).take(limit).joinToString(",")}]}"""
                }
                path.startsWith("/albums/") && path.endsWith("/tracks") -> {
                    val n = path.removePrefix("/albums/alb").removeSuffix("/tracks").toInt()
                    200 to """{"items":[${(1..5).joinToString(",") { track(100 * n + it) }}]}"""
                }
                path == "/me/player" && method == "PUT" -> {
                    transferred = true
                    204 to ""
                }
                path == "/me/player/play" -> when {
                    !transferred -> 404 to """{"error":{"status":404,"message":"Device not found"}}"""
                    else -> {
                        if (!ignorePlay) playingContext = Regex("\"context_uri\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                        204 to ""
                    }
                }
                path == "/me/player" && method == "GET" ->
                    if (playingContext == null) 204 to ""
                    else 200 to """{"is_playing":true,"device":{"id":"dev1"},"context":{"uri":"$playingContext"}}"""
                path == "/me/playlists" && method == "POST" ->
                    if (meCreateAllowed) 201 to """{"id":"pl1"}""" else 404 to """{"error":{"status":404,"message":"Not found"}}"""
                path == "/me" -> 200 to """{"id":"user1","display_name":"Test"}"""
                path == "/users/user1/playlists" && method == "POST" -> 201 to """{"id":"pl2"}"""
                path.matches(Regex("/playlists/pl[12]")) -> 200 to """{"id":"pl"}"""
                path.matches(Regex("/playlists/pl[12]/items")) && method == "PUT" -> {
                    playlistItems = body
                    200 to """{"snapshot_id":"x"}"""
                }
                path.startsWith("/me/player/") -> 204 to ""
                else -> 404 to """{"error":{"status":404,"message":"Not found"}}"""
            }
            val bytes = body.toByteArray()
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    @After
    fun stop() = server.close()

    private fun api() = SpotifyApi(FakePrefs(), "http://127.0.0.1:${server.localPort}/v1", sleep = {})

    private val mix = (1..100).map { "spotify:track:m$it" }

    @Test
    fun createsPlaylistFillsItAndStartsIt() {
        val api = api()
        val id = api.ensureMixPlaylist()
        assertEquals("pl1", id)
        api.replacePlaylistItems(id, mix)
        assertEquals(100, Regex("spotify:track:").findAll(playlistItems!!).count())
        assertTrue(api.startPlayback("dev1", "spotify:playlist:pl1"))
        assertEquals("spotify:playlist:pl1", playingContext)
        // erst übertragen, dann abspielen
        val firstTransfer = requests.indexOfFirst { it.startsWith("/me/player?") }
        val firstPlay = requests.indexOfFirst { it.startsWith("/me/player/play") }
        assertTrue(firstTransfer in 0 until firstPlay)
        // zweiter Aufruf nutzt dieselbe Playlist
        assertEquals("pl1", api.ensureMixPlaylist())
        assertEquals(1, requests.count { it.startsWith("/me/playlists") })
    }

    @Test
    fun fallsBackToUserPlaylistEndpoint() {
        meCreateAllowed = false
        assertEquals("pl2", api().ensureMixPlaylist())
    }

    @Test
    fun reportsWhenSpotifyAcceptsButDoesNotPlay() {
        ignorePlay = true
        assertEquals(false, api().startPlayback("dev1", "spotify:playlist:pl1"))
    }

    @Test
    fun fallsBackToSmallPagesWhenTopTracksAreForbidden() {
        val uris = api().tracksOf(artist)
        // 35 Suchtreffer (ohne fremden Titel, Live-Fassung zusammengefasst) + 15 Albumtitel
        assertEquals(50, uris.size)
        assertTrue("spotify:track:t900" !in uris)
        assertTrue("spotify:track:live1" !in uris)
        // nach der ersten Ablehnung nur noch kleine Seiten
        assertEquals(1, requests.count { it.startsWith("/search") && it.contains("limit=50") })
    }

    @Test
    fun usesAlbumsWhenSearchIsForbidden() {
        searchAllowed = false
        val uris = api().tracksOf(artist)
        assertEquals(15, uris.size)
    }

    @Test
    fun reportsSpotifysErrorWhenNothingWorks() {
        searchAllowed = false
        maxLimit = 0 // alles wird abgelehnt
        try {
            api().tracksOf(artist)
            fail("Fehler erwartet")
        } catch (e: SpotifyException) {
            assertEquals(400, e.status)
            assertTrue(e.message!!.contains("Invalid limit"))
        }
    }

    @Test
    fun worksWithFullApiToo() {
        maxLimit = 50
        topTracksAllowed = true
        val uris = api().tracksOf(artist)
        assertEquals(50, uris.size)
        assertTrue(uris.take(10).all { it.removePrefix("spotify:track:t").toInt() <= 10 })
    }
}

/** SharedPreferences im Speicher, mit gültigem Token. */
private class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>(
        "access_token" to "test",
        "refresh_token" to "refresh",
        "expires_at" to Long.MAX_VALUE,
    )

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(k: String, v: String?) = apply { map[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { map[k] = v }
        override fun putInt(k: String, v: Int) = apply { map[k] = v }
        override fun putLong(k: String, v: Long) = apply { map[k] = v }
        override fun putFloat(k: String, v: Float) = apply { map[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { map[k] = v }
        override fun remove(k: String) = apply { map.remove(k) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
