package io.github.haigerman.hyperradio.playback

import java.net.HttpURLConnection
import java.net.URL

/**
 * Viele Sender veröffentlichen statt der eigentlichen Stream-Adresse eine Playlist-Datei
 * (.m3u / .pls). Der Player braucht aber den Stream selbst – deshalb wird die Playlist hier
 * geladen und die erste Stream-Adresse daraus genommen. HLS (.m3u8) spielt der Player direkt.
 */
object StreamResolver {

    fun looksLikePlaylist(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u") || path.endsWith(".pls")
    }

    fun isHls(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    /** Blockierend – nur im Hintergrund aufrufen. Gibt bei Problemen die ursprüngliche URL zurück. */
    fun resolve(url: String, userAgent: String): String {
        if (!looksLikePlaylist(url)) return url
        return try {
            var target = URL(url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (target.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", userAgent)
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    // auch http <-> https folgen (macht HttpURLConnection selbst nicht)
                    val loc = conn.getHeaderField("Location") ?: break
                    target = URL(target, loc)
                    conn.disconnect()
                    redirects++
                    continue
                }
                break
            }
            val text = conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
            conn.disconnect()
            parsePlaylist(text) ?: url
        } catch (_: Exception) {
            url
        }
    }

    /** Erste Stream-Adresse aus einer M3U- oder PLS-Playlist. */
    fun parsePlaylist(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val candidate = if (line.contains('=') && line.substringBefore('=').trim().startsWith("File", ignoreCase = true)) {
                line.substringAfter('=').trim()
            } else {
                line
            }
            if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
        }
        return null
    }
}
