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
    val category: Category = Category.OTHER,
)

/** Ausklappbare Gruppen der Senderliste (in dieser Reihenfolge; auch die Tabs in Android Auto). */
enum class Category(val id: String, val title: String, val emoji: String) {
    ROCK("rock", "Rock", "🎸"),
    WORK("work", "Work", "💼"),
    OTHER("other", "Übrige", "📻"),
    ;

    companion object {
        fun fromId(id: String?): Category = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** Fest eingebaute Sender. */
object Presets {
    val all: List<Station> = listOf(
        Station(
            id = "kexp",
            category = Category.ROCK,
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
            category = Category.ROCK,
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
            category = Category.ROCK,
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
            category = Category.OTHER,
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
            category = Category.ROCK,
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
            category = Category.WORK,
            name = "Smooth Jazz Radio DE",
            subtitle = "Smooth Jazz · Lounge",
            emoji = "🎷",
            urls = listOf(
                "https://stream.laut.fm/pure-smooth-jazz",
                "https://pure-smooth-jazz.stream.laut.fm/pure-smooth-jazz",
                "http://stream.laut.fm/pure-smooth-jazz",
            ),
        ),
        Station(
            id = "somafm-deepspaceone",
            category = Category.WORK,
            name = "SomaFM – Deep Space One",
            subtitle = "SomaFM · Ambient & Space Music",
            emoji = "🌌",
            urls = listOf(
                "https://ice1.somafm.com/deepspaceone-128-mp3",
                "https://ice2.somafm.com/deepspaceone-128-mp3",
                "https://ice4.somafm.com/deepspaceone-128-mp3",
                "https://somafm.com/deepspaceone.pls",
            ),
        ),
        Station(
            id = "lofi-hiphop",
            category = Category.WORK,
            name = "Lofi Hip Hop Radio",
            subtitle = "Lofi Hip Hop · Beats zum Arbeiten",
            emoji = "🎧",
            urls = listOf(
                "https://stream.laut.fm/lofi",
                "https://play.streamafrica.net/lofiradio",
                "http://stream.laut.fm/lofi",
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
