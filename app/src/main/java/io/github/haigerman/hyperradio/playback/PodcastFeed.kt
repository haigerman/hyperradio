package io.github.haigerman.hyperradio.playback

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liest einen Podcast-Feed (RSS) und findet die neueste Folge. So spielen fest eingebaute
 * Podcasts immer die aktuelle Folge – die Feed-Adresse bleibt gleich, die Folgen wechseln.
 */
object PodcastFeed {

    data class Episode(val url: String, val title: String?, val published: Date?)

    /** Blockierend – nur im Hintergrund aufrufen. null, wenn der Feed nicht lesbar ist. */
    fun latest(feedUrl: String, userAgent: String): Episode? = try {
        parseLatest(download(feedUrl, userAgent))
    } catch (_: Exception) {
        null
    }

    /** Neueste Folge im Feed (nach Veröffentlichungsdatum; ohne Datum die erste). */
    fun parseLatest(xml: String): Episode? {
        val items = ITEM.findAll(xml).take(MAX_ITEMS).mapNotNull { parseItem(it.groupValues[1]) }.toList()
        if (items.isEmpty()) return null
        return items.maxByOrNull { it.published?.time ?: Long.MIN_VALUE }
            ?.takeIf { it.published != null } ?: items.first()
    }

    private fun parseItem(item: String): Episode? {
        val enclosures = ENCLOSURE.findAll(item).map { attributes(it.groupValues[1]) }.toList()
        // Audio bevorzugen (manche Feeds hängen zusätzlich ein Video an)
        val enclosure = enclosures.firstOrNull { it["type"]?.startsWith("audio", ignoreCase = true) == true && !it["url"].isNullOrBlank() }
            ?: enclosures.firstOrNull { !it["url"].isNullOrBlank() }
            ?: return null
        val url = unescape(enclosure.getValue("url")).trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val title = TITLE.find(item)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotBlank() }
        val date = PUB_DATE.find(item)?.groupValues?.get(1)?.let { parseDate(text(it)) }
        return Episode(url, title, date)
    }

    private fun attributes(attrs: String): Map<String, String> =
        ATTRIBUTE.findAll(attrs).associate { m -> m.groupValues[1].lowercase() to (m.groupValues[3].ifEmpty { m.groupValues[4] }) }

    /** Text eines Elements: CDATA auspacken, Entities ersetzen, Leerraum glätten. */
    private fun text(raw: String): String {
        val s = raw.trim()
        val inner = CDATA.find(s)?.groupValues?.get(1) ?: s
        return unescape(inner).replace(Regex("\\s+"), " ").trim()
    }

    internal fun unescape(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun parseDate(s: String): Date? {
        for (pattern in DATE_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(s)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun download(url: String, userAgent: String): String {
        var target = URL(url)
        var redirects = 0
        while (true) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                // immer frisch vom Server – nie einen zwischengespeicherten (veralteten) Feed
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Weiterleitung ohne Ziel")
                target = URL(target, loc) // auch http <-> https
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) throw IllegalStateException("Feed: HTTP $code")
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                }
                out.toString(Charsets.UTF_8.name())
            }.also { conn.disconnect() }
        }
    }

    private const val MAX_ITEMS = 60
    private const val MAX_BYTES = 3 * 1024 * 1024
    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENCLOSURE = Regex("<enclosure\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val TITLE = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PUB_DATE = Regex("<pubDate\\b[^>]*>(.*?)</pubDate>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CDATA = Regex("^<!\\[CDATA\\[(.*?)]]>$", RegexOption.DOT_MATCHES_ALL)
    private val DATE_PATTERNS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z")
}
