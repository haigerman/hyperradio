package io.github.haigerman.hyperradio.data

import java.net.URI

/**
 * Ein Radiosender. [urls] enthält die Stream-Adresse(n) in der Reihenfolge, in der sie
 * probiert werden: fällt die erste aus, wechselt die Wiedergabe automatisch zur nächsten.
 */
data class Station(
    val id: String,
    val name: String,
    val urls: List<String>,
    val subtitle: String,
    val emoji: String,
    val custom: Boolean = false,
    /** id der Kategorie (siehe [Category]); unbekannte ids landen bei "Übrige" */
    val categoryId: String = Category.OTHER_ID,
)

/**
 * Ausklappbare Gruppe der Senderliste (in Android Auto: Tabs). Die Nutzer können Kategorien
 * anlegen, umbenennen, umsortieren und löschen – nur "Übrige" bleibt als Sammelstelle immer da.
 */
data class Category(val id: String, val title: String, val emoji: String) {
    /** "Übrige": nimmt die Sender gelöschter Kategorien auf und kann nicht gelöscht werden */
    val isFallback: Boolean get() = id == OTHER_ID

    companion object {
        const val ROCK_ID = "rock"
        const val WORK_ID = "work"
        const val PARADISE_ID = "paradise"
        const val OTHER_ID = "other"

        val defaults = listOf(
            Category(ROCK_ID, "Rock", "🎸"),
            Category(WORK_ID, "Work", "💼"),
            Category(PARADISE_ID, "Paradise", "🌴"),
            Category(OTHER_ID, "Übrige", "📻"),
        )

        /** Auswahl für das Symbol einer Kategorie */
        val emojis = listOf("🎸", "💼", "🌴", "📻", "🎷", "🎧", "🎹", "🎻", "🥁", "🌙", "☕", "🏃", "🚗", "🎉", "🌲", "🌊", "⭐", "❤️", "🔥")
    }
}

/** Fest eingebaute Sender. */
object Presets {
    val all: List<Station> = listOf(
        Station(
            id = "kexp",
            categoryId = Category.ROCK_ID,
            name = "KEXP 90.3 FM",
            subtitle = "Seattle · Indie & Alternative",
            emoji = "🌲",
            urls = listOf(
                "https://kexp.streamguys1.com/kexp160.aac",
                "https://kexp-mp3-128.streamguys1.com/kexp128.mp3",
                "https://kexp.streamguys1.com/kexp64.aac",
            ),
        ),
        Station(
            id = "bob-live",
            categoryId = Category.ROCK_ID,
            name = "Radio Bob Live",
            subtitle = "RADIO BOB! · Rock",
            emoji = "🤘",
            urls = listOf(
                "https://streams.radiobob.de/bob-live/mp3-192/mediaplayer",
                "https://streams.radiobob.de/bob-live/mp3-128/mediaplayer",
                "http://streams.radiobob.de/bob-live/mp3-192/mediaplayer",
            ),
        ),
        Station(
            id = "bob-grunge",
            categoryId = Category.ROCK_ID,
            name = "Radio Bob Grunge",
            subtitle = "RADIO BOB! · Grunge",
            emoji = "🎸",
            urls = listOf(
                "https://streams.radiobob.de/bob-grunge/mp3-192/mediaplayer",
                "https://streams.radiobob.de/bob-grunge/mp3-128/mediaplayer",
                "http://streams.radiobob.de/bob-grunge/mp3-192/mediaplayer",
            ),
        ),
        Station(
            id = "bayern1",
            categoryId = Category.OTHER_ID,
            name = "Bayern 1",
            subtitle = "Bayerischer Rundfunk · Oberbayern",
            emoji = "🏔️",
            urls = listOf(
                "https://streams.br.de/bayern1obb_2.m3u",
                "https://dispatcher.rndfnk.com/br/br1/obb/mp3/high",
                "https://dispatcher.rndfnk.com/br/br1/obb/mp3/mid",
                "http://dispatcher.rndfnk.com/br/br1/obb/mp3/mid",
            ),
        ),
        Station(
            id = "virgin",
            categoryId = Category.ROCK_ID,
            name = "Virgin Radio",
            subtitle = "Virgin Radio Switzerland · Rock",
            emoji = "🎧",
            urls = listOf(
                "https://stream.streambase.ch/vrock/mp3-192/direct/",
                "https://stream.streambase.ch/vrock/mp3-128/direct/",
                "http://stream.streambase.ch/vrock/mp3-192/direct/",
            ),
        ),
        Station(
            id = "smooth-jazz",
            categoryId = Category.WORK_ID,
            name = "smoothjazz.com",
            subtitle = "SmoothJazz.com · Smooth Jazz",
            emoji = "🎷",
            urls = listOf(
                "https://smoothjazz.cdnstream1.com/2585_320.mp3",
                "https://smoothjazz.cdnstream1.com/2585_128.mp3",
                "https://smoothjazz.cdnstream1.com/2585_256.mp3",
            ),
        ),
        Station(
            id = "lofi-hiphop",
            categoryId = Category.WORK_ID,
            name = "Lofi Hip Hop Radio",
            subtitle = "Lofi Hip Hop · Beats zum Arbeiten",
            emoji = "🎧",
            urls = listOf(
                "https://stream.laut.fm/lofi",
                "https://play.streamafrica.net/lofiradio",
                "http://stream.laut.fm/lofi",
            ),
        ),
        Station(
            id = "rp-main",
            categoryId = Category.PARADISE_ID,
            name = "Radio Paradise – Main Mix",
            subtitle = "Radio Paradise · Rock, Indie, Pop & mehr",
            emoji = "🌴",
            urls = listOf(
                "https://stream.radioparadise.com/mp3-320",
                "https://stream.radioparadise.com/aac-320",
                "https://stream.radioparadise.com/flac",
            ),
        ),
        Station(
            id = "rp-mellow",
            categoryId = Category.PARADISE_ID,
            name = "Radio Paradise – Mellow Mix",
            subtitle = "Radio Paradise · ruhig & entspannt",
            emoji = "🌅",
            urls = listOf(
                "https://stream.radioparadise.com/mellow-320",
                "https://stream.radioparadise.com/mellow-flac",
            ),
        ),
        Station(
            id = "rp-rock",
            categoryId = Category.PARADISE_ID,
            name = "Radio Paradise – Rock Mix",
            subtitle = "Radio Paradise · Rock",
            emoji = "🌋",
            urls = listOf(
                "https://stream.radioparadise.com/rock-320",
                "https://stream.radioparadise.com/rock-flac",
            ),
        ),
    )
}

/** Gültige Stream-Adresse? (nur http/https mit Host) */
fun isValidStreamUrl(url: String): Boolean = try {
    val u = URI(url.trim())
    (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

/** Hostname für die Unterzeile eigener Sender, z. B. "stream.example.com". */
fun hostOf(url: String): String = try {
    URI(url.trim()).host?.removePrefix("www.") ?: url
} catch (_: Exception) {
    url
}
